# UI/UX Pro Max — 繁體中文使用說明

> 這份是說明文件,**不是 skill 本身**。Claude Code 實際讀取執行的是同目錄的
> `SKILL.md`(英文原文,MIT 授權,來源 https://github.com/nextlevelbuilder/ui-ux-pro-max-skill)。
> 不要為了翻譯去改 `SKILL.md` 或 `data/` 下的 CSV——那會動到實際的規則資料。

---

## 這是什麼

一個**本地的 UI/UX 規則資料庫 + BM25 搜尋引擎**。不是叫 AI 憑印象講設計原則,
而是查一份結構化資料:每條規則都有「該做什麼 / 不該做什麼 / 嚴重度 / 程式碼範例」。

收錄量:98 條 UX 準則、192 組配色、84 種視覺風格、74 組字體配對、192 種產品類型、
104 個圖示條目、16 組 GSAP 動效、25 種圖表類型。

**完全離線、無外部相依**,只用 Python 標準庫。跑起來不會連網、不會裝套件。

---

## ⚠️ 用之前要知道的落差

**它的程式碼範例幾乎都是 Tailwind 語法**(`min-h-[44px]`、`gap-2`、`text-sm`),
而本專案是**純 CSS、無 Tailwind、無建置步驟**。stack 清單裡也沒有純 HTML/CSS 選項。

所以實務上這樣用:

| 欄位 | 能不能直接用 |
|---|---|
| Issue / Description / Do / Don't / Severity | ✅ 直接可用,跨技術棧 |
| 數值(44px、4.5:1、150–300ms、8px) | ✅ 直接可用 |
| Code Example Good / Bad | ⚠️ Tailwind 寫法,要自己轉成純 CSS |

**建議只用 `--domain`,不要帶 `--stack`。** domain 規則是跨技術棧的;
`--stack html-tailwind` 對我們沒有意義。

---

## 怎麼用

### 方式一:直接叫我(最常用)

不用背指令,講需求就好:

- 「用 ui-ux-pro-max 查一下行程卡片的配色建議」
- 「這個彈窗的無障礙有沒有問題?查一下規則」

我會自己去查資料庫,再把 Tailwind 範例轉成本專案的純 CSS 寫法。

### 方式二:自己下指令

```bash
python .claude/skills/ui-ux-pro-max/scripts/search.py "<查詢字串>" --domain <domain>
```

Windows 上若 `python` 找不到,依序試 `python3`、`py -3`。

---

## 可用的 domain

| domain | 內容 | 對本專案的用處 |
|---|---|---|
| **`ux`** | 98 條 UX 準則(無障礙、觸控、效能、表單、導覽) | **最有用**。觸控 44px、對比 4.5:1、底部導覽 ≤5 項都在這 |
| **`color`** | 192 組配色 | 挑主色/強調色,檢查對比 |
| **`typography`** | 74 組字體配對 | 中英混排、字級階層 |
| **`style`** | 84 種視覺風格 | 決定整體調性 |
| **`product`** | 192 種產品類型的推理規則 | 查「travel app」會給旅遊類產品的慣例 |
| **`icons`** | 104 個圖示條目 | 目前專案大量用 emoji 當圖示,這裡有反面意見可參考 |
| **`gsap`** | 16 組動效預設 | 本專案沒用 GSAP,參考動效時間曲線即可 |
| **`chart`** | 25 種圖表類型 | 共編面板的興趣長條圖可參考 |
| **`landing`** | 到達頁模式 | `intro.html` 介紹頁可用 |
| `react` / `web` / `google-fonts` | 框架與字體資料 | 本專案較少用到 |

---

## 實用範例(都已實測可跑)

**檢查觸控目標**
```bash
python .claude/skills/ui-ux-pro-max/scripts/search.py "touch target size mobile navigation" --domain ux
```
> 回傳:最小 44×44px、相鄰目標至少 8px 間距

**旅遊 App 的配色**
```bash
python .claude/skills/ui-ux-pro-max/scripts/search.py "travel outdoor nature calm" --domain color
```

**介紹頁的到達頁結構**
```bash
python .claude/skills/ui-ux-pro-max/scripts/search.py "landing page hero conversion" --domain landing
```

**中文字體配對**
```bash
python .claude/skills/ui-ux-pro-max/scripts/search.py "chinese sans-serif readable body" --domain typography
```

常用參數:`--max-results N`(預設 3)。

---

## 兩份離線參考文件

不用跑腳本也能直接讀:

- `references/quick-reference.md`(21KB)— 全部 98 條 UX 準則,附理由
- `references/pro-rules.md`(9KB)— 圖示、觸控回饋、深色模式對比、安全區域等
  進階規則,以及交付前檢查清單

需要通盤檢查某一頁時,讀這兩份比逐條下指令快。

---

## `--design-system` 模式(謹慎使用)

腳本另有產生完整設計系統的功能:

```bash
python .claude/skills/ui-ux-pro-max/scripts/search.py "travel planning app" --design-system
```

加 `--persist` 會**實際寫檔**到 `design-system/<專案名>/MASTER.md`。三件事要注意:

1. 寫檔位置用 `--output-dir` 指定,**沒指定就寫在當前工作目錄**,務必明確傳入專案根目錄。
2. 若 `MASTER.md` 已存在,不加 `--force` 不會覆蓋(這是它的保護機制,別隨便加 `--force`)。
3. 產出的 token 是 Tailwind 導向的,**與本專案既有的 `app/design-tokens.css` 是兩套東西**,
   直接套用會衝突。要用的話請當成參考,手動挑進現有 token 檔。

沒有明確需求時,**不要用 `--persist`**。

---

## 安全性(安裝前已稽核)

四支 Python 共 90KB 全部審過:

- **import 只有標準庫**:csv / json / sys / io / argparse / pathlib / re / math / datetime
- **無網路**:沒有 urllib、requests、socket
- **無 subprocess / os.system / eval / exec**
- `os` 只用在一處:讀 `COLORTERM` 判斷終端機色彩
- **檔案寫入只發生在 `--persist`**,且只寫進 `design-system/` 目錄

---

## 其他

- 授權 MIT(LICENSE 已隨附),作者 Next Level Builder。
- 原 repo 另含 5 個 skill(`ui-styling`、`design`、`design-system`、`brand`、`slides`),
  多為 shadcn/Tailwind 設定產生、簡報與 logo 生成,**本專案未安裝**——與無建置步驟的
  靜態原型不相容,裝了只是佔空間。若日後改用 Tailwind 再考慮。
- 安裝位置:`.claude/skills/ui-ux-pro-max/`(專案層級)。
  要全域可用就複製整個資料夾到 `C:\Users\USER\.claude\skills\`。
- 更新方式:重新從上游下載該資料夾覆蓋即可,沒有安裝步驟或相依需要重跑。
