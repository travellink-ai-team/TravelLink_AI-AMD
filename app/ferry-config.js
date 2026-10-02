// 離島渡輪港口設定（資料端，手動維護）。
// 各離島的「島內港口 / 本島港口 / 本島港別名」是穩定且常被 AI/Places 抓錯的錨點座標，
// 故以資料檔集中管理：要修港口座標或新增離島，改這裡即可，不必動主程式邏輯。
// 由 ai-travel-planner-v8.html 以 <script src="ferry-config.js"> 載入 → window.WAI_FERRY_CONFIG。
//
// 結構：
//   '目的地': {
//     islandHarbor:        { name, emoji, lat, lng },            // 島內上下船港
//     mainlandHarbor:      { name, emoji, lat, lng, region },    // 本島出發/返回港
//     mainlandHarborAlias: [ ...本島港的別名（用於名稱比對） ]
//   }
window.WAI_FERRY_CONFIG = {
  '綠島': {
    islandHarbor: { name: '南寮漁港', emoji: '⚓', lat: 22.6627261, lng: 121.4759701 },
    mainlandHarbor: { name: '富岡漁港', emoji: '⚓', lat: 22.7489, lng: 121.1551, region: '台東' },
    mainlandHarborAlias: ['富岡漁港', '富岡港', '台東漁港', '台東富岡漁港']
  },
  '蘭嶼': {
    islandHarbor: { name: '開元漁港', emoji: '⚓', lat: 22.058222, lng: 121.508167 },
    mainlandHarbor: { name: '富岡漁港', emoji: '⚓', lat: 22.7489, lng: 121.1551, region: '台東' },
    mainlandHarborAlias: ['富岡漁港', '富岡港', '台東漁港']
  },
  '小琉球': {
    islandHarbor: { name: '白沙港', emoji: '⚓', lat: 22.3435, lng: 120.3722 },
    mainlandHarbor: { name: '東港漁港', emoji: '⚓', lat: 22.4577, lng: 120.4512, region: '屏東' },
    mainlandHarborAlias: ['東港漁港', '東港', '大鵬灣港']
  },
  '澎湖': {
    islandHarbor: { name: '馬公港', emoji: '⚓', lat: 23.5636, lng: 119.5693 },
    mainlandHarbor: { name: '布袋港', emoji: '⚓', lat: 23.3815, lng: 120.1616, region: '嘉義' },
    mainlandHarborAlias: ['布袋港', '嘉義布袋', '布袋漁港']
  }
};
