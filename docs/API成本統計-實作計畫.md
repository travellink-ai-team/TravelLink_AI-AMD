# API 成本統計 — 實作計畫

> 目標：在每筆行程上顯示「本次生成的 API 用量與成本估算」，且**使用者無法竄改**權威部分，
> 同時**不把估算誤稱為實際帳單**。
>
> 制定日期：2026-07-27

---

## 0. 名詞與信任邊界（先講清楚，後面所有決策都依賴這個）

這份計畫嚴格區分三層，不可混用：

| 層 | 定義 | 誰產生 | 可信度 |
|---|---|---|---|
| **權威用量** `authoritativeUsage` | 後端在代理層**實際觀察到**的 token 數／請求數 | 伺服器 | 使用者改不了 |
| **權威估算** `costUsd` | 後端依當時計價表對權威用量計算的金額 | 伺服器 | 使用者改不了，但**是估算不是帳單** |
| **用戶端統計** `clientReportedUsage` | Maps JS SDK 的呼叫次數（後端看不到，只能由前端回報） | 瀏覽器回報 | **可竄改**，UI 必須另外標示 |

> **Cloud Billing 的角色**：只能核對整個專案的總量，**無法證明單筆行程的數字正確**。
> 它能發現「全部行程回報 900 次、帳單顯示 1,050 次」這種總量差異，僅此而已。
> 加上免費額度、月累計級距、折扣與失敗請求狀態的影響，
> **任何畫面文案都不得宣稱這是實際帳單金額。**

---

## 1. 本次要做的範圍（第 1–6 步）

### 資料格式：頂層 `generation_runs/{runId}`

行程在生成完成前可能還沒有 `tripId`，因此 run 記錄**先獨立存在**，完成後再由後端綁定。

```
generation_runs/{runId}
  runId          string    文件 id，不可猜測（crypto 亂數，非流水號）
  uid            string    核發時綁定的使用者，代理層每次都要比對
  tripId         string?   null 直到綁定；一次性，不可重綁
  status         string    running | complete | incomplete
  startedAt      timestamp
  endedAt        timestamp?
  pricingVersion string    例如 "2026-07-27"，費率表版本，事後可重算

  authoritativeUsage:
    gemini:
      <modelId>: { promptTokens, outputTokens, thoughtTokens, calls }
    places:
      <skuKey>:  { calls }        // skuKey 由 endpoint + Field Mask 決定

  costUsd        number    權威估算（USD 為準）
  fxRate         number?   顯示台幣用；不參與權威計算
  fxDate         string?   匯率日期

  clientReportedUsage:      // 由前端回報、後端代寫，欄位名已標示來源
    { directions, geocoding, placesLegacy }

  billingStatus  string    complete | incomplete
```

**設計決定與理由：**

- **`costUsd` 是權威值，台幣只在顯示時換算。**
  只存台幣會把匯率烙進歷史資料，日後匯率變動時舊紀錄無法重算。
- **`billingStatus` 只有兩態。**
  原本討論過 `reconciled`，但本次不做對帳程序，沒有任何程式碼會設定它。
  留一個永遠不出現的狀態，之後看資料的人會誤以為對帳功能存在。等真的做對帳再加。
- **`pricingVersion` 必須存。** 費率會改；沒有版本標記就無法解釋歷史數字怎麼算出來的。
- **`clientReportedUsage` 由前端回報但由後端寫入**（見下方 §1.6），
  好處是整份文件對用戶端維持 `allow write: if false`，信任邊界只有一個缺口且集中在一處。

---

### 1.1 定義資料格式與成本狀態
建立費率表模組（**per-model**，不是單一組數字）：

- `gemini-3-flash-preview`：輸入 / 輸出含推理 兩種單價
- `gemini-3.1-flash-image`：**圖片模型計價與文字不同**，不可套用文字公式
- Places (New)：依 endpoint + Field Mask 對應 SKU

**驗收**
- [ ] 費率表為單一模組，含 `pricingVersion` 常數
- [ ] 未知 model / SKU 走明確的 fallback 並在紀錄中標記，**不可靜默當成 0 元**
- [ ] 費率表只存在伺服器端，前端拿不到（`grep` 前端三支 JS 應為 0 命中）

---

### 1.2 後端 `/generation-runs/start`
核發綁 `uid` 的不可猜測 `runId`。

**驗收**
- [ ] 未登入呼叫 → 401
- [ ] `runId` 用密碼學亂數產生，非時間戳或流水號（連續呼叫兩次不可有可預測關係）
- [ ] 回傳的 run 文件 `uid` 等於呼叫者
- [ ] 套用限流（比照既有 `vertexLimiter`），防止大量建立空 run

---

### 1.3 Vertex 代理旁路解析 `usageMetadata`
**這一步是本計畫最大的回歸風險，獨立列出約束：**

現況 [server/server.js](../server/server.js) 的 Vertex 代理是**逐 chunk 直接轉發**，
並設 `X-Accel-Buffering: no`。這是為了 SSE 串流不被緩衝——
先前的效能工作（step2 子進度、streaming 站名逐一浮現）完全依賴它。

> **禁止**：`await upstream.text()` 拿到完整回應再解析再送出。
> 這樣寫最直覺，但會讓生成從「站名逐一浮現」退回「轉圈到最後才一次出現」。
>
> **必須**：`res.write()` 照舊逐塊立即執行，**另外**把同一塊 bytes 餵給旁路的 SSE 解析器。

累加策略：**伺服器記憶體內以 `runId` 累加，結束時一次落地**（另加週期性保險 flush）。
不要每次呼叫都寫一次 Firestore——一次生成有多次 Gemini 呼叫，
會造成寫入放大與同一份文件的競爭。
代價是伺服器中途重啟會遺失，但那正是 `incomplete` 要涵蓋的情況。

**驗收**
- [ ] **串流未退化**：同一份行程生成，改動前後量測「首個站名出現的時間」，差異在雜訊範圍內
- [ ] 非串流 `generateContent` 與串流 `streamGenerateContent` **兩條路徑都有解析到用量**
- [ ] 代理層每次都比對 `runId` 的 `uid` 與請求者，不符 → 403
- [ ] 一次生成的多次呼叫（含 `fillTripTimeBudget`、`verifyAndFilterStops`、replan）**全部累加進同一個 run**
- [ ] 生成中途關閉分頁 → run 狀態為 `incomplete`，**不得顯示成 0 元**
- [ ] 逾時／上游錯誤 → 同樣記 `incomplete`

---

### 1.4 Admin SDK 寫入 + Firestore Rules
**這一步是「後端計算」與「不可竄改」之間的關鍵差異。**
後端算得再準，若前端仍能寫同名欄位，隨後一次 `set(..., {merge:true})` 就蓋掉了。

- 子集合／`generation_runs` 文件：用戶端 `allow read`（限本人／行程成員）、`allow write: if false`
- **行程主文件也要擋**：用 `affectedKeys().hasOnly([...])` 白名單，
  禁止用戶端新增或修改任何同名的權威成本欄位，避免畫面誤讀假資料

**驗收**
- [ ] 用戶端直接 `set` / `update` `generation_runs` 文件 → permission-denied
- [ ] 用戶端在 `micro_trips` 主文件寫入同名成本欄位 → permission-denied
- [ ] 行程擁有者的**正常編輯路徑不受影響**（改標題、改 stops、改車輛全部照常）
- [ ] 共編成員讀得到自己行程的 run 紀錄
- [ ] 以上 5 項用實際攻擊探測驗證（比照既有 `security-audit.html` 的做法），不是只看規則檔

> `firestore.rules` 改完由**使用者自行部署**，我不執行部署。

---

### 1.5 Places (New) 改走代理
5 個 `fetch` 呼叫點（4 個 `searchText` + 1 個 `searchNearby`）搬到代理層。

> **注意**：5 是**呼叫位置數**，不是請求次數。
> 其中有 `Promise.all(queries.map(fetchOne))`，也有逐景點驗證，
> 一次生成的實際請求數依查詢數、景點數與快取命中而變化。

SKU 判定依 **endpoint + Field Mask**，不能只用「請求數 × 固定單價」。

**驗收**
- [ ] 前端三支 JS 不再直接出現 `places.googleapis.com`
- [ ] `file://` 直開時的降級行為明確（代理不可用時的處理與 Vertex 一致）
- [ ] 不同 Field Mask 產生不同 SKU key，可在 run 紀錄中分辨
- [ ] **local-first 快取仍然生效**：命中本地 POI 時不得產生 Places 請求
      （這是這個專案的核心成本設計，回歸了就本末倒置）

---

### 1.6 前端顯示
```
本次生成 API 用量估算    NT$0.45
3 次 AI 請求 · 13,000 Tokens
```
展開：
```
模型：Gemini 3 Flash Preview
輸入：10,000 Tokens
輸出與思考：3,000 Tokens
計價版本：2026-07-27
─────────────────────────
用戶端估算（地圖 SDK，非後端觀察）
路線規劃：2 次 · 地理編碼：1 次
```

Maps JS SDK 的計數由前端 POST 給後端，**後端代寫**進 `clientReportedUsage`，
如此整份文件對用戶端維持唯讀，信任邊界只有一個缺口且集中。

**驗收**
- [ ] 權威估算與用戶端估算**視覺上明確分區**，不混在同一個數字裡
- [ ] 文案為「用量估算」而非「你已花費」
      （Gemini 有免費額度，純 token 數學會**高估**早期實際花費）
- [ ] `incomplete` 的 run 顯示「統計未完成」，不顯示金額
- [ ] 沒有 run 紀錄的舊行程 → 顯示「無統計資料」，不顯示 0 元
- [ ] 台幣為 `costUsd × fxRate` 換算，並標示匯率日期

---

## 2. 下次做（不進這批）

### 2.1 Directions / Geocoder 遷移為後端 Web Service
現況（已查證）：

| 服務 | 建構位置 | 呼叫位置 |
|---|---|---|
| `DirectionsService` | planner:13099 ×1 | `.route()` ×2（12730、13217） |
| `Geocoder` | planner:1826 ×1 | `.geocode()` ×1（1828） |
| 舊 `PlacesService` | planner:840 ×1 | — |

**為什麼獨立排程**：這不只是換 API。
`DirectionsRenderer` 在地圖上畫路線那一整套會失效，得自行處理
polyline 解碼、路線繪製、分段樣式、交通模式與錯誤處理。
這是專案級的功能改造，與成本統計綁在一起會讓兩件事都做不好。

附帶動機：`DirectionsService` 已於 2026-02-25 被標示為 deprecated，長期本來就該遷移。

### 2.2 Cloud Billing 對帳程序
做了才加 `billingStatus: reconciled`。需要定義：誰跑、多久跑一次、差異如何呈現。

---

## 3. 風險清單

| 風險 | 影響 | 對策 |
|---|---|---|
| 代理解析破壞 SSE 串流 | 生成體驗退回「轉圈到最後」，吃掉先前效能工作 | §1.3 驗收第一項，前後量測首字時間 |
| 每次呼叫寫 Firestore | 寫入放大、單文件競爭、額外成本 | 記憶體累加、結束一次落地 |
| 只擋子集合、忘了擋主文件 | 前端寫假成本欄位，畫面顯示假資料 | §1.4 用實際攻擊探測驗證 |
| Places 改代理時打破 local-first | 每趟行程的 Places 費用暴增 | §1.5 最後一項驗收 |
| 把估算講成帳單 | 對外報告失準 | 全域文案規則：一律寫「估算」 |

---

## 4. 驗收總表

完成本批後，下列全部成立才算完成：

1. 生成一趟新行程 → 行程頁顯示權威估算與用戶端估算，兩者分開標示
2. 生成中途關分頁 → 該 run 為 `incomplete`，不顯示 0 元
3. 開 DevTools 嘗試改寫 `generation_runs` 與主文件成本欄位 → 皆 permission-denied
4. 串流生成的首字時間與改動前無顯著差異
5. 目的地在 `poi-data.js` 內時，Places 請求數維持 0（local-first 未回歸）
6. `firestore.rules` 已由使用者部署，且既有共編功能全部回歸通過
7. 兩頁 F12 零紅字
8. 四支 JS `node --check` 通過
