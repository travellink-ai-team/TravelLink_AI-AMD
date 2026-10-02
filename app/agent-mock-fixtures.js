// 旅程應變 Agent 的 mock 事件（只在網址帶 ?agentMock=… 時由 ai-travel-planner-v8.js 動態載入）。
// recorded: true 的三組＝server/test/fixtures/agent-*.json 由 gpt-oss-120b 實際執行錄下的 SSE 事件（精簡掉請求的行程細節）；
// 重播時照 events[i].ms 的間隔送出，並交給跟真正 SSE 同一個事件處理函式。
// 不含任何端點網址。fixture 更新時重新複製即可。
window.WAI_AGENT_MOCK = {
 "rain": {
  "recorded": true,
  "recordedAt": "2026-10-02T11:18:04.734Z",
  "request": {
   "tripStops": [
    {
     "id": "s1",
     "name": "國立臺灣史前文化博物館"
    },
    {
     "id": "s2",
     "name": "榕樹下米苔目(中華路創始老店-別無分店)"
    },
    {
     "id": "s3",
     "name": "臺東森林公園"
    },
    {
     "id": "s4",
     "name": "加路蘭"
    },
    {
     "id": "s5",
     "name": "鐵花村音樂聚落"
    }
   ],
   "trigger": {
    "type": "weather"
   },
   "scenario": {
    "rain": {
     "date": "2026-10-03",
     "from": "13:00",
     "to": "17:00",
     "pop": 80
    }
   }
  },
  "events": [
   {
    "type": "start",
    "trigger": "weather",
    "simulated": true,
    "stops": 5,
    "ms": 11
   },
   {
    "type": "check",
    "label": "程式檢查天氣與營業時間（不呼叫 AI）",
    "ms": 11
   },
   {
    "type": "check_result",
    "label": "發現 2 個問題，啟動 AI 代理人",
    "issues": [
     "「臺東森林公園」是戶外景點，2026-10-03 13:30 降雨機率 80%（模擬）",
     "「加路蘭」是戶外景點，2026-10-03 15:30 降雨機率 80%（模擬）"
    ],
    "ms": 182
   },
   {
    "type": "tool_call",
    "tool": "get_trip_state",
    "label": "讀取目前行程",
    "ms": 353
   },
   {
    "type": "tool_result",
    "tool": "get_trip_state",
    "ok": true,
    "detail": "1 天、5 站",
    "ms": 354
   },
   {
    "type": "tool_call",
    "tool": "get_weather_forecast",
    "label": "查詢天氣預報",
    "ms": 486
   },
   {
    "type": "tool_result",
    "tool": "get_weather_forecast",
    "ok": true,
    "detail": "2 站落在降雨時段：臺東森林公園（80%）、加路蘭（80%）",
    "ms": 487
   },
   {
    "type": "tool_call",
    "tool": "search_local_poi",
    "label": "搜尋附近室內景點",
    "ms": 673
   },
   {
    "type": "tool_result",
    "tool": "search_local_poi",
    "ok": true,
    "detail": "找到 6 個：臺東美術館 0.8km、寶町藝文中心 Boting Art Center 1.2km、台東天后宮 1.3km",
    "ms": 686
   },
   {
    "type": "tool_call",
    "tool": "propose_patch",
    "label": "組合修改草稿並驗證",
    "ms": 1253
   },
   {
    "type": "tool_result",
    "tool": "propose_patch",
    "ok": true,
    "detail": "✅ 驗證通過",
    "ms": 1257
   },
   {
    "type": "tool_call",
    "tool": "present_proposal",
    "label": "提出方案",
    "ms": 1763
   },
   {
    "type": "tool_result",
    "tool": "present_proposal",
    "ok": true,
    "detail": "",
    "ms": 1763
   },
   {
    "type": "proposal",
    "summary": "將受雨影響的戶外景點改為室內景點",
    "reasons": [
     "臺東森林公園與加路蘭在13:30-17:30降雨機率80%，不適合戶外活動",
     "改為臺東美術館與寶町藝文中心，皆為室內且距離原點僅0.8-1.2公里，步行可達",
     "兩地均於13:30-17:30營業，停留時間分別調整為65分鐘與60分鐘，符合行程結束時間19:00"
    ],
    "fallback": false,
    "simulated": true,
    "changes": [
     {
      "type": "replace",
      "day": 1,
      "time": "13:30",
      "from": "臺東森林公園",
      "to": "臺東美術館"
     },
     {
      "type": "replace",
      "day": 1,
      "time": "15:30",
      "from": "加路蘭",
      "to": "寶町藝文中心 Boting Art Center"
     }
    ],
    "costDelta": {
     "perPersonBefore": 101,
     "perPersonAfter": 101,
     "diff": 0,
     "note": "門票加餐費估算，不含交通"
    },
    "warnings": [],
    "draft": {
     "title": "台東市區一日遊",
     "region": "台東",
     "startDate": "2026-10-03",
     "endTime": "20:00",
     "people": 2,
     "budgetPerPerson": null,
     "stops": [
      {
       "id": "s1",
       "day": 1,
       "time": "09:00",
       "stayMin": 120,
       "name": "國立臺灣史前文化博物館",
       "lat": 22.76069,
       "lng": 121.09136,
       "kind": "scenic"
      },
      {
       "id": "s2",
       "day": 1,
       "time": "11:30",
       "stayMin": 60,
       "name": "榕樹下米苔目(中華路創始老店-別無分店)",
       "lat": 22.7547249,
       "lng": 121.1533815,
       "kind": "food"
      },
      {
       "id": "ad6td1",
       "day": 1,
       "time": "13:30",
       "stayMin": 65,
       "name": "臺東美術館",
       "lat": 22.764304,
       "lng": 121.1499119,
       "kind": "scenic",
       "replaces": "s3",
       "agentAdded": true
      },
      {
       "id": "ad6td2",
       "day": 1,
       "time": "15:30",
       "stayMin": 60,
       "name": "寶町藝文中心 Boting Art Center",
       "lat": 22.7581386,
       "lng": 121.15322110000001,
       "kind": "scenic",
       "replaces": "s4",
       "agentAdded": true
      },
      {
       "id": "s5",
       "day": 1,
       "time": "17:30",
       "stayMin": 90,
       "name": "鐵花村音樂聚落",
       "lat": 22.7553,
       "lng": 121.1504,
       "kind": "scenic"
      }
     ],
     "days": 1
    },
    "usage": {
     "promptTokens": 9979,
     "completionTokens": 535,
     "llmCalls": 5
    },
    "steps": 5,
    "model": "gpt-oss-120b",
    "ms": 1764
   }
  ]
 },
 "tired": {
  "recorded": true,
  "recordedAt": "2026-10-02T11:18:05.728Z",
  "request": {
   "tripStops": [
    {
     "id": "s1",
     "name": "國立臺灣史前文化博物館"
    },
    {
     "id": "s2",
     "name": "榕樹下米苔目(中華路創始老店-別無分店)"
    },
    {
     "id": "s3",
     "name": "臺東森林公園"
    },
    {
     "id": "s4",
     "name": "加路蘭"
    },
    {
     "id": "s5",
     "name": "鐵花村音樂聚落"
    }
   ],
   "trigger": {
    "type": "user",
    "message": "走了一早上好累，下午想輕鬆一點、早點回飯店"
   }
  },
  "events": [
   {
    "type": "start",
    "trigger": "user",
    "simulated": false,
    "stops": 5,
    "ms": 0
   },
   {
    "type": "tool_call",
    "tool": "get_trip_state",
    "label": "讀取目前行程",
    "ms": 125
   },
   {
    "type": "tool_result",
    "tool": "get_trip_state",
    "ok": true,
    "detail": "1 天、5 站",
    "ms": 125
   },
   {
    "type": "tool_call",
    "tool": "propose_patch",
    "label": "組合修改草稿並驗證",
    "ms": 499
   },
   {
    "type": "tool_result",
    "tool": "propose_patch",
    "ok": true,
    "detail": "✅ 驗證通過",
    "ms": 500
   },
   {
    "type": "tool_call",
    "tool": "present_proposal",
    "label": "提出方案",
    "ms": 992
   },
   {
    "type": "tool_result",
    "tool": "present_proposal",
    "ok": true,
    "detail": "",
    "ms": 992
   },
   {
    "type": "proposal",
    "summary": "縮短森林公園停留並移除加路蘭與鐵花村，讓行程在下午輕鬆提早回飯店",
    "reasons": [
     "使用者想下午輕鬆、早點回飯店，將原本的 90 分鐘森林公園縮減至 60 分鐘",
     "移除加路蘭與鐵花村兩個戶外景點，減少行程負擔",
     "行程最終於 14:30 結束，符合提前回飯店的需求"
    ],
    "fallback": false,
    "simulated": false,
    "changes": [
     {
      "type": "retime",
      "day": 1,
      "name": "臺東森林公園",
      "from": "13:30",
      "to": "13:30",
      "stayFrom": 90,
      "stayTo": 60
     },
     {
      "type": "remove",
      "day": 1,
      "time": "15:30",
      "from": "加路蘭"
     },
     {
      "type": "remove",
      "day": 1,
      "time": "17:30",
      "from": "鐵花村音樂聚落"
     }
    ],
    "costDelta": {
     "perPersonBefore": 101,
     "perPersonAfter": 101,
     "diff": 0,
     "note": "門票加餐費估算，不含交通"
    },
    "warnings": [],
    "draft": {
     "title": "台東市區一日遊",
     "region": "台東",
     "startDate": "2026-10-03",
     "endTime": "20:00",
     "people": 2,
     "budgetPerPerson": null,
     "stops": [
      {
       "id": "s1",
       "day": 1,
       "time": "09:00",
       "stayMin": 120,
       "name": "國立臺灣史前文化博物館",
       "lat": 22.76069,
       "lng": 121.09136,
       "kind": "scenic"
      },
      {
       "id": "s2",
       "day": 1,
       "time": "11:30",
       "stayMin": 60,
       "name": "榕樹下米苔目(中華路創始老店-別無分店)",
       "lat": 22.7547249,
       "lng": 121.1533815,
       "kind": "food"
      },
      {
       "id": "s3",
       "day": 1,
       "time": "13:30",
       "stayMin": 60,
       "name": "臺東森林公園",
       "lat": 22.768175799999998,
       "lng": 121.15625899999999,
       "kind": "scenic"
      }
     ],
     "days": 1
    },
    "usage": {
     "promptTokens": 5094,
     "completionTokens": 381,
     "llmCalls": 3
    },
    "steps": 3,
    "model": "gpt-oss-120b",
    "ms": 993
   }
  ]
 },
 "no-issue": {
  "recorded": true,
  "recordedAt": "2026-10-02T11:18:05.731Z",
  "request": {
   "tripStops": [
    {
     "id": "s1",
     "name": "國立臺灣史前文化博物館"
    },
    {
     "id": "s2",
     "name": "榕樹下米苔目(中華路創始老店-別無分店)"
    },
    {
     "id": "s3",
     "name": "臺東森林公園"
    },
    {
     "id": "s4",
     "name": "加路蘭"
    },
    {
     "id": "s5",
     "name": "鐵花村音樂聚落"
    }
   ],
   "trigger": {
    "type": "weather"
   }
  },
  "events": [
   {
    "type": "start",
    "trigger": "weather",
    "simulated": false,
    "stops": 5,
    "ms": 0
   },
   {
    "type": "check",
    "label": "程式檢查天氣與營業時間（不呼叫 AI）",
    "ms": 0
   },
   {
    "type": "no_change",
    "reason": "行程時段沒有降雨或公休問題",
    "llmSkipped": true,
    "usage": {
     "promptTokens": 0,
     "completionTokens": 0,
     "llmCalls": 0
    },
    "steps": 0,
    "model": "gpt-oss-120b",
    "ms": 1
   }
  ]
 }
};
