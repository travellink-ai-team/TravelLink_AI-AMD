/*
 * recap-jobs.js — 回顧短片後端渲染 job（M8）
 * 掛在既有代理上：POST 建立 job → 序列化渲染（無頭 Chrome＋ffmpeg）→ 輪詢狀態 → 下載 mp4。
 * 產物存本機暫存目錄、限時清除；不進 Firebase Storage（P3 才上雲，屆時需部署 Storage Rules）。
 *
 * 安全考量：
 *  - 渲染很重（開 Chrome＋逐幀截圖）：全域序列化（同時只跑一支），佇列上限，避免被灌爆。
 *  - 需登入（requireFirebaseUser），且 job 只有建立者 uid 能查詢/下載。
 *  - manifest 一律由伺服器用 trip 重算時間軸；只信任 stops 與（可選的）路線點，其餘欄位不吃前端的。
 *  - 幀數上限，杜絕「請求一支 10 小時影片」的 DoS。
 */
'use strict';
const path = require('path');
const fs = require('fs');
const os = require('os');
const crypto = require('crypto');

const MANIFEST = require(path.join(__dirname, '..', 'app', 'recap-manifest.js'));
const TEMPLATES = require(path.join(__dirname, '..', 'app', 'recap-templates.js'));
const TIMELINE = require(path.join(__dirname, '..', 'app', 'recap-timeline.js'));
const { renderRecapVideo } = require('./recap-renderer.js');

const OUT_DIR = path.join(os.tmpdir(), 'wai-recap-jobs');
const JOB_TTL_MS = 30 * 60 * 1000;      // 產物與 job 記錄保留 30 分鐘
const MAX_QUEUE = 4;                     // 佇列上限（含正在跑的那支）
const MAX_STOPS = 40;
const MAX_TOTAL_MS = 60 * 1000;          // 影片長度硬上限 60s
const MAX_TIMELINE_CLIPS = 200;          // 時間軸片段數上限（40 站的行程也遠低於此）
const MAX_PHOTOS = 24;                   // 一次請求的照片總數上限（放進全部照片時每站都帶，伺服器要逐張抓）
const MAX_PHOTOS_PER_STOP = 10;
const FPS = 30;

// 畫面風格（A15）：後端白名單。目前只有 basic 畫好，其他三種先記下使用者選的值、用 basic 畫。
const LOOKS = ['fly', 'cards', 'film', 'basic'];
const LOOKS_READY = ['basic'];
const DEFAULT_LOOK = 'basic';

// 片尾的網頁網址與 App 下載頁：寫在伺服器設定，不由前端傳，避免有人把任意網址印進影片
const RECAP_WEB_URL = process.env.RECAP_WEB_URL || 'travel-link-amd.duckdns.org';
// App 下載頁還沒有（組員決定先不放 APK）：預設不印這行，片尾只印網頁網址。有下載頁時再設 RECAP_APP_URL。
const RECAP_APP_URL = process.env.RECAP_APP_URL || '';

try { fs.mkdirSync(OUT_DIR, { recursive: true }); } catch (e) {}

const jobs = new Map();                  // jobId → { id, uid, status, progress, outPath, error, createdAt }
const queue = [];
let running = false;

function finite(v) { return typeof v === 'number' && Number.isFinite(v) ? v : null; }
function str(v, max) { return String(v == null ? '' : v).slice(0, max || 200); }

// 只吃 stops 與可選路線點；其餘欄位淨化後交給 manifest builder 重算時間軸
function sanitizeTrip(raw) {
  if (!raw || typeof raw !== 'object') throw badReq('invalid trip');
  const srcStops = Array.isArray(raw.stops) ? raw.stops : [];
  if (srcStops.length < 2) throw badReq('need at least 2 stops');
  if (srcStops.length > MAX_STOPS) throw badReq('too many stops');
  const stops = srcStops.map((s, i) => {
    const lat = finite(s && s.lat), lng = finite(s && s.lng);
    if (lat === null || lng === null || lat < -90 || lat > 90 || lng < -180 || lng > 180) throw badReq('bad stop coordinates at ' + i);
    return {
      stopId: str(s.stopId || ('s' + i), 120),
      name: str(s.name || ('景點 ' + (i + 1)), 200),
      lat: lat, lng: lng,
      mode: str(s.mode || raw.transportMode || 'car', 32),
      stayMin: Math.max(0, Math.min(1440, finite(s.stayMin) || 0)),
      dayIndex: Math.max(1, Math.min(60, finite(s.dayIndex) || 1)),
      // 每站抵達時間（地圖飛行上方的時鐘用）：只收 HH:MM
      arriveTime: /^([01]\d|2[0-3]):[0-5]\d$/.test(String(s.arriveTime || '')) ? String(s.arriveTime) : ''
    };
  });
  const cost = finite(raw.costPerPerson);
  const highlightStopId = str(raw.highlightStopId || '', 120);
  return {
    title: str(raw.title || '旅程', 80),
    region: str(raw.region || '', 60),
    dateLabel: str(raw.dateLabel || '', 60),
    people: str(raw.people || '', 40),
    distanceKm: finite(raw.distanceKm) || 0,
    days: str(raw.days || '', 120),
    transportMode: str(raw.transportMode || 'car', 32),
    // 攻略卡：每人花費（元，沒有就不顯示）、最推薦的一站（必須是這趟行程的站）
    costPerPerson: cost !== null && cost > 0 && cost < 1e6 ? Math.round(cost) : null,
    highlightStopId: stops.some((st) => st.stopId === highlightStopId) ? highlightStopId : '',
    stops: stops
  };
}

// 可選的真實道路點：長度需為 stops-1，每段是 [[lat,lng],...]，數量與範圍設上限
function sanitizeRoutePoints(raw, segCount) {
  if (raw == null) return null;
  if (!Array.isArray(raw) || raw.length !== segCount) throw badReq('routePoints length mismatch');
  return raw.map((seg) => {
    if (!Array.isArray(seg)) throw badReq('bad segment points');
    if (seg.length > 2000) throw badReq('segment too many points');
    return seg.map((p) => {
      const lat = finite(Array.isArray(p) ? p[0] : null), lng = finite(Array.isArray(p) ? p[1] : null);
      if (lat === null || lng === null || lat < -90 || lat > 90 || lng < -180 || lng > 180) throw badReq('bad route point');
      return [lat, lng];
    });
  });
}

function badReq(msg) { const e = new Error(msg); e.status = 400; return e; }

// 樣板只接受伺服器認得的 id；時間軸（前端推薦版或之後編輯過的版本）由 recap-timeline.js
// 重新驗證：只採用對得回這趟行程的片段、時長夾在範圍內，照片只能用這次一起送來的那幾張。
// extra：{ lengthMode, look }（第 2 版請求欄位；舊請求沒帶就用預設）
function resolveManifestOptions(trip, photos, rawTemplateId, rawTimeline, extra) {
  const templateId = TEMPLATES.hasTemplate(rawTemplateId) ? String(rawTemplateId) : TEMPLATES.DEFAULT_ID;
  const more = extra || {};
  const lengthMode = more.lengthMode == null ? null : TEMPLATES.normalizeLengthMode(more.lengthMode);
  const heroIdx = trip.highlightStopId ? trip.stops.findIndex((st) => st.stopId === trip.highlightStopId) : -1;
  if (rawTimeline != null) {
    if (typeof rawTimeline !== 'object' || !Array.isArray(rawTimeline.clips)) throw badReq('invalid timeline');
    if (rawTimeline.clips.length > MAX_TIMELINE_CLIPS) throw badReq('timeline too long');
    // 時間軸自己的 templateId 決定用哪一版驗證；以請求的樣板為準（兩者不一致時照請求的樣板重算）
    const opts = TIMELINE.compileTimeline(trip, Object.assign({}, rawTimeline, { templateId }), photos);
    opts.template = templateId;
    if (lengthMode) opts.lengthMode = lengthMode;
    return opts;
  }
  const counts = trip.stops.map(() => 0);
  photos.forEach((p) => { counts[p.stopIndex] += 1; });
  const seen = {};
  const indices = photos.map((p) => p.stopIndex).filter((i) => (seen[i] ? false : (seen[i] = true)));
  const opts = { template: templateId, mediaCount: indices.length, mediaStopIndices: indices, photoCounts: counts };
  if (heroIdx >= 0) opts.heroStopIndex = heroIdx;
  if (lengthMode) opts.lengthMode = lengthMode;
  return opts;
}

function normalizeLook(raw) {
  const requested = LOOKS.includes(String(raw || '')) ? String(raw) : DEFAULT_LOOK;
  return { requested, look: LOOKS_READY.includes(requested) ? requested : DEFAULT_LOOK };
}

// 攻略卡與封面要的補充資訊：放進 manifest，渲染器只讀不算
function decorateManifest(manifest, trip, photos, body) {
  const looks = normalizeLook(body && body.look);
  manifest.look = looks.look;
  manifest.lookRequested = looks.requested;
  manifest.brand = { webUrl: RECAP_WEB_URL, appUrl: RECAP_APP_URL };
  const heroIdx = finite(manifest.heroStopIndex);
  const hlIdx = trip.highlightStopId ? trip.stops.findIndex((st) => st.stopId === trip.highlightStopId) : -1;
  const pickIdx = hlIdx >= 0 ? hlIdx : (heroIdx !== null && heroIdx >= 0 ? heroIdx : -1);
  manifest.trip.highlightName = pickIdx >= 0 ? trip.stops[pickIdx].name : '';
  // 封面（A1／A5）：使用者選了某站的照片 → 用那張；否則用行程插圖（runNext 讀 Firestore）；都沒有退回第一張照片
  const coverIdx = finite(body && body.coverStopIndex);
  const coverPhoto = coverIdx !== null ? photos.find((p) => p.stopIndex === coverIdx) : null;
  if (manifest.template && manifest.template.structure === 'anchors') {
    manifest.cover = coverPhoto
      ? { style: 'title', source: 'photo', photoStopIndex: coverIdx, src: null }
      : { style: 'title', source: 'illustration', photoStopIndex: photos.length ? photos[0].stopIndex : null, src: null };
  }
  return { coverPhotoUrl: coverPhoto ? coverPhoto.url : null };
}

function buildManifest(trip, options, routePoints) {
  const opts = Object.assign({}, options);
  opts.mediaCount = Math.max(0, Math.min(12, finite(opts.mediaCount) || 0));
  opts.mediaStopIndices = Array.isArray(opts.mediaStopIndices) ? opts.mediaStopIndices : [];
  const manifest = MANIFEST.buildRecapManifest(trip, opts);
  if (manifest.timeline.totalMs > MAX_TOTAL_MS) throw badReq('video too long');
  if (routePoints) {
    const pts = sanitizeRoutePoints(routePoints, manifest.segments.length);
    manifest.segments.forEach((seg, i) => { seg.points = pts[i]; });
  }
  return manifest;
}

// 照片網址由使用者送來、伺服器端去抓，所以只放行自家 Firebase Storage 的下載網址
// （前端 uploadTripPhoto 的 getDownloadURL）。不放行任意網域：否則可拿來打內網（SSRF），
// 也會讓伺服器去解惡意站台回的壓縮內容（如 CVE-2026-111497 Brotli RCE）。
const PHOTO_HOSTS = new Set(['firebasestorage.googleapis.com']);
function isAllowedPhotoUrl(url) {
  if (/^data:image\//i.test(url)) return true;
  let u;
  try { u = new URL(url); } catch (_e) { return false; }
  return u.protocol === 'https:' && !u.port && PHOTO_HOSTS.has(u.hostname);
}

// 照片：url 需為 Firebase Storage 的 https 下載網址或 data:image；stopIndex 需在範圍內。
// 第 2 版（放進全部照片）同一站可以有多張，順序照送來的（共同相簿的手動排序 → 拍攝時間）；
// 舊樣板仍每站只用第一張（manifest 只會為每站排一個照片片段）。
function sanitizePhotos(raw, stopCount) {
  if (raw == null) return [];
  if (!Array.isArray(raw)) throw badReq('photos must be array');
  const out = [];
  const perStop = {};
  const seenUrl = new Set();
  raw.slice(0, MAX_PHOTOS * 2).forEach((p) => {
    if (!p || typeof p !== 'object' || out.length >= MAX_PHOTOS) return;
    const idx = finite(p.stopIndex);
    if (idx === null || idx < 0 || idx >= stopCount) return;
    const url = String(p.url || '');
    if (!isAllowedPhotoUrl(url)) return;
    if (url.length > 6 * 1024 * 1024) return;
    if (seenUrl.has(url) || (perStop[idx] || 0) >= MAX_PHOTOS_PER_STOP) return;
    seenUrl.add(url);
    perStop[idx] = (perStop[idx] || 0) + 1;
    const photo = { stopIndex: idx, url };
    if (p.photoId) photo.photoId = str(p.photoId, 120);
    out.push(photo);
  });
  return out;
}

// ── OSM 真實 2D 地圖底圖：伺服器端抓圖磚 → data URL（避開瀏覽器跨源污染）──
// 授權：OpenStreetMap 標準圖磚，需標註「© OpenStreetMap contributors」；輕量使用。
const OSM_UA = 'TravelLinkAI-recap/1.0 (+https://travel-link-amd.duckdns.org)';
function lng2tileX(lng, z) { return (lng + 180) / 360 * Math.pow(2, z); }
function lat2tileY(lat, z) { var r = lat * Math.PI / 180; return (1 - Math.log(Math.tan(r) + 1 / Math.cos(r)) / Math.PI) / 2 * Math.pow(2, z); }
function tileX2lng(x, z) { return x / Math.pow(2, z) * 360 - 180; }
function tileY2lat(y, z) { var n = Math.PI - 2 * Math.PI * y / Math.pow(2, z); return 180 / Math.PI * Math.atan(0.5 * (Math.exp(n) - Math.exp(-n))); }

async function fetchBasemap(manifest) {
  if (process.env.RECAP_BASEMAP === '0') return null;   // 可用環境變數關閉
  var lats = [], lngs = [];
  (manifest.stops || []).forEach((s) => { lats.push(s.lat); lngs.push(s.lng); });
  (manifest.segments || []).forEach((seg) => { if (Array.isArray(seg.points)) seg.points.forEach((p) => { lats.push(p[0]); lngs.push(p[1]); }); });
  if (lats.length < 2) return null;
  var minLat = Math.min.apply(null, lats), maxLat = Math.max.apply(null, lats);
  var minLng = Math.min.apply(null, lngs), maxLng = Math.max.apply(null, lngs);
  var mLat = (maxLat - minLat) * 0.15 + 0.002, mLng = (maxLng - minLng) * 0.15 + 0.002;
  minLat -= mLat; maxLat += mLat; minLng -= mLng; maxLng += mLng;
  var z, x0, x1, y0, y1;
  for (z = 17; z >= 10; z--) {
    x0 = Math.floor(lng2tileX(minLng, z)); x1 = Math.floor(lng2tileX(maxLng, z));
    y0 = Math.floor(lat2tileY(maxLat, z)); y1 = Math.floor(lat2tileY(minLat, z));
    var w = x1 - x0 + 1, h = y1 - y0 + 1;
    if (w >= 1 && h >= 1 && w <= 6 && h <= 8 && w * h <= 24) break;
  }
  if (z < 10) return null;
  var coords = [];
  for (var x = x0; x <= x1; x++) for (var y = y0; y <= y1; y++) coords.push([x, y]);
  var tiles = await fetchTileSet(z, coords);
  // 開頭「從整個台灣俯衝」需要多層底圖（否則 z7→細節圖倍率落差太大、中段只剩放大的糊圖）。
  // 由粗到細分三層，墊在陣列「前段 → 先畫、被後面較細的蓋過」：台灣全島(z7) → 區域(z10) → 路線細圖。
  var cLat = (minLat + maxLat) / 2, cLng = (minLng + maxLng) / 2;
  var overview = await fetchBboxLayer({ minLat: 21.85, maxLat: 25.35, minLng: 119.9, maxLng: 122.15 }, 7, 12).catch(() => []);
  var mid = await fetchBboxLayer({ minLat: cLat - 0.5, maxLat: cLat + 0.5, minLng: cLng - 0.5, maxLng: cLng + 0.5 }, 10, 30).catch(() => []);
  var all = overview.concat(mid, tiles);
  return all.length ? { tiles: all, attribution: '© OpenStreetMap contributors', z: z } : null;
}

// 抓一組圖磚（座標陣列）→ 帶地理範圍的 tile 物件陣列。單塊失敗略過。
async function fetchTileSet(z, coords) {
  var out = [];
  await Promise.all(coords.map(async (xy) => {
    try {
      const ctrl = new AbortController(); const to = setTimeout(() => ctrl.abort(), 8000);
      const r = await fetch('https://tile.openstreetmap.org/' + z + '/' + xy[0] + '/' + xy[1] + '.png', { headers: { 'User-Agent': OSM_UA }, signal: ctrl.signal });
      clearTimeout(to);
      if (!r.ok) return;
      const buf = Buffer.from(await r.arrayBuffer());
      if (buf.length > 2 * 1024 * 1024) return;
      out.push({
        lngW: tileX2lng(xy[0], z), lngE: tileX2lng(xy[0] + 1, z),
        latN: tileY2lat(xy[1], z), latS: tileY2lat(xy[1] + 1, z),
        dataUrl: 'data:image/png;base64,' + buf.toString('base64')
      });
    } catch (e) {}
  }));
  return out;
}

// 抓某地理範圍在指定 zoom 的整片圖磚（超過 cap 塊就放棄，避免抓太多）。
async function fetchBboxLayer(bb, z, cap) {
  if (process.env.RECAP_BASEMAP === '0') return [];
  var x0 = Math.floor(lng2tileX(bb.minLng, z)), x1 = Math.floor(lng2tileX(bb.maxLng, z));
  var y0 = Math.floor(lat2tileY(bb.maxLat, z)), y1 = Math.floor(lat2tileY(bb.minLat, z));
  var coords = [];
  for (var x = x0; x <= x1; x++) for (var y = y0; y <= y1; y++) coords.push([x, y]);
  if (coords.length > (cap || 16)) return [];
  return fetchTileSet(z, coords);
}

// 伺服器端抓照片 → data URL（避開瀏覽器 file:// 的跨源污染）。失敗回 null。
async function fetchPhotoDataUrl(url) {
  if (/^data:image\//i.test(url)) return url.length <= 6 * 1024 * 1024 ? url : null;
  try {
    const ctrl = new AbortController();
    const to = setTimeout(() => ctrl.abort(), 10000);
    // 不跟隨轉址：白名單只驗了第一跳，轉址會把請求帶到白名單外。
    const r = await fetch(url, { signal: ctrl.signal, redirect: 'error' });
    clearTimeout(to);
    if (!r.ok) return null;
    const ct = (r.headers.get('content-type') || '').toLowerCase();
    if (!/^image\/(jpe?g|png|webp)/.test(ct)) return null;
    const buf = Buffer.from(await r.arrayBuffer());
    if (buf.length > 8 * 1024 * 1024) return null;
    return 'data:' + ct.split(';')[0].trim() + ';base64,' + buf.toString('base64');
  } catch (e) { return null; }
}

function cleanupOld() {
  const now = Date.now();
  for (const [id, job] of jobs) {
    if (now - job.createdAt > JOB_TTL_MS) {
      if (job.outPath) fs.promises.unlink(job.outPath).catch(() => {});
      jobs.delete(id);
    }
  }
}

async function runNext() {
  if (running) return;
  const job = queue.shift();
  if (!job) return;
  running = true;
  job.status = 'rendering';
  try {
    // 到站照片：伺服器端抓成 data URL 填進 manifest.media（抓不到就略過那張）。
    // media.photoIndex＝該站第幾張（依送來的順序）；舊 manifest 沒有 photoIndex＝第一張。
    const perStop = {};
    (job.photos || []).forEach((p) => { (perStop[p.stopIndex] = perStop[p.stopIndex] || []).push(p); });
    const used = new Map();
    (job.manifest.media || []).forEach((mm) => {
      const p = (perStop[mm.stopIndex] || [])[mm.photoIndex || 0];
      if (p) used.set(p.url, null);
    });
    const cover = job.manifest.cover || {};
    let coverUrl = job.coverPhotoUrl || null;
    if (cover.source === 'illustration' && job.coverLoader) {
      coverUrl = await job.coverLoader().catch(() => null);
      if (coverUrl && !isAllowedPhotoUrl(coverUrl)) coverUrl = null;
      if (!coverUrl) {                     // 沒有插圖：退回第一張照片
        const first = (perStop[cover.photoStopIndex] || [])[0];
        coverUrl = first ? first.url : null;
        cover.source = coverUrl ? 'photo' : 'title';
      }
    }
    if (coverUrl) used.set(coverUrl, null);
    await Promise.all(Array.from(used.keys()).map(async (url) => { used.set(url, await fetchPhotoDataUrl(url)); }));
    (job.manifest.media || []).forEach((mm) => {
      const p = (perStop[mm.stopIndex] || [])[mm.photoIndex || 0];
      const src = p && used.get(p.url);
      if (src) mm.src = src;
    });
    if (coverUrl && used.get(coverUrl)) cover.src = used.get(coverUrl);
    // 真實 2D 地圖底圖（OSM 圖磚，抓不到就用風格化底圖）
    job.manifest.basemap = await fetchBasemap(job.manifest).catch(() => null);
    const outPath = path.join(OUT_DIR, job.id + '.mp4');
    await renderRecapVideo(job.manifest, {
      outPath, fps: FPS, width: 1080, height: 1920,
      onProgress: (f) => { job.progress = Math.round(f * 100); }
    });
    job.outPath = outPath;
    job.status = 'done';
    job.progress = 100;
  } catch (err) {
    job.status = 'error';
    job.error = String(err && err.message || err).slice(0, 300);
    console.error('[recap] 渲染失敗 job=' + job.id + '：', job.error);
  } finally {
    job.manifest = null;                 // 釋放
    running = false;
    setImmediate(runNext);               // 接著跑佇列下一支
  }
}

function mountRecapJobs(app, deps) {
  const requireFirebaseUser = deps.requireFirebaseUser;
  const rateLimit = deps.rateLimit;
  const ipKeyGenerator = deps.ipKeyGenerator;
  // loadTripCover(user, tripId) → 行程插圖網址（只有行程成員讀得到；多日行程用第一天）。沒有就回 null
  const loadTripCover = deps.loadTripCover || null;

  // 渲染很重：每人每小時上限 5 次
  const renderLimiter = rateLimit({
    windowMs: 60 * 60 * 1000, max: 5, standardHeaders: true, legacyHeaders: false,
    keyGenerator: (req, res) => (req.user && req.user.uid) || ipKeyGenerator(req, res),
    message: { error: 'rate limited', message: '產生回顧短片次數過多，請稍後再試。' }
  });

  app.post('/api/recap/render', renderLimiter, requireFirebaseUser, (req, res) => {
    cleanupOld();
    if (queue.length >= MAX_QUEUE) return res.status(429).json({ error: 'busy', message: '目前排隊中，請稍後再試。' });
    let manifest, photos, coverPhotoUrl = null;
    const body = req.body || {};
    try {
      const trip = sanitizeTrip(body.trip);
      photos = sanitizePhotos(body.photos, trip.stops.length);
      const opts = resolveManifestOptions(trip, photos, body.templateId, body.timeline, { lengthMode: body.lengthMode });
      manifest = buildManifest(trip, opts, body.routePoints);
      coverPhotoUrl = decorateManifest(manifest, trip, photos, body).coverPhotoUrl;
      // 字幕：時間由伺服器依實際時間軸計算，文字逐則淨化（14 字內）
      if (body.timeline) TIMELINE.attachCaptions(manifest, body.timeline);
    } catch (err) {
      return res.status(err.status || 400).json({ error: 'invalid input', message: err.message });
    }
    const tripId = str(body.tripId || '', 128);
    const user = req.user;
    const coverLoader = loadTripCover && tripId && /^[A-Za-z0-9_-]+$/.test(tripId) ? () => loadTripCover(user, tripId) : null;
    const id = crypto.randomBytes(12).toString('hex');
    const job = { id, uid: req.user.uid, status: 'queued', progress: 0, outPath: null, error: null, createdAt: Date.now(), manifest, photos, coverPhotoUrl, coverLoader };
    jobs.set(id, job);
    queue.push(job);
    setImmediate(runNext);
    return res.json({ ok: true, jobId: id, totalMs: manifest.timeline.totalMs, queuePos: queue.length, look: manifest.look, lookRequested: manifest.lookRequested });
  });

  app.get('/api/recap/jobs/:jobId', requireFirebaseUser, (req, res) => {
    const job = jobs.get(String(req.params.jobId || ''));
    if (!job || job.uid !== req.user.uid) return res.status(404).json({ error: 'job not found' });
    return res.json({ ok: true, jobId: job.id, status: job.status, progress: job.progress, error: job.error || null });
  });

  app.get('/api/recap/jobs/:jobId/download', requireFirebaseUser, (req, res) => {
    const job = jobs.get(String(req.params.jobId || ''));
    if (!job || job.uid !== req.user.uid) return res.status(404).json({ error: 'job not found' });
    if (job.status !== 'done' || !job.outPath || !fs.existsSync(job.outPath)) return res.status(409).json({ error: 'not ready', status: job.status });
    res.set('Content-Type', 'video/mp4');
    res.set('Content-Disposition', 'attachment; filename="travel-recap.mp4"');
    res.set('Cache-Control', 'private, no-store');
    fs.createReadStream(job.outPath).pipe(res);
  });

  console.log('[recap] job 端點已掛載：POST /api/recap/render、GET /api/recap/jobs/:id[/download]');
}

module.exports = { mountRecapJobs: mountRecapJobs, _internals: { sanitizeTrip, sanitizePhotos, resolveManifestOptions, buildManifest, decorateManifest, normalizeLook, fetchBasemap } };
