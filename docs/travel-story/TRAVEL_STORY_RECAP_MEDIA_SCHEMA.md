# 回顧短片 media schema（M0 設計，對齊母計畫 §3.3 / §3.2）

> 目的：定義照片／影片素材如何存放與被 manifest 引用，讓前端預覽、後端 renderer、跨端（Web/App）
> 用同一份結構。**不做第三次照片搬家**——沿用既有打卡照片，另開影片專用子集合。

## 1. 既有照片（沿用，不動）
打卡照片已存在 `micro_trips/{tripId}` 的 visited/photos 結構（B 系列已上線）。回顧短片**唯讀取用**，
以 `stopId` 對應到某站；不改寫既有欄位。

## 2. 影片子集合（新增）
`micro_trips/{tripId}/media/{mediaId}`（只放影片與其正規化產物）：

| 欄位 | 型別 | 說明 |
|---|---|---|
| `mediaId` | string | 文件 id |
| `stopId` | string | 對應景點的穩定 stopId（M0 對齊後保證存在） |
| `kind` | 'video' | 目前只有影片走這；照片仍在既有結構 |
| `storagePath` | string | 原始上傳路徑（私有） |
| `normalizedPath` | string\|null | 後端轉 H.264/YUV420p/30fps 後的路徑（HEVC/HDR 來源必經） |
| `posterPath` | string\|null | 封面縮圖 |
| `durationMs` | number | 原片長 |
| `trimStartMs` / `trimEndMs` | number | 剪輯器選取區間（片長 = 差值） |
| `keepAudio` | boolean | 保留現場原音 / 靜音 |
| `width`/`height`/`rotation` | number | 正規化後幾何 |
| `status` | 'uploading'\|'normalizing'\|'ready'\|'failed' | 生命週期 |
| `ownerUid` | string | 上傳者 |
| `createdAt`/`updatedAt` | Timestamp | — |

冪等：以「原檔 hash + stopId」推 `mediaId`，重複上傳同素材不新增文件（Codex-5 已備純邏輯）。

## 3. manifest 如何引用（M1 已預留 `media:[]`）
compiled manifest 的 `media` 陣列元素（由 builder 之後版本填入；P1 為空）：
```
{ mediaId, kind:'photo'|'video', stopIndex, atMs,      // 對齊到該站 arriveMs
  srcPath,                                             // photo=既有照片路徑 / video=normalizedPath
  durationMs,                                          // 影片段長（照片用 Ken Burns 預設 2.5s）
  keepAudio }                                          // 影片專用
```
renderer 依 `atMs` 在路線動畫到站時插入該段（P2 照片 Ken Burns、P3 影片＋原音）。

## 4. 隱私（§8）
原始照片／影片預設私有，只 owner/editor/後端可讀；成片輸出另存，分享由使用者決定。
Storage Rules（M10）與此對齊，**部署由使用者執行**（我會在改好規則後通知）。

## 5. 待決 / 待通知
- Storage Rules 片段（M10）改好後 → **通知使用者部署**。
- App 端影片上傳與 Web 端剪輯器（§6.4）為 P2/P3，本文件先定結構供 P1 之後對接。
