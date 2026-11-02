# 交接：主動式旅途管家（App 端）

> 2026-11-03。網頁端已完成（見 [proactive-concierge.md](proactive-concierge.md)）。這次的環境沒有 JDK／Android SDK，**沒辦法用 gradle 編譯驗證，所以沒有改 Kotlin**。以下是 App 端要做的事，照現有程式碼的位置寫。

## 現況（已經有的）

- `ui/agent/AgentAlert.kt`：行程進行中「後面排不下」的通知（T4 延誤），點通知 `ACTION_OPEN` 回行程頁、按「讓 AI 處理」`ACTION_RUN` 直接送延誤請求。走 `NEARBY_STOP_CHANNEL_ID` 頻道，`SystemNotificationLog.Kind.AGENT` 記錄。
- `ui/planning/ItineraryPreviewScreen.kt` 約 343 行：`agentConflictKeys` 有新衝突且不是唯讀成員時呼叫 `AgentAlert.notify`；`AgentViewModel.shouldNotify` 只在記憶體去重。
- `MainActivity.kt` 約 200 行：接 `AgentAlert.ACTION_OPEN/RUN`，只在 `docId` 是目前開著的行程時才動作。
- `UpcomingTripReceiver`：出發前一天／當天早上的鬧鐘提醒（不檢查天氣與公休）。

也就是說 **App 已經有「落後」這一種主動提醒**，缺的是天氣、公休、出發前預檢，以及持久化的去重／冷卻與共編規則。

## 要做的事

### 1. 「管家建議」通知加兩種類型：天氣、公休

建議把 `AgentAlert.notify` 擴成帶 `kind`：

```kotlin
enum class Kind { DELAY, WEATHER, CLOSED }
fun notify(context: Context, docId: String?, kind: Kind, title: String, body: String, key: String)
```

- 標題用網頁同一套文字：`⚠️ 13:00 起降雨 80%，管家有建議`、`🚫 「臺東美術館」明天公休，管家有建議`、`⏱ 比原訂晚 30 分，…，管家有建議`。
- 通知 id：每種 kind 一個（例如 7401／7402／7403），同類新的蓋掉舊的。
- 動作按鈕：`看建議`（`ACTION_RUN`，帶 `EXTRA_KIND`）。WEATHER／CLOSED 的 RUN 要送 `trigger: { type: 'weather' }`（`AgentViewModel` 目前只有 `requestAutoDelay()`，要加一個 `requestAutoWeather()`，行程頁看到旗標就 `agentVm.startWeather(...)`，打開 `AgentSection`）。
- `MainActivity` 的 `LaunchedEffect(currentIntent)` 照現在的 docId 檢查，多讀 `EXTRA_KIND` 分流。
- **仍然只是打開管家、送出 replan**；提案照舊要在 AgentSection 按「套用」才寫入。

### 2. 偵測（不呼叫 AI）

| 類型 | 時機 | 規則（與網頁一致） |
|---|---|---|
| 天氣 | 行程進行中、出發當天、出發前一晚 18:00 後 | 還沒去的戶外站（排除餐廳、`anchor` 站）停留時段的 12 小時降雨機率 ≥ 70% |
| 公休 | 同上 | 還沒去的站當天公休（App 既有的營業時間解析） |
| 落後 | 進行中 | 沿用現有 `agentDelay.conflicts`，另外加「落後 ≥ 20 分」的門檻 |

- 行程頁開著時：在 `ItineraryPreviewScreen` 的 `LaunchedEffect` 旁邊加一個每分鐘的檢查（`TripProgressViewModel` 有時間來源的話用它，demo 模擬時才會用到虛擬時間）。
- App 在背景時：可以在 `UpcomingTripReceiver` 的鬧鐘（前一晚、當天早上）順便做預檢；行程進行中的背景檢查建議用 `WorkManager` 15 分鐘週期，不要自己開常駐服務。
- 天氣與公休發通知前，先問後端 **`POST /api/agent/precheck`**（下一節）。後端說 `alert: false` 就不通知。

### 3. 後端預檢 API

`POST {API_PROXY_BASE}/agent/precheck`，標頭與 replan 相同（`Authorization: Bearer <Firebase ID token>`），body 也相同：

```jsonc
{ "trip": { /* 與 replan 相同的 trip */ }, "trigger": { "type": "weather" }, "scenario": { "rain": { ... } } }
// 或 trigger 用 T4 延誤格式：{ "type": "delay", "day": 1, "now": "...+08:00", "from": { ... } }
```

回應（一般 JSON，不是 SSE）：

```jsonc
{ "alert": true, "kind": "weather", "headline": "15:00 起降雨機率 80%，1 個戶外站受影響",
  "issues": [{ "code": "rain", "stopId": "cstop-a1", "day": 1, "stop": "加路蘭", "message": "…" }],
  "simulated": true, "weather": "simulated" }
```

- 不呼叫 AI、不產生提案、不寫任何資料。限流：每 IP 10 分鐘 60 次（replan 是 15 次）。
- 延誤格式錯誤時回 400 `{ error, message }`，與 replan 一樣。
- `AgentClient` 可加一個 `suspend fun precheck(trip, trigger, scenario): PrecheckResult`，用現有的 OkHttp／Retrofit 設定即可。

### 4. 去重與冷卻（持久化）

- `AgentViewModel.notifiedConflictKeys` 目前只在記憶體，App 重開就會再通知一次。改存 `SharedPreferences`：`proactive_seen_<docId>`（`Set<String>` key）、`proactive_cooldown_<docId>_<kind>`（毫秒）。
- key 規則與網頁相同：`rain|日期|站 id 排序`、`closed|日期:站 id`、`lag|站 id|衝突`。
- 通知加一個「不用」動作（`BroadcastReceiver` 即可，不必開 App）：記下 key，該類型冷卻 60 分鐘。
- demo 模擬中（`simDelay`／`simRain`）只記在記憶體，不要寫進 SharedPreferences。

### 5. 多人共編

與網頁一致：**只有行程擁有者的裝置做背景偵測與發通知**。現在的條件是 `!isViewer`，editor 也會發；請改成「單人行程，或我是 owner」。理由與替代方案見 [proactive-concierge.md](proactive-concierge.md) 第 6 節（Firestore 規則目前不允許 editor 寫新欄位當鎖）。

### 6. Demo

AgentSection 的模擬開關（`simRain`、`simDelay`）打開時，偵測要用同一份模擬資料（降雨送 `scenario.rain` 給 precheck；延誤用 `scenario.delay.minutes`），讓展覽現場不用等真實天氣就能看到「管家主動跳通知 → 點開 → 建議卡」。

## 驗收

1. 開模擬降雨 → 1 分鐘內出現「管家有建議」通知 → 點「看建議」→ 進 AgentSection、送出 weather replan → 建議卡 → 套用後網頁端同步。
2. 同一個問題第二次不再通知；按「不用」後 60 分鐘內同類不通知。
3. 共編行程用旅伴（editor）帳號：不會收到通知；擁有者會。
4. 未登入／沒網路：不發天氣通知（拿不到預報不能當成下雨）。
