/*
 * recap-render.js — 旅程回顧短片「共用渲染器」（manifest 驅動）
 * 前端預覽 canvas 與後端無頭渲染（Puppeteer→ffmpeg）共用同一支繪圖邏輯。
 * 相依：recap-manifest.js（產 manifest）。座標投影與鏡頭在本檔內以確定性方式計算，
 * 讓 renderFrameAtMs(t) 對同一 t 永遠畫出同一幀（後端逐幀截圖需要）。
 * plain script（file:// 可跑，非 module）；同時可在 Node require。
 */
(function (root, factory) {
  if (typeof module !== 'undefined' && module.exports) module.exports = factory();
  else root.WAI_RECAP_RENDER = factory();
})(typeof self !== 'undefined' ? self : this, function () {
  'use strict';

  var EMOJI = '"Segoe UI Emoji","Noto Color Emoji",sans-serif';
  var SANS = '"Noto Sans TC",sans-serif';

  // ── 幾何小工具 ──
  function rr(ctx, a, b, w, h, r) {
    r = Math.min(r, w / 2, h / 2);
    ctx.beginPath();
    ctx.moveTo(a + r, b);
    ctx.arcTo(a + w, b, a + w, b + h, r);
    ctx.arcTo(a + w, b + h, a, b + h, r);
    ctx.arcTo(a, b + h, a, b, r);
    ctx.arcTo(a, b, a + w, b, r);
    ctx.closePath();
  }
  function ease(t) { return t * t * (3 - 2 * t); }
  function clamp(v, lo, hi) { return v < lo ? lo : (v > hi ? hi : v); }
  function lerp(a, b, t) { return a + (b - a) * t; }
  function lerpAng(a, b, t) { var d = b - a; while (d > Math.PI) d -= 2 * Math.PI; while (d < -Math.PI) d += 2 * Math.PI; return a + d * t; }

  // ── 世界投影：lat/lng → 世界座標（度空間，北為上）──
  function buildWorld(manifest, viewport) {
    var stops = manifest.stops;
    var lats = stops.map(function (s) { return s.lat; });
    var lngs = stops.map(function (s) { return s.lng; });
    var minLat = Math.min.apply(null, lats), maxLat = Math.max.apply(null, lats);
    var midLat = (minLat + maxLat) / 2;
    var cosLat = Math.cos(midLat * Math.PI / 180);
    function proj(lat, lng) { return { x: lng * cosLat, y: -lat }; }
    var pts = stops.map(function (s) { return proj(s.lat, s.lng); });

    // 每段路線的世界折線：有真實道路點就用，否則退回兩端直線。端點一律錨到站點座標避免飄移。
    var segs = Array.isArray(manifest.segments) ? manifest.segments : [];
    var full = [];           // 串接後的完整折線（相鄰段共用站點，去重）
    var fullCum = [];        // full 各頂點的累積弧長
    var fullBreak = [];      // fullBreak[j]=true → 從 full[j-1] 到 full[j] 是「換日連線」，畫線時斷開（不畫）
    var segStartLen = [0];   // 每個「站」在 full 上的起始弧長（segStartLen[i]=到站 i 為止的長度）
    if (pts.length) { full.push(pts[0]); fullCum.push(0); fullBreak.push(false); }
    for (var k = 0; k < segs.length; k++) {
      var raw = (Array.isArray(segs[k].points) && segs[k].points.length >= 2)
        ? segs[k].points.map(function (p) { return proj(p[0], p[1]); })
        : [pts[k], pts[k + 1]];
      raw[0] = pts[k]; raw[raw.length - 1] = pts[k + 1];
      for (var j = 1; j < raw.length; j++) {
        var prev = full[full.length - 1];
        var d = Math.hypot(raw[j].x - prev.x, raw[j].y - prev.y);
        full.push(raw[j]); fullCum.push(fullCum[fullCum.length - 1] + d);
        fullBreak.push(!!segs[k].dayBreak);   // 跨天段的每條邊都標記為斷開
      }
      segStartLen.push(fullCum[fullCum.length - 1]);
    }
    var fullLen = fullCum.length ? fullCum[fullCum.length - 1] : 0;

    // 邊界含所有折線點（讓鏡頭把整條路線框進來）
    var xs = full.map(function (p) { return p.x; }), ys = full.map(function (p) { return p.y; });
    var bx0 = Math.min.apply(null, xs), bx1 = Math.max.apply(null, xs);
    var by0 = Math.min.apply(null, ys), by1 = Math.max.apply(null, ys);
    var spanX = (bx1 - bx0) || 1e-6, spanY = (by1 - by0) || 1e-6;
    var center = { x: (bx0 + bx1) / 2, y: (by0 + by1) / 2 };
    var fitScale = Math.min(viewport.w / spanX, viewport.h / spanY) * 0.82;

    // 台灣全島全景（開頭俯衝的起點）：用與路線相同的投影/cosLat，鏡頭才對得上。
    var TW = { minLat: 21.9, maxLat: 25.3, minLng: 120.0, maxLng: 122.0 };
    var twSpanX = (TW.maxLng - TW.minLng) * cosLat || 1e-6;
    var twSpanY = (TW.maxLat - TW.minLat) || 1e-6;
    var taiwan = {
      cx: (TW.minLng + TW.maxLng) / 2 * cosLat,
      cy: -(TW.minLat + TW.maxLat) / 2,
      scale: Math.min(viewport.w / twSpanX, viewport.h / twSpanY) * 0.9
    };
    return {
      points: pts, cosLat: cosLat, bounds: { x0: bx0, x1: bx1, y0: by0, y1: by1 },
      center: center, spanX: spanX, spanY: spanY,
      fitScale: fitScale, followScale: fitScale * 2.4, taiwan: taiwan,
      full: full, fullCum: fullCum, fullBreak: fullBreak, fullLen: fullLen, segStartLen: segStartLen
    };
  }

  // 沿折線走 dist 弧長 → 該點座標與局部朝向
  function polyAt(poly, cum, dist) {
    var total = cum[cum.length - 1] || 0;
    if (poly.length < 2) return { x: poly[0].x, y: poly[0].y, ang: 0 };
    if (dist <= 0) return { x: poly[0].x, y: poly[0].y, ang: Math.atan2(poly[1].y - poly[0].y, poly[1].x - poly[0].x) };
    if (dist >= total) { var a = poly[poly.length - 2], b = poly[poly.length - 1]; return { x: b.x, y: b.y, ang: Math.atan2(b.y - a.y, b.x - a.x) }; }
    for (var j = 1; j < poly.length; j++) {
      if (cum[j] >= dist) {
        var seg = cum[j] - cum[j - 1] || 1e-9;
        var f = (dist - cum[j - 1]) / seg;
        return {
          x: poly[j - 1].x + (poly[j].x - poly[j - 1].x) * f,
          y: poly[j - 1].y + (poly[j].y - poly[j - 1].y) * f,
          ang: Math.atan2(poly[j].y - poly[j - 1].y, poly[j].x - poly[j - 1].x)
        };
      }
    }
    return { x: poly[poly.length - 1].x, y: poly[poly.length - 1].y, ang: 0 };
  }

  // ── 時間軸：抵達 / 出發 時間（毫秒）──
  function computeTiming(manifest) {
    var tl = manifest.timeline, segs = manifest.segments, n = manifest.stops.length;
    var hold = Array.isArray(tl.holdMs) ? tl.holdMs : null;
    var arrive = tl.arriveMs.slice();
    var depart = new Array(n);
    for (var i = 0; i < n; i++) {
      depart[i] = arrive[i] + (hold ? hold[i] : (i > 0 && i < n - 1 ? tl.dwellMs : 0));
    }
    var routeStart = tl.coverMs;
    var routeEnd = tl.totalMs - tl.statsMs;
    return { arrive: arrive, depart: depart, routeStart: routeStart, routeEnd: routeEnd, segs: segs, dayBreaks: Array.isArray(tl.dayBreaks) ? tl.dayBreaks : [] };
  }

  // 媒體照片：預載 manifest.media[].src（後端為 data URL，前端可為 https/blob）
  function preloadMedia(manifest) {
    var byStop = {}, images = {};
    var list = Array.isArray(manifest.media) ? manifest.media : [];
    var proms = list.map(function (m) {
      byStop[m.stopIndex] = m;
      return new Promise(function (res) {
        if (!m.src || typeof Image === 'undefined') return res();
        var img = new Image();
        if (/^https?:/i.test(m.src)) img.crossOrigin = 'anonymous';
        img.onload = function () { images[m.stopIndex] = img; res(); };
        img.onerror = function () { res(); };
        img.src = m.src;
      });
    });
    return { byStop: byStop, images: images, ready: Promise.all(proms) };
  }

  // 真實 2D 地圖底圖：預載 OSM 圖磚（data URL）
  function preloadBasemap(manifest) {
    var bm = manifest.basemap;
    if (!bm || !Array.isArray(bm.tiles) || typeof Image === 'undefined') return { tiles: [], attribution: '', ready: Promise.resolve() };
    var loaded = [];
    var proms = bm.tiles.map(function (t) {
      return new Promise(function (res) {
        if (!t.dataUrl) return res();
        var img = new Image();
        img.onload = function () { loaded.push({ lngW: t.lngW, lngE: t.lngE, latN: t.latN, latS: t.latS, img: img }); res(); };
        img.onerror = function () { res(); };
        img.src = t.dataUrl;
      });
    });
    return { tiles: loaded, attribution: bm.attribution || '© OpenStreetMap contributors', ready: Promise.all(proms) };
  }
  // 把圖磚依地理範圍畫進世界座標（本專案投影 x=lng·cosLat、y=-lat 對經緯線性，圖磚落成軸對齊矩形），再暗化配深色主題
  function drawBasemap(ctx, W, H, world, cam, basemap) {
    if (!basemap || !basemap.tiles.length) return false;
    ctx.save();
    ctx.fillStyle = '#0a1214'; ctx.fillRect(0, 0, W, H);
    ctx.imageSmoothingEnabled = true;
    basemap.tiles.forEach(function (t) {
      var tl = cam.toScreen({ x: t.lngW * world.cosLat, y: -t.latN });
      var br = cam.toScreen({ x: t.lngE * world.cosLat, y: -t.latS });
      ctx.drawImage(t.img, tl.x, tl.y, (br.x - tl.x) + 1, (br.y - tl.y) + 1);
    });
    ctx.fillStyle = 'rgba(9,18,22,0.5)'; ctx.fillRect(0, 0, W, H);  // 暗化，讓路線跳出
    ctx.restore();
    return true;
  }

  // 到站照片：暗化地圖＋置中相框＋Ken Burns 緩慢縮放平移＋淡入淡出
  function drawPhotoOverlay(ctx, img, W, H, progress, caption) {
    var fade = Math.max(0, Math.min(1, Math.min(progress / 0.14, (1 - progress) / 0.14)));
    ctx.save();
    ctx.globalAlpha = 0.6 * fade;
    ctx.fillStyle = '#0a1214'; ctx.fillRect(0, 0, W, H);
    ctx.globalAlpha = fade;
    var cw = W * 0.82, ch = H * 0.5, cx = (W - cw) / 2, cy = (H - ch) / 2 - 30;
    ctx.save();
    ctx.shadowColor = 'rgba(0,0,0,.5)'; ctx.shadowBlur = 40; ctx.shadowOffsetY = 16;
    ctx.fillStyle = '#fff'; rr(ctx, cx - 8, cy - 8, cw + 16, ch + 16, 30); ctx.fill();
    ctx.restore();
    ctx.save();
    rr(ctx, cx, cy, cw, ch, 24); ctx.clip();
    var z = 1.06 + progress * 0.12;
    var s = Math.max(cw / img.width, ch / img.height) * z;
    var dw = img.width * s, dh = img.height * s;
    var panX = (progress - 0.5) * 46;
    ctx.drawImage(img, cx + (cw - dw) / 2 + panX, cy + (ch - dh) / 2, dw, dh);
    ctx.restore();
    if (caption) {
      ctx.textAlign = 'center'; ctx.fillStyle = '#fff'; ctx.font = '700 54px ' + SANS;
      ctx.shadowColor = 'rgba(0,0,0,.6)'; ctx.shadowBlur = 18;
      ctx.fillText(caption, W / 2, cy + ch + 92); ctx.shadowBlur = 0; ctx.textAlign = 'left';
    }
    ctx.restore();
  }

  // ── 某時刻小車的位置與朝向（確定性）──
  function posAtTime(manifest, world, timing, tMs) {
    var n = world.points.length;
    var full = world.full, fullCum = world.fullCum, segStart = world.segStartLen, fullLen = world.fullLen;
    function at(dist) { return polyAt(full, fullCum, dist); }
    function stopDir(i) {
      var d = segStart[Math.min(i, segStart.length - 1)];
      var a = at(Math.max(0, d - Math.max(1e-6, fullLen * 0.008)));
      var b = at(Math.min(fullLen, d + Math.max(1e-6, fullLen * 0.008)));
      return Math.atan2(b.y - a.y, b.x - a.x);
    }
    if (tMs <= timing.routeStart) { var p0 = at(0); return { x: p0.x, y: p0.y, ang: stopDir(0), reached: 0, heading: 1, dwelling: false, pulse: 0, progLen: 0 }; }
    // 起點站若有照片，會在出發前停留一段（depart[0] > routeStart）——這段視為在起點 dwell。
    if (tMs < timing.depart[0]) { var ph = at(0); return { x: ph.x, y: ph.y, ang: stopDir(0), reached: 0, heading: 1, dwelling: true, pulse: (tMs - timing.arrive[0]) / Math.max(1, timing.depart[0] - timing.arrive[0]), progLen: 0 }; }
    if (tMs >= timing.routeEnd) { var pe = at(fullLen); return { x: pe.x, y: pe.y, ang: pe.ang, reached: n - 1, heading: n - 1, dwelling: true, pulse: 0, progLen: fullLen }; }
    // 換日轉場窗：不畫車、鏡頭落在隔天第一站，交給 renderFrame 顯示「Day N」卡
    for (var b = 0; b < timing.dayBreaks.length; b++) {
      var win = timing.dayBreaks[b];
      if (tMs >= win.startMs && tMs < win.endMs) {
        var ti = win.toStopIndex, fi = ti - 1;
        var np = world.points[ti] || at(segStart[Math.min(ti, segStart.length - 1)]);
        return {
          x: np.x, y: np.y, ang: 0, reached: fi, heading: ti, dwelling: false, pulse: 0,
          progLen: segStart[Math.min(fi, segStart.length - 1)],
          dayBreak: true, day: win.day, breakProgress: (tMs - win.startMs) / Math.max(1, win.endMs - win.startMs)
        };
      }
    }
    for (var i = 1; i < n; i++) {
      var d0 = timing.depart[i - 1], a1 = timing.arrive[i];
      if (tMs < a1) { // 在第 i 段移動中：沿折線走 progLen
        var f = ease((tMs - d0) / Math.max(1, a1 - d0));
        var dist = segStart[i - 1] + (segStart[i] - segStart[i - 1]) * f;
        var pm = at(dist);
        return { x: pm.x, y: pm.y, ang: pm.ang, reached: i - 1, heading: i, dwelling: false, pulse: 0, progLen: dist };
      }
      var dep = timing.depart[i];
      if (tMs < dep) { // 在第 i 站停留
        var pd = at(segStart[i]);
        return { x: pd.x, y: pd.y, ang: stopDir(Math.min(i, n - 1)), reached: i, heading: Math.min(i + 1, n - 1), dwelling: true, pulse: (tMs - a1) / Math.max(1, dep - a1), progLen: segStart[i] };
      }
    }
    var pl = at(fullLen);
    return { x: pl.x, y: pl.y, ang: pl.ang, reached: n - 1, heading: n - 1, dwelling: true, pulse: 0, progLen: fullLen };
  }

  // ── 鏡頭：確定性中心＋縮放（cover/stats 觀全景，route 跟拍）──
  function cameraAt(manifest, world, timing, carPos, tMs, viewport) {
    var fit = { cx: world.center.x, cy: world.center.y, scale: world.fitScale };
    var follow = { cx: carPos.x, cy: carPos.y, scale: world.followScale };
    var cam;
    if (tMs < timing.routeStart) {
      // 開頭：從台灣全景「俯衝」到整條行程路線（fit）。前 18% 停在全景讓觀眾看清楚，再一路 zoom in。
      // 終點停在路線全景（而非鑽到單一站）→ 落點是清晰的路線細圖，避免中段鑽太深只剩放大的低倍圖。
      var tw = world.taiwan || fit;
      var hold = timing.routeStart * 0.18;
      var zk = ease(clamp((tMs - hold) / Math.max(1, timing.routeStart - hold), 0, 1));
      cam = { cx: lerp(tw.cx, fit.cx, zk), cy: lerp(tw.cy, fit.cy, zk), scale: lerp(tw.scale, fit.scale, zk) };
    } else if (tMs < timing.routeEnd) {
      var kin = clamp((tMs - timing.routeStart) / 600, 0, 1); // 進場 600ms 由路線全景推進到跟拍
      var k = ease(kin);
      cam = { cx: lerp(fit.cx, follow.cx, k), cy: lerp(fit.cy, follow.cy, k), scale: lerp(fit.scale, follow.scale, k) };
    } else {
      var kout = clamp((tMs - timing.routeEnd) / 500, 0, 1); // 收場 500ms 拉回全景
      var k2 = ease(kout);
      cam = { cx: lerp(follow.cx, fit.cx, k2), cy: lerp(follow.cy, fit.cy, k2), scale: lerp(follow.scale, fit.scale, k2) };
    }
    var w = viewport.w, h = viewport.h;
    return {
      toScreen: function (p) { return { x: (p.x - cam.cx) * cam.scale + w / 2, y: (p.y - cam.cy) * cam.scale + h / 2 }; }
    };
  }

  // ── 交通工具外型（車頭朝 +x），螢幕空間固定尺寸 ──
  function carColors(mode) {
    var m = (mode || '').toLowerCase();
    if (m.indexOf('taxi') >= 0) return { body: '#f4bf3e', dark: '#a9781a', roof: '#ffe488', win: '#2b3742' };
    if (m.indexOf('scoot') >= 0 || m.indexOf('moto') >= 0) return { body: '#e05b5b', dark: '#8f3030', roof: '#f6a1a1', win: '#2b3742' };
    if (m.indexOf('ferry') >= 0 || m.indexOf('boat') >= 0) return { body: '#54c4a4', dark: '#2b7460', roof: '#97e6cf', win: '#e0f7ef' };
    if (m.indexOf('walk') >= 0) return { body: '#63d6aa', dark: '#2f8f76', roof: '#bff0dc', win: '#2b3742' };
    return { body: '#e2e5ea', dark: '#8e949c', roof: '#f5f7f9', win: '#2b3742' };
  }
  function shapeCar(ctx, col, taxi) {
    var L = 88, Wd = 44;
    ctx.fillStyle = '#141419';[[-27, -22], [27, -22], [-27, 22], [27, 22]].forEach(function (w) { rr(ctx, w[0] - 12, w[1] - 8, 24, 16, 6); ctx.fill(); });
    var g = ctx.createLinearGradient(0, -Wd / 2, 0, Wd / 2);
    g.addColorStop(0, col.dark); g.addColorStop(.26, col.body); g.addColorStop(.5, col.roof); g.addColorStop(.74, col.body); g.addColorStop(1, col.dark);
    ctx.fillStyle = g; rr(ctx, -L / 2, -Wd / 2, L, Wd, 18); ctx.fill();
    ctx.strokeStyle = 'rgba(0,0,0,.28)'; ctx.lineWidth = 2.5; rr(ctx, -L / 2, -Wd / 2, L, Wd, 18); ctx.stroke();
    ctx.fillStyle = col.win; rr(ctx, -16, -16, 36, 32, 10); ctx.fill();
    ctx.fillStyle = 'rgba(255,255,255,.14)'; rr(ctx, 22, -15, 11, 30, 7); ctx.fill();
    ctx.fillStyle = 'rgba(255,255,255,.3)'; rr(ctx, -30, -Wd / 2 + 6, 58, 8, 4); ctx.fill();
    ctx.fillStyle = '#fff7d2';[[44, -14], [44, 14]].forEach(function (p) { ctx.beginPath(); ctx.ellipse(p[0], p[1], 5, 6, 0, 0, 7); ctx.fill(); });
    ctx.fillStyle = '#e14b4b';[[-44, -14], [-44, 14]].forEach(function (p) { ctx.beginPath(); ctx.ellipse(p[0], p[1], 4.5, 6, 0, 0, 7); ctx.fill(); });
    if (taxi) { ctx.fillStyle = '#2a2f36'; rr(ctx, -11, -8, 22, 16, 3); ctx.fill(); ctx.fillStyle = '#ffd84d'; rr(ctx, -7, -4, 14, 8, 2); ctx.fill(); }
  }
  function shapeScooter(ctx, col) {
    ctx.fillStyle = '#141419'; rr(ctx, 28, -7, 24, 14, 6); ctx.fill(); rr(ctx, -52, -7, 24, 14, 6); ctx.fill();
    var g = ctx.createLinearGradient(0, -10, 0, 10); g.addColorStop(0, col.dark); g.addColorStop(.5, col.body); g.addColorStop(1, col.dark);
    ctx.fillStyle = g; rr(ctx, -34, -10, 68, 20, 9); ctx.fill();
    ctx.strokeStyle = '#20242a'; ctx.lineWidth = 6; ctx.lineCap = 'round'; ctx.beginPath(); ctx.moveTo(33, -17); ctx.lineTo(33, 17); ctx.stroke();
    ctx.fillStyle = '#fff7d2'; ctx.beginPath(); ctx.ellipse(41, 0, 3.5, 4.5, 0, 0, 7); ctx.fill();
    ctx.fillStyle = '#2c3138'; rr(ctx, -16, -14, 28, 28, 11); ctx.fill();
    ctx.fillStyle = col.body; ctx.beginPath(); ctx.arc(0, 0, 11, 0, 7); ctx.fill();
    ctx.fillStyle = 'rgba(255,255,255,.25)'; ctx.beginPath(); ctx.arc(4, 0, 5, 0, 7); ctx.fill();
  }
  function shapeWalk(ctx, col) {
    ctx.strokeStyle = col.body; ctx.lineWidth = 9; ctx.lineCap = 'round';
    ctx.beginPath(); ctx.moveTo(-2, 4); ctx.lineTo(-14, 24); ctx.stroke();
    ctx.beginPath(); ctx.moveTo(2, 4); ctx.lineTo(16, 20); ctx.stroke();
    ctx.beginPath(); ctx.moveTo(0, -16); ctx.lineTo(0, 6); ctx.stroke();
    ctx.beginPath(); ctx.moveTo(0, -8); ctx.lineTo(14, -2); ctx.stroke();
    ctx.fillStyle = col.roof; ctx.beginPath(); ctx.arc(0, -28, 11, 0, 7); ctx.fill();
  }
  function shapeFerry(ctx, col) {
    ctx.fillStyle = col.body;
    ctx.beginPath(); ctx.moveTo(54, 0); ctx.quadraticCurveTo(34, -26, -26, -24); ctx.lineTo(-48, -18); ctx.lineTo(-48, 18); ctx.lineTo(-26, 24); ctx.quadraticCurveTo(34, 26, 54, 0); ctx.closePath(); ctx.fill();
    ctx.strokeStyle = 'rgba(0,0,0,.28)'; ctx.lineWidth = 2.5; ctx.stroke();
    ctx.fillStyle = col.roof; rr(ctx, -28, -15, 38, 30, 8); ctx.fill();
    ctx.fillStyle = col.win; rr(ctx, -20, -9, 22, 18, 5); ctx.fill();
    ctx.fillStyle = 'rgba(255,255,255,.5)'; rr(ctx, 40, -4, 10, 8, 3); ctx.fill();
  }
  function drawShape(ctx, mode, col) {
    var m = (mode || '').toLowerCase();
    if (m.indexOf('taxi') >= 0) return shapeCar(ctx, col, true);
    if (m.indexOf('scoot') >= 0 || m.indexOf('moto') >= 0) return shapeScooter(ctx, col);
    if (m.indexOf('walk') >= 0) return shapeWalk(ctx, col);
    if (m.indexOf('ferry') >= 0 || m.indexOf('boat') >= 0) return shapeFerry(ctx, col);
    return shapeCar(ctx, col, false);
  }

  // ── 背景 ──
  function drawBg(ctx, W, H, world, cam, basemap) {
    if (basemap && drawBasemap(ctx, W, H, world, cam, basemap)) return;  // 有真實地圖就用它
    var g = ctx.createLinearGradient(0, 0, 0, H); g.addColorStop(0, '#16232a'); g.addColorStop(.55, '#0f1a20'); g.addColorStop(1, '#0a1214');
    ctx.fillStyle = g; ctx.fillRect(0, 0, W, H);
    var sea = ctx.createLinearGradient(W * 0.66, 0, W, 0); sea.addColorStop(0, 'rgba(40,90,120,0)'); sea.addColorStop(1, 'rgba(38,96,130,0.28)');
    ctx.fillStyle = sea; ctx.fillRect(W * 0.66, 0, W * 0.34, H);
    ctx.fillStyle = 'rgba(255,255,255,.05)';
    for (var i = 60; i < W; i += 70) for (var j = 60; j < H; j += 70) { ctx.beginPath(); ctx.arc(i, j, 2.2, 0, 7); ctx.fill(); }
  }
  function drawRouteBase(ctx, world, cam) {
    ctx.strokeStyle = 'rgba(120,210,185,.18)'; ctx.lineWidth = 9; ctx.lineJoin = 'round'; ctx.lineCap = 'round'; ctx.setLineDash([2, 16]);
    ctx.beginPath();
    var br = world.fullBreak || [];
    world.full.forEach(function (p, i) { var s = cam.toScreen(p); (i === 0 || br[i]) ? ctx.moveTo(s.x, s.y) : ctx.lineTo(s.x, s.y); });
    ctx.stroke(); ctx.setLineDash([]);
  }
  function drawRouteProgress(ctx, world, cam, progLen, carScreen) {
    ctx.save(); ctx.shadowColor = 'rgba(90,220,180,.55)'; ctx.shadowBlur = 22; ctx.strokeStyle = '#63d6aa'; ctx.lineWidth = 12; ctx.lineJoin = 'round'; ctx.lineCap = 'round';
    var br = world.fullBreak || [];
    var first = cam.toScreen(world.full[0]); ctx.beginPath(); ctx.moveTo(first.x, first.y);
    for (var j = 1; j < world.full.length && world.fullCum[j] <= progLen; j++) {
      var s = cam.toScreen(world.full[j]);
      br[j] ? ctx.moveTo(s.x, s.y) : ctx.lineTo(s.x, s.y);   // 跨天連線斷開，不畫
    }
    if (carScreen) ctx.lineTo(carScreen.x, carScreen.y);
    ctx.stroke(); ctx.restore();
  }
  // 換日轉場卡：置中「Day N」，隨轉場進度淡入淡出
  function drawDayCard(ctx, W, H, day, progress) {
    var fade = Math.max(0, Math.min(1, Math.min(progress / 0.22, (1 - progress) / 0.22)));
    ctx.save();
    ctx.globalAlpha = 0.5 * fade;
    ctx.fillStyle = '#0a1214'; ctx.fillRect(0, 0, W, H);
    ctx.globalAlpha = fade;
    ctx.textAlign = 'center';
    ctx.fillStyle = 'rgba(255,255,255,.72)'; ctx.font = '700 46px ' + SANS;
    ctx.shadowColor = 'rgba(0,0,0,.6)'; ctx.shadowBlur = 18;
    ctx.fillText('第 ' + day + ' 天', W / 2, H * 0.44);
    ctx.fillStyle = '#fff'; ctx.font = '800 168px ' + SANS;
    ctx.fillText('DAY ' + day, W / 2, H * 0.54);
    ctx.shadowBlur = 0; ctx.textAlign = 'left';
    ctx.restore();
  }
  function drawStops(ctx, screenPts, reached) {
    screenPts.forEach(function (p, i) {
      var passed = i <= reached;
      if (passed) {
        ctx.fillStyle = 'rgba(99,214,170,.25)'; ctx.beginPath(); ctx.arc(p.x, p.y, 20, 0, 7); ctx.fill();
        ctx.fillStyle = '#eafff6'; ctx.beginPath(); ctx.arc(p.x, p.y, 10, 0, 7); ctx.fill();
        ctx.strokeStyle = '#63d6aa'; ctx.lineWidth = 4; ctx.beginPath(); ctx.arc(p.x, p.y, 10, 0, 7); ctx.stroke();
      } else { ctx.fillStyle = 'rgba(255,255,255,.32)'; ctx.beginPath(); ctx.arc(p.x, p.y, 7, 0, 7); ctx.fill(); }
    });
  }
  function drawVehicle(ctx, carScreen, ang, mode, worldPts, carIdxFloat) {
    var col = carColors(mode);
    var isWalk = (mode || '').toLowerCase().indexOf('walk') >= 0;
    // 落地陰影：置中（只往下一點點），避免車＋影整團偏右、看起來離開路線
    ctx.save();
    if (isWalk) { ctx.translate(carScreen.x, carScreen.y + 20); ctx.fillStyle = 'rgba(0,0,0,.3)'; ctx.beginPath(); ctx.ellipse(0, 0, 18, 8, 0, 0, 7); ctx.fill(); }
    else { ctx.translate(carScreen.x, carScreen.y + 3); ctx.rotate(ang); ctx.fillStyle = 'rgba(0,0,0,.3)'; ctx.beginPath(); ctx.ellipse(0, 0, 50, 22, 0, 0, 7); ctx.fill(); }
    ctx.restore();
    ctx.save(); ctx.translate(carScreen.x, carScreen.y);
    if (isWalk) { if (Math.cos(ang) < 0) ctx.scale(-1, 1); }
    else ctx.rotate(ang);
    drawShape(ctx, mode, col); ctx.restore();
  }

  // ── 字卡 ──
  function titleCard(ctx, m, W, H, a) {
    ctx.save(); ctx.globalAlpha = a; ctx.textAlign = 'center';
    ctx.fillStyle = 'rgba(159,227,200,.9)'; ctx.font = '600 46px ' + SANS; ctx.fillText('旅程回顧', W / 2, H * 0.36);
    ctx.fillStyle = '#fff'; ctx.font = '800 150px ' + SANS; ctx.fillText(m.trip.title, W / 2, H * 0.46);
    ctx.fillStyle = '#9fe3c8'; ctx.font = '600 56px ' + SANS; ctx.fillText(m.trip.region + ' · ' + m.trip.dateLabel, W / 2, H * 0.46 + 96);
    ctx.fillStyle = 'rgba(255,255,255,.85)'; ctx.font = '500 48px ' + SANS; ctx.fillText((m.stops.length - 1) + ' 個景點 · ' + m.trip.people, W / 2, H * 0.46 + 176);
    ctx.textAlign = 'left'; ctx.restore();
  }
  function statsCard(ctx, m, W, H, a) {
    ctx.save(); ctx.globalAlpha = a; ctx.fillStyle = 'rgba(10,16,18,.78)'; ctx.fillRect(0, 0, W, H); ctx.textAlign = 'center';
    ctx.fillStyle = '#9fe3c8'; ctx.font = '700 60px ' + SANS; ctx.fillText('這趟旅程', W / 2, H * 0.26);
    var items = [[String(m.stops.length - 1), '個景點'], [m.trip.distanceKm + ' km', '總路程'], [String(m.trip.dayCount), '天']];
    var y = H * 0.42; items.forEach(function (it) {
      ctx.fillStyle = '#fff'; ctx.font = '800 96px ' + SANS; ctx.fillText(it[0], W / 2, y);
      ctx.fillStyle = 'rgba(255,255,255,.7)'; ctx.font = '500 44px ' + SANS; ctx.fillText(it[1], W / 2, y + 56); y += 190;
    });
    ctx.fillStyle = '#63d6aa'; ctx.font = '700 46px ' + SANS; ctx.fillText('TravelLink AI', W / 2, H * 0.93); ctx.textAlign = 'left'; ctx.restore();
  }
  function stopChip(ctx, m, carScreen, idx, W) {
    var s = m.stops[Math.min(idx, m.stops.length - 1)];
    ctx.font = '700 46px ' + SANS; var nameW = ctx.measureText(s.name).width;
    var label = '第 ' + idx + ' / ' + (m.stops.length - 1) + ' 站';
    ctx.font = '500 34px ' + SANS; var numW = ctx.measureText(label).width;
    var bw = Math.max(nameW, numW) + 64, bh = 138, bx = Math.max(24, Math.min(W - bw - 24, carScreen.x - bw / 2)), by = carScreen.y - bh - 72;
    ctx.save(); ctx.shadowColor = 'rgba(0,0,0,.45)'; ctx.shadowBlur = 26; ctx.fillStyle = 'rgba(14,20,22,.92)'; rr(ctx, bx, by, bw, bh, 18); ctx.fill(); ctx.restore();
    ctx.strokeStyle = 'rgba(99,214,170,.5)'; ctx.lineWidth = 2; rr(ctx, bx, by, bw, bh, 18); ctx.stroke();
    ctx.textAlign = 'center'; ctx.fillStyle = '#9fe3c8'; ctx.font = '500 34px ' + SANS; ctx.fillText(label, bx + bw / 2, by + 50);
    ctx.fillStyle = '#fff'; ctx.font = '700 46px ' + SANS; ctx.fillText(s.name, bx + bw / 2, by + 104); ctx.textAlign = 'left';
    ctx.fillStyle = 'rgba(14,20,22,.92)'; ctx.beginPath(); ctx.moveTo(carScreen.x - 14, by + bh); ctx.lineTo(carScreen.x + 14, by + bh); ctx.lineTo(carScreen.x, by + bh + 22); ctx.closePath(); ctx.fill();
  }
  function header(ctx, m, mode) {
    var MODE_ICON = { taxi: '🚕', car: '🚗', scooter: '🛵', walk: '🚶', ferry: '⛴' };
    var MODE_LABEL = { taxi: '計程車', car: '開車', scooter: '機車', walk: '步行', ferry: '渡輪' };
    var mm = (mode || m.transportMode || 'car').toLowerCase();
    var ic = MODE_ICON[mm] || '🚗', lab = MODE_LABEL[mm] || '交通';
    ctx.fillStyle = 'rgba(255,255,255,.92)'; ctx.font = '700 54px ' + SANS; ctx.fillText(m.trip.title + '  ·  ' + m.trip.region, 84, 150);
    ctx.fillStyle = 'rgba(255,255,255,.5)'; ctx.font = '500 38px ' + SANS; ctx.fillText(m.trip.dateLabel + '  ·  ' + m.trip.people, 84, 210);
    ctx.font = '600 40px ' + SANS; var lw = ctx.measureText(lab).width; var cw = lw + 128, ch = 64, cx0 = 84, cy0 = 250;
    ctx.fillStyle = 'rgba(99,214,170,.16)'; rr(ctx, cx0, cy0, cw, ch, 32); ctx.fill(); ctx.strokeStyle = 'rgba(99,214,170,.5)'; ctx.lineWidth = 2; rr(ctx, cx0, cy0, cw, ch, 32); ctx.stroke();
    ctx.textAlign = 'left'; ctx.textBaseline = 'middle'; ctx.font = '46px ' + EMOJI; ctx.fillStyle = '#ffe08a'; ctx.fillText(ic, cx0 + 26, cy0 + ch / 2 + 2);
    ctx.fillStyle = '#bff0dc'; ctx.font = '600 40px ' + SANS; ctx.fillText(lab, cx0 + 86, cy0 + ch / 2 + 2); ctx.textBaseline = 'alphabetic';
  }

  // ── 主入口：對某時刻畫一整幀 ──
  function renderFrameAtMs(ctx, m, world, timing, tMs, viewport, media, basemap) {
    var W = viewport.w, H = viewport.h, tl = m.timeline;
    var car = posAtTime(m, world, timing, tMs);
    var cam = cameraAt(m, world, timing, car, tMs, viewport);
    drawBg(ctx, W, H, world, cam, basemap);   // 底圖需要鏡頭，故先算 car/cam
    var screenPts = world.points.map(function (p) { return cam.toScreen(p); });
    var carScreen = cam.toScreen({ x: car.x, y: car.y });
    if (tMs < tl.coverMs) {
      drawRouteBase(ctx, world, cam);
      var ca = tMs < 400 ? tMs / 400 : (tMs > tl.coverMs - 400 ? Math.max(0, (tl.coverMs - tMs) / 400) : 1);
      titleCard(ctx, m, W, H, ca);
    } else if (tMs < timing.routeEnd) {
      drawRouteBase(ctx, world, cam);
      if (car.dayBreak) {
        // 換日轉場：畫到上一天為止的進度、不畫車，蓋上「Day N」卡（鏡頭已落在隔天第一站）
        drawRouteProgress(ctx, world, cam, car.progLen, null);
        drawStops(ctx, screenPts, car.reached);
        header(ctx, m, m.transportMode);
        drawDayCard(ctx, W, H, car.day, car.breakProgress);
      } else {
        drawRouteProgress(ctx, world, cam, car.progLen, carScreen);
        drawStops(ctx, screenPts, car.reached);
        var mode = (m.stops[Math.min(car.heading, m.stops.length - 1)].mode) || m.transportMode;
        drawVehicle(ctx, carScreen, car.ang, mode);
        // 到站且該站有照片 → 顯示照片（Ken Burns），此時不畫站名卡（照片自帶標題）
        var mediaImg = (car.dwelling && media && media.byStop[car.reached]) ? media.images[car.reached] : null;
        if (mediaImg) {
          drawPhotoOverlay(ctx, mediaImg, W, H, car.pulse, m.stops[car.reached].name);
        } else {
          var idx = car.dwelling ? car.reached : car.heading;
          stopChip(ctx, m, carScreen, idx, W);
        }
        header(ctx, m, mode);
      }
    } else {
      drawRouteBase(ctx, world, cam);
      drawRouteProgress(ctx, world, cam, world.fullLen, carScreen);
      drawStops(ctx, screenPts, m.stops.length - 1);
      statsCard(ctx, m, W, H, Math.min(1, (tMs - timing.routeEnd) / 500));
    }
    // 地圖來源標註（OSM 授權要求）
    if (basemap && basemap.tiles && basemap.tiles.length && basemap.attribution) {
      ctx.save();
      ctx.textAlign = 'right'; ctx.font = '400 24px ' + SANS;
      ctx.fillStyle = 'rgba(255,255,255,.5)';
      ctx.shadowColor = 'rgba(0,0,0,.6)'; ctx.shadowBlur = 8;
      ctx.fillText(basemap.attribution, W - 18, H - 18);
      ctx.textAlign = 'left'; ctx.restore();
    }
  }

  // ── 便利 session（前端預覽用）──
  function createSession(ctx, manifest, viewport) {
    var world = buildWorld(manifest, viewport);
    var timing = computeTiming(manifest);
    var media = preloadMedia(manifest);
    var basemap = preloadBasemap(manifest);
    return {
      totalMs: manifest.timeline.totalMs,
      world: world, timing: timing, media: media, basemap: basemap,
      ready: Promise.all([media.ready, basemap.ready]),
      renderAt: function (tMs) { renderFrameAtMs(ctx, manifest, world, timing, tMs, viewport, media, basemap); }
    };
  }

  return {
    buildWorld: buildWorld, computeTiming: computeTiming, posAtTime: posAtTime,
    renderFrameAtMs: renderFrameAtMs, createSession: createSession,
    carColors: carColors, drawShape: drawShape
  };
});
