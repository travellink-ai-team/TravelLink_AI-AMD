/* ══════════════════════════════════════════════════
   主動式旅途管家（方案 D）
   ──────────────────────────────────────────────────
   行程進行中（以及出發前一晚、出發當天）在背景定期做「程式檢查」，不呼叫 AI：
     1. 落後：目前這站已經比原訂離開時間晚 ≥ 20 分，而且照現在離開，後面的站會撞營業時間或當天結束時間
     2. 天氣轉壞：還沒去的戶外站，所在時段的降雨機率 ≥ 70%（CWA 預報，與天氣頁同一個來源）
     3. 公休：還沒去的站當天公休（與行程卡同一個 business-hours.js 判斷）
   偵測到才在頁面頂端出現提示列；使用者按「看建議」才走既有的 AI 隨行管家流程
   （/api/agent/replan → 建議修改卡 → 按「套用」才寫入）。這支檔案不會直接改行程。

   - 天氣／公休在顯示前會再問後端 /api/agent/precheck（同一套程式檢查、不呼叫 AI），
     後端也認為有問題才提醒，避免使用者點開後得到「程式檢查沒發現問題」。mock 模式不問。
   - 去重：同一個問題（key）只提醒一次；按「不用」後，同類提醒冷卻 60 分鐘。
     記在 localStorage（每份行程分開）；展示模擬與 ?agentMock 只記在記憶體，不影響正式紀錄。
   - 多人共編：只有行程擁有者的裝置做背景偵測（單人行程不受限），見 docs/amd-agent/proactive-concierge.md。
   - 依賴 ai-travel-planner-v8.js 的全域（replanStops、startAgentRun…），必須在它之後載入。
   - ?proactive=0 可整個關掉。
   ══════════════════════════════════════════════════ */
(function () {
  'use strict';
  if (typeof replanStops === 'undefined' || typeof startAgentRun !== 'function' || typeof buildReplanSchedule !== 'function') {
    console.warn('[proactive] 找不到 planner 的管家函式，主動提醒停用');
    return;
  }
  const PARAMS = (() => { try { return new URLSearchParams(window.location.search); } catch (_e) { return new URLSearchParams(''); } })();
  if (PARAMS.get('proactive') === '0') return;

  const CFG = Object.assign({
    rainPop: 70,                 // 戶外站時段降雨機率門檻（%）
    lagMin: 20,                  // 落後門檻（分鐘）
    pollMs: 60 * 1000,           // 正式：每分鐘檢查一次（天氣有 30 分鐘快取，不會每次打 API）
    simPollMs: 4000,             // 展示模擬：虛擬時鐘跑得快，4 秒檢查一次
    cooldownMin: 60,             // 按「不用」後，同類提醒冷卻多久
    eveningHour: 18,             // 出發前一晚幾點起做預檢
    precheckTtlMs: 30 * 60 * 1000
  }, window.WAI_PROACTIVE_CONFIG || {});
  const STORE_KEY = 'wai_proactive_v1';
  const KIND_ORDER = { lag: 0, weather: 1, closed: 2 };

  const pc = {
    lastRun: 0, checking: false, current: null, demoLagMin: null,
    memory: {}, wasEphemeral: false, precheck: new Map(), lastTripId: ''
  };

  // ── 小工具 ──
  const taipei = (ms) => new Date(ms + 8 * 3600 * 1000);
  const isoOf = (ms) => taipei(ms).toISOString().slice(0, 10);
  const hourOf = (ms) => taipei(ms).getUTCHours();
  const clock = (ms) => taipei(ms).toISOString().slice(11, 16);
  const runtimeNow = () => (typeof getTripRuntimeNow === 'function' ? getTripRuntimeNow() : Date.now());
  const simOn = () => typeof tripSimulation !== 'undefined' && tripSimulation && tripSimulation.enabled;
  const mockOn = () => typeof AGENT_MOCK_MODE !== 'undefined' && !!AGENT_MOCK_MODE;
  const demoOn = () => typeof AGENT_DEMO_MODE !== 'undefined' && !!AGENT_DEMO_MODE;
  const toast = (msg, color) => { if (typeof feedbackToast === 'function') feedbackToast(msg, color || 'blue'); };
  const stopKey = (s, i) => String((s && (s.collabStopId || s.id)) || i);

  // 出發日（YYYY-MM-DD）；沒有就回 ''（agentIsoDate 會退回今天，這裡不要）
  function tripStartDate() {
    const prefs = (typeof currentTripPreferences !== 'undefined' && currentTripPreferences) || {};
    const raw = (typeof currentTripDepartureDate !== 'undefined' && currentTripDepartureDate) || prefs.departureDate || prefs.startDate || '';
    const m = /^(\d{4})[-/.](\d{1,2})[-/.](\d{1,2})/.exec(String(raw).trim());
    return m ? `${m[1]}-${m[2].padStart(2, '0')}-${m[3].padStart(2, '0')}` : '';
  }
  // 排程上的分鐘（第 N 天＝(N−1)×1440＋時刻）→ 台灣時間的毫秒
  function rowMs(startDate, minutes) {
    return Date.parse(`${startDate}T00:00:00+08:00`) + minutes * 60000;
  }
  function dayLabel(ms, nowMs) {
    const d = isoOf(ms);
    if (d === isoOf(nowMs)) return '';
    if (d === isoOf(nowMs + 86400000)) return '明天 ';
    const t = taipei(ms);
    return `${t.getUTCMonth() + 1}/${t.getUTCDate()} `;
  }
  function namesText(list) {
    const names = list.map((x) => `「${x}」`);
    return names.length > 3 ? `${names.slice(0, 3).join('、')} 等 ${names.length} 站` : names.join('、');
  }

  // ── 去重與冷卻 ──
  function ephemeral() { return simOn() || mockOn(); }
  function loadStore() {
    if (ephemeral()) return pc.memory;
    try { return JSON.parse(localStorage.getItem(STORE_KEY) || '{}') || {}; } catch (_e) { return {}; }
  }
  function saveStore(store) {
    if (ephemeral()) { pc.memory = store; return; }
    try {
      // 只留最近 20 份行程的紀錄
      const ids = Object.keys(store).sort((a, b) => (store[b].touched || 0) - (store[a].touched || 0));
      ids.slice(20).forEach((id) => { delete store[id]; });
      localStorage.setItem(STORE_KEY, JSON.stringify(store));
    } catch (_e) { /* 私密模式等：只是少了去重，功能照常 */ }
  }
  function bucket(store) {
    const id = String(currentItineraryId || '');
    store[id] = store[id] || { seen: {}, cooldown: {} };
    store[id].touched = Date.now();
    return store[id];
  }
  function wasSeen(key) { return !!bucket(loadStore()).seen[key]; }
  function markSeen(key, outcome) {
    const store = loadStore();
    bucket(store).seen[key] = { at: Date.now(), outcome };
    saveStore(store);
  }
  function unmarkSeen(key) {
    const store = loadStore();
    delete bucket(store).seen[key];
    saveStore(store);
  }
  function coolingDown(kind) {
    const until = Number(bucket(loadStore()).cooldown[kind]) || 0;
    return until > Date.now();
  }
  function setCooldown(kind) {
    const store = loadStore();
    bucket(store).cooldown[kind] = Date.now() + CFG.cooldownMin * 60000;
    saveStore(store);
  }
  function resetMemory() {
    pc.memory = {};
    pc.precheck.clear();
    if (!ephemeral()) {
      const store = loadStore();
      delete store[String(currentItineraryId || '')];
      saveStore(store);
    }
  }

  // ── 誰來偵測（多人共編）──
  // 單人行程：自己。共編行程：只有擁有者的裝置做背景偵測，旅伴裝置不各自觸發。
  // 擁有者套用後，既有的共編同步會把結果推給旅伴；唯讀成員永遠不偵測。
  function leaderOk() {
    if (typeof collabReadOnly !== 'undefined' && collabReadOnly) return false;
    if (typeof currentTripIsCollab === 'undefined' || !currentTripIsCollab) return true;
    return typeof collabRole !== 'undefined' && collabRole === 'owner';
  }

  // 現在該不該檢查：行程進行中、出發當天、出發前一晚（18 點後）
  function phaseOf(nowMs) {
    if (currentTripStatus === 'ongoing') return 'ongoing';
    if (currentTripStatus !== 'planning') return '';
    const dep = tripStartDate();
    if (!dep) return '';
    if (dep === isoOf(nowMs)) return 'today';
    if (dep === isoOf(nowMs + 86400000) && hourOf(nowMs) >= CFG.eveningHour) return 'eve';
    return '';
  }

  // 還沒去的站從哪一站開始（行程進行中＝下一個要去的站；其餘從頭）
  function firstPendingIndex() {
    return currentTripStatus === 'ongoing' ? Math.max(0, currentStopIndex) : 0;
  }

  function isOutdoorStop(stop) {
    if (!stop || !stop.name || stop.type === 'start' || stop.type === 'end') return false;
    if (typeof isFoodStop === 'function' && isFoodStop(stop)) return false;
    if (typeof agentAnchorOf === 'function' && agentAnchorOf(stop)) return false;   // 車站、住宿、港口
    if (typeof stop.isOutdoor === 'boolean') return stop.isOutdoor;
    return typeof classifyIndoorOutdoor === 'function' ? classifyIndoorOutdoor(stop.name, stop.desc) : false;
  }

  // demo 面板「模擬：下午大雨」勾選時，偵測也用同一份模擬天氣（送 replan 時 startAgentRun 會帶同一個 scenario）
  function demoRain() {
    if (!demoOn() || typeof readAgentRainScenario !== 'function') return null;
    const s = readAgentRainScenario();
    return s && s.rain ? s.rain : null;
  }

  async function forecastPeriods() {
    const rain = demoRain();
    if (rain) {
      return [{ startMs: Date.parse(`${rain.date}T${rain.from}:00+08:00`), endMs: Date.parse(`${rain.date}T${rain.to}:00+08:00`), pop: rain.pop, simulated: true }];
    }
    if (typeof fetchTaitungWeather !== 'function') return [];
    let data = null;
    try { data = await fetchTaitungWeather(); } catch (_e) { data = null; }
    const parse = (s) => { const d = typeof parseWeatherTime === 'function' ? parseWeatherTime(s) : new Date(s); return d ? d.getTime() : NaN; };
    return ((data && data.periods) || []).map((p) => ({ startMs: parse(p.start), endMs: parse(p.end), pop: Number(p.pop) || 0, wx: p.wx || '' }))
      .filter((p) => Number.isFinite(p.startMs) && Number.isFinite(p.endMs));
  }

  // ── 偵測 1：落後 ──
  function detectLag(nowMs) {
    if (currentTripStatus !== 'ongoing' || typeof getStayingStopIndex !== 'function') return null;
    const idx = getStayingStopIndex();
    if (idx < 0) return null;
    const stop = replanStops[idx];
    const day = clampDayIndex(stop.dayIndex, 1);
    // 跟 buildDelayPayload 同一個條件：今天後面還有站（含終點錨點）才有東西可以調整
    const hasLater = replanStops.slice(idx + 1).some((s) => s && s.name && clampDayIndex(s.dayIndex, 1) === day);
    if (!hasLater) return null;
    const schedule = buildReplanSchedule();
    const row = schedule[idx];
    const demoMinutes = Number.isFinite(pc.demoLagMin) ? pc.demoLagMin : null;
    const nowMin = demoMinutes != null
      ? agentPlannedSchedule(idx)[idx].end + demoMinutes
      : clockToScheduleMinutes(new Date(nowMs), row.dayIndex, row.start);
    const lag = Math.round(nowMin - row.end);
    if (lag < CFG.lagMin) return null;
    // 試算「現在才離開」：只改記憶體、算完立刻還原
    const saved = stop.expectedLeaveMin;
    let after;
    stop.expectedLeaveMin = nowMin;
    try { after = buildReplanSchedule(); } finally { stop.expectedLeaveMin = saved; }
    const impact = assessLeaveImpact(schedule, after, idx, {
      dayEnds: getTripDayEndMinutes(),
      hoursWarning: (r) => getBusinessHoursWarning(r)
    });
    if (!impact.conflicts.length) return { quiet: true, kind: 'lag', lag };
    const hours = impact.conflicts.filter((c) => c.kind === 'hours');
    const dayEnd = impact.conflicts.find((c) => c.kind === 'day-end');
    const what = hours.length
      ? `${namesText(hours.map((c) => c.name))}可能趕不上`
      : `今天會超過 ${toClockFieldValue(dayEnd.limit)} 才結束`;
    return {
      kind: 'lag',
      key: `lag|${stopKey(stop, idx)}|${impact.conflicts.map((c) => c.kind + ':' + (c.stopId || c.day)).join(',')}`,
      icon: '⏱',
      title: `比原訂晚 ${lag} 分，${what}，管家有建議`,
      detail: `你還在「${stop.name}」。按「看建議」會以 ${toClockFieldValue(nowMin)} 當作預計離開時間，請管家調整今天後面的行程。`,
      simulated: demoMinutes != null || simOn(),
      stopId: stop.id, leaveMin: Math.round(nowMin), demoMinutes
    };
  }

  // ── 偵測 2、3：天氣與公休（還沒去的站）──
  async function detectWeatherAndClosed(nowMs, phase) {
    const startDate = tripStartDate();
    if (!startDate) return [];
    const schedule = buildReplanSchedule();
    const from = firstPendingIndex();
    const nowMin = (nowMs - Date.parse(`${startDate}T00:00:00+08:00`)) / 60000;
    const pending = [];
    for (let i = from; i < replanStops.length; i += 1) {
      const stop = replanStops[i];
      const row = schedule[i];
      if (!stop || !row || !stop.name || stop.type === 'start' || stop.type === 'end') continue;
      if (phase === 'ongoing' && row.end <= nowMin) continue;
      // 出發前一晚／當天：只看第一天以及預報涵蓋得到的日子，交給預報範圍自然過濾
      pending.push({ i, stop, row, startMs: rowMs(startDate, row.start), endMs: rowMs(startDate, row.end) });
    }
    if (!pending.length) return [];
    const out = [];

    const periods = await forecastPeriods();
    const wet = [];
    pending.forEach((p) => {
      if (!isOutdoorStop(p.stop)) return;
      const hit = periods.find((w) => w.pop >= CFG.rainPop && w.startMs < p.endMs && w.endMs > p.startMs);
      if (hit) wet.push({ ...p, pop: hit.pop, atMs: Math.max(p.startMs, hit.startMs), simulated: !!hit.simulated });
    });
    if (wet.length) {
      wet.sort((a, b) => a.atMs - b.atMs);
      const first = wet[0];
      const pop = Math.max(...wet.map((w) => w.pop));
      out.push({
        kind: 'weather',
        key: `rain|${isoOf(first.atMs)}|${wet.map((w) => stopKey(w.stop, w.i)).sort().join(',')}`,
        icon: '⚠️',
        title: `${dayLabel(first.atMs, nowMs)}${clock(first.atMs)} 起降雨 ${pop}%，管家有建議`,
        detail: `${namesText(wet.map((w) => w.stop.name))}是戶外行程`,
        simulated: wet.some((w) => w.simulated),
        date: isoOf(first.atMs)
      });
    }

    const closed = pending.filter((p) => typeof getBusinessHoursWarning === 'function' && getBusinessHoursWarning(p.row) === '⚠️ 當天公休');
    if (closed.length) {
      const first = closed[0];
      out.push({
        kind: 'closed',
        key: `closed|${closed.map((c) => isoOf(c.startMs) + ':' + stopKey(c.stop, c.i)).sort().join(',')}`,
        icon: '🚫',
        title: closed.length > 1
          ? `${closed.length} 個站當天公休，管家有建議`
          : `「${first.stop.name}」${dayLabel(first.startMs, nowMs) || '今天'}公休，管家有建議`,
        detail: closed.length > 1 ? namesText(closed.map((c) => c.stop.name)) : '依營業時間資料判斷，出發前可以先換掉。',
        simulated: false
      });
    }
    return out;
  }

  // ── 後端確認（天氣、公休）：同一套程式檢查，不呼叫 AI ──
  async function confirmWithServer(issue) {
    if (issue.kind === 'lag' || mockOn()) return true;
    const base = typeof VERTEX_PROXY_BASE !== 'undefined' ? VERTEX_PROXY_BASE : '';
    const user = typeof firebaseAuth !== 'undefined' && firebaseAuth && firebaseAuth.currentUser;
    if (!base || !user || typeof buildAgentTripPayload !== 'function') return true;   // 沒有後端可問：以本地判斷為準
    const built = buildAgentTripPayload();
    if (!built) return false;
    const rain = demoRain();
    const cacheKey = `${issue.key}|${agentTripSignature()}|${rain ? JSON.stringify(rain) : ''}`;
    const hit = pc.precheck.get(cacheKey);
    if (hit && hit.exp > Date.now()) return hit.alert;
    try {
      const token = await user.getIdToken();
      const body = { trip: built.trip, trigger: { type: 'weather' } };
      if (rain) body.scenario = { rain };
      const res = await fetch(`${base}/agent/precheck`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json', Authorization: 'Bearer ' + token },
        body: JSON.stringify(body)
      });
      if (!res.ok) {
        // 後端暫時不行（例如舊版沒有這支）：不擋提醒，也記住結果，別每分鐘重打一次
        pc.precheck.set(cacheKey, { exp: Date.now() + CFG.precheckTtlMs, alert: true });
        return true;
      }
      const json = await res.json();
      // 後端的 alert 是「有任何問題」；這則提醒要後端也找到同一類問題才成立
      // （避免後端只查到公休，卻跳出降雨提示列）
      const codes = new Set(((json && json.issues) || []).map((v) => v.code));
      const alert = !!(json && json.alert) && (issue.kind === 'weather'
        ? codes.has('rain')
        : ['closed', 'suspended', 'hours'].some((c) => codes.has(c)));
      pc.precheck.set(cacheKey, { exp: Date.now() + CFG.precheckTtlMs, alert });
      return alert;
    } catch (_e) {
      return true;
    }
  }

  // ── 提示列 ──
  function ensureBanner() {
    let el = document.getElementById('proactiveBanner');
    if (el) return el;
    el = document.createElement('div');
    el.id = 'proactiveBanner';
    el.className = 'pc-banner';
    el.setAttribute('role', 'status');
    el.setAttribute('aria-live', 'polite');
    el.hidden = true;
    el.innerHTML = `<span class="pc-icon" aria-hidden="true"></span>
      <div class="pc-body"><div class="pc-title"></div><div class="pc-detail"></div></div>
      <div class="pc-actions">
        <button type="button" class="pc-btn primary" data-pc-open>看建議</button>
        <button type="button" class="pc-btn" data-pc-dismiss>不用</button>
      </div>`;
    el.querySelector('[data-pc-open]').addEventListener('click', acceptCurrent);
    el.querySelector('[data-pc-dismiss]').addEventListener('click', dismissCurrent);
    document.body.appendChild(el);
    return el;
  }

  function placeBanner(el) {
    const header = document.querySelector('.glass-header');
    const bottom = header ? header.getBoundingClientRect().bottom : 0;
    el.style.setProperty('--pc-top', `${Math.max(8, Math.round(bottom) + 8)}px`);
  }

  function showBanner(issue) {
    const el = ensureBanner();
    pc.current = issue;
    el.className = `pc-banner is-${issue.kind}`;
    el.querySelector('.pc-icon').textContent = issue.icon;
    const title = el.querySelector('.pc-title');
    title.textContent = issue.title;
    if (issue.simulated) {
      const tag = document.createElement('span');
      tag.className = 'pc-sim';
      tag.textContent = '模擬';
      title.appendChild(tag);
    }
    el.querySelector('.pc-detail').textContent = issue.detail || '';
    if (typeof wrapCjkWords === 'function') { wrapCjkWords(title); wrapCjkWords(el.querySelector('.pc-detail')); }
    placeBanner(el);
    el.hidden = false;
    markSeen(issue.key, 'shown');
    console.info('[proactive] 提醒：' + issue.title);
  }

  function hideBanner() {
    const el = document.getElementById('proactiveBanner');
    if (el) el.hidden = true;
    pc.current = null;
  }

  function acceptCurrent() {
    const issue = pc.current;
    if (!issue) return;
    if ((typeof agentState !== 'undefined' && agentState.running) || (typeof isAiResponding !== 'undefined' && isAiResponding)) {
      toast('AI 隨行管家還在處理上一個問題，請稍等', 'orange');
      return;
    }
    const a = agentAvailability();
    if (!a.ok) { toast(a.reason || '目前無法調整這份行程', 'orange'); hideBanner(); return; }
    markSeen(issue.key, 'accepted');
    hideBanner();
    pc.demoLagMin = null;
    if (issue.kind === 'lag') {
      // 跟「預計離開時間」入口、demo 延誤同一條路：先設預計離開，排程才會順延，套用後的時間才對得上提案。
      // 預計離開只是預測（原訂停留不變，按「照原訂」可以還原）；改行程一樣要在建議卡按「套用」。
      if (!applyExpectedLeave(issue.stopId, issue.leaveMin, { silent: true })) {
        toast('這一站已經不是目前所在的站，請重新檢查', 'orange');
        return;
      }
      startDelayAgentRun(issue.stopId, issue.demoMinutes != null ? issue.demoMinutes : undefined);
      return;
    }
    const plain = issue.title.replace(/，管家有建議$/, '');
    startAgentRun({
      type: 'weather',
      title: `管家主動提醒：${plain}`,
      userText: `🔔 管家提醒：${plain}（${issue.detail}），幫我看看怎麼調整`
    });
  }

  function dismissCurrent() {
    const issue = pc.current;
    if (!issue) return;
    markSeen(issue.key, 'dismissed');
    setCooldown(issue.kind);
    hideBanner();
    pc.demoLagMin = null;
    toast(`好的，${CFG.cooldownMin} 分鐘內不再提醒這類狀況`, 'blue');
  }

  // ── 主流程 ──
  // opts.force：demo「立即偵測」，不受時段與冷卻限制（仍然只提醒一次，可以「清除提醒紀錄」重來）
  async function check(opts) {
    const o = opts || {};
    if (pc.checking) return { skipped: 'busy' };
    const tripId = String(currentItineraryId || '');
    if (tripId !== pc.lastTripId) { pc.lastTripId = tripId; hideBanner(); pc.precheck.clear(); }
    const eph = ephemeral();
    if (eph && !pc.wasEphemeral) pc.memory = {};   // 每次進展示模擬都從乾淨的紀錄開始
    pc.wasEphemeral = eph;

    const hasTrip = tripId && tripId !== 'TRIP-EMPTY' && Array.isArray(replanStops) && replanStops.some((s) => s && s.name);
    if (!hasTrip) { hideBanner(); return { skipped: 'no-trip' }; }
    if (!leaderOk() && !o.force) { hideBanner(); return { skipped: 'not-leader' }; }
    if (typeof collabReadOnly !== 'undefined' && collabReadOnly) { hideBanner(); return { skipped: 'readonly' }; }
    if ((typeof agentState !== 'undefined' && agentState.running) || (typeof isAiResponding !== 'undefined' && isAiResponding)
      || (typeof isReplanning !== 'undefined' && isReplanning)) return { skipped: 'busy' };
    const nowMs = runtimeNow();
    let phase = phaseOf(nowMs);
    if (!phase && o.force) phase = currentTripStatus === 'ongoing' ? 'ongoing' : 'today';
    if (!phase) { hideBanner(); return { skipped: 'out-of-window' }; }
    if (!agentAvailability().ok) { hideBanner(); return { skipped: 'unavailable' }; }

    pc.checking = true;
    try {
      const found = [];
      const lag = detectLag(nowMs);
      if (lag && !lag.quiet) found.push(lag);
      found.push(...await detectWeatherAndClosed(nowMs, phase));
      found.sort((a, b) => KIND_ORDER[a.kind] - KIND_ORDER[b.kind]);

      const candidates = found.filter((f) => !wasSeen(f.key) && (o.force || !coolingDown(f.kind)));
      // 正在顯示的提醒：問題還在就留著，除非出現更急的（落後 > 天氣 > 公休）；不在了（例如已經改過行程）就收起來
      if (pc.current) {
        const urgent = candidates.some((f) => KIND_ORDER[f.kind] < KIND_ORDER[pc.current.kind]);
        if (found.some((f) => f.key === pc.current.key) && !urgent) return { showing: pc.current.key };
        if (urgent) unmarkSeen(pc.current.key);   // 被更急的蓋掉、使用者還沒處理：之後還可以再提醒一次
        hideBanner();
      }
      for (const issue of candidates) {
        if (!(await confirmWithServer(issue))) continue;
        if (pc.current || (typeof agentState !== 'undefined' && agentState.running)) break;
        showBanner(issue);
        return { shown: issue.key, issue };
      }
      return { found: found.length, lagQuiet: lag && lag.quiet ? lag.lag : null, seen: found.filter((f) => wasSeen(f.key)).length };
    } finally {
      pc.checking = false;
    }
  }

  function tick() {
    if (document.hidden) return;
    const interval = simOn() ? CFG.simPollMs : CFG.pollMs;
    if (Date.now() - pc.lastRun < interval) return;
    pc.lastRun = Date.now();
    check().catch((e) => console.warn('[proactive] 檢查失敗', e));
  }

  // ── 展示用 ──
  async function forceCheck() {
    const r = await check({ force: true });
    if (r.shown) return r;
    if (r.skipped === 'unavailable' || r.skipped === 'no-trip') toast('目前沒有可以調整的行程', 'orange');
    else if (r.skipped === 'busy') toast('AI 隨行管家正在處理，稍後再試', 'orange');
    else if (r.seen) toast('這個狀況已經提醒過了（可以按「清除提醒紀錄」重新展示）', 'orange');
    else if (r.lagQuiet != null) toast(`落後 ${r.lagQuiet} 分，但後面仍排得下，管家不打擾你`, 'blue');
    else if (r.showing) toast('提醒已經在頁面頂端了', 'blue');
    else toast('管家檢查過了，目前沒有需要提醒的事', 'blue');
    return r;
  }

  function simulateLag(minutes) {
    if (typeof getStayingStopIndex !== 'function' || getStayingStopIndex() < 0) {
      toast('要在行程進行中、停在某一站時才能模擬落後（可以先開「展示模擬」）', 'orange');
      return Promise.resolve({ skipped: 'not-staying' });
    }
    const input = document.getElementById('agentDelayMinutes');
    const m = Number.isFinite(Number(minutes)) ? Number(minutes) : Number(input && input.value);
    pc.demoLagMin = Math.max(5, Math.min(300, Math.round(m) || 50));
    return forceCheck().then((r) => { if (!r.shown) pc.demoLagMin = null; return r; });
  }

  function resetDemo() {
    resetMemory();
    hideBanner();
    pc.demoLagMin = null;
    toast('已清除這份行程的提醒紀錄', 'blue');
  }

  function injectDemoControls() {
    const panel = document.getElementById('agentDemoPanel');
    if (!panel || document.getElementById('pcDemoBlock')) return;
    const block = document.createElement('div');
    block.id = 'pcDemoBlock';
    block.className = 'agent-demo-proactive';
    block.innerHTML = `<div class="agent-demo-subtitle">🔔 主動提醒（管家自己偵測）</div>
      <button type="button" class="agent-demo-btn" id="pcDemoCheckBtn">立即偵測</button>
      <button type="button" class="agent-demo-btn" id="pcDemoLagBtn">模擬落後（主動）</button>
      <button type="button" class="agent-demo-btn" id="pcDemoResetBtn">清除提醒紀錄</button>
      <div class="agent-demo-note">勾選「模擬：下午大雨」後按「立即偵測」，頁面頂端會出現提醒；「模擬落後」用上面的分鐘數，後面排不下才會提醒。</div>`;
    block.querySelector('#pcDemoCheckBtn').addEventListener('click', forceCheck);
    block.querySelector('#pcDemoLagBtn').addEventListener('click', () => simulateLag());
    block.querySelector('#pcDemoResetBtn').addEventListener('click', resetDemo);
    panel.appendChild(block);
  }

  // 展示模擬的懸浮工具列：加一顆「管家檢查」，展覽現場不用開抽屜也能觸發
  function injectSimButton() {
    const actions = document.querySelector('#tripSimulationPanel .sim-actions');
    if (!actions || actions.querySelector('[data-pc-sim-check]')) return;
    const btn = document.createElement('button');
    btn.type = 'button';
    btn.dataset.pcSimCheck = '1';
    btn.style.whiteSpace = 'nowrap';
    btn.textContent = '🔔 管家檢查';
    btn.addEventListener('click', forceCheck);
    const close = actions.querySelector('[data-sim-close]');
    actions.insertBefore(btn, close || null);
  }

  function setup() {
    injectDemoControls();
    try {
      new MutationObserver(() => injectSimButton()).observe(document.body, { childList: true });
    } catch (_e) { /* 舊瀏覽器：模擬工具列少一顆按鈕，demo 面板仍可用 */ }
    injectSimButton();
    window.addEventListener('resize', () => { const el = document.getElementById('proactiveBanner'); if (el && !el.hidden) placeBanner(el); });
    document.addEventListener('visibilitychange', () => { if (!document.hidden) { pc.lastRun = 0; tick(); } });
    window.setInterval(tick, 1000);
    // 頁面載入後行程還在讀：稍等再做第一次檢查
    window.setTimeout(() => { pc.lastRun = 0; tick(); }, 5000);
  }

  window.WAI_PROACTIVE = Object.freeze({
    check: (opts) => check(opts), forceCheck, simulateLag, reset: resetDemo,
    dismiss: dismissCurrent, accept: acceptCurrent,
    get current() { return pc.current; }, config: CFG
  });

  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', setup);
  else setup();
})();
