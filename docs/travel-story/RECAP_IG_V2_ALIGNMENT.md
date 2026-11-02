# 回顧短片 IG 優化：網頁／後端進度與第 2 版格式對齊

2026-11-01 · 泓廷（回覆 @ken 10-29「回顧短片 IG 分享優化提案」）

提案裡寫「時間軸第 2 版的欄位名稱與驗證規則請先對齊再動工」。我先把後端的算法和格式做出來（有測試），下面是**實際做出來的版本**，請皓宇看第 2、3、4 節，沒問題就照這份移植到 RecapTimelines.kt；要改名或改規則直接回我，我這邊改比較快。

---

## 1. 這次做了什麼、還沒做什麼

| # | 項目 | 狀態 |
| --- | --- | --- |
| A2 | Reels 節奏 | ✅ 樣板 `reels`；前端選單預設改成 Reels（使用者選過別的節奏照舊保留） |
| A3 | IG 安全區 | ✅ 標題、字幕條（86%→70%）、品牌字樣都移進安全區，**所有節奏都套用** |
| A4 | 攻略卡 | ⚠️ 天數、景點數、每人花費、交通、最推薦一站、網頁與 App 網址都有；**QR code 還沒做** |
| A5 | 自選封面 | ✅ `coverStopIndex` |
| A1 | 封面用行程插圖 | ✅ 後端讀 `micro_trips/{id}.imageUrl`（就是第 1 天那張），沒插圖退回第一張照片，再沒有退回標題卡 |
| A6 | 滿版照片（基本） | ✅ 置中裁切＋四種推近方向輪流 |
| A7～A10 | 照片錨點、主角景點、三種中段、換日標籤 | ✅ |
| A11 | 時間軸第 2 版 | ✅ 產生、驗證、字幕軌、編輯器操作 |
| A19 | 影片長度 | ✅ `all`（上限 30 秒）／`15` |
| A15 | 畫面風格框架 | ⚠️ 請求收 `look`、白名單驗證；目前只有 `basic` 畫好，選其他三種會先用 basic 畫 |
| A16～A18 | 地圖飛行、卡片疊放、底片手帳 | ❌ 還沒開始 |
| 網頁前端 | 風格／長度／封面選擇、分享流程、配樂建議卡、APK 頁 | ❌ 還沒開始（只改了預設節奏、送 `lengthMode`、`tripId`） |

測試：`node server/test/recap-timeline.test.js`（97 項，第 2 版的在檔案後半段）。提案「參考時間表」那支台東一日遊，後端排出來逐段秒數完全一樣（總長 20.5 秒）。

---

## 2. 時間軸第 2 版（請皓宇確認欄位名稱）

只有 Reels（`templateId: "reels"`）產第 2 版；標準／快節奏／慢遊／電影感開場仍產第 1 版，後端兩版都收。

```jsonc
{
  "schemaVersion": 2,
  "locale": "zh-TW",
  "revision": 0,
  "templateId": "reels",
  "lengthMode": "all",              // all | 15
  "structure": "photoStory",        // photoStory | mapFeatured | routeStory（顯示用，後端不採信，自己重算）
  "totalMs": 20500,
  "clips": [
    { "clipId": "cover", "type": "cover", "source": "illustration", "durationMs": 1200 },
    // source: "photo" 時多一個 "coverStopIndex": 3
    { "clipId": "reveal", "type": "route", "reveal": true,
      "fromStopId": "第一站", "toStopId": "最後一站", "viaStopIds": ["中間全部站…"], "label": "路線揭曉", "durationMs": 1000 },
    { "clipId": "photo-0", "type": "photo", "stopIndex": 0, "label": "台東糖廠文創園區",
      "assetRefs": [{ "kind": "memory_photo", "stopId": "s0", "photoId": "p0" }], "durationMs": 1400 },
    // 多日行程時，當天第一張照片的片段多一個 "dayLabel": 2
    { "clipId": "route-1-3", "type": "route", "fromStopId": "s1", "toStopId": "s3", "viaStopIds": ["s2"],
      "label": "卑南豬血湯 → TTstyle（經過 鐵花村）", "distanceKm": 2.2, "durationMs": 700 },
    { "clipId": "photo-5", "type": "photo", "stopIndex": 5, "label": "台東森林公園", "hero": true,
      "assetRefs": [{ "...": "3 張" }], "durationMs": 3000 },
    { "clipId": "summary", "type": "summary", "stats": { "stops": 8, "days": 1, "distanceKm": 18.4 }, "durationMs": 1500 },
    { "clipId": "outro", "type": "outro", "stats": { "stops": 8, "distanceKm": 18.4, "days": 1 }, "durationMs": 3300 }
  ]
}
```

和提案的差異（請確認）：

- **路線揭曉用 `route` + `reveal: true` 表示**，不另開片段類型。起訖是第一站與最後一站，`viaStopIds` 是中間所有站。
- **換日標籤用 `dayLabel: N`** 放在當天第一張照片的片段上。多日行程時第 1 天也會標（「第 1 天・台東」）；一日遊不標。
- **`photo` 只有 `assetRefs`**（第 1 版的 `assetRef` 後端仍讀，但第 2 版不產）。
- `stats.stops` 在第 2 版是**全部站數**（第 1 版是站數 − 1）。畫面上「第 3／8 站」「8 個景點」用同一個數字。

### 驗證規則（後端 `compileTimeline`，App 編輯器要用同一套）

| 片段 | 規則 |
| --- | --- |
| `route` | `fromStopId` 在 `toStopId` 之前；`viaStopIds` 必須**正好**是兩站之間的站、依序。不合格的整段忽略（該過場改用預設秒數） |
| `route` + `reveal` | 起訖必須是第一站與最後一站 |
| `photo` | 依行程順序往後（倒著排的丟掉）、每站最多一個片段、那站真的有照片；`assetRefs` 用 `photoId` 對回送來的照片，沒有 `photoId` 就照位置對 |
| 過場怎麼決定 | 上一個照片錨點（第一個錨點從起點算）→ 這個錨點之間有合格的 route 片段 → 用它的秒數；沒有 → 相鄰兩站＝同組連續放、不相鄰＝預設 0.7 秒 |
| 主角 | 第一個標 `hero: true` 的照片片段；都沒標＝不設主角 |
| 字幕 | 每則 14 字，`summary` 也可以加字幕 |

秒數範圍（`LIMITS`）：cover 1～6 秒、route 0.3～15 秒、reveal 0.5～3 秒、summary 0.8～4 秒、outro 1～6 秒、**photo 第 2 版是「每張」0.5～4 秒 × 張數**（整段最多 30 秒）。第 1 版 photo 維持 0.8～8 秒（`LIMITS.photo` 沒動，新增 `LIMITS.photoEach`）。

### 編輯器操作

- **移除照片片段後自動重接路線**：前後的 route 片段合併成一段（起訖＝前後的照片錨點，經過的站接起來，秒數相加；原本是同組沒有路線時用 0.7 秒）。最後一張照片之後的路線拿掉。這樣編輯後的時間軸一定通過上面的驗證。
- **主角卡片可以個別移除照片**（`removeClipPhoto(timeline, clipId, index)`）：秒數依張數等比例縮短；只剩一張再移除＝移除整個片段。
- ±0.5 秒照舊。

---

## 3. render 請求欄位（定案名稱）

都是選填，舊請求照常可用。

| 欄位 | 說明 |
| --- | --- |
| `templateId: "reels"` | 沒帶仍是 `standard`（**後端預設沒改**，App 目前的請求不受影響） |
| `look` | `fly`／`cards`／`film`／`basic`；不認得的值當 `basic`。回應會帶 `look`（實際用的）與 `lookRequested` |
| `lengthMode` | `all`（預設）／`15`；亂填當 `all` |
| `trip.costPerPerson` | 數字（元），四捨五入；0 或沒有就不顯示 |
| `trip.highlightStopId` | 必須是這趟行程的 `stopId`，不是就忽略。同時是主角景點 |
| `trip.stops[].arriveTime` | **提案寫「欄位名稱待對齊」，我用 `arriveTime`，格式 `HH:MM`**，格式不對當空字串。目前收進來還沒用到（地圖飛行才會用） |
| `coverStopIndex` | 那站要有照片，否則改用行程插圖 |
| `tripId`（**新增，提案沒有**） | 後端要讀 `micro_trips/{tripId}.imageUrl` 當封面，所以要知道是哪趟。後端會檢查呼叫者是行程成員，不是就當沒有插圖 |
| `photos[].photoId` | 選填，給時間軸 `assetRefs` 對照用 |
| `photos` | 同一個 `stopIndex` 可以多張，順序照送來的；**總數上限 24、每站上限 10**，重複網址只算一次 |

`lengthMode: "15"` 時不用自己先挑照片，全部送來後端會挑（主角最多 3 張、其他站 1 張，放不下的站略過）。`all` 超過 30 秒時也是後端減：先減一般站多出來的照片 → 主角減到 3 張 → 拿掉一般錨點（挑拿掉後照片分布最平均的）。提案說「超過上限時讓使用者挑照片」，這個 UI 兩邊都還沒做，現在是後端自動挑。

---

## 4. 算法有改，RecapTimelines.kt 要同步的地方

1. **其他節奏的換日卡縮短（A10）**：標準 1600→800、快節奏 1100→600、慢遊 2200→800、電影感開場 1500→700 ms。**App 現在沒帶 templateId＝標準，多日行程的影片會變短，App 編輯器的秒數要跟著改。**
2. **Reels 秒數**：開場 1200、揭曉 1000、一般照片每張 1400、主角每張 1000（只有 1 張 2000）、過場 700、收束 1500、攻略卡 3300。
3. **分組**：相鄰兩站都有照片時兩站一組（**一組最多 2 站**），主角自己一組；組與組之間播過場。第一站有照片時揭曉後直接放、不播過場。
4. **主角沒指定時**：照片最多（至少 2 張）的站 → 都只有 1 張時取停留時間（`stayMin`）最久的有照片站 → 都沒有就不設。
5. **三種中段**：照片總張數 0＝routeStory、1～2＝mapFeatured、3 以上＝photoStory。
   - mapFeatured：沒有揭曉，整條路線開 5 秒（依距離分配），到錨點停下放照片。
   - routeStory：揭曉後整條路線開 6 秒，平均挑最多 4 個中間站各聚焦 0.6 秒。
6. **固定 15 秒**：主角 1 張 2400、2 張各 1000、3 張以上取 3 張各 800；挑完後剩下的秒數按比例加到一般照片（沒有一般照片就加到主角），總長剛好 15000。
7. **跨天的段**：Reels 不畫車開過去（0 秒直接跳到隔天），也不出換日卡。

**對照用的測試資料**：`server/test/recap-timeline.test.js` 的「參考時間表」那組（8 站、照片數 `[1,1,0,1,1,3,1,1]`、主角第 6 站），逐段開始時間應該是：

```
cover@0 reveal@1200 photo@2200 photo@3600 hop@5000 photo@5700 photo@7100
hop@8500 photo@9200 hop@12200 photo@12900 photo@14300 summary@15700 outro@17200（總長 20500）
```

---

## 5. 待討論：我這邊的建議

| 問題 | 建議（現在程式就是這樣做的） |
| --- | --- |
| 三種中段的照片數門檻 | 維持 0／1～2／3 張以上 |
| 沒選主角時的挑法 | 見第 4 節第 4 點 |
| 放進全部照片的上限 | 30 秒（寫在樣板 `maxMs`，要改只改一個數字） |
| 預設畫面風格 | 另外三種做好之前先用「基本」；全部做好再改成地圖飛行 |
| 多日行程封面用哪天的插圖 | 第一天（`imageUrl` 本來就是第一天那張） |
| 地圖飛行的抵達時間欄位 | `stops[].arriveTime`，`HH:MM` |
| 基本風格裁切切到主體 | 先置中裁切＋推近幅度 6%（目前的做法） |
| 自動生成增加後端負擔（同時只排 4 支） | C7 做之前先不動；到時看實際排隊量 |

---

## 6. 需要你們提供／決定

- [ ] **皓宇**：確認第 2、3 節的欄位名稱與驗證規則，或直接回要改的地方。
- [ ] **皓宇**：App 的每人花費算法（公式或程式片段），我讓網頁用同一套，不然同一趟影片金額會不一樣。
- [ ] **皓宇**：APK 檔與之後更新的方式（我放到 `travel-link-ai.duckdns.org/download`，片尾已經印這個網址）。
- [ ] **有實機的人**：用一支測試影片發 Reels 與限動各一次截圖，確認安全區數值（目前上 260、下 420、右 130，以 1080×1920 計）。
- [ ] **大家**：分享文案與 hashtag 寫法（兩邊一致）。
