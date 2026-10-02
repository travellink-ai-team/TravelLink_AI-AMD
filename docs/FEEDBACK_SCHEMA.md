# 使用者回饋系統 — Firestore Schema（Android / 網頁端對齊用）

> **任務**：B1 使用者回饋系統、B2 深度連結  
> **狀態**：網頁端已實作（2026 Week 1）。Android 端請依此 schema 對齊。  
> **原則**：回饋放在 `micro_trips/{tripId}/feedback/{emailKey}` 子集合，每位成員只寫自己的文件，避免共編成員互相覆寫或藉由行程文件更新他人回饋。

> **2026-09-21 隱私決策**：Web 子集合及 Android 使用的頂層 `feedback` 原文均只供專案管理者查看。本輪 Rules 設為客戶端不可讀，管理者暫用 Firebase Console／受信任 Admin SDK；管理頁日後補齊。Web 已移除「大家的回饋」讀取。Android 舊版的個人化提示查詢需停用或改讀去識別統計，才能搭配新 Rules 發布；不要恢復一般登入者可讀以消除錯誤。

---

## 一、資料位置

```
micro_trips/{tripId}
  ├─ ...（既有欄位：stops, wizardData, members, inviteCode ...）
  └─ feedback/{emailKey}              // 每位成員一份文件
       └─ <FeedbackEntry>
```

- **emailKey 規則**（與 `collab.js` 的 `emailKey()` 完全一致，兩端必須相同）：
  ```
  emailKey(email) = email.toLowerCase().replace(/[^a-z0-9]/g, '_')
  ```
  例：`Tim.Lin@Gmail.com` → `tim_lin_gmail_com`

- 每位使用者對同一趟行程只有一筆回饋（重複提交 = 覆蓋自己的那筆）。

---

## 二、FeedbackEntry 欄位定義

| 欄位 | 型別 | 必填 | 說明 |
|------|------|:----:|------|
| `email` | string | ✅ | 回饋者 email（原始大小寫） |
| `name` | string | ✅ | 顯示名稱 |
| `tripRating` | number (1–5) | ✅ | **行程整體評分**（星等） |
| `aiAccuracy` | number (1–5) | ✅ | **AI 準確度回饋**：AI 生成的行程是否合理、符合需求（1=很不準，5=非常準） |
| `comment` | string | ⬜ | 選填文字意見（上限建議 500 字） |
| `visitedCount` | number | ✅ | 此趟「已到訪」景點數（快照，寫入當下計算） |
| `totalStops` | number | ✅ | 此趟總景點數（不含 start/end 節點） |
| `submittedAt` | number | ✅ | 提交時間，`Date.now()` 毫秒時間戳 |
| `appPlatform` | string | ✅ | 來源平台：`'web'` \| `'android'` |

### JSON 範例

```json
{
  "email": "tim.lin@gmail.com",
  "name": "林泓廷",
  "tripRating": 5,
  "aiAccuracy": 4,
  "comment": "路線很順，但第二站營業時間有誤差",
  "visitedCount": 5,
  "totalStops": 6,
  "submittedAt": 1782000000000,
  "appPlatform": "web"
}
```

文件路徑：`micro_trips/{tripId}/feedback/tim_lin_gmail_com`

---

## 三、寫入方式

```js
await db.collection('micro_trips').doc(tripId)
  .collection('feedback').doc(emailKey)
  .set(feedbackEntry, { merge: true });
```

每位使用者只能寫入自己的 email 文件；Firestore Rules 會再次驗證文件內的 `email`。

> **歷史相容**：舊版曾將回饋寫成 `micro_trips/{tripId}` 文件上的 `feedback` map（key = emailKey）。新版本不再寫入該 map。若線上仍有舊 map，需另行盤點、遷移與清理；子集合 Rules 不會保護主文件內的舊 map。本輪未操作線上歷史資料。

---

## 四、每景點到訪標記（既有，兩端沿用）

「每景點到訪標記」在網頁端**已存在**，資料獨立於 feedback：

```
users/{uid}
  └─ visitedSpots: [                 // arrayUnion / arrayRemove 維護
        { name, region, visitDate, tripId, tripTitle, emoji }
     ]
```

- 網頁端 localStorage 對應 key：`wai_visited_places`
- 切換函式：`toggleVisitedPlace(stop)` / `isPlaceVisited(name)`
- feedback 內的 `visitedCount` 即由此清單對「本趟景點」比對計算。

---

## 五、本地快取（網頁端，離線 / 個人行程用）

網頁端另存一份 localStorage 供離線預填與個人（非 collab）行程：

```
localStorage["wai_trip_feedback:<Firebase UID 或 guest>"] = {
  "<tripId>": { tripRating, aiAccuracy, comment, submittedAt }
}
```

Android 端可用等效本地儲存（SharedPreferences / Room），欄位對齊即可。

舊版共用 `wai_trip_feedback` 不再讀取或自動搬移，因為無法證明草稿屬於目前帳號。已登入者的本機草稿按 UID 隔離；這不是雲端回饋讀取權限。

---

## 六、B2 深度連結 / URL 格式（兩端對齊）

| 情境 | URL 格式 | 說明 |
|------|---------|------|
| 開啟自己的行程 | `ai-travel-planner-v8.html?id=<tripId>` | 同帳號跨裝置 |
| 唯讀分享（訪客） | `ai-travel-planner-v8.html?sharedId=<tripId>&token=<shareToken>&guest=1` | collab 行程，訪客免登入唯讀 |
| 邀請碼加入 | 邀請碼字串（8 碼 base32，格式 `AB3D-7K9P`） | 由 `WAI_COLLAB.joinByCode()` 處理 |

- `shareToken`：20 碼 base32，由 `WAI_COLLAB.generateShareToken()` 產生。
- Android 深連結（App Link）建議 host/path 與上述 query 參數對應，或以 custom scheme `travellink://trip?id=...` 承接後轉內部路由。

---

## 七、待兩端確認的協商點（週末對齊）

1. `aiAccuracy` 採 **1–5 星**（本 schema）還是三段式（good/ok/bad）？→ 目前定為 **1–5**，Android 若偏好三段式需雙方改。
2. Web 已採行程子集合，Android 頂層 `feedback` 的不同 schema 仍相容；不是主文件 map。未來管理頁需整合兩種格式。
3. viewer 仍可提交自己的回饋；回饋原文客戶端一律不能讀。管理者權限及管理頁另案實作。
