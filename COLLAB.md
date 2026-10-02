# 多人共同建立行程

> 更新：2026-09-21。使用者已重啟後端並發布 Rules，單帳號正式站驗收已通過，雙帳號驗收依使用者決定延後。詳見 [issue 清單](docs/ISSUE-INVENTORY-2026-09-21.md) 與 [正式站驗收](test-artifacts/issue-live-verification-2026-09-21.md)。

## 功能與角色

- owner 建立行程、管理角色、生成團體行程；editor 可修改允許的行程內容；viewer 可檢視及提交自己的偏好／回饋。
- owner 由 `ownerUid`、`ownerEmail`、舊個人行程的 `userEmail` 判定。
- editor 由 `editorEmails` 判定，或使用舊 `members[emailKey]` 的 editor 角色；舊角色必須同時核對完整 email，不能僅憑 key。
- 團體偏好採興趣聯集、預算平均、禁忌聯集；節奏多數決，平手以 owner 偏好為準，缺少 owner 偏好才選較放鬆者。
- 預設上限 10 人，包含 owner。申請、接受申請及邀請碼入口都要算 owner 與成員的聯集；接受／加入在 transaction 中重驗名額。
- 內容同步、重新生成鎖仍沿用原實作；不要把多人同步解讀為所有欄位都有無衝突合併。

## 檔案

| 檔案 | 責任 |
|---|---|
| [app/collab.js](app/collab.js) | 邀請碼加入、角色與偏好、成員退出、群體偏好彙整 |
| [app/ai-travel-explore-final.js](app/ai-travel-explore-final.js) | 建立行程、成員面板、我的行程 |
| [app/ai-travel-planner-v8.js](app/ai-travel-planner-v8.js) | 行程內容、即時狀態、角色顯示、唯讀限制 |
| [server/server.js](server/server.js) | 邀請驗證、訪客分享、加入申請與處理 |
| [firestore.rules](firestore.rules) | 資料庫讀寫權限；後端 Admin SDK 另由端點自行驗證 |

## 資料與識別

```text
micro_trips/{tripId}
  ownerUid, ownerEmail, ownerName, userEmail
  collab, inviteCode, shareToken, maxMembers
  memberEmails: [email, ...]
  editorEmails: [email, ...]
  members: { legacyEmailKey: { email, name, role, ready, prefs, joinedAt } }
  member_prefs/{legacyEmailKey}
  join_proofs/{uid}
  join_requests/{uid}
invites/{CODE}: { tripId, active, createdBy, createdAt }
```

`legacyEmailKey` 仍是 email 小寫後將非英數轉為底線，存在碰撞；不是唯一身分證明。本輪保留 schema 相容性，加入、角色調整及偏好儲存先檢查完整 email，衝突時停止寫入並提示。退出不刪除屬於其他 email 的成員項目。Rules 的 legacy editor、自改偏好及退出也核對完整 email。

**日後補齊**：Web、Android、後端、Rules 與舊資料一起遷移 UID／無碰撞 key。現有防護不會自動修復歷史碰撞資料。

## 加入與分享路徑

1. **邀請碼**：Web 呼叫 `POST /api/collab/invites/verify`，後端驗證有效邀請及容量／身分，簽發兩分鐘 join proof；前端 transaction 加入 viewer。Rules 再限制可變欄位。
2. **唯讀分享**：`?sharedId=...&token=...&guest=1` 透過 `GET /api/collab/public-trip` 取得內容。後端驗證 token，只回傳去敏欄位，排除 email、members、inviteCode。不是依賴前端比對 token 來保護整份文件。
3. **分享頁申請加入**：登入後 `POST /api/collab/join-requests` 建立 pending；owner 由 `POST /api/collab/join-requests/resolve` 接受／拒絕。接受時重驗容量與 key 衝突，避免申請後狀態已改變。
4. **回饋**：一般網站帳號可提交自己的回饋，不可讀取回饋集合。管理者暫由 Firebase Console／受信任 Admin SDK 查看；另做管理頁列入後續。

## Rules 現況與相容限制

- owner、editor、自己加入／退出／偏好更新各有獨立條件；不能將 `collab == true` 當作任一登入者可寫的通行證。
- editor 更新有欄位白名單；客戶端不得寫入權威成本欄位。
- **尚存的 Android 相容風險**：具有非空 `inviteCode`／`joinPin` 的行程，Rules 仍允許登入者讀取；加入也保留 `inviteCodeActive`／`joinPinValid` 路徑。不可宣稱所有客戶端只靠 proof 存取。另列於本輪額外發現，待跨端改接後端再收緊。
- `scenic_points`／`hours_cache` 暫保留登入者寫入，以相容 Android 評分聚合與快取。Web 不提供一般景點編輯 UI，但 Rules 層的直接寫入風險仍存在。
- 原文回饋停用讀取會影響 Android 舊版的個人化回饋查詢；部署前需同步停用該查詢。回饋提交 schema 仍保留。

## 部署與驗收

靜態檔會立即由 nginx 提供；瀏覽器需 Ctrl+F5。Node 後端修改需重啟服務，Rules 修改需另行發布。不要以「頁面已變」推定權限也已生效。

本 repo 未附 `.firebaserc` 或 `firebase.json`，部署者須先核對 Firebase 專案與目前線上完整規則。不得僅為排除 permission-denied 而加回任一登入者可修改共編文件的條件。

本機回歸：`node tools/test-e2e-logic.js`、`node --test tools/test-issue-fixes.js`。VM 測試執行真實函式／端點並替換 I/O，不等於 Firestore Rules 整合測試。

正式站雙帳號驗收使用兩個獨立 Chrome Profiles：建立 → 邀請 → 加入 → 填偏好 → 調 editor/viewer → 退出重加；另驗 owner 未在陣列、人數已滿、key 碰撞與訪客分享。每條路徑確認 Console 零紅字。發布後再驗一般成員不能讀回饋、仍可提交自己的回饋。
