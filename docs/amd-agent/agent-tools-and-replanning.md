# TravelLinkAI Agent：工具清單與自主應變流程（AMD AI 代理人創新應用組）

> 狀態：設計稿（2026-10-02）。**後端已實作 P1、P2 的一部分與情境 A、B、D**，見文末「實作狀態」。前端尚未接上。
> 目標：讓 AI 從「幫你寫一份行程」升級成「旅途中自己發現問題、自己查資料、自己修好行程、再請你確認」的代理人。
> 評分對應：創新性 30%（自主應變）、市場性 40%（實際效益）、技術性 20%（共通性、資安、成熟度）。

---

## 1. 設計原則

1. **LLM 負責判斷，程式負責驗證。** 營業時間、時間順序、船班、預算這類硬規則由程式（`validate_itinerary`）檢查，不交給 LLM 猜。LLM 提出方案，驗證失敗就把錯誤回饋給它重來。這是代理人「自我修正」的核心，也是 demo 時最穩的地方。
2. **先用本地資料，最後才打付費 API。** 沿用專案既有的本地優先（local-first）設計：`poi-data.js`、`restaurant-data.js`、`parking-data.js` 免費；Google Places 只當最後手段，而且每次執行有呼叫上限。
3. **改行程一定要使用者確認。** Agent 只產生「修改提案（patch）」，按下套用前不會動到使用者的行程。使用者手動改過的時間照 AGENTS.md 規則保留。
4. **Agent loop 放在 server 端。** 工具需要 CWA、TDX、Places 的金鑰，而且步數、逾時、工具白名單都要在伺服器端控管。前端用串流（SSE）即時顯示 Agent 的「思考 → 呼叫工具 → 結果」過程。
5. **模型可以替換。** 對 LLM 一律走 OpenAI 相容的 tool calling 介面：決賽用 AMD Developer Cloud（MI300X + vLLM），備援用 AMD AI PC（Lemonade Server），開發時也能接其他模型。

---

## 2. 工具清單

標示說明：**既有**＝專案已有可重用的邏輯；**新增**＝要新寫；費用欄的「免費」代表只讀本地資料或免費開放資料。

### 2.1 感知類（發現問題）

| # | 工具 | 參數 | 回傳 | 資料來源 / 可重用程式 | 費用 |
|---|---|---|---|---|---|
| 1 | `get_trip_state` | `tripId` | 行程（每日 stops、時間、使用者手動修改標記）、目前第幾天、路線進度、使用者位置（若有授權）、偏好（避免標籤、預算、人數） | 既有：`currentTripPreferences`、`restoreRouteProgress`、`replanStops` | 免費 |
| 2 | `get_weather_forecast` | `region`, `dates[]` | 每日／時段降雨機率、溫度、天氣描述、是否「戶外不宜」 | 既有：server `/api/cwa` 代理、`fetchTaitungWeather`、`getRainyTripDays`（降雨機率 ≥ 50%） | 免費（CWA 開放資料） |
| 3 | `get_sea_conditions` | `island`, `date` | 浪高、風力、**停航風險等級**（低／中／高） | **新增**：CWA 海面天氣預報資料集（資料集代號待確認），套用門檻規則推估風險 | 免費 |
| 4 | `get_ferry_status` | `island`, `date` | 該日各班次、是否停航、公告來源 | **新增**：資料源待確認（TDX 航運資料或船公司公告）。**demo 一定要支援「情境注入」**（見第 6 節） | 免費 |
| 5 | `check_business_hours` | `stopName`, `date`, `arriveTime`, `leaveTime` | `open` / `closed` / `closing_soon` / `unknown`、當日時段 | 既有：`business-hours.js` 的 `parseDayStatus`、planner 的 `getBusinessHoursWarning` | 免費 |

### 2.2 查詢類（找替代方案）

| # | 工具 | 參數 | 回傳 | 資料來源 / 可重用程式 | 費用 |
|---|---|---|---|---|---|
| 6 | `search_local_poi` | `region`, `near{lat,lng}`, `radiusKm`, `category?`, `indoor?`, `excludeNames[]` | 景點候選（名稱、座標、營業時間、停留時間、門票、是否室內） | 既有：`getLocalPoiList`、`poi-data.js`；排除已去過的地點用 `getVisitedPlaces`、封鎖清單用 `getBlockedSpotNames` | 免費 |
| 7 | `search_restaurants` | `near`, `mealTime`, `avoidTags[]`, `maxCostPerPerson?` | 餐廳候選（含 `costPerPerson`、營業時間） | 既有：`restaurant-data.js`、`getLocalFoodCostMap`；避免標籤用 `avoid-tags.js` | 免費 |
| 8 | `find_parking` | `near`, `radiusM` | 停車場（距離、公有／私有、使用者回報） | 既有：`parking-data.js`、`nearbyTdxParkings`、社群停車回報 | 免費 |
| 9 | `estimate_travel` | `from`, `to`, `mode` | 距離、預估時間 | 既有：`measureDistanceMeters` 加上車速估算；需要精準時才呼叫 Directions | 預設免費 |
| 10 | `estimate_cost` | `stops[]`, `people` | 門票、餐費、交通費、每人合計、與預算的差距 | 既有：`lookupStopFee`、`lookupStopFoodCost`、`estimateTripTransport`、`buildBudgetBreakdown`、`getFerryRoundTrip` | 免費 |
| 11 | `verify_place` | `name`, `region` | 是否真實存在、精準座標、營業時間 | 既有：`verifyPlaceWithOpenData`，找不到才用 Places 查（`findNearbyFallbackStop` 的邏輯） | **付費，最後手段**，每次執行最多 3 次 |

### 2.3 行動類（修好行程、通知）

| # | 工具 | 參數 | 回傳 | 說明 |
|---|---|---|---|---|
| 12 | `propose_patch` | `ops[]`：`replace` / `insert` / `remove` / `move` / `retime` / `swap_day` | 草稿行程和差異（diff） | **只產生草稿，不寫入。** 草稿會自動送進 #13 驗證 |
| 13 | `validate_itinerary` | `draft` | `ok` 或 `violations[]`（每條附原因和建議） | **新增、純程式、最重要。** 檢查項目見 2.4 |
| 14 | `ask_user` | `question`, `options[]` | 使用者的選擇 | 有取捨要使用者決定時才用，例如「多住一晚」還是「提早搭船」 |
| 15 | `present_proposal` | `summary`, `reasons[]`, `diff`, `costDelta` | 使用者選擇「套用／修改／放棄」 | 結束 loop 的動作。按「套用」後走既有的 `applyReplan` 流程；共編行程先取 `WAI_COLLAB.acquireRegenLock` |
| 16 | `notify_companions` | `message` | 是否送達 | 既有：`WAI_NOTIFY.pushToMany`，通知共編的同行者 |

### 2.4 `validate_itinerary` 檢查項目（硬規則）

1. **營業時間：** 每個 stop 抵達到離開的時段都在營業時間內（`unknown` 只發警告，不擋）。
2. **時間順序：** 同一天的時間要遞增；交通時間不能小於 `estimate_travel` 的估計值。後面的 stop 只有在時間早於或等於前一個 stop 時才自動延後，使用者手動設定的時間要保留（依 AGENTS.md 規則）。
3. **船班：** 離島行程的上下船時間要對得上可搭的班次；停航當天不能排離島移動。
4. **天氣：** 降雨機率 ≥ 50% 的時段，戶外 stop 要有理由（例如使用者堅持要去），否則視為違規。
5. **偏好：** 不違反避免標籤（#素食、#避免水上活動…）；每人花費不超過預算上限 × 1.1。
6. **合理性：** 每天不超過使用者設定的結束時間；不重複、不排已去過的地方；用餐時段有安排餐廳。

---

## 3. 自主應變流程

### 3.1 觸發條件

| 代號 | 觸發 | 偵測方式 | 頻率 |
|---|---|---|---|
| T1 天氣 | 有戶外 stop 的那天降雨機率 ≥ 50% | `get_weather_forecast` | 出發前每天一次，旅途中每 3 小時 |
| T2 船班 | 離島移動日的停航風險高或確定停航 | `get_sea_conditions`、`get_ferry_status` | 出發前 72 小時起，每 6 小時 |
| T3 營業 | stop 當天公休或來不及在關門前抵達 | `check_business_hours` | 生成行程後、出發前一天 |
| T4 延誤 | 實際進度比計畫晚超過 30 分鐘 | 路線進度和目前時間比對 | 旅途中，每完成一站時檢查 |
| T5 主動要求 | 使用者說「好累」、「下雨了」、「想多待一下」 | 聊天輸入 | 隨時 |
| T6 超支 | 預估花費超過預算 | `estimate_cost` | 每次修改行程後 |

T1～T4、T6 由程式定時檢查，**沒問題就不呼叫 LLM**，這樣才省成本。只有偵測到問題時才啟動 Agent loop。

### 3.2 Agent loop

```
 偵測到觸發事件（程式判斷）
        │
        ▼
 ① 了解現況   get_trip_state + 觸發事件的細節
        │
        ▼
 ② 評估影響   哪幾天、哪幾個 stop 受影響？嚴重程度？
        │       （輕微：單一 stop → 局部替換；嚴重：船班停駛 → 整天重排）
        ▼
 ③ 找替代方案 search_local_poi / search_restaurants / find_parking / estimate_travel
        │
        ▼
 ④ 組草稿     propose_patch
        │
        ▼
 ⑤ 驗證       validate_itinerary ──違規──► 把 violations 回饋給 LLM，回到 ③
        │ ok                              （最多 3 輪，超過就降級，見 3.3）
        ▼
 ⑥ 算差異     estimate_cost（花費差多少）＋ 時間差異
        │
        ▼
 ⑦ 有取捨？   是 → ask_user（例如多住一晚 vs 提早回本島）
        │
        ▼
 ⑧ 提案       present_proposal：一句話摘要 + 原因 + 修改前後對照 + 花費變化
        │
        ▼
 ⑨ 使用者按「套用」 → applyReplan → notify_companions
```

### 3.3 防護機制

| 項目 | 設定 |
|---|---|
| 最大步數 | 一次執行最多 12 次工具呼叫，驗證最多重試 3 輪 |
| 逾時 | 整次執行 60 秒，沿用既有 `activeReplanDeadlineAt` 的做法 |
| 付費 API 上限 | `verify_place` 每次執行最多 3 次 |
| 工具白名單 | server 端只執行清單內的工具；參數用 schema 驗證，LLM 傳的名稱和座標都要檢查 |
| 降級 | LLM 逾時或驗證 3 輪都失敗時，改用規則式替換：同類別、最近、有營業的本地 POI（類似既有 `swapStopWithAlternative`），並告訴使用者「這是快速替代方案」 |
| 不自動套用 | 任何修改都要使用者按「套用」 |
| 共編 | 套用前取重生成鎖，避免和同行者同時改行程 |
| 記錄 | 每次執行記錄觸發原因、工具呼叫、token 用量（接到既有 `generation-runs.js`），可以拿來做「效益數字」 |

---

## 4. 情境劇本（demo 主軸）

### 情境 A：綠島回程船班停駛（主秀，最能展現「代理人」）

> 3 天 2 夜綠島行，第 3 天 15:30 搭船回富岡漁港。第 2 天晚上 CWA 預報第 3 天浪高 3 公尺以上。

1. **T2 觸發：** `get_sea_conditions(綠島, D3)` 回傳停航風險「高」。
2. **評估影響：** D3 下午的回程船班可能取消，後面還連著台東市區的晚餐和住宿。
3. **找方案：**
   - 方案 1：改搭 D3 **早上**的船提前回本島，下午改排台東市區室內景點（`search_local_poi(台東, indoor=true)`）。
   - 方案 2：在綠島**多住一晚**，D3 改成島上室內或避風景點，D4 再回本島。
   - 方案 3：改搭飛機（綠島機場），用 `estimate_cost` 算出價差。
4. **驗證：** 每個方案都送進 `validate_itinerary`。如果方案 1 排的景點當天公休，就退回去重找。
5. **有取捨：** 呼叫 `ask_user`，提問：「明天下午的船很可能停駛。A：早上 9:30 的船提早回台東（多花 0 元、行程少 2 個綠島景點）；B：多住一晚（每人約多花 NT$1,800）。」
6. **提案和套用：** 套用後通知同行者。

### 情境 B：午後下雨（T1，最常見）

D2 下午 14:00 後降雨機率 70%，原本排的是戶外步道 → 換成附近室內景點（館舍、文創園區）→ 驗證營業時間和車程 → 提案：「下午改去 X，車程從 25 分鐘縮短到 12 分鐘，門票每人多 NT$50」。

### 情境 C：行程延誤（T4，旅途中）

到第 3 站時已經比計畫晚 50 分鐘 → 評估後面的 stop：哪個會來不及在關門前抵達？→ 提出「刪掉 X」或「X 和 Y 對調」→ 晚餐訂位時間不變。

### 情境 D：使用者說「好累，想輕鬆一點」（T5）

刪掉或縮短步行量大的 stop，換成咖啡廳或景觀點，套用 `#避免大量步行` 偏好，結束時間提前。

---

## 5. 前端呈現（創新性的關鍵畫面）

1. **Agent 動態面板：** 用串流即時顯示每一步，例如「🔍 查詢綠島明天海象…」、「⚠️ 浪高 3.2m，停航風險高」、「🔄 找到 3 個替代方案」、「✅ 驗證通過」。評審要能**親眼看到它在自己思考和動手**。
2. **提案卡：** 修改前後對照（刪除線／新增標記）、原因、花費變化，以及「套用／修改／放棄」按鈕。
3. **主動通知：** 透過既有通知中心推播「你的行程有狀況，我已經準備好替代方案」。

---

## 6. Demo 情境注入（決賽必備）

11/7 決賽當天不一定會下雨或停航，所以要有一個**情境模擬面板**（只在 demo 模式顯示）：

- 「模擬：綠島明天停航」 → 讓 `get_ferry_status` / `get_sea_conditions` 回傳停航
- 「模擬：下午大雨」 → 讓 `get_weather_forecast` 回傳高降雨機率
- 「模擬：已延誤 50 分鐘」 → 調整路線進度時間

注入的資料只替換工具的回傳值，**Agent 本身的流程完全真實執行**。簡報時要誠實說明這是模擬的觸發事件。

---

## 7. 和評分項目的對應

| 評分項目 | 本設計提供的證據 |
|---|---|
| 創新性 30% | 事件驅動、主動發現問題、多工具推理、自我驗證修正、和使用者協商取捨；過程即時呈現 |
| 市場性 40% | 停航和下雨是台東、離島旅遊的真實痛點；記錄「應變次數、節省時間、避免的損失」當效益數字；可延伸成 B2B 服務（縣府觀光處、民宿、船公司推播停航改程） |
| 技術性 20% | OpenAI 相容介面可在 AMD 雲端和 AI PC 之間切換（跨應用共通性）；程式化驗證（可行性、成熟度）；金鑰留在伺服器、登入驗證、限流、工具白名單（資安） |
| 文件 10% | 本文件可直接轉成系統規格章節 |

---

## 8. 實作順序建議

| 階段 | 內容 | 產出 |
|---|---|---|
| P1 | server 新增 OpenAI 相容 LLM 路由（AMD vLLM / Lemonade），加上 Agent loop 骨架（白名單、步數、逾時、SSE） | 能跑通「LLM 呼叫一個假工具」 |
| P2 | 純程式工具：#1、#2、#5～#10、#13（大多是把既有前端邏輯搬到可共用的模組） | `validate_itinerary` 加上單元測試 |
| P3 | 情境 B（下雨）端到端跑通，含前端動態面板和提案卡 | 第一個可 demo 的情境 |
| P4 | #3、#4 船班和海象，加上情境 A、情境注入面板 | 主秀情境 |
| P5 | 情境 C、D，降級機制，加上效益數據記錄 | 補齊 |

**待確認：**
- CWA 海面天氣預報的資料集代號和欄位
- 綠島、蘭嶼船班資料能不能從 TDX 取得
- 前端 planner 的邏輯是包在 IIFE 裡的瀏覽器程式，要讓 server 端也能用，需要抽成共用模組（類似 `business-hours.js` 的 UMD 寫法）

---

## 9. 實作狀態（2026-10-02）

**LLM：** 大會（AMD／工研院）提供的 gpt-oss-120b vLLM 端點，OpenAI 相容、支援 tool calling。第 1 節第 5 點的「AMD Developer Cloud」改成這個端點；端點只開放登記 IP，網址放 `server/.env` 的 `AMD_LLM_BASE_URL`，不進 repo。雲端 session 連不到，實測要在登記 IP 的機器上跑。

**已完成（server/agent/）：**

| 檔案 | 內容 |
|---|---|
| `loop.js` | Agent loop：程式先檢查，沒問題就不呼叫 LLM；工具白名單、最多 12 次工具呼叫、驗證失敗 3 輪、總時限 60 秒；失敗降級成規則式替換（標 `fallback`） |
| `tools.js` | `get_trip_state`、`get_weather_forecast`、`check_business_hours`、`search_local_poi`、`search_restaurants`、`estimate_travel`、`propose_patch`（自動驗證）、`ask_user`、`present_proposal`、`no_change_needed` |
| `itinerary.js` | `validate_itinerary` 的硬規則（營業時間含午休多時段、時間順序與車程、每天結束時間、降雨 ≥ 50% 的戶外站、重複、暫停開放、預算）、套用修改（replace／insert／remove／retime 含 stayMin）、差異 |
| `data.js` | 直接讀 `app/poi-data.js`、`restaurant-data.js`、`business-hours.js`；室內外用名稱與簡介推估；CWA 一週預報；情境注入 `scenario.rain` |

**API：** `POST /api/agent/replan`（需登入、每 IP 10 分鐘 15 次），body `{ trip, trigger: { type: 'weather'|'user', message? }, scenario? }`，回應是 SSE，事件 `start`／`check`／`check_result`／`tool_call`／`tool_result`／`fallback`，最後是 `proposal`／`question`／`no_change`／`error`。`trip.stops[]` 每站 `{ id, day, time, stayMin, name, lat?, lng?, timeLocked?, keepReason? }`。

**實測（2026-10-02，`node test/agent.test.js --live`）：**
- 情境 B（模擬 13:00–17:00 降雨 80%）：兩個戶外站換成附近室內景點（臺東美術館、寶町藝文中心等），5–7 次 LLM、1.4–2.9 秒，連跑多次結果一致。
- 情境 D（「好累，想早點回飯店」）：刪站並縮短停留，結束時間 19:00 → 14:30，約 1 秒。

**情境 A（P4，2026-10-02）：** `sea.js`（CWA F-A0012-001 綠島蘭嶼海面預報 → 停航風險高／中／低，颱風警報、TDX 通阻公告）、`ferry.js`（從港口站找出去程／回程船班、判斷每站在島上或本島）；新工具 `get_sea_conditions`、`get_ferry_status`；新 op `move`、`move_ferry`（整段船班改天，程式自動處理港口站、之後的本島站與來不及去的島上站），可延長一晚；驗證新增 `ferry_risk`、`wrong_side`（沒搭船就跨海）。實測模擬第 3 天浪高 3.5 公尺：每次都把回程提前到風險低的第 2 天，2.4–7.8 秒。第 4 天真實預報也是高風險，所以 Agent 不會提議多住一晚。

**還沒做：** 套用後的 `notify_companions`、`estimate_cost` 的交通費與住宿費、延誤觸發（T4）、船班時刻（沒有資料源，需人工查證後建設定檔）。

**已知限制：** 室內外是用名稱推估的，不是資料欄位；本地資料有少數店家同時出現在景點資料且被標成景點（例如「榕樹下米苔目」），完全同名時會抓到景點那筆。
