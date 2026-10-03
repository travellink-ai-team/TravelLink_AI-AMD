'use strict';
/* ══════════════════════════════════════════════════
   海象與停航風險（情境 A：綠島／蘭嶼船班）
   ──────────────────────────────────────────────────
   資料源研究見 docs/amd-agent/ferry-sea-data-research.md：
   - CWA F-A0012-001 海面天氣預報（檔案 API）：「綠島蘭嶼海面」5 天浪高／風力，主要依據
   - CWA W-C0034-001 颱風警報：有海上警報就判定高風險
   - TDX 航運 Alert（台東）：官方通阻公告。平常是空的，有資料時優先採用
   台東的 TDX 班表與即時到離港都是空的，所以停航只能「依預報推估」——
   工具回傳一律附上原始描述與「以船公司公告為準」。
   ══════════════════════════════════════════════════ */

const CWA_API_KEY = (process.env.CWA_API_KEY || '').trim();
const TDX_APP_ID = (process.env.TDX_APP_ID || '').trim();
const TDX_APP_KEY = (process.env.TDX_APP_KEY || '').trim();
const CACHE_MS = 10 * 60 * 1000;   // TDX 金鑰與正式站共用、有每分鐘上限，一定要快取

// 停航風險門檻：沒有官方標準，依新聞報導歸納（台東往返綠島、蘭嶼約在浪高 3～3.5 公尺停航）
const THRESHOLDS = {
  high: { waveM: 3, windLevel: 7 },
  medium: { waveM: 2, gustLevel: 8 }
};

// 海域：離島航線看「綠島蘭嶼海面」
const SEA_AREA = { 綠島: '綠島蘭嶼海面', 蘭嶼: '綠島蘭嶼海面' };

/* ── 中文描述解析 ── */
function waveMaxOf(desc) {
  const nums = String(desc || '').match(/\d+(?:\.\d+)?/g);
  return nums ? Math.max(...nums.map(Number)) : null;
}
// 「4至5陣風7級晨轉5至6陣風8級」→ 平均風 6、陣風 8
function windOf(desc) {
  const s = String(desc || '');
  const gusts = [...s.matchAll(/陣風(\d+)/g)].map((m) => Number(m[1]));
  const sustained = (s.replace(/陣風\d+級?(?:以下)?/g, ' ').match(/\d+/g) || []).map(Number);
  return {
    windMax: sustained.length ? Math.max(...sustained) : null,
    gustMax: gusts.length ? Math.max(...gusts) : null
  };
}

function riskOf({ waveMaxM, windMax, gustMax, waveType, typhoon }) {
  const reasons = [];
  if (typhoon) reasons.push('颱風海上警報');
  if (waveMaxM !== null && waveMaxM >= THRESHOLDS.high.waveM) reasons.push(`浪高最高 ${waveMaxM} 公尺（≥ ${THRESHOLDS.high.waveM}）`);
  if (windMax !== null && windMax >= THRESHOLDS.high.windLevel) reasons.push(`平均風 ${windMax} 級（≥ ${THRESHOLDS.high.windLevel}）`);
  if (reasons.length) return { risk: 'high', reasons };
  if (waveMaxM !== null && waveMaxM >= THRESHOLDS.medium.waveM) reasons.push(`浪高最高 ${waveMaxM} 公尺（≥ ${THRESHOLDS.medium.waveM}）`);
  if (gustMax !== null && gustMax >= THRESHOLDS.medium.gustLevel) reasons.push(`陣風 ${gustMax} 級（≥ ${THRESHOLDS.medium.gustLevel}）`);
  if (/大浪|巨浪|狂浪/.test(waveType || '')) reasons.push(`浪型「${waveType}」`);
  if (reasons.length) return { risk: 'medium', reasons };
  if (waveMaxM === null && windMax === null) return { risk: 'unknown', reasons: ['沒有預報資料'] };
  return { risk: 'low', reasons: [] };
}

/* ── CWA 海面天氣預報 ── */
const cache = new Map();
async function cached(key, fn) {
  const hit = cache.get(key);
  if (hit && hit.exp > Date.now()) return hit.value;
  const value = await fn();
  if (value !== null) cache.set(key, { exp: Date.now() + CACHE_MS, value });
  return value;
}

async function fetchSeaAreas() {
  if (!CWA_API_KEY) return null;
  return cached('F-A0012-001', async () => {
    try {
      const url = `https://opendata.cwa.gov.tw/fileapi/v1/opendataapi/F-A0012-001?Authorization=${encodeURIComponent(CWA_API_KEY)}&downloadType=WEB&format=JSON`;
      const r = await fetch(url, { signal: AbortSignal.timeout(10000) });
      if (!r.ok) return null;
      const j = await r.json();
      const locs = j && j.cwaopendata && j.cwaopendata.Dataset && j.cwaopendata.Dataset.Locations && j.cwaopendata.Dataset.Locations.Location;
      if (!Array.isArray(locs)) return null;
      const areas = {};
      for (const loc of locs) {
        const periods = {};
        for (const el of (loc.WeatherElement || [])) {
          for (const t of (el.Time || [])) {
            const key = String(t.StartTime || '').slice(0, 16);
            const p = periods[key] || (periods[key] = { start: key, end: String(t.EndTime || '').slice(0, 16) });
            const v = t.ElementValue || {};
            if (v.WaveHeightDescription) p.wave = v.WaveHeightDescription;
            if (v.BeaufortScaleDescription) p.wind = v.BeaufortScaleDescription;
            if (v.WaveTypeDescription) p.waveType = v.WaveTypeDescription;
            if (v.Weather) p.weather = v.Weather;
          }
        }
        areas[loc.LocationName] = Object.values(periods).sort((a, b) => a.start.localeCompare(b.start));
      }
      return { issuedAt: j.cwaopendata.Sent || '', areas };
    } catch (_e) {
      return null;
    }
  });
}

/** 颱風海上警報：有颱風時才有內容，結構難以事先驗證，用文字比對「海上」與東南部海面／綠島／蘭嶼 */
async function fetchTyphoonSeaWarning() {
  if (!CWA_API_KEY) return null;
  return cached('W-C0034-001', async () => {
    try {
      const url = `https://opendata.cwa.gov.tw/api/v1/rest/datastore/W-C0034-001?Authorization=${encodeURIComponent(CWA_API_KEY)}&format=JSON`;
      const r = await fetch(url, { signal: AbortSignal.timeout(8000) });
      if (!r.ok) return null;
      const text = JSON.stringify(await r.json());
      const active = /海上/.test(text) && /東南部海面|綠島|蘭嶼|臺東|台東/.test(text);
      return { active };
    } catch (_e) {
      return null;
    }
  });
}

/* ── TDX 官方通阻公告（台東）── */
let tdxToken = null, tdxTokenExp = 0;
async function fetchTdxAlerts() {
  if (!TDX_APP_ID || !TDX_APP_KEY) return null;
  return cached('tdx-ship-alert', async () => {
    try {
      if (!tdxToken || Date.now() > tdxTokenExp) {
        const t = await fetch('https://tdx.transportdata.tw/auth/realms/TDXConnect/protocol/openid-connect/token', {
          method: 'POST',
          headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
          body: new URLSearchParams({ grant_type: 'client_credentials', client_id: TDX_APP_ID, client_secret: TDX_APP_KEY }),
          signal: AbortSignal.timeout(8000)
        }).then((r) => r.json());
        tdxToken = t.access_token || null;
        tdxTokenExp = Date.now() + Math.max(60, (Number(t.expires_in) || 3600) - 300) * 1000;
      }
      if (!tdxToken) return null;
      const r = await fetch('https://tdx.transportdata.tw/api/basic/v3/Ship/Alert/Domestic/City/TaitungCounty?$format=JSON', {
        headers: { Authorization: 'Bearer ' + tdxToken }, signal: AbortSignal.timeout(8000)
      });
      if (!r.ok) return null;   // 429 等一律當作「查不到」，不影響推估
      const j = await r.json();
      return (j.Items || j.Alerts || []).map((a) => ({
        title: String(a.Title || ''), description: String(a.Description || '').slice(0, 200),
        status: a.Status, start: a.StartTime, end: a.EndTime, url: a.AlertURL || ''
      }));
    } catch (_e) {
      return null;
    }
  });
}

/**
 * 某離島在某日的海況與停航風險。
 * scenario.sea = { date, waveMaxM, gustMax?, windMax? } 會覆蓋該日（demo 情境注入）；
 * 也可以給陣列，一次覆蓋好幾天（測試要固定每一天，不能跟著真實天氣變）。
 */
async function seaOn(island, isoDate, scenario) {
  const area = SEA_AREA[island] || '綠島蘭嶼海面';
  const injList = scenario && scenario.sea ? [].concat(scenario.sea) : [];
  const inj = injList.find((x) => x && x.date === isoDate);
  if (inj) {
    const waveMaxM = Number(inj.waveMaxM) || 3.5;
    // 沒給風力時依浪高推一個合理值：注入「平靜」的日子不該因為預設陣風 9 級變成中風險
    const rough = waveMaxM >= 3;
    const gustMax = Number(inj.gustMax) || (rough ? 9 : 5);
    const windMax = Number(inj.windMax) || (rough ? 6 : 3);
    const waveType = waveMaxM >= 4 ? '大浪轉巨浪' : (rough ? '中浪轉大浪' : '小浪');
    return {
      date: isoDate, area, simulated: true,
      wave: `${Math.max(1, waveMaxM - 1)}轉${waveMaxM}公尺`, wind: `${windMax}級陣風${gustMax}級`, waveType,
      waveMaxM, windMax, gustMax, ...riskOf({ waveMaxM, windMax, gustMax, waveType })
    };
  }
  const [fc, ty] = await Promise.all([fetchSeaAreas(), fetchTyphoonSeaWarning()]);
  const periods = fc && fc.areas[area];
  const p = periods && periods.find((x) => x.start.slice(0, 10) <= isoDate && isoDate < x.end.slice(0, 10))
    || (periods && periods.find((x) => x.start.slice(0, 10) === isoDate));
  if (!p) {
    return { date: isoDate, area, risk: ty && ty.active ? 'high' : 'unknown', reasons: ty && ty.active ? ['颱風海上警報'] : ['超出 5 天海面預報範圍或拿不到預報'] };
  }
  const waveMaxM = waveMaxOf(p.wave);
  const { windMax, gustMax } = windOf(p.wind);
  return {
    date: isoDate, area, issuedAt: fc.issuedAt,
    wave: p.wave || '', wind: p.wind || '', waveType: p.waveType || '', weather: p.weather || '',
    waveMaxM, windMax, gustMax,
    ...riskOf({ waveMaxM, windMax, gustMax, waveType: p.waveType, typhoon: Boolean(ty && ty.active) })
  };
}

// 離島停航風險（海象工具、move_ferry、停航檢查）目前不對使用者開放：2026-10-03 決定先停用，程式保留。
// 要打開：server/.env 設 AGENT_SEA_RISK=on，重啟代理。
const enabled = () => String(process.env.AGENT_SEA_RISK || '').trim().toLowerCase() === 'on';

module.exports = { enabled, seaOn, fetchTdxAlerts, waveMaxOf, windOf, riskOf, THRESHOLDS };
