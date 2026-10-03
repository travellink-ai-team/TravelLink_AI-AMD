'use strict';
/* ══════════════════════════════════════════════════
   TravelLinkAI 旅程應變 Agent（gpt-oss-120b on AMD）
   ──────────────────────────────────────────────────
   流程（docs/amd-agent/agent-tools-and-replanning.md 3.2）：
     程式先偵測觸發事件 → 沒問題就不呼叫 LLM（省成本）
     → 有問題才啟動 loop：LLM 選工具 → 程式執行 → 結果回給 LLM
     → propose_patch 由程式驗證，違規就退回重做（最多 3 輪）
     → present_proposal／ask_user／no_change_needed 結束
   防護（3.3）：工具白名單、步數上限、總時限、失敗時降級成規則式替換。
   任何修改都只是提案，不會直接寫入使用者的行程。
   ══════════════════════════════════════════════════ */
const amd = require('../amd-llm');
const D = require('./data');
const I = require('./itinerary');
const T = require('./tools');
const F = require('./ferry');
const SEA = require('./sea');
const DL = require('./delay');

const MAX_TOOL_CALLS = 14;   // 離島情境要多查海況與船班
const MAX_FAILED_ROUNDS = 3;
const MAX_LLM_CALLS = 16;
const TIMEOUT_MS = Number(process.env.AGENT_TIMEOUT_MS) || 60 * 1000;

// 離島停航功能關閉時（預設），第 7 條換成「船班不動」；打開時用完整的停航處理規則（見 sea.js 的 enabled）
const RULE7_SEA = '7. 離島（綠島／蘭嶼）行程：港口站代表搭船，不能刪除或替換。若某段船班停航風險高，可選擇 (a) 把回程提前到風險低的日子；(b) 延後回程、多住一晚（day＝天數 + 1）。兩者都用一個 「type: move_ferry、direction: return、day、time」的 op 完成，港口站、之後的本島站、來不及去的島上站都由程式處理，結果會列出被刪掉的島上景點。之後可再用 insert 在本島補景點或餐廳。先用 get_sea_conditions 確認候選日子的風險：風險高的日子不能當作新的搭船日，也不能出現在選項裡。只剩一個可行方案時直接 propose_patch，不要問；兩案都可行且取捨明顯（少玩幾個景點 vs 多一晚住宿費）時才用 ask_user。使用者已經說明選擇時就直接照做。沒有船班時刻資料，提案要提醒向船公司確認班次。若提前回程與多住一晚的日子也都是高風險（沒有安全的搭船日），不要硬組草稿，直接用 ask_user：question 說明哪幾天風險高與依據（浪高、風力），options 給可行的選擇，例如「保留原訂，出發前再看船公司公告」「改成本島台東行程、不去綠島」「延後整趟行程」。';
const RULE7_NO_SEA = '7. 離島（綠島／蘭嶼）行程：港口站代表搭船，不能刪除、替換或改時間；船班不在調整範圍，只調整島上與本島的其他站。';
const SYSTEM_PROMPT_LINES = [
  '你是 TravelLink AI 的「旅程應變代理人」，負責在台東旅途中發現問題、找替代方案、修好行程，再請使用者確認。',
  '工作方式：',
  '1. 先呼叫 get_trip_state 了解行程；與天氣有關時呼叫 get_weather_forecast。',
  '2. 只處理受影響的站，沒受影響的站不要動。',
  '3. 替代地點只能用 search_local_poi／search_restaurants 回傳的名稱，不可自行編造地名。下雨時找 indoor=true 的室內景點，優先選距離近、當天有營業的。景點換景點（search_local_poi），餐廳換餐廳（search_restaurants），不要把景點換成伴手禮店或餐廳。',
  '4. 用 propose_patch 組草稿；程式會驗證營業時間、時間順序、天氣與每天結束時間。有 violations 就修改後重送（ops 每次都要完整重給）。',
  '5. 使用者說累、想輕鬆或想早點結束時，優先用 remove 減少站數（或縮短 stayMin），不要只是替換地點。',
  '6. 驗證通過後呼叫 present_proposal，summary 用一句繁體中文說明改了什麼，reasons 寫原因（引用降雨機率、距離、營業時間等具體數字）。summary 與 reasons 只能描述草稿裡真的發生的修改，不可宣稱沒做到的效果。',
  'RULE7_PLACEHOLDER',
  'ask_user 的 question 與 options 要用旅客看得懂的話（日期、時段、景點名稱、大約花費），不可出現 stopId、op 名稱等內部代號。',
  '8. 只有在方案之間有明顯取捨（例如多花錢 vs 少去一個景點）時才用 ask_user；確認不需要修改就呼叫 no_change_needed。',
  '9. 每一輪都要呼叫工具，不要只回文字。全程使用繁體中文。'
];
const systemPrompt = () => SYSTEM_PROMPT_LINES.map((l) => (l === 'RULE7_PLACEHOLDER' ? (SEA.enabled() ? RULE7_SEA : RULE7_NO_SEA) : l)).join('\n');

/**
 * gpt-oss 在 vLLM 上偶爾會吐出格式錯亂的回應，vLLM 解析失敗回 500
 * （例如 "unexpected tokens remaining in message header"）。這是暫時性的，
 * 同樣的對話再送一次通常就好，所以 5xx 重試最多 2 次；4xx 是請求本身的問題，不重試。
 */
async function chatWithRetry(messages, t0, tools = T.DEFINITIONS) {
  let lastErr;
  for (let attempt = 0; attempt < 3; attempt++) {
    const budget = TIMEOUT_MS - (Date.now() - t0);   // 重試也算在整次執行的總時限內
    if (budget <= 0) break;
    try {
      return await amd.chat({ messages, tools, temperature: 0.2, maxTokens: 2048, signal: AbortSignal.timeout(budget) });
    } catch (err) {
      lastErr = err;
      if (!(err.status >= 500)) throw err;
      console.warn(`[agent] LLM ${err.status}，重試第 ${attempt + 1} 次`);
    }
  }
  throw lastErr || Object.assign(new Error('timeout'), { code: 'timeout' });
}

function triggerText(trigger) {
  const t = trigger || {};
  if (t.type === 'weather') return SEA.enabled()
    ? '系統偵測到行程有天氣或海象風險（降雨、公休、或離島船班停航風險），請檢查受影響的部分並提出調整方案。'
    : '系統偵測到行程有天氣風險（降雨或公休），請檢查受影響的部分並提出調整方案。';
  if (t.type === 'user') return `使用者說：「${String(t.message || '').slice(0, 200)}」。請依需求調整行程。`;
  return '請檢查行程是否需要調整。';
}

function summarizeResult(name, r) {
  if (!r || r.error) return r && r.error ? `⚠️ ${r.error}` : '';
  switch (name) {
    case 'get_trip_state': return `${r.days} 天、${r.stops.length} 站`;
    case 'get_weather_forecast':
      return r.stopsInRain && r.stopsInRain.length
        ? `${r.stopsInRain.length} 站落在降雨時段：${r.stopsInRain.map((s) => `${s.name}（${s.pop}%）`).join('、')}`
        : (r.note || '行程時段沒有明顯降雨');
    case 'search_local_poi':
    case 'search_restaurants':
      return r.results.length ? `找到 ${r.results.length} 個：${r.results.slice(0, 3).map((x) => `${x.name} ${x.distanceKm}km`).join('、')}` : '範圍內沒有符合的地點';
    case 'get_sea_conditions':
      return r.days ? r.days.map((d) => `${d.date.slice(5)} ${{ high: '高', medium: '中', low: '低', unknown: '未知' }[d.risk]}${d.wave ? '（' + d.wave + '）' : ''}`).join('、') : (r.note || '');
    case 'get_ferry_status':
      return r.legs ? r.legs.map((l) => `第${l.day}天 ${l.depart} ${l.direction} 風險${{ high: '高', medium: '中', low: '低', unknown: '未知' }[l.risk]}`).join('、') + (Array.isArray(r.officialAlerts) ? '；有官方公告' : '') : (r.note || '');
    case 'check_business_hours': return `${r.name}：${r.status === 'open' ? r.hours : r.status}`;
    case 'estimate_travel': return `約 ${r.minutes} 分鐘`;
    case 'propose_patch': return r.ok ? '✅ 驗證通過' : `❌ ${r.violations.length} 項違規：${r.violations.slice(0, 2).join('；')}`;
    default: return '';
  }
}

/** 規則式降級：每個下雨的戶外站換成最近、當天有營業的室內景點 */
async function fallbackProposal(ctx, reason) {
  const check = await ctx.check(ctx.trip);
  const rainy = check.violations.filter((v) => v.code === 'rain');
  if (!rainy.length) return null;
  const used = new Set(ctx.trip.stops.map((s) => D.normName(s.name)));
  const ops = [];
  for (const v of rainy) {
    const s = ctx.trip.stops.find((x) => x.id === v.stopId);
    if (!s || !Number.isFinite(s.lat)) continue;
    const date = I.dateOfDay(ctx.trip.startDate, s.day);
    const pick = D.data().pois
      .filter((p) => !used.has(p._key) && D.indoorOf(p) === 'indoor' && !D.accessOf(p).avoid)
      .filter((p) => {
        const h = D.hoursOn(p, date);
        const start = I.toMin(s.time);
        return h.status === 'open' && D.fitsHours(h, start, start + Math.min(240, p.duration || 60));
      })
      .map((p) => ({ p, km: D.haversineKm(s, p) }))
      .filter((x) => x.km <= 25)
      .sort((a, b) => a.km - b.km)[0];
    if (!pick) continue;
    used.add(pick.p._key);
    ops.push({ type: 'replace', stopId: s.id, name: pick.p.name });
  }
  if (!ops.length) return null;
  const { draft } = I.applyOps(ctx.trip, ops);
  const v = await ctx.check(draft);
  if (!v.ok) return null;
  ctx.draft = draft;
  ctx.draftCheck = v;
  return {
    summary: '快速替代方案：下雨時段的戶外景點改成附近有營業的室內景點',
    reasons: [reason, '依距離與營業時間自動挑選，未經 AI 推理'],
    fallback: true
  };
}

const legBrief = (l) => ({ direction: l.direction === 'return' ? '回程' : '去程', day: l.day, depart: I.toClock(l.departMin) });

// 提案裡的站：原樣帶回用戶端送來的欄位；Agent 新增的站附上完整資料，
// 用戶端套用後可以直接顯示與導航，不用再為了補資料查一次 Google（T4 規格第 3 節第 4 點）
function draftStopView(s) {
  const out = { id: s.id, day: s.day, time: s.time, stayMin: s.stayMin, name: s.name, lat: s.lat, lng: s.lng, kind: s.kind };
  if (s.stopType) out.stopType = s.stopType;
  if (s.anchor) out.anchor = s.anchor;
  if (s.keepReason) out.keepReason = s.keepReason;
  if (s.replaces) out.replaces = s.replaces;
  if (s.agentAdded || s.replaces) {
    out.agentAdded = Boolean(s.agentAdded);
    const place = D.findPlace(s.name) || {};
    out.stopType = s.kind === 'food' ? 'food' : 'scenic';
    if (place.businessHours) out.businessHours = place.businessHours;
    if (place.desc) out.desc = place.desc;
    out.emoji = place.emoji || (s.kind === 'food' ? '🍽️' : '📍');
  } else if (s.businessHours) {
    out.businessHours = s.businessHours;
  }
  return out;
}

function buildProposal(ctx, p) {
  // 延誤情境的 ctx.trip 是「程式已順延」的版本；對照要用使用者原本的計畫
  const original = ctx.baseTrip || ctx.trip;
  const before = I.costPerPerson(original);
  const after = I.costPerPerson(ctx.draft);
  return {
    type: 'proposal',
    summary: p.summary,
    reasons: p.reasons,
    fallback: Boolean(p.fallback),
    simulated: Boolean(ctx.scenario),
    ...(p.retimeOnly ? { retimeOnly: true } : {}),
    changes: I.diffTrips(original, ctx.draft),
    costDelta: { perPersonBefore: before, perPersonAfter: after, diff: after - before, note: '門票加餐費估算，不含交通' + (ctx.draft.extraNights ? `與多 ${ctx.draft.extraNights} 晚的住宿費` : '') },
    ...(ctx.draft.extraNights ? { extraNights: ctx.draft.extraNights } : {}),
    ...(ctx.trip.island && SEA.enabled() ? { ferry: { before: F.legsOf(ctx.trip).map(legBrief), after: F.legsOf(ctx.draft).map(legBrief), note: '依海面預報推估，實際班次與是否停航以船公司公告為準' } } : {}),
    warnings: ctx.draftCheck.warnings.slice(0, 5).map((w) => w.message),
    draft: { ...ctx.draft, stops: ctx.draft.stops.map(draftStopView) }
  };
}

// 延誤只能刪站、縮短停留：不給 ask_user（問了也只能問出違反規格的選項，例如「接受延後結束」），
// 也不給找景點的工具。排不下就由驗證失敗走 delayFallback。
const DELAY_TOOLS = new Set(['get_trip_state', 'check_business_hours', 'estimate_travel', 'propose_patch', 'present_proposal']);
const DELAY_TOOL_DEFS = T.DEFINITIONS.filter((d) => DELAY_TOOLS.has(d.function && d.function.name));

// 延誤提案的時間結論由程式算：模型會把「錨點站排定時間＋停留」當成結束時間，
// 寫出「16:33 結束，符合 16:28 的車站時間」這種自相矛盾的句子。
function delayTimingLine(ctx) {
  const draft = ctx.draft;
  const req = ctx.delay;
  const stops = draft.stops;
  const idx = stops.map((s) => Boolean(s.anchor)).lastIndexOf(true);
  const train = req.returnTrain ? `，趕得上 ${I.toClock(req.returnTrain.min)} 的回程火車` : '';
  const ferry = req.lastFerry ? `，趕得上 ${I.toClock(req.lastFerry.min)} 的末班船` : '';
  if (idx < 0) return `今天約 ${I.dayEnds(draft)[req.day]} 結束${train}${ferry}`;
  const anchor = stops[idx];
  const prev = stops[idx - 1];
  const from = prev || DL.fromPoint(req, ctx.baseTrip);
  const leave = prev ? I.toMin(prev.time) + prev.stayMin : req.leave.min;
  const move = I.travel(draft, from, anchor);
  const arrive = leave + (Number.isFinite(move) ? move : 15);
  const head = prev ? `最後一站「${prev.name}」${I.toClock(leave)} 結束` : `從「${req.from.name}」${I.toClock(leave)} 出發`;
  return `${head}，約 ${I.toClock(arrive)} 到「${anchor.name}」（原訂 ${anchor.time}）${train}${ferry}`;
}

function delayWording(ctx, p) {
  const changes = I.diffTrips(ctx.baseTrip, ctx.draft);
  const removed = changes.filter((c) => c.type === 'remove').map((c) => `「${c.from}」`);
  const shortened = changes.filter((c) => c.type === 'retime' && Number.isFinite(c.stayTo) && c.stayTo < c.stayFrom)
    .map((c) => `「${c.name}」停留縮短為 ${c.stayTo} 分`);
  const parts = [removed.length ? `刪掉${removed.join('')}` : '', ...shortened].filter(Boolean);
  const reasons = (Array.isArray(p.reasons) ? p.reasons : []).filter((r) => !/\d{1,2}:\d{2}/.test(String(r)));
  return {
    ...p,
    summary: parts.length ? parts.join('，') + '，其餘依車程順延' : p.summary,
    reasons: [delayTimingLine(ctx), ...reasons]
  };
}

// 延誤情境的規則式降級：從後面刪不固定的站再順延，直到排得下
function delayFallback(ctx, why) {
  const r = DL.fallback(ctx.baseTrip, ctx.delay);
  if (!r) return null;
  ctx.draft = r.draft;
  ctx.draftCheck = r.check;
  return {
    summary: r.removed.length ? `快速調整：刪掉${r.removed.map((n) => `「${n}」`).join('')}，其餘依車程順延` : '快速調整：依車程順延',
    reasons: [delayTimingLine(ctx), why, '依時間順序自動刪站，未經 AI 推理'],
    fallback: true
  };
}

/**
 * @param {object} opts
 * @param {object} opts.trip      前端送來的行程（會重新正規化）
 * @param {object} opts.trigger   { type: 'weather'|'user', message? }
 * @param {object} [opts.scenario] demo 情境注入，例如 { rain: { date, from, to, pop } }
 * @param {function} opts.onEvent 每一步呼叫一次，參數是可直接送給前端的事件物件
 * @param {function} [opts.onUsage] 每次 LLM 呼叫後回報 OpenAI usage（記帳用）
 * @returns {Promise<object>} 最後一個事件（proposal / question / no_change / error）
 */
async function runAgent({ trip: rawTrip, trigger, scenario, onEvent, onUsage }) {
  const t0 = Date.now();
  const emit = (e) => { try { onEvent && onEvent({ ...e, ms: Date.now() - t0 }); } catch (_e) {} };
  const trip = I.normalizeTrip(rawTrip);
  if (!trip.stops.length) {
    const e = { type: 'error', message: '行程沒有任何站點' };
    emit(e);
    return e;
  }

  let forecastPromise = null;
  const seaCache = new Map();
  const ctx = {
    trip, scenario: scenario && (scenario.rain || (SEA.enabled() && scenario.sea)) ? scenario : null,
    draft: null, draftCheck: null, failedRounds: 0,
    getForecast: () => (forecastPromise || (forecastPromise = D.getForecast(trip.region, scenario).then((f) => (f.periods.length ? f : null)))),
    // 離島行程每一天（含可能多住的一晚）的海況，同一天只查一次
    getSea: async (t) => {
      if (!SEA.enabled()) return null;   // 停航功能關閉：validate 不檢查船班風險
      if (!t.island) return {};
      const out = {};
      for (let d = 1; d <= Math.min(14, t.days + 1); d++) {
        const date = I.dateOfDay(t.startDate, d);
        if (!seaCache.has(date)) seaCache.set(date, SEA.seaOn(t.island, date, scenario));
        out[date] = await seaCache.get(date);
      }
      return out;
    },
    check: async (t) => I.validate(t, await ctx.getForecast(), await ctx.getSea(t))
  };
  const usage = { promptTokens: 0, completionTokens: 0, llmCalls: 0 };
  const upstreamModels = new Set();
  const finish = (e) => {
    // model：端點實際回報的名稱（每次呼叫都收集，正常只會有一個）。沒呼叫 LLM 時是空陣列
    const out = { ...e, usage, steps: toolCalls, model: amd.USAGE_MODEL_ID, upstreamModels: [...upstreamModels], ms: Date.now() - t0 };
    emit(out);
    return out;
  };
  let toolCalls = 0;

  // ── T4 延誤：程式先順延，排得下就不呼叫 AI ──
  let firstUserMessage = null;
  if (trigger && trigger.type === 'delay') {
    const req = DL.parseDelayRequest(trigger, rawTrip, scenario);
    if (req.error) return finish({ type: 'error', message: req.error });
    const base = DL.dayTrip(trip, req);
    emit({ type: 'start', trigger: 'delay', simulated: req.simulated, stops: base.stops.length });
    if (!base.stops.length) return finish({ type: 'no_change', reason: `第 ${req.day} 天後面沒有行程了`, llmSkipped: true });
    if (req.appConflicts.length) console.log('[agent] T4 appConflicts（用戶端判斷，僅記錄）：' + JSON.stringify(req.appConflicts));
    ctx.delay = req;
    ctx.baseTrip = base;
    ctx.scenario = req.simulated ? scenario : null;
    ctx.trip = DL.shift(base, req);
    ctx.check = async (t) => DL.check(t, base, req);

    emit({ type: 'check', label: `程式依車程順延後面的站，檢查營業時間與期限（不呼叫 AI）` });
    const pre = await ctx.check(ctx.trip);
    if (pre.ok) {
      ctx.draft = ctx.trip;
      ctx.draftCheck = pre;
      const moved = I.diffTrips(base, ctx.draft).filter((c) => c.type === 'retime');
      const end = I.dayEnds(ctx.draft)[req.day];
      const reasons = [`從「${req.from.name}」${I.toClock(req.leave.min)} 出發，依車程重新估算後面每一站的時間`];
      if (req.returnTrain) reasons.push(`仍趕得上 ${I.toClock(req.returnTrain.min)} 的回程火車`);
      if (req.lastFerry) reasons.push(`仍趕得上 ${I.toClock(req.lastFerry.min)} 的末班船`);
      reasons.push('營業時間與每天結束時間都檢查過');
      return finish({
        ...buildProposal(ctx, {
          summary: moved.length
            ? `${req.delayMin != null ? `延誤 ${req.delayMin} 分鐘：` : ''}後面 ${moved.length} 站順延，${end} 結束，都來得及`
            : '延誤不影響後面的行程，時間都不用改',
          reasons,
          retimeOnly: true
        }),
        llmSkipped: true
      });
    }
    const issues = pre.violations.map((v) => v.message);
    emit({ type: 'check_result', label: `順延後有 ${issues.length} 個衝突，啟動 AI 代理人`, issues });
    if (!amd.isEnabled()) {
      const fb = delayFallback(ctx, 'AI 代理人未啟用');
      return fb ? finish(buildProposal(ctx, fb))
        : finish({ type: 'error', message: '延誤後的行程排不下，自動刪站也解決不了（多半是固定的站或期限本身就來不及）：' + issues.slice(0, 2).join('；') });
    }
    firstUserMessage = DL.triggerText(req, issues, base);
  } else {
    emit({ type: 'start', trigger: trigger && trigger.type, simulated: Boolean(ctx.scenario), stops: trip.stops.length });
  }

  // ── 程式先檢查：天氣觸發但沒有任何站受影響 → 不呼叫 LLM ──
  if (!firstUserMessage && (!trigger || trigger.type !== 'user')) {
    const seaOn = Boolean(trip.island) && SEA.enabled();
    emit({ type: 'check', label: seaOn ? '程式檢查天氣、營業時間與海象（不呼叫 AI）' : '程式檢查天氣與營業時間（不呼叫 AI）' });
    const pre = await ctx.check(trip);
    const issues = pre.violations.filter((v) => ['rain', 'closed', 'hours', 'suspended', 'ferry_risk'].includes(v.code));
    if (!issues.length) {
      return finish({ type: 'no_change', reason: pre.warnings.some((w) => w.code === 'weather_unknown') ? '拿不到天氣預報，暫時無法判斷' : (seaOn ? '行程時段沒有降雨、公休或船班停航風險' : '行程時段沒有降雨或公休問題'), llmSkipped: true });
    }
    emit({ type: 'check_result', label: `發現 ${issues.length} 個問題，啟動 AI 代理人`, issues: issues.map((v) => v.message) });
  }

  if (!firstUserMessage && !amd.isEnabled()) {
    const fb = await fallbackProposal(ctx, 'AI 代理人未啟用');
    return fb ? finish(buildProposal(ctx, fb)) : finish({ type: 'error', message: 'AI 代理人未啟用（AI_PROVIDER 不是 amd）' });
  }

  const messages = [
    { role: 'system', content: systemPrompt() },
    { role: 'user', content: firstUserMessage || `${triggerText(trigger)}\n今天是 ${I.todayIso()}。` }
  ];
  let nudged = false;

  try {
    for (let call = 0; call < MAX_LLM_CALLS; call++) {
      const remaining = TIMEOUT_MS - (Date.now() - t0);
      if (remaining <= 0) throw Object.assign(new Error('timeout'), { code: 'timeout' });
      const res = await chatWithRetry(messages, t0, ctx.delay ? DELAY_TOOL_DEFS : T.definitions());
      usage.llmCalls += 1;
      if (res.model) upstreamModels.add(res.model);
      if (res.usage) {
        usage.promptTokens += Number(res.usage.prompt_tokens) || 0;
        usage.completionTokens += Number(res.usage.completion_tokens) || 0;
        if (onUsage) { try { onUsage(res.usage); } catch (_e) {} }
      }
      messages.push(res.message);

      const calls = res.message.tool_calls || [];
      if (!calls.length) {
        if (nudged) throw Object.assign(new Error('model stopped calling tools'), { code: 'no_tools' });
        nudged = true;
        messages.push({ role: 'user', content: '請用工具繼續，最後一定要呼叫 present_proposal、ask_user 或 no_change_needed 其中之一。' });
        continue;
      }

      for (const c of calls) {
        const name = c.function && c.function.name;
        let args = {};
        try { args = JSON.parse((c.function && c.function.arguments) || '{}'); } catch (_e) {}
        toolCalls += 1;
        if (toolCalls > MAX_TOOL_CALLS) throw Object.assign(new Error('too many tool calls'), { code: 'max_steps' });
        emit({ type: 'tool_call', tool: name, label: T.labelOf(name, args) });
        const result = ctx.delay && !DELAY_TOOLS.has(name)
          ? { error: `延誤調整只能刪站或縮短停留，不能用 ${name}` }
          : await T.runTool(ctx, name, args);
        emit({ type: 'tool_result', tool: name, ok: !result.error, detail: summarizeResult(name, result) });
        messages.push({ role: 'tool', tool_call_id: c.id, content: JSON.stringify(result) });

        if (T.TERMINAL.has(name) && !result.error) {
          if (name === 'present_proposal') return finish(buildProposal(ctx, ctx.delay ? delayWording(ctx, result) : result));
          if (name === 'ask_user') return finish({ type: 'question', question: result.question, options: result.options });
          return finish({ type: 'no_change', reason: result.reason });
        }
      }
      if (ctx.failedRounds >= MAX_FAILED_ROUNDS) throw Object.assign(new Error('validation failed 3 times'), { code: 'validation' });
    }
    throw Object.assign(new Error('too many llm calls'), { code: 'max_steps' });
  } catch (err) {
    const why = {
      timeout: 'AI 代理人逾時', max_steps: 'AI 代理人步數超過上限', validation: 'AI 草稿連續 3 輪驗證失敗', no_tools: 'AI 代理人沒有完成流程'
    }[err.code] || (err.name === 'TimeoutError' ? 'AI 代理人逾時' : 'AI 服務暫時無法回應');
    // 原始錯誤只進伺服器日誌：上游訊息可能含模型內部格式，不該出現在使用者畫面
    console.warn('[agent] 降級：' + why + (err.code ? '' : '｜' + String(err.message || err).slice(0, 300)));
    // 規則式替換只會處理「下雨的戶外站」；使用者主動提的需求（好累、想早點結束）沒有規則可套，直接請他再試
    const fb = ctx.delay ? delayFallback(ctx, why)
      : (trigger && trigger.type === 'user' ? null : await fallbackProposal(ctx, why));
    if (fb) {
      emit({ type: 'fallback', label: why + '，改用快速替代方案' });
      return finish(buildProposal(ctx, fb));
    }
    return finish({ type: 'error', message: why + '，請稍後再試一次。' });
  }
}

module.exports = { runAgent };
