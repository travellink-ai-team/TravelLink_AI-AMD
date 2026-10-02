# 生成錯誤歷史比對（2026-09-21）

範圍：本機 Git 歷史、HEAD `9b6d1f9` 與目前工作目錄。未切換版本、未覆寫使用者資料、未重跑付費生成。不能由 Git 推定每個歷史版本實際部署的環境設定。

| 問題 | 歷史證據 | 結論 |
|---|---|---|
| Places 503 | `4a03abe`（2026-07-28）新增只讀 GOOGLE_MAPS_SERVER_KEY 的代理與缺值 503；`9d91401`（同日）前端切到代理。HEAD 仍如此。現有 server/.env 僅定義 GOOGLE_MAPS_API_KEY，且 .env 不在 Git 追蹤內。 | 修正前已存在「設定名稱不符就 503」路徑；無法確認歷史環境何時改變或過去是否曾配置正確。 |
| Firestore undefined | `1eedb15`（2026-09-03）補景點候選加入 dayIndex: Number(s.dayIndex) \|\| undefined。HEAD 存檔原樣傳 stops。`ignoreUndefinedProperties` 可追溯至 `f8a4ec8`（2026-06-16），設定異常會被 catch 忽略。 | undefined 的來源與缺乏存檔清理都早於本轮修改；只有忽略設定未生效等情況才會造成 SDK 拒收。截圖未顯示確切欄位，dayIndex 是確認存在的候選來源，不能宣稱就是該次唯一來源。 |
| bind-trip 404 | `9d91401`（2026-07-28）呼叫非同步背景存檔後立即 completeRun；後端 `4a03abe` 對不存在的 trip 回 404。cost-tracking 已有有限次重試。 | 先綁定、後存好與存檔永久失敗後仍綁定的路徑早已存在。該次 404 回應 body 未取得，個別請求也可能是 run 已過期。 |

## 前後對照驗證

在 VM 執行 HEAD 與目前的實際 saveMicroTripToFirebase，使用同一份 `stops: [{name:'test', dayIndex:undefined}]`；I/O 以拒絕 undefined 的模擬寫入器取代。HEAD 回傳 false、0 次成功寫入；目前程式回傳 true、1 次成功寫入。這是條件式回歸驗證，不是舊版部署或真實 Firebase SDK 的重播。

## Rules 與本輪修改

本輪 Rules 差異集中於成員完整 email 核對、人數算法、回饋讀取限制。個人行程 create 條件未改；SDK 的 unsupported undefined 發生在 Rules 評估前。Places 503 也不是 Firestore Rules 產生。不能因此推論所有尚未測試的 Rules 路徑都正常。

最近加入的草稿重試沿用既有 _doGeneration 與存檔流程，會再次遇到上述舊缺口；前一輪修正沒有捕捉完整生成與儲存流程，驗證範圍不足。

## 尚未完成的驗證

- 新版後端環境變數相容修正需要重啟 server；本次歷史查核未重啟。
- 修正後完整新生成、重新生成與雲端儲存尚未完成正式站實機驗收。
- .env 沒有 Git 歷史，不能判定部署設定改變日期。
