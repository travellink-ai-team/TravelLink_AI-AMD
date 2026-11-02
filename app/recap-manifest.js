/*
 * TravelLink AI recap manifest builder.
 * This file intentionally has no runtime dependencies so it can be loaded by
 * a plain browser <script> tag as well as by Node.js.
 */
(function (root, factory) {
  var templates = null;
  if (typeof module === 'object' && module.exports) {
    try { templates = require('./recap-templates.js'); } catch (e) { templates = null; }
    module.exports = factory(templates);
  } else {
    root.WAI_RECAP_MANIFEST = factory(root.WAI_RECAP_TEMPLATES || null);
  }
}(typeof globalThis !== 'undefined' ? globalThis : this, function (TEMPLATES) {
  'use strict';

  // 沒載入 recap-templates.js 時（例如舊的無頭渲染頁）用這組，數值等於「標準」樣板
  var FALLBACK_TEMPLATE = {
    id: 'standard', label: '標準',
    coverMs: 3400, statsMs: 3000, dwellMs: 200, photoMs: 2600, dayTransitionMs: 800,
    targetMs: { '0': 14500, '1-2': 18000, '3-5': 23000, '6+': 32000 },
    minRouteMs: 6000,
    segment: { minMs: 900, maxShare: 0.45, ease: 'smooth' },
    cover: { style: 'title' }
  };

  function resolveTemplate(value) {
    if (value && typeof value === 'object') return value;
    if (TEMPLATES && typeof TEMPLATES.getTemplate === 'function') return TEMPLATES.getTemplate(value);
    return FALLBACK_TEMPLATE;
  }

  var EARTH_RADIUS_KM = 6371;

  function haversineKm(lat1, lng1, lat2, lng2) {
    var toRadians = Math.PI / 180;
    var deltaLat = (Number(lat2) - Number(lat1)) * toRadians;
    var deltaLng = (Number(lng2) - Number(lng1)) * toRadians;
    var startLat = Number(lat1) * toRadians;
    var endLat = Number(lat2) * toRadians;
    var sinLat = Math.sin(deltaLat / 2);
    var sinLng = Math.sin(deltaLng / 2);
    var a = sinLat * sinLat + Math.cos(startLat) * Math.cos(endLat) * sinLng * sinLng;
    var c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));

    return EARTH_RADIUS_KM * c;
  }

  function optionOrDefault(options, key, defaultValue) {
    return options[key] === undefined ? defaultValue : options[key];
  }

  // 片長目標依真實素材數分四級（樣板決定每級的秒數）；素材不足就縮短，不用假畫面補長度
  function getAdaptiveSettings(mediaCount, coverMs, statsMs, template) {
    var tier = mediaCount === 0 ? '0' : (mediaCount <= 2 ? '1-2' : (mediaCount <= 5 ? '3-5' : '6+'));
    var targets = (template && template.targetMs) || FALLBACK_TEMPLATE.targetMs;
    var targetMs = Number(targets[tier]) || FALLBACK_TEMPLATE.targetMs[tier];
    var minRouteMs = Number(template && template.minRouteMs) || FALLBACK_TEMPLATE.minRouteMs;

    return {
      tier: tier,
      routeMs: Math.max(minRouteMs, targetMs - coverMs - statsMs)
    };
  }

  // 站間路線時間：依距離比例分配，但每段至少 minMs、最多 maxMs（再把多出／不足的時間分給其他段）。
  // 純依距離時，200 公尺的短程只有幾十毫秒、看不清楚，一段長途又會吃掉整支影片。
  function allocateSegmentMs(distances, indices, totalMs, segmentCfg) {
    var out = {};
    var count = indices.length;
    if (!count) return out;
    // 站多時（例如 10 段只有 8 秒）下限會吃光全部時間、每段變成一樣長 → 下限最多取平均的 60%，仍依距離分配
    var minMs = Math.min(Math.max(0, Number(segmentCfg && segmentCfg.minMs) || 0), (totalMs / count) * 0.6);
    var maxShare = Number(segmentCfg && segmentCfg.maxShare) || 1;
    var maxMs = Math.max(minMs, totalMs * maxShare, totalMs / count);
    var fixed = {};
    var round;
    for (round = 0; round <= count; round += 1) {
      var remaining = totalMs;
      var free = [];
      var freeDist = 0;
      indices.forEach(function (i) {
        if (fixed[i] !== undefined) remaining -= fixed[i];
        else { free.push(i); freeDist += distances[i]; }
      });
      if (!free.length) break;
      var changed = false;
      free.forEach(function (i) {
        var v = freeDist > 0 ? remaining * (distances[i] / freeDist) : remaining / free.length;
        if (v < minMs) { fixed[i] = minMs; changed = true; }
        else if (v > maxMs) { fixed[i] = maxMs; changed = true; }
        else out[i] = v;
      });
      if (!changed) break;
      free.forEach(function (i) { delete out[i]; });
    }
    indices.forEach(function (i) { if (fixed[i] !== undefined) out[i] = fixed[i]; });
    // 每段都卡在下限或上限時，總和可能不到 totalMs → 剩下的時間按比例加回各段，片長才符合樣板
    var sum = indices.reduce(function (acc, i) { return acc + out[i]; }, 0);
    if (sum > 0 && sum < totalMs - 1) {
      var scale = totalMs / sum;
      indices.forEach(function (i) { out[i] *= scale; });
    }
    return out;
  }

  function calculateDayCount(stops) {
    var dayCount = 1;
    var i;
    var value;

    for (i = 0; i < stops.length; i += 1) {
      value = Number(stops[i].dayIndex);
      if (isFinite(value)) {
        dayCount = Math.max(dayCount, value);
      }
    }

    return dayCount;
  }

  function copyStop(stop, index) {
    return {
      index: index,
      stopId: stop.stopId,
      name: stop.name,
      lat: stop.lat,
      lng: stop.lng,
      mode: stop.mode,
      stayMin: stop.stayMin,
      dayIndex: stop.dayIndex,
      arriveTime: stop.arriveTime || ''
    };
  }

  // ── structure 'anchors'（Reels 節奏，IG 分享優化提案 A7～A10、A19）──
  // 只在有照片的站（錨點）之間播路線；中段依照片張數切三種結構：
  //   photoStory（3 張以上）：開場 → 路線揭曉 → [過場 → 照片] × N → 旅程收束 → 攻略卡
  //   mapFeatured（1～2 張）：開場 → 整條路線開一遍、到錨點放滿版照片 → 旅程收束 → 攻略卡
  //   routeStory（0 張）：開場 → 路線揭曉 → 整條路線開一遍、依序聚焦幾站 → 旅程收束 → 攻略卡
  // 輸出沿用舊 manifest 的形狀（segments.travelMs、timeline.holdMs/arriveMs、media），另加 timeline.phases
  // 給渲染器與字幕軌用；換日不產生 dayBreaks，改在當天第一張照片標 dayLabel。

  function structureFor(photoTotal) {
    return photoTotal >= 3 ? 'photoStory' : (photoTotal >= 1 ? 'mapFeatured' : 'routeStory');
  }

  // 主角景點沒指定時：照片最多（至少 2 張）的站；都只有 1 張時取停留最久的有照片站；資料不足就不設
  function pickHeroStop(stops, counts) {
    var best = -1;
    var i;
    for (i = 0; i < counts.length; i += 1) {
      if (counts[i] >= 2 && (best < 0 || counts[i] > counts[best])) best = i;
    }
    if (best >= 0) return best;
    for (i = 0; i < counts.length; i += 1) {
      var stay = Number(stops[i] && stops[i].stayMin) || 0;
      if (counts[i] > 0 && stay > 0 && (best < 0 || stay > (Number(stops[best].stayMin) || 0))) best = i;
    }
    return best;
  }

  // 一個錨點的照片總長：主角每張 heroMs（只有 1 張時 heroSingleMs），一般每張 photoMs；
  // 固定 15 秒時主角 1 張 2.4 秒、2 張各 1 秒、3 張以上取 3 張各 0.8 秒
  function anchorPhotoMs(count, isHero, template, lengthMode) {
    if (!isHero) return count * template.photoMs;
    if (lengthMode === '15') {
      var f = template.fixed15 || {};
      if (count === 1) return f.heroSingleMs || 2400;
      if (count === 2) return 2 * (f.heroMs || 1000);
      return (f.heroManyCount || 3) * (f.heroManyMs || 800);
    }
    return count === 1 ? template.heroSingleMs : count * template.heroMs;
  }

  // 錨點 → 分組：相鄰兩站都有照片時兩站一組連續放（中間不播路線），主角景點自己一組
  function groupAnchors(anchors, hero) {
    var groups = [];
    anchors.forEach(function (idx) {
      var last = groups[groups.length - 1];
      var prev = last && last[last.length - 1];
      if (last && last.length < 2 && prev === idx - 1 && idx !== hero && prev !== hero) last.push(idx);
      else groups.push([idx]);
    });
    return groups;
  }

  // 從錨點組出 plan：[{ stopIndex, count, hero, photoMs, hopBefore }]；hopBefore＝放這站前要不要先播一段過場
  function planFromAnchors(anchors, counts, hero, template, lengthMode) {
    var plan = [];
    groupAnchors(anchors, hero).forEach(function (g, gi) {
      g.forEach(function (idx, k) {
        var isHero = idx === hero;
        plan.push({
          stopIndex: idx,
          count: counts[idx],
          hero: isHero,
          photoMs: anchorPhotoMs(counts[idx], isHero, template, lengthMode),
          hopBefore: k === 0 && !(gi === 0 && idx === 0)
        });
      });
    });
    return plan;
  }

  function planMs(plan, hopMs) {
    return plan.reduce(function (acc, p) { return acc + p.photoMs + (p.hopBefore ? hopMs : 0); }, 0);
  }

  // 放不下時依序：先減照片多的一般站 → 主角減到 3 張 → 拿掉一個一般錨點（挑拿掉後空隙最小的，讓照片分布平均）
  function fitAnchorsToBudget(anchors, counts, hero, template, lengthMode, budgetMs) {
    var list = anchors.slice();
    var c = counts.slice();
    var guard = 0;
    while (guard < 400) {
      guard += 1;
      var plan = planFromAnchors(list, c, hero, template, lengthMode);
      if (planMs(plan, template.hopMs) <= budgetMs || !list.length) return { anchors: list, counts: c, plan: plan };
      var most = -1;
      list.forEach(function (idx) { if (idx !== hero && c[idx] > 1 && (most < 0 || c[idx] > c[most])) most = idx; });
      if (most >= 0) { c[most] -= 1; continue; }
      if (hero >= 0 && c[hero] > 3) { c[hero] -= 1; continue; }
      var removable = list.filter(function (idx) { return idx !== hero; });
      if (!removable.length) return { anchors: list, counts: c, plan: plan };
      var bestPos = -1, bestGap = Infinity;
      list.forEach(function (idx, pos) {
        if (idx === hero) return;
        var left = pos > 0 ? list[pos - 1] : 0;
        var right = pos < list.length - 1 ? list[pos + 1] : counts.length - 1;
        if (right - left < bestGap) { bestGap = right - left; bestPos = pos; }
      });
      c[list[bestPos]] = 0;
      list.splice(bestPos, 1);
    }
    return { anchors: list, counts: c, plan: planFromAnchors(list, c, hero, template, lengthMode) };
  }

  // 一段過場（from → to，可跨好幾站）的總時長依距離分給中間各段；跨天段不畫車（0ms，直接跳到隔天）
  function spreadHop(travel, distances, breakFlags, from, to, ms, segmentCfg) {
    var idx = [];
    for (var i = from; i < to; i += 1) { if (!breakFlags[i]) idx.push(i); else travel[i] = 0; }
    if (!idx.length) return;
    var alloc = allocateSegmentMs(distances, idx, ms, { minMs: Math.min(Number(segmentCfg && segmentCfg.minMs) || 0, ms / idx.length * 0.6), maxShare: 1 });
    idx.forEach(function (k) { travel[k] = alloc[k]; });
  }

  function buildAnchorManifest(trip, options, template) {
    var sourceStops = Array.isArray(trip.stops) ? trip.stops : [];
    var n = sourceStops.length;
    var lengthMode = options.lengthMode === '15' ? '15' : 'all';
    var fixed15 = template.fixed15 || { totalMs: 15000 };
    var i;

    // 每站可用的照片張數：photoCounts 優先；舊參數 mediaStopIndices 視為每站 1 張
    var counts = [];
    for (i = 0; i < n; i += 1) counts.push(0);
    if (Array.isArray(options.photoCounts)) {
      options.photoCounts.forEach(function (v, k) { if (k < n) counts[k] = Math.max(0, Math.min(20, Math.floor(Number(v) || 0))); });
    } else if (Array.isArray(options.mediaStopIndices)) {
      options.mediaStopIndices.forEach(function (k) { if (k >= 0 && k < n) counts[k] = Math.max(counts[k], 1); });
    }
    var photoTotal = counts.reduce(function (a, b) { return a + b; }, 0);
    var structure = structureFor(photoTotal);

    // heroStopIndex：undefined＝依規則挑；-1＝明確不設主角（時間軸上沒有標 hero 的照片片段）
    var hero = -1;
    if (photoTotal > 0 && options.heroStopIndex !== -1) {
      var heroIn = Number(options.heroStopIndex);
      hero = (options.heroStopIndex !== undefined && options.heroStopIndex !== null && isFinite(heroIn) && counts[heroIn] > 0)
        ? heroIn : pickHeroStop(sourceStops, counts);
    }

    var coverMs = optionOrDefault(options, 'coverMs', template.coverMs);
    var statsMs = optionOrDefault(options, 'statsMs', template.statsMs);
    var summaryMs = optionOrDefault(options, 'summaryMs', template.summaryMs);
    var revealMs = structure === 'mapFeatured' ? 0 : optionOrDefault(options, 'revealMs', template.revealMs);
    var hopMs = template.hopMs;

    // 距離與跨天
    var distances = [];
    var breakFlags = [];
    for (i = 0; i < n - 1; i += 1) {
      distances.push(haversineKm(sourceStops[i].lat, sourceStops[i].lng, sourceStops[i + 1].lat, sourceStops[i + 1].lng));
      breakFlags.push(Number(sourceStops[i].dayIndex || 1) !== Number(sourceStops[i + 1].dayIndex || 1));
    }
    var travel = distances.map(function () { return 0; });
    var holdMs = sourceStops.map(function () { return 0; });
    var photoSel = options.photoSelection && typeof options.photoSelection === 'object' ? options.photoSelection : {};

    // 錨點計畫：時間軸 compile 來的 anchorPlan 直接用（使用者編輯過的版本），否則依規則排
    var plan;
    var anchors = [];
    for (i = 0; i < n; i += 1) if (counts[i] > 0) anchors.push(i);
    if (Array.isArray(options.anchorPlan)) {
      plan = options.anchorPlan.filter(function (p) { return p && counts[p.stopIndex] > 0; }).map(function (p) {
        var cnt = Math.max(1, Math.min(counts[p.stopIndex], Math.floor(Number(p.count) || counts[p.stopIndex])));
        var isHero = p.stopIndex === hero;
        return {
          stopIndex: p.stopIndex, count: cnt, hero: isHero,
          photoMs: Number(p.photoMs) > 0 ? Number(p.photoMs) : anchorPhotoMs(cnt, isHero, template, lengthMode),
          hopBefore: p.hopBefore !== false,
          hopMs: Number(p.hopMs) > 0 ? Number(p.hopMs) : null
        };
      });
    } else if (structure === 'photoStory') {
      var startCounts = counts.slice();
      if (lengthMode === '15') {
        anchors.forEach(function (idx) { startCounts[idx] = idx === hero ? Math.min(startCounts[idx], fixed15.heroManyCount || 3) : 1; });
      }
      var budget = lengthMode === '15'
        ? fixed15.totalMs - coverMs - revealMs - summaryMs - statsMs
        : (Number(template.maxMs) || 30000) - coverMs - revealMs - summaryMs - statsMs;
      var fitted = fitAnchorsToBudget(anchors, startCounts, hero, template, lengthMode, budget);
      plan = fitted.plan;
      // 固定 15 秒：剩下的時間按比例加到一般照片上（沒有一般照片就加到主角），總長剛好 15 秒
      if (lengthMode === '15') {
        var used = planMs(plan, hopMs);
        var spare = budget - used;
        var normal = plan.filter(function (p) { return !p.hero; });
        var target = normal.length ? normal : plan;
        var base = target.reduce(function (a, p) { return a + p.photoMs; }, 0);
        if (spare > 0 && base > 0) target.forEach(function (p) { p.photoMs += spare * (p.photoMs / base); });
      }
    } else {
      plan = planFromAnchors(anchors, counts, hero, template, lengthMode).map(function (p) { p.hopBefore = false; return p; });
    }

    // 時間排程
    var arriveMs = [];
    var phases = [];
    var media = [];
    var dayLabels = [];
    var t = 0;
    phases.push({ type: 'cover', startMs: 0, endMs: coverMs });
    t = coverMs;
    if (revealMs > 0) { phases.push({ type: 'reveal', startMs: t, endMs: t + revealMs }); t += revealMs; }
    var routeStartMs = t;
    var dayCountAll = calculateDayCount(sourceStops);
    var lastDayShown = 0;

    function addPhotoPhase(p) {
      var start = t;
      var idxs = Array.isArray(photoSel[p.stopIndex]) ? photoSel[p.stopIndex].slice(0, p.count) : null;
      var each = p.photoMs / p.count;
      var day = Number(sourceStops[p.stopIndex].dayIndex || 1);
      var label = dayCountAll > 1 && day !== lastDayShown ? day : 0;
      if (label) { dayLabels.push({ stopIndex: p.stopIndex, day: day, atMs: start }); lastDayShown = day; }
      for (var k = 0; k < p.count; k += 1) {
        media.push({ kind: 'photo', stopIndex: p.stopIndex, photoIndex: idxs ? idxs[k] : k, atMs: start + each * k, durationMs: each, hero: p.hero, src: null });
      }
      phases.push({ type: 'photo', startMs: start, endMs: start + p.photoMs, stopIndex: p.stopIndex, count: p.count, hero: p.hero, dayLabel: label || 0 });
      holdMs[p.stopIndex] = p.photoMs;
      t += p.photoMs;
    }

    if (structure === 'photoStory') {
      var cur = 0;
      plan.forEach(function (p) {
        if (p.stopIndex > cur && p.hopBefore) {
          var ms = p.hopMs || hopMs;
          spreadHop(travel, distances, breakFlags, cur, p.stopIndex, ms, template.segment);
          var via = [];
          for (var v = cur + 1; v < p.stopIndex; v += 1) via.push(v);
          phases.push({ type: 'hop', startMs: t, endMs: t + ms, fromIndex: cur, toIndex: p.stopIndex, viaIndices: via });
          t += ms;
        }
        // 同組相鄰站（或沒有過場）：車直接跳到下一站
        cur = p.stopIndex;
        addPhotoPhase(p);
      });
    } else {
      // 整條路線開一遍：mapFeatured 在錨點停下放照片；routeStory 依序聚焦幾站
      var photoSum = plan.reduce(function (a, p) { return a + p.photoMs; }, 0);
      var focus = [];
      if (structure === 'routeStory') {
        var middle = [];
        for (i = 1; i < n - 1; i += 1) middle.push(i);
        var want = Math.min(template.focusMax || 4, middle.length);
        for (i = 0; i < want; i += 1) focus.push(middle[Math.floor((i + 0.5) * middle.length / want)]);
      }
      var focusSum = focus.length * (template.focusMs || 600);
      var driveMs = structure === 'routeStory' ? template.routeOnlyMs : template.featuredRouteMs;
      if (options.driveMs > 0) driveMs = Number(options.driveMs);
      else if (lengthMode === '15') driveMs = Math.max(3000, fixed15.totalMs - coverMs - revealMs - summaryMs - statsMs - photoSum - focusSum);
      spreadHop(travel, distances, breakFlags, 0, n - 1, driveMs, template.segment);
      var planBy = {};
      plan.forEach(function (p) { planBy[p.stopIndex] = p; });
      var focusSet = {};
      focus.forEach(function (k) { focusSet[k] = true; });
      phases.push({ type: 'drive', startMs: t, endMs: null, fromIndex: 0, toIndex: n - 1 });
      var driveStart = phases.length - 1;
      for (i = 0; i < n; i += 1) {
        if (i > 0) t += travel[i - 1];
        if (planBy[i]) addPhotoPhase(planBy[i]);
        else if (focusSet[i]) {
          var fm = template.focusMs || 600;
          phases.push({ type: 'focus', startMs: t, endMs: t + fm, stopIndex: i });
          holdMs[i] = fm;
          t += fm;
        }
      }
      phases[driveStart].endMs = t;
    }

    // 抵達時間：從路線開始依 travel/hold 累加
    var acc = routeStartMs;
    for (i = 0; i < n; i += 1) {
      if (i > 0) acc += holdMs[i - 1] + travel[i - 1];
      arriveMs.push(acc);
    }
    // photoStory 的過場與照片穿插，arriveMs 以 phases 為準（錨點＝照片開始、經過站＝過場中按距離內插）
    if (structure === 'photoStory') {
      var hopPhases = phases.filter(function (ph) { return ph.type === 'hop'; });
      var photoStart = {};
      phases.forEach(function (ph) { if (ph.type === 'photo') photoStart[ph.stopIndex] = ph.startMs; });
      arriveMs[0] = routeStartMs;
      for (i = 1; i < n; i += 1) {
        if (photoStart[i] !== undefined) { arriveMs[i] = photoStart[i]; continue; }
        var hp = hopPhases.filter(function (h) { return h.fromIndex < i && h.toIndex > i; })[0];
        if (hp) {
          var total = 0, upto = 0;
          for (var s = hp.fromIndex; s < hp.toIndex; s += 1) { total += travel[s]; if (s < i) upto += travel[s]; }
          arriveMs[i] = hp.startMs + (total > 0 ? (hp.endMs - hp.startMs) * upto / total : 0);
        } else {
          arriveMs[i] = arriveMs[i - 1] + holdMs[i - 1];   // 最後一個錨點之後的站：收束時一次畫完
        }
      }
    }

    var routeEndMs = t;
    phases.push({ type: 'summary', startMs: t, endMs: t + summaryMs });
    t += summaryMs;
    phases.push({ type: 'outro', startMs: t, endMs: t + statsMs });
    t += statsMs;

    var stops = sourceStops.map(copyStop);
    var segments = distances.map(function (d, k) {
      return {
        fromIndex: k, toIndex: k + 1,
        mode: sourceStops[k + 1].mode || sourceStops[k].mode || trip.transportMode,
        geoDistanceKm: d, travelMs: travel[k], dayBreak: breakFlags[k],
        toDay: Number(sourceStops[k + 1].dayIndex || 1), points: null
      };
    });
    var segmentCfg = template.segment || {};
    var tier = photoTotal === 0 ? '0' : (photoTotal <= 2 ? '1-2' : (photoTotal <= 5 ? '3-5' : '6+'));
    return {
      version: 3,
      structure: structure,
      lengthMode: lengthMode,
      heroStopIndex: hero,
      template: { id: template.id, label: template.label, segmentEase: segmentCfg.ease === 'linear' ? 'linear' : 'smooth', structure: 'anchors' },
      cover: { style: 'title' },
      trip: tripInfo(trip, sourceStops),
      transportMode: trip.transportMode,
      stops: stops,
      segments: segments,
      timeline: {
        coverMs: coverMs, revealMs: revealMs, routeMs: routeEndMs - routeStartMs, summaryMs: summaryMs, statsMs: statsMs,
        dwellMs: 0, photoMs: template.photoMs, holdMs: holdMs, totalMs: Math.round(t), arriveMs: arriveMs,
        routeStartMs: routeStartMs, routeEndMs: routeEndMs, dayBreaks: [], dayLabels: dayLabels, phases: phases
      },
      adaptiveTier: tier,
      media: media,
      subtitles: []
    };
  }

  function tripInfo(trip, sourceStops) {
    var cost = Number(trip.costPerPerson);
    return {
      title: trip.title,
      region: trip.region,
      dateLabel: trip.dateLabel,
      people: trip.people,
      distanceKm: trip.distanceKm,
      days: trip.days,
      dayCount: calculateDayCount(sourceStops),
      costPerPerson: isFinite(cost) && cost > 0 ? Math.round(cost) : null,
      highlightStopId: trip.highlightStopId || ''
    };
  }

  // opts.template：樣板 id 或樣板物件；opts 裡明確給的時間值（coverMs、photoMs…）優先於樣板。
  // opts.segmentMs[i]：第 i 段路線的時長（由推薦時間軸 compile 而來，覆蓋距離分配）
  // opts.holdMsByStop[i]：第 i 站的停留時長（照片片段的秒數）
  // structure 'anchors' 的樣板另吃：photoCounts[i]、heroStopIndex、lengthMode、anchorPlan、photoSelection
  function buildRecapManifest(trip, opts) {
    var options = opts || {};
    var template = resolveTemplate(options.template);
    if (template.structure === 'anchors') return buildAnchorManifest(trip, options, template);
    var sourceStops = Array.isArray(trip.stops) ? trip.stops : [];
    var mediaIndicesIn = Array.isArray(options.mediaStopIndices) ? options.mediaStopIndices : [];
    // 電影感開場需要一張實景照片；沒有照片就退回標題卡開場
    var coverCfg = template.cover || { style: 'title' };
    var usePhotoCut = coverCfg.style === 'photoCut' && mediaIndicesIn.length > 0;
    var coverMs = optionOrDefault(options, 'coverMs',
      usePhotoCut ? (Number(coverCfg.photoMs) || 1430) + (Number(coverCfg.beatMs) || 320) : template.coverMs);
    var statsMs = optionOrDefault(options, 'statsMs', template.statsMs);
    var dwellMs = optionOrDefault(options, 'dwellMs', template.dwellMs);
    var mediaCount = optionOrDefault(options, 'mediaCount', 0);
    var adaptive = getAdaptiveSettings(mediaCount, coverMs, statsMs, template);
    var routeMs = options.routeMs === null || options.routeMs === undefined
      ? adaptive.routeMs
      : options.routeMs;
    var stops = [];
    var segments = [];
    var distances = [];
    var totalDistance = 0;
    var arriveMs = [];
    var subtitles = [];
    var currentMs;
    var i;
    var distance;
    var travelMs;
    var lastStopIndex = sourceStops.length - 1;

    for (i = 0; i < sourceStops.length; i += 1) {
      stops.push(copyStop(sourceStops[i], i));
    }

    for (i = 0; i < sourceStops.length - 1; i += 1) {
      distance = haversineKm(
        sourceStops[i].lat,
        sourceStops[i].lng,
        sourceStops[i + 1].lat,
        sourceStops[i + 1].lng
      );
      distances.push(distance);
      totalDistance += distance;
    }

    // 多日行程：跨天段（前後站 dayIndex 不同）不算「開車」，改成固定時長的「換日轉場」，
    // 且不參與 routeMs 的距離分配 —— 否則跨天長跳會吃掉全片最長動畫、還畫出一條沒開過的連線。
    var dayTransitionMs = optionOrDefault(options, 'dayTransitionMs', template.dayTransitionMs);
    var breakFlags = [];
    var drivingDistance = 0;
    for (i = 0; i < distances.length; i += 1) {
      var isBreak = Number(sourceStops[i].dayIndex || 1) !== Number(sourceStops[i + 1].dayIndex || 1);
      breakFlags.push(isBreak);
      if (!isBreak) drivingDistance += distances[i];
    }
    var breakCount = breakFlags.filter(function (b) { return b; }).length;
    var drivingMs = Math.max(3000, routeMs - breakCount * dayTransitionMs);
    var drivingIndices = [];
    for (i = 0; i < distances.length; i += 1) { if (!breakFlags[i]) drivingIndices.push(i); }
    var allocated = allocateSegmentMs(distances, drivingIndices, drivingMs, template.segment);
    var segmentMsIn = Array.isArray(options.segmentMs) ? options.segmentMs : null;

    for (i = 0; i < distances.length; i += 1) {
      if (breakFlags[i]) {
        travelMs = dayTransitionMs;
      } else {
        travelMs = allocated[i] !== undefined ? allocated[i] : drivingMs / Math.max(1, drivingIndices.length);
      }
      if (segmentMsIn && isFinite(Number(segmentMsIn[i])) && Number(segmentMsIn[i]) > 0) travelMs = Number(segmentMsIn[i]);
      segments.push({
        fromIndex: i,
        toIndex: i + 1,
        mode: sourceStops[i + 1].mode || sourceStops[i].mode || trip.transportMode,
        geoDistanceKm: distances[i],
        travelMs: travelMs,
        dayBreak: breakFlags[i],
        toDay: Number(sourceStops[i + 1].dayIndex || 1),
        points: null
      });
    }

    // 媒體站：有照片的站在到站時多停留 photoMs 顯示照片（含 Ken Burns）。
    var photoMs = optionOrDefault(options, 'photoMs', template.photoMs);
    var mediaSet = {};
    mediaIndicesIn.forEach(function (idx) { mediaSet[idx] = true; });
    var holdIn = Array.isArray(options.holdMsByStop) ? options.holdMsByStop : null;
    var holdMs = [];
    for (i = 0; i < sourceStops.length; i += 1) {
      var isMiddle = i > 0 && i < lastStopIndex;
      var h = mediaSet[i] ? photoMs : (isMiddle ? dwellMs : 0);
      if (mediaSet[i] && holdIn && isFinite(Number(holdIn[i])) && Number(holdIn[i]) > 0) h = Number(holdIn[i]);
      holdMs.push(h);
    }

    var media = [];
    var dayBreaks = [];
    if (sourceStops.length > 0) {
      arriveMs.push(coverMs);
      currentMs = coverMs;
      if (mediaSet[0]) media.push({ kind: 'photo', stopIndex: 0, atMs: coverMs, durationMs: holdMs[0], src: null });
      currentMs += holdMs[0];

      for (i = 0; i < segments.length; i += 1) {
        if (segments[i].dayBreak) {
          // 換日轉場窗：從出發（currentMs）到抵達隔天第一站
          dayBreaks.push({ startMs: currentMs, endMs: currentMs + segments[i].travelMs, day: segments[i].toDay, toStopIndex: i + 1 });
        }
        currentMs += segments[i].travelMs;
        arriveMs.push(currentMs);
        subtitles.push({ atMs: currentMs, stopIndex: i + 1, text: sourceStops[i + 1].name });
        var si = i + 1;
        if (mediaSet[si]) media.push({ kind: 'photo', stopIndex: si, atMs: currentMs, durationMs: holdMs[si], src: null });
        currentMs += holdMs[si];
      }
    }
    var totalHold = holdMs.reduce(function (a, b) { return a + b; }, 0);
    // 實際路線動畫時間（開車段＋換日轉場），可能與 routeMs 略有出入
    var actualRouteMs = segments.reduce(function (a, s) { return a + s.travelMs; }, 0);

    var segmentCfg = template.segment || {};
    return {
      version: 2,
      template: { id: template.id, label: template.label, segmentEase: segmentCfg.ease === 'linear' ? 'linear' : 'smooth' },
      // 開場：photoCut 時前 photoMs 放實景照片（取第一張有照片的站），硬切到地圖再停 beatMs
      cover: usePhotoCut
        ? { style: 'photoCut', photoStopIndex: mediaIndicesIn[0], photoMs: Number(coverCfg.photoMs) || 1430, beatMs: Number(coverCfg.beatMs) || 320 }
        : { style: 'title' },
      trip: tripInfo(trip, sourceStops),
      transportMode: trip.transportMode,
      stops: stops,
      segments: segments,
      timeline: {
        coverMs: coverMs,
        routeMs: routeMs,
        statsMs: statsMs,
        dwellMs: dwellMs,
        photoMs: photoMs,
        holdMs: holdMs,
        totalMs: coverMs + actualRouteMs + totalHold + statsMs,
        arriveMs: arriveMs,
        dayBreaks: dayBreaks
      },
      adaptiveTier: adaptive.tier,
      media: media,
      subtitles: subtitles
    };
  }

  return {
    buildRecapManifest: buildRecapManifest,
    resolveTemplate: resolveTemplate,
    structureFor: structureFor,
    pickHeroStop: pickHeroStop,
    allocateSegmentMs: allocateSegmentMs
  };
}));
