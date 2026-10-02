# 網頁端 ↔ Android 端 跨端契約與待確認事項

> 對象：Android 端開發者
> 寫作日期：2026-09-24
> 依據：本 repo `main` 分支 `a92fb4b`，以及 2026-09-24 在正式站
> `https://travel-link-ai.duckdns.org/` 用兩個帳號實機驗證的結果
>
> 這份文件只寫「兩端共用、寫錯會壞」的部分。純網頁 UI 的改動不列入。

---

## 0. 三十秒摘要

| 類別 | 內容 |
|---|---|
| 🔴 最優先確認 | App 存共編行程時，是否會覆寫 `memberEmails`（會讓成員被踢出相簿，且無法升為 editor） |
| ✅ 已決定 | 照片刪除權限採方案 B（owner/editor 可協助管理），網頁端已實作（§2.3） |
| 🟠 要決定方向 | 照片是單向相容——App 寫的網頁看得到，網頁寫的 App 看不到（§2.1b） |
| 📋 要對齊的規格 | `micro_trips/{tripId}/photos/{photoId}` 的 18 欄白名單、Storage 路徑與限制 |
| 📦 要索取的檔案 | `scripts/export_explore_templates.mjs`、`scripts/upload_explore_templates.py`（本 repo 沒有） |
| ⚠️ 新增的耦合 | `app/explore-templates.js` ＋ `app/poi-data.js` 現在是雙端共用的資料來源 |

---

## 1. 🔴 最優先：`memberEmails` 被覆寫的問題

### 現象

成員加入共編行程後，只要擁有者「生成團體行程」，該成員就會從 `memberEmails` 陣列中消失。

### 後果（2026-09-24 雙帳號實測）

`memberEmails` 是 Rules 裡 `isTripMemberOrOwner()` 的判斷依據，所以成員一旦掉出這個陣列：

- 共同行程相簿（`photos`）→ `permission-denied`
- 線上狀態（`presence`）→ `permission-denied`
- 旅遊回憶（`memories`）→ `permission-denied`
- 因為 Rules 要求 `editorEmails ⊆ memberEmails`，**把他升成 editor 會永久失敗**

最麻煩的是**表面看不出來**：行程主文件因為 `inviteCode` 不為空，仍然讀得到，所以畫面照常顯示行程，只有相簿是空的。

### 網頁端的根因與修法

`saveMicroTripToFirebase()` 把整包本機 trip 物件展開後 `set(..., { merge: true })` 寫回。
`merge: true` 只保護「沒有出現在 payload 裡」的欄位——本機那份 trip 帶著建立 lobby 當下的
`memberEmails` 快照（裡面只有擁有者），所以這一寫就把後來加入的成員整個蓋掉。

已修正（commit `5577da9`）：寫回前一律剝除下列協作欄位，只寫行程內容。

```
members, memberEmails, memberUids, editorEmails,
ownerEmail, ownerUid, ownerName,
guestReadable, shareToken, inviteCode, maxMembers, collabCreatedAt
```

另外 `userEmail` 也改成只在非共編行程寫入——Rules 的 `isOwner()` 也認 `userEmail`，
原本無條件寫會讓 editor 存檔時把自己升成擁有者等級。

### ❓ 請確認

App 端在更新 `micro_trips/{tripId}` 時，payload 裡是否夾帶了上述任何一個協作欄位？
這些欄位應該只由「加入／退出／改角色」的專用流程去動，一般存檔不可碰。

---

## 2. 照片資料契約

### 2.1 Firestore 文件

路徑：`micro_trips/{tripId}/photos/{photoId}`

Rules 用 `hasOnly` + `hasAll` 鎖死欄位，**多寫一個欄位會讓整筆被拒**（不是忽略該欄位）。

| 欄位 | 必填 | 型別 | 說明 |
|---|:--:|---|---|
| `photoId` | ✅ | string | 必須等於文件 id |
| `tripId` | ✅ | string | 必須等於路徑上的 tripId |
| `stopId` | ✅ | string | 未分類時寫空字串，不要省略 |
| `stopName` | ✅ | string | |
| `dayKey` | ✅ | string | `YYYY-MM-DD` |
| `capturedAt` | ✅ | number | epoch 毫秒 |
| `manualOrder` | ✅ | number \| null | 人工排序用；沒有就寫 `null` |
| `ownerUid` | ✅ | string | 必須等於 `request.auth.uid` |
| `ownerName` | ✅ | string | 顯示用標籤，見 §2.4 |
| `url` | ✅ | string | 非空；Storage download URL |
| `storagePath` | ✅ | string | 見 §2.2 |
| `status` | ✅ | string | 正式資料一律 `synced` |
| `capturedTimezone` | | string | |
| `mimeType` | | string | |
| `hash` | | string | 去重用 |
| `createdAt` | | number | epoch 毫秒 |
| `uploadedAt` | | number | epoch 毫秒 |
| `updatedAt` | | number | epoch 毫秒 |

`status` 的完整列舉（`queued` / `uploading` / `publishing` / `synced` / `failed` / `local-only`）
只存在於用戶端的本機佇列；**寫進 Firestore 的一律是 `synced`**。

### 2.1b 目前是「單向相容」，不是互不相通

網頁端**同時讀兩個來源**並合併：

| 來源 | 路徑 | 網頁 | App |
|---|---|:--:|:--:|
| 新版 | `micro_trips/{tripId}/photos/{photoId}` | 讀 ＋ 寫 | ？ |
| 舊版 | `micro_trips/{tripId}/memories/{uid}` | **讀**（含 onSnapshot 即時訂閱） | 讀 ＋ 寫 |

程式位置：`app/trip-photo-sync.js` 的 `readLegacy()` / `list()` / `subscribe()`，
由 `ai-travel-planner-v8.js` 以 `includeLegacy: true` 啟用。

所以現況是：

- **App 寫到 `memories` 的照片，網頁看得到** ✅
- **網頁寫到 `photos` 的照片，App 看不到**（若 App 只讀 `memories`）❌

建議 Android 改寫新版 `photos`，舊版 `memories` 的讀取相容保留一段時間再移除。

### 2.2 Storage

```
trip-photos/{uid}/{tripId}/{檔名}

建立：僅 uid 本人、檔案 < 5 MB、contentType 必須是 image/jpeg
刪除：僅 uid 本人（owner/editor 刪他人照片時，檔案由 Cloud Function 清除，見 §2.3）
讀取：任何登入者
```

`storagePath` 欄位請填這個完整路徑。

**檔名：已與 Android 端達成共識，統一為 `{photoId}.jpg`**（2026-09-25）

```
trip-photos/{uid}/{tripId}/{photoId}.jpg
```

- `photoId` 永遠不變，且與 Firestore 文件 id 一一對應——看到檔案找得到文件，反過來也一樣
- 景點歸屬一律以文件裡的 `stopId` 欄位為準，**不從路徑反推**
- **舊檔案不改名**：`storagePath` 是逐筆存在文件裡的，舊路徑照樣解析得到

之所以不用 `stopId`：照片可以被重新分類到別的景點（`updateClassification()` 會改 `stopId`
並重新發布），但 Storage 物件不會跟著搬家——檔名裡的 stopId 一旦過期，除錯時比沒有更誤導。

**網頁端已完成**（`uploadTripPhoto()`，`app/ai-travel-planner-v8.js`）。
2026-09-25 正式站實測：新照片路徑
`trip-photos/{uid}/my_1790138720174/6a3716ee-e185-4831-a50b-13cd5cb57962.jpg`，
與其 Firestore 文件 id 完全一致；同一趟行程裡三張舊的時間戳檔名照片仍可正常載入（HTTP 200）。

### 2.3 權限

| 動作 | 條件 |
|---|---|
| read | `isTripMemberOrOwner(tripId)` |
| create | 成員 ＋ `ownerUid == auth.uid` ＋ 欄位合法 |
| update | 成員 ＋（自己的照片 或 行程 owner/editor）＋ `ownerUid`/`tripId` 不可變 |
| delete | 成員 ＋（自己的照片 或 行程 owner/editor） |

**刪除政策：方案 B（owner/editor 可協助管理）**——2026-09-25 兩端共同決定，網頁端已實作。

| 角色 | 刪自己的照片 | 刪他人的照片 |
|---|:--:|:--:|
| 行程 owner | ✅ | ✅ |
| editor | ✅ | ✅ |
| member | ✅ | ❌ |
| viewer | ❌ | ❌ |

舊版 `memories` 併進來的唯讀照片（`readOnly`）一律不提供刪除——它們不在 `photos` 集合裡。

三個層級現在一致：`TripPhotoManager.can('delete')`、`TripPhotoGallery` 的 `defaultCan`、
底層 `TripPhotoSync` 的 `authorize('remove')`，以及 Firestore Rules。
（決定前的狀態是「owner 可以、editor 不行」，是三層各自演化的結果。）

**實作時一併發現的三個問題**（方案 B 讓它們浮現）：

1. **遠端刪不掉**：`remove()` 原本只在 `photo.remote` 存在時才刪遠端，而那個欄位
   只有「這台裝置上傳過」的照片才有。別人傳的、或自己在另一台裝置傳的照片，
   按刪除只清掉本機快取，下次同步又整張回來。改為看 `storagePath` / `url` 判斷遠端身分。
2. **刪除不會傳到其他成員**：`ingestRemote()` 只做新增／更新，從不移除遠端已消失的紀錄，
   於是被刪的照片會永遠留在其他成員的本機快取裡。改為收到完整快照時修剪——
   已同步的才清（這台自己上傳的，同步完成滿 1 分鐘才清，避免被發布前的舊快照誤判），
   待上傳的本機照片一律不動。

實測（2026-09-25，雙帳號）：editor B 刪 owner 的照片 → Firestore 5 → 4 筆；
owner 端重新載入後本機快取也同步變 4 筆，照片從相簿消失。

3. **圖檔變孤兒**：Firestore 的 `photos` 文件允許 owner/editor 刪他人照片，但
   `storage.rules` 只允許本人刪 Storage 檔——於是文件刪得掉、圖檔刪不掉。
   實測 editor 刪除後，那張圖檔仍存在（6140 bytes），**持有舊下載網址的人照樣看得到**。
   **解法（2026-09-27 定案）：Cloud Function `cleanup_deleted_trip_photo`**
   （`photos` 文件刪除時觸發）用管理員權限刪檔，並把同一張從 `memories/{ownerUid}` 移除。
   `storage.rules` 維持「只准本人刪」不變；搭配 `firestore.rules` 的兩條限制
  （建立時 `storagePath` 必須在自己的 `trip-photos/{uid}/` 底下、建立後 `storagePath`／`url`
   不可改），Function 才不會被拿去刪別人的檔案。
   網頁端：刪**自己的**照片仍會直接刪檔；刪**別人的**照片只刪文件，檔案交給 Function
  （照刪只會換來 403）。

4. **旅記／九宮格／回顧短片仍顯示已刪照片**：這三個功能讀的是本機造訪紀錄（`visitedSpots`），
   原本只會從 `memories` 併入、從不移除，Function 清掉 `memories` 也沒用；而且下一次
   `saveMyTripMemory` 會把本機那份再寫回 `memories`（照片「刪了又跑回來」，檔案已刪所以是破圖）。
   已改為：對齊來源改成 `photos` ＋ `memories`（所以也看得到旅伴只存在 `photos` 的照片），
   雲端已沒有的就從本機移除；`saveMyTripMemory` 寫入前先對齊。
   沒有 `photoId` 的舊照片（`photos` 出現前上傳）沒有雲端紀錄可比對，一律保留。
   **不依賴 Function 的補救**：自己上傳的照片只以正式 `photos` 判斷存不存在（`memories` 殘留的網址
   不算），被刪時由上傳者本人開頁時把網址從自己的 `memories` 移除（merge 寫入，不動其他欄位）。
   實測 2026-09-27：Function 沒有動作（刪除 6 分鐘後 Storage 檔案仍在），在這之前照片會以舊版
   `memories` 的身分一直回來；加上補救後，上傳者開頁一次，兩端都乾淨了。
   ⚠️ Storage 檔案仍要靠 Function 刪——網頁端沒有權限刪別人的檔案。

**Android 端請比照**：刪除走 `photos/{photoId}` 文件（自己的照片可一併刪 Storage 物件）；
收到 snapshot 時，本機快取裡已不存在於遠端的照片要一併移除；
若 App 有「每次開啟就從 `memories` 搬移」的流程，要改成只搬一次，否則被刪的照片會被搬回來。

### 2.4 `ownerName` 與撞名

`ownerName` 只是顯示標籤，**身分一律以 `ownerUid` 為準**。

網頁端 2026-09-24 的處理方式（commit `a92fb4b`）：

- 寫入時優先用 App 內設定的暱稱，而不是 Firebase Auth 的 `displayName`
  （後者多半是空的，會退到 email 前綴，畫面上出現一串帳號字串）
- 顯示時才處理撞名：掃過相簿裡所有照片，**只有真的撞到的名字**才加上 uid 尾四碼
  （`小明 #1a2b`），沒撞名的人維持乾淨的名字

這件事不能靠「換一個欄位」解決——改用 email 前綴一樣會撞
（`tim@gmail.com` 與 `tim@nttu.edu.tw` 都會顯示 `tim`）。

### 2.5 分類演算法規格（網頁端現況，供 Android 對齊）

以下全部出自 `app/trip-photo-manager.js`，是網頁端**目前實際跑的**邏輯。

#### (1) `capturedAt` / `dayKey` / `capturedTimezone`

**EXIF 讀取順序**（`parseExif` → `parseTiff`）：

```
1. ExifIFD  0x9003  DateTimeOriginal      ← 優先
2. ExifIFD  0x9004  DateTimeDigitized
3. IFD0     0x0132  DateTime
```

**時區偏移標籤優先**（2026-09-25 兩端共同決定，網頁端已實作）：

日期標籤必須與對應的偏移標籤**配成對**，拿錯配對比沒有偏移更糟（會把時間往錯的方向推）：

| 日期標籤 | 配對的偏移標籤 |
|---|---|
| `0x9003` DateTimeOriginal | `0x9011` OffsetTimeOriginal |
| `0x9004` DateTimeDigitized | `0x9012` OffsetTimeDigitized |
| `0x0132` DateTime（IFD0） | 無，只能用裝置時區 |

**字串轉 epoch**（`exifDateToEpoch(值, 偏移分鐘)`）：

```js
/^(\d{4}):(\d{2}):(\d{2})[ T](\d{2}):(\d{2}):(\d{2})/

有偏移 → Date.UTC(年, 月-1, 日, 時, 分, 秒) - 偏移分鐘 × 60000   // 絕對時間
沒偏移 → new Date(年, 月-1, 日, 時, 分, 秒).getTime()            // 假設同裝置時區
```

偏移字串接受 `+08:00` 與 `+0800` 兩種寫法。

`metadata.timeAssumption` 會標成 `'exif-offset'`（有偏移）或 `'device-local'`（沒有）。

2026-09-25 正式站實測，同一個牆上時間 `2026:09:28 09:32:00`：

| 偏移 | 解出的絕對時間 | timeAssumption |
|---|---|---|
| `+08:00` | `2026-09-28T01:32:00Z` | `exif-offset` |
| `-05:00` | `2026-09-28T14:32:00Z` | `exif-offset` |
| 無 | `2026-09-28T01:32:00Z`（裝置為 Asia/Taipei） | `device-local` |

兩者相差正好 13 小時——**這就是兩端時區解讀不一致時會產生的偏移量**。

**沒有 EXIF 時間的後備**：

```js
capturedAt  = file.lastModified          // 檔案修改時間
timeSource  = 'file-last-modified'
fallbackUsed = true
warnings   += '照片缺少拍攝時間，目前使用檔案修改時間，建議使用者確認。'
```

後備時間會讓分類的時間權重從 0.45 降到 0.2（見下），且通常落到「待整理」。

**`dayKey`**（`dayKeyFor(epoch, offsetMinutes)`）：

```js
offsetMinutes == null  → 用本地時間  YYYY-MM-DD（getFullYear/getMonth/getDate）
offsetMinutes 有值      → new Date(epoch + offset*60000).toISOString().slice(0,10)
```

網頁端目前**不傳 `offsetMinutes`**，所以走本地時間那條。

**`capturedTimezone`**：兩端都填 **IANA 名稱**（例如 `Asia/Taipei`），不是 `+08:00` 偏移。
名稱可以換算出偏移，偏移換不回地區（夏令時、歷史時區變更都還原不了）。

| 端 | 取得方式 |
|---|---|
| 網頁 | `Intl.DateTimeFormat().resolvedOptions().timeZone` |
| Android | `ZoneId.systemDefault().id` |

網頁端 2026-09-25 已實作（`deviceTimeZone()`），實測寫入 Firestore 的值是 `Asia/Taipei`。

#### (2) `stopId` 自動判斷：時間與 GPS **一起**算加權分

不是二選一。`classifyPhoto()` 對每一站算分：

```
預設參數
  radius = 350 公尺
  margin = 90 分鐘

locationScore = max(0, 1 - 距離 / max(radius*3, 1))        // 即 1050 公尺外歸零
timeDistance  = 照片時間落在該站時間窗內 → 0
                早於窗 → (窗開始 - 照片) 分鐘
                晚於窗 → (照片 - 窗結束) 分鐘
timeScore     = max(0, 1 - timeDistance / max(margin, 1))

score = locationScore * 0.55  +  timeScore * (EXIF 有時間 ? 0.45 : 0.20)
```

站的時間窗（`stopWindow`）：`startAt` / `arrivalAt` / `time` 擇一為開始；
`endAt` / `departureAt` 為結束；若只有開始，則 `結束 = 開始 + (durationMinutes 或 stayMinutes，預設 60) 分鐘`。

**候選先以 `dayKey` 過濾**（同一天的站才納入），再依 score 由高到低排序。

**判為「待整理」的條件**（任一成立）：

```
沒有候選  或  最高分 < 0.2  或  （既沒有位置證據也沒有時間證據）
→ stopId: null, group: 'unassigned', confidence: 'low', method: 'pending-review'
```

**信心等級**：

```
時間與位置都有證據 且 score >= 0.72  → 'high'
score >= 0.38                        → 'medium'
其餘                                  → 'low'
```

**`method` 值**：`time-and-location` / `location` / `time` / `file-time-fallback` / `pending-review`

#### (3) `hash`：算在**原始檔**上，不是壓縮後

```js
// enqueue() 第 585 行：入佇列時就算，此時還沒壓縮
var hash = await computeHash(file);

// computeHash()
有 crypto.subtle → 'sha256:' + 十六進位摘要
沒有（舊瀏覽器） → 'weak-fnv1a:' + FNV-1a(8碼) + ':' + 位元組長度
```

壓縮發生在**上傳當下**（`upload` adapter 裡的 `compressImageToJpeg`），晚於 hash 計算。

⚠️ **組員的顧慮完全正確**：兩端壓縮方式不同，但因為 hash 算的是**原始檔**，
理論上同一張原圖在兩端會得到相同的 sha256。不過仍**不建議用它做跨端去重**——
Android 相簿匯出／EXIF 重寫／不同的讀檔路徑都可能讓位元組不同。
網頁端目前也只把它用在 `findDuplicateHints()`——而且那支只掃**本機 IndexedDB 佇列**
（`getAll()`），從不跟遠端照片比對。它的用途是「同一個人在同一台裝置重複選到同一張」的提示，
本來就不是跨端去重機制。跨端去重請用 `storagePath`（見下）。

#### (4) 去重 key：網頁端已經吃 `storagePath`

組員要求「網頁端也請用 `storagePath` 當去重的 key」——**這件事網頁端已經在做了**，
而且是三個 key 一起看（`mergePhotos()`，`trip-photo-sync.js:177`）：

```js
id   = photoId || id
path = storagePath || path
url  = url || downloadUrl
// 三者任一撞到既有紀錄就跳過
```

所以只要 Android 寫入的 `storagePath` 與網頁一致，合併時就不會重複。不需要網頁端改動。

### ❓ 請確認

**已於 2026-09-25 達成共識（網頁端皆已實作）：**

| # | 議題 | 結論 |
|---|---|---|
| 4 | EXIF 時區解讀 | 有 `OffsetTimeOriginal` 優先用它，沒有才用裝置時區。兩端一致 |
| 5 | `capturedTimezone` | 開始填，用 IANA 名稱 |
| 6 | 分類參數 | App 目前不自動分類（使用者自選景點），分類結果在上傳當下就寫進 `stopId`；日後若要自動分類再沿用網頁常數 |
| — | 讀取時是否重算分類 | **網頁端不會重算**。`ingestRemote()` 直接採用遠端 `stopId`，`classifyPhoto()` 只在 `createPhotoRecord()`（上傳當下）被呼叫，相簿只讀不算 |

**仍待確認：**

1. App 寫入的欄位是否**完全吻合**上表 18 欄？有沒有多寫例如 `width` / `height` / `caption`？
2. `ownerName` 目前寫的是什麼來源？
3. App 相簿是否也需要撞名消歧？

### 2.6 舊 `memories` 照片搬移到 `photos` 的注意事項

Android 端回報：舊的 `memories` 裡**只存了網址字串，沒有固定的 `photoId`**，
App 每次讀取都會重新產生一個 UUID。所以搬移時：

- `photoId` 只能**產生一次並寫進 `photos`**，不可每次讀取重新產生
- 搬移前的舊資料**用 `id` 對不上**，只能靠 `storagePath` 或 `url` 去重
- 舊照片的 `storagePath` 與 `url` **原樣保留**，不要重建（Storage 物件不會搬家）

網頁端的 `mergePhotos()` 是三個 key 一起比對（`photoId` / `storagePath` / `url`），
剛好涵蓋這種「id 對不上但路徑相同」的情況，不需要額外處理。

---

## 3. 共編權限即時變更

### 網頁端修正（commit `a35174b`）

擁有者把成員降成唯讀時，對方的角色狀態確實會即時同步，但畫面上的編輯入口
（開始行程／調整順序／重新規劃）原本不會跟著收起來——實測唯讀成員**仍能進入排序模式**，
做完才發現存不回去（Rules 會擋，資料不會壞，但工已經白做）。

已改為：`collabReadOnly` 一翻轉就重繪操作列與站點卡，並在進入排序模式的入口再擋一次。

### ❓ 請確認

App 端在收到角色變更的即時更新時，是否同步收起編輯入口？

---

## 4. `explore_templates`：新的雙端耦合

### 現況

2026-09-24 新增的 Firestore 規則：

```
match /explore_templates/{docId} {
  allow read: if request.auth != null;
  allow write: if false;
}
```

這 9 行**已經部署生效**，線上 `explore_templates/current` 文件已存在，內容驗證如下：

```
version         1
source          https://travel-link-ai.duckdns.org
generatedAt     2026-09-24T02:16:32Z
poiGeneratedAt  2026-09-22T06:45:09Z
templates       16 份
groups          海線3 / 市區2 / 縱谷5 / 南迴4 / 離島2
gate            台東車站 22.7931, 121.1229
homePicks       台東 綠島 蘭嶼 卑南 成功 東河
```

### ⚠️ 這代表什麼

`groups`、`gate`、`homePicks` 與網頁端 `app/explore-templates.js` 裡的
`GROUPS`、`GATE`、`HOME_PICKS` **逐字對應**。也就是說：

> Android 探索頁的範本，現在是由網頁端的 `app/explore-templates.js` ＋ `app/poi-data.js` 產生的。

這在此之前是純網頁功能。往後網頁端動到這兩支檔案（或重跑爬蟲更新 `poi-data.js`），
就會連帶影響 App 的探索頁。這個耦合目前**沒有寫進 CLAUDE.md 或 README**。

### 📦 請提供

規則註解提到的兩支腳本，**本 repo 裡不存在**（連 `scripts/` 目錄都沒有）：

1. `scripts/export_explore_templates.mjs`
2. `scripts/upload_explore_templates.py`

沒有它們，網頁端改了資料也無法更新 App 看到的範本——這條管線目前只有 Android 端跑得動。

### ❓ 請確認

1. App 目前是否真的在讀 `explore_templates/current`？還是規則先放著備用
2. 範本重新產生的觸發時機與負責人
3. 目前線上快照的 `poiGeneratedAt` 是 2026-09-22 14:45（台北），
   而 `app/poi-data.js` 最後一次變更是同日 14:47（commit `43f4160`，−52/+2 行）。
   相差約 2 分鐘，範本可能少了那次改動，請確認是否需要重新產生

---

## 5. 其他待確認

| # | 項目 | 說明 |
|---|---|---|
| 1 | `scenic_points` / `hours_cache` | 目前是 `allow read, write: if request.auth != null`，任一登入者可改可刪。Rules 註解說是為了 Android 相容（評分聚合、營業時間快取）。請提供遷移到後端寫入的時程 |
| 2 | `photos` / `memories` / `recaps` 的實際欄位 | 請提供 App 端寫入的欄位清單，用來對照 Rules 白名單 |
| 3 | `firestore.rules` 版控 | `explore_templates` 那 9 行目前**未進版控**（不在任何 commit 裡），但已部署。請確認以哪一份為準 |

---

## 6. 相關 commit（本 repo `main`）

| commit | 內容 |
|---|---|
| `cc23b11` | 照片離線佇列、自動分類與多人共同相簿（含 `firestore.rules` 的 photos 區塊） |
| `a35174b` | 共編權限即時變更時收起編輯入口 |
| `5577da9` | 存檔不再覆寫 `memberEmails`；`userEmail` 不寫入共編行程 |
| `a92fb4b` | 相簿上傳者標籤：優先用暱稱，撞名時以 uid 尾碼消歧 |

規格來源檔案：

- `firestore.rules` — `micro_trips/{tripId}/photos` 區塊
- `storage.rules` — `trip-photos/{uid}/{tripId}/{file}` 區塊
- `app/trip-photo-sync.js` — `formalWrite()` 即為實際寫入 Firestore 的欄位
- `app/trip-photo-manager.js` — EXIF 解析、自動分類、離線佇列
- `app/explore-templates.js` — `GROUPS` / `GATE` / `buildTemplate()`
