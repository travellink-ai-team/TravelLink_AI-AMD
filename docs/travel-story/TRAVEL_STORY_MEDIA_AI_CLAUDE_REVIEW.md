# TravelLink AI 旅程故事輸出：Claude 獨立審查

> 歷史審查紀錄：2026-08-05 後續已將產品收斂為 Instagram 主頁九宮格＋旅程回顧短片；AI 短劇相關建議不再是目前 roadmap，最新範圍以 `TRAVEL_STORY_MEDIA_AI_PLAN.md` 為準。
>
> 最新產品決策亦已移除內建 BGM／曲庫；下方提及 BGM 或授權配樂的文字僅是舊版審查歷史，不代表目前規格。

審查日期：2026-08-05  
審查工具：Claude Code 2.1.220  
審查方式：兩輪唯讀審查；Claude 只能讀取計畫，沒有檔案寫入權限。

## 審查範圍

第一輪聚焦資料模型、多人共編競態、上傳、Firebase 權限、API、工作佇列、刪除與復原。第二輪聚焦 metadata、codec／HDR／音訊、AI 過場與短劇、成本、透明標示、隱私法務、UX、測試與營運。

## Claude 找到的缺口與處理結果

| # | 嚴重度 | 缺口 | 處理結果 |
|---|---|---|---|
| 1 | 高 | 新增集合只有 Storage 說明，缺 Firestore Rules | 已採納：補 `media`、`story_jobs`、`media_jobs` 欄位白名單與後端專屬寫入 |
| 2 | 高 | 上傳與建檔先後順序自相矛盾 | 已採納：統一先建 `uploading` 文件，再上傳並由 finalize 觸發後端工作 |
| 3 | 高 | UID Storage 路徑與共編 owner 管理 editor 素材互斥 | 已採納：改為 trip／media 路徑，實際刪除走後端 |
| 4 | 高 | 媒體掃描與轉碼沒有獨立 job | 調整後採納：新增 `media_jobs`，只由 Storage finalize／後端排程建立 |
| 5 | 高 | timeline 的 `clips[]` 仍會被兩位 editor 整包覆寫 | 已採納：先加 revision transaction，未來再拆 clips 子集合 |
| 6 | 中高 | 軟刪後沒有復原窗口 | 調整後採納：一般誤刪保留 30 天；隱私刪除立即不可逆清理 |
| 7 | 中高 | 生成途中刪除被引用素材會讓 worker 失敗 | 調整後採納：job 固定 media revision，刪除轉待刪，終態後清理 |
| 8 | 中 | idempotency key 未定義作用域與重放語意 | 已採納：定義唯一範圍、24 小時保存、同 payload 回原 job、異 payload 回 422 |
| 9 | 中 | 只管 AI 秒數，未限制儲存與上傳成本 | 已採納：加入總量、每日位元組、素材數與 upload authorization 前檢查 |
| 10 | 中 | 離頁通知只有輪詢，缺回站還原通道 | 調整後採納：MVP 提供 job list＋輪詢；FCM／Web Push 延後且需授權 |
| 11 | 高 | 缺 HEIC／HEIF 與 EXIF Orientation | 已採納：後端轉 JPEG／WebP並套用方向 |
| 12 | 高 | schema 缺 HDR、色彩、幀率與 audio codec | 已採納：補齊正規化判斷欄位 |
| 13 | 高 | 模型結果暫存 URI 可能過期 | 已採納：worker 先下載、掃描、落地自家 Storage 才標 ready |
| 14 | 高 | 缺監控、告警與營運手冊 | 已採納：新增 job、佇列、孤兒、成本、heartbeat 指標與處置流程 |
| 15 | 中高 | 儲存、轉碼 CPU、流量與保留期未納入成本 | 已採納：補 `retentionUntil`、lifecycle 與完整成本模型 |
| 16 | 中高 | 路線鏡頭缺少 reduced-motion | 已採納：預覽與匯出皆提供減少動態版本 |
| 17 | 中高 | prompt injection 與 codec 相容矩陣缺驗收項 | 已採納：補安全回歸測試與 iOS／Android／桌機實測矩陣 |
| 18 | 中 | AI 生成音訊／語音界線不明 | 已採納：第一版關閉生成的人聲對白，只允許環境音與授權配樂 |
| 19 | 中 | 未成年帳號使用付費 AI 的門檻未定 | 已採納：列為上線前法務與產品必要決策 |
| 20 | 中 | 拍攝時間明顯不在行程內仍可能被自動配對 | 已採納：標記 `out_of_range`，必須手動確認 |

## Claude 未直接指出、但整併時額外補強

- 付費 AI 工作預設只能由 owner 或具 `canSpend` 權限者確認，避免 editor 替 owner 產生費用。
- 行程刪除需級聯清理素材、工作、成片、分享 token 與快取；晚到 worker 不得讓資料復活。
- 日誌不得保存完整 prompt、原始 media URL、精確 GPS 或 ID token。
- 公開分享納入權利申訴與侵權處理，不能只有一般內容檢舉。

## 最終判斷

Claude 的兩輪審查沒有推翻方案 1＋2、AI 過場或獨立 AI 創意短劇的產品方向；主要補強點集中在「如何可靠地上傳、正規化、多人共編、排隊、計費、刪除與營運」。所有有效建議都已直接或調整後合併至主計畫，沒有讓 Claude 直接修改專案或文件。

## 合併後封口複查

Claude 對更新後的主計畫再做一次只限 P0／P1 的唯讀複查，找到並完成以下修正：

1. Storage 的 `trips/{tripId}` 是 object 路徑，Rules 授權查詢必須明確指向 Firestore `micro_trips/{tripId}`。
2. 30 天最近刪除期間，`retentionUntil` 與 lifecycle 不得提前清掉可復原素材。
3. 公開成片使用可撤銷 share ID 換取短效簽章 URL，與私有原始素材隔離，不依賴永久 Storage token。
4. 固定非寫實過場移至 Phase 2，Phase 3 才加入 AI 導演與付費生成，讓 MVP 清單與實作階段一致。

完成上述修正後，沒有留下已知的 P0／P1 計畫矛盾。

## Android 組員意見後的限縮審查

主計畫新增 Phase -1、產品門檻、手機剪輯器、BGM、分享、Web／Android 離線分流、timeline schema 與 AI 短劇拆檔後，Claude 僅在指定範圍內複查。提出並已修正：Storage create 的可執行 Rules 條件、離線 local draft 轉正式 media、retry 冪等、`timeRangeFlag`、30fps／33ms 容差、trim 與 duration 權威來源，以及 `contentHash` 不合併實體檔案。

AI 短劇已移至 [TRAVEL_STORY_AI_DRAMA_APPENDIX.md](TRAVEL_STORY_AI_DRAMA_APPENDIX.md)，不再阻擋核心 Phase -1～2。

最後一次唯讀核對僅檢查上述 7 項，不開新議題；Claude 回覆 **7／7 已關閉**。

## App 端評估與九宮格後的限縮審查

新增穩定 stopId／memories migration、Android 合成基準、MediaStore、跨端 API，以及 Instagram 主頁九宮格後，Claude 僅檢查指定範圍。提出的 8 項修正包括 photo assetRef、memories re-key、所有端 stopId、私有照片 CORS／`getBlob()`、API 26～28 fallback、`listStoryJobs`、正式 MP4 後端 renderer 與 uploading TTL。修正後再次核對，結果為 **8／8 已關閉**。

組員位於 Downloads 的原始評估檔沒有傳給 Claude；Claude 只讀取專案內已整理的計畫文件。
