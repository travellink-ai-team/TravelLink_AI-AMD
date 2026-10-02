# Firestore Rules 正式站功能測試紀錄

- 測試日期：2026-07-16
- 測試環境：`https://travel-link-ai.duckdns.org/`
- 測試方式：正式站 Hard Refresh 後，以兩個已登入帳號及一個未登入環境進行實機操作
- Owner：林泓廷
- Editor：Admin
- 匿名訪客：唯讀分享連結
- 測試範圍：共編同步、角色權限、邀請碼、分享連結、功能分頁、行程回饋與 Console
- 程式修改：無

## User Flow Test Result

### Summary

結論：**新 Rules 尚未全部正常**。主要共編寫入可運作，但仍有數項規則與前端整合問題。

### 通過項目

- Owner／editor 可讀取同一行程。
- Editor 單獨修改可以同步。
- 雙方同時修改不同景點，兩筆變更都保留。
- 雙方同時修改交通工具，重新整理後兩端會收斂成同一結果。
- 雙方同時改名，最終名稱一致，沒有資料分裂。
- Owner 可將 editor 降為 viewer，再恢復 editor。
- Viewer 的越權寫入沒有進入 Firestore。
- Editor 可以送出行程回饋，重新載入後確認已持久化。
- 預算、旅伴、天氣、旅記、說明與行程頁都可載入。
- 預算及主要行程資料兩端一致。
- 無效邀請碼會顯示「找不到邀請碼，或邀請碼已失效」。
- 正確唯讀分享連結可讀取行程。
- 錯誤分享 token 會顯示「分享連結無效」。

### Issues Found

#### 1. 有效邀請碼無法加入

Owner 畫面顯示邀請碼 `QJW9-ASGZ` 仍啟用，但 editor 分別輸入以下兩種格式，都被判定為邀請碼已失效：

- `QJW9-ASGZ`
- `QJW9ASGZ`

#### 2. Owner 的「我的微旅行」仍有權限錯誤

林泓廷帳號 Hard Refresh 後出現：

```text
Failed to sync trips from Firebase:
Missing or insufficient permissions.
```

Admin 端本輪未出現相同錯誤，表示前一版只改善了一部分查詢。

#### 3. 共編寫入仍產生 `failed-precondition`

兩端 Console 共出現多次：

```text
Firestore Commit failed-precondition
```

最終資料雖成功收斂，但仍不符合零 Firestore 錯誤驗收。高延遲或重度同時操作時，仍存在修改遺失風險。

#### 4. 匿名分享頁的座標讀取被 Rules 擋住

匿名開啟有效分享連結時，重複出現：

```text
讀取景點座標失敗：
Missing or insufficient permissions.
```

行程仍能顯示，但部分景點或地圖資料可能不完整。

#### 5. 唯讀 UI 仍顯示編輯控制

- Viewer：停留時間按鈕已隱藏，但主要交通工具仍可操作。
- 匿名訪客：交通工具、景點「調整」及「調整順序」仍顯示。
- 操作後本機畫面會暫時變動，但 Rules 有阻止寫入，Owner 資料沒有改變。

這是前端權限 UI 問題，目前未發現資料越權成功。

#### 6. 錯誤分享 token 會產生額外 JavaScript 錯誤

畫面正確顯示「分享連結無效」，但 Console 同時出現：

```text
InvalidValueError:
Map expected mapDiv HTMLElement but received null
```

推測錯誤頁顯示後，程式仍繼續初始化 Google Maps。

#### 7. 回饋送出缺少成功狀態

Editor 回饋實際已成功儲存，但送出後：

- 對話框沒有關閉。
- 沒有成功提示。
- 畫面當下仍像尚未完成。
- 重新載入後才確認回饋存在。

### 測試資料狀態

測試結束後已恢復：

- 行程名稱：`編輯者同時命名`
- 主要交通工具：汽車
- 前兩個景點停留時間：30 分鐘
- Admin 權限：editor

測試期間新增了一筆回饋：`Rules 實機測試回饋`。

### Not Tested / Risk

為避免破壞目前共編行程，本輪未執行以下具破壞性或大幅改變資料的流程：

- 刪除行程
- Owner 停用邀請碼
- 成員離開後重新加入
- 重新生成整份團體行程

本輪只進行功能測試，沒有修改任何程式碼或 Firestore Rules。
