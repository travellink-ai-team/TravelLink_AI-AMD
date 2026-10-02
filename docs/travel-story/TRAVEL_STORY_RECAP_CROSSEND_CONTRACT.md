# 回顧短片・跨端契約（Web 與 App 共用，保證輸出一致）

> 原則：**後端是唯一真相源**。所有渲染邏輯（時間軸／版面／交通工具外型／鏡頭／照片／編碼）
> 都在後端（無頭 Chrome 跑 `app/recap-render.js`）。**任何一端都不得自行重寫渲染器**——
> 只要送出相同的 `{trip, photos}`、打同一個端點，就會得到**位元組級相同**的 mp4。

## 1. 端點（Web 與 App 完全相同）
- `POST /api/recap/render` — body `{ trip, photos }`（見下）。回 `{ jobId, totalMs }`。
- `GET  /api/recap/jobs/{jobId}` — 回 `{ status: queued|rendering|done|error, progress, error }`。
- `GET  /api/recap/jobs/{jobId}/download` — `status=done` 時回 `video/mp4`（只有建立者 uid 可下載）。
- **認證**：每個請求帶 `Authorization: Bearer <Firebase ID token>`。job 綁 uid，別人查不到、載不到。
- **限流**：每人每小時 5 次；同時只渲染一支（序列化）。

## 2. `trip`（兩端必須組出相同結構；欄位型別／單位固定）
```jsonc
{
  "title": "string",          // 行程標題
  "region": "string",         // 地區（如 台東）
  "dateLabel": "string",      // 顯示用日期（如 2026.08.13–14）
  "people": "string",         // 如 2人
  "distanceKm": 24,           // 數字，總路程公里（可由 stops haversine 求和）
  "transportMode": "taxi",    // 主交通工具：taxi|car|scooter|walk|ferry
  "stops": [
    {
      "stopId": "string",     // 穩定站點 id（App：用同一顆穩定 id，勿用陣列索引）
      "name": "string",
      "lat": 22.79, "lng": 121.12,
      "mode": "taxi",         // 該站抵達所用交通工具（同上枚舉）
      "stayMin": 45,          // 停留分鐘
      "dayIndex": 1           // 第幾天（1 起算；決定 dayCount）
    }
    // 2..40 站
  ]
}
```
**單位/枚舉一致性是一致輸出的前提**：mode 枚舉、stayMin 分鐘、dayIndex 由 1 起算，兩端務必相同。
時間軸、片長、鏡頭全由後端依這些欄位重算，**兩端不要各自算**。

## 3. `photos`（到站照片；每站最多一張）
```jsonc
"photos": [ { "stopIndex": 1, "url": "https://.../photo.jpg" } ]
```
- `stopIndex`：對應 `trip.stops` 的索引。
- `url`：**https 可公開讀取的圖**（Firebase Storage 下載 URL 即可）；後端會伺服器端抓成 data URL 再插入，
  避免跨源污染。App 端只要給得到 https URL 即可，**不必自己畫照片**。
- 有照片的站，後端自動把停留拉長 ~2.6 秒並做 Ken Burns。

## 4. 路線幾何（保證一致的關鍵）——存 Firestore、兩端讀同一份
不新接 Directions 供應商、不重複付費：把 planner 已算好的路線幾何**存進 Firestore**，兩端讀同一份。

**存放位置**：`micro_trips/{tripId}.routeGeometry`
```jsonc
{
  "sig": "stopId0|stopId1|...",        // 站點簽章（各站 stopId 依序 join；站序一變即失效）
  "segments": [ [[lat,lng],[lat,lng],...], ... ],  // 每段一條折線（length = stops-1），每段最多約 120 點
  "updatedAt": 1690000000000
}
```
**讀取（Web 與 App 相同流程）**：
1. 算出目前 `sig`（`stops.map(s=>s.stopId).join('|')`）。
2. 讀 `routeGeometry`；若 `sig` 相符且 `segments.length === stops-1` → 用它當本次 render 的 `routePoints`（跟著 `{trip,photos}` 一起 POST）。
3. 沒有或 `sig` 不符 → 該端用自己的地圖服務現算 `overview_path`，**寫回** `routeGeometry`（同 `sig`），下次兩端都命中。

**寫入權限**：owner 直接可寫；editor 需 `firestore.rules` 的 editor 白名單含 `routeGeometry`（本 repo 已加，部署後生效）；viewer 只讀不寫。寫不進去不影響本次生成（該端仍以現算幾何出片）。

> 一致性關鍵：只要 `routeGeometry` 已存在，Web 與 App **讀到並送出同一份 `segments`** → 後端產出**相同影片**。
> 過渡期（快取尚未建立）某端先現算，另一端讀到後即對齊。

**Web 端參考實作**：`ai-travel-planner-v8.js` 的 `resolveRecapRoutePoints`（讀快取→現算→寫回）、`recapRouteSig`、`computeRecapRoutePoints`。

## 5. 客戶端各自要做的（僅這些，其餘都在後端）
1. 蒐集本端行程 → 組出上面的 `trip`（欄位/單位對齊本文件）。
2. 蒐集每站第一張打卡照片的 https URL → `photos`。
3. 帶 Firebase ID token `POST /api/recap/render`。
4. 輪詢 `jobs/{id}` 顯示進度。
5. `done` 後 `download` 取 mp4，交給系統分享/儲存。

## 6. 一致性保證與注意
- **相同輸入 → 相同輸出**：manifest builder 為決定性；渲染器對同一 manifest 畫同樣的幀。
- 影片規格固定：1080×1920 直式、H.264/yuv420p、30fps。
- App 不需要、也不應該移植 `recap-manifest.js`／`recap-render.js`；那會造成兩份邏輯漂移，違反本契約。
- Web 端參考實作：`app/ai-travel-planner-v8.js` 的 `collectRecapTrip` / `collectRecapPhotos` / `startRecapVideo`。
