# TravelLink AI — UI/UX 原型與功能指南

TravelLink AI 是以台東微旅行為核心的無建置、無框架網頁原型。使用者可以從範本或 AI 建立個人／多人行程，在地圖上編輯及實際執行旅程，記錄照片與停車位置，最後產出旅程拼貼圖或回顧短片。

- 正式網站：[https://travel-link-ai.duckdns.org/](https://travel-link-ai.duckdns.org/)
- 探索與建立行程：[ai-travel-explore-final.html](https://travel-link-ai.duckdns.org/ai-travel-explore-final.html)
- 行程編輯器：[ai-travel-planner-v8.html](https://travel-link-ai.duckdns.org/ai-travel-planner-v8.html)
- 主要前端：[app/](app/)
- 文件索引：[docs/README.md](docs/README.md)
- 部署指南：[DEPLOY.md](DEPLOY.md)

> 正式站的 nginx 網站根目錄直接指向 `app/`。請勿任意搬動 `app/` 內的入口 HTML、同名 CSS／JS 或資料檔，否則既有網址與相對引用會失效。

## 快速使用

### 一般使用者

1. 開啟「探索與建立行程」，可先瀏覽官方範本，或按「建立微旅行」。
2. 選擇單人或多人行程，依精靈填入目的地、日期、時長、人數、交通、興趣、節奏、預算、起訖點與指定景點。
3. 檢查右側預覽後建立行程。生成期間可關閉精靈，稍後從浮動進度卡返回。
4. 在「我的微旅行」開啟行程，進入編輯器調整站點、時間、順序、停留時間與交通方式。
5. 出發時按「開始行程」，依序導航、定位、打卡；完成後可到「旅記」整理照片、評分並製作回憶。

### 多人行程

1. 建立行程時選擇「多人一起規劃」，建立共編 lobby。
2. 將邀請碼或分享訊息傳給旅伴；旅伴登入後以邀請碼加入。
3. 每位成員填寫自己的預算、興趣、節奏、禁忌與想去景點。
4. 發起人查看團體偏好彙整後生成行程；owner、editor、viewer 依角色取得不同權限。
5. 編輯器會即時接收遠端更新；不同成員的照片會匯入同一個共同行程相簿並標示上傳者。

### 成果展示／虛擬 GPS

現場完整操作順序與備援方式見[展示流程手冊](docs/展示流程手冊.md)。

1. 先準備一份已有路線的行程。
2. 在編輯器網址加上 `?demo=1`；若本來已有 query string，改加 `&demo=1`。
3. 按「展示模擬」，用搖桿（或鍵盤方向鍵）、暫停／繼續與 `1×`、`2×`、`5×`、`10×` 速度跑完整趟行程；工具列可收合，手機會自動顯示地圖。
4. 系統會模擬移動、抵達、照片提醒、路線逐段變灰與完成事件；結束後可看展示回顧。

展示模式是 sandbox：狀態只存在目前分頁，不會把模擬打卡、完成狀態、照片提示或路線修改寫入正式 localStorage／Firestore。關閉展示後會回復原本的正式行程狀態。
照片上傳與回憶製作不屬於 sandbox；現場請用展示專用行程與示範照片。

## 完整功能清單

下表的「畫面位置」是使用者在網站上找到功能的位置，「主要實作」則是開發者應優先查看的檔案。

### 1. 首頁、探索與帳號

| 功能 | 使用方式／行為 | 畫面位置 | 主要實作 |
|---|---|---|---|
| 品牌介紹頁 | 顯示產品定位並導向主站 | `intro.html` | `app/intro.html` |
| 官方行程範本 | 依台東地區、標籤與搜尋挑選範本，展開查看站點，再以範本開始規劃 | 探索 → 官方精選範本 | `app/explore-templates.js`、`app/ai-travel-explore-final.js` |
| 地區與主題篩選 | 依台東本島、綠島、蘭嶼、海岸、縱谷、美食、自然、親子等條件過濾 | 探索頁上方與側欄 | `app/ai-travel-explore-final.js` |
| 帳號登入／註冊 | Email 密碼、Google、Facebook 登入；可登出與修改密碼 | 右上角使用者選單 | `app/ai-travel-explore-final.js`、`app/ai-travel-planner-v8.js` |
| 個人旅遊偏好 | 設定興趣、旅行節奏與禁忌；建立新行程時自動帶入，仍可單次覆寫 | 使用者選單 → 修改個人喜好 | `app/avoid-tags.js`、`app/ai-travel-explore-final.js` |
| 我的微旅行 | 搜尋、排序、開啟、重新命名、刪除、分享與恢復未完成生成 | 首頁 → 我的微旅行 | `app/ai-travel-explore-final.js` |
| 通知中心 | 未讀數、全部／未讀篩選、全部已讀、好友與加入申請處理 | 頂部鈴鐺 | `app/notifications.js`、兩支主頁 JS |

### 2. AI 建立行程

| 功能 | 使用方式／行為 | 畫面位置 | 主要實作 |
|---|---|---|---|
| 單人／多人模式 | 建立前選擇自己規劃或先建立多人 lobby | 探索 → 建立微旅行 | `app/ai-travel-explore-final.html/.js` |
| 分步規劃精靈 | 收集目的地、單日／兩天一夜、時間、人數、交通、節奏、興趣、預算、住宿、起訖點與指定景點 | 建立微旅行彈窗 | `renderWizard()` 等，位於 `app/ai-travel-explore-final.js` |
| 即時行程預覽 | 依輸入顯示站點與時間；使用者改時間後，只在後站衝突時自動順延 | 精靈右側預覽 | `renderFlowPreview()`、`buildAutoDelayedPreviewTimes()` |
| 禁忌衝突提醒 | 指定景點若與個人禁忌衝突，立即顯示警告 | 精靈的「想去景點」欄位 | `app/avoid-tags.js`、`findDesiredSpotConflicts()` |
| AI 串流生成 | 逐步顯示生成進度與已識別景點；可收起精靈後繼續 | 建立完成後的進度區／浮動進度卡 | `_doGeneration()`、`requestGeminiMicroTravelPlan()` |
| 中斷恢復 | 先保存草稿與生成狀態，重整後可從「我的微旅行」繼續 | 我的微旅行 → 繼續生成 | `wai_pending_gen` 與 `resumePersonalTripGeneration()` |
| 真實景點驗證 | 本地白名單優先，必要時用 Places 驗證名稱、座標與目的地距離 | 生成管線自動執行 | `app/poi-data.js`、`verifyAndFilterStopsWithPlaces()` |
| 路線最佳化 | 最近鄰居法、2-opt、尖峰時段加權、起訖走廊過濾、子景點合併與時間長度修正 | 生成管線自動執行 | `optimizeGeneratedTripStops()` 相關函式 |
| 雨天規劃 | 出發日在預報範圍內時將天氣訊號加入生成條件 | 生成管線自動執行 | `fetchTripRainOutlook()` |
| 預算約束 | 估算門票、餐飲、交通與船票；超出預算時優先改為免費替代點 | 生成與預算頁 | `app/cost-config.js`、`app/attraction-fee-config.js`、`enforceBudgetCap()` |

### 3. 行程編輯器

| 功能 | 使用方式／行為 | 畫面位置 | 主要實作 |
|---|---|---|---|
| 行程總覽 | 查看旅程日期、地區、站點、交通段、營業狀態、距離與時間 | 編輯器 → 行程 | `app/ai-travel-planner-v8.html/.js/.css` |
| 桌機單站抽屜 | 點站點後由右側抽屜修改時間、停留時間與交通；不遮住主要行程卡 | 桌機行程卡 | `openStopEditor()`、`saveStopEditor()` |
| 手機編輯 | 手機採單欄卡片、底部行程／景點／地圖切換與可收合路線 | 手機版編輯器 | `app/ai-travel-planner-v8.css`、`setMobileMode()` |
| 調整順序 | 進入重新規劃模式後拖曳或用按鈕調整站序，再套用或取消 | 行程 → 調整順序 | `enterReplanMode()`、`applyReplan()` |
| 新增／刪除／替換景點 | 搜尋 Places、探索附近景點、從景點牆或地圖替換；刪除前確認 | 行程編輯工具 | `openAddPlaceDialog()`、`openModifyWindow()`、`confirmRemoveStop()` |
| 停留與排程連動 | 修改一站後，後續站只在早於或等於前站時依序順延 | 單站編輯／停留時間調整 | `adjustOverlappingStops()`、`cascadeOverlappingStopTimes()` |
| 分段交通 | 可設定整趟主要交通，也可逐段設定步行、汽車、機車、大眾運輸等 | 行程卡與路線 | `setTripPrimaryVehicle()`、`setSegmentTransitMode()` |
| AI 重新規劃 | 依現況、天氣、偏好與排除景點重生行程；共編時有重生成鎖 | 行程 → 重新規劃 | `replanWithAI()`、`app/collab.js` |
| AI 隨行管家 | 用自然語言新增、刪除、替換、改時間、重排或詢問建議 | 右上角 → AI 隨行管家 | `handleAiSendMessage()`、`applyAiItineraryActions()` |
| 營業時間 | 解析每站營業時間，標記公休或風險，不自行猜測不明資料 | 行程卡／重新規劃 | `app/business-hours.js` |
| Google Maps 路線 | 在 Google Maps 開啟整趟路線，或分享地圖連結 | 行程上方操作列 → 更多 | `openFullTripNavigation()`、`openMapShareDialog()` |
| 網址匯入 | 從支援的地圖／行程網址帶入資料 | 行程 → 更多 → 網址匯入 | `openTravelTools('import')` 相關函式 |
| 匯出行程圖 | 產生行程圖片，可預覽、縮放、下載與分享 | 行程 → 更多 → 匯出行程圖 | travel tools 與 lightbox 相關函式 |

### 4. 地圖、導航與出遊模式

| 功能 | 使用方式／行為 | 畫面位置 | 主要實作 |
|---|---|---|---|
| Google 地圖與分段路線 | 顯示站點、Directions 路線、分段資訊與替代路線；服務失敗時以直線示意 | 編輯器右側地圖 | `calculateAndDisplayRoute()`、`refreshRouteDirections()` |
| 已走路線變灰 | 依 GPS 或展示進度切分線段，走過部分改為灰色；重新整理後保留正式進度 | 地圖路線 | `splitStagePath()`、`persistRouteProgress()` |
| 景點與區域標記 | 顯示站點、子景點與 OSM 景區範圍 | 地圖 | `renderMapMarkersFromCurrentLocations()`、`fetchOsmBoundaryPath()` |
| 廁所資訊 | 顯示行程附近或當前路段的廁所點 | 地圖／景點資訊 | `renderAllToiletMarkers()`、`searchPlacesForToilet()` |
| 停車＋步行銜接 | 從自有回報、社群、內建、TDX、Places 找候選停車場，再估算步行路線 | 自駕路段地圖 | `resolveParkingCoord()`、`pickWalkableParking()` |
| 開始／結束行程 | 將行程切成 ongoing／completed，依序顯示目前站與下一站 | 行程頂部 | `startTripProgress()`、`markTripAsCompleted()` |
| GPS 打卡 | 取得瀏覽器定位，驗證到站後完成打卡；可啟動持續定位顯示目前位置 | 進行中站點卡 | `checkInCurrentStop()`、位置來源相關函式 |
| 外部導航 | 由當前位置導向下一站，交給 Google Maps 導航 | 進行中站點卡 | `openExternalNavigation()`／Google Maps URL 邏輯 |
| 現在景點 | 顯示當前景點詳情、語音導覽、打卡、回憶與評分入口 | 導覽 → 現在景點 | `renderCurrentSpotView()` |
| 語音導覽 | 以瀏覽器語音合成朗讀景點資訊 | 現在景點 → 語音導覽 | `playVoiceGuide()`、`stopVoiceGuide()` |
| 展示模擬 | 虛擬 GPS、虛擬時間、方向控制、速度切換、抵達／照片提醒與展示回顧 | `?demo=1` → 行程 → 展示模擬 | `tripSimulation`、`enableSimulation()`、`createSimulationPanel()` |

### 5. 預算、天氣、旅伴與社群

| 功能 | 使用方式／行為 | 畫面位置 | 主要實作 |
|---|---|---|---|
| 預算追蹤 | 顯示人均預算、門票、餐飲、交通／船票估算與剩餘額 | 編輯器 → 預算 | `renderBudgetTracker()` 與各 cost config |
| API 成本紀錄 | 把一趟生成的 Gemini 與可觀測 API 用量歸到同一 run；不完整時不假裝為 0 元 | 生成流程與管理資料 | `app/cost-tracking.js`、`server/generation-runs.js`、`server/pricing.js` |
| 行程天氣 | 顯示台東旅遊日預報、降雨建議與日期展開 | 編輯器 → 天氣 | `refreshWeatherView()`、CWA 代理 |
| 雨天替代方案 | 篩出戶外站並替換為室內備選，可單站替換或套用整體雨天方案 | 天氣頁／替換面板 | `applyRainPlan()`、`swapStopWithAlternative()` |
| 旅伴與角色 | 顯示 owner、editor、viewer；控制編輯與檢視權限 | 編輯器 → 旅伴 | `renderMembersView()`、`app/collab.js` |
| 邀請碼／分享 | 複製邀請碼、好友一鍵邀請、LINE 分享、分享連結與加入申請 | 探索共編面板、旅伴頁 | `app/collab.js`、後端 `/api/collab/*` |
| 團體偏好彙整 | 節奏多數決、預算平均、興趣票數、禁忌聯集與衝突提示 | 共編面板 | `aggregateGroupProfile()`、`buildCollabAggView()` |
| 即時共編同步 | Firestore snapshot 即時更新；儲存時以 base／local／remote 三方合併避免無聲覆蓋 | 共編行程編輯器 | `startCollabTripLiveSync()`、`mergeCollabStops()` |
| 好友系統 | 搜尋使用者、邀請、接受／拒絕、好友列表與移除 | 首頁 → 好友 | `app/friends.js` |

### 6. 照片、旅記與成果輸出

| 功能 | 使用方式／行為 | 畫面位置 | 主要實作 |
|---|---|---|---|
| 去過狀態與旅記 | 手動標記去過、打卡後自動同步，加入文字備註與旅程評分 | 編輯器 → 旅記 | `renderTravelLog()`、`toggleVisitedPlace()`、feedback 相關函式 |
| 批次照片上傳 | 一次選取多張照片後建立本機佇列，依序上傳，顯示等待／上傳／同步／失敗狀態 | 旅記 → 批次上傳並自動整理 | `app/trip-photo-manager.js` |
| 自動照片排序 | 優先讀 JPEG EXIF 拍攝時間與 GPS；再以行程日期、站點時間窗、距離與檔案時間分組 | 批次上傳流程 | `extractMetadata()`、`classifyPhoto()` |
| 待整理與手動修正 | 缺少資訊或可信度低的照片放入待整理，可改景點分類與手動順序 | 共同行程相簿 | `app/trip-photo-gallery.js` |
| 離線待傳佇列 | Blob 與狀態存 IndexedDB；網路恢復、頁面重現或重新開啟時續傳並重試 | 照片上傳自動處理 | `app/trip-photo-manager.js` |
| 多人相簿 | 所有成員照片以同一正式資料來源即時同步，依日／景點分組並標示上傳者 | 旅記 → 共同行程相簿 | `app/trip-photo-sync.js`、`app/trip-photo-gallery.js` |
| 權限與刪除 | 成員可看共同行程照片；預設只能修改／刪除自己的照片 | 共同行程相簿 | `TripPhotoSync`、`firestore.rules`、`storage.rules` |
| IG 九宮格拼貼 | 選照片、模板、主視覺與小卡，拖曳／縮放／裁切，輸出九張並提示發布順序 | 旅記 → 製作旅程回憶 | Memory Studio，位於 `app/ai-travel-planner-v8.js` |
| AI 版面建議 | 以 Gemini 建議拼貼版面；服務不可用時仍可手動完成 | 回憶工作室 | `memoryAiEdit()` |
| 回顧短片 | 依站點、照片與真實路線生成 MP4；可命名、取得標題／hashtag 建議、預覽、下載與分享 | 回憶工作室 → 旅程回顧短片 | `app/recap-*.js`、`server/recap-*.js` |
| 短片跨裝置保存 | 成品先存 IndexedDB，再上傳 Firebase Storage 並寫入 recap 索引 | 回顧短片完成後 | planner recap 邏輯、Storage `recap-videos/` |
| 回憶概念 Demo | 獨立展示電影旅行、節奏剪輯、旅程紀錄三種風格 | `travel-memory-demo.html` | `app/travel-memory-demo.*` |

### 7. 停車、資料與可靠性

| 功能 | 使用方式／行為 | 畫面位置 | 主要實作 |
|---|---|---|---|
| 記錄停車位置 | 以目前定位或地圖微調位置，加樓層／出口備註，之後可導航回車 | 行程中的停車工具／找車浮條 | planner 停車函式 |
| 社群停車回報 | 回報找到／客滿等停車資訊；後端驗證登入、精度、距離、類型與頻率 | 停車回報視窗 | `POST /api/parking-report`、`docs/停車回報群眾外包-實作計畫.md` |
| 個人回報立即生效 | 自己回報的停車點不必等待社群聚合門檻 | 停車候選自動選取 | `GET /api/parking-reports/mine` |
| 本地資料優先 | 景點、餐廳、停車與票價以靜態資料檔優先，降低延遲與 API 成本 | 全站背景行為 | `app/poi-data.js`、`restaurant-data.js`、`parking-data.js` |
| 資料爬蟲 | 匯入 OpenData、Places 驗證、費用補齊、餐廳／停車爬取與前端資料匯出 | 維運命令列 | `crawler/worker.js`、`crawler/README.md` |
| 後端代理 | 隱藏 Vertex、Places、TDX、CWA 金鑰；驗證 Firebase token 並限流 | `/api/*` | `server/server.js`、`server/README.md` |
| Firestore／Storage 權限 | 保護行程角色、好友、通知、照片、回憶、成本與停車資料 | Firebase Rules | `firestore.rules`、`storage.rules` |
| 響應式與無障礙 | 桌機雙欄、手機底部切換、dialog／ARIA、鍵盤操作、busy／skeleton 回饋 | 兩個主頁 | `app/design-tokens.css`、`app/ui-feedback.js`、主頁 CSS |

## 專案結構

```text
UIUX/
├─ app/                  正式站網站根目錄；HTML、CSS、瀏覽器 JS、靜態資料
├─ server/               Node／Express API 代理、成本統計與短片渲染
├─ crawler/              景點、餐廳、票價與停車資料維護工具
├─ tools/                純邏輯與回歸測試腳本
├─ prototypes/           尚未併入主流程的獨立概念原型
├─ archive/              已退休的歷史原型
├─ docs/                 架構、設計、規劃、schema、稽核與交接文件
├─ test-artifacts/       測試截圖、驗證報告與測試用瀏覽器資料
├─ firestore.rules       Firestore 安全規則
├─ storage.rules         Firebase Storage 安全規則
├─ DEPLOY.md             正式站部署與維運指南
├─ COLLAB.md             多人行程資料與角色說明
└─ AGENTS.md             此 workspace 的開發與驗收規則
```

### `app/` 主要檔案

| 檔案 | 用途 |
|---|---|
| `ai-travel-explore-final.html/.css/.js` | 首頁、探索、旅行精靈、AI 生成、我的行程、好友、多人 lobby |
| `ai-travel-planner-v8.html/.css/.js` | 行程編輯、地圖、出遊、展示、預算、天氣、旅記、回憶 |
| `collab.js` | 多人行程、邀請碼、角色、偏好、重生成鎖 |
| `friends.js`、`notifications.js` | 好友與通知 |
| `trip-photo-manager.js` | EXIF、分類、排序、IndexedDB 佇列與重試 |
| `trip-photo-sync.js` | Firebase Storage／Firestore 照片同步與權限 |
| `trip-photo-gallery.js/.css` | 共同行程相簿 UI |
| `recap-manifest.js`、`recap-render.js`、`recap-route.js`、`recap-camera.js` | 回顧短片共用時間軸與 Canvas 渲染 |
| `poi-data.js`、`restaurant-data.js`、`parking-data.js` | 爬蟲產生的前端本地資料 |
| `business-hours.js` | Explore 與 Planner 共用的營業時間解析器 |
| `cost-config.js`、`attraction-fee-config.js`、`ferry-config.js` | 預算估算設定 |
| `weather.env.js` | 前端執行環境設定；勿提交真實金鑰 |

### 獨立原型與內部頁面

| 位置 | 用途 |
|---|---|
| `app/travel-memory-demo.html` | 旅程回顧短片的互動式視覺概念，不是正式生成器 |
| `app/recap-headless.html` | 後端 Puppeteer 使用的無頭短片渲染頁，不是一般使用者入口 |
| `app/security-audit.html` | 本機安全檢查頁，含測試探測內容，不公開上線 |
| `prototypes/explore-home-redesign.html` | 探索首頁改版概念 |
| `prototypes/gap-mode-prototype.html` | 行程空檔模式概念 |
| `prototypes/parking-record-prototype.html` | 停車記錄流程概念 |
| `archive/` | 已退休版本，只供追溯，不應作為新功能來源 |

### 後端 API 位置

| 路徑 | 用途 | 實作 |
|---|---|---|
| `GET /api/health` | 代理服務健康檢查 | `server/server.js` |
| `/api/vertex/*` | Vertex AI／Gemini 代理與串流 | `server/server.js` |
| `/api/places/:method` | Google Places 代理 | `server/server.js` |
| `/api/tdx/*` | TDX 交通與停車資料代理 | `server/server.js` |
| `/api/cwa/v1/rest/datastore/:dataset` | 中央氣象署資料代理 | `server/server.js` |
| `/api/generation-runs/*` | 建立、綁定、記錄與完成一趟 API 成本統計 | `server/server.js`、`generation-runs.js` |
| `/api/collab/*` | 邀請碼驗證、公開分享與加入申請 | `server/server.js` |
| `/api/parking-report*` | 停車回報、新增、讀取自己的回報與刪除 | `server/server.js` |
| `/api/recap/*` | 建立短片工作、查詢狀態與下載 | `server/recap-jobs.js` |

## 開發與設定

本專案沒有 bundler 或前端 build step。HTML 以普通 `<script>`／`<link>` 載入相鄰檔案，需保持 CDN 與 `file://` 相容，請勿直接改為 ES module。

```powershell
git clone https://github.com/Timlin0929/UIUX.git
cd UIUX
cd server
npm install
Copy-Item .env.example .env
npm start
```

前端設定放在 `app/weather.env.js`，後端私密設定放在 `server/.env`。Firebase、Google Maps、Vertex AI、Places、TDX、CWA 與短片渲染所需設定請參考 [server/README.md](server/README.md) 與 [DEPLOY.md](DEPLOY.md)。不要把 API key、service account 或 `.env` 提交到 Git。

若只看靜態版面，可直接開 HTML；需要 AI、CWA、TDX、停車回報、共編加入驗證或回顧短片時，必須透過正式站或已正確設定反向代理的環境使用。

## 測試

```powershell
node --check app/ai-travel-explore-final.js
node --check app/ai-travel-planner-v8.js
node tools/test-e2e-logic.js
node tools/test-generation-save.js
node tools/test-issue-fixes.js
node tools/test-planner-replan.js
node tools/test-trip-event-access.js
node tools/test-unfinished-trips.js
node server/test/parking-report.test.js
```

正式驗收以 `https://travel-link-ai.duckdns.org/` 為準：先 `Ctrl+F5` 強制重新整理，再走一次受影響的完整使用流程，並確認 DevTools Console 沒有紅色錯誤、`permission-denied` 或 uncaught exception。雙帳號共編測試必須使用兩個不同 Chrome Profile。

## 資料保存位置

- Firestore：行程、共編成員、偏好、照片 metadata、回憶、短片索引、好友、通知、回饋與成本紀錄。
- Firebase Storage：`trip-photos/{uid}/{tripId}/`、`recap-videos/{uid}/{tripId}/`、海報與背景圖。
- localStorage：訪客／帳號隔離的行程快取、去過景點、待恢復生成、OSM 與天氣快取、正式路線進度。
- IndexedDB：待上傳照片 Blob 與已完成的回顧短片。
- 分頁記憶體：展示模擬的 GPS、時間、打卡與完成事件；關閉分頁即消失。

## 已知限制

- 目前內容、天氣、票價、餐廳與停車資料主要集中在台東。
- 交通時間部分仍是估算值，Google Directions 不可用時會退回直線示意。
- 照片離線續傳可在網頁重新上線、回到前景或重新開啟時恢復；專案未註冊 Service Worker，因此關閉瀏覽器後不會由作業系統在背景自行上傳。
- EXIF 自動分類以 JPEG 支援最完整；缺少拍攝時間／GPS 的照片會依檔案時間推測或進入「待整理」。
- 影片渲染在單一 Windows 主機上排隊處理，沒有多機備援。
- Explore 與 Planner 主 JS 仍是大型傳統腳本；新增功能時應優先抽成可由瀏覽器 `<script>` 和 Node `require` 共用的 UMD 模組。

更完整的架構、資料 schema、安全邊界與技術取捨請見 [專題技術摘要](docs/architecture/專題技術摘要.md)。
