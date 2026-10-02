/**
 * trip-photo-manager.js — TravelLink AI 旅程照片整理、離線佇列與多人同步底層
 *
 * 使用方式（載入此檔後）：
 *   TripPhotoManager.configure({
 *     upload: async function (blob, photo) {
 *       // 上傳 Firebase Storage，回傳可序列化結果。
 *       return { url: 'https://...', path: 'trip-photos/...' };
 *     },
 *     publish: async function (photo) {
 *       // 將 photo（不含 blob）寫入 micro_trips/{tripId}/photos/{photo.id}。
 *     },
 *     subscribe: function (tripId, onPhotos, onError) {
 *       // 接上 Firestore onSnapshot；回傳 unsubscribe。
 *     }
 *   });
 *
 *   var result = await TripPhotoManager.enqueue(input.files, {
 *     tripId: currentItineraryId,
 *     stops: itinerary.stops,
 *     owner: { uid: user.uid, name: user.displayName, email: user.email },
 *     role: 'editor'
 *   });
 *
 * 此模組刻意不直接依賴 Firebase，也不自行建立 Service Worker。既有上傳流程只需注入
 * upload / publish / subscribe adapter；不支援 Background Sync 的瀏覽器仍會在 online、
 * pageshow、visibilitychange 時續傳。
 */
window.TripPhotoManager = (function () {
  'use strict';

  var DB_NAME = 'travellink-trip-photos';
  var DB_VERSION = 1;
  var STORE = 'photos';
  var SYNC_TAG = 'travellink-trip-photo-upload';
  var STATUS = Object.freeze({
    QUEUED: 'queued',
    UPLOADING: 'uploading',
    PUBLISHING: 'publishing',
    SYNCED: 'synced',
    FAILED: 'failed',
    LOCAL_ONLY: 'local-only'
  });
  // 短狀態標籤；UI 顯示時應套 white-space: nowrap。
  var STATUS_LABELS = Object.freeze({
    queued: '等待上傳',
    uploading: '上傳中',
    publishing: '同步中',
    synced: '已同步',
    failed: '同步失敗',
    'local-only': '僅存在此裝置'
  });
  var config = {
    upload: null,
    publish: null,
    subscribe: null,
    removeRemote: null,
    logger: null,
    maxAttempts: 8,
    retryBaseMs: 4000
  };
  var dbPromise = null;
  var retryPromise = null;
  var listeners = new Set();
  var remoteUnsubscribers = new Map();
  var lifecycleBound = false;

  function log(level, message, detail) {
    if (typeof config.logger === 'function') {
      try { config.logger(level, message, detail); } catch (_e) {}
      return;
    }
    if (level === 'error' && window.console && console.warn) console.warn(message, detail || '');
  }

  function emit(type, detail) {
    var event = { type: type, detail: detail || {}, at: Date.now() };
    listeners.forEach(function (listener) {
      try { listener(event); } catch (error) { log('error', '照片事件監聽器執行失敗。', error); }
    });
    if (typeof window.CustomEvent === 'function') {
      window.dispatchEvent(new CustomEvent('trip-photo-manager:' + type, { detail: event.detail }));
    }
  }

  function subscribe(listener) {
    if (typeof listener !== 'function') throw new TypeError('listener 必須是函式。');
    listeners.add(listener);
    return function () { listeners.delete(listener); };
  }

  function uuid() {
    if (window.crypto && typeof window.crypto.randomUUID === 'function') return window.crypto.randomUUID();
    var bytes = new Uint8Array(16);
    if (window.crypto && window.crypto.getRandomValues) window.crypto.getRandomValues(bytes);
    else for (var i = 0; i < bytes.length; i++) bytes[i] = Math.floor(Math.random() * 256);
    bytes[6] = (bytes[6] & 15) | 64;
    bytes[8] = (bytes[8] & 63) | 128;
    var hex = Array.prototype.map.call(bytes, function (b) { return b.toString(16).padStart(2, '0'); }).join('');
    return [hex.slice(0, 8), hex.slice(8, 12), hex.slice(12, 16), hex.slice(16, 20), hex.slice(20)].join('-');
  }

  function openDb() {
    if (dbPromise) return dbPromise;
    dbPromise = new Promise(function (resolve, reject) {
      if (!window.indexedDB) {
        reject(new Error('此瀏覽器不支援 IndexedDB，無法安全保存待上傳照片。'));
        return;
      }
      var request = window.indexedDB.open(DB_NAME, DB_VERSION);
      request.onupgradeneeded = function () {
        var db = request.result;
        var store = db.objectStoreNames.contains(STORE)
          ? request.transaction.objectStore(STORE)
          : db.createObjectStore(STORE, { keyPath: 'id' });
        if (!store.indexNames.contains('tripId')) store.createIndex('tripId', 'tripId', { unique: false });
        if (!store.indexNames.contains('status')) store.createIndex('status', 'status', { unique: false });
        if (!store.indexNames.contains('hash')) store.createIndex('hash', 'hash', { unique: false });
        if (!store.indexNames.contains('updatedAt')) store.createIndex('updatedAt', 'updatedAt', { unique: false });
      };
      request.onsuccess = function () {
        var db = request.result;
        db.onversionchange = function () { db.close(); dbPromise = null; };
        resolve(db);
      };
      request.onerror = function () { reject(request.error || new Error('無法開啟照片離線資料庫。')); };
      request.onblocked = function () { emit('storage-blocked', {}); };
    });
    return dbPromise;
  }

  async function withStore(mode, operation) {
    var db = await openDb();
    return new Promise(function (resolve, reject) {
      var tx = db.transaction(STORE, mode);
      var store = tx.objectStore(STORE);
      var result;
      try { result = operation(store, tx); } catch (error) { reject(error); return; }
      tx.oncomplete = function () { resolve(result); };
      tx.onerror = function () { reject(tx.error || new Error('照片資料庫操作失敗。')); };
      tx.onabort = function () { reject(tx.error || new Error('照片資料庫操作已取消。')); };
    });
  }

  async function put(photo) {
    photo.updatedAt = Date.now();
    await withStore('readwrite', function (store) { store.put(photo); });
    return photo;
  }

  async function get(id) {
    var db = await openDb();
    return new Promise(function (resolve, reject) {
      var tx = db.transaction(STORE, 'readonly');
      var request = tx.objectStore(STORE).get(String(id));
      request.onsuccess = function () { resolve(request.result || null); };
      request.onerror = function () { reject(request.error); };
    });
  }

  async function getAll() {
    var db = await openDb();
    return new Promise(function (resolve, reject) {
      var tx = db.transaction(STORE, 'readonly');
      var request = tx.objectStore(STORE).getAll();
      request.onsuccess = function () { resolve(request.result || []); };
      request.onerror = function () { reject(request.error); };
    });
  }

  async function list(query) {
    query = query || {};
    var photos = await getAll();
    photos = photos.filter(function (photo) {
      if (query.tripId != null && String(photo.tripId) !== String(query.tripId)) return false;
      if (query.dayKey != null && String(photo.dayKey || '') !== String(query.dayKey)) return false;
      if (query.stopId != null && String(photo.stopId || '') !== String(query.stopId)) return false;
      if (query.ownerUid != null && String(photo.ownerUid || '') !== String(query.ownerUid)) return false;
      if (query.status != null) {
        var statuses = Array.isArray(query.status) ? query.status : [query.status];
        if (statuses.indexOf(photo.status) === -1) return false;
      }
      return true;
    });
    return stableSort(photos);
  }

  function deleteLocal(id) {
    return withStore('readwrite', function (store) { store.delete(String(id)); });
  }

  function arrayBufferOf(blob) {
    if (blob && typeof blob.arrayBuffer === 'function') return blob.arrayBuffer();
    return new Promise(function (resolve, reject) {
      var reader = new FileReader();
      reader.onload = function () { resolve(reader.result); };
      reader.onerror = function () { reject(reader.error || new Error('無法讀取檔案。')); };
      reader.readAsArrayBuffer(blob);
    });
  }

  // EXIF 的偏移標籤（OffsetTimeOriginal 0x9011 / OffsetTimeDigitized 0x9012），格式如 "+08:00"。
  // 回傳分鐘數；認不出來就回 null。
  function exifOffsetToMinutes(value) {
    var match = String(value || '').trim().match(/^([+-])(\d{2}):?(\d{2})$/);
    if (!match) return null;
    var minutes = (+match[2]) * 60 + (+match[3]);
    return match[1] === '-' ? -minutes : minutes;
  }

  // DateTimeOriginal 是「牆上時間」，本身不帶時區。
  //   有 OffsetTimeOriginal → 用它換算成絕對時間，跨時區才正確（新一點的手機都會寫）
  //   沒有                  → 只能假設拍攝裝置與此刻的裝置同一時區（timeAssumption: 'device-local'）
  function exifDateToEpoch(value, offsetMinutes) {
    var match = String(value || '').trim().match(/^(\d{4}):(\d{2}):(\d{2})[ T](\d{2}):(\d{2}):(\d{2})/);
    if (!match) return null;
    var epoch;
    if (Number.isFinite(offsetMinutes)) {
      epoch = Date.UTC(+match[1], +match[2] - 1, +match[3], +match[4], +match[5], +match[6])
        - offsetMinutes * 60000;
    } else {
      epoch = new Date(+match[1], +match[2] - 1, +match[3], +match[4], +match[5], +match[6]).getTime();
    }
    return Number.isFinite(epoch) ? epoch : null;
  }

  function parseExif(arrayBuffer) {
    var view = new DataView(arrayBuffer);
    if (view.byteLength < 4 || view.getUint16(0, false) !== 0xffd8) return null;
    var offset = 2;
    while (offset + 4 <= view.byteLength) {
      if (view.getUint8(offset) !== 0xff) break;
      var marker = view.getUint8(offset + 1);
      offset += 2;
      if (marker === 0xd8 || marker === 0x01) continue;
      if (marker === 0xd9 || marker === 0xda) break;
      if (offset + 2 > view.byteLength) break;
      var length = view.getUint16(offset, false);
      if (length < 2 || offset + length > view.byteLength) break;
      if (marker === 0xe1 && length >= 8
        && view.getUint8(offset + 2) === 0x45 && view.getUint8(offset + 3) === 0x78
        && view.getUint8(offset + 4) === 0x69 && view.getUint8(offset + 5) === 0x66) {
        return parseTiff(view, offset + 8, offset + length);
      }
      offset += length;
    }
    return null;
  }

  function parseTiff(view, tiffStart, sectionEnd) {
    if (tiffStart + 8 > sectionEnd) return null;
    var byteOrder = view.getUint16(tiffStart, false);
    var little = byteOrder === 0x4949;
    if (!little && byteOrder !== 0x4d4d) return null;
    function u16(at) { if (at < tiffStart || at + 2 > sectionEnd) throw new RangeError('EXIF 範圍錯誤'); return view.getUint16(at, little); }
    function u32(at) { if (at < tiffStart || at + 4 > sectionEnd) throw new RangeError('EXIF 範圍錯誤'); return view.getUint32(at, little); }
    function entryValueOffset(entry, count, unitSize) {
      var byteLength = count * unitSize;
      var at = byteLength <= 4 ? entry + 8 : tiffStart + u32(entry + 8);
      if (at < tiffStart || at + byteLength > sectionEnd) throw new RangeError('EXIF 值超出範圍');
      return at;
    }
    function ascii(entry, count) {
      var at = entryValueOffset(entry, count, 1);
      var result = '';
      for (var i = 0; i < count && view.getUint8(at + i); i++) result += String.fromCharCode(view.getUint8(at + i));
      return result.trim();
    }
    function rationals(entry, count) {
      var at = entryValueOffset(entry, count, 8);
      var result = [];
      for (var i = 0; i < count; i++) {
        var numerator = u32(at + i * 8);
        var denominator = u32(at + i * 8 + 4);
        result.push(denominator ? numerator / denominator : 0);
      }
      return result;
    }
    function readIfd(relativeOffset) {
      var start = tiffStart + relativeOffset;
      var count = u16(start);
      if (start + 2 + count * 12 > sectionEnd) throw new RangeError('EXIF IFD 超出範圍');
      var entries = {};
      for (var i = 0; i < count; i++) {
        var entry = start + 2 + i * 12;
        entries[u16(entry)] = { entry: entry, type: u16(entry + 2), count: u32(entry + 4) };
      }
      return entries;
    }
    function pointer(entries, tag) {
      var value = entries[tag];
      return value ? u32(value.entry + 8) : null;
    }
    function readAsciiTag(entries, tag) {
      var value = entries[tag];
      return value && value.type === 2 ? ascii(value.entry, value.count) : '';
    }
    try {
      if (u16(tiffStart + 2) !== 42) return null;
      var ifd0 = readIfd(u32(tiffStart + 4));
      var exifIfd = pointer(ifd0, 0x8769);
      var gpsIfd = pointer(ifd0, 0x8825);
      var exif = exifIfd != null ? readIfd(exifIfd) : {};
      // 日期與對應的偏移標籤要配成對：DateTimeOriginal 配 0x9011、DateTimeDigitized 配 0x9012。
      // 拿錯配對比沒有偏移更糟（會把時間往錯的方向推）。
      var rawDate = readAsciiTag(exif, 0x9003);
      var rawOffset = rawDate ? readAsciiTag(exif, 0x9011) : '';
      if (!rawDate) {
        rawDate = readAsciiTag(exif, 0x9004);
        rawOffset = rawDate ? readAsciiTag(exif, 0x9012) : '';
      }
      if (!rawDate) { rawDate = readAsciiTag(ifd0, 0x0132); rawOffset = ''; }
      var offsetMinutes = exifOffsetToMinutes(rawOffset);
      var coords = null;
      if (gpsIfd != null) {
        var gps = readIfd(gpsIfd);
        var latRef = readAsciiTag(gps, 0x0001);
        var lngRef = readAsciiTag(gps, 0x0003);
        var latValue = gps[0x0002];
        var lngValue = gps[0x0004];
        if (latValue && lngValue && latValue.type === 5 && lngValue.type === 5) {
          var latParts = rationals(latValue.entry, Math.min(3, latValue.count));
          var lngParts = rationals(lngValue.entry, Math.min(3, lngValue.count));
          if (latParts.length === 3 && lngParts.length === 3) {
            var lat = latParts[0] + latParts[1] / 60 + latParts[2] / 3600;
            var lng = lngParts[0] + lngParts[1] / 60 + lngParts[2] / 3600;
            if (latRef.toUpperCase() === 'S') lat *= -1;
            if (lngRef.toUpperCase() === 'W') lng *= -1;
            if (Math.abs(lat) <= 90 && Math.abs(lng) <= 180) coords = { lat: lat, lng: lng };
          }
        }
      }
      return {
        rawDate: rawDate || null,
        rawOffset: rawOffset || null,
        offsetMinutes: offsetMinutes,
        capturedAt: exifDateToEpoch(rawDate, offsetMinutes),
        coords: coords
      };
    } catch (_error) {
      return null;
    }
  }

  async function extractMetadata(file) {
    var result = {
      capturedAt: null,
      timeSource: 'missing',
      timeAssumption: null,
      coords: null,
      locationSource: 'missing',
      exifFound: false,
      fallbackUsed: false,
      warnings: []
    };
    var isJpeg = file && (/image\/jpeg/i.test(file.type || '') || /\.jpe?g$/i.test(file.name || ''));
    if (isJpeg) {
      try {
        var parsed = parseExif(await arrayBufferOf(file));
        if (parsed) {
          result.exifFound = true;
          result.rawExifDate = parsed.rawDate;
          if (parsed.capturedAt) {
            result.capturedAt = parsed.capturedAt;
            result.timeSource = 'exif';
            // 有 EXIF 偏移就是絕對時間，沒有才是「假設同裝置時區」
            result.timeAssumption = Number.isFinite(parsed.offsetMinutes) ? 'exif-offset' : 'device-local';
            result.exifOffset = parsed.rawOffset || null;
          }
          if (parsed.coords) {
            result.coords = parsed.coords;
            result.locationSource = 'exif';
          }
        }
      } catch (_error) {
        result.warnings.push('無法解析 EXIF，已改用檔案資訊。');
      }
    } else {
      result.warnings.push('此格式未提供 JPEG EXIF 解析，已改用檔案資訊。');
    }
    if (!result.capturedAt && file && Number(file.lastModified) > 0) {
      result.capturedAt = Number(file.lastModified);
      result.timeSource = 'file-last-modified';
      result.fallbackUsed = true;
      result.warnings.push('照片缺少拍攝時間，目前使用檔案修改時間，建議使用者確認。');
    }
    if (!result.coords) result.warnings.push('照片缺少位置資訊，將以時間分類或放入待整理。');
    return result;
  }

  function bytesToHex(bytes) {
    return Array.prototype.map.call(bytes, function (b) { return b.toString(16).padStart(2, '0'); }).join('');
  }

  async function computeHash(blob) {
    var buffer = await arrayBufferOf(blob);
    if (window.crypto && window.crypto.subtle) {
      var digest = await window.crypto.subtle.digest('SHA-256', buffer);
      return 'sha256:' + bytesToHex(new Uint8Array(digest));
    }
    // 舊瀏覽器退路，只用於「可能重複」提示，不應作安全用途。
    var bytes = new Uint8Array(buffer);
    var hash = 2166136261;
    for (var i = 0; i < bytes.length; i++) {
      hash ^= bytes[i];
      hash = Math.imul(hash, 16777619);
    }
    return 'weak-fnv1a:' + (hash >>> 0).toString(16).padStart(8, '0') + ':' + bytes.length;
  }

  function numberOrNull(value) {
    if (value == null || value === '') return null;
    var number = Number(value);
    return Number.isFinite(number) ? number : null;
  }

  function coordinatesOf(value) {
    if (!value) return null;
    var source = value.coords || value.coordinate || value.location || value;
    var lat = numberOrNull(source.lat != null ? source.lat : source.latitude);
    var lng = numberOrNull(source.lng != null ? source.lng : source.lon != null ? source.lon : source.longitude);
    return lat != null && lng != null && Math.abs(lat) <= 90 && Math.abs(lng) <= 180 ? { lat: lat, lng: lng } : null;
  }

  function haversineMeters(a, b) {
    if (!a || !b) return Infinity;
    var rad = Math.PI / 180;
    var dLat = (b.lat - a.lat) * rad;
    var dLng = (b.lng - a.lng) * rad;
    var p = Math.sin(dLat / 2) ** 2 + Math.cos(a.lat * rad) * Math.cos(b.lat * rad) * Math.sin(dLng / 2) ** 2;
    return 6371000 * 2 * Math.atan2(Math.sqrt(p), Math.sqrt(1 - p));
  }

  function dayKeyFor(epoch, offsetMinutes) {
    if (epoch == null || epoch === '' || !Number.isFinite(Number(epoch))) return null;
    var date = new Date(Number(epoch));
    if (offsetMinutes == null) {
      return [date.getFullYear(), String(date.getMonth() + 1).padStart(2, '0'), String(date.getDate()).padStart(2, '0')].join('-');
    }
    var shifted = new Date(Number(epoch) + Number(offsetMinutes) * 60000);
    return shifted.toISOString().slice(0, 10);
  }

  function epochOf(value, dayKey) {
    if (value == null || value === '') return null;
    if (typeof value === 'number') return Number.isFinite(value) ? value : null;
    var text = String(value).trim();
    if (/^\d{1,2}:\d{2}$/.test(text) && dayKey) text = dayKey + 'T' + text + ':00';
    var parsed = Date.parse(text);
    return Number.isFinite(parsed) ? parsed : null;
  }

  function stopWindow(stop, contextDayKey) {
    var day = stop.dayKey || stop.date || contextDayKey || null;
    var start = epochOf(stop.startAt != null ? stop.startAt : stop.arrivalAt != null ? stop.arrivalAt : stop.time, day);
    var end = epochOf(stop.endAt != null ? stop.endAt : stop.departureAt, day);
    if (start != null && end == null) {
      var duration = Number(stop.durationMinutes != null ? stop.durationMinutes : stop.stayMinutes);
      end = start + (Number.isFinite(duration) && duration > 0 ? duration : 60) * 60000;
    }
    return { start: start, end: end != null ? end : start, dayKey: day };
  }

  function classifyPhoto(photo, options) {
    options = options || {};
    var metadata = photo.metadata || photo;
    var capturedAt = numberOrNull(metadata.capturedAt != null ? metadata.capturedAt : photo.capturedAt);
    var coords = coordinatesOf(metadata.coords || photo.coords);
    var dayKey = photo.dayKey || dayKeyFor(capturedAt, options.timeZoneOffsetMinutes);
    // times / coords 可獨立以陣列（依 stops 順序）或以 stopId 為 key 傳入，方便
    // 既有 planner 不必先重組資料模型。
    var rawStops = Array.isArray(options.stops) ? options.stops : [];
    var supplemental = function (source, id, index) {
      if (Array.isArray(source)) return source[index];
      return source && typeof source === 'object' ? source[id] : null;
    };
    var stops = rawStops.map(function (rawStop, index) {
      var stop = Object.assign({}, rawStop || {});
      var id = String(stop.collabStopId || stop.stopId || stop.id || ('stop-' + index));
      var time = supplemental(options.times, id, index);
      var coords = supplemental(options.coords, id, index);
      if (typeof time === 'string' || typeof time === 'number') stop.time = time;
      else if (time && typeof time === 'object') Object.assign(stop, time);
      if (coords) stop.coords = coordinatesOf(coords) || stop.coords;
      return stop;
    });
    var radius = Number(options.radiusMeters) > 0 ? Number(options.radiusMeters) : 350;
    var margin = Number(options.timeMarginMinutes) >= 0 ? Number(options.timeMarginMinutes) : 90;
    var weakTime = metadata.timeSource === 'file-last-modified';
    var candidates = stops.map(function (stop, index) {
      var id = String(stop.collabStopId || stop.stopId || stop.id || ('stop-' + index));
      var windowRange = stopWindow(stop, dayKey);
      var stopCoords = coordinatesOf(stop);
      var distance = coords && stopCoords ? haversineMeters(coords, stopCoords) : Infinity;
      var locationScore = Number.isFinite(distance) ? Math.max(0, 1 - distance / Math.max(radius * 3, 1)) : 0;
      var timeDistance = Infinity;
      if (capturedAt != null && windowRange.start != null) {
        if (capturedAt < windowRange.start) timeDistance = (windowRange.start - capturedAt) / 60000;
        else if (windowRange.end != null && capturedAt > windowRange.end) timeDistance = (capturedAt - windowRange.end) / 60000;
        else timeDistance = 0;
      }
      var timeScore = Number.isFinite(timeDistance) ? Math.max(0, 1 - timeDistance / Math.max(margin, 1)) : 0;
      var weightedTime = timeScore * (weakTime ? 0.2 : 0.45);
      var weightedLocation = locationScore * 0.55;
      return {
        stop: stop,
        stopId: id,
        index: index,
        dayKey: windowRange.dayKey || dayKey,
        distanceMeters: Number.isFinite(distance) ? Math.round(distance) : null,
        timeDistanceMinutes: Number.isFinite(timeDistance) ? Math.round(timeDistance) : null,
        hasLocationEvidence: locationScore > 0,
        hasTimeEvidence: timeScore > 0,
        score: weightedLocation + weightedTime
      };
    }).filter(function (candidate) {
      return !dayKey || !candidate.dayKey || candidate.dayKey === dayKey;
    }).sort(function (a, b) { return b.score - a.score || a.index - b.index; });
    var best = candidates[0] || null;
    if (!best || best.score < 0.2 || (!best.hasLocationEvidence && !best.hasTimeEvidence)) {
      return {
        tripId: String(photo.tripId || options.tripId || ''),
        dayKey: dayKey,
        stopId: null,
        group: 'unassigned',
        confidence: 'low',
        score: best ? best.score : 0,
        method: 'pending-review',
        reasons: metadata.warnings || ['沒有足夠的時間或位置資訊。']
      };
    }
    var both = best.hasLocationEvidence && best.hasTimeEvidence;
    return {
      tripId: String(photo.tripId || options.tripId || ''),
      dayKey: best.dayKey || dayKey,
      stopId: best.stopId,
      group: 'stop',
      confidence: both && best.score >= 0.72 ? 'high' : best.score >= 0.38 ? 'medium' : 'low',
      score: Math.round(best.score * 1000) / 1000,
      method: both ? 'time-and-location' : best.hasLocationEvidence ? 'location' : weakTime ? 'file-time-fallback' : 'time',
      distanceMeters: best.distanceMeters,
      timeDistanceMinutes: best.timeDistanceMinutes,
      reasons: []
    };
  }

  function stableSort(photos) {
    return (Array.isArray(photos) ? photos : []).map(function (photo, index) {
      return { photo: photo, index: index };
    }).sort(function (a, b) {
      var am = numberOrNull(a.photo.manualOrder);
      var bm = numberOrNull(b.photo.manualOrder);
      if (am != null || bm != null) {
        if (am == null) return 1;
        if (bm == null) return -1;
        if (am !== bm) return am - bm;
      }
      var ac = numberOrNull(a.photo.capturedAt) || numberOrNull(a.photo.metadata && a.photo.metadata.capturedAt) || Infinity;
      var bc = numberOrNull(b.photo.capturedAt) || numberOrNull(b.photo.metadata && b.photo.metadata.capturedAt) || Infinity;
      if (ac !== bc) return ac - bc;
      var au = numberOrNull(a.photo.uploadedAt) || Infinity;
      var bu = numberOrNull(b.photo.uploadedAt) || Infinity;
      if (au !== bu) return au - bu;
      var idCompare = String(a.photo.id || '').localeCompare(String(b.photo.id || ''));
      return idCompare || a.index - b.index;
    }).map(function (entry) { return entry.photo; });
  }

  function classifyPhotos(photos, options) {
    return stableSort((photos || []).map(function (photo) {
      if (photo.manualClassification) return Object.assign({}, photo);
      var classification = classifyPhoto(photo, options);
      return Object.assign({}, photo, {
        tripId: classification.tripId,
        dayKey: classification.dayKey,
        stopId: classification.stopId,
        classification: classification
      });
    }));
  }

  function groupPhotos(photos) {
    var root = {};
    stableSort(photos || []).forEach(function (photo) {
      var trip = String(photo.tripId || 'no-trip');
      var day = String(photo.dayKey || 'pending-day');
      var stop = String(photo.stopId || 'unassigned');
      if (!root[trip]) root[trip] = {};
      if (!root[trip][day]) root[trip][day] = {};
      if (!root[trip][day][stop]) root[trip][day][stop] = [];
      root[trip][day][stop].push(photo);
    });
    return root;
  }

  function normalizeOwner(owner) {
    owner = owner || {};
    return {
      uid: String(owner.uid || ''),
      name: String(owner.name || owner.displayName || '').slice(0, 100),
      email: String(owner.email || '').slice(0, 254)
    };
  }

  // 目前裝置的 IANA 時區名稱。Android 端對應 ZoneId.systemDefault().id。
  function deviceTimeZone() {
    try {
      var tz = Intl.DateTimeFormat().resolvedOptions().timeZone;
      return typeof tz === 'string' ? tz : '';
    } catch (_e) { return ''; }
  }

  async function findDuplicateHints(hash, tripId, excludeId) {
    if (!hash) return [];
    var photos = await getAll();
    return photos.filter(function (photo) {
      return photo.hash === hash
        && String(photo.tripId || '') === String(tripId || '')
        && String(photo.id || '') !== String(excludeId || '');
    }).map(function (photo) {
      return { id: photo.id, filename: photo.filename, stopId: photo.stopId, owner: photo.owner, status: photo.status };
    });
  }

  async function createPhotoRecord(file, context) {
    context = context || {};
    if (!(file instanceof Blob)) throw new TypeError('照片必須是 File 或 Blob。');
    var metadata = await extractMetadata(file);
    var hash = await computeHash(file);
    var seed = {
      id: context.id || uuid(),
      tripId: String(context.tripId || ''),
      stopName: String(context.stopName || '').slice(0, 200),
      filename: String(file.name || context.filename || 'photo').slice(0, 255),
      mimeType: String(file.type || 'application/octet-stream'),
      size: Number(file.size) || 0,
      blob: file,
      hash: hash,
      metadata: metadata,
      capturedAt: metadata.capturedAt,
      // 與 Android 約定用 IANA 名稱（例如 Asia/Taipei）而不是 ±08:00 偏移：
      // 名稱可以換算出偏移，偏移換不回地區（夏令時、歷史時區變更都還原不了）。
      capturedTimezone: deviceTimeZone(),
      uploadedAt: Date.now(),
      owner: normalizeOwner(context.owner),
      ownerUid: String((context.owner && context.owner.uid) || ''),
      roleAtUpload: String(context.role || 'member'),
      manualOrder: context.manualOrder != null ? Number(context.manualOrder) : null,
      manualClassification: false,
      status: context.localOnly ? STATUS.LOCAL_ONLY : STATUS.QUEUED,
      attempts: 0,
      nextRetryAt: 0,
      lastError: null,
      remote: null,
      createdAt: Date.now(),
      updatedAt: Date.now(),
      source: 'local'
    };
    var classification = classifyPhoto(seed, context);
    seed.dayKey = context.dayKey || classification.dayKey;
    seed.stopId = context.stopId || classification.stopId;
    seed.classification = context.stopId ? {
      tripId: seed.tripId,
      dayKey: seed.dayKey,
      stopId: seed.stopId,
      group: 'stop',
      confidence: 'manual',
      score: 1,
      method: 'upload-context',
      reasons: []
    } : classification;
    seed.manualClassification = !!context.stopId;
    seed.duplicateHints = await findDuplicateHints(hash, seed.tripId, seed.id);
    return seed;
  }

  async function enqueue(files, context) {
    var listOfFiles = Array.from(files || []);
    var queued = [];
    var failed = [];
    for (var i = 0; i < listOfFiles.length; i++) {
      try {
        var photo = await createPhotoRecord(listOfFiles[i], context || {});
        await put(photo);
        queued.push(photo);
        emit('queued', { photo: publicPhoto(photo), duplicateHints: photo.duplicateHints });
      } catch (error) {
        failed.push({ file: listOfFiles[i], error: error });
        emit('queue-error', { filename: listOfFiles[i] && listOfFiles[i].name, error: String(error && error.message || error) });
      }
    }
    requestBackgroundSync();
    if (typeof navigator === 'undefined' || navigator.onLine !== false) retryPending();
    return { queued: queued.map(publicPhoto), failed: failed };
  }

  function publicPhoto(photo) {
    if (!photo) return null;
    var copy = Object.assign({}, photo);
    delete copy.blob;
    return copy;
  }

  function serializablePhoto(photo) {
    var copy = publicPhoto(photo);
    // 本機重試欄位不應成為雲端協作資料的真實來源。
    delete copy.nextRetryAt;
    delete copy.lastError;
    return copy;
  }

  function backoff(attempts) {
    var exponent = Math.max(0, Math.min(Number(attempts) - 1, 7));
    return Number(config.retryBaseMs) * (2 ** exponent) + Math.floor(Math.random() * 1000);
  }

  async function syncOne(photo) {
    if (!photo || photo.status === STATUS.SYNCED || photo.status === STATUS.LOCAL_ONLY) return photo;
    if (typeof config.upload !== 'function') return photo;
    if (typeof navigator !== 'undefined' && navigator.onLine === false) return photo;
    var current = await get(photo.id);
    if (!current) return null;
    try {
      // 上傳與發布各自都可能失敗；每次完整重試都遞增，避免發布階段無限重試。
      current.attempts = Number(current.attempts || 0) + 1;
      if (!current.remote) {
        current.status = STATUS.UPLOADING;
        current.lastError = null;
        await put(current);
        emit('uploading', { photo: publicPhoto(current) });
        current.remote = await config.upload(current.blob, publicPhoto(current));
        if (!current.remote || (!current.remote.url && !current.remote.path && !current.remote.id)) {
          throw new Error('upload adapter 必須回傳 url、path 或 id。');
        }
        await put(current); // 先保存遠端結果，publish 失敗時不會重傳檔案。
      }
      current.status = STATUS.PUBLISHING;
      await put(current);
      if (typeof config.publish === 'function') await config.publish(serializablePhoto(current));
      current.status = STATUS.SYNCED;
      current.syncedAt = Date.now();
      current.lastError = null;
      current.nextRetryAt = 0;
      // 同步完成後移除本機 Blob，降低 IndexedDB 空間；remote metadata 仍保留。
      current.blob = null;
      await put(current);
      emit('synced', { photo: publicPhoto(current) });
      return current;
    } catch (error) {
      current.status = STATUS.FAILED;
      current.lastError = String(error && error.message || error).slice(0, 500);
      current.nextRetryAt = Date.now() + backoff(current.attempts);
      await put(current);
      emit('sync-error', { photo: publicPhoto(current), error: current.lastError });
      return current;
    }
  }

  async function retryPending(options) {
    options = options || {};
    if (retryPromise) return retryPromise;
    retryPromise = (async function () {
      var now = Date.now();
      var photos = await list({ status: [STATUS.QUEUED, STATUS.FAILED, STATUS.UPLOADING, STATUS.PUBLISHING] });
      photos = photos.filter(function (photo) {
        if (options.tripId != null && String(photo.tripId) !== String(options.tripId)) return false;
        if (!options.force && Number(photo.nextRetryAt || 0) > now) return false;
        return Number(photo.attempts || 0) < Number(config.maxAttempts || 8) || options.force;
      });
      var results = [];
      // 依序上傳，避免手機一次解碼／上傳多張造成記憶體尖峰。
      for (var i = 0; i < photos.length; i++) results.push(await syncOne(photos[i]));
      return results.map(publicPhoto);
    })();
    try { return await retryPromise; } finally { retryPromise = null; }
  }

  async function updateClassification(id, patch, actor) {
    var photo = await get(id);
    if (!photo) throw new Error('找不到照片。');
    if (!can('classify', photo, actor)) throw new Error('你沒有權限調整這張照片的分類。');
    patch = patch || {};
    if (patch.tripId != null) photo.tripId = String(patch.tripId);
    if (patch.dayKey != null) photo.dayKey = String(patch.dayKey);
    if (Object.prototype.hasOwnProperty.call(patch, 'stopId')) photo.stopId = patch.stopId == null ? null : String(patch.stopId);
    if (patch.stopName != null) photo.stopName = String(patch.stopName).slice(0, 200);
    photo.manualClassification = true;
    photo.classification = Object.assign({}, photo.classification || {}, {
      tripId: photo.tripId,
      dayKey: photo.dayKey,
      stopId: photo.stopId,
      group: photo.stopId ? 'stop' : 'unassigned',
      confidence: 'manual',
      score: 1,
      method: 'manual',
      reasons: []
    });
    if (photo.status === STATUS.LOCAL_ONLY) photo.status = STATUS.QUEUED;
    else if (photo.status === STATUS.SYNCED) photo.status = STATUS.PUBLISHING;
    await put(photo);
    emit('classification-changed', { photo: publicPhoto(photo), actor: normalizeOwner(actor) });
    if (typeof config.upload === 'function') retryPending({ force: true, tripId: photo.tripId });
    return publicPhoto(photo);
  }

  async function setManualOrder(ids, actor) {
    var orderedIds = Array.from(ids || []).map(String);
    var changed = [];
    for (var i = 0; i < orderedIds.length; i++) {
      var photo = await get(orderedIds[i]);
      if (!photo) continue;
      if (!can('classify', photo, actor)) throw new Error('你沒有權限調整照片順序。');
      photo.manualOrder = i;
      if (photo.status === STATUS.SYNCED) photo.status = STATUS.PUBLISHING;
      await put(photo);
      changed.push(publicPhoto(photo));
    }
    emit('order-changed', { photos: changed, actor: normalizeOwner(actor) });
    retryPending({ force: true });
    return changed;
  }

  function normalizeRole(role) {
    role = String(role || 'viewer').toLowerCase();
    return ['owner', 'editor', 'member', 'viewer'].indexOf(role) >= 0 ? role : 'viewer';
  }

  function can(action, photo, actor) {
    actor = actor || {};
    var role = normalizeRole(actor.role);
    var uid = String(actor.uid || '');
    var ownsPhoto = !!uid && uid === String(photo && (photo.ownerUid || photo.owner && photo.owner.uid) || '');
    if (role === 'owner') return true;
    if (action === 'view') return true;
    if (action === 'upload') return role === 'editor' || role === 'member';
    if (action === 'classify') return role === 'editor' || ownsPhoto;
    if (action === 'edit') return ownsPhoto && (role === 'editor' || role === 'member');
    // 刪除採「方案 B：owner/editor 可協助管理」（2026-09-25 與 Android 端共同決定）。
    // 先前這裡是 ownsPhoto && (editor || member)，但 TripPhotoSync.remove() 與
    // Firestore Rules 都已允許 editor 刪他人照片——三層政策不一致，結果是
    // 「行程 owner 可以刪、editor 不行」，兩個方案都不是。現在三層對齊。
    if (action === 'delete') return role === 'editor' || (ownsPhoto && role === 'member');
    return false;
  }

  async function remove(id, actor) {
    var photo = await get(id);
    if (!photo) return false;
    if (!can('delete', photo, actor)) throw new Error('你沒有權限刪除這張照片。');
    // 判斷「這張在遠端有沒有實體」不能只看 photo.remote——那個欄位是 retryPending 在
    // 本機上傳成功後才寫的，只有「這台裝置傳過」的照片才有。從 ingestRemote 併進來的
    // （別人傳的、或自己在另一台裝置傳的）都沒有這個欄位，於是遠端永遠刪不掉，
    // 只有本機快取被清掉——下一次同步又整張回來。
    // 改看「有沒有遠端身分」：storagePath / url 任一存在即代表它在 Firestore 有文件。
    // 純 local-only 的照片三個都沒有，不會誤觸。
    var hasRemoteCopy = !!(photo.remote || photo.storagePath || photo.url);
    if (hasRemoteCopy && typeof config.removeRemote === 'function') {
      // 這裡刻意不 try/catch：遠端刪失敗就讓例外往上拋，本機那份保留，
      // 使用者會看到錯誤而不是「刪掉了但下次又出現」。
      await config.removeRemote(serializablePhoto(photo));
    }
    await deleteLocal(photo.id);
    emit('removed', { photo: publicPhoto(photo), actor: normalizeOwner(actor) });
    return true;
  }

  // 本機上傳的照片剛同步完成時，手上的快照可能是「發布前」取得的（還沒有這張），
  // 這段時間內不修剪，避免把剛傳上去的照片當成被別人刪掉。
  var LOCAL_PRUNE_GRACE_MS = 60 * 1000;

  // 完整快照裡已經沒有這張時，本機紀錄可不可以清掉。
  // - 從遠端下載的（source 'remote'）：已同步就清。
  // - 這台上傳的（source 'local'）：同步完成超過寬限期才清。它在收到第一份含有
  //   自己的快照後會轉成 'remote'；會卡在 'local' 的，是「上傳後這台還沒看過任何快照，
  //   別人就把它刪了」——原本這種永遠清不掉，只有自己看得到。
  // - 還沒傳完的（queued/uploading/publishing/failed/local-only）一律不動，否則會弄丟待上傳的照片。
  function isPrunableWhenMissing(photo, now) {
    if (!photo || photo.status !== STATUS.SYNCED) return false;
    if (photo.source === 'remote') return true;
    if (photo.source !== 'local') return false;
    return Number(now) - Number(photo.syncedAt || 0) >= LOCAL_PRUNE_GRACE_MS;
  }

  async function ingestRemote(photos, options) {
    options = options || {};
    var incoming = Array.isArray(photos) ? photos : photos ? [photos] : [];
    var changed = [];
    for (var i = 0; i < incoming.length; i++) {
      var remote = incoming[i];
      if (!remote || !remote.id) continue;
      var local = await get(remote.id);
      // 本機尚未完成上傳／發布時，不讓舊 snapshot 蓋掉 Blob 與重試狀態。
      if (local && local.status !== STATUS.SYNCED && local.source !== 'remote') continue;
      var merged = Object.assign({}, local || {}, remote, {
        id: String(remote.id),
        tripId: String(remote.tripId || options.tripId || ''),
        blob: null,
        status: STATUS.SYNCED,
        source: 'remote',
        updatedAt: Number(remote.updatedAt) || Date.now()
      });
      await put(merged);
      changed.push(publicPhoto(merged));
    }
    // 遠端已消失的要一起清掉。ingestRemote 原本只做新增／更新，於是別人刪掉的照片
    // 會永遠留在其他成員的本機快取裡（status 仍是 synced，畫面照樣顯示），
    // 下次開啟還在——只有清網站資料才會消失。editor 可以刪他人照片之後這件事更明顯。
    //
    // 只在「拿到某趟行程的完整快照」時修剪（subscribe 與 list 都是完整快照）；
    // 哪些紀錄可以清見 isPrunableWhenMissing。
    if (options.tripId && options.prune !== false) {
      var keep = Object.create(null);
      incoming.forEach(function (photo) { if (photo && photo.id) keep[String(photo.id)] = true; });
      var locals = await list({ tripId: options.tripId });
      var now = Date.now();
      for (var j = 0; j < locals.length; j++) {
        var candidate = locals[j];
        if (keep[String(candidate.id)]) continue;
        if (!isPrunableWhenMissing(candidate, now)) continue;
        await deleteLocal(candidate.id);
        emit('remote-removed', { tripId: options.tripId, photoId: candidate.id });
      }
    }
    if (changed.length) emit('remote-changed', { tripId: options.tripId, photos: changed });
    return changed;
  }

  function subscribeTrip(tripId) {
    tripId = String(tripId || '');
    if (!tripId) throw new Error('tripId 不可為空。');
    if (remoteUnsubscribers.has(tripId)) return remoteUnsubscribers.get(tripId);
    if (typeof config.subscribe !== 'function') throw new Error('尚未設定照片 subscribe adapter。');
    var unsubscribe = config.subscribe(tripId, function (photos) {
      ingestRemote(photos, { tripId: tripId }).catch(function (error) {
        emit('remote-error', { tripId: tripId, error: String(error && error.message || error) });
      });
    }, function (error) {
      emit('remote-error', { tripId: tripId, error: String(error && error.message || error) });
    });
    var stop = function () {
      try { if (typeof unsubscribe === 'function') unsubscribe(); } finally { remoteUnsubscribers.delete(tripId); }
    };
    remoteUnsubscribers.set(tripId, stop);
    return stop;
  }

  async function requestBackgroundSync() {
    if (!('serviceWorker' in navigator)) return false;
    try {
      var registration = await navigator.serviceWorker.ready;
      if (!registration.sync || typeof registration.sync.register !== 'function') return false;
      await registration.sync.register(SYNC_TAG);
      return true;
    } catch (_error) {
      return false;
    }
  }

  function getCapabilities() {
    return {
      indexedDB: !!window.indexedDB,
      sha256: !!(window.crypto && window.crypto.subtle),
      serviceWorker: 'serviceWorker' in navigator,
      backgroundSync: 'serviceWorker' in navigator && 'SyncManager' in window,
      jpegExif: true
    };
  }

  function bindLifecycle() {
    if (lifecycleBound) return;
    lifecycleBound = true;
    window.addEventListener('online', function () { retryPending(); });
    window.addEventListener('pageshow', function () { retryPending(); });
    document.addEventListener('visibilitychange', function () {
      if (document.visibilityState === 'visible') retryPending();
    });
    // 若既有 Service Worker 將 sync 事件轉成頁面 message，可直接喚醒佇列。
    if ('serviceWorker' in navigator) {
      navigator.serviceWorker.addEventListener('message', function (event) {
        if (event.data && event.data.type === 'TRIP_PHOTO_RETRY') retryPending({ force: true });
      });
    }
  }

  function configure(options) {
    options = options || {};
    ['upload', 'publish', 'subscribe', 'removeRemote', 'logger'].forEach(function (key) {
      if (Object.prototype.hasOwnProperty.call(options, key)) config[key] = options[key];
    });
    if (Number(options.maxAttempts) > 0) config.maxAttempts = Number(options.maxAttempts);
    if (Number(options.retryBaseMs) > 0) config.retryBaseMs = Number(options.retryBaseMs);
    bindLifecycle();
    return api;
  }

  async function init() {
    bindLifecycle();
    await openDb();
    if (typeof navigator === 'undefined' || navigator.onLine !== false) retryPending();
    return getCapabilities();
  }

  function destroy() {
    remoteUnsubscribers.forEach(function (unsubscribe) { try { unsubscribe(); } catch (_e) {} });
    remoteUnsubscribers.clear();
    listeners.clear();
  }

  var api = {
    VERSION: '1.0.0',
    STATUS: STATUS,
    STATUS_LABELS: STATUS_LABELS,
    configure: configure,
    init: init,
    getCapabilities: getCapabilities,
    extractMetadata: extractMetadata,
    computeHash: computeHash,
    classifyPhoto: classifyPhoto,
    classifyPhotos: classifyPhotos,
    stableSort: stableSort,
    groupPhotos: groupPhotos,
    createPhotoRecord: createPhotoRecord,
    enqueue: enqueue,
    retryPending: retryPending,
    requestBackgroundSync: requestBackgroundSync,
    list: list,
    get: get,
    findDuplicateHints: findDuplicateHints,
    updateClassification: updateClassification,
    setManualOrder: setManualOrder,
    can: can,
    remove: remove,
    ingestRemote: ingestRemote,
    subscribeTrip: subscribeTrip,
    subscribe: subscribe,
    destroy: destroy
  };

  return api;
})();
