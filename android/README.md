# TravelLink AI — Android App

台東微旅行規劃 App（Kotlin + Jetpack Compose），與同一 repo 的網頁版共用 Firebase 專案與 Firestore 資料：
在 App 建立的行程可在網頁開啟，反之亦然。

- 行程生成：規劃精靈 → AI 依本地景點清單排出行程（含多日、兩天一夜、綠島／蘭嶼離島、用餐時段）
- 行程規劃器：地圖路線、停車場與步行時間、天氣、預算、拖曳排序、單站編輯
- 行程進行中：逐站打卡、即時定位、預計離開時間順延與衝突提醒、AI 隨行管家
- 多人共編、好友與通知、共同相簿、九宮格與回顧短片

## AMD AI 代理人組功能

開發版（debug build）的側欄有「🧪 AI 引擎」切換：

| 模式 | 文字生成 | 旅程應變 |
|---|---|---|
| Gemini（預設） | Firebase Cloud Functions → Gemini | — |
| AMD | 網頁 AMD 版代理 `/api/vertex` → 工研院 gpt-oss-120b | 隨行管家內呼叫 `/api/agent/replan`（SSE） |

- 兩個端點都在 `travel-link-amd.duckdns.org`，需要 Firebase 登入（ID token），由伺服器端限流；
  工研院端點網址只放在伺服器，不在 App 內。
- 旅程應變助理會依延誤、下雨、停航、疲累等情境提出修改行程的提案，使用者確認後才套用（共編行程有重新生成鎖）。
- 圖片生成沒有 AMD 對應模型，兩種模式都走 Gemini。
- **AMD 切換、情境模擬與 Demo 模式只在 debug build 出現**；release build 固定使用 Gemini。

## 建置

需求：Android Studio（JDK 17）、Android SDK 35，最低支援 Android 8.0（API 26）。

1. 在 `android/` 建立 `local.properties`（已被 git 忽略，**不要提交**）：

   ```properties
   sdk.dir=你的 Android SDK 路徑
   MAPS_API_KEY=Google Maps SDK / Places / Geocoding / Static Maps 金鑰
   DIRECTIONS_API_KEY=Directions API 金鑰（可與上面同一把）
   TDX_CLIENT_ID=TDX 運輸資料平台 Client ID（台鐵班次、停車場）
   TDX_CLIENT_SECRET=TDX Client Secret
   CWA_API_KEY=中央氣象署開放資料授權碼（天氣預報）
   RECAP_API_BASE=回顧短片後端網址（可留空，留空時短片功能顯示未啟用）
   ```

   TDX、氣象署、短片後端留空時，對應功能（台鐵班次、天氣、回顧短片）會略過；地圖與路線需要 Maps／Directions 金鑰。

2. `app/google-services.json` 對應 Firebase 專案 `project-720a680b-3ad1-40d1-b07`。
   要接自己的 Firebase 專案時換成自己的檔案，並部署 `firestore.rules`、`storage.rules` 與 `functions/`。

3. 建置並安裝 debug 版（AMD 功能只在 debug 版）：

   ```bash
   ./gradlew installDebug
   ```

## Firebase Cloud Functions（Python）

`functions/main.py`：

- `generate_content` — App 文字生成的 Gemini 代理（需登入），回傳 token 用量與耗時
- `generate_image` — 行程插圖生成
- `cleanup_deleted_trip_photo` — 共同相簿刪除照片時，清掉 Storage 檔案與舊版引用

```bash
cd functions
pip install -r requirements.txt
firebase emulators:start           # 本機模擬
firebase deploy --only functions   # 部署
```

## 資料

App 內建的景點、餐廳、停車場、船班資料在 `app/src/main/assets/`，由 `scripts/` 產生：

- `build_local_places.py`、`merge_tourism_data.py` — 合併網頁景點／餐廳資料與觀光署開放資料
- `export_parking_data.mjs` — 從網頁匯出縣府＋OpenStreetMap 停車場
- `export_explore_templates.mjs`、`upload_explore_templates.py` — 官方精選範本（與網頁同一份）

需要 Firebase 管理權限的腳本要把服務帳戶金鑰放在 `scripts/serviceAccount.json`（已被 git 忽略）。

## 測試

```bash
./gradlew test                   # 單元測試
./gradlew connectedAndroidTest   # 裝置測試（需連接裝置或模擬器）
```

## 架構

- 依賴注入：Hilt；狀態：ViewModel + StateFlow（`ui/planning/ItineraryViewModel.kt` 為核心）
- 本地：Room（離線歷史）、DataStore（偏好）；遠端：Firestore 即時同步與共編
- AMD 串接：`data/ai/AiProvider.kt`、`data/ai/AmdTextClient.kt`、`data/agent/`、`ui/agent/`

更多開發說明見 [CLAUDE.md](CLAUDE.md)。
