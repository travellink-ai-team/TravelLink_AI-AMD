# TravelLink AI 文件索引

這裡只存文件；正式站執行檔都在 `app/`。若要了解功能與使用方式，先看根目錄的 [README](../README.md)。

## 架構

- [專題技術摘要](architecture/專題技術摘要.md)：系統架構、技術棧、核心演算法、資料 schema、安全邊界與已知限制。
- [網頁端 ↔ Android 跨端契約](architecture/APP_CROSSEND_CONTRACT.md)：照片欄位白名單、Storage 路徑、共編權限、explore_templates 耦合與待確認事項。
- [回饋 Schema](FEEDBACK_SCHEMA.md)：Web 與 App 的旅程回饋資料格式。
- [多人共同建立行程](../COLLAB.md)：角色、邀請、分享與 Rules 相容性。

## 設計與 UI/UX

- [現場展示流程手冊](展示流程手冊.md)：建立、編輯、虛擬出遊、照片與回顧的操作順序及備援。
- UI/UX 修改建議 `docs/UIUX修改建議.md`：目前介面問題與改善方向。**本機文件，未進版控**（clone 不會有這個檔案）。
- 早期 UI/UX 建議 `docs/design/uiux-recommendations.md`：初期檢視與設計備忘。**本機文件，未進版控**。

## 實作計畫

- 網頁端 Week 2 起詳細排程 `docs/planning/TravelLink_AI_網頁端排程_Week2起詳細展開.md`——**本機文件，未進版控**
- 雙端同步開發排程 `docs/planning/TravelLink_AI_雙端同步開發排程.md`——**本機文件，未進版控**
- [停車回報群眾外包實作計畫](停車回報群眾外包-實作計畫.md)
- [API 成本統計實作計畫](API成本統計-實作計畫.md)

## 旅程回憶與短片

- [媒體與 AI 總計畫](travel-story/TRAVEL_STORY_MEDIA_AI_PLAN.md)
- [媒體 AI 審查紀錄](travel-story/TRAVEL_STORY_MEDIA_AI_CLAUDE_REVIEW.md)
- [AI 短劇歷史附錄](travel-story/TRAVEL_STORY_AI_DRAMA_APPENDIX.md)
- [回顧短片跨端契約](travel-story/TRAVEL_STORY_RECAP_CROSSEND_CONTRACT.md)
- [回顧媒體 Schema](travel-story/TRAVEL_STORY_RECAP_MEDIA_SCHEMA.md)
- App 端交接 `docs/travel-story/TRAVEL_STORY_RECAP_APP_HANDOFF.md`——**本機文件，未進版控**（僅內部傳閱）
- [Path B 任務拆解](travel-story/TRAVEL_STORY_RECAP_TASKS_PATH_B.md)
- 回顧短片樣板交接 `docs/travel-story/RECAP_VIDEO_TEMPLATE_HANDOFF.md`——**本機文件，未進版控**（僅內部傳閱）

## 稽核與問題追蹤

- [2026-09-21 問題清單](ISSUE-INVENTORY-2026-09-21.md)
- [2026-09-21 生成歷史稽核](GENERATION-HISTORY-AUDIT-2026-09-21.md)
- 最新程式審查輸出仍保留在根目錄 `codex-review.md`，方便既有 `/codex-verify` 工作流程覆寫。

## 維運文件

- [正式站部署](../DEPLOY.md)
- [後端代理](../server/README.md)
- [資料爬蟲](../crawler/README.md)
- [工作區規則](../AGENTS.md)
