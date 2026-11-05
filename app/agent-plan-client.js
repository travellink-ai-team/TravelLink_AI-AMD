/* ══════════════════════════════════════════════════
   行程生成 Agent 前端（方案 C）：POST {API_PROXY_BASE}/agent/plan（SSE）
   ──────────────────────────────────────────────────
   由 ai-travel-explore-final.html 以 <script src> 載入（在主程式之前），
   _doGeneration 只多一行：先試 Agent，失敗回 null 就照舊走一次生成。

   開關（預設關閉）：
     - weather.env.js：TRAVEL_APP_CONFIG.AGENT_PLAN_ENABLED = true
     - 或網址加 ?agentPlan=1（?agentPlan=0 強制關閉，優先於設定檔）
   範圍：只有一日遊；多日（兩天一夜）維持原本的一次生成流程。
   需要 API_PROXY_BASE（file:// 直開沒有後端，直接略過）與登入。

   Agent 回傳的站點格式對齊原本 AI JSON（name/time/duration/desc/lat/lng/businessHours/emoji），
   交給同一個 optimizeGeneratedTripStops 後處理，存檔與 planner 端完全不用改。
   ══════════════════════════════════════════════════ */
(function () {
  'use strict';

  var TOTAL_TIMEOUT_MS = 100000;   // 後端 90 秒逾時，再多留一點網路時間
  // 兩個事件之間最久等多久。gpt-oss 單次呼叫通常幾秒內；端點變慢時早點退回一般生成，別讓使用者乾等
  var IDLE_TIMEOUT_MS = 20000;

  function urlFlag() {
    try {
      var v = new URLSearchParams(window.location.search).get('agentPlan');
      if (v === '1' || v === 'true') return true;
      if (v === '0' || v === 'false') return false;
    } catch (_e) {}
    return null;
  }

  function proxyBase() {
    var cfg = window.TRAVEL_APP_CONFIG || {};
    return String(cfg.API_PROXY_BASE || '').replace(/\/+$/, '');
  }

  function isEnabled() {
    var u = urlFlag();
    if (u !== null) return u;
    var cfg = window.TRAVEL_APP_CONFIG || {};
    return cfg.AGENT_PLAN_ENABLED === true;
  }

  /** 讀 SSE：每個事件一行 data: {json}，空行分隔 */
  async function readSse(res, onEvent, resetIdle) {
    var reader = res.body.getReader();
    var decoder = new TextDecoder();
    var buffer = '';
    function flush(block) {
      var data = block.split(/\r?\n/).filter(function (l) { return l.indexOf('data:') === 0; })
        .map(function (l) { return l.slice(5).replace(/^ /, ''); }).join('\n');
      if (!data) return;
      try { onEvent(JSON.parse(data)); } catch (e) { console.warn('[agent-plan] 無法解析事件', data, e); }
    }
    for (;;) {
      var r = await reader.read();
      if (r.done) break;
      resetIdle();
      buffer += decoder.decode(r.value, { stream: true });
      var parts = buffer.split(/\r?\n\r?\n/);
      buffer = parts.pop();
      parts.forEach(flush);
    }
    buffer += decoder.decode();
    if (buffer.trim()) flush(buffer);
  }

  /**
   * 呼叫 /api/agent/plan。成功回傳 { title, reply, stops, agent }；失敗丟出錯誤。
   * @param {object} request  旅遊條件（見 server/agent/plan-route.js 的 parsePlanBody）
   * @param {object} opts     { onStep(label, event), headers }
   */
  async function plan(request, opts) {
    opts = opts || {};
    var base = proxyBase();
    if (!base) throw new Error('沒有設定 API_PROXY_BASE');
    var headers = opts.headers || { 'Content-Type': 'application/json' };
    if (!headers.Authorization) throw new Error('未登入');
    var ctrl = new AbortController();
    var total = setTimeout(function () { ctrl.abort(); }, TOTAL_TIMEOUT_MS);
    var idle = null;
    function resetIdle() { clearTimeout(idle); idle = setTimeout(function () { ctrl.abort(); }, IDLE_TIMEOUT_MS); }
    var result = null;
    try {
      resetIdle();
      var res = await fetch(base + '/agent/plan', { method: 'POST', headers: headers, body: JSON.stringify({ request: request }), signal: ctrl.signal });
      var type = String(res.headers.get('content-type') || '');
      if (!res.ok || type.indexOf('text/event-stream') < 0) {
        var msg = '';
        try { var j = await res.json(); msg = (j && (j.message || j.error)) || ''; } catch (_e) {}
        throw new Error(msg || ('HTTP ' + res.status));
      }
      await readSse(res, function (e) {
        if (e.type === 'tool_call' && typeof opts.onStep === 'function') opts.onStep(e.label, e);
        if (e.type === 'tool_result' && e.ok === false && e.tool === 'draft_itinerary' && typeof opts.onStep === 'function') opts.onStep('檢查到問題，AI 正在修改行程…', e);
        if (e.type === 'itinerary' || e.type === 'error') result = e;
      }, resetIdle);
    } catch (err) {
      if (err && err.name === 'AbortError') throw new Error('AI 規劃代理人逾時');
      throw err;
    } finally {
      clearTimeout(total);
      clearTimeout(idle);
    }
    if (!result) throw new Error('AI 規劃代理人沒有回傳結果');
    if (result.type === 'error') throw new Error(result.message || 'AI 規劃代理人失敗');
    if (!Array.isArray(result.stops) || result.stops.length < 2) throw new Error('AI 規劃代理人回傳的站點太少');
    var reasons = Array.isArray(result.reasons) ? result.reasons : [];
    return {
      title: result.title || '',
      reply: [result.summary || ''].concat(reasons.length ? ['（' + reasons.join('；') + '）'] : []).join(' ').trim(),
      stops: result.stops,
      returnTime: result.returnTime || '',
      agent: { ms: result.ms, steps: result.steps, llmCalls: result.usage && result.usage.llmCalls, draftRounds: result.draftRounds, warnings: result.warnings || [] }
    };
  }

  window.WAI_AGENT_PLAN = { isEnabled: isEnabled, plan: plan };
})();

/**
 * explore 主生成流程用：符合條件就先試 Agent，成功回傳 plan（同 requestGeminiMicroTravelPlan 的格式），
 * 不符合條件或失敗回 null，呼叫端接著走原本的一次生成。不會丟出錯誤。
 * 依賴 ai-travel-explore-final.js 的全域函式（isLongTrip、parseDurationMinutes、getBudgetTier、
 * vertexAuthHeaders、setWizGenStep），都以 typeof 防護。
 */
/** 最近兩趟同目的地行程排過的中間站名（不含起訖點），最多 20 個 */
function recentPlannedStopNames(dest) {
  try {
    if (typeof myTrips === 'undefined' || !Array.isArray(myTrips) || !dest) return [];
    var norm = function (s) { return String(s || '').replace(/臺/g, '台').trim(); };
    var target = norm(dest);
    var names = [];
    myTrips.filter(function (t) {
      return t && Array.isArray(t.stops) && t.stops.length && norm(t.region || (t.wizardData && t.wizardData.dest)) === target;
    }).slice(0, 2).forEach(function (t) {
      t.stops.forEach(function (s) {
        var n = s && (s.name || s.title);
        if (n && s.type !== 'start' && s.type !== 'end' && names.indexOf(n) < 0) names.push(String(n).slice(0, 40));
      });
    });
    return names.slice(0, 20);
  } catch (_e) { return []; }
}

async function tryAgentPlanForWizard(wData) {
  // 暫態旗標（同 _rainOutlook，不入庫）：optimizeGeneratedTripStops 看到它就保留後端排好的順序
  if (wData) wData._agentPlanned = false;
  try {
    var A = window.WAI_AGENT_PLAN;
    if (!A || !A.isEnabled() || !wData) return null;
    if (typeof isLongTrip === 'function' && isLongTrip(wData.days)) return null;   // 多日維持原流程
    var headers = typeof vertexAuthHeaders === 'function' ? await vertexAuthHeaders() : null;
    if (!headers || !headers.Authorization) return null;
    var tier = typeof getBudgetTier === 'function' ? getBudgetTier(wData.budget) : null;
    var step = function (label) { if (typeof setWizGenStep === 'function') setWizGenStep(1, '🤖 ' + label); };
    step('AI 規劃代理人啟動中…');
    var t0 = Date.now();
    var dest = wData.dest || wData.destCustom || '';
    var hub = typeof getDefaultTransitHub === 'function' ? getDefaultTransitHub(dest) : '';
    var startName = String(wData.startLocation || '').trim();
    var endName = String(wData.endLocation || '').trim() || startName;
    // 使用者自填的起訖點（門牌、路名、地標）後端的本地資料認不得，以前會退回目的地中心排程，
    // 地圖卻畫在 Places 查到的位置。這裡先查好座標一起送，排程與地圖用同一個點（同名查詢有快取，後處理不會重打）
    var coordOf = async function (name) {
      if (!name || typeof fetchCoordinateFromGooglePlaces !== 'function') return null;
      try {
        var c = await fetchCoordinateFromGooglePlaces(name, dest);
        return c && Number.isFinite(c.lat) && Number.isFinite(c.lng) ? { lat: c.lat, lng: c.lng } : null;
      } catch (_e) { return null; }
    };
    var coords = await Promise.all([coordOf(startName), endName === startName ? null : coordOf(endName)]);
    var startCoord = coords[0];
    var endCoord = endName === startName ? startCoord : coords[1];
    var plan = await A.plan({
      dest: wData.dest || wData.destCustom || '',
      date: wData.departureDate || '',
      startTime: wData.startTime || '09:00',
      durationMin: typeof parseDurationMinutes === 'function' ? parseDurationMinutes(wData.days) : 480,
      people: wData.people || '2人',
      pace: wData.pace || '',
      theme: wData.theme || '',
      interests: Array.isArray(wData.interests) ? wData.interests : [],
      budgetPerPerson: tier && tier.perMax != null ? tier.perMax : null,
      desiredSpots: wData.desiredSpots || '',
      // 沒填起訖點時用前端同一個預設轉運站（台東車站、島內港…），後端排路線與前端插入的起訖站一致
      startLocation: startName || hub,
      endLocation: endName || hub,
      startCoord: startCoord,
      endCoord: endCoord,
      // 分流：同一個人最近在同一目的地排過的站，後端會稍微降低它們的優先順序，避免每次都排到同一批
      avoidNames: recentPlannedStopNames(dest),
      transportMode: wData.transportMode || ''
    }, { headers: headers, onStep: step });
    wData._agentPlanned = true;
    // 暫態：回到終點的時刻（後處理把它寫到終點站，不入庫）
    wData._agentReturnTime = plan.returnTime || '';
    console.info('[AI 規劃代理人] 完成：' + plan.stops.length + ' 站、' + ((Date.now() - t0) / 1000).toFixed(1) + ' 秒、LLM ' + plan.agent.llmCalls + ' 次、草稿 ' + plan.agent.draftRounds + ' 輪');
    return plan;
  } catch (err) {
    console.warn('[AI 規劃代理人] 改用一般生成：', err && err.message);
    if (typeof setWizGenStep === 'function') setWizGenStep(1, 'AI 代理人未完成，改用一般生成…');
    return null;
  }
}
