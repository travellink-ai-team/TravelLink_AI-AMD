# TravelLink AI 旅程故事核心計畫

> 狀態：已收斂，待分階段實作  
> 日期：2026-08-05  
> 核心輸出：Instagram 主頁九宮格＋旅程回顧短片  
> 核心原則：只用真實旅程素材，不生成未實際發生的旅行畫面。

---

## 1. 產品範圍

### 1.1 只做兩種輸出

#### A. Instagram 主頁九宮格

- 一張 3240×4320 的旅程主視覺，切成九張 1080×1440、3:4 的獨立 JPEG 貼文。
- 九張依指定的反向順序發布後，在 Instagram 個人檔案組成 3×3 大圖。
- 不是單篇貼文裡的九格拼貼，也不是限時動態格式。
- 使用真實照片、路線、日期、景點與旅程數據設計完整大圖。

#### B. 旅程回顧短片

- 以使用者照片、短影片、路線、日期、景點與旅程數據組成。
- 建議長度 10～30 秒，不製作 1～2 分鐘 Vlog。
- 可使用固定動畫轉場、字幕與影片現場原音；網站與 App 不提供或內嵌音樂。
- AI 若加入，只負責選片、排序、字幕或旁白建議，不生成虛構旅行場景。

### 1.2 明確不做

- AI 創意短劇。
- AI 生成真人、同行者、景點現場或未發生事件。
- 手機多軌專業剪輯器。
- 單篇貼文內含九格的 1080×1080 拼貼圖。
- 第一版的 60～120 秒長影片。

AI 短劇舊方案已停止規劃，詳見 [封存附錄](TRAVEL_STORY_AI_DRAMA_APPENDIX.md)。

### 1.3 使用者入口

旅記頁提供單一入口「製作旅程回憶」，進入後選擇：

```text
製作旅程回憶
├─ IG 個人檔案大圖
└─ 旅程回顧短片
```

兩個按鈕標籤必須保持單行，使用 `white-space: nowrap` 與 `flex-shrink: 0`；說明文字使用 `text-wrap: pretty`。

---

## 2. Phase -1：先驗證成品是否值得分享

在後端與轉碼開工前，用 3～5 個工作天在 Web 做固定素材原型：

- 固定一條台東路線與授權測試照片／短片。
- 產出一套九張個人檔案大圖貼文與一支約 20 秒回顧短片。
- 不登入、不上傳、不寫 Firebase，也不接影片生成模型。
- Web 與 Android 目標使用者觀看同一份成品，不在 App 重做原型。

測試 10～15 位目標使用者，通過門檻：

- 至少 70% 對「值得保存或分享」評分達 4／5。
- 至少 50% 在沒有提示下主動選擇下載或分享。
- 至少 70% 願意直接接受推薦版，不要求進階編輯。
- 九宮格與短片至少有一項達標；若只有一項達標，優先只做該輸出。

兩輪視覺調整後仍未達門檻，就停止後續媒體管線開發。

---

## 3. 跨端資料前置條件

### 3.1 穩定 stopId

- Web、Android 與 `local_{roomId}` 建立景點時，立即產生不可變 `stopId`。
- 改名、換日、排序與重新規劃必須保留同一 ID；真正新增景點才建立新 ID。
- 本地行程同步成 `micro_trips` 時沿用原 ID，不重新產生。
- 舊行程不得繼續用 `order + name` 當長期 ID。

舊資料 migration：

- 建立 `legacyStopKey → stopId` alias map。
- 以可重試 migration job 搬移 `micro_trips/{tripId}/memories/{uid}.spots` 的 key。
- 所有成員文件完成前保留新舊 key，並標記 `stopIdMigrationStatus`。
- migration 未完成時可以檢視，但不能輸出九宮格或回顧短片。

### 3.2 照片與影片並存，不做第三次照片搬家

| 資料 | 第一版處理方式 |
|---|---|
| `users/{uid}/memories/{tripId}` | 舊資料唯讀相容，保留既有 migration |
| `micro_trips/{tripId}/memories/{uid}` → `spots.{stopId}.photos[]` | 照片權威資料；Web 補上讀取與顯示 |
| `micro_trips/{tripId}/media/{mediaId}` | 只存影片、正規化檔、封面與輸出 |

- 新照片第一次寫入時產生不可變 `photoId`。
- 舊照片缺少 `photoId` 時由受控 migration 補 UUID。
- 不使用陣列索引、檔名或 URL 當照片長期 ID。
- Web 與 Android 使用同一個 `MediaAsset` adapter 統一讀取，不立即搬移所有照片。

```js
// 給編輯器與 renderer 使用的統一引用
{ kind: "memory_photo", stopId, photoId }
{ kind: "media", mediaId }
```

### 3.3 影片 media schema

```text
micro_trips/{tripId}/media/{mediaId}
```

```js
{
  schemaVersion,
  ownerUid,
  uploadedByUid,
  type: "video",
  originalPath,
  normalizedPath,
  posterPath,
  capturedAt,
  capturedTimezone,
  timeConfidence,
  timeRangeFlag: "in_range" | "out_of_range" | "confirmed_by_user",
  matchedStopId,
  matchMethod,
  durationMs,
  trimStartMs,
  trimEndMs,
  width,
  height,
  rotation,
  codec,
  isHdr,
  frameRate,
  isVariableFrameRate,
  audioCodec,
  hasAudio,
  contentHash,
  status: "uploading" | "quarantined" | "processing" | "ready" | "failed" | "deleted",
  errorCode,
  createdAt,
  deletedAt,
  retentionUntil
}
```

---

## 4. 素材上傳與正規化

### 4.1 使用者流程

```text
＋ 新增旅程素材
├─ 上傳照片
├─ 上傳影片
└─ 現在錄一段
```

影片確認頁提供：預覽、所屬景點、3～15 秒裁切、保留原音／靜音、封面、格式與大小，以及可暫停／取消的上傳進度。

第一版限制：

- 單段使用長度 3～15 秒。
- 每趟最多使用 5～8 段影片。
- 單檔上限暫定 80 MB。
- 正式影片統一為 H.264、YUV420p、30fps CFR、直式 MP4；保留現場聲時使用 AAC，靜音匯出可省略音軌。

### 4.2 HEVC／HDR 是主要路徑

- iPhone、Pixel、三星、小米等裝置都可能產生 HEVC、HLG HDR、Dolby Vision 或旋轉 metadata。
- Phase 1 容量與成本以相當比例影片需要後端轉 H.264 SDR 為基準。
- 裝置只有在實際 capability 檢查通過時才走本機快速路徑。
- 後端檢查 magic bytes、容器、codec、時長、解析度、方向、色彩與音畫同步。

### 4.3 Web 與 Android 離線

**Web：**

- 斷線時保存待上傳清單；瀏覽器允許時將 Blob 暫存 IndexedDB。
- 再次開啟網站且恢復網路後提示續傳。
- 不承諾關閉瀏覽器後仍能背景上傳大型影片。

**Android：**

- 離線選取先建立 local draft，不先占用 media ID。
- 素材複製到 App 管理的持久暫存區，Room 保存路徑、裁切、stopId、冪等 session ID 與狀態。
- 網路恢復後才建立 `uploading` media 文件並取得 Firebase upload session URI。
- 使用 WorkManager 處理一般上傳；Android 14 以上可評估 user-initiated data transfer job。
- 支援僅 Wi-Fi、進度通知、取消、process restart 與 session 續傳。
- 複製前以 `StatFs` 檢查空間，限制暫存總量並提供清理入口。
- 上傳完成且後端確認後才刪除本機暫存。

### 4.4 冪等與孤兒清理

- local draft 使用固定冪等 session ID；網路重送不得建立重複 media。
- `uploading` 在最後一次伺服器觀察到進度後 8 天仍未完成才視為孤兒。
- 本機草稿可保存更久；media／session 過期時以原冪等 session ID 重新申請。
- `contentHash` 只做完整性與重複提示，不合併或刪除不同 media object。

### 4.5 Rules

- Storage object 路徑：`trips/{tripId}/media/{mediaId}/{original|normalized|poster}`。
- 客戶端 create 必須滿足 `resource == null`，且 Firestore media 文件存在、`status == "uploading"`、`uploadedByUid == request.auth.uid`。
- 已 finalize object 不允許客戶端 update；正規化檔與封面只允許後端寫入。
- Firestore media 文件由後端建立；客戶端只可更新裁切、封面、手動配對、確認時間範圍與軟刪白名單欄位。
- Phase 0 使用 Emulator 與實際 resumable upload 測量 Storage Rules 的 Firestore lookup 次數、延遲與成本。

---

## 5. Instagram 主頁九宮格

### 5.1 輸出格式

Instagram 個人檔案目前以直式 3:4 縮圖為主要格線，因此第一版使用：

- 主視覺：3240×4320、3:4。
- 切片：九張 1080×1440、3:4 JPEG。
- 排列：3 欄×3 列，拼成一張連續的大圖。
- 格式版本：`ig_profile_grid_3x4_v1`，避免 Instagram 日後改版時無法區分舊輸出。

Instagram 可能調整縮圖比例、裁切或個人檔案排列；輸出前必須顯示目前模板的 3:4 格線預覽，不能宣稱永久相容。

每次匯出保存 manifest，讓 Web／Android 使用相同切片與發布順序：

```js
{
  schemaVersion,
  formatVersion: "ig_profile_grid_3x4_v1",
  masterWidth: 3240,
  masterHeight: 4320,
  tileWidth: 1080,
  tileHeight: 1440,
  tiles: [
    { row, col, publishOrder, fileName, checksum }
  ]
}
```

### 5.2 主視覺設計

- 使用真實照片、路線、日期、景點名稱與旅程數據組成一張完整旅程海報。
- 預設依日期與景點分散選片，使用者可替換照片及調整裁切焦點。
- 照片不足時以路線、標題、日期與旅程數據補畫面，不生成假照片。
- 重要臉孔、景點主體與文字不可跨越九宮格切線；避免 Instagram 格線間距把內容切斷。
- 每張切片單獨打開時仍應有基本可讀性，不讓其中一張只剩無意義的小角落。
- 景點短標籤保持單行，長名稱使用省略號；按鈕與發布步驟標籤不可拆行。

### 5.3 發布順序

Instagram 個人檔案以最新貼文排在左上，因此九張必須由右下角開始反向發布：

| 發布次序 | 最終位置 | 檔名 |
|---:|---|---|
| 1 | 右下 `r3c3` | `01_r3c3_post-first.jpg` |
| 2 | 下中 `r3c2` | `02_r3c2.jpg` |
| 3 | 左下 `r3c1` | `03_r3c1.jpg` |
| 4 | 右中 `r2c3` | `04_r2c3.jpg` |
| 5 | 正中 `r2c2` | `05_r2c2.jpg` |
| 6 | 左中 `r2c1` | `06_r2c1.jpg` |
| 7 | 右上 `r1c3` | `07_r1c3.jpg` |
| 8 | 上中 `r1c2` | `08_r1c2.jpg` |
| 9 | 左上 `r1c1` | `09_r1c1_post-last.jpg` |

發布前提示：

- 發布九張期間不要穿插其他貼文或 Reels，也先確認置頂貼文／手動重排不會占用格線位置。
- 發布完成後新增 1～2 篇貼文會暫時打亂大圖欄位；新增滿 3 篇後才會重新對齊成完整三欄。
- MVP 不自動登入或代替使用者發布 Instagram；由產品提供順序引導，使用者自行確認每張已發布。

### 5.4 Web

現有 `exportTripCollage()`／「匯出拼貼」改造成個人檔案大圖工具，不保留舊的單張九格輸出：

- 編輯 3240×4320 主視覺，再精確切成九張 1080×1440 JPEG。
- 按鈕改為「製作 IG 九宮格」；完成後使用「下載 9 張貼文」。
- 下載包含九張依發布順序命名的圖片、3×3 預覽圖與發布說明；優先包成 ZIP，不支援時逐張下載。
- 使用 Firebase Storage SDK `getBlob()` 依登入身分取得私有照片。
- Storage CORS 只允許正式站與核准測試來源的 `GET`。
- 以 `createImageBitmap()`／object URL 繪入 Canvas，不依賴永久 download token。
- 單張照片失敗時指出素材，不只顯示籠統的 CORS 錯誤。

### 5.5 Android

- 純本地與雲端行程都能在裝置上產生主視覺與九張切片。
- API 29 以上使用 `MediaStore.Images`＋`IS_PENDING` 保存九張圖片。
- API 28 以下先取得必要儲存權限；拒絕時退回 App 私有目錄＋FileProvider 分享，並提示尚未存入相簿。
- 提供「第 1 張／共 9 張」引導；每次只分享當前一張，使用者返回 App 並確認後才進下一張。
- 不一次把九張送進同一個 Sharesheet，避免 Instagram 把它們當成單篇輪播貼文。

### 5.6 量測與限制

- 記錄主視覺完成、九張成功輸出、發布引導啟動與使用者確認到第幾張。
- 無法可靠確認 Instagram 是否真的發布，因此「使用者確認已發布」只能算自我回報。
- 不強制加永久浮水印；可在不跨切線的位置保留低干擾 TravelLink AI 品牌簽名。

---

## 6. 旅程回顧短片

### 6.1 自適應片長

| 真實照片／影片數量 | 建議片長 | 內容方向 |
|---|---:|---|
| 0 | 10～12 秒 | 路線＋時間軸＋旅程數據 |
| 1～2 | 12～15 秒 | 路線為主，加入真實素材 |
| 3～5 | 15～20 秒 | 路線與真實素材平衡 |
| 6 以上 | 20～30 秒 | 真實素材為主，路線作串接 |

素材不足時縮短影片，不用 AI 假畫面補長度。

### 6.2 視覺結構

- 地圖鏡頭沿路線移動，到站後自然轉入照片／影片。
- 顯示日期、景點、車程與旅程統計，不做投影片式上一頁／下一頁。
- 橫式素材用模糊背景或品牌底圖填滿直式畫布。
- 轉場使用固定程式／向量動畫，不呼叫影片生成模型。
- 原音有敘事價值時保留，跨片段做淡入淡出與音量一致化；不混入其他音樂。

### 6.3 Timeline

```js
{
  schemaVersion,
  locale: "zh-TW",
  revision,
  clips: [
    { clipId, order, type: "route", fromStopId, toStopId, durationMs },
    { clipId, order, type: "photo", assetRef: { kind: "memory_photo", stopId, photoId }, durationMs },
    { clipId, order, type: "user_video", assetRef: { kind: "media", mediaId }, trimStartMs, trimEndMs },
    { clipId, order, type: "timeline", day, durationMs },
    { clipId, order, type: "outro", stats, durationMs }
  ]
}
```

- 影片片長由 `trimEndMs - trimStartMs` 衍生。
- 絕對 start／end 只在建立不可變 compiled manifest 時推導。
- timeline 儲存使用 revision transaction；衝突時不靜默覆寫。
- schemaVersion 必須有 migration；舊 timeline 仍能用原 compiled manifest 播放。

### 6.4 手機剪輯器

MVP 採單欄片段卡片，不做多軌時間軸：

- 預設「採用推薦版本」。
- 進階編輯提供排序、裁切、靜音／原音、字幕、刪除與一步復原。
- 顯示總片長，修改後更新預覽。
- 提供「還原推薦版本」與離開前未保存提示。
- 按鈕標籤保持單行，375px 寬不可拆行或互相擠壓。

### 6.5 音訊

- 網站與 App 不提供曲庫、不接受音樂上傳，也不在成片內嵌音樂。
- 使用者只能選擇「保留現場聲」或「靜音匯出」。
- 保留現場聲時做音量上限、跨片段一致化與淡入淡出。
- 成片匯出後，使用者可自行在 Instagram、CapCut 或其他平台依其授權規則選擇音樂。
- TravelLink AI 不購買、管理或承諾任何外部平台音樂授權，也不處理 Content ID 申訴。

### 6.6 預覽與正式輸出

- 雲端行程的正式 MP4 在 Web 與 Android 一律由共用後端 renderer 輸出。
- WebCodecs／Mediabunny 與 Media3 只負責低解析度預覽、裁切確認或純本地簡化輸出。
- 後端依 compiled manifest 產生 route clip，再組裝照片、影片、字幕與音訊。
- 正式與預覽固定 30fps CFR；片段邊界容差為一個 frame，約 33ms。
- 順序、字幕、裁切與總片長必須一致；色彩、blur 與抗鋸齒可有小幅 renderer 差異。

### 6.7 Android 本地行程

- `local_{roomId}` 可直接輸出九宮格。
- 完整路線回顧短片需要後端 renderer；使用者必須明確同意先同步成 `micro_trips`。
- 不得因按下預覽或匯出就在背景偷偷建立雲端行程。

---

## 7. 後端工作與跨端介面

### 7.1 只保留兩種 job

```text
media_jobs   驗證、正規化、封面
render_jobs  回顧短片正式輸出
```

不建立 AI 影片生成 job、分鏡 job、scene job 或付費生成流程。

### 7.2 操作語意

```text
createRenderJob
getRenderJob
listRenderJobs
cancelRenderJob
retryRenderJob
getMediaJob
```

- Android 優先使用 Firebase callable，Web 可用 callable 或 REST。
- 兩種傳輸必須呼叫同一 service layer，共用 token 驗證、角色權限、錯誤碼與 job schema。
- 建立與重試都使用 idempotency key；相同請求回原 job，不重複輸出。
- owner／editor 可輸出；viewer 只能查看 owner 已分享的結果。

### 7.3 狀態

```text
queued → processing → assembling → ready
                          ├→ failed
                          └→ cancelled
```

- worker 使用 lease、heartbeat、重試上限與 dead-letter。
- job 固定引用的 assetRef 與 timeline revision。
- 生成中刪除素材時先標記待刪，job 結束後再清理。
- 回站後由工作清單還原進度，不只依賴頁面記憶體。

---

## 8. 隱私、刪除與分享

- 原始照片與影片預設私有，只讓 owner、editor 與後端讀取。
- 公開分享只暴露成片，不暴露原始素材或完整私人行程。
- 分享頁以可撤銷 share ID 換取短效簽章 URL，不使用永久 Storage token。
- 一般誤刪保留 30 天可復原；隱私刪除與刪除帳號直接排入不可逆清理。
- 行程刪除需級聯清理 media、render job、成片、分享 token 與快取。
- 公開前提示檢查同行者、路人、車牌、住家與兒童畫面。

---

## 9. 失敗與降級

| 問題 | 使用者結果 |
|---|---|
| 主視覺照片不足 | 用路線、日期與旅程數據補畫面，不生成假照片 |
| 九張切片任一輸出失敗 | 保留主視覺草稿，指出失敗序號並只重試該張 |
| 個人檔案格線不是目前 3:4 模板 | 阻擋直接發布，提示 Instagram 格線可能已改版並保留原始主視覺 |
| 發布期間穿插其他貼文 | 暫停引導，重新預覽目前格線順序後再繼續 |
| 無照片／影片 | 只產生較短的路線回顧片；不生成假素材 |
| HEVC／HDR 不支援 | 排入後端正規化 |
| 上傳中斷 | 保留 local draft 並續傳 |
| Android 空間不足 | 顯示佔用與清理入口，不靜默失敗 |
| 推薦剪輯不好看 | 重新套用另一個固定節奏或進入片段卡片編輯 |
| 使用者不想編輯 | 一鍵採用推薦版本 |
| 正式輸出失敗 | 保留 timeline，允許安全重試，不要求重新剪輯 |
| 純本地行程要求短片 | 說明需要同步，取得同意後才建立雲端行程 |

---

## 10. 實作階段

### Phase -1：視覺驗證

- Web 固定素材 3×3 個人檔案大圖預覽、九張切片與 20 秒短片。
- 10～15 位使用者測試，依第 2 節門檻決定是否繼續。

### Phase 0：跨端資料解鎖

- Web／Android／本地行程穩定 stopId。
- stopId alias、memories re-key、photoId migration 與輸出閘門。
- Web 讀取新的 memories 路徑。
- Storage／Firestore Rules、CORS、Emulator 與 lookup 成本實測。
- 最小 media job 與一段影片垂直切片。

### Phase 1：Instagram 九宮格

- 升級 Web `exportTripCollage()`。
- 3240×4320 主視覺、裁切焦點與九張 1080×1440 輸出。
- Web ZIP／逐張下載、反向發布說明與 3×3 格線預覽。
- Android 本地 Canvas／Bitmap、MediaStore 與逐張 Sharesheet 引導。
- 分享量測與正式站／裝置驗收。

### Phase 2：旅程回顧短片

- 影片上傳、離線佇列、HEVC／HDR 正規化。
- timeline、固定節奏、片段卡片剪輯器與現場聲／靜音匯出。
- 共用後端 renderer、MP4 匯出與分享連結。

### Phase 3：內部 Beta 與收斂

- 四週實際行為測試。
- 上傳成功率至少 70%。
- 有素材行程的短片完成率至少 60%。
- 完成後下載或開啟分享至少 25%。
- 剪輯器中途離開且未保存不高於 40%。
- 未達標時優先修正或停止其中一種輸出，不擴充 AI 生成內容。

---

## 11. 驗收標準

### 11.1 共用資料

- 改名、換日與排序後 stopId 不變，照片與影片不脫落。
- migration 中斷可重跑，未完成前不刪除舊 key。
- 每張可輸出照片有 photoId；每段影片有 mediaId。
- owner／editor／viewer 權限符合預期。

### 11.2 九宮格

- 主視覺固定 3240×4320，輸出九張 1080×1440、3:4 JPEG。
- 九張依 r3c3 → r3c2 → r3c1 → r2c3 → r2c2 → r2c1 → r1c3 → r1c2 → r1c1 的順序命名與引導。
- 把九張依最終位置重新拼接後，應還原主視覺；切線不得遺失或重疊像素。
- 素材不足與充足時都能正確預覽，使用者可替換照片與調整裁切焦點。
- Web 私有照片可經 `getBlob()` 載入 Canvas，無永久 token。
- Web 可取得九張圖片、完整預覽與發布說明；ZIP 不可用時有逐張下載 fallback。
- Android API 26／29／33／35 均能保存或依權限降級，逐張分享不會被當成輪播貼文。
- 發布前清楚提示置頂／重排、不可穿插貼文，以及之後新增 1～2 篇會暫時打亂大圖。
- 375px 寬按鈕與景點標籤不拆行、不重疊。

### 11.3 回顧短片

- 0、1、2、5、8 個素材都能輸出 10～30 秒合理片長。
- 影片只包含真實素材、路線、資料與固定動畫。
- 音訊只能是使用者影片的現場聲或全片靜音；介面沒有曲庫、音樂上傳或內建音樂選擇器。
- 預覽與後端輸出的順序、字幕、裁切及總片長一致。
- 直式、橫式、旋轉、無音訊、VFR、HEVC 與 HDR 均有測試。
- Android App 測試 API 26／29／33／35、低階 1～2 GB RAM 與 HEVC／HDR 機種。
- iOS Safari、Android Chrome、桌機 Chrome／Safari 均能播放正式 MP4。

### 11.4 安全與復原

- MIME 偽造不能通過後端檢查。
- resumable 重送不產生重複 media 或 job。
- 離線、取消、session 過期與 process restart 不造成永久孤兒或靜默遺失。
- 刪除素材、行程或撤銷共編權限後，晚到 worker 不會讓資料復活。
- 主控台零紅色錯誤；Emulator Rules 測試全部通過。

---

## 12. 外部文件

- Firebase Web upload：<https://firebase.google.com/docs/storage/web/upload-files>
- Firebase Android upload／跨 process 續傳：<https://firebase.google.com/docs/storage/android/upload-files>
- Firebase Web `getBlob()`／CORS：<https://firebase.google.com/docs/storage/web/download-files>
- Firebase Storage Rules：<https://firebase.google.com/docs/storage/security>
- Android WorkManager：<https://developer.android.com/reference/androidx/work/WorkManager>
- Android user-initiated data transfer：<https://developer.android.com/develop/background-work/background-tasks/uidt>
- Android Media3 Transformer：<https://developer.android.com/media/media3/transformer>
- Android MediaStore：<https://developer.android.com/training/data-storage/shared/media>
- Media Capabilities：<https://developer.mozilla.org/en-US/docs/Web/API/MediaCapabilities/decodingInfo>
- WebCodecs：<https://developer.mozilla.org/en-US/docs/Web/API/WebCodecs_API/Codec_selection>

---

## 13. 收斂決策紀錄

2026-08-05 決定把核心計畫收斂為：

1. Instagram 主頁九宮格。
2. 旅程回顧短片。

AI 短劇、AI 生成影片與長 Vlog 全部移出 roadmap。先驗證使用者是否願意保存與分享這兩種真實旅程輸出，再決定是否擴充。
