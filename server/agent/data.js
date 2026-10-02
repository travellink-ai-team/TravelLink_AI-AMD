'use strict';
/* ══════════════════════════════════════════════════
   Agent 資料層：直接讀前端同一份本地資料（local-first）
   ──────────────────────────────────────────────────
   app/poi-data.js、restaurant-data.js 是給 <script src> 用的
   window.* 檔案，這裡用 vm 跑在假的 window 上讀出來，
   確保 Agent 與前端看到的是同一份資料，不另外維護一份。
   營業時間解析共用 app/business-hours.js（本來就支援 Node）。
   ══════════════════════════════════════════════════ */
const fs = require('fs');
const path = require('path');
const vm = require('vm');

const APP_DIR = path.join(__dirname, '..', '..', 'app');
const HOURS = require(path.join(APP_DIR, 'business-hours.js'));

function loadWindowFile(file, key) {
  const sandbox = { window: {} };
  vm.runInNewContext(fs.readFileSync(path.join(APP_DIR, file), 'utf8'), sandbox, { filename: file });
  return sandbox.window[key] || {};
}

const normName = (s) => String(s || '').replace(/臺/g, '台').replace(/\s+/g, '').toLowerCase();

function flatten(buckets, kindDefault) {
  const out = [];
  const seen = new Set();
  for (const [region, list] of Object.entries(buckets)) {
    if (!Array.isArray(list)) continue;
    for (const p of list) {
      if (!p || !p.name || !Number.isFinite(p.lat) || !Number.isFinite(p.lng)) continue;
      const key = normName(p.name);
      if (seen.has(key)) continue;
      seen.add(key);
      out.push({ ...p, region: region.replace(/臺/g, '台'), kind: p.kind || kindDefault, _key: key });
    }
  }
  return out;
}

let cache = null;
function data() {
  if (cache) return cache;
  const pois = flatten(loadWindowFile('poi-data.js', 'WAI_POI_DATA'), 'scenic');
  const foods = flatten(loadWindowFile('restaurant-data.js', 'WAI_RESTAURANT_DATA'), 'food')
    .map((r) => ({ ...r, kind: 'food' }));
  // 同一家店可能同時出現在兩份資料：以餐廳資料為準（有 costPerPerson）
  const byKey = new Map();
  for (const p of foods.concat(pois)) if (!byKey.has(p._key)) byKey.set(p._key, p);
  cache = { pois: pois.filter((p) => p.kind !== 'food'), foods, byKey };
  return cache;
}

/** 用名稱找本地地點：先完全相符，再找「互相包含」的唯一一筆，找不到回 null（不猜） */
function findPlace(name) {
  const key = normName(name);
  if (!key) return null;
  const { byKey } = data();
  if (byKey.has(key)) return byKey.get(key);
  const hits = [];
  for (const p of byKey.values()) {
    if (p._key.includes(key) || key.includes(p._key)) hits.push(p);
    if (hits.length > 1) return null;
  }
  return hits[0] || null;
}

/* ── 室內／戶外：本地資料沒有這個欄位，用名稱與簡介推估 ──
   刻意保守：兩邊都對不到就回 unknown，Agent 不能把 unknown 當室內推薦。 */
const INDOOR_RE = /館|博物|美術|展覽|文創|糖廠|書店|故事|紀念館|教堂|廟|宮|寺|藝文|工坊|遊客中心|商場|百貨|咖啡/;
const OUTDOOR_RE = /步道|海灘|沙灘|海岸|瀑布|森林|公園|山|湖|溪|溫泉|牧場|農場|觀景|燈塔|岬|濕地|自行車|單車|海濱|港|浮潛|潮間帶|草原|部落|大橋|休憩區/;
function indoorOf(place) {
  if (!place) return 'unknown';
  if (place.kind === 'food') return 'indoor';
  const name = String(place.name || '');
  if (INDOOR_RE.test(name) && !/公園|森林|步道/.test(name)) return 'indoor';
  if (OUTDOOR_RE.test(name)) return 'outdoor';
  const desc = String(place.desc || '');
  if (INDOOR_RE.test(desc) && !OUTDOOR_RE.test(desc)) return 'indoor';
  if (OUTDOOR_RE.test(desc)) return 'outdoor';
  return 'unknown';
}

/**
 * 營業狀態，另外補上 windows（一天內的多個時段）。
 * business-hours.js 只回第一段（例如美術館「09:00–12:00, 13:30–17:00」只讀到上午），
 * 前端共用那支不動；Agent 這裡自己把同一行的所有時段拆出來，免得把下午有開的館判成沒開。
 */
function hoursOn(place, isoDate) {
  if (!place || !place.businessHours) return { status: 'unknown', label: '', windows: [] };
  const h = HOURS.parseDayStatus(place.businessHours, isoDate);
  if (h.status !== 'open') return { ...h, windows: [] };
  const windows = [];
  const re = /(\d{1,2}):(\d{2})\s*[–\-~～至]\s*(\d{1,2}):(\d{2})/g;
  let m;
  while ((m = re.exec(String(h.label || '')))) {
    const open = Number(m[1]) * 60 + Number(m[2]);
    let close = Number(m[3]) * 60 + Number(m[4]);
    if (close <= open) close += 1440;   // 跨午夜
    windows.push({ open, close });
  }
  if (!windows.length) windows.push({ open: h.open, close: h.close });
  return { ...h, open: windows[0].open, close: windows[windows.length - 1].close, windows };
}

/** start～end（分鐘）是否完整落在某一個營業時段內 */
function fitsHours(h, start, end) {
  return h.windows.some((w) => start >= w.open && end <= w.close);
}

const fmtMin = (min) => `${String(Math.floor(min / 60) % 24).padStart(2, '0')}:${String(min % 60).padStart(2, '0')}`;
function hoursText(h) {
  if (h.status !== 'open') return h.status;
  if (h.windows.length === 1 && h.windows[0].open === 0 && h.windows[0].close >= 1440) return '全天';
  return h.windows.map((w) => `${fmtMin(w.open)}–${fmtMin(Math.min(w.close, 1439))}`).join(', ');
}

function accessOf(place) {
  return HOURS.classifyAccess(place || {});
}

/* ── 距離與車程（不打 Directions：直線 × 1.35 路網係數，市區外平均 40km/h） ── */
function haversineKm(a, b) {
  const R = 6371, rad = (x) => (x * Math.PI) / 180;
  const dLat = rad(b.lat - a.lat), dLng = rad(b.lng - a.lng);
  const s = Math.sin(dLat / 2) ** 2 + Math.cos(rad(a.lat)) * Math.cos(rad(b.lat)) * Math.sin(dLng / 2) ** 2;
  return 2 * R * Math.asin(Math.sqrt(s));
}
function travelMinutes(a, b) {
  if (!a || !b || !Number.isFinite(a.lat) || !Number.isFinite(b.lat)) return 15;
  const km = haversineKm(a, b) * 1.35;
  return Math.max(5, Math.round(km / 40 * 60 + 5));   // +5 分鐘停車與步行
}

/* ── 費用：門票（poi-data 的 fee）與餐費（restaurant-data 的 costPerPerson），每人 ── */
function costOf(place) {
  if (!place) return 0;
  if (place.kind === 'food') return Number(place.costPerPerson) || 0;
  return Number.isFinite(Number(place.fee)) ? Number(place.fee) : 0;
}

/* ── 天氣：CWA 一週預報（F-D0047-091，縣市 12 小時一段），可被 demo 情境覆蓋 ── */
const CWA_API_KEY = (process.env.CWA_API_KEY || '').trim();
const CWA_COUNTY = { 台東: '臺東縣', 綠島: '臺東縣', 蘭嶼: '臺東縣' };
const weatherCache = new Map();

async function fetchCwaPeriods(region) {
  const county = CWA_COUNTY[String(region || '').replace(/臺/g, '台')] || '臺東縣';
  const hit = weatherCache.get(county);
  if (hit && hit.exp > Date.now()) return hit.periods;
  if (!CWA_API_KEY) return null;
  const url = new URL('https://opendata.cwa.gov.tw/api/v1/rest/datastore/F-D0047-091');
  url.searchParams.set('LocationName', county);
  url.searchParams.set('Authorization', CWA_API_KEY);
  try {
    const r = await fetch(url, { signal: AbortSignal.timeout(8000) });
    if (!r.ok) return null;
    const json = await r.json();
    const locs = (json.records && json.records.Locations && json.records.Locations[0] && json.records.Locations[0].Location) || [];
    const loc = locs.find((x) => x.LocationName === county) || locs[0];
    if (!loc || !Array.isArray(loc.WeatherElement)) return null;
    const byName = {};
    loc.WeatherElement.forEach((e) => { byName[e.ElementName] = e.Time || []; });
    const popOf = (start) => {
      const m = (byName['12小時降雨機率'] || []).find((x) => x.StartTime === start);
      return Number(m && m.ElementValue && m.ElementValue[0] && m.ElementValue[0].ProbabilityOfPrecipitation) || 0;
    };
    const periods = (byName['天氣現象'] || []).map((t) => ({
      start: String(t.StartTime || '').slice(0, 16),
      end: String(t.EndTime || '').slice(0, 16),
      wx: (t.ElementValue && t.ElementValue[0] && t.ElementValue[0].Weather) || '',
      pop: popOf(t.StartTime)
    })).filter((p) => p.start);
    weatherCache.set(county, { exp: Date.now() + 10 * 60 * 1000, periods });
    return periods;
  } catch (_e) {
    return null;
  }
}

/**
 * 回傳 { source, periods[] }。scenario.rain = { date, from, to, pop } 會蓋過該時段（demo 情境注入）。
 * 注入只改工具回傳值，Agent 流程完全真實執行。
 */
async function getForecast(region, scenario) {
  let periods = await fetchCwaPeriods(region);
  let source = periods ? 'cwa' : 'unavailable';
  periods = periods ? periods.slice() : [];
  const rain = scenario && scenario.rain;
  if (rain && /^\d{4}-\d{2}-\d{2}$/.test(rain.date)) {
    const from = /^\d{2}:\d{2}$/.test(rain.from) ? rain.from : '12:00';
    const to = /^\d{2}:\d{2}$/.test(rain.to) ? rain.to : '18:00';
    const pop = Math.min(100, Math.max(0, Number(rain.pop) || 80));
    periods = periods.filter((p) => !(p.start < `${rain.date}T${to}` && p.end > `${rain.date}T${from}`));
    periods.push({ start: `${rain.date}T${from}`, end: `${rain.date}T${to}`, wx: '短暫陣雨或雷雨', pop, simulated: true });
    periods.sort((a, b) => a.start.localeCompare(b.start));
    source = source === 'cwa' ? 'cwa+simulated' : 'simulated';
  }
  return { source, periods };
}

/** 某日某時刻（分鐘）的降雨機率；沒有預報回 null（未知，不可當成不下雨也不可當成下雨） */
function popAt(forecast, isoDate, minute) {
  const hh = String(Math.floor(minute / 60) % 24).padStart(2, '0');
  const mm = String(minute % 60).padStart(2, '0');
  const t = `${isoDate}T${hh}:${mm}`;
  const p = (forecast && forecast.periods || []).find((x) => x.start <= t && t < x.end);
  return p ? { pop: p.pop, wx: p.wx, simulated: Boolean(p.simulated) } : null;
}

module.exports = {
  data, findPlace, normName, indoorOf, hoursOn, fitsHours, hoursText, accessOf,
  haversineKm, travelMinutes, costOf, getForecast, popAt
};
