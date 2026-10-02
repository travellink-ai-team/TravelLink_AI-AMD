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
const { renderRecapVideo } = require('./recap-renderer.js');

const OUT_DIR = path.join(os.tmpdir(), 'wai-recap-jobs');
const JOB_TTL_MS = 30 * 60 * 1000;      // 產物與 job 記錄保留 30 分鐘
const MAX_QUEUE = 4;                     // 佇列上限（含正在跑的那支）
const MAX_STOPS = 40;
const MAX_TOTAL_MS = 60 * 1000;          // 影片長度硬上限 60s
const FPS = 30;

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
      dayIndex: Math.max(1, Math.min(60, finite(s.dayIndex) || 1))
    };
  });
  return {
    title: str(raw.title || '旅程', 80),
    region: str(raw.region || '', 60),
    dateLabel: str(raw.dateLabel || '', 60),
    people: str(raw.people || '', 40),
    distanceKm: finite(raw.distanceKm) || 0,
    days: str(raw.days || '', 120),
    transportMode: str(raw.transportMode || 'car', 32),
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

function buildManifest(trip, mediaCount, routePoints, mediaStopIndices) {
  const manifest = MANIFEST.buildRecapManifest(trip, {
    mediaCount: Math.max(0, Math.min(12, finite(mediaCount) || 0)),
    mediaStopIndices: Array.isArray(mediaStopIndices) ? mediaStopIndices : []
  });
  if (manifest.timeline.totalMs > MAX_TOTAL_MS) throw badReq('video too long');
  if (routePoints) {
    const pts = sanitizeRoutePoints(routePoints, manifest.segments.length);
    manifest.segments.forEach((seg, i) => { seg.points = pts[i]; });
  }
  return manifest;
}

// 照片：每站最多一張，url 需為 https 或 data:image；stopIndex 需在範圍內。
function sanitizePhotos(raw, stopCount) {
  if (raw == null) return [];
  if (!Array.isArray(raw)) throw badReq('photos must be array');
  const out = [];
  const seen = {};
  raw.slice(0, 12).forEach((p) => {
    if (!p || typeof p !== 'object') return;
    const idx = finite(p.stopIndex);
    if (idx === null || idx < 0 || idx >= stopCount) return;
    const url = String(p.url || '');
    if (!/^https?:\/\//i.test(url) && !/^data:image\//i.test(url)) return;
    if (url.length > 6 * 1024 * 1024) return;
    if (seen[idx]) return;
    seen[idx] = true;
    out.push({ stopIndex: idx, url });
  });
  return out;
}

// ── OSM 真實 2D 地圖底圖：伺服器端抓圖磚 → data URL（避開瀏覽器跨源污染）──
// 授權：OpenStreetMap 標準圖磚，需標註「© OpenStreetMap contributors」；輕量使用。
const OSM_UA = 'TravelLinkAI-recap/1.0 (+https://travel-link-ai.duckdns.org)';
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
    const r = await fetch(url, { signal: ctrl.signal });
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
    // 到站照片：伺服器端抓成 data URL 填進 manifest.media（抓不到就略過該站照片）
    if (job.photos && job.photos.length && Array.isArray(job.manifest.media)) {
      const byStop = {};
      await Promise.all(job.photos.map(async (p) => { byStop[p.stopIndex] = await fetchPhotoDataUrl(p.url); }));
      job.manifest.media.forEach((mm) => { const src = byStop[mm.stopIndex]; if (src) mm.src = src; });
    }
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

  // 渲染很重：每人每小時上限 5 次
  const renderLimiter = rateLimit({
    windowMs: 60 * 60 * 1000, max: 5, standardHeaders: true, legacyHeaders: false,
    keyGenerator: (req, res) => (req.user && req.user.uid) || ipKeyGenerator(req, res),
    message: { error: 'rate limited', message: '產生回顧短片次數過多，請稍後再試。' }
  });

  app.post('/api/recap/render', renderLimiter, requireFirebaseUser, (req, res) => {
    cleanupOld();
    if (queue.length >= MAX_QUEUE) return res.status(429).json({ error: 'busy', message: '目前排隊中，請稍後再試。' });
    let manifest, photos;
    try {
      const trip = sanitizeTrip(req.body && req.body.trip);
      photos = sanitizePhotos(req.body && req.body.photos, trip.stops.length);
      const mediaStopIndices = photos.map((p) => p.stopIndex);
      manifest = buildManifest(trip, photos.length, req.body && req.body.routePoints, mediaStopIndices);
    } catch (err) {
      return res.status(err.status || 400).json({ error: 'invalid input', message: err.message });
    }
    const id = crypto.randomBytes(12).toString('hex');
    const job = { id, uid: req.user.uid, status: 'queued', progress: 0, outPath: null, error: null, createdAt: Date.now(), manifest, photos };
    jobs.set(id, job);
    queue.push(job);
    setImmediate(runNext);
    return res.json({ ok: true, jobId: id, totalMs: manifest.timeline.totalMs, queuePos: queue.length });
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

module.exports = { mountRecapJobs: mountRecapJobs, _internals: { sanitizeTrip, buildManifest, fetchBasemap } };
