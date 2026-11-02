// 行程生成 Agent（方案 C，/api/agent/plan）測試。
//   node test/plan-agent.test.js          離線：排程與驗證、mock LLM 跑通工具迴圈、SSE 端點（不呼叫 AI、不打 CWA）
//   node test/plan-agent.test.js --live   另外用 server/.env 的 AMD 端點實際規劃台東一日遊與綠島一日遊，印出每一步與耗時
'use strict';
const path = require('path');
require('dotenv').config({ path: path.join(__dirname, '..', '.env') });
const LIVE = process.argv.includes('--live');
if (!LIVE) { process.env.AI_PROVIDER = 'off'; process.env.CWA_API_KEY = ''; process.env.TDX_APP_ID = ''; }
process.env.AGENT_PLAN_TIMEOUT_MS = process.env.AGENT_PLAN_TIMEOUT_MS || (LIVE ? '120000' : '5000');

const P = require('../agent/planner');
const A = require('../agent/plan-agent');
const { parsePlanBody, mountAgentPlan } = require('../agent/plan-route');

let pass = 0, fail = 0;
const ok = (name, cond, extra) => { if (cond) { pass++; console.log('PASS  ' + name); } else { fail++; console.log('FAIL  ' + name + (extra ? '\n      ' + extra : '')); } };

// 旅遊日：明天起第一個週六（避開週一二公休的館，測試結果不跟著星期幾變）
const SAT = (() => { const d = new Date(Date.now() + 8 * 3600e3); d.setUTCDate(d.getUTCDate() + 1); while (d.getUTCDay() !== 6) d.setUTCDate(d.getUTCDate() + 1); return d.toISOString().slice(0, 10); })();
const TAITUNG = { dest: '台東', date: SAT, startTime: '09:00', durationMin: 480, people: 2, interests: ['文化', '自然'], pace: '平衡' };
const GREEN = { dest: '綠島', date: SAT, startTime: '09:00', durationMin: 480, people: 2, interests: ['自然'] };

/* ── mock LLM：依劇本回 tool_calls；劇本的每一步可以讀前面工具的結果（用搜尋到的名稱） ── */
function mockChat(script) {
  let i = 0;
  const calls = [];
  const chat = async ({ messages, tools }) => {
    calls.push({ messages: messages.slice(), tools });
    const results = {};
    for (const m of messages) {
      if (m.role !== 'tool') continue;
      const req = messages.flatMap((x) => x.tool_calls || []).find((c) => c.id === m.tool_call_id);
      if (req) (results[req.function.name] = results[req.function.name] || []).push(JSON.parse(m.content));
    }
    const step = script[i++];
    if (!step) return { message: { role: 'assistant', content: '我做完了' }, usage: { prompt_tokens: 10, completion_tokens: 5 } };
    if (step.throw) throw step.throw;
    const list = typeof step === 'function' ? step(results) : step;
    if (!list || !list.length) return { message: { role: 'assistant', content: '（沒有呼叫工具）' }, usage: { prompt_tokens: 10, completion_tokens: 5 } };
    return {
      message: { role: 'assistant', content: '', tool_calls: list.map(([name, args], k) => ({ id: `c${i}_${k}`, type: 'function', function: { name, arguments: JSON.stringify(args || {}) } })) },
      usage: { prompt_tokens: 100, completion_tokens: 20 },
      model: 'mock-gpt-oss'
    };
  };
  chat.calls = calls;
  return chat;
}
const last = (results, name) => (results[name] || [])[(results[name] || []).length - 1];

(async () => {
  /* ═══ 1. planner.js：排序、用餐時段、驗證 ═══ */
  const req = P.normalizeRequest(TAITUNG);
  ok('normalizeRequest：一日遊 09:00–17:00', req.startMin === 540 && req.endMin === 1020 && req.date === SAT);
  ok('normalizeRequest：本島起點用目的地中心', req.startPoint.source === 'center' && !req.island);
  ok('mealSlots：09–17 只需要午餐', P.mealSlots(540, 1020).map((m) => m.meal).join() === 'lunch');
  ok('mealSlots：10–20 午餐＋晚餐', P.mealSlots(600, 1200).map((m) => m.meal).join() === 'lunch,dinner');

  const greenReq = P.normalizeRequest(GREEN);
  ok('綠島：起訖點是島內港（南寮漁港）', greenReq.island === '綠島' && greenReq.startPoint.name === '南寮漁港');
  ok('綠島：景點池只含島上的點', P.poolOf(greenReq, 'scenic').length > 5 && P.poolOf(greenReq, 'scenic').every((p) => p.region === '綠島' || p.lat < 22.8 && p.lng > 121.4));
  ok('本島：景點池不含綠島蘭嶼', P.poolOf(req, 'scenic').every((p) => p.region !== '綠島' && p.region !== '蘭嶼'));

  const ctx0 = { req, allowed: new Set(), failedRounds: 0, getForecast: async () => null };
  const poi = await A.runTool(ctx0, 'search_local_poi', {});
  const food = await A.runTool(ctx0, 'search_restaurants', { nearName: poi.results[0].name });
  ok('search_local_poi 有結果且附營業時間', poi.results.length >= 5 && poi.results.every((r) => r.hours));
  ok('search_restaurants 有結果且附每人花費', food.results.length >= 2 && 'costPerPerson' in food.results[0]);

  const picks = poi.results.slice(0, 4).map((r) => ({ name: r.name })).concat([{ name: food.results[0].name }]);
  const d1 = P.buildDraft(req, picks.slice().reverse(), { allowed: ctx0.allowed });
  ok('buildDraft：順序由程式決定（時間遞增）', d1.stops.every((s, i, a) => i === 0 || s.time > a[i - 1].time), d1.stops.map((s) => s.time + s.name).join(' / '));
  const lunch = d1.stops.find((s) => s.meal === 'lunch');
  ok('buildDraft：餐廳排在午餐時段 11:00–14:00', lunch && lunch.time >= '11:00' && lunch.time <= '14:00', lunch && lunch.time);
  ok('buildDraft：每站都有車程分鐘', d1.stops.every((s) => Number.isFinite(s.moveMin)));

  const noLunch = P.buildDraft(req, picks.slice(0, 4), { allowed: ctx0.allowed });
  ok('buildDraft：沒排午餐 → meal 違規', !noLunch.ok && noLunch.violations.some((v) => v.code === 'meal'));

  const fake = P.buildDraft(req, picks.concat([{ name: '不存在的夢幻秘境' }]), { allowed: ctx0.allowed });
  ok('buildDraft：編造的地名 → unknown_place 違規', fake.violations.some((v) => v.code === 'unknown_place' && v.stop === '不存在的夢幻秘境'));
  const notSearched = P.buildDraft(req, picks.concat([{ name: '三仙台' }]), { allowed: ctx0.allowed });
  ok('buildDraft：本地有但沒經過搜尋工具的名稱也擋', notSearched.violations.some((v) => v.code === 'unknown_place'));

  const tooMany = P.buildDraft(req, poi.results.concat(food.results.slice(0, 1)).map((r) => ({ name: r.name, stayMin: 120 })), { allowed: ctx0.allowed });
  ok('buildDraft：站太多 → 回不到終點違規', tooMany.violations.some((v) => v.code === 'return_late' || v.code === 'day_end'), tooMany.violations.map((v) => v.code).join());

  // 午餐＋晚餐（10:00–20:00）：只開晚上的店即使排在清單第一個，也要配給晚餐
  const longReq = P.normalizeRequest({ ...TAITUNG, startTime: '10:00', durationMin: 600 });
  const ctxL = { req: longReq, allowed: new Set(), failedRounds: 0, getForecast: async () => null };
  const dinnerOnly = await A.runTool(ctxL, 'search_restaurants', { meal: 'dinner', radiusKm: 30 });
  const lunchOnly = await A.runTool(ctxL, 'search_restaurants', { meal: 'lunch', radiusKm: 30 });
  ok('search_restaurants meal=lunch：每家都標可吃午餐', lunchOnly.results.length > 0 && lunchOnly.results.every((r) => (r.goodFor || []).includes('午餐')));
  const poiL = await A.runTool(ctxL, 'search_local_poi', {});
  const dn = dinnerOnly.results.find((r) => !(r.goodFor || []).includes('午餐')) || dinnerOnly.results[0];
  const ln = lunchOnly.results.find((r) => r.name !== dn.name);
  const twoMeals = P.buildDraft(longReq, [{ name: dn.name }, { name: ln.name }].concat(poiL.results.slice(0, 5).map((r) => ({ name: r.name, stayMin: 70 }))), { allowed: ctxL.allowed });
  const dinnerStop = twoMeals.stops.find((s) => s.meal === 'dinner');
  const lunchStop = twoMeals.stops.find((s) => s.meal === 'lunch');
  ok('buildDraft：午餐、晚餐各配一家且在時段內', lunchStop && dinnerStop && lunchStop.time >= '11:00' && lunchStop.time <= '14:00' && dinnerStop.time >= '17:00' && dinnerStop.time <= '20:30',
    twoMeals.stops.map((s) => s.time + s.name + (s.meal || '')).join(' / ') + ' ' + twoMeals.violations.map((v) => v.message).join('；'));

  // 下雨：13–17 降雨 80%，兩個以上戶外站 → 擋
  const D = require('../agent/data');
  const rainFc = await D.getForecast('台東', { rain: { date: SAT, from: '09:00', to: '17:00', pop: 80 } });
  const outdoors = poi.results.filter((r) => r.indoor === 'outdoor').slice(0, 3).map((r) => ({ name: r.name }));
  const rainy = P.buildDraft(req, outdoors.concat([{ name: food.results[0].name }]), { allowed: ctx0.allowed, forecast: rainFc });
  ok('buildDraft：大雨天排 2 個以上戶外站 → rain 違規', rainy.violations.some((v) => v.code === 'rain'));

  // 同一處景點的別名：名稱不互相包含、座標差 750 m（觀景步道 vs 岩石本身）
  const sleepA = { name: '哈巴狗與睡美人岩', lat: 22.653986, lng: 121.507175 };
  const sleepB = { name: '小長城步道 & 睡美人與哈巴狗', lat: 22.660833, lng: 121.506816 };
  ok('sameAttraction：睡美人岩的兩個別名算同一處', P.sameAttraction(sleepA, sleepB));
  ok('sameAttraction：「三和海濱公園」與「海濱公園」相距 15 km 不算', !P.sameAttraction({ name: '三和海濱公園', lat: 22.62, lng: 121.03 }, { name: '海濱公園', lat: 22.755, lng: 121.158 }));
  ok('sameAttraction：相鄰 80 m 的不同景點不算', !P.sameAttraction({ name: '臺東故事館', lat: 22.7935, lng: 121.1235 }, { name: '鐵花新聚落', lat: 22.7929, lng: 121.124 }));
  const gctx = { req: greenReq, allowed: new Set(), failedRounds: 0, getForecast: async () => null };
  const gpoi = await A.runTool(gctx, 'search_local_poi', { radiusKm: 30 });
  const gnames = gpoi.results.map((r) => r.name);
  ok('search_local_poi：綠島結果不會同時有兩個睡美人岩別名', gnames.filter((n) => /睡美人/.test(n)).length <= 1, gnames.join('、'));
  const dupAllowed = new Set(gctx.allowed);
  ['哈巴狗與睡美人岩', '小長城步道 & 睡美人與哈巴狗'].forEach((n) => { const p = D.findPlace(n); if (p) dupAllowed.add(p._key); });
  const dup = P.buildDraft(greenReq, [{ name: '哈巴狗與睡美人岩' }, { name: '小長城步道 & 睡美人與哈巴狗' }], { allowed: dupAllowed });
  ok('buildDraft：同一處景點排兩次 → 退回', dup.violations.some((v) => v.code === 'unknown_place' && /同一個景點/.test(v.message)), dup.violations.map((v) => v.message).join('；'));

  // 行程太早結束：09–18 只排兩站，離結束還空很久 → idle 違規
  const lateReq = P.normalizeRequest({ ...TAITUNG, durationMin: 540 });
  const shortDay = P.buildDraft(lateReq, picks.slice(0, 2).concat([{ name: food.results[0].name }]), { allowed: ctx0.allowed });
  ok('buildDraft：離結束時間空超過 90 分 → idle 違規', shortDay.violations.some((v) => v.code === 'idle'), shortDay.returnTime);

  /* ═══ 2. mock LLM 跑通工具迴圈：第一輪缺午餐被退回，第二輪通過 ═══ */
  const script = [
    [['get_trip_request', {}]],
    [['get_weather_forecast', {}]],
    [['search_local_poi', { interest: '文化' }]],
    (r) => [['search_restaurants', { nearName: last(r, 'search_local_poi').results[0].name }]],
    // 第一輪：故意只送景點、沒有午餐 → 程式回 violations
    (r) => [['draft_itinerary', { stops: last(r, 'search_local_poi').results.slice(0, 4).map((x) => ({ name: x.name })) }]],
    (r) => [['draft_itinerary', { stops: last(r, 'search_local_poi').results.slice(0, 5).map((x) => ({ name: x.name, stayMin: 70 })).concat([{ name: last(r, 'search_restaurants').results[0].name }]) }]],
    [['present_itinerary', { title: '台東文化一日遊', summary: '市區文化景點加在地午餐', reasons: ['景點集中在市區，車程都在 10 分鐘內'] }]]
  ];
  const chat = mockChat(script);
  const events = [];
  let usageCalls = 0;
  const out = await A.runPlanAgent({ request: TAITUNG, chat, onEvent: (e) => events.push(e), onUsage: () => { usageCalls++; } });
  ok('mock 迴圈：最後事件是 itinerary', out.type === 'itinerary', JSON.stringify(out).slice(0, 300));
  ok('mock 迴圈：第一輪草稿被退回、第二輪通過', out.draftRounds === 2);
  ok('mock 迴圈：每一步都有 tool_call 事件與中文標籤', events.filter((e) => e.type === 'tool_call').length === 7 && events.every((e) => e.type !== 'tool_call' || /[一-鿿]/.test(e.label)));
  ok('mock 迴圈：draft 失敗的 tool_result 標 ok=false', events.some((e) => e.type === 'tool_result' && e.tool === 'draft_itinerary' && e.ok === false));
  ok('mock 迴圈：usage 累計 7 次 LLM 呼叫', out.usage.llmCalls === 7 && usageCalls === 7 && out.usage.promptTokens === 700);
  ok('mock 迴圈：輸出站點帶座標、時間、停留與 dayIndex=1', out.stops.length === 6 && out.stops.every((s) => Number.isFinite(s.lat) && /^\d{2}:\d{2}$/.test(s.time) && s.duration > 0 && s.dayIndex === 1));
  ok('mock 迴圈：餐廳標 tag=food 與 costPerPerson', out.stops.some((s) => s.tag === 'food' && s.meal === 'lunch' && Number.isFinite(s.costPerPerson)));
  ok('mock 迴圈：本島行程不提供 get_ferry_status', !chat.calls[0].tools.some((t) => t.function.name === 'get_ferry_status'));

  /* ═══ 3. 防護 ═══ */
  // present 在草稿通過前被擋，之後模型不再呼叫工具 → error（前端退回一般生成）
  const early = await A.runPlanAgent({ request: TAITUNG, chat: mockChat([[['present_itinerary', { title: 'x', summary: 'y', reasons: [] }]]]), onEvent: () => {} });
  ok('防護：沒有草稿就 present → 擋下，最後回 error', early.type === 'error' && early.code === 'no_tools');

  const badTool = await A.runPlanAgent({ request: TAITUNG, chat: mockChat([[['delete_everything', {}]]]), onEvent: () => {} });
  ok('防護：白名單外的工具不執行', badTool.type === 'error');

  const loopForever = mockChat(Array.from({ length: 30 }, () => [['get_trip_request', {}]]));
  const steps = await A.runPlanAgent({ request: TAITUNG, chat: loopForever, onEvent: () => {} });
  ok('防護：步數上限', steps.type === 'error' && steps.code === 'max_steps');

  const badDraft = (r) => [['draft_itinerary', { stops: [{ name: '不存在的夢幻秘境' }] }]];
  const threeFails = await A.runPlanAgent({ request: TAITUNG, chat: mockChat([[['get_trip_request', {}]], badDraft, badDraft, badDraft, badDraft]), onEvent: () => {} });
  ok('防護：草稿連續 4 輪驗證失敗 → error', threeFails.type === 'error' && threeFails.code === 'validation');

  const e500 = Object.assign(new Error('vLLM 500'), { status: 500 });
  const retried = mockChat([{ throw: e500 }, ...script]);
  const r500 = await A.runPlanAgent({ request: TAITUNG, chat: retried, onEvent: () => {} });
  ok('防護：上游 5xx 自動重試', r500.type === 'itinerary');

  const e400 = Object.assign(new Error('bad'), { status: 400 });
  const r400 = await A.runPlanAgent({ request: TAITUNG, chat: mockChat([{ throw: e400 }]), onEvent: () => {} });
  ok('防護：上游 4xx 不重試，回 error code=upstream', r400.type === 'error' && r400.code === 'upstream');

  const slow = async ({ signal }) => new Promise((resolve, reject) => {
    signal.addEventListener('abort', () => reject(Object.assign(new Error('aborted'), { name: 'TimeoutError' })));
  });
  process.env.AGENT_PLAN_TIMEOUT_MS = '300';
  // AbortSignal.timeout 的計時器是 unref 的：沒有別的工作時 Node 會直接結束，這裡撐住事件迴圈
  const keepAlive = setTimeout(() => {}, 5000);
  const tOut = await A.runPlanAgent({ request: TAITUNG, chat: slow, onEvent: () => {} });
  clearTimeout(keepAlive);
  process.env.AGENT_PLAN_TIMEOUT_MS = LIVE ? '120000' : '5000';
  ok('防護：逾時 → error code=timeout', tOut.type === 'error' && tOut.code === 'timeout', JSON.stringify(tOut).slice(0, 200));

  const disabled = await A.runPlanAgent({ request: TAITUNG, onEvent: () => {} });
  ok('AI_PROVIDER 不是 amd → error code=disabled（前端退回一般生成）', LIVE || (disabled.type === 'error' && disabled.code === 'disabled'));

  /* ═══ 4. 綠島：提供船班工具 ═══ */
  const islandChat = mockChat([
    [['get_trip_request', {}]],
    [['get_ferry_status', {}]],
    [['search_local_poi', {}]],
    (r) => [['search_restaurants', { nearName: last(r, 'search_local_poi').results[0].name, radiusKm: 15 }]],
    (r) => [['draft_itinerary', { stops: last(r, 'search_local_poi').results.slice(0, 5).map((x) => ({ name: x.name, stayMin: 70 })).concat([{ name: last(r, 'search_restaurants').results[0].name }]) }]],
    [['present_itinerary', { title: '綠島一日遊', summary: '環島看海', reasons: ['船班以船公司公告為準'] }]]
  ]);
  const islandEvents = [];
  const island = await A.runPlanAgent({ request: GREEN, chat: islandChat, onEvent: (e) => islandEvents.push(e) });
  ok('綠島：工具清單含 get_ferry_status', islandChat.calls[0].tools.some((t) => t.function.name === 'get_ferry_status'));
  ok('綠島：生成畫面顯示「正在查綠島船班」', islandEvents.some((e) => e.type === 'tool_call' && /綠島船班/.test(e.label)));
  ok('綠島：行程完成且起訖點是南寮漁港', island.type === 'itinerary' && island.start === '南寮漁港', JSON.stringify(island).slice(0, 300));

  /* ═══ 5. 端點：驗證與 SSE ═══ */
  ok('parsePlanBody：缺 request → 400', parsePlanBody({}).error === 'invalid request');
  ok('parsePlanBody：缺 dest → 400', parsePlanBody({ request: {} }).error === 'dest required');
  ok('parsePlanBody：多日 → 400 single day only', parsePlanBody({ request: { dest: '台東', days: 2 } }).error === 'single day only');
  ok('parsePlanBody：一日遊通過並裁切欄位', parsePlanBody({ request: { ...TAITUNG, title: 'x'.repeat(99) } }).request.title.length === 40);

  const express = require('express');
  const rateLimit = require('express-rate-limit');
  const app = express();
  app.use(express.json());
  const genRuns = { getOwnedRun: () => null, retain() {}, release: async () => {}, addGeminiUsage() {}, markUpstreamError() {} };
  const requireFirebaseUser = (rq, rs, next) => (rq.headers.authorization ? (rq.user = { uid: 'u1' }, next()) : rs.status(401).json({ error: 'login required' }));
  mountAgentPlan(app, {
    requireFirebaseUser, rateLimit, genRuns, amdLlm: { USAGE_MODEL_ID: 'gpt-oss-120b' },
    run: (opts) => A.runPlanAgent({ ...opts, chat: mockChat(script) })
  });
  const server = await new Promise((resolve) => { const s = app.listen(0, '127.0.0.1', () => resolve(s)); });
  const base = `http://127.0.0.1:${server.address().port}/api/agent/plan`;
  const post = (body, auth = true) => fetch(base, { method: 'POST', headers: { 'Content-Type': 'application/json', ...(auth ? { Authorization: 'Bearer t' } : {}) }, body: JSON.stringify(body) });
  ok('端點：未登入 401', (await post({ request: TAITUNG }, false)).status === 401);
  ok('端點：多日 400', (await post({ request: { ...TAITUNG, days: 2 } })).status === 400);
  const sse = await post({ request: TAITUNG });
  const text = await sse.text();
  const sseEvents = text.split('\n\n').filter(Boolean).map((b) => JSON.parse(b.replace(/^data: /, '')));
  ok('端點：SSE content-type', String(sse.headers.get('content-type')).includes('text/event-stream'));
  ok('端點：SSE 逐步事件，最後是 itinerary', sseEvents[0].type === 'start' && sseEvents[sseEvents.length - 1].type === 'itinerary' && sseEvents.filter((e) => e.type === 'tool_call').length === 7);
  server.close();

  /* ═══ 6. --live：實際呼叫 AMD gpt-oss-120b ═══ */
  if (LIVE) {
    for (const [label, request] of [['台東一日遊', TAITUNG], ['綠島一日遊', GREEN]]) {
      console.log(`\n── LIVE：${label} ──`);
      const t0 = Date.now();
      const res = await A.runPlanAgent({
        request,
        onEvent: (e) => {
          if (e.type === 'tool_call') console.log(`  [${(e.ms / 1000).toFixed(1)}s] ${e.label}`);
          if (e.type === 'tool_result') console.log(`         → ${e.detail}`);
        }
      });
      console.log(`  結果：${res.type}｜${((Date.now() - t0) / 1000).toFixed(1)} 秒｜LLM ${res.usage.llmCalls} 次｜tokens ${res.usage.promptTokens}+${res.usage.completionTokens}`);
      if (res.type === 'itinerary') {
        console.log(`  ${res.title}：${res.summary}`);
        res.stops.forEach((s) => console.log(`   ${s.time} ${s.name}（${s.duration} 分）${s.tag === 'food' ? ' 🍽️' : ''}`));
        res.reasons.forEach((x) => console.log('   · ' + x));
      } else console.log('  ' + res.message);
      ok(`LIVE ${label}：產出行程`, res.type === 'itinerary');
    }
  }

  console.log(`\n${pass} passed, ${fail} failed`);
  process.exit(fail ? 1 : 0);
})().catch((e) => { console.error(e); process.exit(1); });
