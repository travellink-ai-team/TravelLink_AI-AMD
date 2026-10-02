/* ══════════════════════════════════════════════════
   API 成本統計 — 前端側
   （實作計畫 docs/API成本統計-實作計畫.md 第 6 步）
   ──────────────────────────────────────────────────
   本檔「不做計價」。費率完全在伺服器端，前端拿不到也算不出金額——
   否則改前端就能改金額，「權威」兩個字就沒有意義了。

   前端只負責三件事：
     1. 生成開始時跟後端要一個 runId，之後每次 API 呼叫帶著它
     2. 統計 Maps JS SDK 的呼叫次數（後端看不到這些，只能由前端回報）
     3. 讀取後端寫好的紀錄並顯示

   ★ 第 2 項的數字是「用戶端估算」，可被竄改，顯示時必須與後端的權威數字分開標示。

   ── 為什麼 run 是物件而不是幾個全域變數 ──
   早期版本用 currentRunId / boundTripId 兩個模組層級變數，所有非同步工作
   （bind 重試、收尾的兩支 POST）在「執行時」才去讀它們。結果是一連串競態：
     · A 行程的 startRun 還在飛，使用者切到 B → B 沿用 A 的 runId
     · bind 進入 404 重試期間 run 被 idle 收尾 → 重試打到已經換掉的新 run
     · 收尾中的 run 仍被當成可用 → 新請求記到一個伺服器即將刪除的 run
   因此改成：每個 run 是一個物件，所有非同步操作都「釘住」傳進去的那個物件，
   絕不在執行時回頭讀全域狀態。
   ══════════════════════════════════════════════════ */
(function () {
  'use strict';

  // 目前作用中的 run 物件；收尾一開始就會被設回 null，避免被重用。
  var activeRun = null;
  // 序列鎖：ensureRun/startRun 的「判斷＋建立」必須整段互斥，
  // 否則中間的每個 await 都是別的行程插隊的機會。
  var lock = Promise.resolve();
  var pricingVersion = '';

  var IDLE_FINISH_MS = 45000;
  var idleTimer = null;

  function apiBase() {
    var cfg = window.TRAVEL_APP_CONFIG || {};
    return String(cfg.API_PROXY_BASE || '').replace(/\/+$/, '');
  }

  /** 逾時包裝：任何 promise 超過 ms 就以 fallback 收場，絕不無限等待。 */
  function withTimeout(promise, ms, fallback) {
    return Promise.race([
      promise,
      new Promise(function (resolve) { setTimeout(function () { resolve(fallback); }, ms); })
    ]);
  }

  async function authHeaders() {
    var headers = { 'Content-Type': 'application/json' };
    try {
      var u = (typeof firebaseAuth !== 'undefined' && firebaseAuth) ? firebaseAuth.currentUser : null;
      // getIdToken 在 fetch 的 AbortController 之外，若卡住（token 更新遇到網路問題）
      // 一樣會把生成拖住，所以它自己也要有上限。
      if (u) {
        var token = await withTimeout(u.getIdToken(), 2000, null);
        if (token) headers.Authorization = 'Bearer ' + token;
      }
    } catch (_e) {}
    return headers;
  }

  /**
   * 帶逾時的請求。統計的任何一支呼叫都不可以無限期等待——
   * 「統計失敗但生成照常」是本功能的第一原則。
   *
   * ★ 逾時必須涵蓋「讀完 body」而不只是「拿到 headers」。
   *   常見寫法 fetch(...).finally(clearTimeout) 有個陷阱：fetch 在收到
   *   response headers 就 resolve，此時 timer 已被清掉，之後的 res.json()
   *   若因 body 卡住就可以無限等待——逾時形同虛設。
   *   因此這裡等 json 解析完才清 timer。
   *
   * @param needJson true 時回傳 { ok, status, data }，否則只回 { ok, status }
   */
  async function requestWithLimit(url, options, ms, needJson) {
    var ctrl = new AbortController();
    var timer = setTimeout(function () { ctrl.abort(); }, ms || 5000);
    try {
      var res = await fetch(url, Object.assign({}, options, { signal: ctrl.signal }));
      var data = null;
      if (needJson && res.ok) {
        try { data = await res.json(); } catch (_e) { data = null; }
      }
      return { ok: res.ok, status: res.status, data: data };
    } finally {
      clearTimeout(timer);   // 讀完 body 才清，逾時才真的涵蓋整段
    }
  }

  /** 針對「指定的 run」發 POST。run 由參數釘住，不讀全域 activeRun。 */
  async function postForRun(run, pathSuffix, body) {
    var base = apiBase();
    if (!base || !run || !run.id) return { ok: false, status: 0 };
    try {
      return await requestWithLimit(
        base + '/generation-runs/' + encodeURIComponent(run.id) + pathSuffix, {
          method: 'POST',
          headers: await authHeaders(),
          body: JSON.stringify(body || {})
        }, 8000, false);
    } catch (_e) {
      return { ok: false, status: 0 };
    }
  }

  /** 向後端要一個新 runId 並包成 run 物件。失敗回 null（不統計，功能照常）。 */
  async function createRunObject(tripId) {
    var base = apiBase();
    if (!base) return null;   // file:// 或未設代理
    try {
      // 這支是整個統計流程唯一會擋住使用者的呼叫（要先有 runId 才能記第一次用量），
      // 逾時抓緊一點：正常情況它只是在伺服器建一個記憶體物件，毫秒級。
      var res = await requestWithLimit(base + '/generation-runs/start', {
        method: 'POST', headers: await authHeaders(), body: '{}'
      }, 4000, true);
      if (!res.ok || !res.data) {
        console.warn('[API 成本統計] 無法建立 generation run：HTTP ' + res.status);
        return null;
      }
      var data = res.data;
      if (!data.runId) {
        console.warn('[API 成本統計] generation run 回應缺少 runId');
        return null;
      }
      pricingVersion = data.pricingVersion || '';
      return {
        id: data.runId,
        // tripId 記的是「意圖」，在建立當下就決定，不等伺服器 bind 回來。
        // 切換行程的判斷靠它——等 bind 成功才設定的話，bind 還在飛的期間
        // 切到別的行程會被誤判成「同一趟」而沿用舊 run。
        tripId: tripId || null,
        bindPromise: null,
        finishing: false,
        counts: { directions: 0, geocoding: 0, placesLegacy: 0 }
      };
    } catch (_e) {
      console.warn('[API 成本統計] 建立 generation run 失敗：', _e && (_e.name || _e.message) || _e);
      return null;
    }
  }

  /**
   * 綁定 tripId（釘住 run）。404 會重試：
   * explore 的新行程是背景非同步寫入 Firestore，綁定當下後端很可能還讀不到。
   * 不重試的話每一筆新建行程的 run 都會永久沒有 tripId，畫面上永遠看不到統計。
   */
  async function bindTripForRun(run, tripId) {
    if (!run || !tripId) return false;
    var delays = [0, 800, 2000, 4000, 6000];   // 總計約 12.8 秒，涵蓋背景寫入的重試
    for (var i = 0; i < delays.length; i++) {
      if (delays[i]) await new Promise(function (r) { setTimeout(r, delays[i]); });
      var res = await postForRun(run, '/bind-trip', { tripId: tripId });
      if (res.ok) return true;
      if (res.status !== 404 && res.status !== 0) return false;  // 403/409 重試也沒用
    }
    return false;
  }

  function touchIdle() {
    clearTimeout(idleTimer);
    idleTimer = setTimeout(function () { finishRun(true).catch(function () {}); }, IDLE_FINISH_MS);
  }

  /** 這個 run 現在還能用嗎（存在、且不在收尾中）。 */
  function usable(run) { return !!run && !run.finishing; }

  /**
   * 取得可用於 tripId 的 run；沒有就開一個。
   * planner 用（沒有明確的「一次生成」邊界，改為首次呼叫自動開、閒置自動收）。
   */
  function ensureRun(tripId) {
    // 整段判斷＋建立必須是一個不可分割的區段。
    // 只用「await starting」保護不夠：await finishRun() 之後才建立新 run，
    // 那個 await 又是一個讓出點，第三個行程可以插進來先建立自己的 run，
    // 兩邊互相覆蓋 activeRun。改成把整段掛在同一條序列鎖上。
    var result = lock.then(async function () {
      if (usable(activeRun) && activeRun.tripId === tripId) {
        touchIdle();
        return activeRun.id;
      }
      // 換了行程（或前一個 run 沒綁行程）→ 收掉舊的。
      // 刻意「不 await」：舊 run 的收尾要等它的 bind 重試跑完（最長可達數十秒），
      // 等它等於讓統計功能拖住使用者的下一次 AI 呼叫。
      // run 是獨立物件、所有非同步操作都釘住它，因此背景收尾不會影響新 run。
      if (usable(activeRun)) { finishRun(true).catch(function () {}); }

      var run = await createRunObject(tripId);
      if (!run) return null;
      activeRun = run;
      if (tripId) {
        // 同樣不 await：bind 含重試，await 會讓第一次 AI 呼叫平白多等。
        // runId 已經拿到，用量從第一次呼叫就記得到；收尾時會等這個 promise。
        run.bindPromise = bindTripForRun(run, tripId).catch(function () { return false; });
      }
      touchIdle();
      return run.id;
    });
    lock = result.catch(function () {});   // 鎖不可因單次失敗而中斷
    return result;
  }

  /** explore 用：明確的一次生成開始。 */
  function startRun() {
    var result = lock.then(async function () {
      // 不 await 舊 run 的收尾（理由同 ensureRun）
      if (usable(activeRun)) { finishRun(true).catch(function () {}); }
      var run = await createRunObject(null);
      activeRun = run;
      return run ? run.id : null;
    });
    lock = result.catch(function () {});
    return result;
  }

  /** explore 用：生成完成、拿到 tripId 後綁定。 */
  function bindTrip(tripId) {
    var run = activeRun;
    if (!usable(run)) return Promise.resolve(false);
    run.tripId = tripId || null;
    run.bindPromise = bindTripForRun(run, tripId).catch(function () { return false; });
    return run.bindPromise;
  }

  /**
   * explore 用：綁定行程並收尾，全程釘住「呼叫當下」的那個 run。
   *
   * 為什麼不能寫成 bindTrip(id).then(() => finishRun())：
   * bind 含重試（最長十幾秒），那個 .then() 回呼執行時會回頭讀全域 activeRun。
   * 若使用者這期間已開始下一趟生成，收尾到的會是「新的那個 run」——
   * 新 run 被提前結束、之後的用量都不記，而舊 run 只能等 TTL 以 incomplete 收場。
   * 非同步回呼裡絕不可以再用全域狀態找 run。
   */
  function completeRun(tripId, success) {
    var run = activeRun;              // 在同步階段就釘住
    if (!usable(run)) return Promise.resolve(false);
    run.tripId = tripId || run.tripId;
    if (tripId) run.bindPromise = bindTripForRun(run, tripId).catch(function () { return false; });
    return finishRunFor(run, success);
  }

  /**
   * 對「指定的 run」收尾。所有非同步步驟都用參數釘住的那個 run，不讀全域。
   * success=false 代表生成失敗／逾時——後端會落成 incomplete。
   * （伺服器若在這個 run 期間看過上游失敗，即使這裡說成功也會被覆蓋成 incomplete。）
   */
  async function finishRunFor(run, success) {
    if (!usable(run)) return false;
    // 先標記並讓出 activeRun：收尾期間新來的請求必須開新 run，
    // 不能繼續用這一個——伺服器 finish 後會把它刪掉，之後的呼叫只會生成不記帳。
    run.finishing = true;
    if (activeRun === run) { activeRun = null; clearTimeout(idleTimer); }
    // 等 bind 結束再收尾，否則 tripId 可能永遠補不上（畫面看不到這筆統計）
    if (run.bindPromise) { try { await run.bindPromise; } catch (_e) {} }
    await postForRun(run, '/client-usage', run.counts);
    var res = await postForRun(run, '/finish', { ok: success !== false });
    return res.ok;
  }

  function finishRun(success) { return finishRunFor(activeRun, success); }

  /* ── 每日用量統計（不分 run）──────────────────────────────────────────────
     為什麼要另外記一份：原本 countClientCall 只在「AI 生成的統計區間」內計數，
     區間外一律丟掉。但 planner 開啟既有行程時查廁所、查停車場、畫路線這些呼叫
     全都在區間外——也就是網頁端最大的一塊 Maps 用量，我們自己完全沒有帳。
     2026-09-27 帳單 Places 破 $255、而當天 generation_runs 只記到 $0.0015，
     差距就是這個盲點造成的：不是「網頁沒花錢」，是「網頁沒在量」。

     這份統計存在 localStorage，只代表「這台瀏覽器」的用量，不是全站總量；
     要跨使用者彙總需要後端與 Firestore 規則配合（見 docs 待辦）。 */
  var DAILY_KEY = 'wai_maps_daily_usage_v1';
  var DAILY_KEEP_DAYS = 30;

  function todayKey() {
    var d = new Date();
    // 用本地日期：使用者是照自己的日曆在對帳單，不是照 UTC
    return d.getFullYear() + '-'
      + String(d.getMonth() + 1).padStart(2, '0') + '-'
      + String(d.getDate()).padStart(2, '0');
  }

  function readDaily() {
    try {
      var raw = window.localStorage.getItem(DAILY_KEY);
      var obj = raw ? JSON.parse(raw) : {};
      return (obj && typeof obj === 'object') ? obj : {};
    } catch (e) { return {}; }
  }

  function writeDaily(obj) {
    try {
      // 只留最近 N 天，避免無限長大
      var keys = Object.keys(obj).sort();
      while (keys.length > DAILY_KEEP_DAYS) { delete obj[keys.shift()]; }
      window.localStorage.setItem(DAILY_KEY, JSON.stringify(obj));
    } catch (e) { /* 隱私模式或配額滿：統計不是關鍵路徑，靜默略過 */ }
  }

  function bumpDaily(kind) {
    var all = readDaily();
    var key = todayKey();
    var day = all[key] || (all[key] = { directions: 0, geocoding: 0, placesLegacy: 0, inRun: 0, outOfRun: 0 });
    if (!Object.prototype.hasOwnProperty.call(day, kind)) day[kind] = 0;
    day[kind] += 1;
    if (usable(activeRun)) day.inRun += 1; else day.outOfRun += 1;
    writeDaily(all);
  }

  /** Maps JS SDK 呼叫計數。kind: directions | geocoding | placesLegacy */
  function countClientCall(kind) {
    var run = activeRun;
    if (usable(run) && Object.prototype.hasOwnProperty.call(run.counts, kind)) {
      run.counts[kind] += 1;
    }
    // run 之外也要記——這正是先前完全沒帳的那一塊
    bumpDaily(kind);
  }

  /** 讀取每日用量（本機）。回傳 { '2026-09-27': {directions, geocoding, placesLegacy, inRun, outOfRun}, … } */
  function dailyUsage() { return readDaily(); }
  function clearDailyUsage() { try { window.localStorage.removeItem(DAILY_KEY); } catch (e) {} }

  /**
   * 讀取某趟行程的成本紀錄（Firestore 唯讀；寫入只有後端做得到）。
   * 回傳 { ok, runs }：ok=false 代表「讀取失敗」，與「這趟真的沒有紀錄」
   * 是兩件完全不同的事——混為一談會讓權限錯誤或斷網被顯示成
   * 「這是統計上線前的舊行程」，把問題藏起來。
   */
  async function loadTripRuns(tripId) {
    if (!tripId || typeof firebaseDb === 'undefined' || !firebaseDb) {
      return { ok: false, runs: [], reason: 'unavailable' };
    }
    try {
      var snap = await firebaseDb.collection('generation_runs')
        .where('tripId', '==', tripId)
        .get();
      return { ok: true, runs: snap.docs.map(function (d) { return d.data(); }).filter(Boolean) };
    } catch (e) {
      return { ok: false, runs: [], reason: (e && e.code) || 'error' };
    }
  }

  window.WAI_COST = {
    // 收尾中的 run 一律視為不可用，呼叫端就不會把它帶進 X-Run-Id
    currentRunId: function () { return usable(activeRun) ? activeRun.id : null; },
    pricingVersion: function () { return pricingVersion; },
    clientCounts: function () {
      return usable(activeRun) ? Object.assign({}, activeRun.counts)
        : { directions: 0, geocoding: 0, placesLegacy: 0 };
    },
    startRun: startRun,
    ensureRun: ensureRun,
    bindTrip: bindTrip,
    completeRun: completeRun,   // 綁定＋收尾（釘住呼叫當下的 run）
    finishRun: finishRun,
    countClientCall: countClientCall,
    loadTripRuns: loadTripRuns,
    dailyUsage: dailyUsage,
    clearDailyUsage: clearDailyUsage
  };
})();
