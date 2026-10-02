# Scenic Points Crawler 使用說明

這個 Node.js 工具會從「交通部觀光署 OpenData」抓取景點資料，並寫入 Firebase Firestore 的 `scenic_points` 集合。

目前預設：

- Firestore collection：`scenic_points`
- 匯入地區：`台東縣`
- 匯入上限：`500` 筆
- 座標欄位：`scenicCoordinates.lat` / `scenicCoordinates.lng`

## 安裝

進入 crawler 資料夾：

```powershell
cd C:\Users\USER\Desktop\UIUX\crawler
```

安裝套件：

```powershell
npm install
```

如果 PowerShell 擋住 `npm.ps1`，請改用：

```powershell
npm.cmd install
```

## 設定 Firebase 權限

這支程式需要 Firebase Admin service account。

請把 Firebase 下載的私鑰 JSON 放在：

```text
C:\Users\USER\Desktop\UIUX\crawler\serviceAccount.json
```

然後在 PowerShell 設定：

```powershell
$env:FIREBASE_SERVICE_ACCOUNT_PATH="C:\Users\USER\Desktop\UIUX\crawler\serviceAccount.json"
```

如果沒有設定，執行時會出現：

```text
Missing Firebase service account.
```

## 匯入 OpenData 景點

### 景點來源（`--source`）

| 值 | 來源 | 狀態 |
|---|---|---|
| `taitung` | 台東觀光旅遊網 opendata（`tour.taitung.gov.tw`） | ✅ **可用，265 筆** |
| `national` | 全國觀光 OpenData（`media.taiwan.net.tw`） | ❌ **已失效（404）** |
| 省略＝`auto` | 先試 `national`，失效且目標是台東時自動改用 `taitung` | ✅ |

**全國那支在 2026-09 實測回 404** —— 觀光署已把資料移到需要授權的 TDX
（本專案的 `TDX_APP_ID` / `TDX_APP_KEY` 目前沒有設定，只有 `--enrich-fees -- --tdx` 會用到）。
指定 `--source=national` 會直接丟例外並說明原因，不會靜默回 0 筆。
若之後找到可用的全國來源，設 `OPENDATA_SOURCE_URL` 即可切回去。

台東觀光網這支相較之下的優勢：

- **100% 有地址（含鄉鎮）** → 行政區不必靠座標猜
- **100% 有座標與圖片**（`photoUrl`，480×360）
- `opentimeGoogle` 是 Google 的七行格式，正好是 `app/business-hours.js` 吃的格式
  （實測 265 筆中 193 筆可解析、32 筆判為 unknown、40 筆沒有時間資料）

> ⚠️ **圖片只寫進 Firestore（`photoUrl`），不會由 `export:local` 帶進 `app/poi-data.js`。**
> 政府 opendata 的資料本身通常開放，但圖片授權不一定 —— 要先確認
> `tour.taitung.gov.tw` 的使用條款。確認可用後，在 `toLocalPoi` 加一行 `photoUrl` 就能接上前端。

### 執行

先用預覽模式，不會寫入 Firebase，**也不會呼叫 Google API**（乾跑時跳過附近廁所查詢）：

```powershell
npm run import:taitung:dry
```

確認輸出內容沒問題後，正式匯入（這次會呼叫 Google API 查附近廁所）：

```powershell
npm run import:taitung
```

如果 PowerShell 擋住 npm，請改用：

```powershell
npm.cmd run import:dry
npm.cmd run import
```

## 刪掉 scenic_points 後會自動新增嗎？

會。

Firestore 不需要先手動建立 collection。只要執行：

```powershell
npm run import
```

程式會自動建立新的 `scenic_points` collection，並寫入台東縣景點資料。

注意：Firestore Console 只有在第一筆文件寫入後，才會顯示新的 collection。

## 匯入後會寫入哪些欄位？

每筆景點會寫入類似這些欄位：

```text
id
name
desc
notice
region
city
county
scenicCoordinates.lat
scenicCoordinates.lng
lat
lng
formatted_address
tripTitle
source
crawl_source
crawl_confidence
official_opendata_id
official_opendata_name
official_opendata_match
updatedAt
last_crawled
needsCrawl
```

其中 `scenicCoordinates.lat` / `scenicCoordinates.lng` 是目前前端景點資料使用的座標格式。

## 補齊既有景點資料

如果 Firestore 裡已經有景點文件，只想補座標或地址，可以在該文件加上：

```js
needsCrawl: true
```

然後先跑預覽：

```powershell
npm run dry
```

確認沒問題後寫入：

```powershell
npm start
```

這個模式只會處理已存在且 `needsCrawl: true` 的文件，不會自動新增 OpenData 全部景點。

## 匯出本地景點檔（給前端離線優先使用）

把 Firestore `scenic_points` 匯出成前端可直接載入的靜態檔 `..\poi-data.js`（`window.WAI_POI_DATA`），讓「生成 / 重新規劃行程」時先用本地景點清單交給 AI 排序，減少 Google Places / Firebase 呼叫。

先預覽（不寫檔，只印出內容）：

```powershell
npm run export:local:dry
```

確認沒問題後正式產檔：

```powershell
npm run export:local
```

說明：

- 會依景點的行政區把資料分桶成前端目的地鍵（如「台東」「綠島」「蘭嶼」）。
- 每筆只保留變動不大的欄位：`name / lat / lng / desc / address / businessHours / duration? / nearbyToiletLocations`，以及 `enrich:fees` 寫入的 `fee? / feeNote?`（有票價來源才會出現，見下方「校正景點門票費用」）。
- 預設輸出到專案根目錄的 `poi-data.js`；可用 `$env:EXPORT_LOCAL_PATH` 覆寫路徑。
- 前端兩頁（explore、planner-v8）以 `<script src="poi-data.js">` 載入；檔案為空 `{}` 時自動回退 Firebase / live Maps。

## 用 Places 校正景點座標／時間／評分（verify:places）

OpenData 的座標/營業時間較粗略。這個模式用 Google Places 對每個 `scenic_points` **嚴格比對名稱**，把更精準的**座標、營業時間、評分、place_id** 寫回 Firestore（**保留 OpenData 原名稱**），並把永久歇業者標記起來。校正一次後重跑 `export:local`，`poi-data.js` 即達 Places 等級，而**前端執行階段仍 0 Places 費用**。

先試跑前 5 筆（省額度）：

```powershell
npm run verify:places:dry -- --limit 5
```

正式校正（寫回 Firestore）：

```powershell
npm run verify:places
```

說明：

- 預設**跳過已驗證**的文件；要全部重新校正加 `-- --force`。
- 只接受「名稱嚴格比對且落在 OpenData 座標 `VERIFY_NEAR_METERS`(預設 5000m) 內」者，擋掉同名異地；比不到就保留 OpenData。
- 成本：約 1 次 searchText／景點（一次性 Enterprise SKU，**不含** reviews 高價 SKU）。
- 校正後記得 `npm run export:local` 才會反映到 `poi-data.js`。

## 校正景點門票費用（enrich:fees）

景點的真實票價不在 OpenData／Places 的常規欄位裡。這個模式抓「台東觀光旅遊網自有 opendata」的 `ticket` 門票文字（主來源，265 景點約 41 筆有值），解析出金額後把 **`fee`（數字，0＝免費）、`feeNote`（原始票價文字）、`feeSource`** 寫回 Firestore `scenic_points`；校正一次後重跑 `export:local`，`poi-data.js` 就會帶有門票欄位，前端（`ai-travel-planner-v8.js`）用它在行程卡片顯示門票、並算進預算拆解。

先試跑（不寫入，只印出比對結果）：

```powershell
npm run enrich:fees:dry
```

正式寫回 Firestore：

```powershell
npm run enrich:fees
```

說明：

- 預設**跳過已有 `feeSource`** 的文件；要全部重新比對加 `-- --force`。
- 預設**只接受名稱嚴格比對**；加 `-- --loose` 改用寬鬆比對（與 `verify:places` 的 `placeNameMatchesQuery` 同一套），較容易配錯同名異地的景點，請搭配少量 `--limit` 檢查輸出再放大跑。
- 加 `-- --tdx` 會再用**全國 TDX `AttractionFee`／`Attraction`** 補台東觀光網沒涵蓋到的景點（結構化票價優先，其次 `IsAccessibleForFree`／`FeeInfo` 文字），需要 `TDX_APP_ID` / `TDX_APP_KEY`（讀 `app/weather.env.js` 或環境變數）。台東縣本身在全國 TDX 的 `AttractionFee` 資料很少，通常不需要加這個旗標。
- 對不到票價來源的景點**維持未知**（不寫欄位），不做估算；前端會顯示「門票資訊未提供」以區別「查不到」與「免費」。
- 少數解析結果不理想或想覆蓋（例如改用假日全票、或票價文字誤判）的景點，改在 [`app/attraction-fee-config.js`](../app/attraction-fee-config.js) 的人工覆蓋表手動調整，不必重跑爬蟲；前端查詢時人工覆蓋表優先於 `poi-data.js`。
- 校正後記得 `npm run export:local` 才會反映到 `poi-data.js`。

## 餐廳快取（crawl:food → restaurant-data.js）

餐廳變動快、且不在 `poi-data.js` 裡。這個模式依景點形心，用 Places (New) `searchNearby`（`includedTypes: restaurant`）抓每個目的地的餐廳候選，寫成獨立檔 `..\restaurant-data.js`（`window.WAI_RESTAURANT_DATA`）。前端 `fetchGoogleMapsFoodList` **本地優先**：有快取就用、跳過 Places；命中的餐廳站連執行階段驗證也一併跳過。

先預覽：

```powershell
npm run crawl:food:dry
```

正式產檔：

```powershell
npm run crawl:food
```

說明：

- 只對景點數 ≥ `MIN_FOOD_POIS`(預設 3) 的目的地產生；每目的地收 `FOOD_PER_DEST`(預設 25) 間。
- 欄位：`name / lat / lng / businessHours / address / rating`，另外從 Places 的 `priceLevel` / `priceRange` 換算出 **`costPerPerson`（人均消費估值）與 `costNote`（顯示文字，如「$300–500」或「約 $150（$）」）**；只有 `priceLevel` 沒有 `priceRange` 時用等級估值（免費/$150/$350/$700/$1200）代替。前端用它在用餐站顯示人均消費、並算進預算拆解。
- 會過濾掉夾帶在 `restaurant` 類型結果裡的住宿（`primaryType` 命中飯店/民宿類別，或店名符合「飯店／旅館／民宿／resort／hotel」等關鍵字者不收）。
- 成本：改用 `searchNearby` 後每目的地 1 次呼叫（原本 `searchText` 3 詞需 3 次）。
- 由 `..\ai-travel-explore-final.html` 以 `<script src="restaurant-data.js">` 載入；檔案不存在時前端自動回退即時抓。

## 停車場資料（crawl:parking → parking-data.js）

TDX 對台東這種鄉村縣的路外停車場覆蓋很稀疏，改用**臺東縣政府自己維護的開放資料**
（政府資料開放平臺 [dataset 165292](https://data.gov.tw/dataset/165292)，臺東縣政府公有及民營路外停車場，
XLSX 格式）：下載 → 解析 30 筆 → 依地址呼叫 Geocoding API 補經緯度（地址查不到時退回用「名稱＋台東」查）→
寫入 Firestore `parking_lots` collection（預設**跳過已地理編碼**過的，`--force` 全部重查）。

⚠️ **需要一支「不限 HTTP referrer」的 Google Maps API key**：`app/weather.env.js` 的
`GOOGLE_MAPS_API_KEY` 是給瀏覽器用的 referrer 限制鍵，伺服器端呼叫 Geocoding／Places 一律
403（`API_KEY_HTTP_REFERRER_BLOCKED`）。請在 GCP Console 另建一支 Application restrictions
設 **None 或 IP addresses**、API restrictions 只勾 **Geocoding API + Places API (New)** 的鍵，
執行前用環境變數覆寫（優先於 weather.env.js）：

```powershell
$env:GOOGLE_MAPS_API_KEY = "你的爬蟲專用鍵"
npm run crawl:parking:dry   # 先預覽，不寫 Firestore
npm run crawl:parking       # 正式寫入
npm run export:local        # 一併把 parking_lots 匯出成 app/parking-data.js（window.WAI_PARKING_DATA）
```

### OpenStreetMap 來源（`--source`）

縣府那份只有 **30 筆、23 筆集中在台東市區**，但 `poi-data.js` 有 280 個台東景點——
鄉鎮景點幾乎必然查不到停車場，前端只能一路顯示「停車待確認」。因此加上
**OSM `amenity=parking`** 作為第二來源：Overpass API **免金鑰、免費用**，
台灣鄉間由在地社群長期維護，覆蓋遠優於 TDX 與縣府名單。

```powershell
npm run crawl:parking                     # 預設 --source=all：縣府 + OSM 都跑
node worker.js --crawl-parking --source=osm --dry-run   # 只看 OSM 會抓到什麼
node worker.js --crawl-parking --source=gov             # 只跑原本的縣府 XLSX
```

- **不需要 Google 金鑰**：OSM 自帶座標，不用地理編碼。沒設 `GOOGLE_MAPS_API_KEY` 時
  縣府那條會自己略過，OSM 仍照跑。
- **docId**：`osm-<type>-<id>`（穩定，重跑會原地更新而非重複新增）。
- **去重**：與既有資料（縣府／社群／前一輪 OSM）比對，**60 公尺內**視為同一座而跳過；
  可用 `OSM_DEDUPE_METERS` 調整，`--force` 可忽略去重。
- **排除**：`access=private/no/permit` 與 `parking=private` 的私人停車場不匯入。
- **欄位對應**：`capacity` → `smallSpots`；`fee` / `surface` / `covered` → `notes`
  （「收費」「免費」「未鋪面」「有遮蔽」）；`source: 'osm'`，另存 `osmType` / `osmId`。
- **無名稱的場**很常見，一律寫成「停車場」，不自行編造看起來很正式、現場卻找不到的名字。

⚠️ **公共 Overpass 鏡像很不穩**，程式會依序嘗試多個端點（GET 再 POST），並且
**把「HTTP 200 但 0 筆」也當成失敗換下一個端點**——有些鏡像只涵蓋單一國家
（例如 `overpass.osm.ch` 只有瑞士），查台東會回 200 + 空陣列，若當成成功就會
「匯入成功但什麼都沒進來」。要指定單一端點可設 `OVERPASS_API_URL`。
若全部端點都逾時，等幾分鐘再跑即可，既有資料不受影響。

說明：

- 欄位：`name / address / phone / evSpots / largeSpots / smallSpots / motoSpots / notes / lat / lng`。
- 只涵蓋台東本島（資料源本身無綠島／蘭嶼），前端 `resolveParkingCoord` 只在 `region` 判定為
  `Taitung` 本島時才會查這份本地資料，其餘（含離島）仍走 TDX → Places 既有邏輯。
- 「不定期更新」，資料源沒有 API/JSON，只有 XLSX 下載——crawler 用零依賴的手刻 zip/XML
  解析（見 `worker.js` 內註解），沒有引入 npm `xlsx` 套件（該套件 npm 上最新版有未修的
  prototype-pollution／ReDoS 已知漏洞，SheetJS 把後續修補移到自家 CDN 未再發布到 npm）。
- 由 `..\ai-travel-planner-v8.html` 以 `<script src="parking-data.js">` 載入；集合為空時
  `export:local` 會跳過匯出、前端直接退回 TDX/Places（不會噴錯）。

## 每兩週自動爬餐廳（Windows 工作排程）

由 [`run-food-crawl.bat`](run-food-crawl.bat) + 工作排程執行，工作名稱 **`WanderAI Food Crawl`**，每 2 週週日 03:00，輸出寫到 `crawler/crawl-food.log`。

| 操作 | 指令 |
|---|---|
| 查看狀態/下次執行 | `schtasks /query /tn "WanderAI Food Crawl" /v /fo LIST` |
| 立即手動跑一次 | `schtasks /run /tn "WanderAI Food Crawl"` |
| 改時間（例 04:00） | `schtasks /change /tn "WanderAI Food Crawl" /st 04:00` |
| 停用 / 啟用 | `schtasks /change /tn "WanderAI Food Crawl" /disable`（`/enable`） |
| 刪除 | `schtasks /delete /tn "WanderAI Food Crawl" /f` |
| 重新建立 | `schtasks /create /tn "WanderAI Food Crawl" /tr "C:\Users\USER\Desktop\UIUX\crawler\run-food-crawl.bat" /sc WEEKLY /mo 2 /d SUN /st 03:00 /f` |

注意：目前為 **Interactive only**（使用者登入時才會跑），不需密碼；需機器開機。服務帳號路徑寫死在 `.bat` 內，搬移專案請同步修改。若要「未登入也跑」，重建時加 `/ru <帳號> /rp <密碼>`。

## 每日分散爬蟲（一週平均跑完 + 免費額度用完前停止）

`verify:places` 對全部 `scenic_points` 逐筆打 Places（可能上百次），一次跑完容易吃掉免費額度。改用兩個旗標把它**平均分散在一週**、並在**預算內停止**：

| 旗標 | 作用 |
|---|---|
| `--slice K/N` | 把待處理文件均分成 N 份，只跑第 K 份（K 從 1 起算）。例：`--slice 3/7` |
| `--max-calls N` | 本次最多打 N 次 Google API（geocode／Places searchText），達上限即停，**未處理者下次續跑**。`0`＝不限 |

- `verify:places` 會自動**跳過已驗證**的文件，所以同一片隔天重跑只會處理還沒做的；7 天跑完整輪後就閒置（每天 0 工作量）。
- `--slice` / `--max-calls` 也可用環境變數 `CRAWL_SLICE` / `CRAWL_MAX_CALLS` 設定。
- `crawl:food` 在切片／預算模式下改為**部分更新**：先讀既有 `restaurant-data.js`，只覆寫本次處理到的目的地，不會清掉其他天已爬的資料。

手動試一片（含預算上限）：

```powershell
npm run verify:places -- --slice 2/7 --max-calls 120
```

### 用工作排程每天自動分散

[`run-weekly-crawl.bat`](run-weekly-crawl.bat) 會**依星期幾**自動算出今天要跑哪一片（週日=1…週六=7），以 `CRAWL_MAX_CALLS`（預設 120）為當日上限跑 `verify:places`，再重產 `poi-data.js`（`export:local` 零 API 費用）。輸出寫到 `crawler\weekly-crawl.log`。

建立每天 03:00 執行的工作：

```powershell
schtasks /create /tn "WanderAI Weekly Crawl" /tr "C:\Users\USER\Desktop\UIUX\crawler\run-weekly-crawl.bat" /sc DAILY /st 03:00 /f
```

| 操作 | 指令 |
|---|---|
| 立即手動跑今天這一片 | `schtasks /run /tn "WanderAI Weekly Crawl"` |
| 改每日上限（例 80） | 設環境變數 `CRAWL_MAX_CALLS`，或直接改 `.bat` 內的預設值 |
| 停用 / 啟用 | `schtasks /change /tn "WanderAI Weekly Crawl" /disable`（`/enable`） |
| 刪除 | `schtasks /delete /tn "WanderAI Weekly Crawl" /f` |

**`CRAWL_MAX_CALLS` 怎麼定？** 取決於你的免費額度：Google Maps Platform 每月約 US$200 免額，這個 `verify:places` 的 searchText（含座標／營業時間／評分欄位）約 Enterprise 等級，**每月免費額度約可換 5,000–6,000 次**。預設 120/天 × 30 ≈ 3,600/月，落在免額內。若你已有其他用途在吃同一份額度，請把每日上限調低。

## 清理孤兒共編行程（cleanup:collab）

多人共作一開 lobby 就會在 `micro_trips` 建一筆「空殼」讓人加入；若 owner 放棄、從沒進精靈規劃，就會留下孤兒文件佔空間。這個模式會刪掉**從未規劃**（無 `wizardData.dest`）且**超過 N 天沒更新**（預設 7，可用 `--days` 或 `CLEANUP_COLLAB_DAYS` 調整）的 collab 空殼，連同對應的 `invites` 一併硬刪。已規劃／已生成的行程不會被碰。

先預覽（只列不刪）：

```powershell
npm run cleanup:collab:dry
npm run cleanup:collab:dry -- --days 14
```

正式清理：

```powershell
npm run cleanup:collab
```

說明：

- 偵測準則：`collab==true` 且 `!wizardData.dest`（只開 lobby 沒進精靈）且 `updatedAt/collabCreatedAt` 早於 N 天（無時間戳者視為舊資料一併清）。
- 前端面：owner 在「我的微旅行」刪除共編行程時，已會連 Firestore 文件一起刪、邀請碼停用；這個模式是補掃沒手動刪掉的殘留。
- 可選擇排進工作排程（如每週一次），與 `crawl:food` 同樣方式建立。

## 各資料檔與刷新流程

| 檔案 | 全域變數 | 由誰產生 | 何時刷新 |
|---|---|---|---|
| `..\poi-data.js` | `window.WAI_POI_DATA` | `export:local`（資料來自 `verify:places` + `enrich:fees`） | 景點/座標校正有變時：`verify:places -- --force` → `export:local`；門票有變時：`enrich:fees -- --force` → `export:local` |
| `..\restaurant-data.js` | `window.WAI_RESTAURANT_DATA` | `crawl:food` | 每兩週自動；要立即更新就手動 `crawl:food` |
| `..\parking-data.js` | `window.WAI_PARKING_DATA` | `export:local`（資料來自 `crawl:parking`） | 縣府資料「不定期更新」，需要時手動 `crawl:parking -- --force` → `export:local` |
| [`app/attraction-fee-config.js`](../app/attraction-fee-config.js) | `window.WAI_ATTRACTION_FEE` | 手動維護（非爬蟲產出） | 想覆蓋 `poi-data.js` 門票時直接編輯，不必重跑爬蟲 |

前兩者為自動產生檔，請勿手動編輯；`attraction-fee-config.js` 相反，是唯一預期手動編輯的覆蓋層。

## 可選設定

通常不用改，但需要時可以在 PowerShell 設定：

```powershell
$env:POI_COLLECTION="scenic_points"
$env:CRAWL_REGION="台東縣"
$env:CRAWL_LIMIT="50"
$env:CRAWL_FETCH_LIMIT="250"
$env:IMPORT_LIMIT="500"
```

說明：

- `POI_COLLECTION`：要寫入的 Firestore collection，預設 `scenic_points`
- `CRAWL_REGION`：地區限制，預設 `台東縣`
- `CRAWL_LIMIT`：補齊既有文件時最多處理幾筆
- `CRAWL_FETCH_LIMIT`：補齊模式最多先讀取幾筆候選文件
- `IMPORT_LIMIT`：匯入模式最多匯入幾筆，預設 `500`
- `VERIFY_NEAR_METERS`：verify:places 接受 Places 結果與 OpenData 座標的最大距離，預設 `5000`
- `MIN_FOOD_POIS`：crawl:food 目的地最少景點數門檻，預設 `3`
- `FOOD_PER_DEST`：crawl:food 每目的地最多收幾間餐廳，預設 `25`
- `CRAWL_MAX_CALLS`：單次執行最多打幾次 Google API，達上限即停（免費額度守門），`0`＝不限
- `CRAWL_SLICE`：把工作均分成 N 份只跑第 K 份，形如 `3/7`（等同 `--slice`，用於一週分散）
- `RESTAURANT_DATA_PATH` / `EXPORT_LOCAL_PATH`：覆寫輸出檔路徑
- `TDX_APP_ID` / `TDX_APP_KEY`：`enrich:fees -- --tdx` 用的 TDX 會員金鑰；沒設定時會嘗試讀取 `app/weather.env.js` 裡的同名欄位
- `WEATHER_ENV_PATH`：覆寫 `weather.env.js` 的讀取路徑；沒設定時依序找 `app/weather.env.js`（目前現址）再退回專案根目錄（舊位置，相容用）

## 常用流程

第一次匯入台東縣景點：

```powershell
cd C:\Users\USER\Desktop\UIUX\crawler
$env:FIREBASE_SERVICE_ACCOUNT_PATH="C:\Users\USER\Desktop\UIUX\crawler\serviceAccount.json"
npm run import:dry
npm run import
```

只檢查既有景點並補資料：

```powershell
cd C:\Users\USER\Desktop\UIUX\crawler
$env:FIREBASE_SERVICE_ACCOUNT_PATH="C:\Users\USER\Desktop\UIUX\crawler\serviceAccount.json"
npm run dry
npm start
```

## 注意事項

- `npm run import:dry` 和 `npm run dry` 都是安全預覽，不會寫入 Firebase。
- `npm run import` 會從 OpenData 建立或更新 `scenic_points` 文件。
- `npm start` 只會補齊既有且 `needsCrawl: true` 的文件。
- `serviceAccount.json` 是私鑰，不要上傳 GitHub。
- 如果需要 Google Geocoding fallback，可以設定 `GOOGLE_MAPS_API_KEY`；沒有設定時，程式會嘗試讀取上一層的 `weather.env.js`。
