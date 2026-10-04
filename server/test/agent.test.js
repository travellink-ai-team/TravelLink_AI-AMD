// 旅程應變 Agent 測試。
//   node test/agent.test.js          離線：驗證規則、套用修改、降級方案（不呼叫 AI、不打 CWA）
//   node test/agent.test.js --live   另外用 server/.env 的 AMD 端點跑情境 B（下雨）、D（好累）、A（綠島停航）
'use strict';
const path = require('path');
require('dotenv').config({ path: path.join(__dirname, '..', '.env') });
const LIVE = process.argv.includes('--live');
if (!LIVE) { process.env.AI_PROVIDER = 'off'; process.env.CWA_API_KEY = ''; }
// 這份測試涵蓋綠島停航（情境 A），所以一律打開停航功能；關閉時的行為見 test/sea-flag.test.js
process.env.AGENT_SEA_RISK = 'on';

const I = require('../agent/itinerary');
const { runAgent } = require('../agent/loop');

let pass = 0, fail = 0;
const ok = (name, cond, extra) => { if (cond) { pass++; console.log('PASS  ' + name); } else { fail++; console.log('FAIL  ' + name + (extra ? '\n      ' + extra : '')); } };

// 明天（台灣時間），落在 CWA 一週預報內
// 行程日：明天起第一個不是週一、週二的日子。測試行程有鐵花村（週一二公休）、史前館（週二休），
// 碰到公休日「沒有降雨」那幾項會走到別的分支（星期天跑測試就會失敗）
const tomorrow = (() => { const d = new Date(Date.now() + 8 * 3600e3); d.setUTCDate(d.getUTCDate() + 1); while ([1, 2].includes(d.getUTCDay())) d.setUTCDate(d.getUTCDate() + 1); return d.toISOString().slice(0, 10); })();

// 情境 B：台東市區一日遊，下午兩個戶外景點碰上大雨（模擬 13:00–17:00、80%）
const TRIP = {
  title: '台東市區一日遊', region: '台東', startDate: tomorrow, endTime: '20:00', people: 2,
  stops: [
    { id: 's1', day: 1, time: '09:00', stayMin: 120, name: '國立臺灣史前文化博物館' },
    { id: 's2', day: 1, time: '11:30', stayMin: 60, name: '榕樹下米苔目(中華路創始老店-別無分店)' },
    { id: 's3', day: 1, time: '13:30', stayMin: 90, name: '臺東森林公園' },
    { id: 's4', day: 1, time: '15:30', stayMin: 60, name: '加路蘭' },
    { id: 's5', day: 1, time: '17:30', stayMin: 90, name: '鐵花村音樂聚落' }
  ]
};
const SCENARIO = { rain: { date: tomorrow, from: '13:00', to: '17:00', pop: 80 } };

// 情境 A：綠島三天兩夜，第 3 天 15:30 回程。模擬第 3 天浪高 3.5 公尺。
const GREEN = {
  title: '綠島三天兩夜', region: '綠島', startDate: tomorrow, endTime: '21:00', people: 2,
  stops: [
    { id: 'g1', day: 1, time: '08:00', stayMin: 30, name: '富岡漁港' },
    { id: 'g2', day: 1, time: '09:30', stayMin: 20, name: '南寮漁港' },
    { id: 'g3', day: 1, time: '10:00', stayMin: 40, name: '綠島遊客中心' },
    { id: 'g4', day: 1, time: '11:00', stayMin: 60, name: '綠島大街' },
    { id: 'g5', day: 1, time: '12:30', stayMin: 60, name: '綠島竹屋餐廳' },
    { id: 'g6', day: 1, time: '14:00', stayMin: 55, name: '小長城' },
    { id: 'g7', day: 1, time: '15:30', stayMin: 90, name: '帆船鼻大草原' },
    { id: 'g8', day: 1, time: '19:30', stayMin: 90, name: '朝日溫泉' },
    { id: 'g9', day: 2, time: '09:00', stayMin: 65, name: '綠島梅花鹿生態園區' },
    { id: 'g10', day: 2, time: '10:30', stayMin: 35, name: '柴口浮潛區' },
    { id: 'g11', day: 2, time: '12:00', stayMin: 50, name: '綠島 非炒不可海鮮食堂' },
    { id: 'g12', day: 2, time: '14:00', stayMin: 90, name: '過山古道' },
    { id: 'g13', day: 2, time: '16:00', stayMin: 40, name: '牛頭山' },
    { id: 'g14', day: 2, time: '17:30', stayMin: 60, name: '小島太太Mrs.Kojima(店休時間請看IG主頁)' },
    { id: 'g15', day: 3, time: '09:00', stayMin: 35, name: '大白沙' },
    { id: 'g16', day: 3, time: '10:00', stayMin: 35, name: '睡美人岩' },
    { id: 'g17', day: 3, time: '11:00', stayMin: 60, name: '綠島監獄' },
    { id: 'g18', day: 3, time: '12:30', stayMin: 60, name: '超難吃牛肉麵' },
    { id: 'g19', day: 3, time: '14:30', stayMin: 60, name: '南寮漁港' },
    { id: 'g20', day: 3, time: '16:30', stayMin: 20, name: '富岡漁港' },
    { id: 'g21', day: 3, time: '17:30', stayMin: 60, name: '榕樹下米苔目(中華路創始老店-別無分店)' }
  ]
};
const day3 = (() => { const d = new Date(tomorrow + 'T00:00:00Z'); d.setUTCDate(d.getUTCDate() + 2); return d.toISOString().slice(0, 10); })();
const dayN = (n) => { const d = new Date(tomorrow + 'T00:00:00Z'); d.setUTCDate(d.getUTCDate() + n - 1); return d.toISOString().slice(0, 10); };
// 四天都注入，測試結果不跟著真實天氣變（2026-10-03 真實預報 10/5～10/7 都是高風險，只注入第 3 天會讓測試失敗）
const SEA_SCENARIO = { sea: [
  { date: dayN(1), waveMaxM: 1 }, { date: dayN(2), waveMaxM: 1 },
  { date: day3, waveMaxM: 3.5, gustMax: 9 }, { date: dayN(4), waveMaxM: 4 }
] };
// 沒有安全的搭船日：第 2～4 天都高風險
const STORM_ALL = { sea: [
  { date: dayN(1), waveMaxM: 1 }, { date: dayN(2), waveMaxM: 3.5 },
  { date: day3, waveMaxM: 4 }, { date: dayN(4), waveMaxM: 4 }
] };

(async () => {
  const D = require('../agent/data');
  const forecast = await D.getForecast('台東', SCENARIO);
  const trip = I.normalizeTrip(TRIP);

  // ── 正規化 ──
  ok('座標從本地資料補上', trip.stops.every((s) => Number.isFinite(s.lat)));
  ok('餐廳被認成 food', trip.stops.find((s) => s.id === 's2').kind === 'food');
  ok('不信任前端多餘欄位', !('evil' in I.normalizeTrip({ ...TRIP, evil: 1 })));

  // ── 驗證 ──
  const v = I.validate(trip, forecast);
  const rainIds = v.violations.filter((x) => x.code === 'rain').map((x) => x.stopId).sort().join(',');
  ok('下雨時段的兩個戶外站被抓出來', rainIds === 's3,s4', JSON.stringify(v.violations));
  ok('室內博物館與餐廳不算違規', !v.violations.some((x) => ['s1', 's2'].includes(x.stopId)));
  const noWeather = I.validate(trip, null);
  ok('沒有預報時不判降雨、但會提醒', !noWeather.violations.some((x) => x.code === 'rain') && noWeather.warnings.some((w) => w.code === 'weather_unknown'));
  const keep = I.normalizeTrip({ ...TRIP, stops: TRIP.stops.map((s) => (s.id === 's3' ? { ...s, keepReason: '使用者堅持要去' } : s)) });
  ok('使用者堅持要去的站不算違規', !I.validate(keep, forecast).violations.some((x) => x.stopId === 's3'));
  const late = I.normalizeTrip({ ...TRIP, endTime: '18:00' });
  ok('超過每天結束時間會被抓', I.validate(late, forecast).violations.some((x) => x.code === 'day_end'));

  // 臺東美術館週六「09:00–12:00, 13:30–17:00」：下午那段也要認得
  const saturday = (() => { const d = new Date(tomorrow + 'T00:00:00Z'); d.setUTCDate(d.getUTCDate() + ((6 - d.getUTCDay() + 7) % 7)); return d.toISOString().slice(0, 10); })();
  const museum = (time, stayMin) => I.validate(I.normalizeTrip({ startDate: saturday, stops: [{ id: 'm', day: 1, time, stayMin, name: '臺東美術館' }] }), null)
    .violations.some((x) => x.code === 'hours');
  ok('午休的館：下午時段算營業', !museum('14:00', 60));
  ok('午休的館：排在午休時間算違規', museum('12:00', 60));

  // ── 套用修改 ──
  const fake = I.applyOps(trip, [{ type: 'replace', stopId: 's3', name: '不存在的景點123' }]);
  ok('編造的地名被拒絕', fake.results[0].ok === false && fake.draft.stops.find((s) => s.id === 's3'));
  const dup = I.applyOps(trip, [{ type: 'replace', stopId: 's3', name: '國立臺灣史前文化博物館' }]);
  ok('已在行程裡的地點不能重複加', dup.results[0].ok === false);
  const good = I.applyOps(trip, [{ type: 'replace', stopId: 's3', name: '台東糖廠文創園區' }, { type: 'remove', stopId: 's4' }]);
  ok('replace + remove 成功', good.results.every((r) => r.ok), JSON.stringify(good.results));
  ok('原行程沒被改到', trip.stops.find((s) => s.id === 's3').name === '臺東森林公園');
  const diff = I.diffTrips(trip, good.draft);
  ok('差異包含替換與刪除', diff.some((c) => c.type === 'replace' && c.from === '臺東森林公園') && diff.some((c) => c.type === 'remove' && c.from === '加路蘭'), JSON.stringify(diff));
  const squeezed = I.applyOps(trip, [{ type: 'retime', stopId: 's3', time: '12:00' }]);
  const s3 = squeezed.draft.stops.find((s) => s.id === 's3');
  ok('重疊的時間會依車程自動往後推', I.toMin(s3.time) >= I.toMin('12:30'), s3.time);
  const shorter = I.applyOps(trip, [{ type: 'retime', stopId: 's5', stayMin: 45 }]);
  ok('retime 只帶 stayMin 可以縮短停留', shorter.draft.stops.find((s) => s.id === 's5').stayMin === 45 && I.dayEnds(shorter.draft)[1] === '18:15');
  ok('縮短停留會出現在差異裡', I.diffTrips(trip, shorter.draft).some((c) => c.type === 'retime' && c.stayTo === 45));

  // ── 海象解析 ──
  const SEA = require('../agent/sea');
  const F = require('../agent/ferry');
  ok('浪高取最大值', SEA.waveMaxOf('2轉3再轉4公尺') === 4 && SEA.waveMaxOf('約1公尺') === 1);
  const w = SEA.windOf('4至5陣風7級晨轉5至6陣風8級下午再轉4至5陣風7級');
  ok('風力拆出平均風與陣風', w.windMax === 6 && w.gustMax === 8, JSON.stringify(w));
  ok('「陣風6級以下」不算進平均風', SEA.windOf('4級陣風6級以下').windMax === 4);
  ok('浪高 3 公尺＝高風險', SEA.riskOf({ waveMaxM: 3, windMax: 5, gustMax: 7 }).risk === 'high');
  ok('浪高 2 公尺＝中風險', SEA.riskOf({ waveMaxM: 2, windMax: 4, gustMax: 6 }).risk === 'medium');
  ok('颱風海上警報＝高風險', SEA.riskOf({ waveMaxM: 1, windMax: 3, gustMax: 5, typhoon: true }).risk === 'high');

  // ── 船班與島上／本島 ──
  const green = I.normalizeTrip(GREEN);
  const legs = F.legsOf(green);
  ok('認出綠島行程', green.island === '綠島');
  ok('找出去程與回程兩段船班', legs.length === 2 && legs[0].direction === 'outbound' && legs[1].direction === 'return' && legs[1].day === 3, JSON.stringify(legs));
  ok('回程 15:30 開船、航程 60 分鐘', I.toClock(legs[1].departMin) === '15:30' && I.toClock(legs[1].arriveMin) === '16:30');
  const seaOf = async (t, scenario) => {
    const o = {};
    for (let d = 1; d <= t.days + 1; d++) { const dt = I.dateOfDay(t.startDate, d); o[dt] = await SEA.seaOn('綠島', dt, scenario); }
    return o;
  };
  const gv = I.validate(green, null, await seaOf(green, SEA_SCENARIO));
  ok('港口去回各一次不算重複', !gv.violations.some((x) => x.code === 'duplicate'), JSON.stringify(gv.violations));
  ok('模擬浪高 3.5 公尺：回程船班被擋下', gv.violations.some((x) => x.code === 'ferry_risk' && x.day === 3), JSON.stringify(gv.violations));
  ok('拿不到海象時只提醒、不擋', I.validate(green, null, {}).violations.every((x) => x.code !== 'ferry_risk'));
  ok('港口站不能刪除', I.applyOps(green, [{ type: 'remove', stopId: 'g19' }]).results[0].ok === false);
  const moved = I.applyOps(green, [{ type: 'move', stopId: 'g17', day: 1, time: '17:00' }]);
  ok('move 可以把站移到別天', moved.draft.stops.find((s) => s.id === 'g17').day === 1);
  const crossSea = I.applyOps(green, [{ type: 'move', stopId: 'g21', day: 2, time: '19:00' }]);
  ok('沒搭船就從島上跳到本島會被抓', I.validate(crossSea.draft, null, {}).violations.some((x) => x.code === 'wrong_side'));
  const early = I.applyOps(green, [
    { type: 'move', stopId: 'g19', day: 2, time: '15:00' }, { type: 'move', stopId: 'g20', day: 2, time: '16:30' },
    { type: 'move', stopId: 'g21', day: 2, time: '17:30' }, { type: 'remove', stopId: 'g13' }, { type: 'remove', stopId: 'g14' },
    { type: 'remove', stopId: 'g15' }, { type: 'remove', stopId: 'g16' }, { type: 'remove', stopId: 'g17' }, { type: 'remove', stopId: 'g18' }
  ]);
  const earlyLegs = F.legsOf(early.draft);
  ok('提前一天回程：船班移到第 2 天', earlyLegs[1] && earlyLegs[1].day === 2, JSON.stringify(earlyLegs));
  ok('提前回程後行程變成 2 天', early.draft.days === 2);
  const earlyCheck = I.validate(early.draft, null, await seaOf(early.draft, SEA_SCENARIO));
  ok('提前回程的草稿可以通過驗證', earlyCheck.ok, JSON.stringify(earlyCheck.violations));
  const stay = I.applyOps(green, [{ type: 'move', stopId: 'g19', day: 4, time: '09:00' }, { type: 'move', stopId: 'g20', day: 4, time: '10:30' }, { type: 'move', stopId: 'g21', day: 4, time: '11:30' }]);
  ok('多住一晚：可以 move 到第 4 天並記錄 extraNights', stay.draft.days === 4 && stay.draft.extraNights === 1);
  const mf = I.applyOps(green, [{ type: 'move_ferry', direction: 'return', day: 2, time: '15:30' }]);
  const mfr = mf.results[0];
  ok('move_ferry：回程一個 op 改到第 2 天', mfr.ok && F.legsOf(mf.draft)[1].day === 2 && I.toClock(F.legsOf(mf.draft)[1].departMin) === '15:30', JSON.stringify(mfr));
  ok('move_ferry：來不及去的島上站被刪並回報', mfr.removedIslandStops.includes('牛頭山') && mfr.removedIslandStops.includes('綠島監獄') && !mf.draft.stops.some((s) => s.name === '大白沙'));
  ok('move_ferry：抵達後的本島晚餐跟著移', mf.draft.stops.find((s) => s.id === 'g21').day === 2);
  const mfCheck = I.validate(mf.draft, null, await seaOf(mf.draft, SEA_SCENARIO));
  ok('move_ferry：結果通過驗證', mfCheck.ok, JSON.stringify(mfCheck.violations));
  ok('move_ferry：多住一晚', I.applyOps(green, [{ type: 'move_ferry', direction: 'return', day: 4, time: '10:00' }]).draft.extraNights === 1);
  ok('最多只能延長一晚', I.applyOps(green, [{ type: 'move', stopId: 'g21', day: 5 }]).results[0].ok === false);

  // ── 不呼叫 AI 的路徑 ──
  const events = [];
  const sunny = await runAgent({ trip: TRIP, trigger: { type: 'weather' }, onEvent: (e) => events.push(e) });
  ok('沒有降雨時直接結束、不呼叫 LLM', sunny.type === 'no_change' && sunny.llmSkipped && sunny.usage.llmCalls === 0, JSON.stringify(sunny));
  if (!LIVE) {
    const fb = await runAgent({ trip: TRIP, trigger: { type: 'weather' }, scenario: SCENARIO, onEvent: () => {} });
    ok('AI 未啟用時降級成規則式替換', fb.type === 'proposal' && fb.fallback && fb.changes.length >= 2, JSON.stringify(fb).slice(0, 300));
    ok('降級方案裡沒有下雨的戶外站', fb.type === 'proposal' && !fb.draft.stops.some((s) => ['臺東森林公園', '加路蘭'].includes(s.name)));
  }

  // ── 實際跑 gpt-oss（情境 B）──
  if (LIVE) {
    console.log(`\n── 情境 B（${tomorrow}，模擬 13:00–17:00 降雨 80%）──`);
    const result = await runAgent({
      trip: TRIP, trigger: { type: 'weather' }, scenario: SCENARIO,
      onEvent: (e) => {
        const t = (e.ms / 1000).toFixed(1).padStart(5) + 's ';
        if (e.type === 'tool_call') console.log(t + '🔧 ' + e.label);
        else if (e.type === 'tool_result') console.log(t + '   → ' + e.detail);
        else if (['check', 'check_result', 'fallback'].includes(e.type)) console.log(t + 'ℹ️  ' + e.label + (e.issues ? '：' + e.issues.join('；') : ''));
      }
    });
    console.log('');
    if (result.type === 'proposal') {
      console.log('📋 ' + result.summary + (result.fallback ? '（降級）' : ''));
      result.reasons.forEach((r) => console.log('   • ' + r));
      result.changes.forEach((c) => console.log(`   ${c.type}: ${c.from || ''}${c.from && c.to ? ' → ' : ''}${c.to || ''} ${c.time || ''}`));
      console.log(`   每人花費 ${result.costDelta.perPersonBefore} → ${result.costDelta.perPersonAfter}`);
    } else console.log(JSON.stringify(result, null, 2).slice(0, 600));
    console.log(`   ${result.usage.llmCalls} 次 LLM、${result.steps} 次工具、${result.usage.promptTokens}+${result.usage.completionTokens} tokens、${(result.ms / 1000).toFixed(1)}s`);
    ok('live：產出提案', result.type === 'proposal');
    ok('live：由 AI 完成而不是降級', result.type === 'proposal' && !result.fallback);
    ok('live：提案行程沒有違規', result.type === 'proposal' && I.validate(I.normalizeTrip(result.draft), forecast).ok);
    ok('live：沒受影響的站沒被動到', result.type === 'proposal' && ['國立臺灣史前文化博物館', '榕樹下米苔目(中華路創始老店-別無分店)'].every((n) => result.draft.stops.some((s) => s.name === n)));

    // 情境 D：使用者主動說累（T5）。不看天氣，看結束時間有沒有真的提早。
    console.log('\n── 情境 D（使用者：好累，想早點回飯店）──');
    const tired = await runAgent({ trip: TRIP, trigger: { type: 'user', message: '走了一早上好累，下午想輕鬆一點、早點回飯店' }, onEvent: () => {} });
    if (tired.type === 'proposal') {
      console.log('📋 ' + tired.summary);
      tired.changes.forEach((c) => console.log(`   ${c.type}: ${c.from || c.name || ''} ${c.to || ''}${c.stayTo ? ` 停留 ${c.stayFrom}→${c.stayTo} 分` : ''}`));
    } else console.log(JSON.stringify(tired).slice(0, 300));
    const endBefore = I.dayEnds(I.normalizeTrip(TRIP))[1];
    const endAfter = tired.type === 'proposal' ? I.dayEnds(I.normalizeTrip(tired.draft))[1] : null;
    console.log(`   結束時間 ${endBefore} → ${endAfter}、${tired.usage.llmCalls} 次 LLM、${(tired.ms / 1000).toFixed(1)}s`);
    ok('live D：產出提案', tired.type === 'proposal' && !tired.fallback);
    ok('live D：結束時間真的提早', endAfter && I.toMin(endAfter) < I.toMin(endBefore));

    // 情境 A：綠島回程船班停航風險（主秀）。第 4 天的真實預報也可能很差，Agent 要自己權衡。
    console.log('\n── 情境 A（綠島，模擬 ' + day3 + ' 浪高 3.5 公尺）──');
    const storm = await runAgent({
      trip: GREEN, trigger: { type: 'weather' }, scenario: SEA_SCENARIO,
      onEvent: (e) => {
        const t = (e.ms / 1000).toFixed(1).padStart(5) + 's ';
        if (e.type === 'tool_call') console.log(t + '🔧 ' + e.label);
        else if (e.type === 'tool_result') console.log(t + '   → ' + e.detail);
        else if (['check_result', 'fallback'].includes(e.type)) console.log(t + 'ℹ️  ' + e.label + (e.issues ? '：' + e.issues.join('；') : ''));
      }
    });
    console.log('');
    if (storm.type === 'proposal') {
      console.log('📋 ' + storm.summary);
      storm.reasons.forEach((r) => console.log('   • ' + r));
      storm.changes.forEach((c) => console.log('   ' + c.type + ': ' + (c.from || c.name || '') + ' ' + (c.type === 'move' ? '第' + c.fromDay + '天→第' + c.day + '天 ' + c.to : (c.to || ''))));
      const legText = (ls) => ls.map((l) => l.direction + 'D' + l.day + ' ' + l.depart).join('、');
      console.log('   船班 ' + legText(storm.ferry.before) + ' → ' + legText(storm.ferry.after) + (storm.extraNights ? '（多住 ' + storm.extraNights + ' 晚）' : ''));
    } else if (storm.type === 'question') {
      console.log('❓ ' + storm.question);
      storm.options.forEach((o) => console.log('   - ' + o));
    } else console.log(JSON.stringify(storm).slice(0, 400));
    console.log('   ' + storm.usage.llmCalls + ' 次 LLM、' + storm.steps + ' 次工具、' + (storm.ms / 1000).toFixed(1) + 's');
    ok('live A：產出提案或向使用者提問', storm.type === 'proposal' || storm.type === 'question');

    // 情境 A2：第 2～4 天都高風險，沒有安全的搭船日 → 要清楚告訴使用者並給選擇，不能硬排或報錯
    console.log('\n── 情境 A2（綠島，第 2～4 天都浪高 3.5～4 公尺）──');
    const noSafe = await runAgent({ trip: GREEN, trigger: { type: 'weather' }, scenario: STORM_ALL, onEvent: () => {} });
    if (noSafe.type === 'question') { console.log('❓ ' + noSafe.question); noSafe.options.forEach((o) => console.log('   - ' + o)); }
    else console.log(JSON.stringify(noSafe).slice(0, 300));
    console.log('   ' + noSafe.usage.llmCalls + ' 次 LLM、' + (noSafe.ms / 1000).toFixed(1) + 's');
    ok('live A2：沒有安全日 → 向使用者提問（不是錯誤）', noSafe.type === 'question' && noSafe.options.length >= 2);
    ok('live A2：問題裡沒有內部代號', noSafe.type === 'question' && !/\bg\d+\b|stopId|move_ferry/.test(noSafe.question + noSafe.options.join('')));
    if (storm.type === 'proposal') {
      const after = I.normalizeTrip(storm.draft);
      const v = I.validate(after, null, await seaOf(after, SEA_SCENARIO));
      ok('live A：提案沒有違規（含船班風險）', v.ok, JSON.stringify(v.violations));
      ok('live A：回程不在高風險的第 3 天', !F.legsOf(after).some((l) => l.direction === 'return' && I.dateOfDay(after.startDate, l.day) === day3));
    }
  }

  console.log(`\n${pass} passed, ${fail} failed`);
  process.exit(fail ? 1 : 0);
})().catch((e) => { console.error(e); process.exit(1); });
