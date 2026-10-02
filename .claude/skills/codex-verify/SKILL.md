---
name: codex-verify
description: 把最近的變更丟給本機 Codex CLI 做第二雙眼睛驗證。使用者輸入 /codex-verify（可帶 commit 範圍、數量或 wip）時執行：整理變更範圍與驗收標準 → codex exec 審查 → 結果存 codex-review.md 並摘要回報。
---

# /codex-verify — 用 Codex CLI 驗證最近的變更

把指定範圍的變更（含驗收標準）交給本機 `codex` CLI 非互動審查，產出報告。
Codex 用使用者的 ChatGPT 帳號（`codex login status` 可查），不需開任何 app；額度計在 ChatGPT 方案。

## 參數解析（$ARGUMENTS）
- 空 → 範圍 = `HEAD~1..HEAD`（最後一個 commit）。
- 數字 N → `HEAD~N..HEAD`。
- 含 `..` 的字串 → 直接當 git range。
- `wip` → 審**未 commit** 的工作區變更（`git diff HEAD`，含 staged＋unstaged；不需先 commit）。
- 其他文字 → 附加為「額外關注重點」。

## 步驟

1. **組驗證上下文**（你來寫，這是本 skill 的價值所在）：
   - commit 模式：`git log --oneline <range>`；wip 模式：`git status --short`。
   - 依據本 session 的上下文（或 commit message / diff），寫出這些變更的「目的」與「驗收標準」清單——像交接給不知情同事。

2. **寫 prompt 檔**到 scratchpad（避免 shell 引號地獄），內容格式：
   ```
   你是資深審查者。請在這個 repo 審查以下變更：
   <commit 模式：範圍 <range>，先執行 `git log --oneline <range>` 與 `git diff <range>`>
   <wip 模式：未提交變更，先執行 `git status --short` 與 `git diff HEAD`>
   注意：一律透過 git 指令（git diff/show，輸出為 UTF-8）讀變更內容；
   避免用 PowerShell Get-Content 直接讀中文檔案（預設編碼會亂碼）。

   ## 變更目的
   <一段話>

   ## 驗收標準（逐條核對 diff 是否達成、有無破壞）
   - <條列>

   ## 額外關注
   - 本專案是無建置步驟的靜態原型（file:// 可跑、plain script 非 module）
   - F12 必須零紅字：檢查新增路徑是否有未捕捉的 async 錯誤
   - innerHTML 前必須 escapeHtml；Firestore stops 欄位需三處同步（persist／initFromUrl×2／collab 快照）
   - <使用者指定的額外重點（如有）>

   請輸出：(1) 驗收標準逐條 PASS/FAIL/存疑 (2) 發現的 bug（含檔案:行號與觸發情境） (3) 建議（可選）。用繁體中文。
   ```

3. **執行 Codex**（用 Bash tool；可能跑數分鐘，timeout 給滿 600000，或 run_in_background）：
   ```bash
   codex exec --sandbox read-only -C "C:/Users/USER/Desktop/UIUX" \
     -c model_reasoning_effort="high" \
     -o <scratchpad>/codex-final.md \
     "$(cat <prompt檔>)" > <scratchpad>/codex-out.txt 2>&1
   ```
   - `--sandbox read-only`：只給讀權限，Codex 不能改 repo。
   - `-c model_reasoning_effort="high"`：只覆蓋這次叫用（全域 config 仍是 medium）。
     審查要的是找得到真 bug，值得多花一點；嫌慢或想省額度就把這行拿掉。
   - 模型不指定，沿用 `~/.codex/config.toml` 的 `model`（目前 `gpt-5.6-terra`）。
   - `-o codex-final.md`：**只存最終答覆**（乾淨 UTF-8）。完整 transcript（codex-out.txt）僅供除錯——
     裡面 Codex 自己的中間指令回顯可能有編碼亂碼，屬顯示雜訊，忽略即可，以 codex-final.md 為準。
   - 若 `codex exec` 回錯（未登入等），把錯誤原樣回報給使用者，不要重試超過一次。

4. **產出報告**：以 `codex-final.md` 為內容整理存到 repo 根目錄 `codex-review.md`（已 gitignore），
   開頭附：日期、range、commit 清單、tokens used（在 codex-out.txt 尾端）。
   報告長度配合實際發現量——沒發現問題就寫短，不要補湊背景說明、重複摘要之類的填充段落。
   然後在對話中用繁中摘要，只講：整體結論、FAIL/存疑項、bug 清單；細節留在報告裡不要複述。

## 注意
- Codex 的發現是「線索」不是「判決」——每個 FAIL/bug 你要自己對照程式碼確認後再轉述，標明「已確認」或「Codex 認為但我查證後不成立（原因）」。
- **本 skill 的產出是報告，不是修復**：確認成立的 bug 列進報告等使用者決定，除非使用者在這次叫用裡明講要順便修。
- 這是單一指令的任務，自己跑完即可；不要為了審查、複核或分頭讀 diff 另開 subagent。
- 不要把任何 secrets（server/.env、serviceAccount.json 內容）寫進 prompt。
