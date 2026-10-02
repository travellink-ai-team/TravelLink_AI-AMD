---
name: cjk-line-wrap
description: 撰寫或修改此專案（TravelLink AI，繁中介面）的 UI 文字或其 CSS 時，避免中文在奇怪位置換行——詞被拆開（如「享受空／檔」）、按鈕標籤裂成兩行（如「查看替／換」）、句尾留一個孤字。凡任務涉及行程卡、標籤、按鈕、精靈、彈窗、天氣卡等含中文的畫面文字或版面樣式時，套用本規範。
---

# 中文（繁中）不亂換行規範

瀏覽器對中文預設「任兩字之間都可斷行」（不像英文只在空格斷）。不處理就會出現：
詞被拆開（享受空／檔）、按鈕標籤裂行（查看替／換）、句尾孤字（單一字掉到下一行）。
本規範讓換行落在合理位置。改到含中文的 UI 文字或版面時**先讀這份**。

## 規則

### 1. 短標籤一律不換行
按鈕、標籤（tag/chip/pill）、狀態徽章、導覽項、圖示等「短字串」加 `white-space: nowrap`，
整塊不斷行。放在 flex 列時，按鈕那側再加 `flex-shrink: 0`、文字那側加 `min-width: 0`，
避免空間不足時把按鈕擠到裂行。

本專案既有這類 class（新增同類元件時沿用或補上 nowrap）：
`.tool-btn`、`.replan-btn`、`.wizard-tag`、`.mt-action-btn`、`.tag`、`.filter-tab`、
`.hero-btn-primary/.hero-btn-secondary`、`.tripfb-btn`、`.screen-btn`、`.nav-pill`、
`.checkin-btn`、`.stay-time-tag`、`.hero-tag`。

```css
.some-row { display: flex; align-items: center; gap: 8px; }
.some-row .label  { min-width: 0; }                       /* 文字側可縮可換行 */
.some-row .action { flex-shrink: 0; white-space: nowrap; } /* 按鈕不縮不裂 */
```

### 2. 內文用 text-wrap 改善斷點、避免孤字
較長的中文敘述（卡片說明、天氣 advice、提示）在容器加 `text-wrap: pretty;`
（現代瀏覽器；不支援時自動退回一般換行，安全無副作用）。它讓最後一行不留單一孤字、
斷點更自然。本專案已對 `.wc-advice / .spot-desc / .tripfb-sub / .screen-subtitle` 套用。

### 3. 必須整組不拆的片段，包 `.nowrap`
數字＋單位、時間區間、專有名詞等「拆開會怪」的片段，用共用工具 class 包起來
（`.nowrap { white-space: nowrap; }` 已定義於 `app/ai-travel-planner-v8.css`；
其他頁若要用，於該頁 CSS 補一次同樣定義）。

```html
降雨機率 <span class="nowrap">30%</span>、<span class="nowrap">7/10 06:00–18:00</span>
```

### 4. 固定寬度容器要留得下最長標籤
按鈕/卡片寬度若寫死，要以「最長的中文標籤」估寬，否則窄螢幕就裂行。
優先用 flex/min-width 自適應，不要硬塞固定 px。

## 不要做
- **不要 `word-break: break-all`**：它在任意字元斷行，正是亂換行的元凶。
- **不要用 `word-break: keep-all` 處理長中文句**：中文沒空格，keep-all 會讓整句不斷行而溢出容器。
- **別靠加空白／`<br>` 硬換行**：換行位置會隨螢幕寬度變動而失效。

## 範圍
只處理這次任務實際碰到的元件與文字。不要順手全專案掃描補 `nowrap`／`text-wrap`——
若發現別處也有同類問題，講一句告訴使用者即可，不要自行把改動擴大。

## 改完 UI 文字後看什麼
這次改動若本來就需要開畫面確認（依 AGENTS.md 的流程測試規範），在 375px 寬檢查三件事：
按鈕標籤有無裂兩行？詞有無被拆（空／檔）？句尾有無孤字？有就套上面規則。
以正式站 `travel-link-ai.duckdns.org` 為準、F12 零紅字。
若只是照規則補一行 `white-space:nowrap` 這類樣式，改完即可，不必為此另跑一輪瀏覽器驗證。

## 樣式落點
- planner：`app/ai-travel-planner-v8.css` 末段「中文不亂換行」區塊已放 `.nowrap` 與 text-wrap 規則。
- explore：如需同款工具，於 `app/ai-travel-explore-final.css` 補 `.nowrap{white-space:nowrap}`。
