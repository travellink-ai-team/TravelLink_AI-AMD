// 景點門票「人工覆蓋表」（資料端，手動維護）。**這是覆蓋層，不是主來源。**
//
// 主來源＝台東觀光旅遊網自有 opendata（tour.taitung.gov.tw/zh-tw/opendata/attractions 的 ticket 欄位，
//   265 景點約 41 筆有門票）。爬蟲 `npm run enrich:fees` 會抓它、解析成 fee/feeNote 寫入 poi-data.js。
//   （其結構化票價「全國 TDX AttractionFee」台東 0 筆、Google Places 無門票欄位，故不採用。）
// 本檔只放「想覆蓋主來源」的少數景點：主來源解析不理想、或想改用假日全票等更保守的值。
//   前端 lookupStopFee 的優先序：本表 → poi-data.js（opendata 抓的）。
//
// 由 ai-travel-planner-v8.html 以 <script src="attraction-fee-config.js"> 載入 → window.WAI_ATTRACTION_FEE。
// ── 怎麼用 ──
//   覆蓋某景點：在 paid 加 '景點正式名稱': { fee: 金額, note: '說明' }；只想壓掉錯誤金額但不給數字：只填 note。
//   名稱比對已正規化（臺→台、去空白/標點）。改完存檔 → 瀏覽器硬重整 Ctrl+Shift+R。
//   ⚠️ 皆為參考值，以各景點官方最新公告為準。最後查核 2026-07。
window.WAI_ATTRACTION_FEE = {
  note: '人工覆蓋表（覆蓋 poi-data.js 的 opendata 門票）。以各景點官方最新公告為準。最後查核 2026-07。',
  paid: {
    // opendata 解析到「平日全票」，這裡改用假日全票，讓預算偏保守、避免規劃到超支。
    '初鹿牧場':          { fee: 200, note: '平日全票 $100 / 假日全票 $200' },
    '知本國家森林遊樂區': { fee: 100, note: '假日全票 $100 / 平日 $80' },
    // 史前館本館未在 poi-data.js 的台東清單中（無 opendata 對得上的 POI），這裡補上官方全票。
    '國立臺灣史前文化博物館': { fee: 100, note: '本館全票 $100 / 團體 $80' },
    // opendata 的 ticket 文字是「導覽10人成團」被誤讀成 $1000；台東糖廠本身免費入園、體驗才收費 → 不計單一門票。
    '台東糖廠':          { note: '園區免費入園；體驗/導覽另計（約 $200–1500，10 人成團預約）' }
  }
};
