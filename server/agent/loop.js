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

const MAX_TOOL_CALLS = 12;
const MAX_FAILED_ROUNDS = 3;
const MAX_LLM_CALLS = 14;
const TIMEOUT_MS = Number(process.env.AGENT_TIMEOUT_MS) || 60 * 1000;

const SYSTEM_PROMPT = [
  '你是 TravelLink AI 的「旅程應變代理人」，負責在台東旅途中發現問題、找替代方案、修好行程，再請使用者確認。',
  '工作方式：',
  '1. 先呼叫 get_trip_state 了解行程；與天氣有關時呼叫 get_weather_forecast。',
  '2. 只處理受影響的站，沒受影響的站不要動。',
  '3. 替代地點只能用 search_local_poi／search_restaurants 回傳的名稱，不可自行編造地名。下雨時找 indoor=true 的室內景點，優先選距離近、當天有營業的。景點換景點（search_local_poi），餐廳換餐廳（search_restaurants），不要把景點換成伴手禮店或餐廳。',
  '4. 用 propose_patch 組草稿；程式會驗證營業時間、時間順序、天氣與每天結束時間。有 violations 就修改後重送（ops 每次都要完整重給）。',
  '5. 使用者說累、想輕鬆或想早點結束時，優先用 remove 減少站數（或縮短 stayMin），不要只是替換地點。',
  '6. 驗證通過後呼叫 present_proposal，summary 用一句繁體中文說明改了什麼，reasons 寫原因（引用降雨機率、距離、營業時間等具體數字）。summary 與 reasons 只能描述草稿裡真的發生的修改，不可宣稱沒做到的效果。',
  '7. 只有在方案之間有明顯取捨（例如多花錢 vs 少去一個景點）時才用 ask_user；確認不需要修改就呼叫 no_change_needed。',
  '8. 每一輪都要呼叫工具，不要只回文字。全程使用繁體中文。'
].join('\n');

function triggerText(trigger) {
  const t = trigger || {};
  if (t.type === 'weather') return '系統偵測到行程期間有降雨機率偏高的時段，請檢查受影響的戶外行程並提出調整方案。';
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
    case 'check_business_hours': return `${r.name}：${r.status === 'open' ? r.hours : r.status}`;
    case 'estimate_travel': return `約 ${r.minutes} 分鐘`;
    case 'propose_patch': return r.ok ? '✅ 驗證通過' : `❌ ${r.violations.length} 項違規：${r.violations.slice(0, 2).join('；')}`;
    default: return '';
  }
}

/** 規則式降級：每個下雨的戶外站換成最近、當天有營業的室內景點 */
async function fallbackProposal(ctx, reason) {
  const forecast = await ctx.getForecast();
  const check = I.validate(ctx.trip, forecast);
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
  const v = I.validate(draft, forecast);
  if (!v.ok) return null;
  ctx.draft = draft;
  ctx.draftCheck = v;
  return {
    summary: '快速替代方案：下雨時段的戶外景點改成附近有營業的室內景點',
    reasons: [reason, '依距離與營業時間自動挑選，未經 AI 推理'],
    fallback: true
  };
}

function buildProposal(ctx, p) {
  const before = I.costPerPerson(ctx.trip);
  const after = I.costPerPerson(ctx.draft);
  return {
    type: 'proposal',
    summary: p.summary,
    reasons: p.reasons,
    fallback: Boolean(p.fallback),
    simulated: Boolean(ctx.scenario),
    changes: I.diffTrips(ctx.trip, ctx.draft),
    costDelta: { perPersonBefore: before, perPersonAfter: after, diff: after - before, note: '門票加餐費估算，不含交通' },
    warnings: ctx.draftCheck.warnings.slice(0, 5).map((w) => w.message),
    draft: { ...ctx.draft, stops: ctx.draft.stops.map((s) => ({ id: s.id, day: s.day, time: s.time, stayMin: s.stayMin, name: s.name, lat: s.lat, lng: s.lng, kind: s.kind, ...(s.replaces ? { replaces: s.replaces } : {}), ...(s.agentAdded ? { agentAdded: true } : {}) })) }
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
  const ctx = {
    trip, scenario: scenario && scenario.rain ? scenario : null,
    draft: null, draftCheck: null, failedRounds: 0,
    getForecast: () => (forecastPromise || (forecastPromise = D.getForecast(trip.region, scenario).then((f) => (f.periods.length ? f : null))))
  };
  const usage = { promptTokens: 0, completionTokens: 0, llmCalls: 0 };
  const finish = (e) => {
    const out = { ...e, usage, steps: toolCalls, model: amd.USAGE_MODEL_ID, ms: Date.now() - t0 };
    emit(out);
    return out;
  };
  let toolCalls = 0;

  emit({ type: 'start', trigger: trigger && trigger.type, simulated: Boolean(ctx.scenario), stops: trip.stops.length });

  // ── 程式先檢查：天氣觸發但沒有任何站受影響 → 不呼叫 LLM ──
  if (!trigger || trigger.type !== 'user') {
    emit({ type: 'check', label: '程式檢查天氣與營業時間（不呼叫 AI）' });
    const pre = I.validate(trip, await ctx.getForecast());
    const issues = pre.violations.filter((v) => ['rain', 'closed', 'hours', 'suspended'].includes(v.code));
    if (!issues.length) {
      return finish({ type: 'no_change', reason: pre.warnings.some((w) => w.code === 'weather_unknown') ? '拿不到天氣預報，暫時無法判斷' : '行程時段沒有降雨或公休問題', llmSkipped: true });
    }
    emit({ type: 'check_result', label: `發現 ${issues.length} 個問題，啟動 AI 代理人`, issues: issues.map((v) => v.message) });
  }

  if (!amd.isEnabled()) {
    const fb = await fallbackProposal(ctx, 'AI 代理人未啟用');
    return fb ? finish(buildProposal(ctx, fb)) : finish({ type: 'error', message: 'AI 代理人未啟用（AI_PROVIDER 不是 amd）' });
  }

  const messages = [
    { role: 'system', content: SYSTEM_PROMPT },
    { role: 'user', content: `${triggerText(trigger)}\n今天是 ${I.todayIso()}。` }
  ];
  let nudged = false;

  try {
    for (let call = 0; call < MAX_LLM_CALLS; call++) {
      const remaining = TIMEOUT_MS - (Date.now() - t0);
      if (remaining <= 0) throw Object.assign(new Error('timeout'), { code: 'timeout' });
      const res = await amd.chat({ messages, tools: T.DEFINITIONS, temperature: 0.2, maxTokens: 2048, signal: AbortSignal.timeout(remaining) });
      usage.llmCalls += 1;
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
        const result = await T.runTool(ctx, name, args);
        emit({ type: 'tool_result', tool: name, ok: !result.error, detail: summarizeResult(name, result) });
        messages.push({ role: 'tool', tool_call_id: c.id, content: JSON.stringify(result) });

        if (T.TERMINAL.has(name) && !result.error) {
          if (name === 'present_proposal') return finish(buildProposal(ctx, result));
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
    }[err.code] || `AI 代理人發生錯誤（${err.name === 'TimeoutError' ? '逾時' : err.message}）`;
    console.warn('[agent] 降級：' + why);
    emit({ type: 'fallback', label: why + '，改用快速替代方案' });
    const fb = await fallbackProposal(ctx, why);
    return fb ? finish(buildProposal(ctx, fb)) : finish({ type: 'error', message: why + '，也找不到快速替代方案' });
  }
}

module.exports = { runAgent };
