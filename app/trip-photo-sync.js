/**
 * TravelLink AI collaborative photo sync (Firebase v8 / browser UMD).
 *
 * Formal source of truth:
 *   micro_trips/{tripId}/photos/{photoId}
 *
 * Every public operation takes an explicit tripId. This is intentional: an
 * IndexedDB retry for trip A must never publish into whichever trip happens to
 * be open when the browser comes back online.
 */
(function (root, factory) {
  var api = factory(root || {});
  if (typeof module === 'object' && module.exports) module.exports = api;
  if (root) root.TripPhotoSync = api;
}(typeof globalThis !== 'undefined' ? globalThis : this, function (root) {
  'use strict';

  var config = {
    db: null,
    storage: null,
    auth: null,
    serverTimestamp: null,
    authorize: null,
    logger: null,
    includeLegacy: true
  };

  var REQUIRED_FIELDS = Object.freeze([
    'photoId', 'tripId', 'stopId', 'stopName', 'dayKey', 'capturedAt',
    'manualOrder', 'ownerUid', 'ownerName', 'url', 'storagePath', 'status'
  ]);

  function error(code, message, cause, details) {
    var value = new Error(message);
    value.code = code;
    if (cause) value.cause = cause;
    if (details) value.details = details;
    return value;
  }

  function log(level, message, details) {
    if (typeof config.logger !== 'function') return;
    try { config.logger(level, message, details); } catch (_ignored) {}
  }

  function string(value, max) {
    var result = value == null ? '' : String(value).trim();
    return typeof max === 'number' ? result.slice(0, max) : result;
  }

  function numberOrNull(value) {
    if (value == null || value === '') return null;
    var number = Number(value);
    return Number.isFinite(number) ? number : null;
  }

  function epoch(value) {
    if (value == null) return null;
    if (typeof value.toMillis === 'function') return numberOrNull(value.toMillis());
    if (typeof value.toDate === 'function') return numberOrNull(value.toDate().getTime());
    if (value instanceof Date) return numberOrNull(value.getTime());
    if (value.seconds != null) {
      return Number(value.seconds) * 1000 + Math.floor(Number(value.nanoseconds || 0) / 1000000);
    }
    var numeric = numberOrNull(value);
    if (numeric != null) return numeric;
    var parsed = Date.parse(value);
    return Number.isFinite(parsed) ? parsed : null;
  }

  function safeSegment(value, label) {
    var result = string(value, 500);
    if (!result || result.indexOf('/') !== -1 || result === '.' || result === '..') {
      throw error('photo-sync/invalid-' + label, label + ' 必須是有效且不可含斜線的識別碼。');
    }
    return result;
  }

  function currentUser() {
    return config.auth && config.auth.currentUser ? config.auth.currentUser : null;
  }

  function currentActor(actor) {
    actor = actor || {};
    var user = currentUser();
    return {
      uid: string(actor.uid || (user && user.uid), 200),
      name: string(actor.name || actor.displayName || (user && user.displayName)
        || (user && user.email && user.email.split('@')[0]) || '', 100),
      email: string(actor.email || (user && user.email), 254),
      role: string(actor.role || 'member', 30).toLowerCase()
    };
  }

  function requireAuth(actor) {
    var value = currentActor(actor);
    if (!value.uid) throw error('photo-sync/unauthenticated', '請先登入後再同步照片。');
    return value;
  }

  function photoUrl(input) {
    return string(input.url || input.downloadUrl || (input.remote && input.remote.url), 5000);
  }

  function storagePath(input) {
    return string(input.storagePath || input.path || (input.remote && input.remote.path), 1500);
  }

  /** Pure helper: turn a formal or legacy photo object into one stable shape. */
  function normalizeRecord(input, defaults) {
    input = input || {};
    defaults = defaults || {};
    var owner = input.owner || {};
    var photoId = string(input.photoId || input.id || defaults.photoId, 500);
    var captured = epoch(input.capturedAt != null ? input.capturedAt
      : (input.ts != null ? input.ts : defaults.capturedAt));
    var uploaded = epoch(input.uploadedAt != null ? input.uploadedAt : defaults.uploadedAt);
    var created = epoch(input.createdAt != null ? input.createdAt : defaults.createdAt);
    var updated = epoch(input.updatedAt != null ? input.updatedAt : defaults.updatedAt);
    return {
      photoId: photoId,
      id: photoId,
      tripId: string(input.tripId || defaults.tripId, 500),
      stopId: string(input.stopId || defaults.stopId, 500),
      stopName: string(input.stopName || input.spotName || defaults.stopName, 200),
      dayKey: string(input.dayKey || defaults.dayKey, 100),
      capturedAt: captured,
      manualOrder: numberOrNull(input.manualOrder != null ? input.manualOrder : defaults.manualOrder),
      ownerUid: string(input.ownerUid || owner.uid || defaults.ownerUid, 200),
      ownerName: string(input.ownerName || owner.name || owner.displayName || defaults.ownerName, 100),
      url: photoUrl(input) || string(defaults.url, 5000),
      storagePath: storagePath(input) || string(defaults.storagePath, 1500),
      status: string(input.status || defaults.status || 'synced', 40),
      capturedTimezone: string(input.capturedTimezone || defaults.capturedTimezone, 100),
      mimeType: string(input.mimeType || defaults.mimeType, 150),
      hash: string(input.hash || defaults.hash, 300),
      createdAt: created,
      uploadedAt: uploaded,
      updatedAt: updated,
      source: string(input.source || defaults.source || 'formal', 40)
    };
  }

  function fingerprint(photo) {
    return string(photo.photoId || photo.id)
      || string(photo.storagePath || photo.path)
      || string(photo.url || photo.downloadUrl);
  }

  function stableSort(photos) {
    return (photos || []).map(function (photo, index) {
      return { photo: photo, index: index };
    }).sort(function (a, b) {
      var ao = numberOrNull(a.photo.manualOrder);
      var bo = numberOrNull(b.photo.manualOrder);
      if (ao != null || bo != null) {
        if (ao == null) return 1;
        if (bo == null) return -1;
        if (ao !== bo) return ao - bo;
      }
      var ac = epoch(a.photo.capturedAt);
      var bc = epoch(b.photo.capturedAt);
      if (ac == null) ac = Infinity;
      if (bc == null) bc = Infinity;
      if (ac !== bc) return ac - bc;
      var au = epoch(a.photo.uploadedAt);
      var bu = epoch(b.photo.uploadedAt);
      if (au == null) au = Infinity;
      if (bu == null) bu = Infinity;
      if (au !== bu) return au - bu;
      var id = string(a.photo.photoId || a.photo.id).localeCompare(string(b.photo.photoId || b.photo.id));
      return id || a.index - b.index;
    }).map(function (entry) { return entry.photo; });
  }

  /** Pure helper: formal records win over duplicate legacy records. */
  function mergePhotos(formal, legacy) {
    var result = [];
    var seenIds = Object.create(null);
    var seenPaths = Object.create(null);
    var seenUrls = Object.create(null);
    (formal || []).concat(legacy || []).forEach(function (photo) {
      if (!photo) return;
      var id = string(photo.photoId || photo.id);
      var path = string(photo.storagePath || photo.path);
      var url = string(photo.url || photo.downloadUrl);
      if ((id && seenIds[id]) || (path && seenPaths[path]) || (url && seenUrls[url])) return;
      if (!id && !path && !url) return;
      if (id) seenIds[id] = true;
      if (path) seenPaths[path] = true;
      if (url) seenUrls[url] = true;
      result.push(photo);
    });
    return stableSort(result);
  }

  function hashText(value) {
    var hash = 2166136261;
    value = String(value || '');
    for (var index = 0; index < value.length; index++) {
      hash ^= value.charCodeAt(index);
      hash = Math.imul(hash, 16777619);
    }
    return (hash >>> 0).toString(36);
  }

  /** Pure helper for micro_trips/{tripId}/memories documents. */
  function normalizeLegacyMemories(documents, tripId) {
    var output = [];
    (documents || []).forEach(function (entry) {
      if (!entry) return;
      var data = entry.data && typeof entry.data === 'function' ? entry.data() : (entry.data || entry);
      var documentId = string(entry.id || data.uid || data.ownerUid, 200);
      var ownerUid = string(data.ownerUid || documentId, 200);
      var ownerName = string(data.ownerName || '旅伴', 100);
      var spots = data.spots && typeof data.spots === 'object' ? data.spots : {};
      Object.keys(spots).forEach(function (stopId) {
        var spot = spots[stopId] || {};
        (Array.isArray(spot.photos) ? spot.photos : []).forEach(function (item, index) {
          var raw = typeof item === 'string' ? { url: item } : (item || {});
          var url = photoUrl(raw);
          if (!url) return;
          var id = string(raw.photoId || raw.id) || ('legacy_' + hashText([
            tripId, ownerUid, stopId, url
          ].join('|')));
          output.push(normalizeRecord(raw, {
            photoId: id,
            tripId: tripId,
            stopId: stopId,
            stopName: spot.spotName,
            dayKey: spot.dayKey,
            capturedAt: raw.ts || spot.updatedAt || data.updatedAt,
            manualOrder: raw.manualOrder != null ? raw.manualOrder : index,
            ownerUid: ownerUid,
            ownerName: ownerName,
            status: 'synced',
            updatedAt: spot.updatedAt || data.updatedAt,
            source: 'legacy-memory'
          }));
        });
      });
    });
    return mergePhotos([], output);
  }

  function snapshotDocuments(snapshot) {
    var result = [];
    if (!snapshot) return result;
    if (typeof snapshot.forEach === 'function') {
      snapshot.forEach(function (document) {
        result.push({ id: document.id, data: document.data() || {} });
      });
    } else if (Array.isArray(snapshot.docs)) {
      snapshot.docs.forEach(function (document) {
        result.push({ id: document.id, data: document.data() || {} });
      });
    }
    return result;
  }

  function requireDb() {
    if (!config.db || typeof config.db.collection !== 'function') {
      throw error('photo-sync/not-configured', 'TripPhotoSync 尚未設定 Firebase Firestore。');
    }
    return config.db;
  }

  function photoCollection(tripId) {
    tripId = safeSegment(tripId, 'tripId');
    return requireDb().collection('micro_trips').doc(tripId).collection('photos');
  }

  function memoryCollection(tripId) {
    tripId = safeSegment(tripId, 'tripId');
    return requireDb().collection('micro_trips').doc(tripId).collection('memories');
  }

  function timestamp() {
    if (typeof config.serverTimestamp === 'function') return config.serverTimestamp();
    var firebase = root && root.firebase;
    if (firebase && firebase.firestore && firebase.firestore.FieldValue
      && typeof firebase.firestore.FieldValue.serverTimestamp === 'function') {
      return firebase.firestore.FieldValue.serverTimestamp();
    }
    return Date.now();
  }

  function configure(options) {
    options = options || {};
    if (Object.prototype.hasOwnProperty.call(options, 'db')) config.db = options.db;
    if (Object.prototype.hasOwnProperty.call(options, 'storage')) config.storage = options.storage;
    if (Object.prototype.hasOwnProperty.call(options, 'auth')) config.auth = options.auth;
    if (Object.prototype.hasOwnProperty.call(options, 'serverTimestamp')) config.serverTimestamp = options.serverTimestamp;
    if (Object.prototype.hasOwnProperty.call(options, 'authorize')) config.authorize = options.authorize;
    if (Object.prototype.hasOwnProperty.call(options, 'logger')) config.logger = options.logger;
    if (Object.prototype.hasOwnProperty.call(options, 'includeLegacy')) config.includeLegacy = options.includeLegacy !== false;
    return api;
  }

  async function authorized(action, context) {
    if (typeof config.authorize !== 'function') return null;
    try {
      return await config.authorize(action, context);
    } catch (cause) {
      throw error('photo-sync/authorization-failed', '無法確認照片操作權限。', cause);
    }
  }

  function formalWrite(record) {
    return {
      photoId: record.photoId,
      tripId: record.tripId,
      stopId: record.stopId,
      stopName: record.stopName,
      dayKey: record.dayKey,
      capturedAt: record.capturedAt,
      manualOrder: record.manualOrder,
      ownerUid: record.ownerUid,
      ownerName: record.ownerName,
      url: record.url,
      storagePath: record.storagePath,
      status: record.status,
      capturedTimezone: record.capturedTimezone,
      mimeType: record.mimeType,
      hash: record.hash,
      createdAt: record.createdAt || Date.now(),
      uploadedAt: record.uploadedAt || Date.now(),
      updatedAt: timestamp()
    };
  }

  /** Publish uploaded metadata; never depends on users.visitedSpots. */
  async function publish(tripId, input, options) {
    options = options || {};
    tripId = safeSegment(tripId, 'tripId');
    input = input || {};
    if (input.tripId && string(input.tripId) !== tripId) {
      throw error('photo-sync/trip-mismatch', '照片所屬行程與指定的 tripId 不一致。', null, {
        expectedTripId: tripId,
        actualTripId: string(input.tripId)
      });
    }
    var actor = requireAuth(options.actor);
    var ownerUid = string(input.ownerUid || (input.owner && input.owner.uid) || actor.uid, 200);
    if (ownerUid !== actor.uid) {
      var mayPublishForOwner = await authorized('publish-for-owner', {
        tripId: tripId, photo: input, actor: actor, ownerUid: ownerUid
      });
      if (mayPublishForOwner !== true) {
        throw error('photo-sync/forbidden-owner', '不能以其他成員的身分發布照片。');
      }
    }
    var photoId = safeSegment(input.photoId || input.id, 'photoId');
    var record = normalizeRecord(input, {
      photoId: photoId,
      tripId: tripId,
      ownerUid: ownerUid,
      ownerName: actor.name,
      status: 'synced',
      createdAt: Date.now(),
      uploadedAt: Date.now(),
      updatedAt: Date.now(),
      source: 'formal'
    });
    record.photoId = photoId;
    record.id = photoId;
    record.tripId = tripId;
    record.ownerUid = ownerUid;
    if (!record.ownerName) record.ownerName = actor.name;
    if (!record.url) throw error('photo-sync/missing-url', '照片尚未取得下載網址。');
    record.status = string(options.status || 'synced', 40);
    var reference = photoCollection(tripId).doc(photoId);
    var existingSnapshot = await reference.get();
    if (existingSnapshot.exists) {
      var existing = normalizeRecord(existingSnapshot.data() || {}, {
        photoId: photoId, tripId: tripId, source: 'formal'
      });
      if (existing.ownerUid && existing.ownerUid !== actor.uid) {
        var mayOverwrite = await authorized('overwrite', {
          tripId: tripId, photo: existing, replacement: record, actor: actor
        });
        if (mayOverwrite !== true) {
          throw error('photo-sync/forbidden-overwrite', '不能覆寫其他成員的照片。');
        }
      }
      if (existing.createdAt) record.createdAt = existing.createdAt;
    }
    await reference.set(formalWrite(record), { merge: true });
    return record;
  }

  async function readFormal(tripId) {
    var snapshot = await photoCollection(tripId).get();
    return snapshotDocuments(snapshot).map(function (entry) {
      return normalizeRecord(entry.data, {
        photoId: entry.id,
        tripId: tripId,
        source: 'formal'
      });
    });
  }

  async function readLegacy(tripId) {
    var snapshot = await memoryCollection(tripId).get();
    return normalizeLegacyMemories(snapshotDocuments(snapshot), tripId);
  }

  async function list(tripId, options) {
    options = options || {};
    tripId = safeSegment(tripId, 'tripId');
    var formal = await readFormal(tripId);
    var legacy = [];
    var warning = null;
    if (options.includeLegacy !== false && config.includeLegacy) {
      try { legacy = await readLegacy(tripId); }
      catch (cause) {
        warning = error('photo-sync/legacy-read-failed', '舊版回憶資料無法讀取，已改用正式照片資料。', cause);
        log('warn', warning.message, warning);
        if (options.strictLegacy) throw warning;
      }
    }
    var photos = mergePhotos(formal, legacy);
    Object.defineProperty(photos, 'warning', { value: warning, enumerable: false });
    return photos;
  }

  /**
   * Realtime subscription. Legacy permission failure is non-fatal by default.
   * onError(error, { source, fatal }) receives enough detail to show degraded state.
   */
  function subscribe(tripId, onPhotos, onError, options) {
    options = options || {};
    tripId = safeSegment(tripId, 'tripId');
    if (typeof onPhotos !== 'function') throw new TypeError('onPhotos 必須是函式。');
    var formal = [];
    var legacy = [];
    var stopped = false;
    var unsubscribers = [];
    function emit(source) {
      if (!stopped) onPhotos(mergePhotos(formal, legacy), { tripId: tripId, source: source });
    }
    function fail(cause, source, fatal) {
      var wrapped = error('photo-sync/' + source + '-subscribe-failed',
        source === 'legacy' ? '舊版回憶即時同步失敗。' : '照片即時同步失敗。', cause,
        { tripId: tripId, source: source, fatal: !!fatal });
      log(fatal ? 'error' : 'warn', wrapped.message, wrapped);
      if (typeof onError === 'function') onError(wrapped, wrapped.details);
    }
    unsubscribers.push(photoCollection(tripId).onSnapshot(function (snapshot) {
      formal = snapshotDocuments(snapshot).map(function (entry) {
        return normalizeRecord(entry.data, { photoId: entry.id, tripId: tripId, source: 'formal' });
      });
      emit('formal');
    }, function (cause) { fail(cause, 'formal', true); }));

    if (options.includeLegacy !== false && config.includeLegacy) {
      try {
        unsubscribers.push(memoryCollection(tripId).onSnapshot(function (snapshot) {
          legacy = normalizeLegacyMemories(snapshotDocuments(snapshot), tripId);
          emit('legacy');
        }, function (cause) {
          legacy = [];
          fail(cause, 'legacy', false);
          emit('legacy-unavailable');
        }));
      } catch (cause) {
        fail(cause, 'legacy', false);
      }
    }
    return function unsubscribe() {
      if (stopped) return;
      stopped = true;
      unsubscribers.forEach(function (unsubscribe) {
        try { if (typeof unsubscribe === 'function') unsubscribe(); } catch (_ignored) {}
      });
    };
  }

  function defaultCanRemove(photo, actor) {
    return !!actor.uid && actor.uid === string(photo.ownerUid);
  }

  async function removeStorageObject(path) {
    if (!path || !config.storage || typeof config.storage.ref !== 'function') return null;
    try {
      await config.storage.ref(path).delete();
      return null;
    } catch (cause) {
      if (cause && (cause.code === 'storage/object-not-found' || cause.code === 'object-not-found')) return null;
      return error('photo-sync/storage-delete-failed', '照片記錄已刪除，但雲端檔案清理失敗。', cause, {
        storagePath: path
      });
    }
  }

  async function remove(tripId, photoId, options) {
    options = options || {};
    tripId = safeSegment(tripId, 'tripId');
    photoId = safeSegment(photoId, 'photoId');
    var actor = requireAuth(options.actor);
    var reference = photoCollection(tripId).doc(photoId);
    var snapshot = await reference.get();
    if (!snapshot.exists) return { removed: false, reason: 'not-found', warning: null };
    var photo = normalizeRecord(snapshot.data() || {}, {
      photoId: photoId, tripId: tripId, source: 'formal'
    });
    var allowed = defaultCanRemove(photo, actor);
    if (!allowed) {
      allowed = await authorized('remove', { tripId: tripId, photo: photo, actor: actor }) === true;
    }
    if (!allowed) throw error('photo-sync/forbidden', '你只能刪除自己的照片。');

    // Delete metadata first. Storage rules can differ from Firestore rules; doing
    // this in the opposite order could destroy a file before Firestore rejects us.
    await reference.delete();
    // 只刪自己的檔案。storage.rules 只准上傳者本人刪 trip-photos；owner/editor 刪別人的
    // 照片時，檔案由 Cloud Function（cleanup_deleted_trip_photo，文件刪除時觸發）用管理員
    // 權限清掉。這裡若照刪，只會換來一個 403（F12 紅字），檔案也刪不掉。
    var ownsFile = !!actor.uid && actor.uid === string(photo.ownerUid);
    var warning = ownsFile ? await removeStorageObject(photo.storagePath) : null;
    if (warning) {
      log('warn', warning.message, warning);
      if (options.strictStorage) throw warning;
    }
    return { removed: true, photo: photo, warning: warning };
  }

  /** Adapters for TripPhotoManager; retry always uses photo.tripId. */
  function createManagerAdapters(options) {
    options = options || {};
    return {
      publish: function (photo) {
        if (!photo || !photo.tripId) {
          return Promise.reject(error('photo-sync/missing-tripId', '待同步照片缺少原始 tripId。'));
        }
        return publish(photo.tripId, photo, { actor: typeof options.getActor === 'function' ? options.getActor() : options.actor });
      },
      subscribe: function (tripId, onPhotos, onError) {
        return subscribe(tripId, onPhotos, onError, { includeLegacy: options.includeLegacy });
      },
      removeRemote: function (photo) {
        if (!photo || !photo.tripId || !(photo.photoId || photo.id)) {
          return Promise.reject(error('photo-sync/invalid-remove', '待刪照片缺少 tripId 或 photoId。'));
        }
        return remove(photo.tripId, photo.photoId || photo.id, {
          actor: typeof options.getActor === 'function' ? options.getActor() : options.actor,
          strictStorage: options.strictStorage
        });
      }
    };
  }

  var api = {
    version: '1.0.0',
    REQUIRED_FIELDS: REQUIRED_FIELDS,
    configure: configure,
    publish: publish,
    subscribe: subscribe,
    remove: remove,
    list: list,
    createManagerAdapters: createManagerAdapters,
    normalizeRecord: normalizeRecord,
    normalizeLegacyMemories: normalizeLegacyMemories,
    mergePhotos: mergePhotos,
    stableSort: stableSort,
    fingerprint: fingerprint
  };

  return api;
}));
