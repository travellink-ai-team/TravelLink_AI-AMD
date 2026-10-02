# 旅程回顧短片・正式版任務分配（路徑 B：後端 renderer）

> 決策：2026-08-14。使用者選路徑 B（雲端合成 mp4，完整照片＋影片＋原音），對應母計畫
> [TRAVEL_STORY_MEDIA_AI_PLAN.md](TRAVEL_STORY_MEDIA_AI_PLAN.md) §6.6／§7。
> 前端 canvas demo（v5，5 種交通工具外型＋路線動畫）保留為專題簡報底牌與後端渲染器的畫面來源。

## 0. 現實與策略

- **8/22（專題 deadline）前無法完成整條 B。** 完整 B ≈ 無頭渲染＋ffmpeg 合成＋Storage 上傳/轉檔＋job 狀態機＋素材正規化＋Rules，保守 2–3 週。
- **策略：里程碑式交付。** 每個 Phase 結束都有可展示產出；前端 canvas demo 是保底，後端 mp4 是加分。
- **重用現有 canvas 渲染器**：後端用 Puppeteer 無頭開同一支 canvas 動畫、逐幀截圖 → ffmpeg 編碼，避免把畫面邏輯寫兩份。

## 1. 模組分解與負責人

| # | 模組 | 負責 | 依賴 |
|---|---|---|---|
| M0 | 穩定 stopId 對齊 + media schema 定義（§3） | 我（跨端／主檔） | — |
| M1 | compiled manifest（route＋stops＋timings＋media refs＋字幕的共用規格與 builder） | **Codex** 產 builder，我定 schema | M0 |
| M2 | 真實道路路線（Directions API → polyline）＋逐段快取 | **Codex** 解碼/快取純函式；我接 API／proxy／金鑰 | M1 |
| M3 | follow-cam 鏡頭數學（world→screen＋緩動＋前瞻） | **Codex** 純數學＋單元測試；我接 render loop | — |
| M4 | 交通工具外型模組化（已完成 5 種，移植） | 我 | — |
| M5 | 後端路線 clip renderer（Puppeteer 無頭 → 逐幀 → ffmpeg → 路線 mp4） | 我（基建）＋**Codex** ffmpeg 參數 builder | M1,M4 |
| M6 | 素材上傳與正規化（§4：上傳、HEVC→H.264 轉檔、孤兒清理） | 我（Storage/轉檔）＋**Codex** 冪等 key／孤兒清理純邏輯 | M0 |
| M7 | 合成 assembly（ffmpeg：路線 clip＋照片 Ken Burns＋影片段＋字幕＋原音） | 我（編排）＋**Codex** filtergraph builder | M5,M6 |
| M8 | render_jobs 狀態機＋後端 job API（§7：建立/查詢/輸出 GCS） | 我（server/auth/proxy） | M5 |
| M9 | 前端：預覽（現有 canvas）＋送 render job＋進度/下載＋手機剪輯器（§6.4） | 我（主檔整合） | M8 |
| M10 | Storage Rules＋隱私/刪除（§4.5,§8） | 我（Rules 由使用者部署） | M6 |

## 2. 里程碑排程

| Phase | 內容 | 產出 | 樂觀落點 |
|---|---|---|---|
| **P0 前置** | M0 stopId/schema、M1 manifest、M2/M3 Codex 純函式 | manifest JSON＋通過的單元測試 | 8/16 |
| **P1 後端路線 mp4 skeleton** | M5 無頭渲染→ffmpeg→純路線 mp4、M8 最小 job、M9 送 job＋下載 | 「後端產出一支純路線 mp4」里程碑 | 8/20 |
| **P2 素材** | M6 照片上傳/正規化、M7 照片 Ken Burns＋字幕合成 | 有照片的 mp4 | 8/22 後 |
| **P3 完整** | 影片段落＋原音、HEVC 轉檔、孤兒清理、M10 Rules | 計畫書完整規格 | 專題後 |

**8/22 前實際目標：完成 P0＋P1（後端能產純路線 mp4），P2 起專題後續做。** 專題當天以前端 canvas demo 為主展示，後端 mp4 若到位則加分展示。

## 3. Codex 分工（各附輸入/輸出規格與驗收，我對照驗收後整合）

- **Codex-1 / M1**：`buildRecapManifest(trip)` → manifest JSON。驗收：給定測試 trip 產出穩定、欄位齊全的 manifest。
- **Codex-2 / M2**：Google encoded polyline 解碼 + 逐段路線快取結構（純函式）。驗收：對已知編碼測試向量解出正確座標。
- **Codex-3 / M3**：follow-cam `worldToScreen` 變換＋鏡頭緩動＋前瞻。驗收：單元測試（邊界、緩動連續性）。
- **Codex-4 / M5**：ffmpeg 參數 builder（幀序列 → mp4；H.264/YUV420p/30fps CFR/直式）。驗收：輸出可播放 mp4 的參數字串正確。
- **Codex-5 / M6**：冪等上傳 key＋孤兒清理純邏輯。驗收：重複上傳同素材產生同 key；孤兒判定正確。
- **Codex-6 / M7**：ffmpeg filtergraph builder（manifest → 合成 route clip＋照片 Ken Burns＋字幕的 filter_complex）。驗收：filtergraph 語法正確、對應 manifest 段落。

**我保留**：Puppeteer 無頭基建、Storage/轉檔管線、render_jobs API 與 auth、主檔（ai-travel-planner-v8.js 716KB）整合、跨端資料、部署、正式站驗證、Rules（部署由使用者）。

## 4. 待你確認後才動的事

1. 這份里程碑排程與「專題以前端 demo 保底、後端為加分」的策略是否 OK。
2. 是否現在就讓我對 Codex-1/2/3（P0 純函式，兩條路都需要）開規格並跑 `codex exec`。
3. Directions API 用哪個供應商（Google Directions 需金鑰與成本；OSRM 免費但需自架或公用實例）—— 影響 M2。
