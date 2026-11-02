# 主動式旅途管家（方案 D）

> 2026-11-03。網頁端已實作；App 端的接法見 [proactive-concierge-android.md](proactive-concierge-android.md)。
> 前置閱讀：[frontend-handoff.md](frontend-handoff.md)、[t4-delay-trigger-spec.md](t4-delay-trigger-spec.md)。

## 1. 一句話

管家不再等使用者開口：行程進行中（以及出發前一晚、出發當天）在背景做**程式檢查**，發現問題才在頁面頂端跳出提示列，例如「⚠️ 13:00 起降雨 80%，管家有建議」。使用者按「看建議」才走既有的 `/api/agent/replan` → 建議修改卡 → 按「套用」才寫入。**偵測本身不呼叫 AI。**

## 2. 檔案

| 檔案 | 內容 |
|---|---|
| `app/proactive-concierge.js` | 偵測、去重、冷卻、提示列、demo 控制。依賴 planner 的全域函式，`<script src>` 排在 `ai-travel-planner-v8.js` 之後 |
| `app/proactive-concierge.css` | 提示列與 demo 區塊樣式 |
| `app/ai-travel-planner-v8.html` | 只多兩行：上面兩個檔案的 `<link>`／`<script>` |
| `server/agent/precheck.js` | 輕量預檢：跟 replan 同一套程式檢查，只回答「值不值得提醒」 |
| `server/agent/precheck-route.js` | `POST /api/agent/precheck`（限流 60 次／10 分鐘／IP、需登入） |
| `server/server.js` | 只多一行：掛上上面的路由 |
| `server/test/precheck.test.js` | 離線測試（不打 CWA、不呼叫 AI） |

`ai-travel-planner-v8.js` **沒有改動**。

## 3. 偵測規則與門檻

背景每 60 秒檢查一次（展示模擬中每 4 秒，因為虛擬時鐘跑得快）；分頁切回前景時立刻檢查一次。可用 `window.WAI_PROACTIVE_CONFIG` 在載入前覆寫門檻。

| 類型 | 何時檢查 | 規則 | 門檻 |
|---|---|---|---|
| 落後 `lag` | 行程進行中、停在某一站 | 現在（展示模擬用虛擬時鐘）比這站的離開時間晚 ≥ 20 分，**而且**照現在離開，後面的站會撞營業時間或當天結束時間（沿用 planner 的 `assessLeaveImpact`，只算新出現的衝突） | `lagMin: 20` |
| 天氣 `weather` | 進行中、出發當天、出發前一晚 18:00 後 | 還沒去的**戶外**站（`isOutdoor`／`classifyIndoorOutdoor`；排除餐廳、車站、住宿、港口），停留時段與某個預報時段重疊且降雨機率 ≥ 70% | `rainPop: 70` |
| 公休 `closed` | 同上 | 還沒去的站 `getBusinessHoursWarning` 回「當天公休」（與行程卡同一個 business-hours.js 判斷） | — |

- 天氣來源：planner 既有的 `fetchTaitungWeather()`（CWA F-D0047-091 經 `/api/cwa`，30 分鐘快取），跟天氣頁、管家用的是同一份。
- 同時有多個問題時只顯示一個，優先順序：落後 > 天氣 > 公休；顯示中的提示會被更急的取代（被取代的之後還能再提醒）。
- 問題不在了（例如已經改過行程），提示列自動收起。

### 後端確認（天氣、公休）

顯示前先 `POST /api/agent/precheck`（請求格式同 replan：`{ trip, trigger: { type: 'weather' }, scenario? }`），後端用跟 replan 完全相同的 `validate`（降雨 ≥ 50%、公休、營業時間、暫停開放）判斷。後端也說有問題才提醒，避免使用者點開後只得到「程式檢查沒發現問題」。

- 回應：`{ alert, kind: 'weather'|'closed'|'delay', issues: [{ code, stopId, day, stop, message }], headline, simulated, weather }`
- 後端額外排除「只有車站／住宿／港口淋到雨」的情況（不值得打擾使用者）。
- 結果快取 30 分鐘（key 含行程內容簽章）。後端失敗、未登入、沒有代理（file://）、mock 模式 → 以本地判斷為準，不擋提醒。
- `trigger.type: 'delay'` 也支援（App 可用，見 Android 文件）；網頁的落後提醒沿用 planner 本地的衝突判斷，不另外問後端（T4 規格第 1 節第 6 點）。

## 4. 按「看建議」之後

- 天氣／公休：`startAgentRun({ type: 'weather' })`，demo 降雨勾選時會自動帶 `scenario.rain`。
- 落後：先 `applyExpectedLeave(stopId, 現在, { silent: true })`，再 `startDelayAgentRun(stopId)`，跟「預計離開時間」入口與 demo 延誤走同一條路（T4 套用要靠 `expectedLeaveMin` 重算時間）。預計離開只是預測，原訂停留不變，可在行程卡按「照原訂」還原；行程本身仍要在建議卡按「套用」才會改。
- 所有提案照舊：共編鎖、雲端 `updatedAt` 比對、按「套用」才寫入。

## 5. 去重與冷卻

- **同一問題只提醒一次**：每個問題有 key（例如 `rain|2026-11-07|cstop-a,cstop-b`、`lag|cstop-x|hours:cstop-y`、`closed|2026-11-07:cstop-z`），顯示時就記下，之後不再顯示。
- **按「不用」**：除了記下 key，同一類型冷卻 60 分鐘（`cooldownMin`）。
- 存在 `localStorage['wai_proactive_v1']`，依行程 id 分開，最多保留 20 份行程。讀寫都包 try/catch；私密模式只是少了去重。
- **展示模擬與 `?agentMock` 只記在記憶體**：每次進入展示模擬都從乾淨的紀錄開始，不會消耗正式行程的提醒。

## 6. 多人共編：只由擁有者裝置偵測

| 方案 | 結論 |
|---|---|
| Firestore 欄位當鎖（例如 `proactiveClaim`） | 現行 `firestore.rules` 對 editor 的 `micro_trips` 更新有欄位白名單（只放行 `regenLock` 這類既有欄位），新欄位會 `permission-denied`，要先改規則並部署；而且每次背景偵測都要寫一次雲端 |
| **只由擁有者裝置偵測（採用）** | 不需要改規則、不多寫雲端。單人行程照常偵測；共編行程只有 `collabRole === 'owner'` 的裝置做背景偵測，editor／viewer 不偵測（唯讀成員本來就不能調整） |

擁有者套用後，既有的共編即時同步會把新行程推給所有旅伴；即使兩台裝置真的同時送出，套用時的 `regenLock` 與 `updatedAt` 比對也只會讓一份寫入成功。

缺點：擁有者沒開 App 時旅伴收不到主動提醒（旅伴仍可自己按「檢查天氣並調整」）。日後要改成「誰先偵測到誰處理」，可在 `firestore.rules` 的 editor 白名單加一個 `proactiveClaim: { key, by, at }`，用 transaction 搶（寫法比照 `acquireRegenLock`）。

## 7. 展覽現場怎麼重現

網址帶 `?demo=1`（要重播錄製資料再加 `&agentMock=1`）。

**降雨（出發前／行程中都可以）**

1. 開啟一份行程 → 右上「AI 隨行管家」→ 展開「🎛 展示：情境注入」。
2. 勾「模擬：下午大雨」（日期會自動帶行程第一天，時段預設 13:00–17:00、80%）。
3. 按「🔔 主動提醒」區塊的「立即偵測」→ 頁面頂端出現「⚠️ 13:00 起降雨 80%，管家有建議 [模擬]」。
4. 按「看建議」→ 抽屜開啟、跑 Agent（mock 重播 `rain` fixture）→ 建議修改卡 → 套用／放棄。
5. 再按一次「立即偵測」→ 提示「已經提醒過了」（去重）。按「清除提醒紀錄」可重來。

**落後**

1. 開「展示模擬」（行程頁的「展示模擬」按鈕，或 `TravelLinkSimulation.enable()`），按「⏭ 下一站」直到停在某一站。
2. 在 demo 面板設定分鐘數（例如 30），按「模擬落後（主動）」。後面排得下 → 提示「管家不打擾你」；排不下 → 頂端出現「⏱ 比原訂晚 30 分，…，管家有建議」。
3. 按「看建議」→ 走 T4 延誤流程（帶 `scenario.delay`，mock 重播 `delay-retime`／`delay-ai`）。

**完全自動**：展示模擬執行中、勾著模擬降雨時，背景檢查（每 4 秒）會自己跳出降雨提醒；虛擬時鐘一路跑、停在某站超過 20 分且後面排不下時，也會自己跳出落後提醒。展示模擬的工具列多一顆「🔔 管家檢查」，等同「立即偵測」。

除錯：`window.WAI_PROACTIVE.check()`、`.forceCheck()`、`.simulateLag(分)`、`.reset()`、`.current`。`?proactive=0` 整個關掉。
