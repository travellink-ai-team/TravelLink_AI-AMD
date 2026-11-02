# 文字 AI 任務盤點與 `/api/ai/task` 遷移紀錄（方案 E）

> 盤點日期：2026-11-03。行號以本分支 commit 當下為準，合併後會位移，請以函式名搜尋。
> 後端：[server/ai-tasks.js](../../server/ai-tasks.js)；前端 client：[app/ai-task-client.js](../../app/ai-task-client.js)；測試：[server/test/ai-tasks.test.js](../../server/test/ai-tasks.test.js)。

## 1. 為什麼要收攏

- 前端各處自己組 Vertex endpoint 呼叫 `gemini-3-flash-preview`，程式碼上看不出實際由 AMD gpt-oss-120b 回答。
- Gemini 專屬參數（`responseMimeType: 'application/json'`、`thinkingBudget: 0`）經 `amd-llm.js` 轉成 gpt-oss 後，只剩 `json_object` 加 prompt 約束，偶爾仍會回壞 JSON，或回不符預期的形狀。
- prompt 寫在前端，等於任何登入者都能送任意 prompt 到 `/api/vertex`，後端只能靠 `scope-guard.js` 擋。

`/api/ai/task` 的做法：前端只送 `{ task, input }`，prompt、模型參數、輸出 JSON Schema 都在後端定義；
AMD 路徑用 vLLM 的 `response_format: json_schema`（guided decoding）強制輸出形狀，後端再驗一次 schema，失敗重試一次。

## 2. 前端文字 LLM 呼叫點盤點（共 13 處）

「經 proxy」指 `API_PROXY_BASE` 有設時走同源 `/api/vertex/*`（正式站即此模式）；沒設時直連 `aiplatform.googleapis.com`（只限開發）。

| # | 檔案:行號 | 函式 | 用途 | 經 proxy | 要求 JSON | 處置 |
|---|---|---|---|---|---|---|
| 1 | app/ai-travel-explore-final.js:1315（呼叫點 1688） | `fetchGeminiStream` ← `requestGeminiMicroTravelPlan` | 探索頁主行程生成（串流） | 是（帶登入 token） | 是 | **不遷移**：主行程生成，C agent 改 `/api/agent/plan` |
| 2 | app/ai-travel-explore-final.js:1298（呼叫點 1695） | `fetchGeminiJson` ← `requestGeminiMicroTravelPlan` | 主行程生成（非串流／串流失敗重試） | 是 | 是 | **不遷移**：同上 |
| 3 | app/ai-travel-explore-final.js:3735 | `fillTripTimeBudget` | 生成後行程偏短時沿路線補景點 | 是 | 是 | **不遷移**：主行程生成流程的一環（C） |
| 4 | app/ai-travel-planner-v8.js:4641（函式 4615） | `describePosterPlaces` | 互動插圖：站名轉「畫面描述」（文字步驟，不是出圖） | 是 | 是 | **已遷移** → `poster_place_descriptions` |
| 5 | app/ai-travel-planner-v8.js:9120（函式 9092） | `memoryAiEdit` | 九宮格大圖：打字叫 AI 調版面數字 | 是 | 是 | **已遷移** → `memory_layout_edit` |
| 6 | app/ai-travel-planner-v8.js:9528（函式 9499） | `generateRecapIdeas`（回顧短片 `recapSuggestIdeas`、九宮格 `memorySuggestTitles` 共用） | 標題與 hashtag | 是 | 是 | **已遷移** → `recap_ideas` |
| 7 | app/ai-travel-planner-v8.js:17023（stream endpoint 17021） | `replanWithAI` → `fetchReplanGeminiStream` | 行程頁「重新規劃」主呼叫（串流） | 是 | 是 | **不遷移**：行程生成／replan（C／D） |
| 8 | app/ai-travel-planner-v8.js:17076 | `replanWithAI` 補站 | 驗證淘汰後補替代景點 | ⚠ 送出但**沒帶登入 token**（見 §5） | 是 | **不遷移**（C／D） |
| 9 | app/ai-travel-planner-v8.js:17122 | `replanWithAI` 鄰近 fallback | 放寬地區找附近景點 | ⚠ 同上 | 是 | **不遷移**（C／D） |
| 10 | app/ai-travel-planner-v8.js:17198 | `replanWithAI` 補時 | 行程偏短時補景點 | ⚠ 同上 | 是 | **不遷移**（C／D） |
| 11 | app/ai-travel-planner-v8.js:17344（函式 17303，呼叫點 17738） | `requestGeminiTravelPlan` | 管家聊天式行程調整（非 replan 意圖時） | 是；Vertex 未設定時**退回 BYOK Gemini API key 直連** `generativelanguage.googleapis.com` | 是 | **不遷移**：管家（D） |
| 12 | app/ai-travel-planner-v8.js:5131（函式 5127） | `generatePosterWithDefault` | 互動插圖出圖（`gemini-3-pro-image`） | 是 | 否 | **不遷移**：圖片生成 |
| 13 | app/ai-travel-planner-v8.js:5435、5384 | `generatePosterWithOwnGemini`、`testPosterByokKey` | BYOK 出圖、列出模型 | **否**（使用者自己的金鑰直連 Google） | 否 | **不遷移**：圖片生成，且刻意不經我們的後端 |

另有不在前端的 AI 呼叫，僅列出備查：
- `/api/agent/replan`（server/agent/loop.js）：已是後端 Agent，直接用 `amdLlm.chat()` tool calling。
- Android App（android/…/data/ai/AmdTextClient.kt）：走同一個 `/api/vertex` 代理；不在本次範圍。

任務說明中提到的「景點介紹或評論摘要、預算說明文字」：盤點後**目前前端沒有用 LLM 產生這些內容**（景點介紹來自 `poi-data.js`，預算來自 `cost-config.js`／`attraction-fee-config.js` 的規則計算），因此沒有可遷移的呼叫點，本次不新增。

## 3. 已遷移的 task 與 schema

所有 task 共用：
- 系統訊息：`scope-guard.js` 的 `SCOPE_INSTRUCTION` ＋一句「本次任務是 TravelLink 的旅遊功能：…」。
  只放 `SCOPE_INSTRUCTION` 時，gpt-oss 會把「調版面」判成與旅遊無關（`json_object` 模式回 `{"reply":"此服務僅提供旅遊相關功能。"}`，`json_schema` 模式則把輸入原封不動抄回），補上這句後才會照做。
  **現行 `/api/vertex` 路徑上的 `memoryAiEdit` 在 `AI_PROVIDER=amd` 時會失敗**：2026-11-03 用同一段版面 prompt 走舊路徑（`guardVertexBody` → `toOpenAiRequest` → AMD）實測 3/3 次都回 `{"reply":"此服務僅提供旅遊相關功能。","actions":[]}`，前端會顯示「AI 回傳的格式看不懂」。遷移後已修正。
- 使用者自由輸入（只有 `memory_layout_edit.instruction`）放在 `<<< >>>` 之間並註明「只當作版面需求」。
- 輸入先經 `sanitize()` 淨化與裁長度，prompt 只用淨化後的欄位；不合法回 400。

| task | 前端呼叫點 | input | 輸出 schema（摘要） | 失敗時前端的退回行為（沿用既有） |
|---|---|---|---|---|
| `recap_ideas` | `generateRecapIdeas(kind)` | `{ kind: 'video'\|'grid', region, days, dateLabel, transportMode, stops: string[] }` | `{ titles: string[1..5], hashtags: string[1..16] }`；normalize 後 titles ≤3、hashtags 補 `#`、去重、≤12 | `recapTemplateIdeas()` 離線範本（提示顯示「離線建議」） |
| `memory_layout_edit` | `memoryAiEdit()` | `{ instruction ≤200 字, usedCards 0..9, layout: { cards: [{cx,cy,w,angle}], titleBand: {cx,cy,w,h,visible} } }` | `{ cards: [{cx,cy,w,angle: number}] ≤9, titleBand: {cx,cy,w,h: number, visible: boolean} }`；normalize 夾限範圍、張數裁成 usedCards | 橘色 toast 顯示錯誤，版面不變；前端 `applyAiMemoryLayout` 仍再夾限一次 |
| `poster_place_descriptions` | `describePosterPlaces()` | `{ area, places: [{ n, name, type }] ≤30 }` | `{ descriptions: [{ n: integer, desc: string }] ≤30 }`；只留清單內編號、desc ≤40 字 | `posterGenericDescription()` 通用描述，出圖照常進行 |

原本 `describePosterPlaces` 的輸出是 `{"1": "..."}` 這種以編號為 key 的物件；這種形狀不好用 schema 約束，改成陣列 `{n, desc}`，前端自行轉回對照表。

完整 schema 見 `server/ai-tasks.js` 的 `TASKS`。新增 task 時照同一格式加一個項目（`label`、`scopeNote`、`temperature`、`maxTokens`、`sanitize`、`prompt`、`schema`、`normalize`）即可，前端用 `WAI_AI_TASK.request(task, input, { headers })` 呼叫。

## 4. 端點規格

`POST /api/ai/task`（需登入；每 IP 10 分鐘 40 次）

```jsonc
// 請求
{ "task": "recap_ideas", "input": { "region": "台東", "stops": ["鐵花村"] } }
// 成功（200）；標頭另有 x-ai-provider: amd、x-ai-model: gpt-oss-120b
{ "ok": true, "task": "recap_ideas", "output": { "titles": [...], "hashtags": [...] },
  "provider": "amd", "model": "gpt-oss-120b", "label": "AMD gpt-oss-120b",
  "structured": "json_schema", "attempts": 1 }
// 失敗：400 invalid input／unknown task、401、429、502 invalid output／upstream failed
{ "ok": false, "error": "invalid output", "message": "AI 暫時無法完成，請稍後再試。", "provider": "amd", "model": "gpt-oss-120b" }
```

- `AI_PROVIDER=amd` 且有 `AMD_LLM_BASE_URL`：`amdLlm.chatJson()` → `response_format: { type: 'json_schema', json_schema: { name, schema, strict: true } }`。
  端點若回 400 並提到 response_format／json_schema／guided／structured，該行程內自動改用 `json_object`，`structured` 欄位會顯示實際模式。
- 否則：Vertex `gemini-3-flash-preview`，`responseMimeType: application/json`、`thinkingBudget: 0`（`structured: "mime_json"`）。
- 重試：JSON 解析失敗、schema 不符、輸出被截斷、上游 5xx／連線失敗 → 重試一次（schema 問題會把錯誤原因附給模型）；4xx 不重試。每次嘗試 30 秒逾時。
- 記帳：帶 `X-Run-Id` 時用量記進 generation run（AMD 記在 `gpt-oss-120b`，Vertex 記在 `gemini-3-flash-preview`）；失敗時 `markUpstreamError`。
- 前端標示：`provider === 'amd'` 時在回顧／九宮格標題提示句開頭、九宮格 AI 輸入框下方顯示「AMD gpt-oss-120b」小標示；互動插圖在模型說明後加「・地點描述由 AMD gpt-oss-120b 撰寫」。Vertex 時不顯示。
- 未設 `API_PROXY_BASE`（本機直連開發）時 `WAI_AI_TASK.enabled()` 為 false，三個函式照舊走原本的直連程式碼（未刪除）。

## 5. 盤點時發現、但不在本次範圍的問題

1. **replan 的三個補站呼叫沒帶登入 token**（表中 #8～#10）：`headers: { 'Content-Type': 'application/json' }`，沒用 `vertexAuthHeaders()`。代理模式下後端 `requireFirebaseUser` 會回 401，補站／鄰近／補時這三層 fallback 在正式站等於永遠失敗（被 try／條件吞掉，所以不會報錯）。屬於 replan（C／D 範圍），未修改。
2. `requestGeminiTravelPlan`（#11）在 Vertex 未設定時會退回使用者的 Gemini API key 直連，這條路完全不經代理，也就不會走 AMD。正式站有設代理所以不會發生。
3. `memoryAiEdit` 在 AMD 路徑被範圍限制誤判（見 §3），遷移後已修正；但未設代理的直連路徑仍是舊行為（直連只走 Vertex，不受影響）。
