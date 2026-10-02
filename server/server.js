/**
 * WanderAI 後端代理（Vertex AI / Gemini + TDX）
 *
 * 目的：把伺服器端金鑰留在後端，前端只呼叫同源的 /api/*：
 *  - /api/vertex/*：注入 VERTEX_API_KEY 轉發 Vertex AI（文字/串流/圖片）。
 *  - /api/tdx/*   ：以 TDX client_credentials 取 token 後轉發（TDX 憑證不再進前端）。
 *
 * 安全：
 *  - /api/vertex 需要 Firebase 登入（Authorization: Bearer <idToken>，firebase-admin 驗證），
 *    未登入一律 401 —— 否則任何人都能燒 Vertex 額度（帳單風險）。
 *  - 速率限制（express-rate-limit）：vertex 每 IP 10 分鐘 20 次；tdx 每 IP 10 分鐘 60 次。
 *  - 路徑白名單：vertex 只放行實際使用的 model 與端點；tdx 只放行景點/停車場兩個資料路徑。
 *  - 只監聽 127.0.0.1：外部一律經 nginx 反向代理進來（nginx 需帶 X-Forwarded-For 供限流辨識來源 IP）。
 *
 * 需 Node 18+（內建 fetch）。
 */
'use strict';
require('dotenv').config();
// 強制對外連線優先走 IPv4：本機為雙棧，Node 預設偏好 IPv6，但本機 IPv6 是會輪換的
// 臨時隱私位址；Vertex 金鑰的 IP 白名單填的是固定 IPv4（210.240.160.150）。
// 不設這行會被 Google 以 API_KEY_IP_ADDRESS_BLOCKED 擋下（403）。
require('dns').setDefaultResultOrder('ipv4first');
const path = require('path');
const crypto = require('crypto');
const express = require('express');
const rateLimit = require('express-rate-limit');
const { ipKeyGenerator } = require('express-rate-limit');
// firebase-admin v14 起僅支援模組化 API（admin.credential.* 已移除）
const { initializeApp, cert } = require('firebase-admin/app');
const { getAuth } = require('firebase-admin/auth');
const { getFirestore, Timestamp } = require('firebase-admin/firestore');
// API 成本統計（docs/API成本統計-實作計畫.md）
const pricing = require('./pricing');
const genRuns = require('./generation-runs');
const { createUsageTap, modelIdFromPath } = require('./usage-tap');
// AI_PROVIDER=amd 時，文字生成改走 AMD／工研院的 gpt-oss-120b（Gemini 格式在這裡轉換）
const amdLlm = require('./amd-llm');
// 回顧短片後端渲染 job（M8）：獨立模組，掛在既有代理上
const { mountRecapJobs } = require('./recap-jobs');

const PORT = Number(process.env.PORT) || 3001;
const VERTEX_API_KEY = (process.env.VERTEX_API_KEY || '').trim();
const TDX_APP_ID = (process.env.TDX_APP_ID || '').trim();
const TDX_APP_KEY = (process.env.TDX_APP_KEY || '').trim();
const VERTEX_UPSTREAM = 'https://aiplatform.googleapis.com';
const PLACES_UPSTREAM = 'https://places.googleapis.com';
// Places (New) 改走代理後，伺服器需要自己的 Maps 金鑰。
// 必須是「不受 HTTP referrer 限制」的金鑰——瀏覽器用的那把在伺服器端會被 403。
const GOOGLE_MAPS_SERVER_KEY = (process.env.GOOGLE_MAPS_SERVER_KEY || process.env.GOOGLE_MAPS_API_KEY || '').trim();
const TDX_UPSTREAM = 'https://tdx.transportdata.tw';

if (!VERTEX_API_KEY) {
  console.error('[proxy] 缺少 VERTEX_API_KEY，請在 server/.env 設定後再啟動。');
  process.exit(1);
}
if (typeof fetch !== 'function') {
  console.error('[proxy] 需要 Node 18 以上（內建 fetch）。目前版本：' + process.version);
  process.exit(1);
}

// ── Firebase Admin（驗證前端帶來的 ID token）──
// 服務帳戶沿用 crawler 的那份；路徑可用 FIREBASE_SERVICE_ACCOUNT_PATH 覆蓋。
const SA_PATH = process.env.FIREBASE_SERVICE_ACCOUNT_PATH
  || path.join(__dirname, '..', 'crawler', 'serviceAccount.json');
let adminReady = false;
let adminDb = null;
try {
  initializeApp({ credential: cert(require(SA_PATH)) });
  adminDb = getFirestore();
  genRuns.init(adminDb);   // 用量紀錄一律走 Admin SDK 寫入（繞過 Rules，用戶端只讀）
  adminReady = true;
  console.log('[proxy] firebase-admin 已初始化（' + SA_PATH + '）');
} catch (e) {
  console.error('[proxy] firebase-admin 初始化失敗，/api/vertex 將一律回 503：', e.message);
}

const app = express();
app.disable('x-powered-by'); // 不洩漏後端框架
// 只信任 nginx 這一層代理（proxy 僅監聽 127.0.0.1，XFF 只可能由 nginx 設定，可信）
app.set('trust proxy', 1);
// 停車回報只會是幾百位元組。全域 5mb 的 JSON 解析在所有 rateLimit 之前跑，
// 攻擊者可以對任何端點先塞大 JSON 消耗解析資源——限流擋不到解析本身。
// 這道守衛必須排在 express.json 之前，靠 Content-Length 先擋掉，不進解析器。
app.use('/api/parking-report', (req, res, next) => {
  const len = Number(req.headers['content-length'] || 0);
  if (len > 8 * 1024) return res.status(413).json({ error: 'payload too large' });
  next();
});
// ⚠ 上面那道只看 Content-Length，chunked 傳輸可以不帶這個標頭就繞過去
//   （Codex 審查抓到）。所以再掛一個「這條路由專用」的 8kb 解析器：
//   body-parser 會設 req._body，後面的全域 5mb 解析器看到就直接跳過，
//   真正的位元組數由這裡把關，不靠攻擊者自己宣告的長度。
app.use('/api/parking-report', express.json({ limit: '8kb' }));
app.use(express.json({ limit: '5mb' })); // 行程 prompt 可能較大

app.get('/api/health', (_req, res) => res.json({ ok: true, service: 'wanderai-proxy' }));

const collabLimiter = rateLimit({
  windowMs: 10 * 60 * 1000,
  max: 30,
  standardHeaders: true,
  legacyHeaders: false,
  message: { error: 'rate limited', message: '邀請驗證次數過多，請稍後再試。' }
});

const publicTripLimiter = rateLimit({
  windowMs: 10 * 60 * 1000,
  max: 90,
  standardHeaders: true,
  legacyHeaders: false,
  message: { error: 'rate limited' }
});

function normalizeInviteCode(value) {
  return String(value || '').toUpperCase().replace(/[^A-Z0-9]/g, '');
}

function sameSecret(a, b) {
  const left = Buffer.from(String(a || ''), 'utf8');
  const right = Buffer.from(String(b || ''), 'utf8');
  return left.length === right.length && left.length >= 16 && crypto.timingSafeEqual(left, right);
}

function emailIdentityKey(email) {
  return Buffer.from(String(email || '').trim().toLowerCase(), 'utf8').toString('hex');
}

function legacyMemberKey(email) {
  return String(email || '').trim().toLowerCase().replace(/[^a-z0-9]/g, '_');
}

function normalizeEmail(email) {
  return String(email || '').trim().toLowerCase();
}

function tripOwnerEmail(data) {
  return String(data.ownerEmail || data.userEmail || '').trim();
}

// 所有加入入口共用：包含擁有者，以完整 email 去重；保留原值供舊 App 查詢。
function tripMemberEmails(data) {
  const unique = new Map();
  const emails = (Array.isArray(data.memberEmails) ? data.memberEmails : []).concat(tripOwnerEmail(data));
  for (const email of emails) {
    if (normalizeEmail(email) && !unique.has(normalizeEmail(email))) unique.set(normalizeEmail(email), email);
  }
  return [...unique.values()];
}

function assertLegacyMemberIdentity(data, email) {
  const key = legacyMemberKey(email);
  const member = data.members && data.members[key];
  if (!normalizeEmail(email)
      || (member && normalizeEmail(member.email) !== normalizeEmail(email))
      || tripMemberEmails(data).some((other) => legacyMemberKey(other) === key && normalizeEmail(other) !== normalizeEmail(email))) {
    const err = new Error('此帳號與既有成員的識別資料衝突，暫時無法加入；請聯絡管理者。');
    err.status = 409;
    throw err;
  }
}

function isTripOwner(user, data) {
  const ownerEmail = normalizeEmail(tripOwnerEmail(data));
  return Boolean(
    (data.ownerUid && user.uid === data.ownerUid)
    || (user.email && ownerEmail && normalizeEmail(user.email) === ownerEmail)
  );
}

function generateServerInviteCode() {
  const alphabet = 'ABCDEFGHJKLMNPQRSTUVWXYZ23456789';
  const bytes = crypto.randomBytes(8);
  let code = '';
  for (let i = 0; i < bytes.length; i += 1) code += alphabet[bytes[i] % alphabet.length];
  return code;
}

function generateServerShareToken() {
  return crypto.randomBytes(24).toString('base64url');
}

function notificationRef(email, id) {
  return adminDb.collection('user_notifications')
    .doc(emailIdentityKey(email))
    .collection('items')
    .doc(String(id).replace(/[^A-Za-z0-9_-]/g, '_').slice(0, 300));
}

function publicTripData(tripId, data) {
  const allowed = [
    'title', 'customTitle', 'emoji', 'days', 'region', 'budget', 'people',
    'wizardData', 'stops', 'status', 'currentStopIndex', 'startedAt', 'updatedAt',
    'createdAt', 'collab', 'ownerName', 'organizer', 'departureDate', 'tripMode'
  ];
  const result = { id: tripId, guestView: true };
  for (const key of allowed) {
    if (Object.prototype.hasOwnProperty.call(data, key)) result[key] = data[key];
  }
  return result;
}

// ══════════════ API 成本統計：generation_runs（需登入 + 限流）══════════════
// 設計見 docs/API成本統計-實作計畫.md。
// 三層信任邊界：authoritativeUsage（後端觀察，改不了）／costUsd（後端估算，改不了）
// ／clientReportedUsage（前端回報，可竄改，UI 必須另外標示）。

const genRunLimiter = rateLimit({
  windowMs: 10 * 60 * 1000,
  max: 120,
  standardHeaders: true,
  legacyHeaders: false,
  message: { error: 'rate limited' }
});

// 核發一次生成的 runId。綁定呼叫者 uid，之後每次代理請求都會比對。
app.post('/api/generation-runs/start', genRunLimiter, requireFirebaseUser, (req, res) => {
  const runId = genRuns.createRun(req.user.uid);
  return res.json({ ok: true, runId, pricingVersion: pricing.PRICING_VERSION });
});

// 取出目前 run（含所屬 uid 驗證）。找不到與不屬於你刻意回同一種錯誤，
// 避免用回應差異幫人探測哪些 runId 存在。
function ownedRunOr404(req, res) {
  const run = genRuns.getOwnedRun(req.params.runId, req.user.uid);
  if (!run) {
    res.status(404).json({ error: 'run not found' });
    return null;
  }
  return run;
}

// 一次性綁定 tripId。兩道檢查缺一不可：
//   1. run 屬於呼叫者（ownedRunOr404）
//   2. 呼叫者對這個 tripId 有權限 ← 只驗第 1 點的話，任何登入者都能把自己的 run
//      綁到別人已知的 tripId 上，污染那筆行程的成本顯示（成員也讀得到）。
app.post('/api/generation-runs/:runId/bind-trip', genRunLimiter, requireFirebaseUser, async (req, res) => {
  const run = ownedRunOr404(req, res);
  if (!run) return;
  const tripId = String((req.body && req.body.tripId) || '').trim();
  if (!tripId) return res.status(400).json({ error: 'invalid tripId' });
  if (!adminReady) return res.status(503).json({ error: 'auth unavailable' });

  try {
    const snap = await adminDb.collection('micro_trips').doc(tripId).get();
    if (!snap.exists) return res.status(404).json({ error: 'trip not found' });
    const data = snap.data() || {};
    const email = normalizeEmail(req.user.email || '');
    const allowed = isTripOwner(req.user, data)
      || (email && (data.memberEmails || []).map(normalizeEmail).includes(email))
      || (email && (data.editorEmails || []).map(normalizeEmail).includes(email));
    if (!allowed) return res.status(403).json({ error: 'not your trip' });
  } catch (err) {
    console.error('[proxy] bind-trip 權限檢查失敗：', err && err.message);
    return res.status(500).json({ error: 'bind check failed' });
  }

  const r = genRuns.bindTrip(run, tripId);
  if (!r.ok) return res.status(409).json({ error: 'bind failed', reason: r.reason });
  return res.json({ ok: true });
});

// Maps JS SDK 的用量由前端回報、後端代寫，
// 如此整份 run 文件對用戶端維持唯讀，信任邊界只有這一個缺口且集中在此。
app.post('/api/generation-runs/:runId/client-usage', genRunLimiter, requireFirebaseUser, (req, res) => {
  const run = ownedRunOr404(req, res);
  if (!run) return;
  genRuns.setClientReportedUsage(run, req.body || {});
  return res.json({ ok: true });
});

// 收尾：落地並標記 complete。沒收到這支（關分頁、當機）的 run
// 會由 sweep 以 incomplete 落地——顯示「統計未完成」，不是花了 0 元。
app.post('/api/generation-runs/:runId/finish', genRunLimiter, requireFirebaseUser, async (req, res) => {
  const run = ownedRunOr404(req, res);
  if (!run) return;
  // 前端會告知這次生成是否成功；失敗一律落成 incomplete，
  // 不能因為「有呼叫 finish」就把殘缺紀錄標成完整資料。
  const succeeded = !(req.body && req.body.ok === false);
  const ok = await genRuns.finishRun(run, { ok: succeeded });
  return res.json({ ok });
});

// ══════════════ Vertex AI（需登入 + 限流 + model 白名單）══════════════

// 只放行實際用到的 model 與端點（過度寬鬆的白名單＝幫全網代付其他 model 的費用）
const VERTEX_ALLOWED_PATH = new RegExp(
  '^/(?:' +
  'v1/publishers/google/models/(?:gemini-3-flash-preview|gemini-3\\.1-flash-image):(?:generateContent|streamGenerateContent)' +
  '|v1beta1/projects/[^/]+/locations/global/publishers/google/models/(?:gemini-3-flash-preview|gemini-3\\.1-flash-image):generateContent' +
  ')$'
);

const vertexLimiter = rateLimit({
  windowMs: 10 * 60 * 1000,
  max: 20, // 每 IP 10 分鐘 20 次（一次行程生成含重試/補站約 3–6 次呼叫）
  standardHeaders: true,
  legacyHeaders: false,
  message: { error: 'rate limited', message: '請求過於頻繁，請稍後再試。' }
});

// 驗證 Firebase ID token（前端登入後由 SDK 取得，隨請求帶在 Authorization 標頭）
async function requireFirebaseUser(req, res, next) {
  if (!adminReady) return res.status(503).json({ error: 'auth unavailable', message: '伺服器驗證模組未就緒。' });
  const m = String(req.headers.authorization || '').match(/^Bearer\s+(.+)$/i);
  if (!m) return res.status(401).json({ error: 'login required', message: '請先登入後再使用 AI 功能。' });
  try {
    req.user = await getAuth().verifyIdToken(m[1]);
    return next();
  } catch (_e) {
    return res.status(401).json({ error: 'invalid token', message: '登入狀態已失效，請重新登入。' });
  }
}

// 邀請碼只能送到後端驗證。驗證成功後建立短效 join proof，Firestore Rules
// 才允許該登入者讀取行程並把自己加入 memberEmails。
/* ══ 停車回報（群眾外包停車點的寫入邊界）══════════════════════════
   實作依據：docs/停車回報群眾外包-實作計畫.md 第四節。

   為什麼不讓前端直寫 Firestore：
   - Rules 表達不了「回報點必須在目的地 800m 內」這種需要反查行程的驗證
   - 前端直寫沒有可靠的限流與重放防護
   比照 generation_runs 的既有模式：集合對用戶端 read/write 全關，只由 Admin SDK 寫入。

   ⚠ Admin SDK 繞過 Rules——這裡漏掉的驗證，Rules 不會補救。所有欄位一律由伺服器組裝，
     絕不把 req.body 整包塞進 Firestore。 */

const PARKING_REPORT_COLLECTION = 'parking_reports';
const PARKING_REPORT_TYPES = new Set(['found', 'full', 'closed', 'none', 'wrong', 'missing']);
const PARKING_REPORT_KINDS = new Set(['lot', 'roadside', 'temp', 'unknown']); // temp 保留舊客戶端相容
const PARKING_GEOFENCE_METERS = 800;   // 超出視為與這一站無關（多半是住家或飯店）
const PARKING_MAX_ACCURACY = 50;       // 計畫 5.3：更差的定位無法支撐 30m 群聚
const PARKING_REVOTE_DAYS = 30;        // 同一人對同一地點的重複回報視為更新，不是新的一票

// IP 層：擋粗糙攻擊，放在 token 驗證之前（未驗證的請求也要付代價）
const parkingReportIpLimiter = rateLimit({
  windowMs: 60 * 60 * 1000,
  max: 30,
  standardHeaders: true,
  legacyHeaders: false,
  message: { error: 'rate limited' }
});
// UID 層：放在驗證之後，用帳號而非 IP 計算（換 IP 繞不過）
const parkingReportUidLimiter = rateLimit({
  windowMs: 24 * 60 * 60 * 1000,
  max: 10,
  standardHeaders: true,
  legacyHeaders: false,
  // 未登入時退回 IP：必須用套件提供的 ipKeyGenerator，直接回 req.ip 會被
  // express-rate-limit v7+ 的 IPv6 驗證擋下（ERR_ERL_KEY_GEN_IPV6）。
  keyGenerator: (req, res) => (req.user && req.user.uid) || ipKeyGenerator(req, res),
  message: { error: 'daily limit reached' }
});

function haversineMeters(a, b) {
  const R = 6371000, rad = (x) => (x * Math.PI) / 180;
  const dLat = rad(b.lat - a.lat), dLng = rad(b.lng - a.lng);
  const s = Math.sin(dLat / 2) ** 2
    + Math.cos(rad(a.lat)) * Math.cos(rad(b.lat)) * Math.sin(dLng / 2) ** 2;
  return 2 * R * Math.asin(Math.sqrt(s));
}

/* 只接受真正的 JSON number。
   刻意不用 Number(v)——Number(null)、Number('')、Number([]) 全都是 0，
   於是「沒帶座標」會被當成 (0, 0)、「沒帶 accuracy」會被當成完美精度 0。
   前者目前碰巧被地理圍籬擋下，但那是僥倖不是設計。 */
function finiteNumber(v) {
  return typeof v === 'number' && Number.isFinite(v) ? v : null;
}

/* 地點鍵：用「站點座標」而非 tripId+stopId。
   同一個實體地點在不同行程裡是不同的 stopId，若用後者當鍵，
   同一個人跑兩趟就會被算成兩票，聚合門檻形同虛設。
   4 位小數 ≈ 11m，與計畫 5.2 的群聚精度一致。 */
function destKeyOf(lat, lng) {
  return `d${(Math.round(lat * 1e4) / 1e4).toFixed(4)}_${(Math.round(lng * 1e4) / 1e4).toFixed(4)}`
    .replace(/\./g, 'p').replace(/-/g, 'm');
}

app.post('/api/parking-report', parkingReportIpLimiter, requireFirebaseUser, parkingReportUidLimiter, async (req, res) => {
  if (!adminReady) return res.status(503).json({ error: 'storage unavailable' });
  const body = req.body || {};

  const tripId = String(body.tripId || '').trim().slice(0, 120);
  // stopId 只留作事後對帳；比對目的地靠的是座標，所以這裡限長度就夠，
  // 不必也不能要求它對得上（前端的 cstop-* 是每個 session 自己生的）。
  const stopId = String(body.stopId || '').trim().slice(0, 120);
  const type = String(body.type || '').trim();
  const kind = String(body.kind || '').trim();
  const note = String(body.note || '').trim().slice(0, 120);
  const lat = finiteNumber(body.lat);
  const lng = finiteNumber(body.lng);
  const accuracy = finiteNumber(body.accuracy);
  const adjusted = body.adjusted === true;

  if (!tripId || !stopId) return res.status(400).json({ error: 'invalid trip or stop' });
  if (!PARKING_REPORT_TYPES.has(type)) return res.status(400).json({ error: 'invalid type' });

  // 只有「找到停車場」帶座標；其餘狀況沒有可標的點
  const hasCoords = lat !== null && lng !== null;
  // ⚠ 範圍檢查要在 type 分支「之外」——原本只檢查 found，於是 type: 'full'
  //   可以塞 lng = 目的地經度 + 360：Haversine 的 sin(180°) = 0，圍籬照樣通過，
  //   資料庫卻存進超出 [-180, 180] 的經度（Codex 審查抓到）。
  if (hasCoords && (lat < -90 || lat > 90 || lng < -180 || lng > 180)) {
    return res.status(400).json({ error: 'coordinates out of range' });
  }
  if (type === 'found') {
    if (!hasCoords) return res.status(400).json({ error: 'coordinates required' });
    if (!PARKING_REPORT_KINDS.has(kind)) return res.status(400).json({ error: 'invalid kind' });
    if (accuracy === null || accuracy < 0 || accuracy > PARKING_MAX_ACCURACY) {
      return res.status(400).json({ error: 'accuracy too low' });
    }
  } else if (kind && !PARKING_REPORT_KINDS.has(kind)) {
    // 非 found 的 kind 沒有意義，但也不能讓任意字串寫進資料庫
    return res.status(400).json({ error: 'invalid kind' });
  }

  // 目的地座標一律由伺服器反查。
  // ★ 絕不接受前端傳來的目的地座標——攻擊者只要把它改成與假位置相同，
  //   800m 圍籬就必定通過，等於沒有圍籬（計畫 1.4 假設 F）。
  let destLat = null, destLng = null;
  try {
    const snap = await adminDb.collection('micro_trips').doc(tripId).get();
    if (!snap.exists) return res.status(404).json({ error: 'trip not found' });
    const data = snap.data() || {};
    const email = normalizeEmail(req.user.email || '');
    const allowed = isTripOwner(req.user, data)
      || (email && (data.memberEmails || []).map(normalizeEmail).includes(email))
      || (email && (data.editorEmails || []).map(normalizeEmail).includes(email));
    if (!allowed) return res.status(403).json({ error: 'not your trip' });

    /* 找出這筆回報對應的站點。
       ⚠ 不能用 stopId 比對——前端的 `cstop-*` 是每個 session 自己生成的，
         Firestore 的 stops[] 裡根本沒有 id 欄位（端到端測試才發現）。

       改為由前端宣告站點座標，後端**在這趟行程的 stops 裡找有沒有這個站**：
       - 找得到 → 用「Firestore 裡那一筆的座標」當權威值，不用前端宣告的
       - 找不到 → 拒絕
       防偽造的性質因此保住：攻擊者無法憑空指定目的地，
       他宣告的座標必須真的對應到一個他有權限的行程裡的站點。 */
    const claimLat = finiteNumber(body.stopLat);
    const claimLng = finiteNumber(body.stopLng);
    if (claimLat === null || claimLng === null) {
      return res.status(400).json({ error: 'stop coordinates required' });
    }
    const STOP_MATCH_METERS = 50;   // 容忍前端與資料庫間的浮點誤差，但不足以指到另一個站
    let matched = null, matchedDist = Infinity;
    for (const s of (Array.isArray(data.stops) ? data.stops : [])) {
      const sLat = finiteNumber(s && s.lat), sLng = finiteNumber(s && s.lng);
      if (sLat === null || sLng === null) continue;
      const d = haversineMeters({ lat: claimLat, lng: claimLng }, { lat: sLat, lng: sLng });
      if (d < matchedDist) { matchedDist = d; matched = { lat: sLat, lng: sLng }; }
    }
    if (!matched || matchedDist > STOP_MATCH_METERS) {
      return res.status(404).json({ error: 'stop not found' });
    }
    destLat = matched.lat;   // 一律用資料庫裡的值，不用前端宣告的
    destLng = matched.lng;
  } catch (err) {
    console.error('[proxy] parking-report 反查目的地失敗：', err && err.message);
    return res.status(500).json({ error: 'lookup failed' });
  }

  // 地理圍籬：同時做兩件事——濾掉配對雜訊，以及濾掉在家裡或飯店拍下的位置
  let distance = null;
  if (hasCoords) {
    distance = haversineMeters({ lat, lng }, { lat: destLat, lng: destLng });
    if (distance > PARKING_GEOFENCE_METERS) {
      return res.status(422).json({ error: 'too far from destination' });
    }
  }

  // 文件 ID＝地點＋使用者：同一人對同一地點永遠只有一筆，
  // 聚合時「不同文件數」就等於「不同回報者數」，不必另外去重（計畫 6.6）。
  const destKey = destKeyOf(destLat, destLng);
  const docId = `${destKey}__${req.user.uid}`;
  const now = Timestamp.now();
  // accuracy 只在 found 走過上面的上下界檢查；其餘狀況一律不落地，
  // 免得非法數值混進去污染日後的聚合統計。
  const safeAccuracy = (type === 'found') ? accuracy : null;

  try {
    const ref = adminDb.collection(PARKING_REPORT_COLLECTION).doc(docId);
    const prev = await ref.get();
    if (prev.exists) {
      const prevAt = prev.data() && prev.data().at;
      const prevMs = prevAt && typeof prevAt.toMillis === 'function' ? prevAt.toMillis() : 0;
      const days = (Date.now() - prevMs) / 86400000;
      if (days < PARKING_REVOTE_DAYS) {
        // 30 天內重複回報＝更新自己的那一票，不是新增一票
        await ref.set({ type, kind: kind || null, note, lat, lng, accuracy: safeAccuracy, adjusted, at: now, updatedAt: now }, { merge: true });
        return res.json({ ok: true, updated: true, destKey });
      }
    }
    // 欄位逐一指定，不把 req.body 展開進來
    await ref.set({
      uid: req.user.uid,
      destKey,
      tripId,
      stopId,
      type,
      kind: kind || null,
      note,
      lat, lng, accuracy: safeAccuracy, adjusted,
      distance: distance === null ? null : Math.round(distance),
      at: now,
      updatedAt: now
    });
    return res.json({ ok: true, created: true, destKey });
  } catch (err) {
    // 不要把座標或 body 寫進日誌
    console.error('[proxy] parking-report 寫入失敗：', err && err.message);
    return res.status(500).json({ error: 'write failed' });
  }
});

/* 我自己的停車回報。
   這是「冷啟動緩解」的核心（計畫 6.5）：聚合門檻要 3 位不同使用者，
   在使用者規模夠大之前幾乎不會達標。但同一個人再訪同一地點時，
   他自己上次的回報應該立即生效——不需要別人附議。
   查詢只用 uid 單欄位，不需要複合索引。 */
app.get('/api/parking-reports/mine', parkingReportIpLimiter, requireFirebaseUser, async (req, res) => {
  if (!adminReady) return res.status(503).json({ error: 'storage unavailable' });
  try {
    const snap = await adminDb.collection(PARKING_REPORT_COLLECTION)
      .where('uid', '==', req.user.uid).limit(200).get();
    const items = [];
    snap.forEach((doc) => {
      const d = doc.data() || {};
      // 只回前端畫得出來的欄位；note 是自己寫的可以回，但不外流給別人（本端點只回自己的）
      if (d.type !== 'found') return;
      if (typeof d.lat !== 'number' || typeof d.lng !== 'number') return;
      items.push({
        destKey: d.destKey || doc.id.split('__')[0],
        lat: d.lat, lng: d.lng, kind: d.kind || null, note: d.note || '',
        at: d.at && typeof d.at.toMillis === 'function' ? d.at.toMillis() : 0
      });
    });
    return res.json({ ok: true, items });
  } catch (err) {
    console.error('[proxy] parking-reports/mine 讀取失敗：', err && err.message);
    return res.status(500).json({ error: 'read failed' });
  }
});

/* 刪除自己的停車回報（計畫 4.6）。
   「用戶端不可 update/delete」與「使用者刪除個資的權利」不衝突——
   前者約束的是 Rules 層，後端 Admin SDK 仍可刪。

   ★ 真的刪除文件，不是加 deleted:true。留著原座標與 uid 不等於刪除。
     只保留最小化的處理紀錄（隨機請求編號、時間、結果），不含座標與 uid。 */
app.delete('/api/parking-report/:destKey', parkingReportIpLimiter, requireFirebaseUser, async (req, res) => {
  if (!adminReady) return res.status(503).json({ error: 'storage unavailable' });
  const destKey = String(req.params.destKey || '').trim();
  if (!/^[a-zA-Z0-9_]{1,64}$/.test(destKey)) return res.status(400).json({ error: 'invalid destKey' });
  const docId = `${destKey}__${req.user.uid}`;   // 只可能刪到自己的那一筆
  try {
    const ref = adminDb.collection(PARKING_REPORT_COLLECTION).doc(docId);
    const snap = await ref.get();
    if (!snap.exists) return res.status(404).json({ error: 'not found' });
    await ref.delete();
    // 處理紀錄：可稽核「有處理過刪除請求」，但無法從中還原是誰、在哪
    await adminDb.collection('parking_report_deletions').add({
      requestId: crypto.randomBytes(8).toString('hex'),
      at: Timestamp.now(),
      result: 'deleted',
      needsReaggregation: destKey     // 供後續聚合重算；destKey 是地點不是個人
    }).catch(() => {});
    return res.json({ ok: true, deleted: true });
  } catch (err) {
    console.error('[proxy] parking-report 刪除失敗：', err && err.message);
    return res.status(500).json({ error: 'delete failed' });
  }
});

app.post('/api/collab/invites/verify', collabLimiter, requireFirebaseUser, async (req, res) => {
  // 信箱驗證只屬於註冊提示；登入後的共編功能僅要求 token 具有 email。
  if (!req.user.email) {
    return res.status(403).json({ error: 'email required', message: '這個登入帳號沒有可用的電子信箱。' });
  }
  const code = normalizeInviteCode(req.body && req.body.code);
  if (code.length < 6 || code.length > 16) {
    return res.status(400).json({ error: 'invalid invite', message: '邀請碼格式不正確。' });
  }

  try {
    const inviteSnap = await adminDb.collection('invites').doc(code).get();
    if (!inviteSnap.exists || inviteSnap.get('active') !== true) {
      return res.status(404).json({ error: 'invalid invite', message: '找不到邀請碼，或邀請碼已失效。' });
    }
    const tripId = inviteSnap.get('tripId');
    if (typeof tripId !== 'string' || !tripId) {
      return res.status(404).json({ error: 'invalid invite', message: '找不到邀請碼，或邀請碼已失效。' });
    }
    const tripRef = adminDb.collection('micro_trips').doc(tripId);
    const tripSnap = await tripRef.get();
    if (!tripSnap.exists) {
      return res.status(404).json({ error: 'trip not found', message: '行程不存在或已被刪除。' });
    }
    const trip = tripSnap.data();
    assertLegacyMemberIdentity(trip, req.user.email);
    const members = tripMemberEmails(trip);
    const alreadyMember = members.some((email) => normalizeEmail(email) === normalizeEmail(req.user.email));
    const maxMembers = Math.min(Math.max(Number(trip.maxMembers) || 10, 1), 50);
    if (!alreadyMember && members.length >= maxMembers) {
      return res.status(409).json({ error: 'trip full', message: `這個行程人數已滿（上限 ${maxMembers} 人）。` });
    }

    const now = Date.now();
    await tripRef.collection('join_proofs').doc(req.user.uid).set({
      email: req.user.email,
      createdAt: Timestamp.fromMillis(now),
      expiresAt: Timestamp.fromMillis(now + 2 * 60 * 1000)
    });

    return res.json({
      ok: true,
      tripId,
      alreadyMember,
      preview: {
        id: tripId,
        title: trip.title || '共編行程',
        ownerName: trip.ownerName || '行程擁有者',
        memberCount: members.length,
        maxMembers
      }
    });
  } catch (err) {
    console.error('[proxy] invite verify failed:', err && err.message);
    return res.status(err.status || 500).json({ error: 'invite verify failed', message: err.status ? err.message : '目前無法驗證邀請碼，請稍後再試。' });
  }
});

// 訪客分享不直接讀 micro_trips。後端核對不可猜的 token 後，只回傳畫面需要的
// 行程欄位，排除 email、members、inviteCode 等協作與個資欄位。
app.get('/api/collab/public-trip', publicTripLimiter, async (req, res) => {
  if (!adminReady) return res.status(503).json({ error: 'service unavailable' });
  const tripId = String(req.query.tripId || '');
  const token = String(req.query.token || '');
  if (!/^[A-Za-z0-9_-]{3,150}$/.test(tripId) || token.length > 200) {
    return res.status(400).json({ error: 'invalid share link', message: '分享連結無效。' });
  }
  try {
    const snap = await adminDb.collection('micro_trips').doc(tripId).get();
    const data = snap.exists ? snap.data() : null;
    if (!data || !sameSecret(token, data.shareToken)) {
      return res.status(404).json({ error: 'invalid share link', message: '分享連結已失效或不存在。' });
    }
    res.set('Cache-Control', 'private, no-store');
    return res.json({ ok: true, trip: publicTripData(tripId, data) });
  } catch (err) {
    console.error('[proxy] public trip failed:', err && err.message);
    return res.status(500).json({ error: 'public trip failed', message: '目前無法讀取分享行程，請稍後再試。' });
  }
});

// 唯讀分享頁的加入申請。分享權杖只允許提出申請，不會直接賦予成員權限。
app.post('/api/collab/join-requests', collabLimiter, requireFirebaseUser, async (req, res) => {
  if (!req.user.email) {
    return res.status(403).json({ error: 'email required', message: '此帳號缺少 Email，無法申請加入。' });
  }
  const tripId = String(req.body && req.body.tripId || '');
  const token = String(req.body && req.body.token || '');
  if (!/^[A-Za-z0-9_-]{3,150}$/.test(tripId) || token.length > 200) {
    return res.status(400).json({ error: 'invalid share link', message: '分享連結格式不正確。' });
  }

  try {
    const tripRef = adminDb.collection('micro_trips').doc(tripId);
    const requestRef = tripRef.collection('join_requests').doc(req.user.uid);
    const result = await adminDb.runTransaction(async (tx) => {
      const [tripSnap, requestSnap] = await Promise.all([tx.get(tripRef), tx.get(requestRef)]);
      if (!tripSnap.exists) {
        const err = new Error('找不到這份行程。');
        err.status = 404;
        throw err;
      }
      const trip = tripSnap.data();
      if (!sameSecret(token, trip.shareToken)) {
        const err = new Error('分享連結已失效，請向擁有者索取新連結。');
        err.status = 404;
        throw err;
      }
      if (isTripOwner(req.user, trip)) {
        const err = new Error('你已經是這份行程的擁有者。');
        err.status = 409;
        throw err;
      }
      assertLegacyMemberIdentity(trip, req.user.email);
      const memberEmails = tripMemberEmails(trip);
      if (memberEmails.some((email) => normalizeEmail(email) === normalizeEmail(req.user.email))) {
        return { status: 'accepted', alreadyMember: true };
      }
      const maxMembers = Math.min(Math.max(Number(trip.maxMembers) || 10, 1), 50);
      // 與接受申請使用同一人數口徑；名額仍須在接受的 transaction 中重驗。
      if (memberEmails.length >= maxMembers) {
        const err = new Error(`這份行程的成員已達上限（${maxMembers} 人）。`);
        err.status = 409;
        throw err;
      }
      const previous = requestSnap.exists ? requestSnap.data() : null;
      if (previous && previous.status === 'pending') return { status: 'pending', alreadyMember: false };
      if (previous && previous.status === 'accepted') return { status: 'accepted', alreadyMember: true };

      const ownerEmail = tripOwnerEmail(trip);
      if (!ownerEmail) {
        const err = new Error('這份行程缺少擁有者資料，暫時無法申請加入。');
        err.status = 409;
        throw err;
      }
      const now = Timestamp.now();
      const requesterName = String(req.user.name || req.user.email.split('@')[0] || '旅伴').slice(0, 100);
      const requestData = {
        requesterUid: req.user.uid,
        requesterEmail: req.user.email,
        requesterName,
        status: 'pending',
        requestedAt: now,
        updatedAt: now
      };
      tx.set(requestRef, requestData, { merge: true });
      tx.set(notificationRef(ownerEmail, `trip_join_request__${tripId}__${req.user.uid}`), {
        type: 'trip_join_request',
        fromEmail: req.user.email,
        toEmail: ownerEmail,
        fromName: requesterName,
        tripId,
        tripTitle: String(trip.title || trip.aiTitle || '微旅行').slice(0, 200),
        message: '申請加入你的行程',
        requesterUid: req.user.uid,
        requestStatus: 'pending',
        read: false,
        createdAt: now
      }, { merge: true });
      return { status: 'pending', alreadyMember: false };
    });
    return res.json({ ok: true, ...result });
  } catch (err) {
    console.error('[proxy] join request failed:', err && err.message);
    return res.status(err.status || 500).json({
      error: 'join request failed',
      message: err.status ? err.message : '送出加入申請失敗，請稍後再試。'
    });
  }
});

app.get('/api/collab/join-requests/status', collabLimiter, requireFirebaseUser, async (req, res) => {
  const tripId = String(req.query.tripId || '');
  const token = String(req.query.token || '');
  if (!/^[A-Za-z0-9_-]{3,150}$/.test(tripId) || token.length > 200) {
    return res.status(400).json({ error: 'invalid share link', message: '分享連結格式不正確。' });
  }
  try {
    const tripRef = adminDb.collection('micro_trips').doc(tripId);
    const [tripSnap, requestSnap] = await Promise.all([
      tripRef.get(),
      tripRef.collection('join_requests').doc(req.user.uid).get()
    ]);
    const trip = tripSnap.exists ? tripSnap.data() : null;
    if (!trip || !sameSecret(token, trip.shareToken)) {
      return res.status(404).json({ error: 'invalid share link', message: '分享連結已失效。' });
    }
    if (isTripOwner(req.user, trip)) return res.json({ ok: true, status: 'owner' });
    const members = Array.isArray(trip.memberEmails) ? trip.memberEmails : [];
    if (req.user.email && members.some((email) => normalizeEmail(email) === normalizeEmail(req.user.email))) {
      return res.json({ ok: true, status: 'accepted' });
    }
    return res.json({ ok: true, status: requestSnap.exists ? requestSnap.get('status') || 'none' : 'none' });
  } catch (err) {
    console.error('[proxy] join request status failed:', err && err.message);
    return res.status(500).json({ error: 'status failed', message: '無法取得申請狀態。' });
  }
});

// 擁有者核准時，以同一筆 transaction 完成「個人行程轉多人」與加入 viewer。
app.post('/api/collab/join-requests/resolve', collabLimiter, requireFirebaseUser, async (req, res) => {
  const tripId = String(req.body && req.body.tripId || '');
  const requesterUid = String(req.body && req.body.requesterUid || '');
  const decision = String(req.body && req.body.decision || '');
  if (!/^[A-Za-z0-9_-]{3,150}$/.test(tripId) || !requesterUid || !['accept', 'reject'].includes(decision)) {
    return res.status(400).json({ error: 'invalid request', message: '申請資料不完整。' });
  }
  try {
    const tripRef = adminDb.collection('micro_trips').doc(tripId);
    const requestRef = tripRef.collection('join_requests').doc(requesterUid);
    const result = await adminDb.runTransaction(async (tx) => {
      const [tripSnap, requestSnap] = await Promise.all([tx.get(tripRef), tx.get(requestRef)]);
      if (!tripSnap.exists || !requestSnap.exists) {
        const err = new Error('找不到這筆加入申請。');
        err.status = 404;
        throw err;
      }
      const trip = tripSnap.data();
      const joinRequest = requestSnap.data();
      if (!isTripOwner(req.user, trip)) {
        const err = new Error('只有行程擁有者可以處理加入申請。');
        err.status = 403;
        throw err;
      }
      if (joinRequest.status !== 'pending') {
        return { status: joinRequest.status, collab: Boolean(trip.collab) };
      }

      const now = Timestamp.now();
      const ownerEmail = tripOwnerEmail(trip) || req.user.email;
      const ownerName = String(trip.ownerName || req.user.name || ownerEmail.split('@')[0] || '擁有者').slice(0, 100);
      const requesterEmail = String(joinRequest.requesterEmail || '').trim();
      const requesterName = String(joinRequest.requesterName || requesterEmail.split('@')[0] || '旅伴').slice(0, 100);
      const ownerNotification = notificationRef(ownerEmail, `trip_join_request__${tripId}__${requesterUid}`);

      if (decision === 'reject') {
        tx.set(requestRef, {
          status: 'rejected',
          resolvedAt: now,
          resolvedBy: req.user.uid,
          updatedAt: now
        }, { merge: true });
        tx.set(ownerNotification, { requestStatus: 'rejected', read: true }, { merge: true });
        tx.set(notificationRef(requesterEmail, `trip_join_rejected__${tripId}__${requesterUid}`), {
          type: 'trip_join_rejected',
          fromEmail: ownerEmail,
          toEmail: requesterEmail,
          fromName: ownerName,
          tripId,
          tripTitle: String(trip.title || trip.aiTitle || '微旅行').slice(0, 200),
          message: '擁有者未接受這次加入申請',
          read: false,
          createdAt: now
        }, { merge: true });
        return { status: 'rejected', collab: Boolean(trip.collab) };
      }

      assertLegacyMemberIdentity(trip, ownerEmail);
      assertLegacyMemberIdentity(trip, requesterEmail);
      const memberEmails = tripMemberEmails(trip);
      const alreadyMember = memberEmails.some((email) => normalizeEmail(email) === normalizeEmail(requesterEmail));
      const maxMembers = Math.min(Math.max(Number(trip.maxMembers) || 10, 1), 50);
      if (!alreadyMember && memberEmails.length >= maxMembers) {
        const err = new Error(`這份行程的成員已達上限（${maxMembers} 人）。`);
        err.status = 409;
        throw err;
      }
      if (!alreadyMember) memberEmails.push(requesterEmail);

      // 過渡期保留 legacy key，但上方已核對完整 email，碰撞時整筆交易不寫入。
      const members = trip.members && typeof trip.members === 'object' ? { ...trip.members } : {};
      const ownerKey = legacyMemberKey(ownerEmail);
      members[ownerKey] = {
        ...(members[ownerKey] || {}),
        email: ownerEmail,
        name: ownerName,
        role: 'owner',
        ready: true,
        prefs: (members[ownerKey] && members[ownerKey].prefs) || {},
        joinedAt: (members[ownerKey] && members[ownerKey].joinedAt) || Date.now()
      };
      const requesterKey = legacyMemberKey(requesterEmail);
      members[requesterKey] = {
        ...(members[requesterKey] || {}),
        email: requesterEmail,
        name: requesterName,
        role: 'viewer',
        ready: false,
        prefs: (members[requesterKey] && members[requesterKey].prefs) || {},
        joinedAt: (members[requesterKey] && members[requesterKey].joinedAt) || Date.now()
      };

      let inviteCode = normalizeInviteCode(trip.inviteCode);
      let inviteRef = inviteCode ? adminDb.collection('invites').doc(inviteCode) : null;
      if (!inviteCode) {
        for (let attempt = 0; attempt < 6; attempt += 1) {
          const candidate = generateServerInviteCode();
          const candidateRef = adminDb.collection('invites').doc(candidate);
          const candidateSnap = await tx.get(candidateRef);
          if (!candidateSnap.exists || candidateSnap.get('tripId') === tripId) {
            inviteCode = candidate;
            inviteRef = candidateRef;
            break;
          }
        }
        if (!inviteCode || !inviteRef) {
          const err = new Error('暫時無法建立行程邀請碼，請再試一次。');
          err.status = 503;
          throw err;
        }
      }
      const shareToken = String(trip.shareToken || '').length >= 16 ? trip.shareToken : generateServerShareToken();
      tx.set(tripRef, {
        collab: true,
        ownerUid: trip.ownerUid || req.user.uid,
        ownerEmail,
        ownerName,
        userEmail: trip.userEmail || ownerEmail,
        inviteCode,
        shareToken,
        maxMembers,
        memberEmails,
        editorEmails: Array.isArray(trip.editorEmails) ? trip.editorEmails : [],
        members,
        collabCreatedAt: trip.collabCreatedAt || now,
        updatedAt: now
      }, { merge: true });
      tx.set(inviteRef, {
        tripId,
        active: true,
        createdBy: ownerEmail,
        createdAt: now
      }, { merge: true });
      tx.set(requestRef, {
        status: 'accepted',
        resolvedAt: now,
        resolvedBy: req.user.uid,
        updatedAt: now
      }, { merge: true });
      tx.set(ownerNotification, { requestStatus: 'accepted', read: true }, { merge: true });
      tx.set(notificationRef(requesterEmail, `trip_join_accepted__${tripId}__${requesterUid}`), {
        type: 'trip_join_accepted',
        fromEmail: ownerEmail,
        toEmail: requesterEmail,
        fromName: ownerName,
        tripId,
        tripTitle: String(trip.title || trip.aiTitle || '微旅行').slice(0, 200),
        message: '已接受你的加入申請，你目前是唯讀成員',
        read: false,
        createdAt: now
      }, { merge: true });
      return { status: 'accepted', collab: true, inviteCode, shareToken };
    });
    return res.json({ ok: true, ...result });
  } catch (err) {
    console.error('[proxy] resolve join request failed:', err && err.message);
    return res.status(err.status || 500).json({
      error: 'resolve failed',
      message: err.status ? err.message : '處理加入申請失敗，請稍後再試。'
    });
  }
});

app.post('/api/vertex/*', vertexLimiter, requireFirebaseUser, async (req, res) => {
  const upstreamPath = '/' + (req.params[0] || '');
  if (!VERTEX_ALLOWED_PATH.test(upstreamPath)) {
    return res.status(403).json({ error: 'path not allowed' });
  }

  const url = new URL(VERTEX_UPSTREAM + upstreamPath);
  // 保留前端帶來的 query（例如 alt=sse），但一律用伺服器端金鑰覆蓋 key
  for (const [k, v] of Object.entries(req.query)) {
    if (k !== 'key') url.searchParams.set(k, String(v));
  }
  url.searchParams.set('key', VERTEX_API_KEY);

  // run 必須在 fetch 之前取得並 retain：
  //  1. 初始 fetch 就失敗時才有對象可以 markUpstreamError
  //  2. retain 讓這個 run 在請求結束前不會被 idle 收尾或超量淘汰刪除
  //     （串流生成可能長達數十秒，中途被刪掉的話晚到的用量會遺失）
  const run = genRuns.getOwnedRun(req.headers['x-run-id'], req.user.uid);
  if (req.headers['x-run-id'] && !run) {
    // 只記 log，不回 4xx——記帳失敗絕不可以擋住生成
    console.warn('[proxy] 收到無效的 X-Run-Id，本次不記帳（run 可能已收尾或過期）');
  }
  if (run) genRuns.retain(run);

  if (amdLlm.shouldRoute(modelIdFromPath(upstreamPath))) {
    return proxyToAmdLlm(req, res, upstreamPath, run);
  }

  let upstream;
  try {
    upstream = await fetch(url, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(req.body || {})
    });
  } catch (err) {
    console.error('[proxy] vertex upstream fetch 失敗：', err && err.message);
    if (run) { genRuns.markUpstreamError(run); genRuns.release(run).catch(() => {}); }
    return res.status(502).json({ error: 'upstream fetch failed' });
  }

  res.status(upstream.status);
  const contentType = upstream.headers.get('content-type') || 'application/json';
  res.set('Content-Type', contentType);
  res.set('X-Accel-Buffering', 'no'); // 提示 nginx 對 SSE 不要緩衝

  // ── 成本統計：旁路解析 usageMetadata（實作計畫第 3 步）──
  // 只有帶了合法且屬於自己的 X-Run-Id 才記帳；沒帶就完全照舊，行為零差異。
  // ★ 這裡只「多看一眼」流過去的 bytes，不改變 res.write() 的時機。
  //   絕不可改成 await upstream.text() 先收完再送——那會讓 SSE 失去串流，
  //   生成畫面從「站名逐一浮現」退回「轉圈到最後才一次出現」。
  const isSse = /text\/event-stream/i.test(contentType)
    || String(req.query.alt || '').toLowerCase() === 'sse';
  // 上游失敗要記進 run：idle 自動收尾時才知道這趟不該標成 complete
  if (run && !upstream.ok) genRuns.markUpstreamError(run);
  const tap = (run && upstream.ok) ? createUsageTap(isSse) : null;

  if (!upstream.body) {
    // 回了 200 卻沒有 body：這也是「成功但讀不到 usageMetadata」的一種，
    // 要與有 body 但解析不出用量的情況一致地記下來。
    if (run) {
      if (upstream.ok) genRuns.markUsageMissing(run);
      genRuns.release(run).catch(() => {});
    }
    return res.end();
  }
  try {
    const reader = upstream.body.getReader();
    for (;;) {
      const { value, done } = await reader.read();
      if (done) break;
      const buf = Buffer.from(value);
      res.write(buf);          // ← 轉發時機不變，維持串流
      if (tap) tap.feed(buf);  // ← 同一塊 bytes 順便旁路解析
    }
  } catch (err) {
    console.error('[proxy] vertex 串流轉發中斷：', err && err.message);
    // 上游先回 200、讀到一半才斷：這一樣是失敗，必須標記。
    // 否則 idle 自動收尾時會把這筆殘缺的 run 標成 complete。
    if (run) genRuns.markUpstreamError(run);
  }
  if (tap) {
    try {
      const usage = tap.end();
      if (usage) {
        genRuns.addGeminiUsage(run, modelIdFromPath(upstreamPath), usage);
      } else {
        // 回了 200 卻讀不到 usageMetadata（SSE 格式非預期、超過 buffer 上限…）：
        // 這筆 run 的用量並不完整，記下來讓 UI 可以揭露。
        genRuns.markUsageMissing(run);
      }
    } catch (e) {
      console.warn('[proxy] usage 記帳失敗（不影響回應）：', e && e.message);
    }
  }
  if (run) genRuns.release(run).catch(() => {});
  res.end();
});

// AMD LLM：請求／回應都在 amd-llm.js 轉成 Gemini 格式，記帳沿用同一套 usage-tap。
// 與 Vertex 路徑相同的原則：串流逐塊轉發，不可先收完整包再送。
async function proxyToAmdLlm(req, res, upstreamPath, run) {
  const isStream = /:streamGenerateContent$/.test(upstreamPath);
  const finish = () => { if (run) genRuns.release(run).catch(() => {}); };

  let body;
  try {
    body = amdLlm.toOpenAiRequest(req.body, { stream: isStream });
  } catch (err) {
    finish();
    return res.status(err.status || 400).json({ error: err.message || 'invalid request' });
  }

  // 只限制「等到回應標頭」的時間：IP 不在白名單時連線會一直掛著。
  // 開始收流後就不設限，長行程串流本來就可能跑很久。
  const controller = new AbortController();
  const connectTimer = setTimeout(() => controller.abort(), 90 * 1000);
  let upstream;
  try {
    upstream = await fetch(amdLlm.chatCompletionsUrl(), {
      method: 'POST',
      headers: amdLlm.upstreamHeaders(),
      body: JSON.stringify(body),
      signal: controller.signal
    });
  } catch (err) {
    console.error('[proxy] amd llm upstream fetch 失敗：', err && (err.name === 'AbortError' ? '連線逾時' : err.message));
    if (run) genRuns.markUpstreamError(run);
    finish();
    return res.status(502).json({ error: 'upstream fetch failed' });
  } finally {
    clearTimeout(connectTimer);
  }

  if (!upstream.ok) {
    const detail = await upstream.text().catch(() => '');
    console.error('[proxy] amd llm 回應 ' + upstream.status + '：' + detail.slice(0, 300));
    if (run) genRuns.markUpstreamError(run);
    finish();
    return res.status(upstream.status).json({ error: 'amd llm failed' });
  }

  const tap = run ? createUsageTap(isStream) : null;
  const recordUsage = () => {
    if (!tap) return;
    try {
      const usage = tap.end();
      if (usage) genRuns.addGeminiUsage(run, amdLlm.USAGE_MODEL_ID, usage);
      else genRuns.markUsageMissing(run);
    } catch (e) {
      console.warn('[proxy] usage 記帳失敗（不影響回應）：', e && e.message);
    }
  };

  if (!isStream) {
    let out;
    try {
      out = Buffer.from(JSON.stringify(amdLlm.fromOpenAiResponse(await upstream.json())));
    } catch (err) {
      console.error('[proxy] amd llm 回應解析失敗：', err && err.message);
      if (run) genRuns.markUpstreamError(run);
      finish();
      return res.status(502).json({ error: 'invalid upstream response' });
    }
    if (tap) tap.feed(out);
    recordUsage();
    finish();
    res.set('Content-Type', 'application/json; charset=utf-8');
    return res.end(out);
  }

  res.status(200);
  res.set('Content-Type', 'text/event-stream; charset=utf-8');
  res.set('X-Accel-Buffering', 'no');
  const translator = amdLlm.createSseTranslator();
  const decoder = new TextDecoder();
  const send = (text) => {
    if (!text) return;
    const buf = Buffer.from(text);
    res.write(buf);
    if (tap) tap.feed(buf);
  };
  try {
    const reader = upstream.body.getReader();
    for (;;) {
      const { value, done } = await reader.read();
      if (done) break;
      send(translator.push(decoder.decode(value, { stream: true })));
    }
    send(translator.push(decoder.decode()));
    send(translator.end());
  } catch (err) {
    console.error('[proxy] amd llm 串流轉發中斷：', err && err.message);
    if (run) genRuns.markUpstreamError(run);
  }
  recordUsage();
  finish();
  res.end();
}

// ══════════════ Places API (New) 代理（需登入 + 限流 + 端點白名單）══════════════
// 搬到後端的兩個理由：金鑰不進瀏覽器，以及後端才數得到實際請求次數。
// SKU 由 endpoint + Field Mask 共同決定（欄位層級不同 → 單價不同），
// 因此兩者都要進 key，不能只用「請求數 × 固定單價」。

const PLACES_ALLOWED = new Set(['searchText', 'searchNearby']);

const placesLimiter = rateLimit({
  windowMs: 10 * 60 * 1000,
  max: 300,
  standardHeaders: true,
  legacyHeaders: false,
  message: { error: 'rate limited' }
});

app.post('/api/places/:method', placesLimiter, requireFirebaseUser, async (req, res) => {
  const method = String(req.params.method || '');
  if (!PLACES_ALLOWED.has(method)) {
    return res.status(403).json({ error: 'method not allowed' });
  }
  if (!GOOGLE_MAPS_SERVER_KEY) {
    // 明確降級：告訴前端「代理沒設定」，讓它可以決定要不要退回舊路徑，
    // 而不是回一個看起來像「查無結果」的空陣列，讓問題被靜默吞掉。
    return res.status(503).json({
      error: 'places proxy unavailable',
      message: '伺服器未設定 GOOGLE_MAPS_SERVER_KEY。'
    });
  }

  const fieldMask = String(req.headers['x-goog-fieldmask'] || '');
  if (!fieldMask) return res.status(400).json({ error: 'field mask required' });

  // run 必須在 fetch 之前取得：網路例外時才有對象可以標記失敗。
  const placesRun = genRuns.getOwnedRun(req.headers['x-run-id'], req.user.uid);
  if (req.headers['x-run-id'] && !placesRun) {
    // 與 Vertex 一致：只記 log，不回 4xx——記帳失敗不可擋住功能
    console.warn('[proxy] places 收到無效的 X-Run-Id，本次不記帳（run 可能已收尾或過期）');
  }
  if (placesRun) genRuns.retain(placesRun);   // 請求結束前不得被刪除

  let upstream;
  try {
    upstream = await fetch(`${PLACES_UPSTREAM}/v1/places:${method}`, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        'X-Goog-Api-Key': GOOGLE_MAPS_SERVER_KEY,
        'X-Goog-FieldMask': fieldMask
      },
      body: JSON.stringify(req.body || {})
    });
  } catch (err) {
    console.error('[proxy] places upstream fetch 失敗：', err && err.message);
    if (placesRun) {
      genRuns.markUpstreamError(placesRun);   // 網路例外也是失敗
      genRuns.release(placesRun).catch(() => {});
    }
    return res.status(502).json({ error: 'upstream fetch failed' });
  }

  // body 也可能在傳輸途中中斷；不 catch 的話會變成未捕捉的 rejection，
  // 而且這個 run 不會被標成失敗。
  let text;
  try {
    text = await upstream.text();
  } catch (err) {
    console.error('[proxy] places 回應讀取中斷：', err && err.message);
    if (placesRun) {
      genRuns.markUpstreamError(placesRun);
      genRuns.release(placesRun).catch(() => {});
    }
    return res.status(502).json({ error: 'upstream body failed' });
  }

  // 只有成功的請求才計費——失敗的請求 Google 多半不收錢，
  // 記進去會讓估算偏高。
  if (placesRun) {
    if (upstream.ok) {
      genRuns.addPlacesCall(placesRun, pricing.placesSkuKey(`places:${method}`, fieldMask));
    } else {
      genRuns.markUpstreamError(placesRun);
    }
    genRuns.release(placesRun).catch(() => {});
  }

  res.status(upstream.status);
  res.set('Content-Type', upstream.headers.get('content-type') || 'application/json');
  return res.send(text);
});

// ══════════════ TDX（client_credentials 留在伺服器；限流；路徑白名單）══════════════

// 只放行前端實際用的兩個唯讀資料端點；兩者在 TDX 上游的 base 不同：
//  - 停車場（basic API）  ：/api/basic/v1/Parking/...
//  - 景點（新版 odata API）：/api/tourism/service/odata/V2/Tourism/Attraction
//    （舊 basic 的 v2/Tourism/ScenicSpot 已退役回 404，2026-07 一併換到新端點）
function tdxUpstreamUrl(rest) {
  if (/^\/v1\/Parking\/OffStreet\/CarPark\/City\/[A-Za-z]+(?:\/|$)?/.test(rest)) {
    return TDX_UPSTREAM + '/api/basic' + rest;
  }
  if (/^\/V2\/Tourism\/Attraction(?:\/|$)?$/.test(rest)) {
    return TDX_UPSTREAM + '/api/tourism/service/odata' + rest;
  }
  return null;
}

const tdxLimiter = rateLimit({
  windowMs: 10 * 60 * 1000,
  max: 60,
  standardHeaders: true,
  legacyHeaders: false,
  message: { error: 'rate limited' }
});

// TDX token 快取 + in-flight 去重（跟前端原本的邏輯相同，搬到伺服器）
let _tdxToken = null;
let _tdxTokenExp = 0;
let _tdxTokenPromise = null;
async function getTdxToken() {
  if (!TDX_APP_ID || !TDX_APP_KEY) return null;
  if (_tdxToken && Date.now() < _tdxTokenExp) return _tdxToken;
  if (_tdxTokenPromise) return _tdxTokenPromise;
  _tdxTokenPromise = (async () => {
    try {
      const r = await fetch(TDX_UPSTREAM + '/auth/realms/TDXConnect/protocol/openid-connect/token', {
        method: 'POST',
        headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
        body: 'grant_type=client_credentials'
          + '&client_id=' + encodeURIComponent(TDX_APP_ID)
          + '&client_secret=' + encodeURIComponent(TDX_APP_KEY)
      });
      if (!r.ok) return null;
      const data = await r.json();
      _tdxToken = data.access_token || null;
      const ttl = Number(data.expires_in) || 86400;
      _tdxTokenExp = Date.now() + Math.max(0, ttl - 60) * 1000;
      return _tdxToken;
    } catch (e) {
      console.warn('[proxy] TDX token 失敗：', e && e.message);
      return null;
    } finally {
      _tdxTokenPromise = null;
    }
  })();
  return _tdxTokenPromise;
}

app.get('/api/tdx/*', tdxLimiter, async (req, res) => {
  const rest = '/' + (req.params[0] || '');
  const upstreamBase = tdxUpstreamUrl(rest);
  if (!upstreamBase) {
    return res.status(403).json({ error: 'path not allowed' });
  }
  const token = await getTdxToken();
  if (!token) return res.status(503).json({ error: 'tdx unavailable' });

  const url = new URL(upstreamBase);
  for (const [k, v] of Object.entries(req.query)) url.searchParams.set(k, String(v));

  try {
    const upstream = await fetch(url, { headers: { Authorization: 'Bearer ' + token } });
    const body = await upstream.text();
    res.status(upstream.status);
    res.set('Content-Type', upstream.headers.get('content-type') || 'application/json');
    res.send(body);
  } catch (err) {
    console.error('[proxy] tdx upstream fetch 失敗：', err && err.message);
    res.status(502).json({ error: 'upstream fetch failed' });
  }
});

// ══════════════ CWA 中央氣象署（金鑰留在伺服器；dataset 白名單；限流＋快取）══════════════

const CWA_API_KEY = (process.env.CWA_API_KEY || '').trim();
const CWA_UPSTREAM = 'https://opendata.cwa.gov.tw';
// 只放行實際使用的資料集：
//  - F-D0047-091：縣市未來一週天氣預報（主來源，12 小時間隔約 7 天，取臺東縣）
//  - F-C0032-001：36 小時縣市天氣預報（一週抓不到時的 fallback）
//  - F-D0047-089：臺東縣鄉鎮逐 12 小時預報（保留備用）
const CWA_ALLOWED_DATASET = /^(?:F-D0047-091|F-C0032-001|F-D0047-089)$/;

const cwaLimiter = rateLimit({
  windowMs: 10 * 60 * 1000,
  max: 60,
  standardHeaders: true,
  legacyHeaders: false,
  message: { error: 'rate limited' }
});

// 天氣資料變動慢：伺服器端快取 10 分鐘，全站共用同一份，省 CWA 配額
const _cwaCache = new Map(); // url → { exp, status, contentType, body }
app.get('/api/cwa/v1/rest/datastore/:dataset', cwaLimiter, async (req, res) => {
  // 金鑰未設時回 200＋標記（不回 503）：瀏覽器對非 2xx 會在 console 印紅色錯誤，
  // 違反前端「F12 零紅字」驗收標準；前端看到沒有 records 就靜默保留原內容。
  if (!CWA_API_KEY) return res.json({ ok: false, error: 'cwa key not set', message: 'CWA 金鑰未設定。' });
  const dataset = String(req.params.dataset || '');
  if (!CWA_ALLOWED_DATASET.test(dataset)) return res.status(403).json({ error: 'dataset not allowed' });

  const url = new URL(CWA_UPSTREAM + '/api/v1/rest/datastore/' + dataset);
  for (const [k, v] of Object.entries(req.query)) {
    if (k !== 'Authorization') url.searchParams.set(k, String(v)); // 金鑰一律用伺服器端的
  }
  url.searchParams.set('Authorization', CWA_API_KEY);

  const cacheKey = url.href;
  const hit = _cwaCache.get(cacheKey);
  if (hit && Date.now() < hit.exp) {
    res.status(hit.status).set('Content-Type', hit.contentType);
    return res.send(hit.body);
  }

  try {
    const upstream = await fetch(url);
    const body = await upstream.text();
    const contentType = upstream.headers.get('content-type') || 'application/json';
    if (upstream.ok) {
      _cwaCache.set(cacheKey, { exp: Date.now() + 10 * 60 * 1000, status: upstream.status, contentType, body });
    }
    res.status(upstream.status).set('Content-Type', contentType).send(body);
  } catch (err) {
    console.error('[proxy] cwa upstream fetch 失敗：', err && err.message);
    res.status(502).json({ error: 'upstream fetch failed' });
  }
});

// 回顧短片渲染 job 端點（需登入 + 每人每小時 5 次 + 序列化渲染）
mountRecapJobs(app, { requireFirebaseUser, rateLimit, ipKeyGenerator });

app.listen(PORT, '127.0.0.1', () => {
  console.log(`[proxy] 代理已啟動 http://127.0.0.1:${PORT}（僅本機；對外請經 nginx /api/）`);
  console.log('[proxy] /api/vertex：需登入 + 20req/10min/IP；/api/tdx：60req/10min/IP；/api/cwa：60req/10min/IP＋10min 快取');
  if (amdLlm.isEnabled()) {
    console.log('[proxy] AI_PROVIDER=amd：文字生成改走 AMD gpt-oss-120b；圖片生成仍走 Vertex');
  } else if (String(process.env.AI_PROVIDER || '').trim().toLowerCase() === 'amd') {
    console.warn('[proxy] AI_PROVIDER=amd 但缺少 AMD_LLM_BASE_URL，文字生成仍走 Vertex');
  }
});
