'use strict';
/* ══════════════════════════════════════════════════
   主動式旅途管家：輕量預檢（不呼叫 LLM）
   ──────────────────────────────────────────────────
   用戶端（網頁、App）在背景偵測到「可能有問題」時，先問這裡：
   用跟 /api/agent/replan 一模一樣的程式檢查（天氣、公休、營業時間、延誤順延後的期限），
   只回答「值不值得提醒使用者」，不開 SSE、不呼叫 AI、不產生提案。
   這樣提示列跳出來之後，使用者點開送 replan，才不會得到「程式檢查沒發現問題」。
   ══════════════════════════════════════════════════ */
const D = require('./data');
const I = require('./itinerary');
const SEA = require('./sea');
const DL = require('./delay');

// 跟 loop.js 的「程式先檢查」同一組：這些才會讓 replan 啟動 AI（其他違規例如 overlap 是行程本身的問題）
const WEATHER_CODES = ['rain', 'closed', 'hours', 'suspended', 'ferry_risk'];

function pick(v) {
  return { code: v.code, stopId: v.stopId || null, day: v.day || null, stop: v.stop || null, message: String(v.message || '') };
}

// 提示列用的一句話：降雨優先（「第 1 天 14:00 起降雨 80%」），其次公休，再來第一個問題的原文
function headlineOf(trip, issues) {
  const rain = issues.filter((v) => v.code === 'rain');
  if (rain.length) {
    const first = rain
      .map((v) => trip.stops.find((s) => s.id === v.stopId))
      .filter(Boolean)
      .sort((a, b) => (a.day - b.day) || a.time.localeCompare(b.time))[0];
    const pop = Math.max(...rain.map((v) => Number((/降雨機率 (\d+)%/.exec(v.message) || [])[1]) || 0));
    if (first) return `${trip.days > 1 ? `第 ${first.day} 天 ` : ''}${first.time} 起降雨機率 ${pop}%，${rain.length} 個戶外站受影響`;
  }
  const closed = issues.find((v) => v.code === 'closed' || v.code === 'suspended');
  if (closed) return closed.code === 'closed' ? `「${closed.stop}」當天公休` : `「${closed.stop}」目前暫停開放`;
  return issues.length ? issues[0].message : '';
}

/**
 * @returns {Promise<{ alert: boolean, kind: string, issues: object[], headline: string, simulated: boolean, weather?: string, error?: string }>}
 */
async function precheck({ trip: rawTrip, trigger, scenario }) {
  const type = trigger && trigger.type === 'delay' ? 'delay' : 'weather';

  if (type === 'delay') {
    const req = DL.parseDelayRequest(trigger, rawTrip, scenario);
    if (req.error) return { alert: false, kind: 'delay', issues: [], headline: '', simulated: false, error: req.error };
    const trip = I.normalizeTrip(rawTrip);
    const base = DL.dayTrip(trip, req);
    if (!base.stops.length) return { alert: false, kind: 'delay', issues: [], headline: '', simulated: req.simulated };
    const res = await DL.check(DL.shift(base, req), base, req);
    const issues = (res.violations || []).map(pick);
    return {
      alert: issues.length > 0,
      kind: 'delay',
      issues,
      headline: issues.length ? `延誤 ${req.delayMin != null ? `${req.delayMin} 分鐘` : ''}後有 ${issues.length} 個衝突：${issues[0].message}` : '',
      simulated: Boolean(req.simulated)
    };
  }

  const trip = I.normalizeTrip(rawTrip);
  if (!trip.stops.length) return { alert: false, kind: 'weather', issues: [], headline: '', simulated: false, error: '行程沒有任何站點' };
  const forecast = await D.getForecast(trip.region, scenario);
  let sea = null;
  if (SEA.enabled() && trip.island) {
    sea = {};
    for (let d = 1; d <= Math.min(14, trip.days + 1); d++) {
      const date = I.dateOfDay(trip.startDate, d);
      sea[date] = await SEA.seaOn(trip.island, date, scenario);
    }
  }
  const res = I.validate(trip, forecast.periods.length ? forecast : null, sea);
  // 車站、住宿、港口淋到雨不需要改行程（validate 會把它們算成「室內外不明」）：主動提醒不為它們打擾使用者
  const anchorIds = new Set(trip.stops.filter((s) => s.anchor || s.stopType === 'transit').map((s) => s.id));
  const issues = res.violations
    .filter((v) => WEATHER_CODES.includes(v.code) && !(v.code === 'rain' && anchorIds.has(v.stopId)))
    .map(pick);
  return {
    alert: issues.length > 0,
    kind: issues.some((v) => v.code === 'rain') ? 'weather' : (issues.length ? 'closed' : 'weather'),
    issues,
    headline: headlineOf(trip, issues),
    simulated: Boolean(scenario && scenario.rain),
    weather: forecast.source
  };
}

module.exports = { precheck, WEATHER_CODES };
