# T4 延誤觸發：API 格式 v1（定案版）

> 2026-10-03。整合 cloud session 的提案與 App 組員的 9 點回覆，事實都對照過程式碼與 Firestore 實際資料。
> 網頁與 App 共用同一支 `POST /api/agent/replan`，回傳的 SSE 事件格式**不變**（見 [frontend-handoff.md](frontend-handoff.md)），兩端的面板與提案卡處理都可以沿用。

## 1. 六個待決定事項（定案）

| # | 問題 | 決定 |
|---|---|---|
| 1 | 延誤怎麼算 | `delayMin` ＝ 目前這站的「預計離開時間」減「原定離開時間」 |
| 2 | 只要順延就能解決 | 回傳**只改時間**的提案（`changes` 只有 `retime`），**不呼叫 AI** |
| 3 | 先通知還是直接呼叫 | 先通知，使用者按下才送出；兩端都不自動呼叫 |
| 4 | App 有沒有對應欄位 | 有落差，見第 4 節的欄位對照與第 5 節的遷移 |
| 5 | 多日行程 | 只處理**當天**；當天終點如果是住宿站，視為固定的收工點 |
| 6 | 網頁要不要接 T4 | 要，兩端行為一致；網頁沿用行程進行中模式現有的衝突判斷 |

## 2. 請求格式

```jsonc
POST /api/agent/replan
Authorization: Bearer <Firebase ID token>
{
  "trigger": {
    "type": "delay",
    "source": "app",                       // "app" | "web"，只用於紀錄
    "day": 1,                              // 處理第幾天（只處理這一天）
    "now": "2026-11-07T14:30:00+08:00",    // 用戶端時間（demo 假時鐘就送假時間），後端不用伺服器時間
    "from": {                              // 目前所在的站（尚未離開）
      "stopId": "cstop-1a2b3c",
      "name": "臺東森林公園",
      "lat": 22.7698, "lng": 121.1608,     // 選填
      "leaveAt": "2026-11-07T15:20:00+08:00",  // 預計離開時間（帶日期與時區）
      "delayMin": 50                       // ＝ leaveAt − 原定離開時間
    },
    "returnTrain": { "departAt": "2026-11-07T18:05:00+08:00", "station": "台東車站" },   // 選填
    "lastFerry":   { "departAt": "2026-11-07T16:30:00+08:00", "harbor": "南寮漁港" },   // 選填
    "appConflicts": [                       // 選填：用戶端自己算出的衝突，後端只記錄、以後端重算為準
      { "kind": "closing", "stopId": "cstop-9x8y7z", "message": "趕不上 17:00 關門" }
    ]
  },
  "trip": {
    "title": "...", "region": "台東", "startDate": "2026-11-07", "endTime": "20:00", "people": 2,
    "updatedAt": 1794050400000,            // Firestore 的 updatedAt 是 Timestamp，送 updatedAt.toMillis()（毫秒），套用前比對用
    "stops": [ /* 見下方，只放「還沒開始的站」＋當天的錨點站 */ ]
  },
  "scenario": { "delay": { "minutes": 50 } }   // 選填，demo 用：忽略 from.leaveAt，固定延誤 50 分鐘
}
```

### `trip.stops[]` 每一站

| 欄位 | 必填 | 說明 |
|---|---|---|
| `id` | ✅ | **Firestore 上的 `collabStopId`**，兩端必須一樣，否則提案套不回去（見第 5 節） |
| `day` | ✅ | 第幾天 |
| `time` | ✅ | **原定計畫**的時間 `HH:MM`，不要自己先順延，順延一律由後端算 |
| `stayMin` | ✅ | 停留分鐘 |
| `name` | ✅ | 站名 |
| `lat`, `lng` | 選填 | App 舊行程或手動新增的站可能沒有，後端要能處理 |
| `stopType` | 建議 | `scenic` \| `food` \| `transit`。舊欄位 `kind: 'food'` 仍然接受 |
| `anchor` | 錨點站必填 | `station`（出發／回程車站）\| `lodging`（住宿）\| `ferry`（船班港口）。**一律固定**：不刪除、不移動、不改時間 |
| `businessHours` | 建議 | 營業時間**原文**。後端優先用這個，沒有才查本地資料，兩端判斷公休的依據才會一樣（也避開同名抓錯） |
| `timeLocked` | 選填 | 使用者手動改過時間＝`true`。對應 Firestore 的 **`manualStartMin` 不是 null** |
| `keepReason` | 選填 | 訂位、預約等固定時間的站，例如 `"已訂位 18:00"`。有這欄就不會被刪除或挪動 |

> ⚠️ 欄位名稱：組員提議用 `kind: 'station'|'lodging'|'ferry'` 表示錨點，但目前 API 的 `kind` 已經用來區分 `food`／`scenic`，所以錨點改用 **`anchor`** 欄位，`stopType` 表示景點類型，兩者分開。

## 3. 後端處理順序

1. **程式先檢查（不呼叫 AI）**：從 `from.leaveAt` 加上到下一站的車程開始，把當天剩下的站順延，再跑既有的驗證：營業時間、車程、每天結束時間，以及新增的 **`returnTrain.departAt`、`lastFerry.departAt` 期限**（趕到車站或港口的時間必須早於期限）。
2. **全部排得下** → 回傳只改時間的提案（`llmSkipped: true`）。
3. **有站來不及** → 交給 gpt-oss-120b 處理，可用的方法限定為：刪站、縮短停留、調換順序。錨點站、`keepReason` 站、`timeLocked` 站都不能動。
4. 新增的站：提案裡要附上 `businessHours`、`stopType`、`lat`、`lng`、`desc`、`emoji`，用戶端套用後可以直接顯示與導航，不用再查一次 Google。
5. 用戶端送來的 `appConflicts` 與後端重算的結果都寫進紀錄，規則有落差時比較容易發現。

回傳的事件和現在一樣（`start`／`check`／`tool_call`／`tool_result`／`proposal`／`question`／`no_change`／`error`）。`proposal.changes[]` 會出現 `retime`（`from`/`to` 是時間）、`remove`、`move`。

## 4. 欄位對照（實際查證結果）

| 需要的資訊 | 網頁（Firestore） | App 現況 | v1 怎麼做 |
|---|---|---|---|
| 站的 id | `collabStopId`（planner 存檔會寫；explore 生成已修正） | 只有 `stopId`，沒有就用 `web_{order}_{站名}` | 見第 5 節：App 建立、讀取、存檔都要處理 |
| 手動鎖定時間 | **`manualStartMin` / `manualEndMin`**（從 0 點起算的分鐘，`null`＝沒鎖） | 沒有 | App 讀進來時保留這兩欄、送回時原樣寫回；送 API 時換算成 `timeLocked: manualStartMin != null` |
| 座標 | `lat`/`lng`（少數站缺） | 部分舊站是空的 | 後端處理缺座標的站 |
| 營業時間 | `businessHours` 原文 | 有 | 兩端都送原文 |
| 錨點 | 用站名與 `ferry-config.js` 推斷 | 有三種錨點 | 兩端都送 `anchor` |

## 5. 每一站都必須有 `collabStopId`（單人、共編都一樣）

`collabStopId` 是站的身分證：共編的三方合併（`mergeCollabStops`）、地圖 pin、T4 套用提案都用它對應站點。名字有 collab，但**單人行程也要有**：單人轉共編時（`collab.js` 開共編、後端核准加入申請）都**不會**改寫 stops，所以必須在**建立行程的當下**就寫進去。

**2026-10-03 查 Firestore（唯讀）**：251 份行程有 202 份缺這欄。逐一追查寫入來源：

| 存檔流程 | 會寫 `collabStopId` 嗎 | 證據 |
|---|---|---|
| 網頁 explore：生成後第一次存檔 | ❌ → **已修正**（`assignCollabStopIds`，`?v=20261003-stopid1`） | 56 份網頁生成、之後沒再編輯的行程全部缺 |
| **App 建立／編輯行程** | ❌ **待 App 修正** | 91 份帶 App 的 `stopId`、沒有 `collabStopId` |
| 網頁 planner 編輯後存檔（`serializeStopForPersistence`） | ✅ | 缺 id 的網頁行程最後存檔都在 2026-07（planner 7/15 起才寫），8 月之後沒有 |
| 共編流程（`collab.js`、後端 join-requests） | 不寫 stops | — |

**網頁（已完成）**：生成後、存本機與 Firestore 前，每一站給 `collabStopId`，演算法與 planner 的 `getStableCollabStopId` 完全一致；既有的 `collabStopId`／`stopId` 一律沿用、不覆蓋。

**App 要做的（v1 必要）**：
1. **建立行程**時就幫每一站寫入 `collabStopId`（建議 UUID，或沿用自己的 `stopId` 值）。
2. **讀取**優先用 `collabStopId`，沒有才用 `stopId`；**不要**再用 `web_{order}_{站名}` 現算。
3. **存檔**時把 `collabStopId` 原樣寫回，絕對不能丟掉；新增的站自己產生新的 id。

**舊資料**：不刪除、不做大量遷移。送出 T4 請求前，用戶端發現有站缺 id 就先用 transaction 補寫進 Firestore 再送；後端收到缺 `id` 的站會回 400，不會產生套不回去的提案。

## 6. 套用提案（兩端同一套）

1. 取共編鎖：`micro_trips/{tripId}.regenLock = { by: <email>, byName, at: serverTimestamp() }`，**要用 transaction**：別人持有、而且還沒超過 **5 分鐘**就放棄，否則上鎖。（網頁實作在 `app/collab.js` 的 `acquireRegenLock`／`releaseRegenLock`）
2. 比對 `updatedAt`（Firestore Timestamp，用 `toMillis()` 比）：如果跟送出請求時的 `trip.updatedAt` 不一樣，代表行程在這段期間被改過，**拒絕套用**，請使用者重新檢查。
3. 只改提案涉及的站（用 `id` 對應），已經走過的站、錨點站都不動。
4. 寫入完成後，刪除 `regenLock` 欄位。

## 7. 限流與時間

- `/api/agent/replan` 目前是**每個 IP 10 分鐘 15 次**。T4 一定要使用者按下才送出，第一版夠用；手機網路常常很多人共用同一個 IP，之後改成依帳號（uid）限流。
- 所有時間都帶日期與 `+08:00`；`now` 一律用用戶端送來的值，demo 模式送假時鐘的時間。

## 8. 範圍

**v1（這次做）**：第 2 節的必填欄位、`anchor`、`businessHours`、`returnTrain`／`lastFerry`、只改時間的提案、`scenario.delay`、第 5 節的 id 規則（網頁已完成、App 待做）、第 6 節的套用流程。

**v2（之後）**：`appConflicts` 的比對報表、依帳號限流、多日行程跨天順延。
