# 正式站實測截圖（2026-11-03，travel-link-amd.duckdns.org，後端 3011 已重啟為 C/D/E 合併版）

| 檔案 | 內容 | 實測數據 |
|---|---|---|
| C1_agent_plan_ready.jpg | 一日遊 Agent 生成完成（生成精靈 → 行程就緒） | `/api/agent/plan` 3.2 秒 |
| C2_agent_plan_trip.jpg | Agent 產出的行程：台東車站出發，7 段路線，09:00–16:24 | — |
| D1_proactive_banner_full.jpg／_zoom.png | 主動提醒列：「13:00 起降雨 80%，管家有建議」（展示模擬降雨） | `/api/agent/precheck` 34 ms |
| D2_replan_proposal_full.jpg／_zoom.png | 按「看建議」後 AMD 代理人的建議卡：3 個戶外站換成室內 | `/api/agent/replan` 3.1 秒 |
| E1_recap_title_amd_full.jpg／_zoom.png | 回顧短片「✨ 幫我想」：AMD 產生標題與 hashtag，附「AMD gpt-oss-120b」標示 | `/api/ai/task` 0.47 秒 |

## C 的 Agent 步驟（前端實際記錄，從按下建立行程起算）
```
6.8s 🤖 AI 規劃代理人啟動中…
7.0s 🤖 讀取你的旅遊條件
7.1s 🤖 查詢旅遊日天氣
7.4s 🤖 搜尋自然類景點
7.6s 🤖 搜尋「卑南遺址公園」附近餐廳
8.1s 🤖 排順序、算車程並檢查（5 站）
8.1s 🤖 檢查到問題，AI 正在修改行程…
8.6s 🤖 排順序、算車程並檢查（6 站）
10.0s 🤖 整理最終行程
```
進度步驟只顯示數秒，畫面截圖抓不到；海報可直接用上面的紀錄重繪。
