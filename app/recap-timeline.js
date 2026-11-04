/*
 * TravelLink AI recap timeline — 回顧短片的「推薦版時間軸」（clips）。
 * 格式照 docs/travel-story/TRAVEL_STORY_MEDIA_AI_PLAN.md 6.3：
 *   { schemaVersion, locale, revision, templateId, clips: [{ clipId, order, type, ... durationMs }] }
 * type：cover（開場）、route（站間路線）、photo（到站照片）、timeline（換日轉場）、outro（旅程數據）。
 *
 * 推薦版用「規則」產生，不呼叫 AI；時長由 recap-manifest.js 依樣板計算，
 * 所以清單上的秒數就是實際渲染的秒數。compileTimeline 把（之後可被使用者編輯的）時間軸
 * 轉回 manifest 的參數，後端一律重新驗證、夾住每段時長，不信任前端數字。
 * 相依：recap-manifest.js（Node 自動 require；瀏覽器需先載入 recap-templates.js、recap-manifest.js）。
 */
(function (root, factory) {
  var manifest = null;
  if (typeof module === 'object' && module.exports) {
    manifest = require('./recap-manifest.js');
    module.exports = factory(manifest);
  } else {
    root.WAI_RECAP_TIMELINE = factory(root.WAI_RECAP_MANIFEST || null);
  }
}(typeof globalThis !== 'undefined' ? globalThis : this, function (MANIFEST) {
  'use strict';

  // 第 1 版：標準／快節奏／慢遊／電影感開場；第 2 版：Reels（structure 'anchors'，IG 分享優化提案 A11）。
  // 後端兩版都收；第 2 版擴充第 1 版：route 可跨站（viaStopIds）、photo 一站多張（assetRefs、hero）、新增 summary。
  var SCHEMA_VERSION = 1;
  var SCHEMA_VERSION_V2 = 2;

  // 每種片段可接受的時長範圍（ms）：編輯器與後端共用同一組限制。
  // photo：第 1 版單張照片的範圍；photoEach：第 2 版「每張」的範圍，整段上下限＝張數 × photoEach。
  var LIMITS = {
    cover: [1000, 6000],
    route: [300, 15000],
    reveal: [500, 3000],
    photo: [800, 8000],
    photoEach: [500, 4000],
    timeline: [600, 4000],
    summary: [800, 4000],
    outro: [1000, 6000]
  };

  // 字幕：每則最多 14 個字（計劃 6.4），去掉換行與控制字元；空字串＝沒有字幕
  var CAPTION_MAX = 14;
  var CAPTION_TYPES = { cover: true, route: true, photo: true, summary: true, outro: true };

  function sanitizeCaption(value) {
    var text = String(value == null ? '' : value)
      .replace(/[\u0000-\u001f\u007f\u2028\u2029]/g, ' ')
      .replace(/\s+/g, ' ')
      .trim();
    return Array.from(text).slice(0, CAPTION_MAX).join('');
  }

  // count：photo 片段的張數（第 2 版）；沒給＝第 1 版的單張照片
  function clampMs(type, value, count) {
    var range = LIMITS[type] || [0, 60000];
    var n = Math.round(Number(value) || 0);
    if (type === 'photo') {
      if (!(count > 0)) return Math.max(range[0], Math.min(range[1], n));
      var each = LIMITS.photoEach;
      var per = Math.max(1, Math.min(20, Math.floor(count)));
      return Math.max(each[0] * per, Math.min(Math.min(each[1] * per, 30000), n));
    }
    return Math.max(range[0], Math.min(range[1], n));
  }

  function photoStopIndices(photos, stopCount) {
    var seen = {};
    var out = [];
    (Array.isArray(photos) ? photos : []).forEach(function (p) {
      var idx = Number(p && p.stopIndex);
      if (!isFinite(idx) || idx < 0 || idx >= stopCount || seen[idx]) return;
      seen[idx] = true;
      out.push(idx);
    });
    return out.sort(function (a, b) { return a - b; });
  }

  function isAnchorTemplate(templateId) {
    return !!(MANIFEST && MANIFEST.resolveTemplate(templateId).structure === 'anchors');
  }

  // 每站的照片（依送來的順序＝共同相簿的「手動排序 → 拍攝時間」）：[[{ photoId, k }], ...]
  function photosByStop(photos, stopCount) {
    var out = [];
    for (var i = 0; i < stopCount; i += 1) out.push([]);
    (Array.isArray(photos) ? photos : []).forEach(function (p) {
      var idx = Number(p && p.stopIndex);
      if (!isFinite(idx) || idx < 0 || idx >= stopCount) return;
      out[idx].push({ photoId: p.photoId ? String(p.photoId) : '', k: out[idx].length });
    });
    return out;
  }

  function stopIdOf(stops, i) { return String((stops[i] && stops[i].stopId) || ''); }

  function routeLabel(stops, from, to, via) {
    var names = via.map(function (v) { return (stops[v] && stops[v].name) || ''; }).filter(Boolean);
    return ((stops[from] && stops[from].name) || '') + ' → ' + ((stops[to] && stops[to].name) || '')
      + (names.length ? '（經過 ' + names.join('、') + '）' : '');
  }

  function legKm(manifest, from, to) {
    var km = 0;
    for (var s = from; s < to; s += 1) km += manifest.segments[s] ? manifest.segments[s].geoDistanceKm : 0;
    return Math.round(km * 10) / 10;
  }

  // ── 第 2 版：Reels 等 structure 'anchors' 的樣板 ──
  // opts：{ templateId, photos, lengthMode, highlightStopId, coverStopIndex }
  function buildRecommendedTimelineV2(trip, options) {
    var stops = Array.isArray(trip && trip.stops) ? trip.stops : [];
    var byStop = photosByStop(options.photos, stops.length);
    var counts = byStop.map(function (l) { return l.length; });
    var heroIdx;
    if (options.highlightStopId) {
      var hi = stops.findIndex(function (s) { return String(s.stopId) === String(options.highlightStopId); });
      if (hi >= 0) heroIdx = hi;
    }
    var m = MANIFEST.buildRecapManifest(trip, {
      template: options.templateId, photoCounts: counts, heroStopIndex: heroIdx,
      lengthMode: options.lengthMode
    });
    var tl = m.timeline;
    var clips = [];
    function push(clip) { clip.order = clips.length; clips.push(clip); }
    var coverIdx = Number(options.coverStopIndex);
    var coverIsPhoto = options.coverStopIndex !== undefined && options.coverStopIndex !== null && counts[coverIdx] > 0;
    var mediaByStop = {};
    m.media.forEach(function (mm) { (mediaByStop[mm.stopIndex] = mediaByStop[mm.stopIndex] || []).push(mm); });
    var middle = [];
    for (var v = 1; v < stops.length - 1; v += 1) middle.push(v);
    var lastPoint = 0;

    tl.phases.forEach(function (ph) {
      var dur = Math.round(ph.endMs - ph.startMs);
      if (ph.type === 'cover') {
        var cover = { clipId: 'cover', type: 'cover', source: coverIsPhoto ? 'photo' : 'illustration', durationMs: dur };
        if (coverIsPhoto) cover.coverStopIndex = coverIdx;
        push(cover);
      } else if (ph.type === 'reveal') {
        push({
          clipId: 'reveal', type: 'route', reveal: true,
          fromStopId: stopIdOf(stops, 0), toStopId: stopIdOf(stops, stops.length - 1),
          viaStopIds: middle.map(function (i) { return stopIdOf(stops, i); }),
          label: '路線揭曉', durationMs: dur
        });
      } else if (ph.type === 'hop') {
        push({
          clipId: 'route-' + ph.fromIndex + '-' + ph.toIndex, type: 'route',
          fromStopId: stopIdOf(stops, ph.fromIndex), toStopId: stopIdOf(stops, ph.toIndex),
          viaStopIds: ph.viaIndices.map(function (i) { return stopIdOf(stops, i); }),
          label: routeLabel(stops, ph.fromIndex, ph.toIndex, ph.viaIndices),
          distanceKm: legKm(m, ph.fromIndex, ph.toIndex), durationMs: dur
        });
      } else if (ph.type === 'photo') {
        // 整條路線開一遍的結構：照片前補一段「上一個停點 → 這站」的路線片段（只算開車時間）
        if (m.structure !== 'photoStory' && ph.stopIndex > lastPoint) pushLeg(lastPoint, ph.stopIndex);
        lastPoint = Math.max(lastPoint, ph.stopIndex);
        var refs = (mediaByStop[ph.stopIndex] || []).map(function (mm) {
          var ref = { kind: 'memory_photo', stopId: stopIdOf(stops, ph.stopIndex) };
          var src = byStop[ph.stopIndex][mm.photoIndex];
          if (src && src.photoId) ref.photoId = src.photoId;
          return ref;
        });
        var clip = { clipId: 'photo-' + ph.stopIndex, type: 'photo', stopIndex: ph.stopIndex, label: (stops[ph.stopIndex] || {}).name || '', assetRefs: refs, durationMs: dur };
        if (ph.hero) clip.hero = true;
        if (ph.dayLabel) clip.dayLabel = ph.dayLabel;
        push(clip);
      } else if (ph.type === 'summary') {
        if (m.structure !== 'photoStory' && lastPoint < stops.length - 1) pushLeg(lastPoint, stops.length - 1);
        push({ clipId: 'summary', type: 'summary', stats: { stops: stops.length, days: m.trip.dayCount, distanceKm: Number(trip && trip.distanceKm) || 0 }, durationMs: dur });
      } else if (ph.type === 'outro') {
        push({ clipId: 'outro', type: 'outro', stats: { stops: stops.length, distanceKm: Number(trip && trip.distanceKm) || 0, days: m.trip.dayCount }, durationMs: dur });
      }
    });

    function pushLeg(from, to) {
      var ms = 0;
      var via = [];
      for (var s = from; s < to; s += 1) { ms += m.segments[s].travelMs; if (s > from) via.push(s); }
      push({
        clipId: 'route-' + from + '-' + to, type: 'route',
        fromStopId: stopIdOf(stops, from), toStopId: stopIdOf(stops, to),
        viaStopIds: via.map(function (i) { return stopIdOf(stops, i); }),
        label: routeLabel(stops, from, to, via), distanceKm: legKm(m, from, to), durationMs: Math.round(ms)
      });
    }

    return {
      schemaVersion: 2,
      locale: 'zh-TW',
      revision: 0,
      templateId: m.template.id,
      lengthMode: m.lengthMode,
      structure: m.structure,
      adaptiveTier: m.adaptiveTier,
      totalMs: Math.round(tl.totalMs),
      clips: clips
    };
  }

  // 路線片段驗證（第 2 版）：起訖可以不相鄰，但中間經過的站必須正好是 viaStopIds（依序）
  function validRoute(c, idIndex, stops) {
    var from = idIndex[String(c.fromStopId || '')];
    var to = idIndex[String(c.toStopId || '')];
    if (from === undefined || to === undefined || to <= from) return null;
    var via = Array.isArray(c.viaStopIds) ? c.viaStopIds.map(String) : [];
    if (via.length !== to - from - 1) return null;
    for (var k = 0; k < via.length; k += 1) if (via[k] !== stopIdOf(stops, from + 1 + k)) return null;
    return { from: from, to: to };
  }

  function compileTimelineV2(trip, timeline, photos) {
    var stops = Array.isArray(trip && trip.stops) ? trip.stops : [];
    var clips = Array.isArray(timeline && timeline.clips) ? timeline.clips : [];
    var byStop = photosByStop(photos, stops.length);
    var idIndex = {};
    stops.forEach(function (s, i) { idIndex[String(s.stopId || '')] = i; });
    var out = { template: timeline && timeline.templateId, lengthMode: timeline && timeline.lengthMode === '15' ? '15' : 'all' };
    var photoCounts = stops.map(function () { return 0; });
    var selection = {};
    var plan = [];
    var hopMs = {};
    var driveSum = 0;
    var hasDrive = false;
    var hero = -1;
    var lastAnchor = -1;

    clips.forEach(function (c) {
      if (!c || typeof c !== 'object') return;
      if (c.type === 'cover') {
        out.coverMs = clampMs('cover', c.durationMs);
        var ci = Number(c.coverStopIndex);
        if (c.source === 'photo' && isFinite(ci) && byStop[ci] && byStop[ci].length) out.coverStopIndex = ci;
      } else if (c.type === 'outro') out.statsMs = clampMs('outro', c.durationMs);
      else if (c.type === 'summary') out.summaryMs = clampMs('summary', c.durationMs);
      else if (c.type === 'route') {
        var r = validRoute(c, idIndex, stops);
        if (!r) return;
        if (c.reveal) { if (r.from === 0 && r.to === stops.length - 1) out.revealMs = clampMs('reveal', c.durationMs); return; }
        hopMs[r.from + '>' + r.to] = clampMs('route', c.durationMs);
        driveSum += hopMs[r.from + '>' + r.to];
        hasDrive = true;
      } else if (c.type === 'photo') {
        var refs = Array.isArray(c.assetRefs) ? c.assetRefs : (c.assetRef ? [c.assetRef] : []);
        var stopIdx = idIndex[String(refs[0] && refs[0].stopId || '')];
        // 錨點只能依行程順序往後、每站一個片段、這站要真的有照片
        if (stopIdx === undefined || stopIdx <= lastAnchor || !byStop[stopIdx].length) return;
        var avail = byStop[stopIdx];
        var picked = [];
        refs.forEach(function (ref, pos) {
          if (!ref || String(ref.stopId || '') !== stopIdOf(stops, stopIdx)) return;
          var hit = ref.photoId ? avail.filter(function (a) { return a.photoId === String(ref.photoId); })[0] : avail[pos];
          if (hit && picked.indexOf(hit.k) < 0) picked.push(hit.k);
        });
        if (!picked.length) return;
        lastAnchor = stopIdx;
        photoCounts[stopIdx] = picked.length;
        selection[stopIdx] = picked;
        if (c.hero && hero < 0) hero = stopIdx;
        plan.push({ stopIndex: stopIdx, count: picked.length, photoMs: clampMs('photo', c.durationMs, picked.length) });
      }
    });

    // 過場：上一個錨點（第一個錨點從起點算）→ 這個錨點；有對得上的路線片段就用它的秒數，
    // 沒有時：相鄰兩站＝同組連續放，不相鄰＝用樣板預設的過場秒數
    var prev = 0;
    plan.forEach(function (p, i) {
      var from = i === 0 ? 0 : prev;
      var key = from + '>' + p.stopIndex;
      if (p.stopIndex === from) p.hopBefore = false;
      else if (hopMs[key] !== undefined) { p.hopBefore = true; p.hopMs = hopMs[key]; }
      else p.hopBefore = !(i > 0 && p.stopIndex === from + 1);
      prev = p.stopIndex;
    });

    out.photoCounts = photoCounts;
    out.photoSelection = selection;
    out.anchorPlan = plan;
    out.heroStopIndex = hero;
    if (hasDrive) out.driveMs = Math.max(LIMITS.route[0], driveSum);
    out.mediaStopIndices = plan.map(function (p) { return p.stopIndex; });
    out.mediaCount = plan.reduce(function (a, p) { return a + p.count; }, 0);
    return out;
  }

  // 依行程＋實際照片排出推薦版。opts：{ templateId, photos: [{ stopIndex, url?, photoId? }] }
  // Reels 等 'anchors' 樣板另吃 lengthMode、highlightStopId、coverStopIndex，產第 2 版格式
  function buildRecommendedTimeline(trip, opts) {
    if (!MANIFEST) throw new Error('recap-manifest.js not loaded');
    var options = opts || {};
    if (isAnchorTemplate(options.templateId)) return buildRecommendedTimelineV2(trip, options);
    var stops = Array.isArray(trip && trip.stops) ? trip.stops : [];
    var photos = Array.isArray(options.photos) ? options.photos : [];
    var mediaIdx = photoStopIndices(photos, stops.length);
    var m = MANIFEST.buildRecapManifest(trip, {
      template: options.templateId,
      mediaCount: mediaIdx.length,
      mediaStopIndices: mediaIdx
    });
    var tl = m.timeline;
    var photoByStop = {};
    photos.forEach(function (p) { if (p && photoByStop[p.stopIndex] === undefined) photoByStop[p.stopIndex] = p; });
    var clips = [];
    function push(clip) { clip.order = clips.length; clips.push(clip); }
    function photoClip(stopIndex) {
      var p = photoByStop[stopIndex] || {};
      var stop = stops[stopIndex] || {};
      var ref = { kind: 'memory_photo', stopId: String(stop.stopId || '') };
      if (p.photoId) ref.photoId = String(p.photoId);
      push({ clipId: 'photo-' + stopIndex, type: 'photo', stopIndex: stopIndex, label: stop.name || '', assetRef: ref, durationMs: Math.round(tl.holdMs[stopIndex]) });
    }

    push({ clipId: 'cover', type: 'cover', style: m.cover.style, durationMs: Math.round(tl.coverMs) });
    if (tl.holdMs[0] > 0 && mediaIdx.indexOf(0) >= 0) photoClip(0);
    m.segments.forEach(function (seg, i) {
      var to = stops[seg.toIndex] || {};
      if (seg.dayBreak) {
        push({ clipId: 'day-' + seg.toDay, type: 'timeline', day: seg.toDay, durationMs: Math.round(seg.travelMs) });
      } else {
        var from = stops[seg.fromIndex] || {};
        push({
          clipId: 'route-' + i, type: 'route', segmentIndex: i,
          fromStopId: String(from.stopId || ''), toStopId: String(to.stopId || ''),
          label: (from.name || '') + ' → ' + (to.name || ''),
          distanceKm: Math.round(seg.geoDistanceKm * 10) / 10,
          durationMs: Math.round(seg.travelMs)
        });
      }
      if (mediaIdx.indexOf(seg.toIndex) >= 0) photoClip(seg.toIndex);
    });
    push({
      clipId: 'outro', type: 'outro',
      stats: { stops: Math.max(0, stops.length - 1), distanceKm: Number(trip && trip.distanceKm) || 0, days: m.trip.dayCount },
      durationMs: Math.round(tl.statsMs)
    });

    return {
      schemaVersion: SCHEMA_VERSION,
      locale: 'zh-TW',
      revision: 0,
      templateId: m.template.id,
      adaptiveTier: m.adaptiveTier,
      totalMs: Math.round(tl.totalMs),
      clips: clips
    };
  }

  // 時間軸 → buildRecapManifest 的參數。只採用能對回這趟行程的片段，時長一律夾在 LIMITS 內；
  // 站與站的順序以行程為準（之後的編輯器只能改時長、刪照片片段，不能讓路線跳站）。
  function compileTimeline(trip, timeline, photos) {
    if (isAnchorTemplate(timeline && timeline.templateId)) return compileTimelineV2(trip, timeline, photos);
    var stops = Array.isArray(trip && trip.stops) ? trip.stops : [];
    var clips = Array.isArray(timeline && timeline.clips) ? timeline.clips : [];
    var available = photoStopIndices(photos, stops.length);
    var segmentMs = [];
    var holdMsByStop = [];
    var mediaStopIndices = [];
    var coverMs;
    var statsMs;
    var idIndex = {};
    stops.forEach(function (s, i) { idIndex[String(s.stopId || '')] = i; });

    clips.forEach(function (c) {
      if (!c || typeof c !== 'object') return;
      if (c.type === 'cover') coverMs = clampMs('cover', c.durationMs);
      else if (c.type === 'outro') statsMs = clampMs('outro', c.durationMs);
      else if (c.type === 'route') {
        var from = idIndex[String(c.fromStopId || '')];
        var to = idIndex[String(c.toStopId || '')];
        if (from === undefined || to !== from + 1) return;
        segmentMs[from] = clampMs('route', c.durationMs);
      } else if (c.type === 'photo') {
        var stopIdx = idIndex[String(c.assetRef && c.assetRef.stopId || '')];
        if (stopIdx === undefined || available.indexOf(stopIdx) < 0 || mediaStopIndices.indexOf(stopIdx) >= 0) return;
        mediaStopIndices.push(stopIdx);
        holdMsByStop[stopIdx] = clampMs('photo', c.durationMs);
      }
    });
    mediaStopIndices.sort(function (a, b) { return a - b; });
    var out = {
      template: timeline && timeline.templateId,
      mediaCount: mediaStopIndices.length,
      mediaStopIndices: mediaStopIndices,
      segmentMs: segmentMs,
      holdMsByStop: holdMsByStop
    };
    if (coverMs !== undefined) out.coverMs = coverMs;
    if (statsMs !== undefined) out.statsMs = statsMs;
    return out;
  }

  // 給畫面顯示的片段清單：[{ icon, text, seconds }]
  function summarizeTimeline(timeline) {
    var ICON = { cover: '🎬', route: '🚗', photo: '📷', timeline: '🌙', summary: '🗺️', outro: '📊' };
    var v2 = timeline && timeline.schemaVersion === SCHEMA_VERSION_V2;
    var rows = (timeline && timeline.clips || []).map(function (c) {
      var count = Array.isArray(c.assetRefs) ? c.assetRefs.length : 1;
      var text = c.type === 'cover' ? (v2 ? (c.source === 'photo' ? '開場：自選封面照片' : '開場：行程插圖＋標題')
          : (c.style === 'photoCut' ? '開場：實景照片硬切到地圖' : '開場：台灣全景俯衝到路線'))
        : c.type === 'route' ? (c.reveal ? '路線揭曉：從起點畫到終點' : c.label + (c.distanceKm ? '（' + c.distanceKm + ' km）' : ''))
        : c.type === 'photo' ? (c.hero ? '主角景點：' : '照片：') + c.label + (count > 1 ? '（' + count + ' 張）' : '')
          + (c.dayLabel ? '・第 ' + c.dayLabel + ' 天' : '')
        : c.type === 'timeline' ? '換日：第 ' + c.day + ' 天'
        : c.type === 'summary' ? '旅程收束：完整路線與數據'
        : (v2 ? '結尾：攻略卡' : '結尾：旅程數據');
      var icon = c.type === 'route' && c.reveal ? '✨' : (c.type === 'photo' && c.hero ? '⭐' : (ICON[c.type] || '•'));
      return { clipId: c.clipId, type: c.type, icon: icon, text: text, caption: c.caption || '', seconds: Math.round(c.durationMs / 100) / 10, photoCount: c.type === 'photo' ? count : 0, hero: !!c.hero };
    });
    return { rows: rows, totalSeconds: Math.round((timeline && timeline.totalMs || 0) / 100) / 10 };
  }

  // 時間軸 → manifest.captions（字幕軌：[{ startMs, endMs, text }]）。時間依 manifest 實際排出的時間軸計算，
  // 不信任片段上的任何時間值；只接受能對回這趟行程的片段與 CAPTION_TYPES 裡的類型。
  // 第 2 版：時間取自 manifest.timeline.phases（cover／reveal／hop／photo／summary／outro）
  function captionWindowV2(manifest, c, idIndex) {
    var phases = manifest.timeline.phases || [];
    var stops = manifest.stops || [];
    function find(pred) { return phases.filter(pred)[0] || null; }
    if (c.type === 'cover') return find(function (p) { return p.type === 'cover'; });
    if (c.type === 'summary') return find(function (p) { return p.type === 'summary'; });
    if (c.type === 'outro') return find(function (p) { return p.type === 'outro'; });
    if (c.type === 'photo') {
      var refs = Array.isArray(c.assetRefs) ? c.assetRefs : (c.assetRef ? [c.assetRef] : []);
      var s = idIndex[String(refs[0] && refs[0].stopId || '')];
      return s === undefined ? null : find(function (p) { return p.type === 'photo' && p.stopIndex === s; });
    }
    if (c.type === 'route') {
      var r = validRoute(c, idIndex, stops);
      if (!r) return null;
      if (c.reveal) return find(function (p) { return p.type === 'reveal'; });
      var hop = find(function (p) { return p.type === 'hop' && p.fromIndex === r.from && p.toIndex === r.to; });
      if (hop) return hop;
      // 整條路線開一遍的結構：這段路從「離開 from」到「抵達 to」
      if (manifest.structure === 'photoStory') return null;
      var tl = manifest.timeline;
      return { startMs: tl.arriveMs[r.from] + (tl.holdMs[r.from] || 0), endMs: tl.arriveMs[r.to] };
    }
    return null;
  }

  function attachCaptions(manifest, timeline) {
    var clips = Array.isArray(timeline && timeline.clips) ? timeline.clips : [];
    var tl = manifest.timeline;
    if (Array.isArray(tl.phases)) {
      var ids = {};
      (manifest.stops || []).forEach(function (st, i) { ids[String(st.stopId || '')] = i; });
      var list = [];
      clips.forEach(function (c) {
        if (!c || !CAPTION_TYPES[c.type]) return;
        var text = sanitizeCaption(c.caption);
        var win = text && captionWindowV2(manifest, c, ids);
        if (win && win.endMs - win.startMs >= 300) list.push({ startMs: Math.round(win.startMs), endMs: Math.round(win.endMs), text: text });
      });
      list.sort(function (a, b) { return a.startMs - b.startMs; });
      manifest.captions = list.slice(0, 80);
      return manifest;
    }
    var stops = manifest.stops || [];
    var idIndex = {};
    stops.forEach(function (st, i) { idIndex[String(st.stopId || '')] = i; });
    var depart = tl.arriveMs.map(function (a, i) { return a + (tl.holdMs[i] || 0); });
    var out = [];
    clips.forEach(function (c) {
      if (!c || !CAPTION_TYPES[c.type]) return;
      var text = sanitizeCaption(c.caption);
      if (!text) return;
      var start, end;
      if (c.type === 'cover') { start = 0; end = tl.coverMs; }
      else if (c.type === 'outro') { start = tl.totalMs - tl.statsMs; end = tl.totalMs; }
      else if (c.type === 'route') {
        var from = idIndex[String(c.fromStopId || '')];
        var to = idIndex[String(c.toStopId || '')];
        if (from === undefined || to !== from + 1) return;
        start = depart[from]; end = tl.arriveMs[to];
      } else {
        var s = idIndex[String(c.assetRef && c.assetRef.stopId || '')];
        if (s === undefined || !(tl.holdMs[s] > 0)) return;
        if (!(manifest.media || []).some(function (m) { return m.stopIndex === s; })) return;
        start = tl.arriveMs[s]; end = start + tl.holdMs[s];
      }
      if (end - start >= 300) out.push({ startMs: Math.round(start), endMs: Math.round(end), text: text });
    });
    out.sort(function (a, b) { return a.startMs - b.startMs; });
    manifest.captions = out.slice(0, 80);
    return manifest;
  }

  // ── 編輯器用的純函式（都回傳新的時間軸，不改原物件，方便「一步復原」）──
  function cloneTimeline(timeline) { return JSON.parse(JSON.stringify(timeline)); }

  function setClipDuration(timeline, clipId, durationMs) {
    var next = cloneTimeline(timeline);
    next.clips.forEach(function (c) {
      if (c.clipId !== clipId) return;
      var count = Array.isArray(c.assetRefs) ? c.assetRefs.length : 0;
      c.durationMs = clampMs(c.reveal ? 'reveal' : c.type, durationMs, count);
    });
    next.edited = true;
    return next;
  }

  function setClipCaption(timeline, clipId, caption) {
    var next = cloneTimeline(timeline);
    next.clips.forEach(function (c) {
      if (c.clipId !== clipId || !CAPTION_TYPES[c.type]) return;
      var text = sanitizeCaption(caption);
      if (text) c.caption = text; else delete c.caption;
    });
    next.edited = true;
    return next;
  }

  // 第 2 版移除照片片段後重接路線：兩個照片片段之間的路線片段合併成一段（起訖＝前後錨點、經過的站接起來），
  // 最後一張照片之後的路線拿掉（旅程收束會畫完整條路線）。
  // 這樣編輯後的時間軸仍通過「viaStopIds 必須正好是中間的站」的驗證。
  // 被移除的照片先換成標記（marker，起訖都是那一站），讓同組相鄰、原本沒有路線的站也接得起來；
  // 合併後的秒數＝原本路線秒數相加，都沒有路線時用樣板的過場秒數（defaultMs）。
  function relinkRoutesV2(clips, defaultMs) {
    var out = [];
    var pending = [];
    var lastPhotoStop = null;
    function photoStop(c) {
      var refs = Array.isArray(c.assetRefs) ? c.assetRefs : [];
      return String(refs[0] && refs[0].stopId || '');
    }
    function flush(toStop) {
      var fromStop = lastPhotoStop !== null ? lastPhotoStop : pending[0].fromStopId;
      if (pending.length === 1 && pending[0].fromStopId === fromStop && pending[0].toStopId === toStop) {
        out.push(pending[0]);
        pending = [];
        return;
      }
      var seq = [];
      pending.forEach(function (r) {
        [r.fromStopId].concat(r.viaStopIds || [], [r.toStopId]).forEach(function (id) { if (seq[seq.length - 1] !== id) seq.push(id); });
      });
      if (seq[0] !== fromStop) seq.unshift(fromStop);
      if (seq[seq.length - 1] !== toStop) seq.push(toStop);
      var b = seq.length - 1;
      var real = pending.filter(function (r) { return !r.marker; });
      if (b > 0) {
        var dur = real.reduce(function (acc, r) { return acc + (Number(r.durationMs) || 0); }, 0) || defaultMs;
        out.push({
          clipId: 'route-' + fromStop + '-' + toStop,
          type: 'route', fromStopId: fromStop, toStopId: toStop, viaStopIds: seq.slice(1, b),
          label: real.map(function (r) { return r.label; }).join('＋') || (fromStop + ' → ' + toStop),
          distanceKm: Math.round(real.reduce(function (acc, r) { return acc + (Number(r.distanceKm) || 0); }, 0) * 10) / 10,
          durationMs: clampMs('route', dur)
        });
      }
      pending = [];
    }
    clips.forEach(function (c) {
      if (c.type === 'route' && !c.reveal) { pending.push(c); return; }
      if (c.type === 'photo') {
        var stop = photoStop(c);
        if (pending.length) flush(stop);
        lastPhotoStop = stop;
        out.push(c);
        return;
      }
      if (c.type === 'summary' || c.type === 'outro') pending = [];
      out.push(c);
    });
    return out;
  }

  // 只能移除照片片段（路線與開場結尾是影片骨架，拿掉會讓地圖跳站）
  function removePhotoClip(timeline, clipId) {
    var next = cloneTimeline(timeline);
    if (next.schemaVersion === SCHEMA_VERSION_V2) {
      next.clips = next.clips.map(function (c) {
        if (!(c.clipId === clipId && c.type === 'photo')) return c;
        var refs = Array.isArray(c.assetRefs) ? c.assetRefs : [];
        var id = String(refs[0] && refs[0].stopId || '');
        return { type: 'route', marker: true, clipId: 'marker', fromStopId: id, toStopId: id, viaStopIds: [], durationMs: 0 };
      });
      var tpl = MANIFEST ? MANIFEST.resolveTemplate(next.templateId) : null;
      next.clips = relinkRoutesV2(next.clips, (tpl && tpl.hopMs) || 700);
    } else {
      next.clips = next.clips.filter(function (c) { return !(c.clipId === clipId && c.type === 'photo'); });
    }
    next.clips.forEach(function (c, i) { c.order = i; });
    next.edited = true;
    return next;
  }

  // 第 2 版：主角景點（或一站多張）的卡片可以個別移除照片；只剩一張再移除＝移除整個片段。
  // 片段長度依張數等比例縮短，維持每張的秒數。
  function removeClipPhoto(timeline, clipId, refIndex) {
    var target = (timeline && timeline.clips || []).filter(function (c) { return c.clipId === clipId && c.type === 'photo'; })[0];
    if (!target || !Array.isArray(target.assetRefs)) return cloneTimeline(timeline);
    if (target.assetRefs.length <= 1) return removePhotoClip(timeline, clipId);
    var next = cloneTimeline(timeline);
    next.clips.forEach(function (c) {
      if (c.clipId !== clipId) return;
      var n = c.assetRefs.length;
      if (refIndex < 0 || refIndex >= n) return;
      c.assetRefs.splice(refIndex, 1);
      c.durationMs = clampMs('photo', c.durationMs * (n - 1) / n, n - 1);
    });
    next.edited = true;
    return next;
  }

  // AI 劇本建議 → 新時間軸：只填字幕、調整照片片段的長短；不新增片段、不改順序。
  // suggestion：{ cover, outro, clips: { [clipId]: { caption, emphasis: 'long'|'normal'|'short' } } }
  // opts.keepExisting：使用者已經打好的字幕不覆蓋（只填空白的）
  function applyScriptSuggestion(timeline, suggestion, opts) {
    var keepExisting = !!(opts && opts.keepExisting);
    var next = cloneTimeline(timeline);
    var s = suggestion && typeof suggestion === 'object' ? suggestion : {};
    var byClip = s.clips && typeof s.clips === 'object' ? s.clips : {};
    var FACTOR = { long: 1.35, normal: 1, short: 0.75 };
    next.clips.forEach(function (c) {
      var sug = c.type === 'cover' ? { caption: s.cover } : c.type === 'outro' ? { caption: s.outro } : byClip[c.clipId];
      if (!sug || typeof sug !== 'object') return;
      if (CAPTION_TYPES[c.type] && !(keepExisting && c.caption)) {
        var text = sanitizeCaption(sug.caption);
        if (text) c.caption = text;
      }
      // 固定 15 秒的版本不改長短（改了總長就不是 15 秒）
      if (c.type === 'photo' && FACTOR[sug.emphasis] && next.lengthMode !== '15') c.durationMs = clampMs('photo', c.durationMs * FACTOR[sug.emphasis], Array.isArray(c.assetRefs) ? c.assetRefs.length : 0);
    });
    next.edited = true;
    next.scriptSource = 'ai';
    return next;
  }

  // 依目前片段重新算總長（與後端同一套 manifest 計算）
  function timelineTotalMs(trip, timeline, photos) {
    if (!MANIFEST) return Number(timeline && timeline.totalMs) || 0;
    return Math.round(MANIFEST.buildRecapManifest(trip, compileTimeline(trip, timeline, photos)).timeline.totalMs);
  }

  return {
    SCHEMA_VERSION: SCHEMA_VERSION,
    SCHEMA_VERSION_V2: SCHEMA_VERSION_V2,
    LIMITS: LIMITS,
    isAnchorTemplate: isAnchorTemplate,
    removeClipPhoto: removeClipPhoto,
    CAPTION_MAX: CAPTION_MAX,
    sanitizeCaption: sanitizeCaption,
    attachCaptions: attachCaptions,
    setClipDuration: setClipDuration,
    setClipCaption: setClipCaption,
    removePhotoClip: removePhotoClip,
    applyScriptSuggestion: applyScriptSuggestion,
    timelineTotalMs: timelineTotalMs,
    buildRecommendedTimeline: buildRecommendedTimeline,
    compileTimeline: compileTimeline,
    summarizeTimeline: summarizeTimeline
  };
}));
