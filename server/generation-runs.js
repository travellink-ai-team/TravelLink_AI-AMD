'use strict';
/* ══════════════════════════════════════════════════
   generation_runs — 一次「行程生成」的用量累加與落地
   （實作計畫 docs/API成本統計-實作計畫.md 第 2–4 步）
   ──────────────────────────────────────────────────
   為什麼累加放記憶體而不是每次呼叫都寫 Firestore：
   一次生成會發出多次 Gemini／Places 請求（初次生成、補景點、
   fillTripTimeBudget、verifyAndFilterStops、replan…），
   每次都寫一次會造成寫入放大與同一份文件的寫入競爭，而且那些寫入本身也要錢。
   因此在記憶體內按 runId 累加，收尾時一次落地，另有週期性保險 flush。

   代價：伺服器中途重啟會遺失尚未落地的累加。
   這正是 billingStatus=incomplete 要涵蓋的情況——
   紀錄會顯示「統計未完成」，而不是假裝花了 0 元。
   ══════════════════════════════════════════════════ */

const crypto = require('crypto');
const pricing = require('./pricing');

const COLLECTION = 'generation_runs';

// run 在記憶體中的存活上限。超過就當成使用者關掉分頁沒收尾，
// 標記 incomplete 後落地並釋放，避免長時間執行的伺服器記憶體無限增長。
const RUN_TTL_MS = 30 * 60 * 1000;
// 保險 flush 週期：即使沒收到 finish，也定期把已累加的數字寫下去
const SWEEP_INTERVAL_MS = 60 * 1000;
// 記憶體總量上限。TTL 只限制「閒置多久」，不是總量——
// 多帳號／多分頁持續呼叫 start 可以讓 Map 一直長大。
// 超過上限時淘汰最舊的（先落地成 incomplete，不是直接丟掉資料）。
const MAX_RUNS = 500;
// 每個 uid 的並行 run 上限：單一帳號開分頁灌 start 不該吃掉全部額度
const MAX_RUNS_PER_UID = 20;
// 落地連續失敗幾次後放棄保留。
//
// 這是一個誠實的取捨：Firestore 若長期不可用，「永不遺失用量」與「記憶體有界」
// 不可能同時成立——無上限保留最後會把行程撐爆，重啟反而全部遺失。
// 因此改成有界重試；真的被迫丟棄時以 error 等級把 runId 與當下用量印出來，
// 至少還能從 log 還原，而不是無聲消失。
const MAX_FLUSH_ATTEMPTS = 10;

let db = null;
let sweepTimer = null;

/** runId -> run 狀態 */
const runs = new Map();

function init(adminDb) {
  db = adminDb;
  if (!sweepTimer) {
    sweepTimer = setInterval(sweep, SWEEP_INTERVAL_MS);
    // 掃描不該讓 Node 因此無法結束
    if (sweepTimer.unref) sweepTimer.unref();
  }
}

/** 不可猜測的 runId。用密碼學亂數而非時間戳／流水號——
 *  可預測的 id 等於讓任何登入者都能猜到別人的 run 並往裡面灌用量。 */
function newRunId() {
  return crypto.randomBytes(24).toString('base64url');
}

/**
 * 淘汰單一 run。
 * 額度上限必須「同步」生效——若等 flush 回來才從 Map 移除，
 * 一連串 createRun 會在任何淘汰落地前就衝破上限，形同沒有上限。
 * 因此立刻移出 Map，但 run 物件仍被 flush 與在途請求持有：
 *   · 沒有在途請求 → 直接落地，失敗就記錄後放棄（額度已滿，不能再保留）
 *   · 仍有在途請求 → 標記 pendingDelete，由 release() 做最終落地
 *     （release 不需要 run 還在 Map 裡）
 */
function evictOne(victim, reason) {
  runs.delete(victim.runId);          // 同步生效，額度立刻釋放
  if ((victim.inFlight || 0) > 0) {
    victim.pendingDelete = true;      // release() 會做最終落地
    return;
  }
  flush(victim, { complete: false })
    .then((flushed) => { if (!flushed) dropUnflushed(victim, reason + '且落地失敗'); })
    .catch(() => dropUnflushed(victim, reason + '且落地例外'));
}

/** 淘汰最舊的 run（先落地保住已觀察到的用量，再釋放記憶體）。 */
function evictOldest(count) {
  const sorted = Array.from(runs.values()).sort((a, b) => a.lastTouch - b.lastTouch);
  for (let i = 0; i < count && i < sorted.length; i++) {
    evictOne(sorted[i], '超量淘汰');
  }
}

function createRun(uid) {
  // 先做兩層額度檢查再建立，避免記憶體無上限成長
  let mine = 0;
  for (const r of runs.values()) if (r.uid === uid) mine++;
  if (mine >= MAX_RUNS_PER_UID) {
    // 同一帳號超量：淘汰他自己最舊的那筆，不影響其他使用者
    const oldestMine = Array.from(runs.values())
      .filter((r) => r.uid === uid)
      .sort((a, b) => a.lastTouch - b.lastTouch)[0];
    if (oldestMine) evictOne(oldestMine, '單一帳號超量');
  }
  if (runs.size >= MAX_RUNS) evictOldest(runs.size - MAX_RUNS + 1);

  const runId = newRunId();
  runs.set(runId, {
    runId,
    uid,
    tripId: null,
    startedAt: Date.now(),
    lastTouch: Date.now(),
    usage: { gemini: {}, places: {} },
    clientReportedUsage: null,
    finished: false,
    finishedOk: null,
    writing: false,
    version: 0,
    flushFailures: 0,
    inFlight: 0,
    pendingDelete: false,
    usageMissing: 0,   // 上游回 200 卻讀不到 usageMetadata 的次數（統計不完整的線索）
    hadError: false,   // 這個 run 期間有沒有上游失敗；idle 收尾時決定 complete/incomplete
    dirty: true
  });
  // 立刻落地一次：伺服器若在第一次保險 flush（60s）之前重啟，
  // 沒有這一步的話 Firestore 上根本不會有這筆文件，也就無從呈現「統計未完成」。
  const run = runs.get(runId);
  flush(run, { complete: false }).catch(() => {});
  return runId;
}

/** 取 run 並確認擁有者。回傳 null 代表「不存在或不屬於這個 uid」——
 *  兩種情況刻意不分辨，避免用回應差異幫人探測哪些 runId 存在。 */
function getOwnedRun(runId, uid) {
  const run = runs.get(String(runId || ''));
  // 已收尾／待刪除的 run 一律不再接受新請求掛入——
  // 否則會把用量記進一個即將消失的物件。
  if (!run || run.uid !== uid || run.finished || run.pendingDelete) return null;
  return run;
}

/* ── 在途請求的參照計數 ─────────────────────────────
   代理請求可能長達數十秒（串流生成）。若這段期間 idle 收尾或超量淘汰把 run
   從 Map 移除，之後才回來的 usage 會寫進一個沒有人再 flush 的脫管物件，
   等於已經觀察到的用量白白遺失。
   因此：代理在受理請求時 retain()，結束時 release()；
   有在途請求時只標記 pendingDelete，等最後一個請求結束才做最終 flush 與刪除。 */
function retain(run) {
  if (run) run.inFlight = (run.inFlight || 0) + 1;
}

async function release(run) {
  if (!run) return;
  run.inFlight = Math.max(0, (run.inFlight || 0) - 1);
  if (run.inFlight === 0 && run.pendingDelete) {
    // 最終落地：把在途期間才抵達的用量一併寫進去。
    // 寫入失敗時「不可以」刪除——那正是 P0 要防的資料遺失。
    // 留著讓 sweep 重試（run 仍是 finished/pendingDelete，不會被新請求掛入）。
    const flushed = await flush(run, { complete: run.finished ? run.finishedOk !== false : false });
    if (flushed) {
      runs.delete(run.runId);
      run.pendingDelete = false;
    }
  }
}

/** 落地一直失敗、又不能無限保留時的最後手段：把資料印進 log 再丟。
 *  無聲丟棄會讓「統計為什麼少了一筆」永遠查不出來。 */
function dropUnflushed(run, reason) {
  try {
    console.error('[generation-runs] 放棄保留未落地的 run（' + reason + '）：'
      + JSON.stringify({
        runId: run.runId, uid: run.uid, tripId: run.tripId,
        flushFailures: run.flushFailures, usage: run.usage
      }));
  } catch (_e) {
    console.error('[generation-runs] 放棄保留未落地的 run：' + run.runId);
  }
  runs.delete(run.runId);   // 多數情況已被移除；重複呼叫無害
}

/** 刪除 run；仍有在途請求時改為標記，等 release 收尾。 */
function disposeRun(run) {
  if (!run) return;
  if ((run.inFlight || 0) > 0) { run.pendingDelete = true; return; }
  runs.delete(run.runId);
}

function touch(run) {
  run.lastTouch = Date.now();
  run.dirty = true;
  run.version = (run.version || 0) + 1;   // 供 flush 判斷「寫入期間有無新用量」
}

function addGeminiUsage(run, modelId, usage) {
  if (!run || !modelId) return;
  const bucket = run.usage.gemini[modelId] || (run.usage.gemini[modelId] = {
    promptTokens: 0, outputTokens: 0, thoughtTokens: 0, calls: 0
  });
  bucket.promptTokens += Number(usage && usage.promptTokens) || 0;
  bucket.outputTokens += Number(usage && usage.outputTokens) || 0;
  bucket.thoughtTokens += Number(usage && usage.thoughtTokens) || 0;
  bucket.calls += 1;
  touch(run);
}

function addPlacesCall(run, skuKey) {
  if (!run || !skuKey) return;
  const bucket = run.usage.places[skuKey] || (run.usage.places[skuKey] = { calls: 0 });
  bucket.calls += 1;
  touch(run);
}

/** Maps JS SDK 的前端回報。欄位名已標示來源，且由後端代寫——
 *  如此整份文件對用戶端維持唯讀，信任邊界只有這一個缺口且集中在此。 */
function setClientReportedUsage(run, counts) {
  if (!run) return;
  const pick = (v) => Math.max(0, Math.min(100000, Math.floor(Number(v) || 0)));
  run.clientReportedUsage = {
    directions: pick(counts && counts.directions),
    geocoding: pick(counts && counts.geocoding),
    placesLegacy: pick(counts && counts.placesLegacy),
    source: 'client'   // 讀資料的人一眼看得出這串不是後端觀察到的
  };
  touch(run);
}

/** 記錄「這個 run 期間有上游失敗」。由代理層在 upstream 非 2xx 時呼叫。
 *  idle 自動收尾時要用它決定 complete/incomplete——比在前端逐一補錯誤路徑可靠：
 *  前端的失敗形式太多（502、串流中斷、逾時、使用者取消），漏一個就會把
 *  殘缺紀錄標成完整資料；伺服器則是每一次呼叫都必然經過。 */
/** 上游回 200 卻讀不到 usageMetadata：不是失敗，但這筆 run 的用量並不完整。
 *  記次數而不是直接標 incomplete——多數呼叫是有 usage 的，
 *  一次讀不到就把整趟標成未完成過度嚴格；改由 UI 揭露「統計可能不完整」。 */
function markUsageMissing(run) {
  if (!run) return;
  run.usageMissing = (run.usageMissing || 0) + 1;
  touch(run);
}

function markUpstreamError(run) {
  if (!run) return;
  run.hadError = true;
  // 已經收尾的 run（前端先 finish、某個在途請求之後才失敗）也要降級：
  // 不改的話 release 的最終落地仍會寫成 complete，把一筆有失敗的紀錄
  // 呈現成完整資料。
  if (run.finished && run.finishedOk) run.finishedOk = false;
  touch(run);
}

/** 一次性綁定 tripId。已綁過就不再更動——
 *  否則用戶端可以把一次便宜的 run 重綁到別筆行程上。 */
function bindTrip(run, tripId) {
  if (!run) return { ok: false, reason: 'no run' };
  if (run.tripId) return { ok: false, reason: 'already bound' };
  const id = String(tripId || '').trim();
  if (!id) return { ok: false, reason: 'invalid tripId' };
  run.tripId = id;
  touch(run);
  return { ok: true };
}

function buildDoc(run, { complete }) {
  const summary = pricing.summarizeRun(run.usage);
  return {
    runId: run.runId,
    uid: run.uid,
    tripId: run.tripId,
    status: complete ? pricing.RUN_STATUS.COMPLETE : pricing.RUN_STATUS.INCOMPLETE,
    startedAt: new Date(run.startedAt),
    endedAt: complete ? new Date() : null,
    pricingVersion: pricing.PRICING_VERSION,
    authoritativeUsage: run.usage,
    costUsd: summary.costUsd,
    unpriced: summary.unpriced,
    // 匯率跟著紀錄一起存：日後匯率變動時，舊紀錄仍以當時的匯率呈現
    fxRate: pricing.FX_TWD_PER_USD,
    fxDate: pricing.FX_DATE,
    clientReportedUsage: run.clientReportedUsage,
    usageMissing: run.usageMissing || 0,
    billingStatus: complete
      ? pricing.BILLING_STATUS.COMPLETE
      : pricing.BILLING_STATUS.INCOMPLETE
  };
}

/** 落地。只有這裡會寫 generation_runs，且一律走 Admin SDK（繞過 Rules）。 */
async function flush(run, { complete } = {}) {
  if (!db || !run) return false;
  // 同一份 run 的寫入必須「排隊」而不是「丟棄」。
  // 若改成 if (run.writing) return false，則 finishRun 撞上進行中的
  // sweep／初次落地時會被靜默丟掉，最終狀態永遠寫不進 Firestore。
  // 串成 promise chain 可同時保證：不並行寫、順序即呼叫順序（最後一次為準）。
  // 版本要在「flush 被呼叫的當下」取，不能等到 .then() 的微任務才取——
  // 呼叫端後續的同步程式（例如馬上 addGeminiUsage）會比微任務先執行，
  // 那時再取就已經是新版本，會誤判成「期間沒有變化」而把 dirty 清掉。
  const versionAtCall = run.version;
  const prev = run.writeChain || Promise.resolve();
  const next = prev.then(async () => {
    run.writing = true;
    try {
      await db.collection(COLLECTION).doc(run.runId)
        .set(buildDoc(run, { complete: !!complete }), { merge: true });
      // 只有「呼叫到寫完之間完全沒有新用量」才敢清 dirty。
      // 有變化就保留，讓 sweep 再寫一次（多寫一次無害，漏寫會遺失資料）。
      if (run.version === versionAtCall) run.dirty = false;
      run.flushFailures = 0;
      return true;
    } catch (err) {
      run.flushFailures = (run.flushFailures || 0) + 1;
      console.error('[generation-runs] flush 失敗（第 ' + run.flushFailures + ' 次）：', err && err.message);
      return false;
    } finally {
      run.writing = false;
    }
  });
  // chain 本身不可因單次失敗而中斷後續寫入
  run.writeChain = next.catch(() => {});
  return next;
}

/**
 * 收尾。ok=false 代表生成失敗／逾時／上游錯誤——
 * 這種 run 必須落成 incomplete，不能因為「有呼叫 finish」就標成 complete，
 * 否則畫面會把一筆殘缺（甚至零用量）的紀錄當成完整資料呈現。
 */
async function finishRun(run, { ok = true } = {}) {
  if (!run) return false;
  run.finished = true;
  // 前端說成功、但伺服器看到過上游失敗 → 以伺服器觀察為準。
  // 「有沒有失敗」是後端才看得完整的事實，不該由前端說了算。
  run.finishedOk = !!ok && !run.hadError;   // 落地失敗時 sweep 要靠它重試出正確的狀態
  // 用 finishedOk 而不是 ok：伺服器若在這個 run 期間看過上游失敗，
  // 即使前端說成功也必須落成 incomplete。
  const flushed = await flush(run, { complete: run.finishedOk });
  // 寫入失敗就把 run 留著，讓 sweep 之後重試；直接刪掉等於資料永久遺失。
  // 有在途請求時只標記，等最後一個請求 release 後才真正刪除並做最終落地。
  if (flushed) disposeRun(run);
  return flushed;
}

/** 週期性保險：把有變動的 run 先寫下去（狀態仍是 incomplete，
 *  因為還沒收到 finish）；超過 TTL 的直接落地並釋放記憶體。 */
async function sweep() {
  const now = Date.now();
  for (const run of Array.from(runs.values())) {
    const expired = now - run.lastTouch > RUN_TTL_MS;
    // 已呼叫 finish 但落地失敗而留下來的：用它原本的結果重試，不要降級成 incomplete
    const asComplete = run.finished && run.finishedOk !== false;
    if (expired || run.finished || run.pendingDelete) {
      const flushed = await flush(run, { complete: asComplete });
      if (flushed) {
        disposeRun(run);
      } else if ((run.flushFailures || 0) >= MAX_FLUSH_ATTEMPTS) {
        // 重試夠多次仍失敗：不能再無限保留，但要留下可追查的紀錄
        dropUnflushed(run, expired ? 'TTL 過期且落地持續失敗' : '落地持續失敗');
      }
      // 其餘情況留著，下一輪 sweep 再試——過期本身不足以構成丟棄理由
    } else if (run.dirty) {
      await flush(run, { complete: false });
    }
  }
}

module.exports = {
  COLLECTION,
  init,
  createRun,
  getOwnedRun,
  addGeminiUsage,
  addPlacesCall,
  setClientReportedUsage,
  bindTrip,
  markUpstreamError,
  markUsageMissing,
  retain,
  release,
  finishRun,
  flush,
  sweep,
  MAX_RUNS,
  MAX_RUNS_PER_UID,
  _runs: runs   // 測試用
};
