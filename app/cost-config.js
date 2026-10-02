// 交通費率設定（資料端，**手動維護**，非爬蟲產生）。所有數字皆為「可編輯參考值」，非即時報價。
// 用途：把人均預算理解成「總預算 − 交通 = 可動用於餐飲/活動的金額」。
//   - 站間移動：依距離 × 交通模式費率估算（機車/汽車/計程車為每車、大眾為每人每趟、走路 0）。
//   - 離島往返船票：每人來回票價（鍵對齊目的地正規化：綠島/蘭嶼/小琉球/澎湖）。
// 由 ai-travel-explore-final.html / ai-travel-planner-v8.html 以 <script src="cost-config.js"> 載入 → window.WAI_COST_CONFIG。
//
// ── 怎麼更新（不需爬蟲、不需 Firebase，改完存檔→瀏覽器硬重整 Ctrl+Shift+R 即生效）──
//   modeRates.perKm（機車/汽車）＝ 油價 ÷ 油耗（純油錢），可再上調含耗損/停車/過路費。
//        油價查中油官網「油價資訊」（每週一公告）；油耗用車種實測值。
//   modeRates（計程車）＝ 各縣市「計程車運價」公告（起程價＝base、續程換算成每公里＝perKm）。
//   ferryRoundTrip ＝ 各船公司官網「票價」查一次手填（一年難得變動）；請以船公司最新公告為準。
//   dailyMoveAllowance ＝ 生成前（尚無站點）的每日站間移動粗估，純為估算假設，可依體感調整。
//   最後查核：2026-06（船票/油價/跳表會變動，建議每季或出發前複查一次）。
window.WAI_COST_CONFIG = {
  // 站間移動每公里費率（新台幣）。
  //   perKm 每公里費率；base 起跳/單趟固定費；capacity 乘載人數；
  //   perPerson=false → 金額為「每車」（多人會除以人數攤）；perPerson=true → 金額為「每人每趟」（如大眾運輸均一價）。
  modeRates: {
    // 機車：油錢約 中油92無鉛≈$30/L ÷ 機車油耗≈45km/L ≈ $0.7/km，含耗損抓 $1.5/km。
    scooter: { perKm: 1.5, base: 0,  capacity: 2,  perPerson: false, label: '機車' },
    // 汽車：油錢約 95無鉛≈$31/L ÷ 油耗≈12km/L ≈ $2.6/km，含停車/耗損抓 $4/km（國道過路費未計，可再上調）。
    car:     { perKm: 4,   base: 0,  capacity: 4,  perPerson: false, label: '汽車' },
    // 計程車：台東縣跳表 起程1.25km $85、續程每250m $5（≈$20/km）；北部續程每200m $5（≈$25/km）。
    taxi:    { perKm: 20,  base: 85, capacity: 4,  perPerson: false, label: '計程車' },
    // 大眾運輸：市區公車/台灣好行單趟均一估，每人每趟 $25。
    public:  { perKm: 0,   base: 25, capacity: 99, perPerson: true,  label: '大眾運輸' },
    walk:    { perKm: 0,   base: 0,  capacity: 99, perPerson: false, label: '走路' }
  },
  // 離島往返船票（每人來回，新台幣）。參考值，請以船公司最新公告為準。
  ferryRoundTrip: {
    '綠島':   1120,  
    '蘭嶼':  2400, 
    '小琉球': 350,  
  },
  // ── Google Maps 用戶端 API 費率（美金／次），**手動維護**，只用於畫面上的用量推估 ──
  // 為什麼放在這裡而不是 server/pricing.js：那份是「權威費率表」，算出來的金額會寫進
  // Firestore 當成帳上數字，所以必須留在後端不可竄改。但路線規劃是瀏覽器直接呼叫 Google、
  // 伺服器根本觀察不到，只能依行程結構推估——推估值永遠只是顯示用，不會變成權威金額，
  // 因此放在前端設定檔、比照油價船票由人工維護即可。
  //   directionsAdvanced：請求帶即時路況（drivingOptions.departureTime）→ 汽車／機車段
  //   directionsBasic   ：不帶路況參數 → 走路／大眾運輸段、以及停車場↔景點的步行線
  //   placesNearbySearch：PlacesService.nearbySearch（停車場候選、景點座標校正…）
  //   placesTextSearch  ：PlacesService.textSearch（站名精確定位、nearbySearch 無結果的退回…）
  //   placesDetails     ：PlacesService.getDetails（營業時間等詳細欄位）
  //   geocoding         ：Geocoder.geocode（地址 → 座標）
  // ⚠ Places 兩項每次約是 Directions 的 6 倍價，是這裡最貴的呼叫。
  //   停車場解析刻意做成四層退回（景點資料 → 台東本地資料 → TDX → Places），
  //   前三層都是零成本，就是為了盡量不走到 Places 這層。
  // 更新方式：Google Maps Platform 定價頁 → Routes 類別（Directions API / Directions Advanced）
  //   與 Places 類別（Nearby Search / Text Search）。
  // 最後查核：2026-07-28。免費額度會隨方案調整，變動時請一併更新 freeCallsPerMonth。
  mapsApiRates: {
    directionsBasicUsd: 0.005,
    directionsAdvancedUsd: 0.010,
    placesNearbySearchUsd: 0.032,
    placesTextSearchUsd: 0.032,
    placesDetailsUsd: 0.017,
    geocodingUsd: 0.005,
    freeCallsPerMonth: 10000
  },
  // 生成前（尚無站點）prompt 用的每模式「每日站間移動」粗估（每人每日，新台幣）。
  dailyMoveAllowance: {
    scooter: 80,
    car: 200,
    taxi: 400,
    public: 120,
    walk: 0
  }
};
