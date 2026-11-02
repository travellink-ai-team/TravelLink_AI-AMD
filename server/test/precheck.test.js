// 主動式旅途管家：輕量預檢（agent/precheck.js、POST /api/agent/precheck）
//   node test/precheck.test.js     離線：不打 CWA（清掉金鑰，只用 demo 降雨情境）、不呼叫 AI
'use strict';
const path = require('path');
require('dotenv').config({ path: path.join(__dirname, '..', '.env') });
process.env.AI_PROVIDER = 'off';
process.env.CWA_API_KEY = '';   // data.js 在 require 時讀金鑰：先清掉，天氣只來自 scenario.rain
process.env.AGENT_SEA_RISK = '';

const { precheck } = require('../agent/precheck');
const { sanitizeScenario, mount } = require('../agent/precheck-route');

let pass = 0, fail = 0;
const ok = (name, cond, extra) => { if (cond) { pass++; console.log('PASS  ' + name); } else { fail++; console.log('FAIL  ' + name + (extra ? '\n      ' + extra : '')); } };
const clone = (x) => JSON.parse(JSON.stringify(x));

// 2026-11-07（六）。與 delay.test.js 同一份行程
const DAY = '2026-11-07';
const TRIP = {
  title: '台東市區一日遊', region: '台東', startDate: DAY, endTime: '21:00', people: 2,
  stops: [
    { id: 'cstop-a1', day: 1, time: '15:00', stayMin: 60, name: '加路蘭', stopType: 'scenic' },
    { id: 'cstop-b2', day: 1, time: '16:20', stayMin: 30, name: '臺東美術館', stopType: 'scenic' },
    { id: 'web_3_榕樹下米苔目', day: 1, time: '18:00', stayMin: 60, name: '榕樹下米苔目(中華路創始老店-別無分店)', stopType: 'food' },
    { id: 'cstop-d4', day: 1, time: '19:20', stayMin: 20, name: '台東車站', anchor: 'station' }
  ]
};
const RAIN = { rain: { date: DAY, from: '13:00', to: '17:00', pop: 80 } };
const delayTrigger = (delayMin) => {
  const leave = 14 * 60 + 30 + delayMin;
  const hh = String(Math.floor(leave / 60)).padStart(2, '0');
  const mm = String(leave % 60).padStart(2, '0');
  return {
    type: 'delay', source: 'web', day: 1, now: `${DAY}T14:20:00+08:00`,
    from: { stopId: 'cstop-f0', name: '臺東森林公園', lat: 22.7698, lng: 121.1608, leaveAt: `${DAY}T${hh}:${mm}:00+08:00`, delayMin }
  };
};

(async () => {
  // ── 天氣 ──
  const noWx = await precheck({ trip: TRIP, trigger: { type: 'weather' } });
  ok('拿不到預報 → 不提醒（不能把「未知」當成下雨）', noWx.alert === false && noWx.weather === 'unavailable', JSON.stringify(noWx));

  const rain = await precheck({ trip: TRIP, trigger: { type: 'weather' }, scenario: RAIN });
  ok('模擬 13–17 點降雨 80% → 提醒', rain.alert === true && rain.kind === 'weather', JSON.stringify(rain));
  ok('戶外的加路蘭被列出', rain.issues.some((v) => v.code === 'rain' && v.stopId === 'cstop-a1'), JSON.stringify(rain.issues));
  ok('室內的美術館不算', !rain.issues.some((v) => v.code === 'rain' && v.stopId === 'cstop-b2'));
  ok('提示列文字含時間與降雨機率', /15:00 起降雨機率 80%/.test(rain.headline), rain.headline);
  ok('標記模擬情境', rain.simulated === true);

  const light = await precheck({ trip: TRIP, trigger: { type: 'weather' }, scenario: { rain: { ...RAIN.rain, pop: 30 } } });
  ok('降雨 30% → 不提醒（與 replan 的 50% 門檻一致）', light.alert === false, JSON.stringify(light.issues));

  const later = await precheck({ trip: TRIP, trigger: { type: 'weather' }, scenario: { rain: { date: DAY, from: '19:00', to: '22:00', pop: 90 } } });
  ok('雨只落在回程車站（錨點）的時段 → 不提醒', later.alert === false, JSON.stringify(later.issues));
  const after = await precheck({ trip: TRIP, trigger: { type: 'weather' }, scenario: { rain: { date: DAY, from: '20:00', to: '23:00', pop: 90 } } });
  ok('雨下在行程結束後 → 不提醒', after.alert === false, JSON.stringify(after.issues));

  // ── 公休 ──
  const closedTrip = clone(TRIP);
  closedTrip.stops[0].businessHours = '星期一: 09:00–17:00\n星期二: 09:00–17:00\n星期三: 09:00–17:00\n星期四: 09:00–17:00\n星期五: 09:00–17:00\n星期六: 休息\n星期日: 09:00–17:00';
  const closed = await precheck({ trip: closedTrip, trigger: { type: 'weather' } });
  ok('用戶端送的營業時間：星期六休息 → 公休提醒', closed.alert === true && closed.kind === 'closed' && closed.issues[0].code === 'closed', JSON.stringify(closed));
  ok('公休的提示列文字', /加路蘭」當天公休/.test(closed.headline), closed.headline);

  // ── 延誤 ──
  const small = await precheck({ trip: TRIP, trigger: delayTrigger(10) });
  ok('延誤 10 分鐘 → 順延排得下，不提醒', small.alert === false && small.kind === 'delay', JSON.stringify(small));
  const big = await precheck({ trip: TRIP, trigger: delayTrigger(90) });
  ok('延誤 90 分鐘 → 有衝突，提醒', big.alert === true && big.issues.length > 0, JSON.stringify(big));
  ok('延誤提示列寫出分鐘數', /延誤 90 分鐘/.test(big.headline), big.headline);
  const bad = await precheck({ trip: TRIP, trigger: { ...delayTrigger(10), now: `${DAY}T14:20:00` } });
  ok('延誤請求格式錯（沒帶時區）→ 回 error、不提醒', bad.alert === false && /時區/.test(bad.error || ''), JSON.stringify(bad));

  // ── 路由：情境清理與 HTTP 行為（不起伺服器，直接呼叫 handler）──
  ok('scenario 只留 rain／delay', JSON.stringify(sanitizeScenario({ rain: { date: DAY, from: '13:00', to: '17:00', pop: '70' }, foo: 1 })) === JSON.stringify({ rain: { date: DAY, from: '13:00', to: '17:00', pop: 70 } }));
  ok('沒有情境 → null', sanitizeScenario({}) === null && sanitizeScenario(null) === null);
  const routes = {};
  mount({ post: (p, ...h) => { routes[p] = h[h.length - 1]; } }, { requireFirebaseUser: () => {}, rateLimit: () => () => {} });
  const call = (body) => new Promise((resolve) => {
    const res = { statusCode: 200, headers: {}, status(c) { this.statusCode = c; return this; }, set(k, v) { this.headers[k] = v; return this; }, json(j) { resolve({ status: this.statusCode, body: j }); return this; } };
    routes['/api/agent/precheck']({ body }, res);
  });
  ok('掛上 /api/agent/precheck', typeof routes['/api/agent/precheck'] === 'function');
  const r1 = await call({ trip: TRIP, trigger: { type: 'weather' }, scenario: RAIN });
  ok('HTTP：降雨 → 200 alert', r1.status === 200 && r1.body.alert === true, JSON.stringify(r1));
  const r2 = await call({ trip: TRIP, trigger: { type: 'user', message: 'x' } });
  ok('HTTP：user 觸發 → 400（預檢不處理文字需求）', r2.status === 400, JSON.stringify(r2));
  const r3 = await call({ trigger: { type: 'weather' } });
  ok('HTTP：缺 trip → 400', r3.status === 400);
  const r4 = await call({ trip: TRIP, trigger: { ...delayTrigger(10), now: 'bad' } });
  ok('HTTP：延誤格式錯 → 400 且附中文說明', r4.status === 400 && !!r4.body.message, JSON.stringify(r4));

  console.log(`\n${pass} passed, ${fail} failed`);
  process.exit(fail ? 1 : 0);
})().catch((e) => { console.error(e); process.exit(1); });
