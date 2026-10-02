# 正式站修復驗收（2026-09-21）

環境：`https://travel-link-ai.duckdns.org/`。使用者確認已更新 Rules 並重啟 server，本輪以實際請求驗證；依使用者決定只測單帳號，雙 Chrome Profile 留待後續。

## User Flow Test Result

### Summary

**單帳號本輪測試全部通過。** 不是僅跑本機模擬：正式站 Chrome 操作配合真實後端／Firestore Rules 請求，並由管理端讀回測試資料確認落地。

| 測試 | 結果 |
|---|---|
| 正式站 `/api/health` | HTTP 200，ok=true |
| 共編 editor 成員面板 → 儲存自己的偏好 | 已儲存、已同步，身分／角色顯示正確 |
| UI 回饋正常流程 | 未選兩項評分時送出 disabled；選 4 星／4 星與測試文字後可送出 |
| UI 回饋落地與重新整理 | Firestore 確認 1 筆、4 星／4 星；重新載入後原文及星等由自己的本機紀錄回填 |
| UI 修改回饋再送出 | 改 5 星，Firestore 同一筆更新為 5 星／4 星，沒有重複新增 |
| 擁有者回饋權限 | 客戶端提交、更新成功；讀自己的回饋也被拒絕（符合只給管理者看的決策） |
| viewer 回饋權限 | 同一測試帳號在專用 fixture 中作 viewer，提交成功、讀取拒絕 |
| 頂層 feedback | 客戶端可提交合法資料、不能讀取原文 |
| legacy editor 碰撞 | key 相同但完整 email 不同時改標題被拒；改回符合的完整 email 後可編輯 |
| 滿額申請、owner 缺漏 | owner 不在陣列、既有 2 人、上限 3（含 owner）→ HTTP 409，沒有建立 pending |
| 後端加入碰撞 | requester 與 owner key 碰撞 → HTTP 409，明確識別衝突提示 |
| 停車分類真實 API | unknown／roadside／lot 都 HTTP 200；Firestore 與 `/parking-reports/mine` 讀回相同類型 |
| 停車非法類型 | HTTP 400；既有合法回報未被覆寫 |
| 停車 UI 邊界 | 未選狀況送出有提示；found 才顯示三類型，預設不確定 |
| 停車 UI 狀態 | roadside → full → found 保留類型與備註；取消重開回初始狀態 |
| Console | 正常 Chrome 操作捕捉 error=0；只有 Google Maps 的既有 deprecation warnings |

API 探測共 11 個通過項目（含 health），另有清理確認；預期拒絕的 403／409／400 是獨立 API 負向測試，不是正常 UI 操作噴錯。

### Issues Found

本輪單帳號範圍沒有發現新的功能錯誤。先前 Rules／後端未發布的缺口已由上述真實行為證實生效。

### Fixed

本輪沒有修改應用程式、Rules 或伺服器。新增可重跑的 `tools/test-live-issues.js` 與驗收紀錄；更新 issue 清單及協作文件的部署狀態。

資料處理：API 使用獨立、帶本輪 prefix 的人工 fixtures，9 個測試文件已清除；停車使用人工座標，測前確認不覆寫此帳號既有回報。UI 另建立專用行程，驗證後已清除雲端主文件與回饋。原本的使用者行程未用來提交測試回饋。既有共編測試行程僅儲存當前偏好，沒有變更其內容。瀏覽器已返回原共編行程。

### Not Tested / Risk

- 依使用者決定：尚未測兩個獨立 Chrome Profiles 的新成員加入、owner 接受申請、升降角色、退出重加與即時同步。
- API fixture 的 owner／viewer 是同一實際測試帳號在不同資料中的角色，不是雙帳號實機驗收。
- 停車已驗證 UI 分支與真實 API 寫入／讀回，但未透過瀏覽器取得及上傳真實 GPS；不宣稱已完成戶外定位／地理圍籬完整流程。
- 未測 Android、AI 生成、影片輸出；I-02 相容寫入風險仍依使用者決定保留。
- 管理者讀回使用受信任 Admin SDK，管理介面尚未建立。仍未盤點歷史主文件 feedback map。
- 瀏覽器的測試回饋本機草稿可能仍保留於測試帳號專屬 key；雲端專用行程與回饋已刪除，不影響其他帳號。

證據：`issue-live-2026-09-21.json`（API assertion 與 cleanup）、`issue-ui-fixture-2026-09-21.json`（UI 保存與 cleanup）。

## 額外發現

本輪沒有新增 issue。前輪記錄的分享碼讀取相容分支、歷史 feedback map 與 pending 接受的冪等性，仍保留原追蹤狀態。
