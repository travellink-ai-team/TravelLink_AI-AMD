const fs = require('fs');
const path = require('path');
const vm = require('vm');
// 全站唯一的營業時間／入場條件解析（前端兩頁也載同一支）。
// export:local 需要它判斷 exterior_only 來調整停留時長。
const WAI_HOURS_MOD = (() => {
  try { return require(path.join(__dirname, '..', 'app', 'business-hours.js')); }
  catch (e) { console.warn('載入 business-hours.js 失敗：', e.message); return null; }
})();
const zlib = require('zlib');
const axios = require('axios');
const admin = require('firebase-admin');
const minimist = require('minimist');

const argv = minimist(process.argv.slice(2));

function loadLocalAppConfig() {
  // 依序找設定檔：WEATHER_ENV_PATH → app/weather.env.js（整理後現址）→ 舊 root 位置（相容）。
  const candidates = [
    process.env.WEATHER_ENV_PATH,
    path.resolve(__dirname, '..', 'app', 'weather.env.js'),
    path.resolve(__dirname, '..', 'weather.env.js')
  ].filter(Boolean);
  const configPath = candidates.find((p) => fs.existsSync(p));
  if (!configPath) return {};

  try {
    const sandbox = { window: {} };
    vm.runInNewContext(fs.readFileSync(configPath, 'utf8'), sandbox, {
      filename: configPath,
      timeout: 1000
    });
    return sandbox.window.TRAVEL_APP_CONFIG || {};
  } catch (e) {
    console.warn('Unable to load weather.env.js config:', e.message);
    return {};
  }
}

const LOCAL_APP_CONFIG = loadLocalAppConfig();
const DRY = !!(argv['dry-run'] || argv.dry);
const IMPORT_MODE = !!(argv.import || argv.mode === 'import');
const EXPORT_LOCAL_MODE = !!(argv['export-local'] || argv.export || argv.mode === 'export-local');
const VERIFY_PLACES_MODE = !!(argv['verify-places'] || argv.mode === 'verify-places');
const ENRICH_FEES_MODE = !!(argv['enrich-fees'] || argv.mode === 'enrich-fees');
const CRAWL_FOOD_MODE = !!(argv['crawl-food'] || argv.mode === 'crawl-food');
const CRAWL_PARKING_MODE = !!(argv['crawl-parking'] || argv.mode === 'crawl-parking');
const CLEANUP_COLLAB_MODE = !!(argv['cleanup-collab'] || argv.mode === 'cleanup-collab');
const CLEANUP_DAYS = parseInt(argv.days || process.env.CLEANUP_COLLAB_DAYS || '7', 10);
const FORCE = !!argv.force;
const VERIFY_NEAR_METERS = parseInt(process.env.VERIFY_NEAR_METERS || '5000', 10);
const EXPORT_LOCAL_PATH = process.env.EXPORT_LOCAL_PATH || path.resolve(__dirname, '..', 'app', 'poi-data.js');
const RESTAURANT_DATA_PATH = process.env.RESTAURANT_DATA_PATH || path.resolve(__dirname, '..', 'app', 'restaurant-data.js');
const PARKING_DATA_PATH = process.env.PARKING_DATA_PATH || path.resolve(__dirname, '..', 'app', 'parking-data.js');
const PARKING_COLLECTION = process.env.PARKING_COLLECTION || 'parking_lots';
// 臺東縣政府公有及民營路外停車場（政府資料開放平臺 dataset 165292）。無經緯度，crawler 端一次性地理編碼。
const TAITUNG_PARKING_XLSX_URL = process.env.TAITUNG_PARKING_XLSX_URL
  || 'https://ttone.taitung.gov.tw/download?id=OSQVpF5NQ2Aq4%2F7%2FTFR90g%3D%3D';
const MIN_FOOD_POIS = parseInt(process.env.MIN_FOOD_POIS || '3', 10);
const FOOD_PER_DEST = parseInt(process.env.FOOD_PER_DEST || '25', 10);
const LIMIT = parseInt(argv.limit || process.env.CRAWL_LIMIT || '50', 10);
const IMPORT_LIMIT = parseInt(argv.importLimit || process.env.IMPORT_LIMIT || '500', 10);
const FETCH_LIMIT = parseInt(argv.fetchLimit || process.env.CRAWL_FETCH_LIMIT || String(LIMIT * 5), 10);
// 「免費額度用完前停止」：單次執行最多打幾次 Google API（geocode / Places searchText）。0 = 不限。
const MAX_CALLS = parseInt(argv['max-calls'] || process.env.CRAWL_MAX_CALLS || '0', 10);
// 「平均分散在一週」：把工作切成 N 份只跑第 K 份，形如 --slice 3/7（K 從 1 起算）。空 = 不切。
const SLICE_RAW = String(argv.slice || process.env.CRAWL_SLICE || '').trim();
const POI_COLLECTION = process.env.POI_COLLECTION || 'scenic_points';
const GOOGLE_KEY = process.env.GOOGLE_MAPS_API_KEY || LOCAL_APP_CONFIG.GOOGLE_MAPS_API_KEY || null;
if (GOOGLE_KEY) {
  const keySource = process.env.GOOGLE_MAPS_API_KEY ? 'env GOOGLE_MAPS_API_KEY' : 'weather.env.js GOOGLE_MAPS_API_KEY';
  console.log(`Google Maps key loaded from: ${keySource} (${GOOGLE_KEY.slice(0, 8)}...)`);
} else {
  console.warn('GOOGLE_MAPS_API_KEY 未設定，廁所搜尋將略過。');
}
const OPENDATA_URL = process.env.OPENDATA_SOURCE_URL || 'https://media.taiwan.net.tw/XMLReleaseALL_public/scenic_spot_C_f.json';
// TDX 觀光資訊 API（門票費用來源，沿用前端同一組金鑰）。
const TDX_APP_ID = process.env.TDX_APP_ID || LOCAL_APP_CONFIG.TDX_APP_ID || null;
const TDX_APP_KEY = process.env.TDX_APP_KEY || LOCAL_APP_CONFIG.TDX_APP_KEY || null;
const TDX_AUTH_URL = 'https://tdx.transportdata.tw/auth/realms/TDXConnect/protocol/openid-connect/token';
const TDX_TOURISM_BASE = 'https://tdx.transportdata.tw/api/tourism/service/odata/V2/Tourism';
const CRAWL_REGION = argv.region || argv.city || process.env.CRAWL_REGION || process.env.CRAWL_CITY || '\u53f0\u6771\u7e23';
const MAX_NEARBY_TOILET_DISTANCE_METERS = 500;
const REGION_ALIASES = {
  '\u53f0\u6771': ['\u53f0\u6771', '\u81fa\u6771', '\u53f0\u6771\u7e23', '\u81fa\u6771\u7e23', '\u7da0\u5cf6', '\u862d\u5dbc'],
  '\u81fa\u6771': ['\u53f0\u6771', '\u81fa\u6771', '\u53f0\u6771\u7e23', '\u81fa\u6771\u7e23', '\u7da0\u5cf6', '\u862d\u5dbc'],
  '\u53f0\u6771\u7e23': ['\u53f0\u6771', '\u81fa\u6771', '\u53f0\u6771\u7e23', '\u81fa\u6771\u7e23', '\u7da0\u5cf6', '\u862d\u5dbc'],
  '\u81fa\u6771\u7e23': ['\u53f0\u6771', '\u81fa\u6771', '\u53f0\u6771\u7e23', '\u81fa\u6771\u7e23', '\u7da0\u5cf6', '\u862d\u5dbc']
};

// === 額度守門 + 一週分散切片 ===
let apiCallsUsed = 0;
function noteApiCall(n) { apiCallsUsed += (n || 1); }
function callBudgetExhausted() { return MAX_CALLS > 0 && apiCallsUsed >= MAX_CALLS; }
function parseSlice(raw) {
  const m = /^(\d+)\s*\/\s*(\d+)$/.exec(raw || '');
  if (!m) return null;
  const idx = parseInt(m[1], 10);
  const total = parseInt(m[2], 10);
  if (!(total > 0) || idx < 1 || idx > total) {
    console.warn(`--slice 格式無效（${raw}），需為 K/N 且 1 ≤ K ≤ N，本次忽略切片。`);
    return null;
  }
  return { idx: idx - 1, total }; // idx 轉為 0 起算
}
function inSlice(i, slice) { return !slice || (i % slice.total) === slice.idx; }
const WORK_SLICE = parseSlice(SLICE_RAW);
if (WORK_SLICE) console.log(`Slice 模式：只處理第 ${WORK_SLICE.idx + 1}/${WORK_SLICE.total} 份（依文件順序均分）。`);
if (MAX_CALLS > 0) console.log(`API 預算：本次最多 ${MAX_CALLS} 次 Google 呼叫，達上限即停止。`);

// 讀取既有的自動產生檔（window.<globalName> = {...}），給「部分更新」時合併用；失敗則回傳 {}。
function loadExistingGlobalFile(filePath, globalName) {
  try {
    if (!fs.existsSync(filePath)) return {};
    const sandbox = { window: {} };
    vm.runInNewContext(fs.readFileSync(filePath, 'utf8'), sandbox, { filename: filePath, timeout: 2000 });
    const obj = sandbox.window[globalName];
    return (obj && typeof obj === 'object') ? obj : {};
  } catch (e) {
    console.warn(`讀取既有 ${path.basename(filePath)} 失敗，改以全新檔案產生：`, e.message);
    return {};
  }
}

function initFirebase() {
  if (!admin.apps.length) {
    const saPath = process.env.FIREBASE_SERVICE_ACCOUNT_PATH;
    const saJson = process.env.FIREBASE_SERVICE_ACCOUNT_JSON;
    let credential;
    if (saJson) {
      credential = admin.credential.cert(JSON.parse(saJson));
    } else if (saPath && fs.existsSync(saPath)) {
      credential = admin.credential.cert(require(saPath));
    } else {
      console.error('Missing Firebase service account. Set FIREBASE_SERVICE_ACCOUNT_PATH or FIREBASE_SERVICE_ACCOUNT_JSON.');
      process.exit(1);
    }
    admin.initializeApp({ credential });
  }
  return admin.firestore();
}

// 回傳 { status, result }：status 用來區分「真的查不到(ZERO_RESULTS)」與
// 「金鑰/額度暫時性失敗(REQUEST_DENIED / OVER_QUERY_LIMIT…)」——後者不可
// 當成查不到而降級改用較模糊的查詢（會拿到爛座標），應整筆跳過待下輪重試。
async function geocodeAddressWithStatus(address) {
  if (!GOOGLE_KEY) return { status: 'NO_KEY', result: null };
  try {
    const url = `https://maps.googleapis.com/maps/api/geocode/json?address=${encodeURIComponent(address)}&key=${GOOGLE_KEY}`;
    noteApiCall();
    const r = await axios.get(url, { timeout: 15000 });
    const status = (r.data && r.data.status) || 'UNKNOWN_ERROR';
    const result = (r.data && r.data.results && r.data.results.length) ? r.data.results[0] : null;
    return { status, result };
  } catch (e) {
    console.error('geocode error', e.message);
    return { status: 'NETWORK_ERROR', result: null };
  }
}
function isGeocodeTransient(status) {
  return status === 'REQUEST_DENIED' || status === 'OVER_QUERY_LIMIT'
    || status === 'UNKNOWN_ERROR' || status === 'NETWORK_ERROR';
}
async function geocodeAddress(address) {
  return (await geocodeAddressWithStatus(address)).result;
}

// ── 極簡零依賴 XLSX 讀取（zip + XML）──
// 不用 npm `xlsx`：該套件目前 npm 上最新版（0.18.5）有未修的 prototype-pollution／ReDoS
// 已知漏洞（SheetJS 把後續修補移到自家 CDN，未再發布到 npm）。xlsx 本質就是標準（未加密）
// zip 內含 XML，這裡直接用 Node 內建 zlib 手動解 zip 中央目錄＋raw deflate，只做讀取，
// 足夠應付本專案唯一的用途（讀政府開放資料 XLSX）。
function readZipEntries(buf) {
  const eocdSig = 0x06054b50;
  let eocdOff = -1;
  for (let i = buf.length - 22; i >= 0; i--) {
    if (buf.readUInt32LE(i) === eocdSig) { eocdOff = i; break; }
  }
  if (eocdOff < 0) throw new Error('EOCD not found — 不是有效的 zip/xlsx');
  const cdCount = buf.readUInt16LE(eocdOff + 10);
  const cdOffset = buf.readUInt32LE(eocdOff + 16);
  const entries = new Map();
  let p = cdOffset;
  for (let i = 0; i < cdCount; i++) {
    if (buf.readUInt32LE(p) !== 0x02014b50) throw new Error('壞的 zip 中央目錄項目');
    const method = buf.readUInt16LE(p + 10);
    const compSize = buf.readUInt32LE(p + 20);
    const nameLen = buf.readUInt16LE(p + 28);
    const extraLen = buf.readUInt16LE(p + 30);
    const commentLen = buf.readUInt16LE(p + 32);
    const localHeaderOffset = buf.readUInt32LE(p + 42);
    const name = buf.toString('utf8', p + 46, p + 46 + nameLen);
    entries.set(name, { method, compSize, localHeaderOffset });
    p += 46 + nameLen + extraLen + commentLen;
  }
  return entries;
}
function readZipEntry(buf, entries, name) {
  const meta = entries.get(name);
  if (!meta) return null;
  const lp = meta.localHeaderOffset;
  if (buf.readUInt32LE(lp) !== 0x04034b50) throw new Error('壞的 zip 本機檔頭：' + name);
  const nameLen = buf.readUInt16LE(lp + 26);
  const extraLen = buf.readUInt16LE(lp + 28);
  const dataStart = lp + 30 + nameLen + extraLen;
  const data = buf.subarray(dataStart, dataStart + meta.compSize);
  if (meta.method === 0) return data;
  if (meta.method === 8) return zlib.inflateRawSync(data);
  throw new Error(`不支援的 zip 壓縮法 ${meta.method}（${name}）`);
}
function xmlUnescape(s) {
  return String(s)
    .replace(/&lt;/g, '<').replace(/&gt;/g, '>')
    .replace(/&quot;/g, '"').replace(/&apos;/g, "'")
    .replace(/&#(\d+);/g, (_, d) => String.fromCodePoint(parseInt(d, 10)))
    .replace(/&#x([0-9a-fA-F]+);/g, (_, h) => String.fromCodePoint(parseInt(h, 16)))
    .replace(/&amp;/g, '&');
}
function parseSharedStrings(xml) {
  const blocks = xml.match(/<si>[\s\S]*?<\/si>/g) || [];
  return blocks.map((b) => {
    const parts = b.match(/<t[^>]*>([\s\S]*?)<\/t>/g) || [];
    return xmlUnescape(parts.map((p) => p.replace(/<t[^>]*>/, '').replace(/<\/t>/, '')).join(''));
  });
}
function parseSheetRows(xml, sharedStrings) {
  const rowBlocks = xml.match(/<row[^>]*r="(\d+)"[^>]*>([\s\S]*?)<\/row>/g) || [];
  const rows = {};
  for (const rowXml of rowBlocks) {
    const rNumMatch = rowXml.match(/r="(\d+)"/);
    const rNum = rNumMatch ? parseInt(rNumMatch[1], 10) : null;
    if (!rNum) continue;
    const cellRe = /<c r="([A-Z]+)\d+"(?:[^>]*?\st="(\w+)")?[^>]*?(?:\/>|>([\s\S]*?)<\/c>)/g;
    const cells = {};
    let m;
    while ((m = cellRe.exec(rowXml))) {
      const [, col, type, inner] = m;
      if (!inner) { cells[col] = ''; continue; }
      const vMatch = inner.match(/<v>([\s\S]*?)<\/v>/);
      const raw = vMatch ? vMatch[1] : '';
      cells[col] = (type === 's') ? (sharedStrings[parseInt(raw, 10)] || '') : xmlUnescape(raw);
    }
    rows[rNum] = cells;
  }
  return rows;
}
// 依標題列文字比對找出目標工作表（不依賴固定 sheet 順序），回傳 { rows: {rowNum:{colLetter:value}} }
function readXlsxSheetByHeaderMatch(buf, requiredHeaderTexts) {
  const entries = readZipEntries(buf);
  const sstEntry = entries.has('xl/sharedStrings.xml') ? readZipEntry(buf, entries, 'xl/sharedStrings.xml') : null;
  const sharedStrings = sstEntry ? parseSharedStrings(sstEntry.toString('utf8')) : [];
  const sheetFiles = [...entries.keys()].filter((n) => /^xl\/worksheets\/sheet\d+\.xml$/.test(n)).sort();
  for (const sf of sheetFiles) {
    const xml = readZipEntry(buf, entries, sf).toString('utf8');
    const rows = parseSheetRows(xml, sharedStrings);
    const rowNums = Object.keys(rows).map(Number);
    if (!rowNums.length) continue;
    const headerRow = rows[Math.min(...rowNums)];
    const headerVals = new Set(Object.values(headerRow));
    if (requiredHeaderTexts.every((h) => headerVals.has(h))) return { rows };
  }
  return null;
}

// 台東縣府停車場臺東範圍粗篩（拒絕地理編碼明顯落在縣外/查無結果的極端值）
const TAITUNG_BOUNDS = { south: 22.0, north: 23.6, west: 120.7, east: 121.7 };
function isInsideTaitungBounds(lat, lng) {
  return Number.isFinite(lat) && Number.isFinite(lng)
    && lat >= TAITUNG_BOUNDS.south && lat <= TAITUNG_BOUNDS.north
    && lng >= TAITUNG_BOUNDS.west && lng <= TAITUNG_BOUNDS.east;
}

async function crawlParking(db) {
  if (!GOOGLE_KEY) { console.warn('無 GOOGLE_MAPS_API_KEY，停車場地理編碼略過。'); return; }
  console.log('下載臺東縣府停車場 XLSX …', TAITUNG_PARKING_XLSX_URL);
  const res = await axios.get(TAITUNG_PARKING_XLSX_URL, { responseType: 'arraybuffer', timeout: 30000 });
  const buf = Buffer.from(res.data);
  const parsed = readXlsxSheetByHeaderMatch(buf, ['名稱', '地址', '連絡電話', '充電停車位', '大型車', '小型車', '機車格', '備註']);
  if (!parsed) { console.warn('找不到符合欄位的工作表，來源格式可能已變更，略過。'); return; }
  const rowNums = Object.keys(parsed.rows).map(Number).sort((a, b) => a - b);
  const headerRowNum = rowNums[0];
  const dataRows = rowNums.slice(1).map((rn) => parsed.rows[rn]).filter((r) => r.A && r.A.trim());
  console.log(`解析出 ${dataRows.length} 筆停車場（工作表標題列 row${headerRowNum}）`);

  const parseSpotCount = (text) => {
    const m = String(text || '').match(/(\d+)/);
    return m ? parseInt(m[1], 10) : 0;
  };
  const ensureCountyPrefix = (addr) => {
    const a = String(addr || '').trim();
    return /^(台|臺)東/.test(a) ? a : `臺東縣${a}`;
  };

  let scanned = 0, geocoded = 0, skipped = 0, failed = 0, sliced = 0, eligibleIdx = -1;
  for (const row of dataRows) {
    const name = String(row.A).trim();
    const address = String(row.B || '').trim();
    eligibleIdx += 1;
    if (!inSlice(eligibleIdx, WORK_SLICE)) { sliced += 1; continue; }
    if (argv.limit !== undefined && scanned >= LIMIT) break;
    if (callBudgetExhausted()) { console.log('已達本次 API 呼叫預算上限，提前結束。'); break; }
    scanned += 1;

    // docId 含地址：同名不同地址的停車場（未來資料源擴充時可能出現）不可互相覆蓋
    const docId = normalizeText(name + address).slice(0, 300) || `parking-${eligibleIdx}`;
    const docRef = db.collection(PARKING_COLLECTION).doc(docId);
    if (!FORCE) {
      const existing = await docRef.get();
      if (existing.exists && Number.isFinite(existing.data().lat)) { skipped += 1; continue; }
    }

    // 地理編碼查詢順序：
    //  - 地址有門牌（含「號」）→ 地址優先，查不到再退站名（地址較精準）。
    //  - 地址無門牌（如「台東市台11線」「台東火車站周邊區域」整條路/一帶）→ 站名優先，
    //    退地址（站名如「加路蘭遊憩區停車場」反而精準，模糊地址會落在路段中點）。
    //  - 任一查詢遇暫時性失敗（金鑰 DENIED／額度／網路）→ 整筆跳過待下輪重試，
    //    絕不降級改用另一種查詢（會在鑰匙間歇失效時拿到爛座標而不自知）。
    const addrHasNumber = /號/.test(address);
    const queries = addrHasNumber
      ? [['address', ensureCountyPrefix(address)], ['name', `${name} 台東`]]
      : [['name', `${name} 台東`], ['address', ensureCountyPrefix(address)]];
    let loc = null;
    let usedQuery = null;
    let transient = false;
    for (const [kind, q] of queries) {
      const { status, result } = await geocodeAddressWithStatus(q);
      if (isGeocodeTransient(status)) { transient = true; break; }
      const cand = result && result.geometry && result.geometry.location;
      if (cand && isInsideTaitungBounds(cand.lat, cand.lng)) { loc = cand; usedQuery = kind; break; }
    }
    if (transient) {
      console.warn(`✗ ${name}：地理編碼暫時性失敗（金鑰/額度），本輪跳過`);
      failed += 1;
      continue;
    }
    if (!loc) {
      console.warn(`✗ ${name}：地理編碼查無結果或超出台東範圍，略過`);
      failed += 1;
      continue;
    }

    const data = {
      name,
      address,
      phone: String(row.C || '').trim() || null,
      evSpots: parseSpotCount(row.D),
      largeSpots: parseSpotCount(row.E),
      smallSpots: parseSpotCount(row.F),
      motoSpots: parseSpotCount(row.G),
      notes: String(row.H || '').trim() || null,
      lat: loc.lat,
      lng: loc.lng,
      geocodeQuery: usedQuery,
      source: 'taitung_gov_parking',
      geocodedAt: admin.firestore.FieldValue.serverTimestamp()
    };
    console.log(`✓ ${name} → (${loc.lat.toFixed(5)}, ${loc.lng.toFixed(5)})｜小型車位 ${data.smallSpots}｜(${usedQuery})`);
    if (DRY) { geocoded += 1; continue; }
    await docRef.set(data, { merge: true });
    geocoded += 1;
  }
  console.log('Crawl-parking summary', { scanned, geocoded, skipped, failed, sliced, dryRun: DRY });
}

// ── OpenStreetMap 停車場匯入（amenity=parking）────────────────────────────────
// 為什麼要這個來源：縣府 dataset 165292 只有 30 筆、且 23 筆集中在台東市區，
// 但 poi-data.js 有 280 個台東景點——鄉鎮景點幾乎必然查不到停車場，前端只好一路印
// 「停車待確認」。OSM 的 amenity=parking 在台灣鄉間由在地社群長期維護，覆蓋好很多，
// 而且 Overpass API 免金鑰、免費用，符合本專案 local-first 壓 API 成本的設計。
// 依序嘗試多個 Overpass 實例：主站 overpass-api.de 對部分網路環境會直接回 406
// （實測本機就是），單一端點會讓整個匯入靜默失敗。OVERPASS_API_URL 可指定單一端點覆蓋。
const OVERPASS_ENDPOINTS = process.env.OVERPASS_API_URL
  ? [process.env.OVERPASS_API_URL]
  : [
    'https://overpass.kumi.systems/api/interpreter',
    'https://overpass.private.coffee/api/interpreter',
    'https://overpass-api.de/api/interpreter'
  ];
// 已排除的端點（實測）：
//  - overpass.osm.jp：憑證過期（certificate has expired）
//  - overpass.osm.ch：只涵蓋瑞士資料，查台東會回 HTTP 200 + 0 筆 —— 比報錯更危險，
//    會讓匯入「成功但什麼都沒進來」。fetchOverpassElements 因此把 0 筆也當失敗換下一個端點。
// 與既有資料的去重半徑：同一座停車場在兩個來源之間的座標差通常 <40m（政府用門牌地理編碼、
// OSM 用實際範圍中心），取 60m 兼顧「不重複」與「不誤殺隔壁的另一座」。
const OSM_DEDUPE_METERS = parseInt(process.env.OSM_DEDUPE_METERS || '60', 10);

// 台東縣的實際範圍，拆成三塊 bbox：本島 + 綠島 + 蘭嶼。
// 為什麼不用 TAITUNG_BOUNDS 那個大矩形：它是給「地理編碼結果的粗篩」用的，寬鬆沒關係；
// 但拿來當 Overpass 的查詢範圍就會把恆春半島（屏東，lat 22.0–22.1／lng 120.7–120.8）
// 整片撈進來——實測 dry-run 就出現了一批墾丁的停車場被當成台東資料。
// 拆成三塊同時也讓查詢輕很多（少掉大片海域與屏東），公共實例比較不會逾時。
const TAITUNG_OSM_BOXES = [
  { south: 22.30, west: 120.74, north: 23.50, east: 121.70 }, // 本島
  { south: 22.63, west: 121.44, north: 22.72, east: 121.53 }, // 綠島
  { south: 21.90, west: 121.48, north: 22.12, east: 121.65 }  // 蘭嶼
];

function isInsideTaitungCounty(lat, lng) {
  if (!Number.isFinite(lat) || !Number.isFinite(lng)) return false;
  return TAITUNG_OSM_BOXES.some((b) => lat >= b.south && lat <= b.north && lng >= b.west && lng <= b.east);
}

// 首選查詢：用台東縣的行政區界（ISO 3166-2:TW-TTT）精準取值。
// 矩形再怎麼調都會沿著中央山脈那一側多切到高雄／屏東（實測 dry-run 就撈到 lng 120.75
// 一帶、實際在山脈西側的點）。area 查詢較重、公共實例容易逾時，所以保留 bbox 當退路。
function buildOverpassParkingQueryByArea() {
  return `[out:json][timeout:90];
area["ISO3166-2"="TW-TTT"]->.tt;
(
  node["amenity"="parking"](area.tt);
  way["amenity"="parking"](area.tt);
);
out center tags;`;
}

function buildOverpassParkingQuery() {
  const bboxes = TAITUNG_OSM_BOXES.map((b) => `${b.south},${b.west},${b.north},${b.east}`);
  // out center：way 只回幾何中心，不用把整個多邊形拉回來（回應小很多）。
  // 只查 node + way：amenity=parking 的 relation（多邊形群組）在台灣極少，
  // 但把 relation 加進來會讓整個查詢在公共實例上逾時（實測回 504）。
  const clauses = bboxes
    .map((bbox) => `  node["amenity"="parking"](${bbox});\n  way["amenity"="parking"](${bbox});`)
    .join('\n');
  return `[out:json][timeout:90];
(
${clauses}
);
out center tags;`;
}

// 私人／不對外開放的停車場對旅客沒有意義，匯進來只會讓前端挑到停不進去的點
function isPublicOsmParking(tags) {
  const access = String(tags.access || '').toLowerCase();
  if (['private', 'no', 'permit', 'permissive_private'].includes(access)) return false;
  if (String(tags.parking || '').toLowerCase() === 'private') return false;
  return true;
}

function osmParkingName(tags) {
  const name = String(tags['name:zh'] || tags.name || '').trim();
  if (name) return name;
  const operator = String(tags.operator || '').trim();
  if (operator) return `${operator}停車場`;
  // 無名停車場在 OSM 很常見（路邊劃設的小型場）。寧可誠實寫「停車場」，
  // 也不要自己編一個看起來很正式、實際上現場找不到的名字。
  return '停車場';
}

function osmParkingNotes(tags) {
  const parts = [];
  const fee = String(tags.fee || '').toLowerCase();
  if (fee === 'yes') parts.push('收費');
  else if (fee === 'no') parts.push('免費');
  const surface = String(tags.surface || '').toLowerCase();
  if (surface === 'unpaved' || surface === 'ground' || surface === 'gravel') parts.push('未鋪面');
  if (String(tags.covered || '').toLowerCase() === 'yes') parts.push('有遮蔽');
  return parts.length ? parts.join('・') : null;
}

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
// Overpass 公共實例的使用政策：同時只給少量 slot、超量會回 429／504，短時間連打會被暫時擋掉
// （實測就是這樣把自己打到全部端點逾時）。因此：
//  1. 兩次請求之間至少間隔 OVERPASS_MIN_INTERVAL_MS
//  2. 整輪端點都失敗時，指數退避後再重試，而不是立刻重打
const OVERPASS_MIN_INTERVAL_MS = parseInt(process.env.OVERPASS_MIN_INTERVAL_MS || '3000', 10);
const OVERPASS_MAX_ROUNDS = parseInt(process.env.OVERPASS_MAX_ROUNDS || '3', 10);
const OVERPASS_BACKOFF_MS = [15000, 45000, 90000];

// 逐一嘗試端點，每個端點先 GET 再 POST；整輪失敗就退避後重試，全部用完才放棄。
async function fetchOverpassElements(query) {
  const headers = {
    'Accept': 'application/json',
    // Overpass 公共實例要求可辨識的 UA，否則可能被限流
    'User-Agent': 'TravelLinkAI-crawler/1.0 (+https://travel-link-ai.duckdns.org)'
  };
  let lastRequestAt = 0;
  const politeWait = async () => {
    const wait = OVERPASS_MIN_INTERVAL_MS - (Date.now() - lastRequestAt);
    if (wait > 0) await sleep(wait);
    lastRequestAt = Date.now();
  };
  for (let round = 0; round < Math.max(1, OVERPASS_MAX_ROUNDS); round++) {
    if (round > 0) {
      const backoff = OVERPASS_BACKOFF_MS[Math.min(round - 1, OVERPASS_BACKOFF_MS.length - 1)];
      console.log(`Overpass 整輪失敗，${Math.round(backoff / 1000)} 秒後重試（第 ${round + 1}/${OVERPASS_MAX_ROUNDS} 輪）…`);
      await sleep(backoff);
    }
    for (const url of OVERPASS_ENDPOINTS) {
      for (const method of ['get', 'post']) {
        await politeWait();
        try {
          const res = method === 'get'
            // 每次嘗試上限 90s（對齊查詢本身的 [timeout:90]）：公共實例時常逾時或 5xx，
            // 拖太久只是白等，快點換下一個端點比較實際。
            ? await axios.get(url, { params: { data: query }, headers, timeout: 90000 })
            : await axios.post(url, `data=${encodeURIComponent(query)}`,
              { headers: { ...headers, 'Content-Type': 'application/x-www-form-urlencoded' }, timeout: 90000 });
          const elements = Array.isArray(res.data && res.data.elements) ? res.data.elements : [];
          // 0 筆一律當失敗換下一個端點：台東不可能一個停車場都沒有，回 0 代表這個鏡像
          // 根本沒有這個區域的資料（例如只涵蓋單一國家的鏡像）。
          // 若當成成功，匯入會靜默地什麼都不做。
          if (!elements.length) {
            console.warn(`Overpass 回 0 筆（${method.toUpperCase()} ${url}）→ 視為該鏡像無此區域資料，換下一個`);
            continue;
          }
          console.log(`Overpass OK（${method.toUpperCase()} ${url}）`);
          return elements;
        } catch (e) {
          const status = e && e.response ? e.response.status : null;
          console.warn(`Overpass 失敗（${method.toUpperCase()} ${url}）→ ${status || (e && e.message)}`);
          // 429＝明確的限流，這個端點本輪不用再試第二種方法，直接換下一個端點
          if (status === 429) break;
        }
      }
    }
  }
  return null;
}

async function crawlParkingOsm(db) {
  console.log('查詢 OpenStreetMap Overpass（amenity=parking，台東縣行政區）…');
  let elements = await fetchOverpassElements(buildOverpassParkingQueryByArea());
  // 行政區查詢的結果本身就已經是台東縣境內，不可以再套那三塊粗略 bbox——
  // 實測會把 3 個貼著框邊、但確實在縣內的點誤判成 outOfBounds 丟掉。
  let needsBoxFilter = false;
  if (!elements) {
    console.warn('行政區查詢失敗，改用 bbox 查詢（會多切到鄰縣，靠 isInsideTaitungCounty 再篩）…');
    elements = await fetchOverpassElements(buildOverpassParkingQuery());
    needsBoxFilter = true;
  }
  if (!elements) {
    console.warn('所有 Overpass 端點都失敗，OSM 停車場略過（既有資料不受影響）。');
    return;
  }
  console.log(`Overpass 回傳 ${elements.length} 個元素`);

  // 既有資料（縣府 / 社群 / 先前的 OSM）先讀一次，用來做座標去重
  const existing = [];
  const snap = await db.collection(PARKING_COLLECTION).get();
  snap.forEach((doc) => {
    const d = doc.data();
    if (Number.isFinite(d.lat) && Number.isFinite(d.lng)) {
      existing.push({ id: doc.id, lat: d.lat, lng: d.lng, source: d.source || '' });
    }
  });
  console.log(`既有停車場 ${existing.length} 筆，去重半徑 ${OSM_DEDUPE_METERS}m`);

  let scanned = 0, written = 0, dupSkipped = 0, privateSkipped = 0, outOfBounds = 0;
  const acceptedThisRun = [];
  for (const el of elements) {
    const tags = el.tags || {};
    const lat = Number(el.lat != null ? el.lat : (el.center && el.center.lat));
    const lng = Number(el.lon != null ? el.lon : (el.center && el.center.lon));
    if (!Number.isFinite(lat) || !Number.isFinite(lng)) { outOfBounds += 1; continue; }
    // 只有 bbox 退路才需要再篩一次（矩形邊角會切到屏東／花蓮）；行政區查詢的結果已經精準。
    if (needsBoxFilter && !isInsideTaitungCounty(lat, lng)) { outOfBounds += 1; continue; }
    if (!isPublicOsmParking(tags)) { privateSkipped += 1; continue; }
    scanned += 1;
    if (argv.limit !== undefined && written >= LIMIT) break;

    const docId = `osm-${el.type}-${el.id}`;
    const point = { lat, lng };
    // 去重：先比既有資料，再比本輪已接受的（OSM 自己也會有相鄰重複的小場）
    const near = existing.concat(acceptedThisRun)
      .find((p) => p.id !== docId && measureDistanceMeters(point, p) <= OSM_DEDUPE_METERS);
    if (near && !FORCE) { dupSkipped += 1; continue; }

    const capacity = parseInt(tags.capacity, 10);
    const addr = [tags['addr:city'], tags['addr:suburb'], tags['addr:street'], tags['addr:housenumber']]
      .filter(Boolean).join('');
    const data = {
      name: osmParkingName(tags),
      lat,
      lng,
      source: 'osm',
      osmType: el.type,
      osmId: String(el.id),
      updatedAt: admin.firestore.FieldValue.serverTimestamp()
    };
    if (addr) data.address = addr;
    if (Number.isFinite(capacity) && capacity > 0) data.smallSpots = capacity;
    const notes = osmParkingNotes(tags);
    if (notes) data.notes = notes;

    acceptedThisRun.push({ id: docId, lat, lng, source: 'osm' });
    written += 1;
    if (written <= 10) console.log(`✓ ${data.name} → (${lat.toFixed(5)}, ${lng.toFixed(5)})${notes ? `｜${notes}` : ''}`);
    if (DRY) continue;
    await db.collection(PARKING_COLLECTION).doc(docId).set(data, { merge: true });
  }
  console.log('Crawl-parking(OSM) summary', {
    elements: elements.length, scanned, written, dupSkipped, privateSkipped, outOfBounds, dryRun: DRY
  });
}

function measureDistanceMeters(origin, target) {
  if (!origin || !target) return Number.POSITIVE_INFINITY;
  const originLat = Number(origin.lat);
  const originLng = Number(origin.lng);
  const targetLat = Number(target.lat);
  const targetLng = Number(target.lng);
  if (![originLat, originLng, targetLat, targetLng].every(Number.isFinite)) {
    return Number.POSITIVE_INFINITY;
  }
  const toRadians = (degrees) => degrees * Math.PI / 180;
  const earthRadius = 6371000;
  const dLat = toRadians(targetLat - originLat);
  const dLng = toRadians(targetLng - originLng);
  const lat1 = toRadians(originLat);
  const lat2 = toRadians(targetLat);
  const a = Math.sin(dLat / 2) ** 2
    + Math.cos(lat1) * Math.cos(lat2) * Math.sin(dLng / 2) ** 2;
  return earthRadius * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
}

async function fetchNearbyToiletsGoogle(lat, lng) {
  if (!GOOGLE_KEY) return [];
  try {
    // 使用新版 Places API (v1)，避免 legacy nearbysearch 被拒
    noteApiCall();
    const r = await axios.post(
      'https://places.googleapis.com/v1/places:searchText',
      {
        textQuery: '公廁',
        locationBias: {
          circle: {
            center: { latitude: lat, longitude: lng },
            radius: MAX_NEARBY_TOILET_DISTANCE_METERS
          }
        },
        languageCode: 'zh-TW',
        maxResultCount: 5
      },
      {
        headers: {
          'Content-Type': 'application/json',
          'X-Goog-Api-Key': GOOGLE_KEY,
          'X-Goog-FieldMask': 'places.displayName,places.location,places.formattedAddress,places.id'
        },
        timeout: 15000
      }
    );
    const places = Array.isArray(r.data && r.data.places) ? r.data.places : [];
    const seenIds = new Set();
    const results = [];
    for (const place of places) {
      if (!place.location) continue;
      const pos = { lat: place.location.latitude, lng: place.location.longitude };
      if (measureDistanceMeters({ lat, lng }, pos) > MAX_NEARBY_TOILET_DISTANCE_METERS) continue;
      const id = place.id || `${pos.lat}|${pos.lng}`;
      if (seenIds.has(id)) continue;
      seenIds.add(id);
      results.push({
        name: (place.displayName && place.displayName.text) || '公廁',
        lat: pos.lat,
        lng: pos.lng,
        address: place.formattedAddress || '',
        source: 'google',
        confidence: 'verified'
      });
      if (results.length >= 3) break;
    }
    return results;
  } catch (e) {
    if (e.response && e.response.status === 403) {
      console.warn('Google Places API 403：請至 Google Cloud Console 啟用「Places API (New)」，目前跳過 Google 廁所搜尋。');
    } else {
      console.warn('Google Places toilet fetch error:', e.message);
    }
    return [];
  }
}

async function fetchNearbyToilets(lat, lng) {
  if (!lat || !lng || !GOOGLE_KEY) return [];
  return fetchNearbyToiletsGoogle(lat, lng);
}

function normalizeText(value) {
  return String(value || '')
    .trim()
    .toLowerCase()
    .replace(/\s+/g, '')
    .replace(/[()（）「」『』【】\[\],，.。·・-]/g, '');
}

// === Places 名稱嚴格比對（與前端 ai-travel-explore-final.js 同款，避免綁到同名異地）===
const GENERIC_PLACE_SUFFIXES = [
  '觀光漁港', '漁港', '港口', '碼頭', '港',
  '火車站', '高鐵站', '捷運站', '轉運站', '客運站', '車站', '站',
  '國家公園', '森林公園', '地質公園', '公園',
  '國家風景區', '風景區', '遊客中心', '文化園區', '園區',
  '部落', '老街', '夜市', '步道', '古道', '大橋', '吊橋', '橋',
  '溫泉', '瀑布', '農場', '牧場', '林場',
  '博物館', '美術館', '紀念館', '故事館', '展覽館',
  '寺', '宮', '廟', '教堂', '神社'
];
function stripGenericPlaceSuffix(s) {
  let t = String(s || '');
  for (const suf of GENERIC_PLACE_SUFFIXES) {
    if (t.length > suf.length && t.endsWith(suf)) return t.slice(0, -suf.length);
  }
  return t;
}
function placeNameMatchesQuery(displayName, queryName) {
  if (!displayName || !queryName) return true;
  const clean = (s) => String(s).replace(/[\s（）()[\]「」·\-_\/,.。，、！!？?～~]/g, '').toLowerCase();
  const dn = clean(displayName);
  const qn = clean(queryName);
  if (!dn || !qn) return true;
  if (dn.includes(qn) || qn.includes(dn)) return true;
  const dCore = clean(stripGenericPlaceSuffix(displayName));
  const qCore = clean(stripGenericPlaceSuffix(queryName));
  if (dCore && qCore) {
    if (dCore.includes(qCore) || qCore.includes(dCore)) return true;
    if (dCore.length >= 2 && qCore.length >= 2 && dCore.slice(0, 2) === qCore.slice(0, 2)) return true;
  }
  return false;
}

function readNested(source, paths) {
  for (const pathSpec of paths) {
    const value = pathSpec.split('.').reduce((acc, key) => (acc && acc[key] !== undefined ? acc[key] : undefined), source);
    if (value !== undefined && value !== null && value !== '') return value;
  }
  return '';
}

function textMatchesRegion(value, region) {
  const normalizedValue = normalizeText(value);
  const normalizedRegion = normalizeText(region);
  if (!normalizedRegion) return true;
  if (!normalizedValue) return false;
  const aliases = REGION_ALIASES[region] || REGION_ALIASES[normalizedRegion] || [region];
  return aliases.some((alias) => {
    const normalizedAlias = normalizeText(alias);
    return normalizedValue.includes(normalizedAlias) || normalizedAlias.includes(normalizedValue);
  });
}

function toNumber(value) {
  const num = Number(value);
  return Number.isFinite(num) ? num : null;
}

function normalizeOpenDataSpot(raw) {
  const name = readNested(raw, ['AttractionName', 'Name', 'name']);
  const lat = toNumber(readNested(raw, ['PositionLat', 'Position.PositionLat', 'Py', 'Latitude', 'lat']));
  const lng = toNumber(readNested(raw, ['PositionLon', 'Position.PositionLon', 'Px', 'Longitude', 'lng']));
  if (!name || lat === null || lng === null) return null;

  const city = readNested(raw, ['PostalAddress.City', 'PostalAddress_City', 'Region', 'City']);
  const town = readNested(raw, ['PostalAddress.Town', 'PostalAddress_Town', 'Town']);
  const address = readNested(raw, ['PostalAddress.Address', 'PostalAddress', 'Add', 'Address']);

  return {
    id: readNested(raw, ['AttractionID', 'Id', 'ID']),
    name,
    normalizedName: normalizeText(name),
    lat,
    lng,
    city,
    town,
    address,
    description: readNested(raw, ['Description', 'Toldescribe', 'DescriptionDetail', 'Remarks']),
    openTime: readNested(raw, ['OpenTime', 'Opentime']),
    phone: readNested(raw, ['Phone', 'Tel']),
    website: readNested(raw, ['WebsiteUrl', 'Website']),
    updateTime: readNested(raw, ['UpdateTime'])
  };
}

function extractOpenDataRows(payload) {
  if (Array.isArray(payload)) return payload;
  if (Array.isArray(payload?.XML_Head?.Infos?.Info)) return payload.XML_Head.Infos.Info;
  if (Array.isArray(payload?.Infos?.Info)) return payload.Infos.Info;
  if (Array.isArray(payload?.data)) return payload.data;
  if (Array.isArray(payload?.items)) return payload.items;
  return [];
}

async function fetchOfficialScenicSpots() {
  try {
    const response = await axios.get(OPENDATA_URL, { timeout: 30000 });
    const rows = extractOpenDataRows(response.data);
    const spots = rows.map(normalizeOpenDataSpot).filter(Boolean);
    console.log('Loaded official OpenData scenic spots', spots.length);
    return spots;
  } catch (e) {
    // 2026-09 實測：media.taiwan.net.tw 的 scenic_spot_C_f.json 回 404，
    // 觀光署已把這份資料移到需要授權的 TDX。原本這裡吞掉錯誤回 []，
    // 於是 processImport 只印「No official OpenData spots matched」，
    // 看起來像「這個地區沒有資料」而不是「來源掛了」——排查會走錯方向。
    console.error('全國觀光 OpenData 取得失敗：', OPENDATA_URL, '→', e.message);
    if (e.response && e.response.status === 404) {
      console.error('  這支來源已失效（觀光署資料改走需授權的 TDX）。');
      console.error('  台東請改用：npm run import -- --source=taitung');
    }
    return null;   // null = 來源不可用；[] = 來源可用但沒資料。兩者必須分得開
  }
}

// ── 台東觀光旅遊網 opendata：景點來源 ────────────────────────────
// 全國來源退役後，台東改以這支為主。相較之下它的優勢是：
//   · 100% 有地址（含鄉鎮）→ 行政區不必用座標猜
//   · 100% 有座標與圖片
//   · opentimeGoogle 是 Google 的七行格式，正好是 business-hours.js 吃的格式
// 需帶 X-Requested-With: XMLHttpRequest，否則會 302 轉走（與 buildTaitungFeeMap 同）。
const TAITUNG_TOWNS = ['臺東市', '台東市', '成功鎮', '關山鎮', '長濱鄉', '海端鄉', '池上鄉',
  '東河鄉', '鹿野鄉', '延平鄉', '卑南鄉', '金峰鄉', '太麻里鄉', '大武鄉', '達仁鄉', '綠島鄉', '蘭嶼鄉'];

function taitungTownOf(address) {
  const a = String(address || '').replace(/臺/g, '台');
  const hit = TAITUNG_TOWNS.find((t) => a.includes(t.replace(/臺/g, '台')));
  return hit ? hit.replace(/臺/g, '台') : null;
}

// 圖片是相對路徑（'~/image/52811/480x360'），要補成絕對網址才能用
function taitungAbsoluteUrl(raw) {
  const s = String(raw || '').trim();
  if (!s) return null;
  if (/^https?:\/\//i.test(s)) return s;
  return 'https://tour.taitung.gov.tw/' + s.replace(/^~?\//, '');
}

function normalizeTaitungOdSpot(raw) {
  const name = String(raw && raw.name || '').trim();
  if (!name) return null;
  const parts = String(raw.latlng || '').split(',');
  const lat = toNumber(parts[0]);
  const lng = toNumber(parts[1]);
  if (lat === null || lng === null) return null;

  // opentimeGoogle 是七行 Google 格式，遠比 opentime 的自由文字（「全天候開放」）好解析
  const gHours = Array.isArray(raw.opentimeGoogle) ? raw.opentimeGoogle.filter(Boolean) : [];
  const openTime = gHours.length
    ? gHours.join('\n').replace(/：/g, ': ')     // 全形冒號→半形，對齊 Places 的寫法
    : String(raw.opentime || '').trim();

  return {
    id: raw.pid || (raw.id != null ? String(raw.id) : null),
    name,
    normalizedName: normalizeText(name),
    lat,
    lng,
    city: '台東縣',
    town: taitungTownOf(raw.address),
    address: String(raw.address || '').replace(/臺/g, '台').trim(),
    description: '',                              // 這支 opendata 沒有敘述欄位
    openTime,
    phone: String(raw.tel || '').trim(),
    website: taitungAbsoluteUrl(raw.url),
    photoUrl: taitungAbsoluteUrl(raw.img),
    ticketText: String(raw.ticket || '').trim(),
    updateTime: null
  };
}

async function fetchTaitungScenicSpots() {
  console.log('台東觀光網 opendata 抓取景點 …');
  const r = await axios.get(TAITUNG_OD_ATTRACTIONS_URL, {
    headers: {
      'X-Requested-With': 'XMLHttpRequest',
      'Accept': 'application/json, text/plain, */*',
      'Referer': 'https://tour.taitung.gov.tw/zh-tw/attraction',
      'User-Agent': 'Mozilla/5.0'
    },
    timeout: 30000,
    transformResponse: [(d) => d]        // 保留原字串，自行去 BOM 再解析
  });
  const payload = JSON.parse(String(r.data).replace(/^﻿/, ''));
  const rows = extractOpenDataRows(payload);
  const spots = rows.map(normalizeTaitungOdSpot).filter(Boolean);
  const noTown = spots.filter((s) => !s.town).length;
  console.log('台東觀光網景點', spots.length, '筆（無法判定鄉鎮：' + noTown + '）');
  return spots;
}

function findOfficialSpot(name, region, spots) {
  const normalizedName = normalizeText(name);
  const normalizedRegion = normalizeText(region);
  if (!normalizedName || !spots.length) return null;

  const exact = spots.find((spot) => spot.normalizedName === normalizedName);
  if (exact) return { spot: exact, score: 100, reason: 'exact_name' };

  const candidates = spots
    .map((spot) => {
      const regionText = normalizeText([spot.city, spot.town, spot.address].filter(Boolean).join(''));
      let score = 0;
      if (spot.normalizedName.includes(normalizedName) || normalizedName.includes(spot.normalizedName)) score += 75;
      if (normalizedRegion && regionText.includes(normalizedRegion)) score += 20;
      return { spot, score };
    })
    .filter((candidate) => candidate.score >= 75)
    .sort((a, b) => b.score - a.score);

  if (!candidates.length) return null;
  return { ...candidates[0], reason: candidates[0].score >= 95 ? 'name_region' : 'partial_name' };
}

function spotMatchesRegion(spot, region) {
  if (!region) return true;
  const regionText = [spot.city, spot.town, spot.address].filter(Boolean).join('');
  return textMatchesRegion(regionText, region);
}

function docMatchesRegion(data, region) {
  if (!region) return true;
  const regionText = [
    data.region,
    data.city,
    data.county,
    data.destination,
    data.formatted_address,
    data.address,
    data.name,
    data.tripTitle,
    data.id
  ].filter(Boolean).join('');
  return textMatchesRegion(regionText, region);
}

function applyLocationUpdate(update, lat, lng, address) {
  update['scenicCoordinates.lat'] = lat;
  update['scenicCoordinates.lng'] = lng;
  update.lat = lat;
  update.lng = lng;
  if (address) update.formatted_address = address;
}

function normalizeDocKey(text) {
  return String(text || '')
    .trim()
    .toLowerCase()
    .replace(/[^\p{L}\p{N}]+/gu, '');
}

function makeDocId(spot) {
  const name = String(spot.name || '').trim();
  const region = String(spot.town || spot.city || CRAWL_REGION || '').trim();

  if (!name) {
    // Fallback: generate from coordinates
    if (spot.lat && spot.lng) {
      return `spot_${spot.lat.toFixed(4)}_${spot.lng.toFixed(4)}`;
    }
    return null;
  }

  // Strip trailing region from name (matches buildScenicPointKey in ai-travel-planner-v8.html)
  let baseName = name;
  if (region && baseName.endsWith(region) && baseName.length > region.length) {
    baseName = baseName.slice(0, -region.length).trim();
  }

  const key = normalizeDocKey(baseName + region);
  if (key) return key;

  // Fallback: coordinates
  if (spot.lat && spot.lng) {
    return `spot_${spot.lat.toFixed(4)}_${spot.lng.toFixed(4)}`;
  }

  return null;
}

async function buildImportedScenicPoint(spot, sourceLabel) {
  const SRC = sourceLabel || 'official_opendata';
  const region = spot.town || spot.city || CRAWL_REGION;
  const docId = makeDocId(spot);
  
  if (!docId) {
    console.warn('Unable to generate docId for spot:', {
      name: spot.name,
      city: spot.city,
      town: spot.town,
      id: spot.id,
      lat: spot.lat,
      lng: spot.lng,
      address: spot.address
    });
    return null;
  }
  
  // Fetch nearby toilets
  let nearbyToiletLocations = [];
  // 乾跑不打 Google API：一次匯入 265 筆就是 265 次付費呼叫，
  // 而 --dry-run 的用途是「看要寫什麼」，不需要真的去查廁所。
  if (GOOGLE_KEY && !DRY && spot.lat && spot.lng) {
    nearbyToiletLocations = await fetchNearbyToilets(spot.lat, spot.lng);
    if (nearbyToiletLocations.length > 0) {
      console.log(`Found ${nearbyToiletLocations.length} nearby toilets for ${spot.name}`);
    }
    // Add delay to avoid rate limiting
    await new Promise((r) => setTimeout(r, 250));
  }
  
  const data = {
    id: docId,
    name: spot.name,
    // desc / notice 不在這裡寫死空字串——寫入是 merge:true，空字串會蓋掉既有內容。
    // 台東觀光網這支 opendata 沒有敘述欄位，照寫就等於清空 164 筆既有敘述（實測）。
    // 有值才寫，見下方。
    region,
    city: spot.city || '\u53f0\u6771\u7e23',
    county: spot.city || '\u53f0\u6771\u7e23',
    scenicCoordinates: {
      lat: spot.lat,
      lng: spot.lng
    },
    lat: spot.lat,
    lng: spot.lng,
    formatted_address: spot.address || [spot.city, spot.town].filter(Boolean).join(''),
    tripTitle: `${region} 1\u5929\u5fae\u65c5\u884c`,
    source: SRC,
    crawl_source: SRC,
    crawl_confidence: 100,
    official_opendata_id: spot.id || null,
    official_opendata_name: spot.name,
    official_opendata_match: 'import',
    updatedAt: admin.firestore.FieldValue.serverTimestamp(),
    last_crawled: admin.firestore.FieldValue.serverTimestamp(),
    needsCrawl: false
  };

  // ★ 只在「真的查到」時才寫廁所欄位。
  //   原本無條件寫 nearbyToiletLocations: []，而寫入是 merge:true——
  //   只要 Places 查詢失敗（例如金鑰是瀏覽器限定的、伺服器端回 403），
  //   fetchNearbyToilets 回 []，就會把既有的廁所資料整個蓋掉。
  //   實測：一次匯入清掉了 38 筆中的 32 筆。查不到就別動這個欄位。
  if (nearbyToiletLocations.length) {
    data.nearbyToiletLocations = nearbyToiletLocations;
    data.toiletCoordinatesVerifiedAt = admin.firestore.FieldValue.serverTimestamp();
  }

  if (spot.description) data.desc = spot.description;
  if (spot.openTime) data.openTime = spot.openTime;
  if (spot.phone) data.phone = spot.phone;
  if (spot.website) data.website = spot.website;
  if (spot.updateTime) data.official_opendata_updated_at = spot.updateTime;
  // 圖片先存進 Firestore，但「不」由 export:local 帶進 app/poi-data.js——
  // 政府 opendata 的資料本身通常開放，圖片授權不一定，要先確認 tour.taitung.gov.tw
  // 的使用條款。確認可用後，在 toLocalPoi 加一行 photoUrl 即可接上前端。
  if (spot.photoUrl) {
    data.photoUrl = spot.photoUrl;
    data.photoSource = SRC;
  }
  // 門票原文；實際的 fee/feeNote 仍由 enrich-fees 解析後寫入，這裡只留原始字串備查
  if (spot.ticketText) data.ticketText = spot.ticketText;

  return { docId, data };
}

function scoreMatch(name, geocodeResult, regionCenter) {
  if (!geocodeResult) return 0;
  const formatted = (geocodeResult.formatted_address || '').toLowerCase();
  const nameLower = (name || '').toLowerCase();
  let score = 50;
  if (formatted.includes(nameLower)) score += 40;
  if (geocodeResult.types && geocodeResult.types.includes('establishment')) score += 10;
  if (regionCenter && geocodeResult.geometry) {
    const lat = geocodeResult.geometry.location.lat;
    const lng = geocodeResult.geometry.location.lng;
    const R = 6371000;
    const toRad = (d) => (d * Math.PI) / 180;
    const dlat = toRad(lat - regionCenter.lat);
    const dlng = toRad(lng - regionCenter.lng);
    const a = Math.sin(dlat / 2) * Math.sin(dlat / 2) + Math.cos(toRad(lat)) * Math.cos(toRad(regionCenter.lat)) * Math.sin(dlng / 2) * Math.sin(dlng / 2);
    const c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    const dist = R * c;
    if (dist < 2000) score += 10;
    else if (dist < 10000) score += 5;
    if (dist > 50000) score -= 30;
  }
  return Math.max(0, score);
}

async function processBatch(db) {
  console.log('Using Firestore collection', POI_COLLECTION);
  const allOfficialSpots = await fetchOfficialScenicSpots();
  const officialSpots = CRAWL_REGION ? allOfficialSpots.filter((spot) => spotMatchesRegion(spot, CRAWL_REGION)) : allOfficialSpots;
  if (CRAWL_REGION) {
    console.log('Region filter enabled', CRAWL_REGION, 'official spots', officialSpots.length);
  }
  const q = db.collection(POI_COLLECTION).where('needsCrawl', '==', true).limit(CRAWL_REGION ? FETCH_LIMIT : LIMIT);
  const snap = await q.get();
  if (snap.empty) {
    console.log(`No docs needing crawl (set needsCrawl=true on ${POI_COLLECTION} docs).`);
    return;
  }
  let processed = 0;
  let skippedByRegion = 0;
  let eligibleIdx = -1;
  let slicedCount = 0;
  for (const doc of snap.docs) {
    const data = doc.data();
    if (!docMatchesRegion(data, CRAWL_REGION)) {
      skippedByRegion += 1;
      console.log('Skipping', doc.id, 'outside region filter', CRAWL_REGION);
      continue;
    }
    eligibleIdx += 1; // 分片 / 預算守門（geocode 補齊也算 Google 呼叫）
    if (!inSlice(eligibleIdx, WORK_SLICE)) { slicedCount += 1; continue; }
    if (callBudgetExhausted()) { console.log(`已達 API 預算（${MAX_CALLS} 次），提前停止補齊（未處理者下次續跑）。`); break; }
    const name = data.name || doc.id;
    const region = data.region || data.city || '';
    const regionCenter = data.destination_center || data.region_center || null;
    const addressHint = [name, region].filter(Boolean).join(', ');
    console.log('Processing', doc.id, name);
    const officialMatch = findOfficialSpot(name, region, officialSpots);
    let geocode = null;
    if (!officialMatch && GOOGLE_KEY) {
      geocode = await geocodeAddress(addressHint);
      await new Promise((r) => setTimeout(r, 250));
    }
    const score = officialMatch ? officialMatch.score : scoreMatch(name, geocode, regionCenter);
    const update = {
      last_crawled: admin.firestore.FieldValue.serverTimestamp(),
      crawl_source: officialMatch ? 'official_opendata' : (geocode ? 'google_geocode' : 'none'),
      crawl_confidence: score
    };
    let lat = null;
    let lng = null;
    if (officialMatch) {
      applyLocationUpdate(
        update,
        officialMatch.spot.lat,
        officialMatch.spot.lng,
        officialMatch.spot.address || [officialMatch.spot.city, officialMatch.spot.town].filter(Boolean).join('')
      );
      lat = officialMatch.spot.lat;
      lng = officialMatch.spot.lng;
      update.official_opendata_id = officialMatch.spot.id || null;
      update.official_opendata_name = officialMatch.spot.name;
      update.official_opendata_match = officialMatch.reason;
      if (officialMatch.spot.updateTime) update.official_opendata_updated_at = officialMatch.spot.updateTime;
    } else if (geocode && geocode.geometry && geocode.geometry.location) {
      applyLocationUpdate(update, geocode.geometry.location.lat, geocode.geometry.location.lng, geocode.formatted_address);
      lat = geocode.geometry.location.lat;
      lng = geocode.geometry.location.lng;
    }
    
    // Fetch nearby toilets if we have coordinates
    if (lat && lng && GOOGLE_KEY) {
      const nearbyToilets = await fetchNearbyToilets(lat, lng);
      update.nearbyToiletLocations = nearbyToilets;
      update.toiletCoordinatesVerifiedAt = admin.firestore.FieldValue.serverTimestamp();
      if (nearbyToilets.length > 0) {
        console.log(`Added ${nearbyToilets.length} nearby toilets for ${doc.id}`);
      } else {
        console.log(`Cleared nearby toilets for ${doc.id}; no verified toilets within ${MAX_NEARBY_TOILET_DISTANCE_METERS}m`);
      }
      await new Promise((r) => setTimeout(r, 250));
    }
    
    if (DRY) {
      console.log('DRY update for', doc.id, update);
    } else {
      await doc.ref.set(update, { merge: true });
      console.log('Wrote', doc.id, 'score', score);
    }
    processed += 1;
    if (processed >= LIMIT) break;
  }
  if (CRAWL_REGION) {
    console.log('Region filter summary', { region: CRAWL_REGION, processed, skippedByRegion, sliced: slicedCount, apiCalls: apiCallsUsed, slice: SLICE_RAW || 'all', maxCalls: MAX_CALLS || 'none' });
  }
}

async function processImport(db) {
  console.log('Using Firestore collection', POI_COLLECTION);
  console.log('Import mode enabled for region', CRAWL_REGION);

  // 來源選擇：--source=taitung｜national（預設 auto）
  //   auto = 先試全國來源；它已失效（回 null）且目標是台東時，自動改用台東觀光網。
  const wanted = String(argv.source || process.env.IMPORT_SOURCE || 'auto').toLowerCase();
  const isTaitung = /台東|臺東/.test(String(CRAWL_REGION || ''));
  let allSpots = null;
  let sourceLabel = null;

  if (wanted === 'taitung') {
    allSpots = await fetchTaitungScenicSpots();
    sourceLabel = 'taitung_tour_opendata';
  } else {
    allSpots = await fetchOfficialScenicSpots();
    sourceLabel = 'official_opendata';
    if (allSpots === null) {
      if (wanted === 'national') {
        throw new Error('全國觀光 OpenData 來源不可用，且已指定 --source=national。改用 --source=taitung，或設定 OPENDATA_SOURCE_URL 指向可用的來源。');
      }
      if (!isTaitung) {
        throw new Error(`全國觀光 OpenData 來源不可用，而 ${CRAWL_REGION} 沒有替代來源。`);
      }
      console.warn('→ 全國來源不可用，自動改用台東觀光網 opendata');
      allSpots = await fetchTaitungScenicSpots();
      sourceLabel = 'taitung_tour_opendata';
    }
  }

  const spots = (allSpots || [])
    .filter((spot) => spotMatchesRegion(spot, CRAWL_REGION))
    .slice(0, IMPORT_LIMIT);

  if (!spots.length) {
    // 這裡才是真的「來源可用但沒有符合的資料」
    console.log('來源可用但沒有符合地區的景點', { region: CRAWL_REGION, source: sourceLabel, fetched: (allSpots || []).length });
    return;
  }
  console.log('來源', sourceLabel, '→ 符合', CRAWL_REGION, '的景點', spots.length, '筆');

  // ★ 沿用既有 docId，避免同一個景點被匯入兩次。
  //   docId = normalizeDocKey(名稱 + region)，而 region = town || city || CRAWL_REGION。
  //   舊的全國來源有些景點 PostalAddress.Town 是空的（於是退回「臺東縣」），
  //   台東觀光網則 100% 有鄉鎮——同一個景點會算出兩個不同的 docId，變成重複文件
  //   （實測 265 筆中有 168 筆名稱對得上現有資料）。先用正規化名稱建索引，命中就沿用舊 id。
  const existingByName = new Map();
  try {
    const snap = await db.collection(POI_COLLECTION).get();
    snap.docs.forEach((doc) => {
      const n = normalizeDocKey(String((doc.data() || {}).name || '').replace(/臺/g, '台'));
      if (n && !existingByName.has(n)) existingByName.set(n, doc.id);
    });
    console.log('既有景點索引', existingByName.size, '筆（用於沿用 docId、避免重複匯入）');
  } catch (e) {
    console.warn('讀取既有景點失敗，無法防重複：', e.message);
  }

  let imported = 0;
  let skipped = 0;
  let reused = 0;
  for (const spot of spots) {
    const result = await buildImportedScenicPoint(spot, sourceLabel);
    if (result && result.docId) {
      const nameKey = normalizeDocKey(String(spot.name || '').replace(/臺/g, '台'));
      const existingId = nameKey ? existingByName.get(nameKey) : null;
      if (existingId && existingId !== result.docId) {
        result.data.id = existingId;
        result.docId = existingId;
        reused += 1;
      }
    }
    if (!result || !result.docId) {
      console.log('Skipping spot without valid document id', spot.name);
      skipped += 1;
      continue;
    }

    const { docId, data } = result;
    if (DRY) {
      console.log('DRY import for', docId, data);
    } else {
      await db.collection(POI_COLLECTION).doc(docId).set(data, { merge: true });
      console.log('Imported', docId);
    }
    imported += 1;
  }

  console.log('Import summary', { region: CRAWL_REGION, source: sourceLabel, imported, skipped, reusedExistingId: reused, dryRun: DRY });
}

// 由景點的行政區資訊推導前端 explore wizard 使用的「目的地鍵」（dest token）
// 例：綠島鄉→「綠島」、蘭嶼鄉→「蘭嶼」、台東市/台東縣→「台東」。
function deriveDestKey(data) {
  // 統一「臺」→「台」：OpenData 多用「臺東縣」，但前端 dest 用「台東」，不統一會分裂成兩桶且比對不到。
  const tw = (s) => String(s || '').replace(/臺/g, '台');
  const text = [data.region, data.city, data.county, data.town, data.formatted_address, data.address, data.name]
    .filter(Boolean).map(tw).join(' ');
  const islandRules = [
    { key: '綠島', kw: ['綠島'] },
    { key: '蘭嶼', kw: ['蘭嶼'] },
    { key: '小琉球', kw: ['小琉球', '琉球鄉'] }
  ];
  for (const rule of islandRules) {
    if (rule.kw.some((k) => text.includes(k))) return rule.key;
  }
  // 一般本島：取 city/county 去掉「縣/市」後綴當鍵；缺值則用 CRAWL_REGION。
  const base = tw(String(data.city || data.county || data.region || CRAWL_REGION || '').trim());
  return base.replace(/[縣市]$/u, '').trim() || base;
}

// 從描述文字解析「建議停留時間」（分鐘）。與前端 ai-travel-explore-final.js 的同名函式保持一致。
function parseDurationFromText(text) {
  if (!text || typeof text !== 'string') return null;
  const durations = [];
  for (const m of text.matchAll(/(\d+(?:\.\d+)?)\s*[個个]?小時/g)) {
    const h = parseFloat(m[1]);
    if (h >= 0.25 && h <= 8) durations.push(Math.round(h * 60));
  }
  for (const m of text.matchAll(/(\d+)\s*分[鐘钟]?/g)) {
    const min = parseInt(m[1]);
    if (min >= 10 && min <= 480) durations.push(min);
  }
  for (const m of text.matchAll(/(\d+(?:\.\d+)?)\s*h(?:ours?|rs?)\b/gi)) {
    const h = parseFloat(m[1]);
    if (h >= 0.25 && h <= 8) durations.push(Math.round(h * 60));
  }
  for (const m of text.matchAll(/(\d+)\s*min(?:utes?)?\b/gi)) {
    const min = parseInt(m[1]);
    if (min >= 10 && min <= 480) durations.push(min);
  }
  if (!durations.length) return null;
  durations.sort((a, b) => a - b);
  return durations[Math.floor(durations.length / 2)];
}

// 依景點類型估「建議停留時間」（分鐘）。關鍵字比對 name+desc，回傳穩定預設值（語感對齊前端 prompt 的示例）。
const DURATION_KEYWORD_TABLE = [
  { min: 90, kw: ['溫泉', '泡湯'] },
  { min: 75, kw: ['樂園', '遊樂', '農場', '牧場'] },
  { min: 65, kw: ['博物館', '美術館', '文物館', '故事館', '展覽', '園區'] },
  { min: 55, kw: ['步道', '登山', '健行', '森林', '瀑布'] },
  { min: 50, kw: ['老街', '商圈', '夜市'] },
  { min: 45, kw: ['市場', '漁港', '碼頭'] },
  { min: 40, kw: ['公園', '廣場', '部落', '社區'] },
  { min: 35, kw: ['海灘', '沙灘', '海濱', '海岸', '潭', '湖'] },
  { min: 30, kw: ['觀景', '景觀台', '眺望', '燈塔', '橋'] },
  { min: 25, kw: ['廟', '寺', '宮', '教堂', '神社'] }
];
const DURATION_DEFAULT_MINUTES = 60;

// 用免費來源推估 duration：既有值 → 描述解析 → 關鍵字分類 → 全域預設。完全不打 Places API。
function estimateDuration(data) {
  const explicit = Number(data && data.duration);
  if (Number.isFinite(explicit) && explicit > 0) return Math.round(explicit);
  const fromText = parseDurationFromText(data && (data.desc || data.description));
  if (fromText) return fromText;
  const hay = `${(data && data.name) || ''} ${(data && (data.desc || data.description)) || ''}`;
  for (const row of DURATION_KEYWORD_TABLE) {
    if (row.kw.some((k) => hay.includes(k))) return row.min;
  }
  return DURATION_DEFAULT_MINUTES;
}

// 把一筆 scenic_points 文件映射成前端要的精簡 POI（只保留變動不大的穩定欄位）
// ── 景點分類與行政區推定（export:local 專用） ─────────────────────────
//
// 為什麼要分類：getLocalPoiList 會把整份清單當成「景點候選」餵給 AI，
// 但 poi-data 裡混了車站（台東火車站 duration=480 分）、民宿，以及
// restaurant-data.js 已經有的餐廳。不分類的話 AI 會把火車站排成景點。
//
// 分類不刪資料——各端自行依 kind 取用，避免任何一方誤刪對別人有用的東西。
const POI_KIND_RE = {
  transit: /車站|火車站|機場|碼頭|轉運站|客運站/,
  lodging: /民宿|飯店|旅店|旅館|酒店|villa|hotel|hostel|inn\b/i,
  // 補上實測漏網的寫法：津芳冰城／秘食-私廚／藍蜻蜓速食店／阿鋐炸雞／
  // 東粄香客家米食／只有海-午餐輕食晚餐。注意不可只用「茶」「食」等單字，
  // 會把「奉茶樹」（池上的地標樹）誤判成餐飲。
  food: /餐廳|食堂|小吃|咖啡廳|咖啡館|咖啡店|cafe|廚房|私廚|麵館|燒烤|火鍋|茶飲|早餐店|冰店|冰城|小館|速食|炸雞|米食|輕食|便當|pizza|義大利麵/i
};

function loadRestaurantNameSet() {
  const set = new Set();
  try {
    const file = path.join(__dirname, '..', 'app', 'restaurant-data.js');
    if (!fs.existsSync(file)) return set;
    const sandbox = { window: {} };
    vm.createContext(sandbox);
    vm.runInContext(fs.readFileSync(file, 'utf8'), sandbox);
    const d = sandbox.window.WAI_RESTAURANT_DATA || {};
    Object.keys(d).forEach((k) => {
      if (!Array.isArray(d[k])) return;
      d[k].forEach((r) => {
        if (r && r.name) set.add(normalizeText(String(r.name).replace(/臺/g, '台')));
      });
    });
  } catch (e) {
    console.warn('讀取 restaurant-data.js 失敗，餐廳交叉比對略過：', e.message);
  }
  return set;
}

function classifyPoiKind(name, restaurantNames) {
  const n = String(name || '').replace(/臺/g, '台');
  if (POI_KIND_RE.transit.test(n)) return 'transit';
  if (POI_KIND_RE.lodging.test(n)) return 'lodging';
  // restaurant-data.js 已收錄的，一律算餐飲（避免同一家店在兩份資料各出現一次）
  if (restaurantNames && restaurantNames.has(normalizeText(n))) return 'food';
  if (POI_KIND_RE.food.test(n)) return 'food';
  return 'scenic';
}

// 名稱只是地區名（「台東」「綠島」）的不是景點，直接排除
const BARE_REGION_RE = /^(台東|臺東|綠島|蘭嶼|知本|池上|成功|關山|鹿野|長濱|海端|東河|延平|卑南|金峰|太麻里|大武|達仁)(縣|市|鄉|鎮)?$/;
// 名稱只有類別詞、沒有專名（「咖啡廳」「餐廳」「公園」）也不是可用的景點——
// 使用者看不出那是哪一家，AI 也沒辦法驗證座標。實測有一筆「咖啡廳」的座標
// 落在新北市金山（25.2065, 121.525），完全不在台東。
const GENERIC_NAME_RE = /^(咖啡廳|咖啡館|咖啡店|餐廳|小吃|民宿|飯店|旅館|夜市|公園|步道|海灘|沙灘|景點|廁所|停車場)$/;

// 以「最近 3 個已知鄰居投票」推定行政區。
// 先前用的「最近重心」法留一驗證只有 86.7%（而且會把沒地址的全倒進地理居中的
// 台東市）；kNN(k=3) 是 94.9%，且錯的都落在相鄰鄉鎮（卑南↔台東、金峰↔太麻里）。
// 推定值一律標 districtInferred，呼叫端要知道這不是地址判定出來的。
function inferDistrictByNeighbours(target, known, k) {
  if (!known.length) return null;
  const scored = known.map((q) => ({
    d: Math.hypot(target.lat - q.lat, (target.lng - q.lng) * Math.cos(target.lat * Math.PI / 180)),
    district: q.district
  })).sort((a, b) => a.d - b.d).slice(0, k || 3);
  const votes = {};
  scored.forEach((x) => { votes[x.district] = (votes[x.district] || 0) + 1; });
  const best = Object.keys(votes).sort((a, b) => votes[b] - votes[a])[0];
  return best || null;
}

function toLocalPoi(data) {
  const coord = data.scenicCoordinates || {};
  const lat = toNumber(coord.lat != null ? coord.lat : data.lat);
  const lng = toNumber(coord.lng != null ? coord.lng : data.lng);
  if (!data.name || lat === null || lng === null) return null;
  // 空字串不輸出：原本 desc / address / businessHours 一律寫入，
  // 於是「欄位覆蓋率 100%」但其中 104 / 86 / 44 筆其實是空的，
  // 看報表會誤以為資料很完整。有值才寫，缺就是缺。
  const poi = { name: String(data.name).trim(), lat, lng };
  const desc = String(data.desc || data.description || '').trim();
  const addr = String(data.formatted_address || data.address || '').trim();
  const hours = String(data.placeOpeningHours || data.openTime || data.businessHours || '').trim();
  if (desc) poi.desc = desc;
  if (addr) poi.address = addr;
  if (hours) poi.businessHours = hours;
  // 行政區（去掉鄉/鎮/市，如「長濱」「太麻里」）。前端探索頁用它做鄉鎮分頁——
  // poi-data.js 本身是照目的地鍵（台東/綠島/蘭嶼）分桶的，桶內看不出鄉鎮。
  // 先取 region（import 時就是 town），再退回從地址解析；都沒有就不寫這個欄位，
  // 前端據此把該景點歸到「其他」，不臆測。
  const district = taitungTownOf(data.region) || taitungTownOf(data.formatted_address || data.address);
  if (district) poi.district = district.replace(/[鄉鎮市]$/, '');
  if (Number.isFinite(Number(data.placesRating)) && Number(data.placesRating) > 0) poi.rating = Number(data.placesRating);
  if (data.placeVerified === true) poi.placeVerified = true; // 供前端 verify 短路：命中即可跳過 Places 呼叫
  // 門票費用（enrich-fees 寫入的真實票價；含 0=免費）。對不到的景點無此欄位＝未知。
  if (Number.isFinite(Number(data.fee))) poi.fee = Number(data.fee);
  if (data.feeNote) poi.feeNote = String(data.feeNote);
  poi.duration = estimateDuration(data);
  if (Array.isArray(data.nearbyToiletLocations) && data.nearbyToiletLocations.length) {
    poi.nearbyToiletLocations = data.nearbyToiletLocations;
  }
  return poi;
}

// 對單一景點用 Places (New) searchText 嚴格比對名稱，回傳校正資料或 null。
// FieldMask 只取座標/營業時間/評分/id/狀態（Enterprise 等級，不含 reviews 的 Atmosphere 高價 SKU），且名稱不更動。
async function fetchPlaceVerification(name, region, center) {
  if (!GOOGLE_KEY || !name) return null;
  const body = {
    textQuery: `${name} ${region || ''}`.trim(),
    languageCode: 'zh-TW',
    maxResultCount: 5
  };
  const hasCenter = center && Number.isFinite(center.lat) && Number.isFinite(center.lng);
  if (hasCenter) {
    body.locationBias = { circle: { center: { latitude: center.lat, longitude: center.lng }, radius: 30000 } };
  }
  try {
    noteApiCall();
    const r = await axios.post(
      'https://places.googleapis.com/v1/places:searchText',
      body,
      {
        headers: {
          'Content-Type': 'application/json',
          'X-Goog-Api-Key': GOOGLE_KEY,
          'X-Goog-FieldMask': 'places.displayName,places.location,places.regularOpeningHours,places.rating,places.id,places.businessStatus'
        },
        timeout: 15000
      }
    );
    const places = Array.isArray(r.data && r.data.places) ? r.data.places : [];
    for (const p of places) {
      if (!p.location) continue;
      const dn = p.displayName && p.displayName.text;
      // 嚴格名稱比對 → 擋掉只共用通用後綴的別處地點
      if (!placeNameMatchesQuery(dn, name)) continue;
      const pos = { lat: p.location.latitude, lng: p.location.longitude };
      // 有 OpenData 座標時，要求 Places 結果落在附近（VERIFY_NEAR_METERS）→ 擋掉同名異地（如本島也有「南寮漁港」）
      if (hasCenter && measureDistanceMeters(center, pos) > VERIFY_NEAR_METERS) continue;
      const hours = (p.regularOpeningHours && Array.isArray(p.regularOpeningHours.weekdayDescriptions))
        ? p.regularOpeningHours.weekdayDescriptions.join('\n') : '';
      return {
        lat: pos.lat,
        lng: pos.lng,
        businessHours: hours,
        rating: (typeof p.rating === 'number') ? p.rating : null,
        placeId: p.id || null,
        businessStatus: p.businessStatus || null,
        matchedName: dn || null
      };
    }
    return null;
  } catch (e) {
    if (e.response && e.response.status === 403) {
      console.warn('Places API 403：請至 GCP Console 啟用「Places API (New)」。');
    } else {
      console.warn(`Places verify error (${name}):`, e.message);
    }
    return null;
  }
}

// 一次性：用 Places 校正 scenic_points 的座標/營業時間/評分（名稱保留 OpenData 原值），寫回 Firestore。
// 之後重跑 export:local 即可讓 poi-data.js 達 Places 等級，執行階段仍 0 Places 費用。
async function verifyPlaces(db) {
  if (!GOOGLE_KEY) { console.error('需要 GOOGLE_MAPS_API_KEY 才能用 Places 校正。'); return; }
  console.log(`Verify-places mode: reading collection ${POI_COLLECTION}（force=${FORCE}, near=${VERIFY_NEAR_METERS}m）`);
  const snap = await db.collection(POI_COLLECTION).get();
  if (snap.empty) { console.log(`No documents in ${POI_COLLECTION}.`); return; }
  let scanned = 0, verified = 0, unmatched = 0, skipped = 0, closed = 0, sliced = 0, eligibleIdx = -1;
  for (const doc of snap.docs) {
    const data = doc.data();
    if (CRAWL_REGION && !docMatchesRegion(data, CRAWL_REGION)) continue;
    if (!FORCE && data.placeVerified === true) { skipped += 1; continue; }
    eligibleIdx += 1; // 只對「真正待校正」的文件分片，讓一週各天工作量平均
    if (!inSlice(eligibleIdx, WORK_SLICE)) { sliced += 1; continue; }
    if (callBudgetExhausted()) { console.log(`已達 API 預算（${MAX_CALLS} 次），提前停止 verify-places（未處理者下次續跑）。`); break; }
    if (argv.limit !== undefined && scanned >= LIMIT) break; // --limit N 時只處理前 N 筆（省 Places 額度，便於試跑）
    scanned += 1;
    const coord = data.scenicCoordinates || {};
    const center = {
      lat: toNumber(coord.lat != null ? coord.lat : data.lat),
      lng: toNumber(coord.lng != null ? coord.lng : data.lng)
    };
    const region = String(data.county || data.city || data.region || CRAWL_REGION || '').replace(/臺/g, '台').trim();
    const res = await fetchPlaceVerification(data.name, region, center);
    await new Promise((r) => setTimeout(r, 250)); // 避免觸發速率限制
    if (!res) {
      unmatched += 1;
      console.log(`✗ 無嚴格配對，保留 OpenData：${data.name}`);
      if (!DRY) await doc.ref.set({ placeVerified: false, placeVerifiedAt: admin.firestore.FieldValue.serverTimestamp() }, { merge: true });
      continue;
    }
    const isClosed = res.businessStatus === 'CLOSED_PERMANENTLY';
    if (isClosed) closed += 1;
    verified += 1;
    const moved = (Number.isFinite(center.lat) && Number.isFinite(center.lng))
      ? Math.round(measureDistanceMeters(center, { lat: res.lat, lng: res.lng })) : null;
    console.log(`✓ ${data.name}｜座標位移 ${moved != null ? moved + 'm' : 'n/a'}｜評分 ${res.rating != null ? res.rating : '—'}${isClosed ? '｜⚠ 已永久歇業' : ''}`);
    if (DRY) continue;
    const update = {
      scenicCoordinates: { lat: res.lat, lng: res.lng },
      lat: res.lat,
      lng: res.lng,
      coordinateSource: 'google_places',
      placeVerified: true,
      placeVerifiedAt: admin.firestore.FieldValue.serverTimestamp(),
      place_id: res.placeId,
      placesRating: res.rating,
      placeBusinessStatus: res.businessStatus,
      placePermanentlyClosed: isClosed
    };
    if (res.businessHours) update.placeOpeningHours = res.businessHours;
    // 首次校正時保留原始 OpenData 座標供追溯
    if (data.opendataCoordinates === undefined && Number.isFinite(center.lat) && Number.isFinite(center.lng)) {
      update.opendataCoordinates = { lat: center.lat, lng: center.lng };
    }
    await doc.ref.set(update, { merge: true });
  }
  console.log('Verify-places summary', { region: CRAWL_REGION, scanned, verified, unmatched, closed, skipped, sliced, apiCalls: apiCallsUsed, slice: SLICE_RAW || 'all', maxCalls: MAX_CALLS || 'none', dryRun: DRY });
}

// ================= 門票費用（TDX 觀光資訊：Attraction + AttractionFee）=================
// 只寫真實票價：對得到就寫 fee(數字,0=免費)/feeNote/feeSource；對不到不動（保持未知）。不做估算。
let _tdxToken = null;
async function getTdxToken() {
  if (_tdxToken) return _tdxToken;
  if (!TDX_APP_ID || !TDX_APP_KEY) throw new Error('缺少 TDX_APP_ID / TDX_APP_KEY（請確認 app/weather.env.js）');
  const body = `grant_type=client_credentials&client_id=${encodeURIComponent(TDX_APP_ID)}&client_secret=${encodeURIComponent(TDX_APP_KEY)}`;
  const r = await axios.post(TDX_AUTH_URL, body, {
    headers: { 'content-type': 'application/x-www-form-urlencoded' },
    timeout: 15000
  });
  _tdxToken = r.data && r.data.access_token;
  if (!_tdxToken) throw new Error('TDX 取得 access_token 失敗');
  return _tdxToken;
}

// 單次 GET，遇 429（TDX 免費層速率限制）依 Retry-After 退避重試。
async function tdxGet(url, attempt) {
  const token = await getTdxToken();
  try {
    return await axios.get(url, { headers: { authorization: `Bearer ${token}` }, timeout: 30000 });
  } catch (e) {
    const status = e.response && e.response.status;
    if (status === 429 && (attempt || 0) < 5) {
      const retryAfter = Number(e.response.headers && e.response.headers['retry-after']);
      const waitMs = Number.isFinite(retryAfter) && retryAfter > 0 ? retryAfter * 1000 : Math.min(30000, 2000 * Math.pow(2, attempt || 0));
      console.warn(`TDX 429 限流，等待 ${Math.round(waitMs / 1000)}s 後重試…`);
      await new Promise((res) => setTimeout(res, waitMs));
      return tdxGet(url, (attempt || 0) + 1);
    }
    throw e;
  }
}

// 以 $top/$skip 分頁抓完整資源，回陣列。
async function fetchTdxAll(resourcePath, pageSize) {
  const size = pageSize || 500; // TDX 觀光 OData $top 上限為 500
  const out = [];
  for (let skip = 0; ; skip += size) {
    const url = `${TDX_TOURISM_BASE}/${resourcePath}?%24top=${size}&%24skip=${skip}&%24format=JSON`;
    const r = await tdxGet(url, 0);
    const rows = Array.isArray(r.data) ? r.data : (r.data && Array.isArray(r.data.value) ? r.data.value : []);
    out.push(...rows);
    if (rows.length < size) break;
    await new Promise((res) => setTimeout(res, 600)); // 頁間間隔，降低觸發限流機率
  }
  return out;
}

// fee map key：normalizeText + 臺→台（與前端 getLocalPoiList 對齊）。
function feeNameKey(name) { return normalizeText(String(name || '').replace(/臺/g, '台')); }

// 從自由票價文字解析「每人全票金額」。先去掉年齡/人數/時間等會被誤判成金額的數字，
// 再優先抓「全票…金額」，否則取有 元/$/＄ 標記者的最大值；純免費→0；無法判斷→null。
function parseFeeFromText(text) {
  if (!text || typeof text !== 'string') return null;
  let t = text.replace(/[\r\n]+/g, ' ');
  // 純免費（且無任何金額）直接 0
  if (/免費(參觀|入園|入場)?/.test(t) && !/(\d+\s*元|＄\s*\d|\$\s*\d)/.test(t)) return 0;
  // 去雜訊：年齡/人數/公分/時間/月日/成團，避免「65歲」「10人」被當金額
  t = t.replace(/\d+\s*歲/g, ' ')
       .replace(/\d+\s*(人|位|名|公分|分鐘|小時|公尺|公里|張)/g, ' ')
       .replace(/\d{1,2}:\d{2}/g, ' ')
       .replace(/\d+\s*[月日]/g, ' ')
       .replace(/\d+\s*成團/g, ' ');
  // 優先「全票 … 金額」
  const full = t.match(/全票[^0-9元＄$]{0,8}(?:nt\$?|＄|\$)?\s*(\d{1,5})/i);
  if (full) { const v = parseInt(full[1], 10); if (v >= 0 && v <= 20000) return v; }
  // 否則取有 元/$/＄ 標記的金額最大值（忽略裸數字，如團體人數）
  const marked = [];
  for (const m of t.matchAll(/(?:nt\$?|＄|\$)\s*(\d{1,5})|(\d{1,5})\s*元/gi)) {
    const v = parseInt(m[1] || m[2], 10);
    if (Number.isFinite(v) && v >= 0 && v <= 20000) marked.push(v);
  }
  if (marked.length) return Math.max(...marked);
  return /免費|免门票|免門票|free/i.test(t) ? 0 : null;
}

// ── 台東觀光旅遊網自有 opendata（含 265 景點的 ticket 門票文字，41 筆有值，優於全國 TDX）──
// 需帶 X-Requested-With: XMLHttpRequest（否則 302 轉走）。回 名稱正規化key → { name, fee?, feeNote, feeSource }。
const TAITUNG_OD_ATTRACTIONS_URL = 'https://tour.taitung.gov.tw/zh-tw/opendata/attractions';
async function buildTaitungFeeMap() {
  console.log('台東觀光網 opendata 抓取 attractions …');
  const r = await axios.get(TAITUNG_OD_ATTRACTIONS_URL, {
    headers: {
      'X-Requested-With': 'XMLHttpRequest',
      'Accept': 'application/json, text/plain, */*',
      'Referer': 'https://tour.taitung.gov.tw/zh-tw/attraction',
      'User-Agent': 'Mozilla/5.0'
    },
    timeout: 30000,
    transformResponse: [(d) => d] // 保留原字串，自行去 BOM 解析
  });
  let payload;
  try { payload = JSON.parse(String(r.data).replace(/^﻿/, '')); }
  catch (e) { console.warn('台東 opendata 解析失敗：', e.message); return new Map(); }
  const arr = Array.isArray(payload) ? payload : (payload.data || payload.Data || []);
  const map = new Map();
  let withFee = 0;
  for (const a of arr) {
    const name = a.name || a.Name || a.title;
    if (!name) continue;
    const ticket = String(a.ticket || '').trim();
    if (!ticket || /^[無─\-–—.．\s]*$/.test(ticket)) continue; // 空或「無」等佔位＝未知，不寫
    const fee = parseFeeFromText(ticket);
    const feeNote = ticket.replace(/[\r\n]+/g, ' ').slice(0, 60);
    const entry = { name: String(name).trim(), feeNote, feeSource: 'taitung_od' };
    if (Number.isFinite(fee)) entry.fee = fee;
    map.set(feeNameKey(name), entry);
    withFee += 1;
  }
  console.log(`台東 opendata 取得 ${arr.length} 景點，其中 ${withFee} 筆有門票資料`);
  return map;
}

// 建立 名稱正規化key → { name, fee?, feeNote, feeSource }。結構化票價優先，其次免費旗標，最後 FeeInfo 文字。
async function buildTdxFeeMap() {
  console.log('TDX 抓取 AttractionFee / Attraction …（依序，避免限流）');
  const fees = await fetchTdxAll('AttractionFee');
  const attractions = await fetchTdxAll('Attraction');
  console.log(`TDX 取得 Attraction ${attractions.length} 筆、AttractionFee ${fees.length} 筆`);
  const map = new Map();
  const setIfAbsent = (name, entry) => {
    const k = feeNameKey(name);
    if (!k || !entry) return;
    if (!map.has(k)) map.set(k, Object.assign({ name: String(name).trim() }, entry));
  };
  // 1) 結構化票價（含 Price 數字）優先
  for (const f of fees) {
    const list = Array.isArray(f.Fees) ? f.Fees : [];
    const prices = list.map((x) => Number(x && x.Price)).filter((v) => Number.isFinite(v) && v >= 0);
    if (!prices.length) continue;
    const fee = Math.max(...prices); // 全票（通常最高）
    const note = list.map((x) => {
      const nm = String(x.Name || '').trim();
      const pr = Number(x.Price);
      if (Number.isFinite(pr) && pr > 0) return `${nm || '票'} $${pr}`;
      return nm || '免費';
    }).filter(Boolean).join(' / ') || (fee === 0 ? '免費' : `$${fee}`);
    setIfAbsent(f.AttractionName, { fee, feeNote: note, feeSource: 'tdx_fee' });
  }
  // 2) 免費旗標 / FeeInfo 文字補充（不覆蓋已有結構化票價）
  for (const a of attractions) {
    const name = a.AttractionName || a.Name;
    if (!name || map.has(feeNameKey(name))) continue;
    if (a.IsAccessibleForFree === true) {
      setIfAbsent(name, { fee: 0, feeNote: '免費', feeSource: 'tdx_free' });
      continue;
    }
    const info = String(a.FeeInfo || '').trim();
    if (!info) continue;
    const fee = parseFeeFromText(info);
    if (fee !== null) setIfAbsent(name, { fee, feeNote: info.slice(0, 60), feeSource: 'tdx_feeinfo' });
    else setIfAbsent(name, { feeNote: info.slice(0, 60), feeSource: 'tdx_feeinfo' }); // 只有文字沒數字
  }
  console.log(`TDX fee map 建立完成：${map.size} 筆`);
  return map;
}

// enrich-fees 模式：把真實票價比對回 scenic_points。
// 主來源＝台東觀光網 opendata（含 ticket 門票文字）；加 --tdx 時再用全國 TDX 補未命中者。
// 旗標：--limit / --region / --dry-run / --force / --loose / --tdx。
async function enrichFees(db) {
  const feeMap = await buildTaitungFeeMap(); // 台東主來源優先
  if (argv.tdx) {
    const tdx = await buildTdxFeeMap();      // 選用：全國 TDX 補洞（台東 opendata 未涵蓋者）
    for (const [k, v] of tdx) { if (!feeMap.has(k)) feeMap.set(k, v); }
  }
  if (!feeMap.size) { console.warn('無任何票價來源資料，結束。'); return; }
  const looseList = Array.from(feeMap.values());
  const FEE_LOOSE = !!argv.loose; // 預設只用精確名稱比對，避免把票價配錯到同名異地
  console.log(`Enrich-fees mode: reading ${POI_COLLECTION}（force=${FORCE}, loose=${FEE_LOOSE}, tdx=${!!argv.tdx}, 來源筆數=${feeMap.size}）`);
  const snap = await db.collection(POI_COLLECTION).get();
  if (snap.empty) { console.log(`No documents in ${POI_COLLECTION}.`); return; }
  let scanned = 0, matched = 0, exact = 0, loose = 0, unmatched = 0, skipped = 0, sliced = 0, eligibleIdx = -1;
  for (const doc of snap.docs) {
    const data = doc.data();
    if (CRAWL_REGION && !docMatchesRegion(data, CRAWL_REGION)) continue;
    if (!FORCE && data.feeSource) { skipped += 1; continue; } // 已有費用來源、非 force 就跳過
    eligibleIdx += 1;
    if (!inSlice(eligibleIdx, WORK_SLICE)) { sliced += 1; continue; }
    if (argv.limit !== undefined && scanned >= LIMIT) break;
    scanned += 1;
    let hit = feeMap.get(feeNameKey(data.name)) || null;
    let via = 'exact';
    if (!hit && FEE_LOOSE) {
      hit = looseList.find((e) => placeNameMatchesQuery(e.name, data.name)) || null;
      via = 'loose';
    }
    if (!hit || (!Number.isFinite(hit.fee) && !hit.feeNote)) { unmatched += 1; continue; }
    matched += 1; if (via === 'exact') exact += 1; else loose += 1;
    const feeStr = (hit.fee === 0) ? '免費' : (Number.isFinite(hit.fee) ? '$' + hit.fee : (hit.feeNote || '?'));
    console.log(`✓ ${data.name} → ${feeStr}｜${hit.feeSource}｜(${via})`);
    if (DRY) continue;
    const update = { feeSource: hit.feeSource, feeUpdatedAt: admin.firestore.FieldValue.serverTimestamp() };
    if (Number.isFinite(hit.fee)) update.fee = hit.fee;
    if (hit.feeNote) update.feeNote = hit.feeNote;
    await doc.ref.set(update, { merge: true });
  }
  console.log('Enrich-fees summary', { region: CRAWL_REGION, scanned, matched, exact, loose, unmatched, skipped, sliced, dryRun: DRY });
}

async function exportLocal(db) {
  console.log('Export-local mode: reading collection', POI_COLLECTION);
  const snap = await db.collection(POI_COLLECTION).get();
  if (snap.empty) {
    console.log(`No documents in ${POI_COLLECTION}; nothing to export.`);
    return;
  }
  // 同一個景點在 collection 裡可能有多份文件（不同時期的匯入用了不同的 docId 規則，
  // 實測有 84 組同名）。原本是「先到先贏」，而 Firestore 預設依 docId 排序，
  // 等於隨機挑一份——實測讓 20 筆掉了 rating、3 筆掉了 fee，因為贏的是沒被
  // verify:places / enrich:fees 加值過的那一份。改成先收集、再挑資料最完整的。
  function completeness(poi) {
    let s = 0;
    if (poi.placeVerified) s += 8;
    if (Number.isFinite(poi.rating) && poi.rating > 0) s += 4;
    if (Number.isFinite(poi.fee)) s += 3;
    if (poi.district) s += 2;
    if (poi.businessHours) s += 2;
    if (poi.nearbyToiletLocations && poi.nearbyToiletLocations.length) s += 1;
    s += Math.min(3, Math.floor(String(poi.desc || '').length / 40));
    return s;
  }

  // 逐欄位合併，而不是「挑一份贏家」。挑整份的話，贏的那份只要某個欄位較差就會
  // 平白掉資料（實測仍有 7 筆：加路蘭遊憩區掉營業時間、富岡漁港掉廁所…）。
  // 同名的幾份文件描述的是同一個景點，取聯集才對。
  const EMPTY = (v) => v === undefined || v === null || v === ''
    || (Array.isArray(v) && !v.length);
  function mergePoi(base, extra) {
    const better = completeness(extra) > completeness(base) ? extra : base;
    const worse = better === extra ? base : extra;
    const out = Object.assign({}, worse, better);
    // better 缺的欄位由 worse 補；數值欄位取有值的那個
    Object.keys(worse).forEach((k) => { if (EMPTY(out[k]) && !EMPTY(worse[k])) out[k] = worse[k]; });
    Object.keys(better).forEach((k) => { if (EMPTY(out[k]) && !EMPTY(better[k])) out[k] = better[k]; });
    // 「未知」不是有效的營業時間，別讓它擋掉真正的值
    if (String(out.businessHours || '').trim() === '未知') {
      const alt = [better, worse].map((p) => String(p.businessHours || '').trim())
        .find((h) => h && h !== '未知');
      // 沒有替代值時要「刪掉欄位」而不是設成空字串——
      // 整份資料的規格是「有欄位就等於有值」，留空字串會破壞這個保證。
      if (alt) out.businessHours = alt; else delete out.businessHours;
    }
    return out;
  }

  const restaurantNames = loadRestaurantNameSet();
  const candidates = {};          // destKey → Map(正規化名稱 → 合併後的 poi)
  let droppedBareRegion = 0;
  const droppedOutOfBounds = [];
  let clampedDuration = 0;
  let shortenedExterior = 0;
  for (const doc of snap.docs) {
    const data = doc.data();
    if (CRAWL_REGION && !docMatchesRegion(data, CRAWL_REGION)) continue;
    if (data.placePermanentlyClosed === true) continue; // 跳過 Places 標記已永久歇業者
    const poi = toLocalPoi(data);
    if (!poi) continue;
    // 名稱只是地區名（「台東」）或純類別詞（「咖啡廳」）的不是景點，排除
    const trimmedName = String(poi.name).trim();
    if (BARE_REGION_RE.test(trimmedName) || GENERIC_NAME_RE.test(trimmedName)) {
      droppedBareRegion += 1; continue;
    }
    // 座標必須落在台東縣範圍內。錯誤座標會讓行程把使用者導到別的縣市，
    // 也會污染「最近鄰居」的行政區推定。
    if (!isInsideTaitungBounds(poi.lat, poi.lng)) {
      droppedOutOfBounds.push(trimmedName + '(' + poi.lat.toFixed(3) + ',' + poi.lng.toFixed(3) + ')');
      continue;
    }
    poi.kind = classifyPoiKind(poi.name, restaurantNames);
    // estimateDuration 對交通節點會估出離譜的值（實測台東火車站 480 分）。
    // 這個欄位是用來排行程的，超過半天的單站停留一定是估錯。
    if (!(poi.duration > 0 && poi.duration <= 180)) {
      clampedDuration += 1;
      poi.duration = Math.max(20, Math.min(180, Number(poi.duration) || 45));
    }
    // 只能在外面看的景點（宜灣卡片教堂「僅供外部參觀，無對外開放」），
    // estimateDuration 仍會照類別給 35–60 分。看外觀拍張照不需要那麼久，
    // 排進行程會白白佔掉時間，壓縮到真正能進去的站。
    if (WAI_HOURS_MOD && typeof WAI_HOURS_MOD.classifyAccess === 'function'
        && WAI_HOURS_MOD.classifyAccess(poi).level === 'exterior_only'
        && poi.duration > 20) {
      shortenedExterior += 1;
      poi.duration = 20;
    }
    const destKey = deriveDestKey(data);
    if (!destKey) continue;
    if (!candidates[destKey]) candidates[destKey] = new Map();
    const dedupeKey = normalizeText(poi.name);
    const prev = candidates[destKey].get(dedupeKey);
    candidates[destKey].set(dedupeKey, prev ? mergePoi(prev, poi) : poi);
  }

  const buckets = {};
  let total = 0;
  Object.keys(candidates).forEach((k) => {
    buckets[k] = [...candidates[k].values()];
    total += buckets[k].length;
  });

  // ── 行政區推定：地址判不出來的，用「最近 3 個已知鄰居投票」補 ──
  const flat = Object.keys(buckets).reduce((a, k) => a.concat(buckets[k]), []);
  const knownDistrict = flat.filter((x) => x.district && x.lat && x.lng);
  let inferred = 0;
  flat.forEach((x) => {
    if (x.district || !x.lat || !x.lng) return;
    const guess = inferDistrictByNeighbours(x, knownDistrict, 3);
    if (guess) { x.district = guess; x.districtInferred = true; inferred += 1; }
  });

  const kindCount = {};
  flat.forEach((x) => { kindCount[x.kind] = (kindCount[x.kind] || 0) + 1; });
  console.log('分類：', kindCount);
  console.log('行政區：地址判定 ' + knownDistrict.length + '，鄰居推定 ' + inferred
    + '，仍缺 ' + flat.filter((x) => !x.district).length);
  if (droppedBareRegion) console.log('排除泛稱／地區名 ' + droppedBareRegion + ' 筆');
  if (droppedOutOfBounds.length) console.log('排除座標超出台東縣 ' + droppedOutOfBounds.length
    + ' 筆：' + droppedOutOfBounds.slice(0, 5).join('、'));
  if (clampedDuration) console.log('修正離譜的停留時長 ' + clampedDuration + ' 筆');
  if (shortenedExterior) console.log('僅可外部參觀 → 停留縮短為 20 分 ' + shortenedExterior + ' 筆');

  const summary = Object.keys(buckets).map((k) => `${k}:${buckets[k].length}`).join(', ');
  console.log(`Export-local prepared ${total} POIs across keys → ${summary || '(none)'}`);

  const payload = Object.assign({ __generatedAt: new Date().toISOString() }, buckets);
  const fileBody = `// Auto-generated by crawler (npm run export:local). Do not edit by hand.\n`
    + `window.WAI_POI_DATA = ${JSON.stringify(payload, null, 2)};\n`;

  if (DRY) {
    console.log('DRY export-local; would write', EXPORT_LOCAL_PATH);
    console.log(fileBody.slice(0, 800) + (fileBody.length > 800 ? '\n... (truncated)' : ''));
    return;
  }
  fs.writeFileSync(EXPORT_LOCAL_PATH, fileBody, 'utf8');
  console.log('Export-local wrote', EXPORT_LOCAL_PATH);

  await exportParkingLocal(db);
}

// parking_lots（縣府停車場，crawl:parking 寫入）→ app/parking-data.js 扁平陣列。
// 不像 scenic_points 依目的地分桶：停車場覆蓋整個台東縣本島，前端用座標就近比對即可。
async function exportParkingLocal(db) {
  const snap = await db.collection(PARKING_COLLECTION).get();
  if (snap.empty) { console.log(`No documents in ${PARKING_COLLECTION}; skip parking export.`); return; }
  const list = [];
  snap.forEach((doc) => {
    const d = doc.data();
    if (!Number.isFinite(d.lat) || !Number.isFinite(d.lng) || !d.name) return;
    const poi = { name: d.name, lat: d.lat, lng: d.lng };
    if (d.address) poi.address = d.address;
    if (d.phone) poi.phone = d.phone;
    if (d.evSpots) poi.evSpots = d.evSpots;
    if (d.largeSpots) poi.largeSpots = d.largeSpots;
    if (d.smallSpots) poi.smallSpots = d.smallSpots;
    if (d.motoSpots) poi.motoSpots = d.motoSpots;
    if (d.notes) poi.notes = d.notes;
    // 社群回報促進而來的點：來源與信心必須跟著出到靜態檔，
    // 否則前端無法把它跟官方停車場區分開（見 docs/停車回報群眾外包-實作計畫.md 1.2 缺口四）。
    // ⚠ 個別回報的 note 一律不出——那是使用者寫的自由文字，不該進公開資料檔。
    if (d.source) poi.source = d.source;                       // 'community'
    if (d.kind) poi.kind = d.kind;                             // 'lot' | 'roadside'
    if (Number.isFinite(d.reports)) poi.reports = d.reports;
    if (d.lastConfirmedAt) poi.lastConfirmedAt = d.lastConfirmedAt;
    list.push(poi);
  });
  const payload = { __generatedAt: new Date().toISOString(), taitungCounty: list };
  const fileBody = `// Auto-generated by crawler (npm run export:local). Do not edit by hand.\n`
    + `window.WAI_PARKING_DATA = ${JSON.stringify(payload, null, 2)};\n`;
  if (DRY) {
    console.log(`DRY export-local (parking); would write ${PARKING_DATA_PATH}（${list.length} 筆）`);
    return;
  }
  fs.writeFileSync(PARKING_DATA_PATH, fileBody, 'utf8');
  console.log(`Export-local wrote ${PARKING_DATA_PATH}（${list.length} 筆停車場）`);
}

// priceLevel enum → 0..4；並提供各級的人均消費估值（無 priceRange 時退回用）。
const PRICE_LEVEL_NUM = {
  PRICE_LEVEL_FREE: 0, PRICE_LEVEL_INEXPENSIVE: 1, PRICE_LEVEL_MODERATE: 2,
  PRICE_LEVEL_EXPENSIVE: 3, PRICE_LEVEL_VERY_EXPENSIVE: 4
};
const PRICE_LEVEL_ESTIMATE = [0, 150, 350, 700, 1200]; // 免費/$/$$/$$$/$$$$ 的人均估值
const PRICE_LEVEL_SYMBOL = ['免費', '$', '$$', '$$$', '$$$$'];
// 過濾住宿（searchNearby includedTypes:restaurant 仍會夾帶附設餐廳的飯店）
const LODGING_TYPE_SET = new Set(['lodging', 'hotel', 'resort_hotel', 'motel', 'hostel', 'bed_and_breakfast', 'guest_house', 'campground', 'rv_park']);
const LODGING_NAME_RE = /飯店|酒店|旅店|旅館|民宿|行館|度假村|度假酒店|villa|hotel|inn\b|resort|hostel/i;

// 對單一目的地（以形心 center 為中心）用 Places (New) searchNearby 抓餐廳候選。
// 改用 searchNearby（此金鑰伺服器端 searchText 會回空、searchNearby 正常）並取回 priceLevel/priceRange 價格。
// Places 結果 → restaurant-data.js 的餐廳物件。
// 抽出來共用：searchNearby（自動抓）與 searchText（指名補充）必須產生完全一樣的
// 欄位規格，否則補進來的店會缺 costPerPerson，預算估算就少算它們。
function placeToRestaurant(p, nameOverride, hoursOverride) {
  const name = nameOverride != null ? nameOverride : ((p.displayName && p.displayName.text) || '');
  const hours = hoursOverride != null ? hoursOverride
    : ((p.regularOpeningHours && Array.isArray(p.regularOpeningHours.weekdayDescriptions))
      ? p.regularOpeningHours.weekdayDescriptions.join('\n') : '');
  const poi = {
    name,
    lat: p.location.latitude,
    lng: p.location.longitude,
    businessHours: hours,
    address: p.formattedAddress || ''
  };
  if (typeof p.rating === 'number') poi.rating = p.rating;
  // 價格：priceLevel(0..4) + priceRange 的 NT$ 區間 → 人均估值 costPerPerson + 顯示用 costNote
  const pl = PRICE_LEVEL_NUM[p.priceLevel];
  if (pl !== undefined) poi.priceLevel = pl;
  const pr = p.priceRange || null;
  const lo = pr && pr.startPrice ? Number(pr.startPrice.units) : NaN;
  const hi = pr && pr.endPrice ? Number(pr.endPrice.units) : NaN;
  if (Number.isFinite(lo) || Number.isFinite(hi)) {
    if (Number.isFinite(lo)) poi.costMin = lo;
    if (Number.isFinite(hi)) poi.costMax = hi;
    const mid = (Number.isFinite(lo) && Number.isFinite(hi)) ? Math.round((lo + hi) / 2) : (Number.isFinite(hi) ? hi : lo);
    if (Number.isFinite(mid)) poi.costPerPerson = mid;
    poi.costNote = (Number.isFinite(lo) && Number.isFinite(hi))
      ? (lo <= 1 ? `$${hi} 內` : `$${lo}–${hi}`)
      : (Number.isFinite(hi) ? `約 $${hi}` : `約 $${lo}`);
  } else if (pl !== undefined) { // 無 priceRange → 用 priceLevel 估
    const est = PRICE_LEVEL_ESTIMATE[pl];
    poi.costPerPerson = est;
    poi.costNote = pl === 0 ? '免費' : `約 $${est}（${PRICE_LEVEL_SYMBOL[pl]}）`;
  }
  return poi;
}

async function fetchRestaurantsNear(region, center) {
  if (!GOOGLE_KEY) return [];
  if (!center || !Number.isFinite(center.lat) || !Number.isFinite(center.lng)) return [];
  const out = [];
  const seen = new Set();
  try {
    noteApiCall();
    const r = await axios.post(
      'https://places.googleapis.com/v1/places:searchNearby',
      {
        includedTypes: ['restaurant'],
        maxResultCount: 20,
        languageCode: 'zh-TW',
        locationRestriction: { circle: { center: { latitude: center.lat, longitude: center.lng }, radius: 15000 } }
      },
      {
        headers: {
          'Content-Type': 'application/json',
          'X-Goog-Api-Key': GOOGLE_KEY,
          'X-Goog-FieldMask': 'places.displayName,places.location,places.regularOpeningHours,places.rating,places.formattedAddress,places.id,places.businessStatus,places.primaryType,places.priceLevel,places.priceRange'
        },
        timeout: 15000
      }
    );
    const places = Array.isArray(r.data && r.data.places) ? r.data.places : [];
    for (const p of places) {
      if (!p.location) continue;
      if (p.businessStatus === 'CLOSED_PERMANENTLY') continue;
      const name = (p.displayName && p.displayName.text) || '';
      if (!name) continue;
      if (LODGING_TYPE_SET.has(p.primaryType) || LODGING_NAME_RE.test(name)) continue; // 濾掉飯店
      const dedupe = normalizeText(name);
      if (seen.has(dedupe)) continue;
      seen.add(dedupe);
      const hours = (p.regularOpeningHours && Array.isArray(p.regularOpeningHours.weekdayDescriptions))
        ? p.regularOpeningHours.weekdayDescriptions.join('\n') : '';
      out.push(placeToRestaurant(p, name, hours));
      if (out.length >= FOOD_PER_DEST) break;
    }
  } catch (e) {
    if (e.response && e.response.status === 403) {
      console.warn('Places API 403：請至 GCP Console 啟用「Places API (New)」。');
    } else {
      console.warn(`Restaurant fetch error (${region}):`, e.message);
    }
  }
  return out;
}

// 產生獨立的餐廳快取 restaurant-data.js：依 scenic_points 分桶算形心，逐桶抓餐廳。
// ── 指名補充的餐廳（crawler/food-include.json，手動維護）───────────────
//
// 為什麼需要：fetchRestaurantsNear 對每個目的地只打一次 searchNearby、只拿 20 筆
// （這是 Places 單次請求的上限），由 Google 依知名度排序。台東市區有數百家餐廳，
// 20 筆是很小的切片，很多在地名店排不進去（實測秘食-私廚 ★4.7、津芳冰城、
// 墾墨咖啡都沒被選上）。這裡讓使用者指名補上，成本是每家 1 次 searchText。
function loadFoodIncludeList() {
  try {
    const file = path.join(__dirname, 'food-include.json');
    if (!fs.existsSync(file)) return {};
    const raw = JSON.parse(fs.readFileSync(file, 'utf8'));
    const out = {};
    Object.keys(raw).forEach((k) => {
      if (k.startsWith('_') || !Array.isArray(raw[k])) return;   // _readme 之類的說明欄位
      out[k] = raw[k].filter((x) => typeof x === 'string' && x.trim());
    });
    return out;
  } catch (e) {
    console.warn('讀取 food-include.json 失敗，指名補充略過：', e.message);
    return {};
  }
}

// 以名稱＋目的地座標找單一店家。
// ★ locationBias 不可省略：實測沒加的話，「SP夏帕義大利麵 台東正氣店」會回
//   「谷津音響」，「秘食 私廚」「津芳冰城」「墾墨咖啡」直接查無結果。
async function fetchRestaurantByName(query, center) {
  if (!GOOGLE_KEY || !query || !center) return null;
  try {
    noteApiCall();
    const r = await axios.post(
      'https://places.googleapis.com/v1/places:searchText',
      {
        textQuery: query,
        languageCode: 'zh-TW',
        maxResultCount: 1,
        locationBias: { circle: { center: { latitude: center.lat, longitude: center.lng }, radius: 20000 } }
      },
      {
        headers: {
          'Content-Type': 'application/json',
          'X-Goog-Api-Key': GOOGLE_KEY,
          'X-Goog-FieldMask': 'places.displayName,places.location,places.regularOpeningHours,places.rating,places.formattedAddress,places.id,places.businessStatus,places.primaryType,places.priceLevel,places.priceRange'
        },
        timeout: 15000
      }
    );
    const p2 = (r.data && Array.isArray(r.data.places)) ? r.data.places[0] : null;
    if (!p2 || !p2.location) return null;
    if (p2.businessStatus === 'CLOSED_PERMANENTLY') return null;
    return placeToRestaurant(p2);
  } catch (e) {
    const code = e.response && e.response.status;
    console.warn(`  指名補充「${query}」失敗${code ? '（HTTP ' + code + '）' : ''}：${e.message}`);
    return null;
  }
}

// 把已收錄的名單補齊，回傳新增的筆數
async function applyFoodIncludes(destKey, center, restaurants, includeMap) {
  const wanted = includeMap[destKey] || [];
  if (!wanted.length) return 0;
  const have = new Set(restaurants.map((r) => normalizeText(String(r.name).replace(/臺/g, '台'))));
  let added = 0;
  for (const q of wanted) {
    if (callBudgetExhausted()) { console.log('  已達 API 預算，其餘指名補充略過。'); break; }
    // 粗略比對：名單寫的是關鍵字，只要已有的店名包含其中任一段就當作已收錄
    const qk = normalizeText(String(q).replace(/臺/g, '台'));
    const already = [...have].some((h) => h.includes(qk.slice(0, 4)) || qk.includes(h.slice(0, 4)));
    if (already) continue;
    const hit = await fetchRestaurantByName(q, center);
    if (!hit) { console.log(`  ✗ 指名補充查無：${q}`); continue; }
    const hk = normalizeText(String(hit.name).replace(/臺/g, '台'));
    if (have.has(hk)) continue;
    have.add(hk);
    restaurants.push(hit);
    added += 1;
    console.log(`  ＋ 指名補充：${q} → ${hit.name}${hit.costPerPerson ? '（約 $' + hit.costPerPerson + '）' : ''}`);
    await new Promise((r) => setTimeout(r, 150));
  }
  return added;
}

async function crawlFood(db) {
  if (!GOOGLE_KEY) { console.error('需要 GOOGLE_MAPS_API_KEY 才能爬餐廳。'); return; }
  console.log('Crawl-food mode: reading collection', POI_COLLECTION);
  const snap = await db.collection(POI_COLLECTION).get();
  if (snap.empty) { console.log(`No documents in ${POI_COLLECTION}; nothing to crawl.`); return; }

  // 依 deriveDestKey 分桶，累積座標以算形心
  const agg = {};
  for (const doc of snap.docs) {
    const data = doc.data();
    if (CRAWL_REGION && !docMatchesRegion(data, CRAWL_REGION)) continue;
    const coord = data.scenicCoordinates || {};
    const lat = toNumber(coord.lat != null ? coord.lat : data.lat);
    const lng = toNumber(coord.lng != null ? coord.lng : data.lng);
    if (lat === null || lng === null) continue;
    const key = deriveDestKey(data);
    if (!key) continue;
    if (!agg[key]) agg[key] = { sumLat: 0, sumLng: 0, count: 0 };
    agg[key].sumLat += lat; agg[key].sumLng += lng; agg[key].count += 1;
  }

  const targets = Object.keys(agg).filter((k) => agg[k].count >= MIN_FOOD_POIS);
  console.log(`Crawl-food targets（POI ≥ ${MIN_FOOD_POIS}）：${targets.map((k) => `${k}(${agg[k].count})`).join(', ') || '(none)'}`);

  // 切片或預算模式為「部分更新」：先載入既有檔，只覆寫本次處理到的目的地，避免清掉其他天已爬的資料。
  const partial = !!(WORK_SLICE || MAX_CALLS > 0);
  const buckets = {};
  if (partial) {
    const existing = loadExistingGlobalFile(RESTAURANT_DATA_PATH, 'WAI_RESTAURANT_DATA');
    Object.keys(existing).forEach((k) => { if (k !== '__generatedAt') buckets[k] = existing[k]; });
  }
  const foodIncludes = loadFoodIncludeList();
  const includeTotal = Object.keys(foodIncludes).reduce((n, k) => n + foodIncludes[k].length, 0);
  if (includeTotal) console.log(`指名補充名單：${includeTotal} 家（food-include.json）`);
  let processedTargets = 0, slicedTargets = 0;
  for (let i = 0; i < targets.length; i++) {
    const key = targets[i];
    if (!inSlice(i, WORK_SLICE)) { slicedTargets += 1; continue; }
    if (callBudgetExhausted()) { console.log(`已達 API 預算（${MAX_CALLS} 次），提前停止 crawl-food（其餘目的地保留既有資料）。`); break; }
    const center = { lat: agg[key].sumLat / agg[key].count, lng: agg[key].sumLng / agg[key].count };
    const restaurants = await fetchRestaurantsNear(key, center);
    const added = await applyFoodIncludes(key, center, restaurants, foodIncludes);
    buckets[key] = restaurants;
    processedTargets += 1;
    console.log(`🍽 ${key}：${restaurants.length} 間餐廳${added ? `（含指名補充 ${added} 間）` : ''}`);
  }
  if (partial) console.log(`Crawl-food 部分更新：本次更新 ${processedTargets} 個目的地，略過 ${slicedTargets} 個（切片），API 呼叫 ${apiCallsUsed} 次。`);

  const total = Object.keys(buckets).reduce((n, k) => n + buckets[k].length, 0);

  // 保險：抓到 0 間（多半是金鑰 403／API 掛掉）而既有檔案有資料時，不可覆寫——
  // 2026-07-12 排程曾因 weather.env.js 換成瀏覽器限制鍵、Places 全 403，把整檔洗成空。
  if (total === 0) {
    const existing = loadExistingGlobalFile(RESTAURANT_DATA_PATH, 'WAI_RESTAURANT_DATA');
    const existingTotal = Object.keys(existing)
      .filter((k) => k !== '__generatedAt')
      .reduce((n, k) => n + (Array.isArray(existing[k]) ? existing[k].length : 0), 0);
    if (existingTotal > 0) {
      console.error(`Crawl-food 全部目的地 0 間（疑似金鑰/API 故障），既有檔案有 ${existingTotal} 間 — 中止寫檔以保護資料。`);
      process.exitCode = 1; // 讓排程 log 看得出這次是失敗，而不是「成功寫入 0 間」
      return;
    }
  }

  const payload = Object.assign({ __generatedAt: new Date().toISOString() }, buckets);
  const fileBody = `// Auto-generated by crawler (npm run crawl:food). Do not edit by hand.\n`
    + `window.WAI_RESTAURANT_DATA = ${JSON.stringify(payload, null, 2)};\n`;

  if (DRY) {
    console.log(`DRY crawl-food; would write ${RESTAURANT_DATA_PATH}（${total} 間）`);
    console.log(fileBody.slice(0, 800) + (fileBody.length > 800 ? '\n... (truncated)' : ''));
    return;
  }
  fs.writeFileSync(RESTAURANT_DATA_PATH, fileBody, 'utf8');
  console.log(`Crawl-food wrote ${RESTAURANT_DATA_PATH}（${total} 間餐廳，跨 ${targets.length} 個目的地）`);
}

// 清理「孤兒共編行程」：collab 空殼（只開了 lobby、從沒進精靈規劃 → 無 wizardData.dest）
// 且超過 N 天沒更新者，刪 micro_trips doc + 對應 invites（admin 繞過規則可硬刪）。
function tsToMillis(ts) {
  if (!ts) return 0;
  if (typeof ts.toMillis === 'function') return ts.toMillis();
  if (typeof ts.seconds === 'number') return ts.seconds * 1000;
  const n = Date.parse(ts);
  return Number.isFinite(n) ? n : 0;
}
async function cleanupCollab(db) {
  console.log(`Cleanup-collab mode：刪除「無 wizardData.dest 且 ${CLEANUP_DAYS} 天未更新」的共編空殼（dryRun=${DRY}）`);
  const snap = await db.collection('micro_trips').where('collab', '==', true).get();
  if (snap.empty) { console.log('沒有 collab 行程。'); return; }
  const cutoff = Date.now() - CLEANUP_DAYS * 24 * 60 * 60 * 1000;
  let scanned = 0, deleted = 0, kept = 0;
  for (const doc of snap.docs) {
    scanned += 1;
    const data = doc.data();
    const planned = !!(data.wizardData && data.wizardData.dest);
    const lastTouch = Math.max(tsToMillis(data.updatedAt), tsToMillis(data.collabCreatedAt));
    const stale = lastTouch > 0 ? lastTouch < cutoff : true; // 無時間戳視為舊資料，也清
    if (planned || !stale) { kept += 1; continue; }
    console.log(`🗑 孤兒空殼：${doc.id}（owner=${data.ownerEmail || '?'}，invite=${data.inviteCode || '-'}）`);
    if (!DRY) {
      if (data.inviteCode) {
        try { await db.collection('invites').doc(String(data.inviteCode).toUpperCase().replace(/[^A-Z0-9]/g, '')).delete(); } catch (e) { /* ignore */ }
      }
      await doc.ref.delete();
    }
    deleted += 1;
  }
  console.log('Cleanup-collab summary', { scanned, deleted, kept, days: CLEANUP_DAYS, dryRun: DRY });
}

async function main() {
  const db = initFirebase();
  if (CLEANUP_COLLAB_MODE) {
    await cleanupCollab(db);
  } else if (CRAWL_FOOD_MODE) {
    await crawlFood(db);
  } else if (CRAWL_PARKING_MODE) {
    // --source=gov｜osm｜all（預設 all）。gov 這條在沒有 GOOGLE_MAPS_API_KEY 時會自己略過，
    // 而 OSM 完全不需要金鑰，所以預設兩條都跑：沒有金鑰的環境至少還拿得到 OSM 資料。
    const source = String(argv.source || 'all').toLowerCase();
    if (source === 'gov' || source === 'all') await crawlParking(db);
    if (source === 'osm' || source === 'all') await crawlParkingOsm(db);
    if (!['gov', 'osm', 'all'].includes(source)) {
      throw new Error(`未知的 --source=${source}（可用：gov / osm / all）`);
    }
  } else if (VERIFY_PLACES_MODE) {
    await verifyPlaces(db);
  } else if (ENRICH_FEES_MODE) {
    await enrichFees(db);
  } else if (EXPORT_LOCAL_MODE) {
    await exportLocal(db);
  } else if (IMPORT_MODE) {
    await processImport(db);
  } else {
    await processBatch(db);
  }
  process.exit(0);
}

main().catch((e) => {
  console.error(e);
  process.exit(1);
});
