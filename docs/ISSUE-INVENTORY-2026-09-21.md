# Issue 修復與後續清單（2026-09-21）

## 最新狀態：已部署後的單帳號驗收

使用者已發布新版 Rules 並重啟 server；2026-09-21 正式站實際驗證通過：回饋可提交／更新但客戶端不可讀、legacy editor 完整 email 核對、後端滿額包含 owner、加入碰撞拒絕，以及三種停車分類的寫入／讀回。Chrome 完整回饋提交、重整回填、修改重送與停車表單狀態通過，正常操作 Console error=0。

**I-01／I-03／I-04／I-05 的單帳號可測部分已驗證線上生效；I-02 仍保留相容風險；I-06 文件已更新。** 雙帳號流程依使用者決定延後，不宣稱全面驗收完成。完整紀錄見 [正式站驗收](../test-artifacts/issue-live-verification-2026-09-21.md)。本輪 API fixtures 與 UI 專用行程／回饋均已清除。

## 初次修正紀錄（部署前歷史）

以下保留前輪的未發布狀態與測試限制供追溯；其中「尚未重啟／未發布」已由上方最新驗收取代，不能再當成目前阻塞。

以本次工作目錄為準。使用者核定六項處理方向；本輪改程式與文件，尚未重啟正式站 Node 後端或發布 Firestore Rules。靜態檔已由正式站提供；不要將前端已更新等同全部上線。

## 六項處理狀態

| ID | 問題 | 本輪處理 | 狀態／剩餘條件 |
|---|---|---|---|
| I-01 / P1 | 共編 email key 碰撞 | Web 加入／角色／偏好操作檢查完整 email；交易中阻止覆寫。Rules legacy editor、自改偏好、退出加入核對完整 email。兩頁角色／本人顯示不再只比 key，也不信任快取 role 升權。後端驗證／申請／接受均擋碰撞。 | 程式完成、VM 回歸通過；待後端重啟、Rules 發布與雙帳號線上驗收。完整遷移延後。 |
| I-02 / P1 | 景點與營業時間任一登入者可寫 | 依使用者決定保留較大 Android 相容性。確認 Web 不提供景點修改 UI、也停止直接寫回 scenic_points；Android 評分聚合與 hours_cache 仍依賴寫入。 | 接受現有風險、延後修復，不能標為已解。日後改後端寫入。 |
| I-03 / P1 | 回饋原文讀取過廣 | 頂層 feedback、行程 feedback 子集合改為客戶端不可讀；管理者暫由 Firebase Console／受信任 Admin SDK 查看。移除 Web 大家的回饋查詢，草稿按 UID 隔離。 | 程式完成、前端正式站已驗；Rules 未發布前不能宣稱資料權限已收緊。Android 舊查詢需配合停用。 |
| I-04 / P2 | 加入與接受的人數算法不同 | 申請／接受／邀請碼使用含 owner 的去重人數，接受／加入 transaction 內重驗；Rules 也把 owner 計入上限。 | 程式完成、正常／滿額／重複／owner 缺漏測試通過；待發布與雙帳號驗收。 |
| I-05 / P3 | 停車回報固定 lot | 選到 found 才顯示停車場／路邊停車格／不確定；類型保留至標記、回報、本機／行程資料、API payload；後端接受 unknown，保留舊 temp；舊缺值不猜測分類。 | 前端與後端程式完成，正式站選單已驗。後端需重啟才接受 unknown；尚未真實上傳停車座標。 |
| I-06 / P2 | 文件過期 | COLLAB.md 更新目前角色、分享、加入與相容限制；codex-review.md 加歷史標示；FEEDBACK_SCHEMA.md 修正權限、本機鍵與 map／子集合矛盾。 | 完成。歷史審查內容保留，不改寫當年的結論。 |

## 日後補齊

1. **完整身分遷移**：Web、Android、後端、Rules 及舊資料改 UID／無碰撞 key；盤點 members、member_prefs、presence、feedback 等 legacy key。先盤點與備份，不自動刪除歷史資料。
2. **公共資料僅後端可寫**：Android 的景點評分聚合、營業時間快取改後端端點，再關閉 scenic_points／hours_cache 客戶端寫入。現在沒有編輯 UI，不代表資料庫不能被直接呼叫修改。
3. **回饋管理頁**：管理者身分由後端配置；唯讀列表、日期／行程篩選、詳情，整合 Web 與 Android 格式。沒有管理員自我授權入口，不用單純隱藏按鈕當權限控管。

## 上線與驗收條件

- 更新 Node 代理並核對正式站 `/api/health`，讓新碰撞／容量檢查及 unknown 類型生效。未更新後端時，unknown 的社群回報會被舊 API 拒絕；前端會提示社群送出失敗，不假報成功。
- 對齊 Android：停用一般客戶端讀取回饋原文；保留提交。推薦／個人化改讀後端去識別統計。
- 核對目標 Firebase 專案及線上完整 Rules，再發布。本 repo 沒有 `.firebaserc`／`firebase.json`；本轮未覆蓋線上規則。
- 本輪嘗試用既有服務帳戶呼叫官方 Rules `projects:test`（僅編譯，不發布），官方回 403 `The caller does not have permission`。這是服務帳戶 IAM 權限不足，不是編譯通過，也不是自動審批拒絕。需具備 Rules 測試／發布權限的部署身分完成。
- 本機未找到 Java／Firestore Emulator；未執行 Rules emulator 整合測試。VM 測試不模擬 Rules 引擎，不可當作權限發布驗收。
- 兩個獨立 Chrome Profiles 重跑共編與權限測試，確認正常成員操作、碰撞帳號、滿額、重複申請、一般帳號禁止讀回饋、本人仍可提交，Console 零紅字。

## User Flow Test Result

### Summary

- `node tools/test-e2e-logic.js`：94 項通過、0 失敗。
- `node --test tools/test-issue-fixes.js`：13 組通過、0 失敗，直接執行真實 Web 函式與 Express handler，資料庫／HTTP 以記憶體 I/O 替代。
- 四個修改的 JS 通過 `node --check`；差異檢查通過。
- 正式站 Chrome Ctrl+F5，既有測試帳號／測試行程：旅記 → 評分，初始送出 disabled，選星後 enabled，輸入草稿可保留，沒有大家的回饋列表；未提交測試回饋。
- 正式站停車回報：未選狀況送出有提示；found 才顯示三種分類；選 roadside 後切 full 再回 found，分類與備註保留；取消再開回初始狀態。375px 寬度檢查文字與選單；未上傳真實停車座標。
- 測試為開啟停車入口曾把既有測試行程單一路段由走路改機車，結束已還原走路，時間恢復 09:00–11:29。暫時 viewport 已重設。
- 本輪上述正式站流程 Console 捕捉紅色錯誤為 0；Google Maps 警告不視為錯誤。

### Issues Found

- 原問題觸發與修法：I-01 碰撞 email 加入／調角色可對到同 key；現改阻止寫入，完整 email 符合才使用 legacy 角色。I-04 owner 不在 memberEmails 且接近上限，原申請與接受口徑不一；現統一計數並在接受時重驗。
- 回饋草稿共用 key：A 留草稿後 B 開同一行程可能看到 A 的內容。預期只看到自己的草稿；現按 Firebase UID 儲存，不讀取無法辨識歸屬的舊鍵，回歸通過。
- 本輪新增的退出碰撞分支檢查出空 members map 的 Firestore merge 風險：已改為無自己的項目時省略 members 欄位，保留他人成員。測試模擬器也對齊空 map 覆寫語意。

### Fixed

- `app/collab.js`、兩頁主 JS：身分／角色／偏好防碰撞、加入交易、回饋讀取移除與草稿隔離、停車類型狀態流。
- `app/ai-travel-planner-v8.html`、`.css`：條件式類型選單及中文不換行。
- `server/server.js`：碰撞阻擋、含 owner 去重容量、unknown 類型。
- `firestore.rules`：完整 email 比對、含 owner 容量、回饋原文客戶端不可讀。
- 文件及 `tools/test-issue-fixes.js` 如上。保留本輪開始前的使用者未提交變更。

### Not Tested / Risk

後端與 Rules 尚未發布；沒有完成新 Rules 引擎測試、雙 Profile 真實協作寫入、Android、真正回饋提交、真正停車上傳、AI 生成。沒有把模擬 I/O 通過當作正式站整合成功。本輪不涉及排程時間算法，未重跑整套手動調時矩陣。

## 額外發現（原六項以外）

1. **已修：本機回饋草稿跨帳號共用**，如上。
2. **待跨端修：分享碼行程仍可被任一登入者讀取**。Rules 的非空 inviteCode／joinPin 讀取相容分支，以及 inviteCodeActive／joinPinValid 加入分支仍在。需協調 Android 改走後端／proof；本輪不擅自切斷相容路徑。
3. **待確認：歷史主文件 feedback map**。舊 schema 曾存原文在可被同行者讀的主文件；本次關閉回饋子集合與頂層集合讀取不會保護這些舊欄位。未查線上是否仍存在，需另做資料盤點／遷移。
4. **待修：舊 pending 申請在另一入口已加入後再被接受**，接受分支仍會將 requester role 寫為 viewer；可能把後來授予的 editor map 角色重設。程式碼觀察，未正式站重現，另排保留既有成員角色的冪等處理。
