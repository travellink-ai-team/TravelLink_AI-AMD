'use strict';
/* ══════════════════════════════════════════════════
   API 成本統計 — 費率表與 generation_runs 資料格式
   （實作計畫 docs/API成本統計-實作計畫.md 第 1 步）
   ──────────────────────────────────────────────────
   信任邊界（三層，不可混用）：
     authoritativeUsage  後端在代理層實際觀察到的用量 → 使用者改不了
     costUsd             後端依當時費率算出的「估算」 → 使用者改不了，但不是帳單
     clientReportedUsage Maps JS SDK 的前端回報數字   → 可竄改，UI 必須另外標示

   本檔只存在於伺服器端。費率絕不可出現在 app/ 底下任何檔案，
   否則使用者改前端就能改金額，整個「權威」的前提就沒了。
   ══════════════════════════════════════════════════ */

// 費率表版本。改任何一個數字都必須同時改這裡——
// 歷史紀錄靠它才能解釋「這筆錢當初是怎麼算出來的」，也才有辦法回頭重算。
const PRICING_VERSION = '2026-07-27';

/* ── Gemini 費率（USD / 每百萬 token）─────────────────
   來源：Vertex AI 官方定價頁（Standard 費率）。
   輸出單價「含推理」——thoughtsTokenCount 與 candidatesTokenCount 同價，
   計算時必須相加，只算 candidates 會嚴重低估開啟 thinking 的模型。       */
const GEMINI_RATES = {
  'gemini-3-flash-preview': {
    inputPerMillion: 0.50,
    outputPerMillion: 3.00   // 含 thoughts
  }

  // gemini-3.1-flash-image（行程圖生成，planner 使用）：
  //   刻意不填。圖片模型不是按 token 計價，套用上面的公式會得到錯誤數字。
  //   在取得官方單價前，它會走 priceGeminiUsage 的 unpriced 分支——
  //   用量照實記錄、金額不計入，而不是靜默當成 0 元。
};

/* ── Places API (New) 費率 ───────────────────────────
   Places (New) 的 SKU 取決於 endpoint 與 Field Mask 要了哪些欄位
   （欄位層級不同 → 單價不同），無法用「請求數 × 固定單價」計算。

   這裡刻意留空：與其填一組看起來完整但未經查證的對照表，
   不如先把「實際要了哪些欄位」如實記錄下來。
   有了 pricingVersion，日後補上官方單價可以回頭重算歷史紀錄；
   但如果當初記錄的用量本身是錯的，就再也救不回來了。

   key 格式見 placesSkuKey()。                                        */
const PLACES_RATES = {
  // 'searchText|displayName,formattedAddress': { perCall: 0.00 },
};

/* ── 顯示用匯率 ─────────────────────────────────────
   權威值一律是 costUsd；台幣只是「顯示」。但匯率必須跟著紀錄一起存下來，
   否則日後匯率變動時，舊紀錄會被用新匯率重新換算成不同的數字。
   放在伺服器端而非前端：前端硬編等於使用者可以改顯示金額。
   這是人工維護的近似值，不是即時匯率——改這裡要同時更新 FX_DATE。   */
const FX_TWD_PER_USD = 32.5;
const FX_DATE = '2026-07-27';

/* ══════════════════════════════════════════════════
   generation_runs 資料格式
   ══════════════════════════════════════════════════ */

// 只有兩態。原本討論過 reconciled，但本批不做 Cloud Billing 對帳，
// 沒有任何程式碼會設定它——留一個永遠不出現的狀態，
// 之後看資料的人會誤以為對帳功能存在。等真的做對帳再加。
const BILLING_STATUS = {
  COMPLETE: 'complete',
  INCOMPLETE: 'incomplete'
};

const RUN_STATUS = {
  RUNNING: 'running',
  COMPLETE: 'complete',
  INCOMPLETE: 'incomplete'
};

/**
 * 建立一份新的 run 文件初始內容。
 * tripId 保持 null：行程在生成完成前可能還沒有 id，完成後才由後端一次性綁定。
 */
function createRunDoc(runId, uid, nowTimestamp) {
  return {
    runId,
    uid,
    tripId: null,
    status: RUN_STATUS.RUNNING,
    startedAt: nowTimestamp,
    endedAt: null,
    pricingVersion: PRICING_VERSION,
    authoritativeUsage: { gemini: {}, places: {} },
    costUsd: 0,
    unpriced: [],           // 有用量但當時無費率可套的項目，見 summarizeRun
    fxRate: FX_TWD_PER_USD, // 顯示台幣用；不參與權威計算，但要跟著紀錄存下來
    fxDate: FX_DATE,
    clientReportedUsage: null,
    billingStatus: BILLING_STATUS.INCOMPLETE  // 先假設沒跑完，成功收尾才改 complete
  };
}

/* ══════════════════════════════════════════════════
   計價
   ══════════════════════════════════════════════════ */

function toNum(v) {
  const n = Number(v);
  return Number.isFinite(n) && n > 0 ? n : 0;
}

/**
 * 單一模型的一段用量 → 金額。
 * 回傳 { costUsd, priced, reason }：
 *   priced=false 時 costUsd 一定是 0，且呼叫端必須把它記進 unpriced，
 *   不可當成「這次不用錢」。
 */
function priceGeminiUsage(modelId, usage) {
  const rate = Object.prototype.hasOwnProperty.call(GEMINI_RATES, modelId)
    ? GEMINI_RATES[modelId]
    : null;

  const promptTokens = toNum(usage && usage.promptTokens);
  // 輸出與思考同價，相加後一起計
  const billableOutput = toNum(usage && usage.outputTokens) + toNum(usage && usage.thoughtTokens);

  if (!rate) {
    return {
      costUsd: 0,
      priced: false,
      reason: `no rate for model "${modelId}" in pricing version ${PRICING_VERSION}`
    };
  }

  const costUsd =
    (promptTokens / 1e6) * rate.inputPerMillion +
    (billableOutput / 1e6) * rate.outputPerMillion;

  return { costUsd, priced: true, reason: '' };
}

/**
 * 由 endpoint 與 Field Mask 組出 SKU key。
 * Field Mask 決定 Places (New) 落在哪個計費層級，因此必須進 key，
 * 否則不同單價的請求會被混成同一筆而無法分辨。
 * 欄位排序後正規化，讓「同一組欄位、不同書寫順序」對應到同一個 key。
 */
function placesSkuKey(endpoint, fieldMask) {
  const ep = String(endpoint || '').trim().replace(/^\/+/, '');
  const fields = String(fieldMask || '')
    .split(',')
    .map((s) => s.trim())
    .filter(Boolean)
    .sort();
  return `${ep}|${fields.join(',')}`;
}

/** Places 某個 SKU 的 N 次呼叫 → 金額。語意同 priceGeminiUsage。 */
function pricePlacesCalls(skuKey, calls) {
  const rate = Object.prototype.hasOwnProperty.call(PLACES_RATES, skuKey)
    ? PLACES_RATES[skuKey]
    : null;
  if (!rate) {
    return {
      costUsd: 0,
      priced: false,
      reason: `no rate for places sku "${skuKey}" in pricing version ${PRICING_VERSION}`
    };
  }
  return { costUsd: toNum(calls) * rate.perCall, priced: true, reason: '' };
}

/**
 * 把整個 run 的 authoritativeUsage 換算成金額。
 * 回傳 { costUsd, unpriced }：
 *   unpriced 是「有用量但算不出錢」的清單，必須一併存進紀錄並在 UI 揭露，
 *   否則使用者看到的金額會比實際低，而且看不出少了什麼。
 */
function summarizeRun(authoritativeUsage) {
  const usage = authoritativeUsage || {};
  let costUsd = 0;
  const unpriced = [];

  const gemini = usage.gemini || {};
  for (const modelId of Object.keys(gemini)) {
    const r = priceGeminiUsage(modelId, gemini[modelId]);
    costUsd += r.costUsd;
    if (!r.priced) unpriced.push({ kind: 'gemini', key: modelId, reason: r.reason });
  }

  const places = usage.places || {};
  for (const skuKey of Object.keys(places)) {
    const entry = places[skuKey] || {};
    const r = pricePlacesCalls(skuKey, entry.calls);
    costUsd += r.costUsd;
    if (!r.priced) unpriced.push({ kind: 'places', key: skuKey, reason: r.reason });
  }

  // 浮點累加會產生 0.30000000000000004 這類尾數；金額存到小數第 8 位足夠
  // （百萬分之一美元等級），也避免存進 Firestore 的數字看起來很怪。
  return { costUsd: Math.round(costUsd * 1e8) / 1e8, unpriced };
}

module.exports = {
  PRICING_VERSION,
  FX_TWD_PER_USD,
  FX_DATE,
  BILLING_STATUS,
  RUN_STATUS,
  GEMINI_RATES,
  PLACES_RATES,
  createRunDoc,
  priceGeminiUsage,
  placesSkuKey,
  pricePlacesCalls,
  summarizeRun
};
