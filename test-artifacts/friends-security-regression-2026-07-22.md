# 好友、通知與登入政策修復回歸報告

測試日期：2026-07-22  
正式站：`https://travel-link-ai.duckdns.org/`  
前端版本：`20260722-friends2`

## 本次修復

1. 共編邀請 API 不再要求 `email_verified`，只要求有效 Firebase 登入與 email。
2. 新增無碰撞 `identityKey`，好友文件、好友搜尋索引及通知收件箱不再使用可能碰撞的 legacy `emailKey`。
3. `user_profiles` 改為僅本人可讀；好友搜尋改走只允許精確 `get`、禁止 list/query 的 `user_profiles_by_email`。
4. 共編通知會顯示邀請碼，點擊後會將邀請碼帶入加入視窗。
5. friendship 新增 `cycleId`，解除後再次邀請會使用新的通知 ID。
6. 好友動態只使用 friendship 的 `acceptedAt` 顯示好友成立，不再與 `friend_accept` 通知重複。
7. 通知寫入只有成功後才標記 session 已送出；短暫失敗可再次重試。
8. 修復好友邀請建立前讀取不存在文件，以及 async event `currentTarget` 變 null 的問題。
9. 加好友視窗新增 44×44 px 關閉鍵、Esc 關閉與焦點返回。

## Chrome Profile A

帳號：`tim.lin.930929@gmail.com`

- 好友搜尋成功。
- Profile B 接受後，好友狀態即時由 pending 變為 accepted。
- 好友數為 1，好友成立動態只有一筆。
- 對已成立好友再次送出邀請，正確顯示「你們已經是好友了。」
- 共編邀請訊息與剪貼簿邀請碼 `ZJGY-REP6` 正確。
- Console 紅字：0。

## Chrome Profile B

帳號：`11211217@gm.nttu.edu.tw`

- 收到好友邀請，接受／拒絕按鈕皆正常。
- 實際接受成功，Toast 正常。
- 接受後好友數 1、pending 0。
- Hard Refresh 後好友關係仍保留。
- 好友動態重整前後均只有一筆，沒有重複。
- Console 紅字：0。

## Blocked / Deployment Required

- 新版 `firestore.rules` 尚未部署，因此新的 notification `identityKey` 路徑仍被正式站舊 Rules 擋下；雙方通知面板皆顯示「目前沒有通知」。
- `server/server.js` 修改後需確認 Node proxy 已重新啟動，才能驗證未驗證 Email 帳號加入共編不再回 403。
- 兩個現有 Chrome Profile 都是已驗證／Google 帳號，沒有可用的未驗證 Email 測試帳號。

部署新版 Rules 並重新啟動 Node proxy 後，需補測：

1. Profile A 再送一次共編邀請。
2. Profile B 收到包含邀請碼的通知。
3. 點擊通知後自動預填邀請碼。
4. 使用未驗證 Email／密碼帳號呼叫共編邀請碼流程。

## 靜態驗證

- `node --check`：探索頁、Planner、collab、friends、notifications、server 全數通過。
- `git diff --check`：通過。
- 碰撞測試：`a.b@example.com` 與 `a_b@example.com` 產生不同 identity key。
- 通知重試測試：第一次模擬離線失敗後，第二次寫入成功。
