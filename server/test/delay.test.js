// T4 延誤觸發測試（docs/amd-agent/t4-delay-trigger-spec.md）
//   node test/delay.test.js          離線：請求驗證、順延、期限、固定站、規則式降級（不呼叫 AI）
//   node test/delay.test.js --live   另外用 AMD 端點跑「延誤 90 分鐘」讓 Agent 處理
'use strict';
const path = require('path');
require('dotenv').config({ path: path.join(__dirname, '..', '.env') });
const LIVE = process.argv.includes('--live');
if (!LIVE) process.env.AI_PROVIDER = 'off';

const I = require('../agent/itinerary');
const DL = require('../agent/delay');
const { runAgent } = require('../agent/loop');

let pass = 0, fail = 0;
const ok = (name, cond, extra) => { if (cond) { pass++; console.log('PASS  ' + name); } else { fail++; console.log('FAIL  ' + name + (extra ? '\n      ' + extra : '')); } };
const clone = (x) => JSON.parse(JSON.stringify(x));

// 2026-11-07（六，決賽當天）。使用者人在臺東森林公園，原定 14:30 離開
const DAY = '2026-11-07';
const TRIP = {
  title: '台東市區一日遊', region: '台東', startDate: DAY, endTime: '21:00', people: 2,
  stops: [
    { id: 'cstop-a1', day: 1, time: '15:00', stayMin: 60, name: '加路蘭', stopType: 'scenic' },
    { id: 'cstop-b2', day: 1, time: '16:20', stayMin: 30, name: '臺東美術館', stopType: 'scenic' },   // 16:50 離開，離 17:00 關門留 10 分鐘餘裕
    { id: 'web_3_榕樹下米苔目', day: 1, time: '18:00', stayMin: 60, name: '榕樹下米苔目(中華路創始老店-別無分店)', stopType: 'food' },
    { id: 'cstop-d4', day: 1, time: '19:20', stayMin: 20, name: '台東車站', anchor: 'station' }
  ]
};
const trigger = (delayMin, extra) => ({
  type: 'delay', source: 'app', day: 1, now: `${DAY}T14:20:00+08:00`,
  from: { stopId: 'cstop-f0', name: '臺東森林公園', lat: 22.7698, lng: 121.1608, leaveAt: `${DAY}T${String(14 + Math.floor((30 + delayMin) / 60)).padStart(2, '0')}:${String((30 + delayMin) % 60).padStart(2, '0')}:00+08:00`, delayMin },
  ...(extra || {})
});
const run = (trip, trig, scenario) => runAgent({ trip, trigger: trig, scenario, onEvent: () => {} });

(async () => {
  // ── 請求驗證 ──
  ok('正常請求通過', !DL.parseDelayRequest(trigger(10), TRIP).error);
  ok('now 沒帶時區 → 拒絕', /時區/.test(DL.parseDelayRequest({ ...trigger(10), now: `${DAY}T14:20:00` }, TRIP).error || ''));
  ok('缺 from.stopId → 拒絕', !!DL.parseDelayRequest({ ...trigger(10), from: { name: 'x', leaveAt: `${DAY}T14:40:00+08:00` } }, TRIP).error);
  const noId = clone(TRIP); delete noId.stops[1].id;
  ok('有站缺 id → 拒絕並說明', /缺少 id/.test(DL.parseDelayRequest(trigger(10), noId).error || ''));
  const utc = DL.parseIso('2026-11-07T06:30:00Z');
  ok('UTC 時間換成台灣時間', utc.date === DAY && utc.min === 14 * 60 + 30);

  // ── 小延誤：順延就排得下 → 只改時間、不呼叫 AI ──
  const small = await run(TRIP, trigger(10));
  ok('延誤 10 分鐘 → 只改時間的提案', small.type === 'proposal' && small.retimeOnly && small.llmSkipped, JSON.stringify(small).slice(0, 300));
  ok('沒有呼叫 LLM', small.usage.llmCalls === 0);
  ok('10 分鐘到得了第一站 → 沒有任何變更', small.changes.length === 0 && /不影響/.test(small.summary), small.summary);
  const small20 = await run(TRIP, trigger(20));
  ok('延誤 20 分鐘 → 只改時間、全部是 retime', small20.type === 'proposal' && small20.retimeOnly && small20.changes.length > 0 && small20.changes.every((c) => c.type === 'retime'), JSON.stringify(small20.changes));
  ok('錨點站台東車站時間沒動', small.draft.stops.find((s) => s.id === 'cstop-d4').time === '19:20');
  ok('含中文的 id 原樣送回', small.draft.stops.some((s) => s.id === 'web_3_榕樹下米苔目'));
  ok('時間是依原本計畫順延（不是從 0 開始排）', small.draft.stops.find((s) => s.id === 'cstop-a1').time >= '15:00');

  // ── 延誤 90 分鐘：美術館 17:00 關門會來不及 → 規則式降級刪站 ──
  const big = await run(TRIP, trigger(90));
  ok('延誤 90 分鐘 → 有提案（AI 關閉時用規則）', big.type === 'proposal', JSON.stringify(big).slice(0, 300));
  ok('刪掉來不及的美術館', big.type === 'proposal' && !big.draft.stops.some((s) => s.name === '臺東美術館'), JSON.stringify(big.changes));
  ok('錨點站還在、時間不變', big.type === 'proposal' && big.draft.stops.find((s) => s.anchor === 'station').time === '19:20');
  const bigBase = DL.dayTrip(I.normalizeTrip(TRIP), DL.parseDelayRequest(trigger(90), TRIP));
  ok('提案通過延誤驗證', big.type === 'proposal' && DL.check(I.normalizeTrip({ ...big.draft, stops: big.draft.stops }), bigBase, DL.parseDelayRequest(trigger(90), TRIP)).ok);

  // ── 回程火車期限：一份前後一致的行程（車站 18:10、火車 18:30）──
  const TRAIN_TRIP = clone(TRIP);
  TRAIN_TRIP.stops[2].time = '17:20'; TRAIN_TRIP.stops[2].stayMin = 40;
  TRAIN_TRIP.stops[3].time = '18:10';
  const trainTrig = (d) => trigger(d, { returnTrain: { departAt: `${DAY}T18:30:00+08:00`, station: '台東車站' } });
  const train = await run(TRAIN_TRIP, trainTrig(60));
  const trainReq = DL.parseDelayRequest(trainTrig(60), TRAIN_TRIP);
  const trainBase = DL.dayTrip(I.normalizeTrip(TRAIN_TRIP), trainReq);
  ok('延誤 60 分鐘＋18:30 火車 → 有提案', train.type === 'proposal', JSON.stringify(train).slice(0, 300));
  ok('提案趕得上火車（沒有期限違規）', train.type === 'proposal' && !DL.check(I.normalizeTrip(train.draft), trainBase, trainReq).violations.some((v) => v.code === 'deadline'));
  // 直接驗證期限規則：車站前一站晚到，就要報「趕不上回程火車」
  const late = clone(TRAIN_TRIP); late.stops[2].time = '18:00'; late.stops[2].stayMin = 30;
  const lateReq = DL.parseDelayRequest(trainTrig(0), late);
  const lateBase = DL.dayTrip(I.normalizeTrip(late), lateReq);
  ok('晚餐 18:30 才吃完 → 報「趕不上回程火車」', DL.check(lateBase, lateBase, lateReq).violations.some((v) => v.code === 'deadline'), JSON.stringify(DL.check(lateBase, lateBase, lateReq).violations));
  ok('期限只看「最快到站時間」，不看車站的表定時間', !DL.check(DL.dayTrip(I.normalizeTrip(TRAIN_TRIP), DL.parseDelayRequest(trainTrig(0), TRAIN_TRIP)), DL.dayTrip(I.normalizeTrip(TRAIN_TRIP), DL.parseDelayRequest(trainTrig(0), TRAIN_TRIP)), DL.parseDelayRequest(trainTrig(0), TRAIN_TRIP)).violations.some((v) => v.code === 'deadline'));

  // ── 訂位的晚餐（keepReason）不能刪、不能挪 ──
  const booked = clone(TRIP); booked.stops[2].keepReason = '已訂位 18:00';
  const b = await run(booked, trigger(90));
  ok('有訂位的餐廳沒被刪、時間不變', b.type === 'proposal' && b.draft.stops.find((s) => s.id === 'web_3_榕樹下米苔目').time === '18:00', JSON.stringify(b).slice(0, 300));
  const bApply = I.applyOps(I.normalizeTrip(booked), [{ type: 'remove', stopId: 'web_3_榕樹下米苔目' }, { type: 'remove', stopId: 'cstop-d4' }, { type: 'retime', stopId: 'cstop-d4', time: '18:00' }]);
  ok('applyOps 擋下刪除訂位站、刪除錨點、改錨點時間', bApply.results.every((r) => r.ok === false), JSON.stringify(bApply.results));

  // ── 用戶端送的營業時間優先 ──
  const closed = clone(TRIP); closed.stops[1].businessHours = '星期六: 休息';
  const cReq = DL.parseDelayRequest(trigger(0), closed);
  const cBase = DL.dayTrip(I.normalizeTrip(closed), cReq);
  ok('App 送「星期六休息」→ 美術館判公休（不看本地資料）', DL.check(DL.shift(cBase, cReq), cBase, cReq).violations.some((v) => v.code === 'closed' && v.stopId === 'cstop-b2'));

  // ── demo：scenario.delay 固定延誤 ──
  const demo = await run(TRIP, trigger(0), { delay: { minutes: 90 } });
  ok('scenario.delay 90 分鐘 → 跟真的延誤 90 分鐘一樣處理，並標模擬', demo.type === 'proposal' && demo.simulated && !demo.draft.stops.some((s) => s.name === '臺東美術館'), JSON.stringify(demo).slice(0, 200));

  // ── 只處理當天 ──
  const twoDays = clone(TRIP); twoDays.stops.push({ id: 'cstop-x9', day: 2, time: '09:00', stayMin: 60, name: '鐵花村音樂聚落' });
  const td = await run(twoDays, trigger(10));
  ok('第 2 天的站不在提案裡（不動到）', td.type === 'proposal' && !td.draft.stops.some((s) => s.id === 'cstop-x9'));

  if (LIVE) {
    console.log('\n── live：延誤 90 分鐘，交給 gpt-oss（回程火車 19:40）──');
    const live = await runAgent({
      trip: TRIP, trigger: trigger(90, { returnTrain: { departAt: `${DAY}T19:40:00+08:00`, station: '台東車站' } }),
      onEvent: (e) => {
        const t = (e.ms / 1000).toFixed(1).padStart(5) + 's ';
        if (e.type === 'check_result') console.log(t + 'ℹ️  ' + e.label + '：' + e.issues.join('；'));
        else if (e.type === 'tool_call') console.log(t + '🔧 ' + e.label);
        else if (e.type === 'tool_result') console.log(t + '   → ' + e.detail);
        else if (e.type === 'fallback') console.log(t + '🛟 ' + e.label);
      }
    });
    if (live.type === 'proposal') {
      console.log('📋 ' + live.summary + (live.fallback ? '（規則式降級）' : ''));
      (live.reasons || []).forEach((r) => console.log('   • ' + r));
      live.changes.forEach((c) => console.log(`   ${c.type}: ${c.from || c.name || ''} ${c.to || ''}${c.stayTo ? ` 停留 ${c.stayFrom}→${c.stayTo}` : ''}`));
    } else console.log(JSON.stringify(live).slice(0, 400));
    console.log(`   ${live.usage.llmCalls} 次 LLM、${(live.ms / 1000).toFixed(1)}s、model ${JSON.stringify(live.upstreamModels)}`);
    const req = DL.parseDelayRequest(trigger(90, { returnTrain: { departAt: `${DAY}T19:40:00+08:00` } }), TRIP);
    const base = DL.dayTrip(I.normalizeTrip(TRIP), req);
    ok('live：產出提案', live.type === 'proposal');
    ok('live：提案通過延誤驗證（營業時間、期限、固定站）', live.type === 'proposal' && DL.check(I.normalizeTrip(live.draft), base, req).ok,
      live.type === 'proposal' ? JSON.stringify(DL.check(I.normalizeTrip(live.draft), base, req).violations) : '');
    ok('live：錨點車站沒被動到', live.type === 'proposal' && live.draft.stops.find((s) => s.anchor === 'station').time === '19:20');
  }

  console.log(`\n${pass} passed, ${fail} failed`);
  process.exit(fail ? 1 : 0);
})().catch((e) => { console.error(e); process.exit(1); });
