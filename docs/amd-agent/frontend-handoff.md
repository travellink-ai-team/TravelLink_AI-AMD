# 交接：旅程應變 Agent 前端（P3）

> 寫給接手的雲端 session。後端已完成並實測過，這份只講前端要做什麼、怎麼在連不到 AI 的環境下開發。
> 先讀：[AGENTS.md](../../AGENTS.md)、[CLAUDE.md](../../CLAUDE.md)、設計稿 [agent-tools-and-replanning.md](agent-tools-and-replanning.md)（第 5、6、9 節）。

## 你的環境限制（重要）

- **連不到 AI 端點。** gpt-oss-120b 是 AMD／工研院提供的，只開放登記的 IP；雲端環境沒有 `server/.env`、沒有 Firebase 服務帳戶，**跑不起後端**。
- 所以前端要做 **mock 模式**：重播 [server/test/fixtures/](../../server/test/fixtures/) 裡真實錄下的事件（下面有格式）。真的接後端、上正式站測試，由本機那邊負責。
- **不要動 `server/`**（除非是修明顯的 bug，並在 commit 說明）。後端的行為以 `node server/test/agent.test.js`（離線，不需網路）為準，改了要跑過。
- 端點網址**絕對不能**寫進 repo 任何地方。

## 要做的事

全部在 planner：[app/ai-travel-planner-v8.js](../../app/ai-travel-planner-v8.js)、[.css](../../app/ai-travel-planner-v8.css)、[.html](../../app/ai-travel-planner-v8.html)（html 只放標記）。**不要動 explore**（`ai-travel-explore-final.*` 是 codex 負責的）。

1. **入口**：行程頁加兩個觸發點
   - 「檢查天氣並調整」→ `trigger: { type: 'weather' }`
   - 一個輸入框讓使用者說需求（例如「好累，想早點回飯店」）→ `trigger: { type: 'user', message }`
2. **Agent 動態面板**：把 SSE 事件逐條顯示出來，評審要能「親眼看到它在思考和動手」。
   - `check` / `check_result`：程式先檢查（不呼叫 AI），`check_result.issues[]` 是發現的問題
   - `tool_call.label` → 「🔧 搜尋附近室內景點」；接著的 `tool_result.detail` → 「找到 6 個：臺東美術館 0.8km…」，`ok: false` 時用警示樣式
   - `fallback.label`：AI 失敗、改用規則式替換
   - 每個事件都有 `ms`（從開始算起的毫秒），可以顯示經過時間
3. **提案卡**（最後一個事件 `type: 'proposal'`）
   - `summary`、`reasons[]`
   - `changes[]`：修改前後對照。`type` 有 `replace`（`from` → `to`）、`remove`（`from`，用刪除線）、`insert`（`to`，用新增標記）、`retime`（`from`/`to` 是時間，`stayFrom`/`stayTo` 是停留分鐘）
   - `costDelta.perPersonBefore / perPersonAfter / diff`（門票加餐費，不含交通）
   - `fallback: true` 要標「快速替代方案（未經 AI 推理）」；`simulated: true` 要標「模擬情境」
   - 按鈕：「套用」「放棄」。**套用**＝把 `draft.stops` 寫回目前行程，走既有的 `applyReplan` 流程；共編行程先取 `WAI_COLLAB.acquireRegenLock`。套用前不能改到使用者的行程。
   - `draft.stops[]` 每站：`{ id, day, time, stayMin, name, lat, lng, kind, replaces?, agentAdded? }`。`replaces` 是被取代的原站 id
4. **其他結尾事件**：`question`（`question` + `options[]`，先顯示出來即可，回覆流程之後再做）、`no_change`（`reason`；`llmSkipped: true` 時可以寫「程式檢查沒發現問題，沒有呼叫 AI」）、`error`（`message`）
5. **demo 情境注入面板**（只在 demo 模式顯示，例如網址帶 `?demo=1`）：「模擬：下午大雨」→ 請求帶 `scenario: { rain: { date: 'YYYY-MM-DD', from: '13:00', to: '17:00', pop: 80 } }`

## API

`POST {API_PROXY_BASE}/agent/replan`（同源 `/api/agent/replan`）

- 標頭：`Content-Type: application/json`、`Authorization: Bearer <Firebase ID token>`（planner 既有的 `vertexAuthHeaders()` 會組好含 token 的標頭，可以直接用）；選填 `X-Run-Id`（成本統計）
- body：`{ trip, trigger, scenario? }`
  - `trip`：`{ title, region, startDate: 'YYYY-MM-DD', endTime: 'HH:MM', people, budgetPerPerson?, stops: [{ id, day, time: 'HH:MM', stayMin, name, lat?, lng?, timeLocked?, keepReason? }] }`
  - 從 planner 現有的行程狀態組出來（`replanStops` 等）。使用者手動改過時間的站帶 `timeLocked: true`
- 回應：`text/event-stream`，每個事件一行 `data: {json}`，空行分隔。錯誤時（401/400/429）回一般 JSON。
- 限流：每 IP 10 分鐘 15 次。

## mock 模式

[server/test/fixtures/](../../server/test/fixtures/) 有三份真實錄下的執行：

| 檔案 | 情境 | 結果 |
|---|---|---|
| `agent-rain.json` | 模擬 13:00–17:00 降雨 80% | 兩個戶外站換成室內景點，14 個事件，約 1.8 秒 |
| `agent-tired.json` | 使用者：「好累，想早點回飯店」 | 刪兩站、縮短一站，約 1 秒 |
| `agent-no-issue.json` | 沒問題 | 程式檢查後直接結束，沒呼叫 AI |

格式：`{ request, events[] }`，照 `events[i].ms` 的間隔依序送出就是真實的節奏。建議 mock 開關用網址參數（例如 `?agentMock=rain`），走跟真 SSE 同一套事件處理函式，這樣接上後端時不用改 UI。fixture 放在 `server/` 底下，正式站不會對外提供；mock 要用的話請複製一份精簡版到 `app/` 底下的獨立檔案，或直接內嵌在 JS 裡。

## 專案慣例（踩過的坑）

- **改了 js/css 一定要跳 html 裡的 `?v=` 版本號**，否則 nginx／瀏覽器會繼續供舊檔。
- planner 執行期**沒有全域 `showToast`**，直接呼叫會 ReferenceError；用 `feedbackToast` 或 `showVisitedToast`，或先 `typeof` 檢查。
- 中文介面避免奇怪的換行（詞被拆開、按鈕標籤斷成兩行、句尾孤字）：用 repo 裡的 `cjk-line-wrap` skill。
- F12 不能有紅色錯誤。
- 每次改完照 AGENTS.md 的「User Flow Test Result」格式回報；連不到後端和正式站的部分寫在 Not Tested。

## 完成後

開 PR 或推一個分支，在描述裡寫清楚哪些只在 mock 下測過。本機那邊會接真後端、在正式站驗證。

## 追加：情境 A（綠島停航）— 2026-10-02

後端已支援離島船班停航（見 [ferry-sea-data-research.md](ferry-sea-data-research.md)）。前端要補的：

1. **demo 面板加「模擬：回程日浪高 3.5 公尺」**：請求帶 `scenario: { sea: { date: 'YYYY-MM-DD', waveMaxM: 3.5, gustMax: 9 } }`（date 用行程回程那天）。可與 `rain` 同時存在。
2. **新的 fixture**：`server/test/fixtures/agent-ferry.json`（16 個事件，約 4.5 秒），mock 參數建議 `?agentMock=ferry`。
3. **提案卡的新欄位**（只有離島行程才有）：
   - `changes[]` 多一種 `type: 'move'`：`{ name, fromDay, day, from, to }`（站被移到別天，`from`/`to` 是時間）
   - `ferry: { before: [{ direction: '去程'|'回程', day, depart }], after: [...], note }`：建議做成「回程 第 3 天 15:30 → 第 2 天 15:30」這種醒目的一行，`note` 要顯示（依預報推估，以船公司公告為準）
   - `extraNights`：多住幾晚（有值時要提醒住宿另計，`costDelta.note` 已含這句）
4. **回答 `question`**：Agent 有取捨時會回 `question` + `options[]`。使用者選了之後，**再送一次請求**：同一份 `trip`、同一個 `scenario`，`trigger: { type: 'user', message: '<原本的問題>。我的選擇：<選項文字>' }`。後端不保存對話狀態，所以要把問題一起帶上。你們目前「點選項填進輸入框」的做法只要確保送出時帶上問題即可。
5. 離島行程的 `trip.region` 請帶 `'綠島'`／`'蘭嶼'`，港口站（富岡漁港、南寮漁港、開元漁港）照原本的名稱送；後端靠港口站判斷搭船。
