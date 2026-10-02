
// ══════════════════════════════════════════════════
// DATA
// ══════════════════════════════════════════════════
// 官方精選範本：由 app/explore-templates.js 依 poi-data.js 的真實景點即時組出。
//
// 這裡原本是 6 筆寫死的「社群行程」（羅馬競技場、京都嵐山、首爾弘大…，
// 作者 Marco旅遊／Ken／Jenny 與讚數、複製數、星等全是編的）。移除的理由有兩個：
//   1. 這個 app 沒有公開行程功能，沒有任何真實來源可以支撐那些社群訊號
//   2. 那些目的地的本地景點資料是 0，使用者點進去按生成也做不出行程
// 改成即時從真實資料組範本：只展示做得到的地方，數字也都是實算的。
//
// poi-data.js 是 defer 載入，所以這裡先留空，由 initOfficialTemplates()
// 在 DOMContentLoaded 後填入。
let OFFICIAL_TEMPLATES = [];
let DISTRICT_BUCKETS = {};

// wizard step definitions
const WIZ_TOTAL = 4;
const WIZ_STYLES = [
  {emoji:'🌿',label:'自然探索'},{emoji:'🍱',label:'美食之旅'},{emoji:'🎨',label:'深度文化'},
  {emoji:'👨‍👩‍👧',label:'親子同遊'},{emoji:'🏖',label:'海灘渡假'},{emoji:'💑',label:'蜜月浪漫'}
];
const WIZ_DESTS = [
  {emoji:'🌊',label:'台東'},{emoji:'�',label:'綠島'},{emoji:'🏝',label:'蘭嶼'},
  {emoji:'🏔',label:'知本'},{emoji:'🌅',label:'三仙台'},{emoji:'⛱️',label:'海濱公園'}
];

// ── STATE ──
// 按讚／評分／已複製這三個狀態隨假社群行程一起移除：
// 官方精選範本沒有社群互動，使用者按讚也沒有任何地方會看到。
// 舊的 localStorage 鍵（wai_likes / wai_ratings / wai_copied）留著不動，
// 不主動刪除使用者資料；不再讀寫即可。
let currentPreviewId = null;
let activeCat = 'all';
let searchQ = '';
let isLoggedIn = false;
let currentUser = null;
let myTrips = [];  // user's own trips
const currentItineraryId = 'TRIP-ZUOYING-240410-A';
const currentTripTitle = '左營站外 2 小時微旅行';
const currentTripWindow = { start: '14:00', end: '15:45' };
const currentInviteCode = 'WNDR-88A';
const currentJourneyMeetingPoint = '左營站 1 號出口';
const currentJourneyPreviewStops = [
  { time: '14:00', title: '左營站外集合', desc: '先在站外完成報到與旅伴確認，領隊會用 5 分鐘說明今天的步行節奏與拍照停留點。', tags: ['集合', '報到'] },
  { time: '14:20', title: '龍虎塔周邊散步', desc: '沿著蓮池潭外圍慢慢走，先看經典地標，再帶你抓到適合拍照的轉角視角。', tags: ['散步', '拍照', '地標'] },
  { time: '15:00', title: '果貿小吃短停', desc: '中段安排 20 分鐘自由覓食，可以補充飲料，也能快速買一份在地點心邊走邊吃。', tags: ['小吃', '休息'] },
  { time: '15:25', title: '回到站區結束', desc: '最後一段帶回左營站周邊，方便直接解散或銜接下一段行程，不用再繞路。', tags: ['返回', '解散'] }
];
let pendingJourneyEntry = null;
let wizStep = 0;
let wizData = {};
let collabState = null; // 多人協作面板的即時狀態：{ tripId, data, unsub, myEmail, myPrefsDraft }
let myCollabTripsUnsub = null;

// 將任意字串跳脫成可安全放進 innerHTML 的文字（避免成員顯示名稱等使用者輸入被當 HTML 執行）
function escapeHtml(text) {
  return String(text == null ? '' : text)
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
    .replace(/'/g, '&#39;');
}

// defer 的 <script>（poi-data.js / explore-templates.js）保證在 DOMContentLoaded
// 之前執行完；若事件已經過了就直接跑。
function whenDeferredScriptsReady(fn) {
  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', fn, { once: true });
  else fn();
}

// ── GEMINI & FIREBASE CONFIG ──
const GEMINI_MODEL = 'gemini-3-flash-preview';
const GEMINI_API_BASE = 'https://generativelanguage.googleapis.com/v1beta/models';
const GEMINI_LOCAL_KEY = 'TRAVEL_GEMINI_API_KEY';
// 後端代理模式：API_PROXY_BASE 設了就走同源代理（/api/vertex），金鑰由伺服器注入、不進前端。
// 未設則回退直連 Google（需前端自帶 VERTEX_API_KEY，僅本機開發用）。
const VERTEX_PROXY_BASE = ((window.TRAVEL_APP_CONFIG && window.TRAVEL_APP_CONFIG.API_PROXY_BASE) || '').replace(/\/$/, '');
const VERTEX_HOST = VERTEX_PROXY_BASE ? (VERTEX_PROXY_BASE + '/vertex') : 'https://aiplatform.googleapis.com';
const VERTEX_API_BASE = `${VERTEX_HOST}/v1`;
const VERTEX_LOCAL_KEY = 'TRAVEL_VERTEX_API_KEY';
const VERTEX_LOCAL_PROJECT = 'TRAVEL_VERTEX_PROJECT_ID';
let firebaseDb = null;
let firebaseEnabled = false;
let isGeneratingTrip = false;
let firebasePoiHintCache = { destination: '', block: '', updatedAt: 0 };
let wizardPrefetchPromise = null;
let wizardPreviewPromise = null;
let wizardPreviewPlan = null;
let wizardPreviewCacheKey = '';
let wizardPreviewStreamBuffer = '';
let wizardPreviewRequestKey = '';
let wizardPreviewDebounceTimer = null;

// ── INIT ──
(async () => {
  initFirebaseIfConfigured();
  await loadState();

  // ?view=friends|mytrips|explore：讓別頁（planner 通知中心）能直接深連結到指定分頁。
  const _viewParam = new URLSearchParams(window.location.search).get('view');
  if (_viewParam && ['explore', 'mytrips', 'friends'].includes(_viewParam)) {
    showMainView(_viewParam);
  }

  // Check if openPref=1 parameter is present in URL
  if (new URLSearchParams(window.location.search).get('openPref')) {
    setTimeout(() => {
      if (isLoggedIn && typeof openPrefWizard === 'function') {
        openPrefWizard();
      } else if (!isLoggedIn) {
        showToast('請先登入以修改個人喜好', 'orange');
        openLogin();
      }
    }, 600);
  }

  // Show welcome toast for fresh login (flag set by landing page)
  if (sessionStorage.getItem('wai_just_logged_in')) {
    sessionStorage.removeItem('wai_just_logged_in');
    setTimeout(() => {
      if (currentUser) showToast(`👋 歡迎，${currentUser.name}！開始規劃你的旅程吧`, 'green');
    }, 400);
  }

  renderUserMenu();
  // ★ poi-data.js 與 explore-templates.js 都是 defer，而這段 INIT 是立即執行的 IIFE
  //   （主程式的 <script> 沒有 defer，會在解析階段就跑）。直接在這裡叫
  //   initOfficialTemplates() 會拿到還沒載入的 WAI_POI_DATA，結果是 0 個範本。
  //   defer 腳本保證在 DOMContentLoaded 之前執行完，所以掛在那裡。
  whenDeferredScriptsReady(() => {
    initOfficialTemplates();
    renderDistrictTabs();
    const tabHost = document.getElementById('districtTabs');
    if (tabHost) tabHost.addEventListener('click', onDistrictTabClick);
    renderGrid();
    renderDestSidebar();
    renderHeroStats();
    renderHomeMyTrips();
  });
  renderSideMyTrips();
  renderMyTrips();

  // 恢復因切換頁面而中斷的行程生成
  const _pendingGen = JSON.parse(localStorage.getItem('wai_pending_gen') || 'null');
  if (_pendingGen && _pendingGen.tripId && _pendingGen.wData) {
    const _pendingTrip = myTrips.find(t => t.id === _pendingGen.tripId);
    if (_pendingTrip && (!_pendingTrip.stops || _pendingTrip.stops.length === 0)) {
      setTimeout(() => {
        showToast('偵測到未完成的行程，自動重新生成中…', 'blue');
        _doGeneration(_pendingTrip, _pendingGen.wData);
      }, 600);
    } else {
      localStorage.removeItem('wai_pending_gen');
    }
  }
})();

// ══════════════════════════════════════════════════
// FIREBASE & GEMINI
// ══════════════════════════════════════════════════
function initFirebaseIfConfigured() {
  const config = window.TRAVEL_APP_CONFIG && window.TRAVEL_APP_CONFIG.FIREBASE_CONFIG;
  if (!window.firebase || !config) return false;
  if (!config.apiKey || !config.projectId) return false;
  try {
    if (!firebase.apps.length) {
      firebase.initializeApp(config);
    }
    firebaseDb = firebase.firestore();
    // 強制 long-polling：避開會 400 Bad Request 的串流 Listen 通道，讓 onSnapshot 即時更新可靠。
    // merge:true 不覆蓋預設 host（消除 "overriding the original host" 警告）；ignoreUndefinedProperties 避免 undefined 欄位害寫入整批失敗。
    try { firebaseDb.settings({ experimentalForceLongPolling: true, ignoreUndefinedProperties: true, merge: true }); } catch (e) { /* 已啟動則略過 */ }
    firebaseAuth = firebase.auth();
    firebaseEnabled = true;
    return true;
  } catch (error) {
    console.warn('Firebase 初始化失敗：', error);
    firebaseEnabled = false;
    return false;
  }
}

function parseDurationFromText(text) {
  if (!text || typeof text !== 'string') return null;
  const durations = [];
  for (const m of text.matchAll(/(\d+(?:\.\d+)?)\s*[個个]?小時/g)) {
    const h = parseFloat(m[1]);
    if (h >= 0.25 && h <= 8) durations.push(Math.round(h * 60));
  }
  for (const m of text.matchAll(/(\d+)\s*分[鐘钟]?/g)) {
    const min = parseInt(m[1]);
    if (min >= 10 && min <= 480) durations.push(min);
  }
  for (const m of text.matchAll(/(\d+(?:\.\d+)?)\s*h(?:ours?|rs?)\b/gi)) {
    const h = parseFloat(m[1]);
    if (h >= 0.25 && h <= 8) durations.push(Math.round(h * 60));
  }
  for (const m of text.matchAll(/(\d+)\s*min(?:utes?)?\b/gi)) {
    const min = parseInt(m[1]);
    if (min >= 10 && min <= 480) durations.push(min);
  }
  if (!durations.length) return null;
  durations.sort((a, b) => a - b);
  return durations[Math.floor(durations.length / 2)];
}

async function fetchPlaceReviewsText(name, destination, apiKey) {
  if (!apiKey || !name) return '';
  try {
    const res = await placesFetch('searchText', 'places.reviews,places.editorialSummary',
      { textQuery: `${name} ${destination}`, languageCode: 'zh-TW', maxResultCount: 1 },
      10000, '評論查詢', apiKey);
    if (!res.ok) return '';
    const data = await res.json();
    const place = data.places && data.places[0];
    if (!place) return '';
    const texts = [];
    if (place.editorialSummary?.text) texts.push(place.editorialSummary.text);
    if (Array.isArray(place.reviews)) {
      place.reviews.forEach(r => {
        if (r.text?.text) texts.push(r.text.text);
        if (r.originalText?.text && r.originalText.text !== r.text?.text) texts.push(r.originalText.text);
      });
    }
    return texts.join('\n');
  } catch (e) {
    return '';
  }
}

async function buildFirebasePoiHintBlock(wizardData) {
  if (!firebaseEnabled || !firebaseDb) return '';
  const destination = (wizardData.dest || wizardData.destCustom || '').trim();
  if (!destination) return '';
  const apiKey = window.TRAVEL_APP_CONFIG && window.TRAVEL_APP_CONFIG.GOOGLE_MAPS_API_KEY;
  try {
    const snapshot = await firebaseDb.collection('poi_cache').where('destination', '==', destination).limit(20).get();
    if (snapshot.empty) return '';

    const enrichedPois = await Promise.all(snapshot.docs.map(async (doc) => {
      const poi = { ...doc.data() };
      const hasDuration = Number.isFinite(Number(poi.duration)) && Number(poi.duration) > 0;
      if (!hasDuration) {
        const descDuration = parseDurationFromText(poi.desc || poi.description || '');
        if (descDuration) {
          poi.duration = descDuration;
          doc.ref.update({ duration: descDuration }).catch(() => {});
        } else {
          const reviewText = await fetchPlaceReviewsText(poi.name, destination, apiKey);
          const reviewDuration = parseDurationFromText(reviewText);
          if (reviewDuration) {
            poi.duration = reviewDuration;
            doc.ref.update({ duration: reviewDuration }).catch(() => {});
          }
        }
      }
      return poi;
    }));

    const poiLines = enrichedPois.map(poi => {
      const poiLat = Number(poi.lat);
      const poiLng = Number(poi.lng);
      const coordinateHint = Number.isFinite(poiLat) && Number.isFinite(poiLng)
        ? `景點座標：lat ${poiLat}, lng ${poiLng}`
        : '景點座標：未知';
      const durationHint = Number.isFinite(Number(poi.duration)) && Number(poi.duration) > 0 ? `${poi.duration} 分鐘` : '未知';
      return `景點名稱：${poi.emoji ? poi.emoji + ' ' : ''}${poi.name || '未知'}\n${coordinateHint}\n建議停留時間：${durationHint}\n營業時間：${poi.businessHours || '未知'}\n地址：${poi.address || poi.location || '無'}\n描述：${poi.desc || poi.description || '無'}\n`;
    });

    return `【已驗證景點快取】規劃行程時「只能」從以下清單中挑選景點，嚴禁自行創造、想像或加入清單以外的任何景點（即使你認為該地點真實存在也不行）。景點名稱必須與「景點名稱：」欄位完全一致，禁止自行附加縣市名稱後綴（例：快取顯示「鐵花村」則輸出「鐵花村」，禁止改寫為「鐵花村台東」、「鐵花村臺東市」或任何變體）：\n${poiLines.join('\n')}`;
  } catch (error) {
    console.warn('Firebase POI 檢索失敗：', error);
    return '';
  }
}

function getWizardDestination(wizardData) {
  return String((wizardData && (wizardData.dest || wizardData.destCustom)) || '').trim();
}

// 從本地靜態檔 window.WAI_POI_DATA（由爬蟲 npm run export:local 產生）取某目的地的景點清單。
// dest 正規化：精確鍵 → 去掉「縣/市」後綴 → 與既有鍵互相包含比對。無資料回 []。
function getLocalPoiList(destination, data = (typeof window !== 'undefined' && window.WAI_POI_DATA) || null) {
  // 正規化：臺→台（OpenData 用「臺東」、前端用「台東」，否則比對不到）；去頭尾空白
  const norm = (s) => String(s || '').trim().replace(/臺/g, '台');
  const dest = norm(destination);
  if (!data || !dest) return [];
  const stripped = dest.replace(/[縣市]$/u, '').trim();
  // 合併所有「正規化後相符」的桶（例如「臺東」「台東」會被拆成兩桶，需合併），並以名稱去重
  const seen = new Set();
  const out = [];
  for (const key of Object.keys(data)) {
    if (key === '__generatedAt' || !Array.isArray(data[key]) || !data[key].length) continue;
    const k = norm(key);
    const match = k === dest || (stripped && k === stripped)
      || dest.includes(k) || k.includes(dest)
      || (stripped && (stripped.includes(k) || k.includes(stripped)));
    if (!match) continue;
    for (const poi of data[key]) {
      // ★ 依 kind 只取景點。poi-data 裡混了交通節點（台東火車站，duration 480 分）
      //   與 restaurant-data.js 已收錄的餐廳；不濾掉會被當成「景點候選」餵給 AI。
      //   舊資料沒有 kind 欄位時視為景點，維持相容。
      if (poi && poi.kind && poi.kind !== 'scenic') continue;
      // 暫停開放的景點不進候選。prompt 寫明「只能從此清單挑選」，
      // 濾掉之後 AI 就看不到，不會排進行程。
      // classifyAccess 只讀 businessHours/feeNote——絕不能讀 desc（見 business-hours.js）。
      if (typeof WAI_HOURS !== 'undefined' && WAI_HOURS && typeof WAI_HOURS.classifyAccess === 'function'
          && WAI_HOURS.classifyAccess(poi).avoid) continue;
      const id = poi && poi.name ? String(poi.name).trim() : '';
      if (id && seen.has(id)) continue;
      if (id) seen.add(id);
      out.push(poi);
    }
  }
  return out;
}

// 從獨立的餐廳快取 window.WAI_RESTAURANT_DATA 取某目的地的餐廳清單（重用景點桶比對邏輯）。
// restaurant-data.js 的項目沒有 kind 欄位，上面「沒有 kind 就視為景點」的相容分支
// 會讓它們全部通過，行為與過去一致。
function getLocalFoodList(destination) {
  return getLocalPoiList(destination, (typeof window !== 'undefined' && window.WAI_RESTAURANT_DATA) || null);
}

// ══════════════════════════════════════════════════
// F4 預算硬約束：查價 helper（鏡像 planner ai-travel-planner-v8.js 的同名函式）
// 門票：attraction-fee-config.js（人工維護，優先）→ poi-data.js 的 fee/feeNote（enrich:fees 寫入）。
// 餐費：restaurant-data.js 的 costPerPerson/costNote（crawl:food 寫入）。
// ══════════════════════════════════════════════════
function normalizeFeeName(s) { return String(s || '').replace(/\s/g, '').replace(/臺/g, '台').toLowerCase(); }

function getLocalPoiFeeMap(destination) {
  const map = new Map();
  const list = getLocalPoiList(destination) || [];
  for (const p of list) {
    if (!p || !p.name) continue;
    const hasFee = Number.isFinite(Number(p.fee));
    if (!hasFee && !p.feeNote) continue;
    map.set(normalizeFeeName(p.name), { fee: hasFee ? Number(p.fee) : null, feeNote: p.feeNote || '' });
  }
  return map;
}

function getCuratedFee(name) {
  const cfg = (typeof window !== 'undefined' && window.WAI_ATTRACTION_FEE) || null;
  if (!cfg || !cfg.paid || !name) return null;
  const key = normalizeFeeName(name);
  for (const k of Object.keys(cfg.paid)) {
    if (normalizeFeeName(k) === key) {
      const e = cfg.paid[k] || {};
      return { fee: Number.isFinite(Number(e.fee)) ? Number(e.fee) : null, feeNote: e.note || '' };
    }
  }
  return null;
}

function lookupStopFee(feeMap, name) {
  const curated = getCuratedFee(name);
  if (curated) return curated;
  if (!feeMap || !feeMap.size || !name) return null;
  return feeMap.get(normalizeFeeName(name)) || null;
}

function getLocalFoodCostMap(destination) {
  const map = new Map();
  const list = getLocalFoodList(destination) || [];
  for (const r of list) {
    if (!r || !r.name) continue;
    const hasCost = Number.isFinite(Number(r.costPerPerson));
    if (!hasCost && !r.costNote) continue;
    map.set(normalizeFeeName(r.name), { costPerPerson: hasCost ? Number(r.costPerPerson) : null, costNote: r.costNote || '' });
  }
  return map;
}

// 比對順序：exact → 去括號附註 exact → 前綴比對（至少 4 字）
function lookupStopFoodCost(costMap, stop) {
  if (!stop) return null;
  if (Number.isFinite(Number(stop.costPerPerson))) return { costPerPerson: Number(stop.costPerPerson), costNote: stop.costNote || '' };
  if (!costMap || !costMap.size || !stop.name) return null;
  const full = normalizeFeeName(stop.name);
  const exact = costMap.get(full);
  if (exact) return exact;
  const cut = String(stop.name).search(/[(（]/);
  if (cut > 0) {
    const hit = costMap.get(normalizeFeeName(String(stop.name).slice(0, cut)));
    if (hit) return hit;
  }
  let best = null, bestLen = 0;
  for (const [key, val] of costMap) {
    if (key.length >= 4 && key.length > bestLen && full.startsWith(key)) { best = val; bestLen = key.length; }
  }
  return best;
}

function stopIsFood(stop, foodCostMap) {
  if (typeof isFoodStop === 'function' && isFoodStop(stop)) return true;
  return !!lookupStopFoodCost(foodCostMap, stop);
}

function sumStopFeesPerPerson(stops, destination) {
  const feeMap = getLocalPoiFeeMap(destination);
  const foodMap = getLocalFoodCostMap(destination);
  let sum = 0;
  for (const s of (Array.isArray(stops) ? stops : [])) {
    if (stopIsFood(s, foodMap)) continue; // 餐廳歸餐飲
    const hit = lookupStopFee(feeMap, s && s.name);
    if (hit && Number.isFinite(hit.fee)) sum += hit.fee;
  }
  return Math.round(sum);
}

function sumStopFoodPerPerson(stops, destination) {
  const costMap = getLocalFoodCostMap(destination);
  let sum = 0;
  for (const s of (Array.isArray(stops) ? stops : [])) {
    const hit = lookupStopFoodCost(costMap, s);
    if (hit && Number.isFinite(hit.costPerPerson)) sum += hit.costPerPerson;
  }
  return Math.round(sum);
}

// F4：純 mutation 版替換（鏡像 planner swapStopWithAlternative 的 mutation 段，不做 toast/render/persist）
function swapStopToAlt(stop, alt) {
  const prev = { name: stop.name, lat: stop.lat, lng: stop.lng, desc: stop.desc, isOutdoor: stop.isOutdoor };
  stop.name = alt.name;
  stop.desc = alt.desc || '';
  stop.lat = alt.lat; stop.lng = alt.lng;
  stop.scenicCoordinates = { lat: alt.lat, lng: alt.lng };
  stop._lockedCoordinates = { lat: alt.lat, lng: alt.lng };
  stop.coordVerified = true;
  stop.isOutdoor = (alt.isOutdoor === true);
  stop.placeId = null; stop.businessHours = null;
  if (Array.isArray(stop.altNearby)) {
    stop.altNearby = stop.altNearby.filter((a) => a.name !== alt.name);
    if (prev.name && Number.isFinite(prev.lat)) {
      stop.altNearby.unshift({ name: prev.name, lat: prev.lat, lng: prev.lng, desc: prev.desc || '', rating: null, isOutdoor: prev.isOutdoor, distM: 0 });
    }
  }
}

// F4 預算硬約束：生成後估算「交通＋門票＋餐飲」人均總額，超出預算級距上限時，
// 逐次把「門票最貴的非用餐站」換成 altNearby 中免費（或查無票價）的替代景點，直到不超支或換無可換。
// 回 { overrunPerPerson, swaps:[{from,to,fee}] } 或 null（無預算上限／無需處理）。
function enforceBudgetCap(trip, wData) {
  const tier = getBudgetTier(wData && wData.budget);
  if (!tier || tier.perMax == null) return null; // 豪華（開放上限）或無預算 → 不做硬約束
  const stops = Array.isArray(trip && trip.stops) ? trip.stops : [];
  if (!stops.length) return null;
  const dest = wData.dest || wData.destCustom || trip.region || '台東';
  const transport = estimateTransportRough(dest, wData.transportMode, wData.people, wData.days).totalPerPerson;
  const feeMap = getLocalPoiFeeMap(dest);
  const foodMap = getLocalFoodCostMap(dest);
  const cap = tier.perMax;
  const usedNames = new Set(stops.map((s) => String((s && s.name) || '').trim()));
  const total = () => transport + sumStopFeesPerPerson(stops, dest) + sumStopFoodPerPerson(stops, dest);
  const swaps = [];
  let guard = 0;
  while (total() > cap && guard++ < 10) {
    // 挑門票最貴的非用餐站
    let target = null, targetFee = 0;
    for (const s of stops) {
      if (!s || s.type === 'start' || s.type === 'end' || !s.name) continue;
      if (stopIsFood(s, foodMap)) continue;
      const hit = lookupStopFee(feeMap, s.name);
      const fee = (hit && Number.isFinite(hit.fee)) ? hit.fee : 0;
      if (fee > targetFee && Array.isArray(s.altNearby) && s.altNearby.length) { target = s; targetFee = fee; }
    }
    if (!target || targetFee <= 0) break; // 沒有可降的付費景點 → 換不動
    // altNearby 中找免費或查無票價的替代（未被行程使用者）
    const alt = target.altNearby.find((a) => {
      if (!a || !a.name || usedNames.has(String(a.name).trim())) return false;
      const aFee = lookupStopFee(feeMap, a.name);
      return !aFee || !Number.isFinite(aFee.fee) || aFee.fee === 0;
    });
    if (!alt) break; // 這站換不動，其他站門票更低也救不了 → 結束
    usedNames.delete(String(target.name).trim());
    const fromName = target.name;
    swapStopToAlt(target, alt);
    usedNames.add(String(target.name).trim());
    swaps.push({ from: fromName, to: alt.name, fee: targetFee });
  }
  const overrun = Math.max(0, Math.round(total() - cap));
  if (!swaps.length && overrun <= 0) return null;
  return { overrunPerPerson: overrun, swaps };
}

// ── Plan B 替代景點（查看替換）：生成時保留 ──
// 本地 POI 無室內/戶外欄位，用名稱＋描述關鍵字啟發式判斷（供「下雨換室內」用）。
function classifyIndoorOutdoor(name, desc) {
  const t = String(name || '') + ' ' + String(desc || '');
  const indoor = /館|博物|美術|文創|展覽|展館|中心|咖啡|餐廳|飯店|商場|市集|室內|廟|宮|寺|教堂|教會|書店|酒莊|觀光工廠|文物|故事館/;
  const outdoor = /公園|海|沙灘|山|步道|瀑布|森林|部落|漁港|濕地|景觀|農場|牧場|溫泉|草原|溪|湖|島|岬|燈塔|花海|稻田|大道|自行車/;
  if (outdoor.test(t) && !indoor.test(t)) return false; // 戶外
  if (indoor.test(t)) return true;                       // 室內
  return false;                                          // 預設戶外（台東多戶外景點）
}
function haversineM(a, b) {
  if (!a || !b) return Infinity;
  const R = 6371000, toR = (d) => d * Math.PI / 180;
  const dLat = toR(b.lat - a.lat), dLng = toR(b.lng - a.lng);
  const s = Math.sin(dLat / 2) ** 2 + Math.cos(toR(a.lat)) * Math.cos(toR(b.lat)) * Math.sin(dLng / 2) ** 2;
  return 2 * R * Math.asin(Math.min(1, Math.sqrt(s)));
}
// 為每個非起訖站附上「附近替代景點」（本地池扣掉已排入者，依距離排序），並標室內/戶外。
function attachStopAlternatives(stops, destination) {
  if (!Array.isArray(stops) || !stops.length) return stops;
  const pool = getLocalPoiList(destination) || [];
  const usedNames = new Set(stops.map((s) => String((s && s.name) || '').trim()));
  stops.forEach((stop) => {
    if (!stop || stop.type === 'start' || stop.type === 'end' || !stop.name) return;
    stop.isOutdoor = classifyIndoorOutdoor(stop.name, stop.desc);
    const here = (Number.isFinite(stop.lat) && Number.isFinite(stop.lng)) ? { lat: stop.lat, lng: stop.lng } : null;
    stop.altNearby = pool
      .filter((p) => p && p.name && !usedNames.has(String(p.name).trim()) && Number.isFinite(p.lat) && Number.isFinite(p.lng))
      .map((p) => ({
        name: p.name, lat: p.lat, lng: p.lng,
        desc: String(p.desc || '').slice(0, 80),
        rating: p.rating || null,
        isOutdoor: classifyIndoorOutdoor(p.name, p.desc),
        distM: here ? Math.round(haversineM(here, { lat: p.lat, lng: p.lng })) : null
      }))
      .sort((a, b) => (a.distM ?? Infinity) - (b.distM ?? Infinity))
      .slice(0, 6);
  });
  return stops;
}

// 用本地景點清單組「【已驗證景點快取】」hint（格式與 buildFirebasePoiHintBlock 一致），交給 AI 只做排序。
function buildLocalPoiHintBlock(destination) {
  const pois = getLocalPoiList(destination);
  if (!pois.length) return '';
  const poiLines = pois.slice(0, 40).map((poi) => {
    const poiLat = Number(poi.lat);
    const poiLng = Number(poi.lng);
    const coordinateHint = Number.isFinite(poiLat) && Number.isFinite(poiLng)
      ? `景點座標：lat ${poiLat}, lng ${poiLng}`
      : '景點座標：未知';
    const durationHint = Number.isFinite(Number(poi.duration)) && Number(poi.duration) > 0 ? `${poi.duration} 分鐘` : '未知';
    return `景點名稱：${poi.emoji ? poi.emoji + ' ' : ''}${poi.name || '未知'}\n${coordinateHint}\n建議停留時間：${durationHint}\n營業時間：${poi.businessHours || '未知'}\n地址：${poi.address || poi.location || '無'}\n描述：${poi.desc || poi.description || '無'}\n`;
  });
  return `【已驗證景點快取】規劃行程時「只能」從以下清單中挑選景點，嚴禁自行創造、想像或加入清單以外的任何景點（即使你認為該地點真實存在也不行）。景點名稱必須與「景點名稱：」欄位完全一致，禁止自行附加縣市名稱後綴（例：快取顯示「鐵花村」則輸出「鐵花村」，禁止改寫為「鐵花村台東」、「鐵花村臺東市」或任何變體）：\n${poiLines.join('\n')}`;
}

async function prefetchFirebasePoiHint(wizardData, force = false) {
  const destination = getWizardDestination(wizardData);
  if (!destination) return '';
  if (!force && firebasePoiHintCache.destination === destination && firebasePoiHintCache.block) {
    return firebasePoiHintCache.block;
  }
  if (wizardPrefetchPromise && !force) return wizardPrefetchPromise;
  wizardPrefetchPromise = buildFirebasePoiHintBlock(wizardData)
    .then((block) => {
      firebasePoiHintCache = {
        destination,
        block: String(block || ''),
        updatedAt: Date.now()
      };
      return firebasePoiHintCache.block;
    })
    .catch((error) => {
      console.warn('Firebase hint prefetch failed:', error);
      return '';
    })
    .finally(() => {
      wizardPrefetchPromise = null;
    });
  return wizardPrefetchPromise;
}

async function fetchAndBuildLiveMapsHint(wizardData) {
  const destination = getWizardDestination(wizardData);
  if (!destination) return '';
  const destCenter = getDestinationCenter(destination);
  const interests = wizardData.interests || [];
  const places = await fetchGoogleMapsPoiList(destination, interests, destCenter);
  if (!places.length) return '';
  return buildLiveMapsPoiHintBlock(places);
}

// 將「天數字串」統一轉成分鐘（支援 N小時 / 兩天一夜，並相容舊字串）
function parseDurationMinutes(days) {
  const s = String(days || '').trim();
  // 過夜行程一律以兩日活動估算；'3天'/'三天兩夜' 為舊資料相容別名，視同兩天一夜（本專案上限兩天一夜）
  if (s === '2天' || s === '兩天一夜' || s === '3天' || s === '三天兩夜') return 960;
  const h = s.match(/^(\d+(?:\.\d+)?)\s*小時$/);
  if (h) return Math.round(parseFloat(h[1]) * 60);          // N小時 / N.5小時
  if (s === '半天') return 240;                              // 舊資料相容
  if (s === '1天')  return 480;                              // 舊資料相容
  return 480;                                                // fallback
}

function isLongTrip(days) {
  const s = String(days || '');
  return s === '2天' || s === '兩天一夜' || s === '3天' || s === '三天兩夜';
}

// 天數字串 → 過夜行程天數（單日回 1）。本專案上限兩天一夜，舊 '3天' 資料視同 2 天。
function getWizardDayCount(days) {
  const s = String(days || '');
  if (s === '2天' || s === '兩天一夜' || s === '3天' || s === '三天兩夜') return 2;
  return 1;
}

// 人數 → 整數（容錯舊字串：「6人」→6、「3-4人」→3、「5-8人」→5、「1人」→1）
function getPeopleCount(people) {
  const n = parseInt(String(people || ''), 10);
  return Number.isFinite(n) && n > 0 ? Math.min(20, n) : 2;
}
// 人數分級：影響站數（stopDelta）與 prompt 的景點/用餐指示
function getPeopleProfile(people) {
  const count = getPeopleCount(people);
  if (count <= 1) return { count, tier: 'solo', stopDelta: 0,
    planRule: '獨旅行程：重視個人節奏與安靜體驗，景點不須考慮大型團體容納量，可含較私密、需排隊或單人友善的場所',
    introSentence: '根據用戶需求生成一份適合獨旅者的可執行行程，重視個人節奏、自我探索與彈性安排，景點偏向可獨自前往且不須等候大型團體的地點。' };
  if (count <= 3) return { count, tier: 'small', stopDelta: 0,
    planRule: `${count} 人小型同行：安排彈性高，可含精緻小店、咖啡廳或需短暫排隊的人氣店；用餐選擇不受大團體限制`,
    introSentence: `根據用戶需求生成一份適合 ${count} 人小型同行的可執行行程，安排靈活、可兼顧個別喜好，景點與餐廳選擇彈性。` };
  if (count <= 6) return { count, tier: 'medium', stopDelta: -1,
    planRule: `${count} 人中型同行：優先安排可容納 ${count} 人的餐廳並預留訂位時段，避免座位極少或排隊過久的店；景點適合多人共同停留，站與站之間預留集合與移動緩衝`,
    introSentence: `根據用戶需求生成一份適合 ${count} 人中型團體的可執行行程，考慮團體動態、優先可容納多人的餐廳並安排共同用餐時段。` };
  return { count, tier: 'large', stopDelta: -2,
    planRule: `${count} 人大型團體：景點與餐廳必須能容納大團體、強烈建議事先訂位並安排共同用餐；避免狹小空間、長時間排隊或單人體驗型場所；集合、上下車與移動需預留更多緩衝，站點精簡、每站停留拉長`,
    introSentence: `根據用戶需求生成一份適合 ${count} 人大型團體的可執行行程，全程選擇可接待大團體的場所、預留訂位與共同用餐時段，並安排充足的集合與移動緩衝。` };
}

// === 預算（人均）階梯與換算 ===
// 預算一律以「人均」表示；整團總額 = 人均 × Step 0 選定人數，於 UI 與 AI prompt 同步呈現，
// 避免「$500-1500」這類金額看不出是單人還是整團。
const BUDGET_TIERS = [
  { key: '節省', perMin: 0,    perMax: 500 },
  { key: '適中', perMin: 500,  perMax: 1500 },
  { key: '舒適', perMin: 1500, perMax: 3000 },
  { key: '豪華', perMin: 3000, perMax: null } // 開放上限
];

// 把任意 budget 字串（新人均 token／舊籠統字串／社群範例 `$2,000`）對應回某個 tier
function getBudgetTier(value) {
  const str = String(value || '').trim();
  if (!str) return null;
  // 1) 直接含 tier key（如「節省」「適中（每人…）」）
  const byKey = BUDGET_TIERS.find(t => str.includes(t.key));
  if (byKey) return byKey;
  // 2) 退回用字串中的金額對應級距（社群範例如 `$2,000`、舊字串如 `$500-1500`）
  const nums = (str.match(/\d[\d,]*/g) || []).map(n => parseInt(n.replace(/,/g, ''), 10)).filter(Number.isFinite);
  if (nums.length) {
    const amount = Math.max(...nums); // 取較大值代表該預算量級
    return BUDGET_TIERS.find(t => t.perMax == null ? amount >= t.perMin : amount <= t.perMax) || BUDGET_TIERS[BUDGET_TIERS.length - 1];
  }
  return null;
}

// 千分位
function formatMoney(n) {
  return '$' + Math.round(n).toLocaleString('en-US');
}

// 由 budget 值 + 人數產生人均/總額描述：
// { tier, perPersonLabel:'每人 $500–1500', groupLabel:'2 人約共 $1,000–3,000', promptText:'每人 $500–1500（2 人共約 $1,000–3,000）' }
function describeBudget(value, people) {
  const tier = getBudgetTier(value);
  if (!tier) return null;
  const count = getPeopleCount(people);
  const open = tier.perMax == null;
  const perPersonLabel = open
    ? `每人 ${formatMoney(tier.perMin)} 以上`
    : (tier.perMin > 0 ? `每人 ${formatMoney(tier.perMin)}–${formatMoney(tier.perMax)}` : `每人 ${formatMoney(tier.perMax)} 內`);
  const groupMin = tier.perMin * count;
  const groupMax = open ? null : tier.perMax * count;
  const groupTotal = open
    ? `${formatMoney(groupMin || tier.perMin)}+`
    : (tier.perMin > 0 ? `${formatMoney(groupMin)}–${formatMoney(groupMax)}` : `${formatMoney(groupMax)} 內`);
  const groupLabel = `${count} 人約共 ${groupTotal}`;
  const promptText = `每人 ${open ? `${formatMoney(tier.perMin)} 以上` : (tier.perMin > 0 ? `${formatMoney(tier.perMin)}–${formatMoney(tier.perMax)}` : `${formatMoney(tier.perMax)} 內`)}（${count} 人共約 ${groupTotal}）`;
  return { tier, count, perPersonLabel, groupLabel, promptText };
}

// === 交通費估算（讀 window.WAI_COST_CONFIG；缺檔時各 helper 回退為 0／null）===
function getCostConfig() {
  return (typeof window !== 'undefined' && window.WAI_COST_CONFIG && typeof window.WAI_COST_CONFIG === 'object') ? window.WAI_COST_CONFIG : null;
}
// 目的地 → 離島每人往返船票（非離島回 0）。正規化沿用 getLocalPoiList：臺→台、去縣市、寬鬆比對。
function getFerryRoundTrip(destination) {
  const cfg = getCostConfig();
  if (!cfg || !cfg.ferryRoundTrip) return 0;
  const norm = (s) => String(s || '').trim().replace(/臺/g, '台');
  const dest = norm(destination);
  if (!dest) return 0;
  const stripped = dest.replace(/[縣市]$/u, '').trim();
  for (const key of Object.keys(cfg.ferryRoundTrip)) {
    const k = norm(key);
    if (k === dest || (stripped && k === stripped) || dest.includes(k) || k.includes(dest) || (stripped && (stripped.includes(k) || k.includes(stripped)))) {
      const v = Number(cfg.ferryRoundTrip[key]);
      if (Number.isFinite(v) && v > 0) return v;
    }
  }
  return 0;
}
// days 字串 → 天數（'2天'→2；'5小時'→1）
function getTripDayCount(days) {
  const m = String(days || '').match(/(\d+)\s*天/);
  if (m) return Math.max(1, parseInt(m[1], 10));
  return 1;
}
// 生成前（尚無站點）的每人交通粗估：每日站間移動額度 × 天數 + 離島船票
function estimateTransportRough(destination, mode, people, days) {
  const cfg = getCostConfig();
  const count = getPeopleCount(people);
  const m = (mode && cfg && cfg.modeRates && cfg.modeRates[mode]) ? mode : 'car';
  const dayCount = getTripDayCount(days);
  const allowance = cfg && cfg.dailyMoveAllowance && Number.isFinite(Number(cfg.dailyMoveAllowance[m])) ? Number(cfg.dailyMoveAllowance[m]) : 0;
  const movePerPerson = Math.round(allowance * dayCount);
  const ferryPerPerson = getFerryRoundTrip(destination);
  return { ferryPerPerson, movePerPerson, totalPerPerson: ferryPerPerson + movePerPerson, count };
}
// 由人均級距範圍扣掉交通 → 可動用（餐飲/活動）。回 { label, min, max, open } 供 prompt/卡片共用；無 budget 回 null。
function buildBudgetBreakdown(budget, people, transportPerPerson) {
  const tier = getBudgetTier(budget);
  if (!tier) return null;
  const t = Math.max(0, Math.round(Number(transportPerPerson) || 0));
  const open = tier.perMax == null;
  const dMin = Math.max(0, tier.perMin - t);
  const dMax = open ? null : Math.max(0, tier.perMax - t);
  const label = open
    ? `${formatMoney(dMin)}+`
    : (tier.perMin > 0 ? `${formatMoney(dMin)}–${formatMoney(dMax)}` : `${formatMoney(dMax)} 內`);
  return { tier, transport: t, min: dMin, max: dMax, open, label };
}

function getDurationStopRange(days, people) {
  const base = isLongTrip(days)
    ? { min: 12, max: 18 }
    : (() => { const m = parseDurationMinutes(days); const mid = Math.max(1, Math.round(m / 55)); return { min: Math.max(1, mid - 1), max: mid + 2 }; })();
  const d = getPeopleProfile(people).stopDelta;
  const min = Math.max(1, base.min + d);
  return { min, max: Math.max(min, base.max + d) };
}

function calcTripEndTime(startTime, days) {
  const duration = parseDurationMinutes(days);
  const startMin = timeStringToMinutes(normalizeClockInput(startTime, '09:00'));
  return minutesToTimeString(startMin + duration);
}

function getLastDayStartTime(endTime, firstDayEndMinutes = 0) {
  const endMinutes = timeStringToMinutes(normalizeClockInput(endTime, '12:00'));
  const usualStart = Math.min(9 * 60, Math.max(0, endMinutes - 60));
  // 第一天若玩到午夜後，第二天不能在第一天結束前又重新開始。
  const afterFirstDay = Math.max(0, firstDayEndMinutes - 1440);
  return minutesToTimeString(Math.max(usualStart, Math.min(endMinutes, afterFirstDay)));
}

function getDay2StartTime(wizardData, firstDayEndMinutes = 0) {
  const selected = normalizeClockInput(wizardData.day2StartTime, '');
  return selected || getLastDayStartTime(wizardData.day2EndTime, firstDayEndMinutes);
}

function reconcileDay2StartTime() {
  const selected = normalizeClockInput(wizData.day2StartTime, '');
  if (!selected) return false;
  const firstEnd = timeStringToMinutes(wizData.startTime || '09:00')
    + (Number(wizData.day1Hours) || 8) * 60;
  const secondStart = 1440 + timeStringToMinutes(selected);
  const secondEnd = 1440 + timeStringToMinutes(wizData.day2EndTime || '12:00');
  if (secondStart >= firstEnd && secondStart < secondEnd) return false;
  wizData.day2StartTime = '';
  return true;
}

// 計算行程每日時間窗口（單日：start–end；多日：第一天時數 + 中間日 8 小時 + 最後一天結束時間）
// F5：day2EndTime 語意泛化為「最後一天玩到幾點」；保留 day1End/day2End 鍵讓既有兩天邏輯不變。
function getTripDayWindows(wizardData) {
  const start = normalizeClockInput(wizardData.startTime, '09:00');
  if (!isLongTrip(wizardData.days)) {
    return { multi: false, start, end: calcTripEndTime(start, wizardData.days) };
  }
  const dayCount = getWizardDayCount(wizardData.days);
  const d1h = Math.min(12, Math.max(1, Math.round(Number(wizardData.day1Hours) || 8)));
  const day1End = minutesToTimeString(timeStringToMinutes(start) + d1h * 60);
  const lastEnd = normalizeClockInput(wizardData.day2EndTime, '12:00'); // 語意：最後一天玩到幾點
  const lastStart = getDay2StartTime(wizardData, timeStringToMinutes(day1End));
  const dayWindows = [];
  for (let i = 1; i <= dayCount; i++) {
    if (i === 1) dayWindows.push({ day: 1, start, end: day1End });
    else if (i === dayCount) dayWindows.push({ day: i, start: lastStart, end: lastEnd });
    else dayWindows.push({ day: i, start, end: minutesToTimeString(timeStringToMinutes(start) + 8 * 60) }); // 中間日約 8 小時
  }
  return { multi: true, start, dayCount, dayWindows, day1Start: start, day1End, day1Hours: d1h, day2Start: lastStart, day2End: lastEnd };
}

function getTripActiveMinutes(wizardData) {
  const windows = getTripDayWindows(wizardData);
  if (!windows.multi) return parseDurationMinutes(wizardData.days);
  return windows.dayWindows.reduce((total, window) =>
    total + Math.max(0, timeStringToMinutes(window.end) - timeStringToMinutes(window.start)), 0);
}

function hasValidMultiDayWindow(wizardData) {
  const windows = getTripDayWindows(wizardData);
  if (!windows.multi) return true;
  const firstEnd = timeStringToMinutes(windows.day1End);
  const lastStart = 1440 + timeStringToMinutes(windows.day2Start);
  const lastEnd = 1440 + timeStringToMinutes(windows.day2End);
  return lastStart < lastEnd && firstEnd <= lastStart;
}

function getPromptRuleLines(wizardData, mode) {
  const days = wizardData.days || '1天';
  const people = wizardData.people || '2人';
  const isSolo = people === '1人';
  const pace = wizardData.pace || '平衡';
  const interests = (wizardData.interests || []).join('、') || '多元';
  const detailed = mode !== 'preview';
  const startTime = normalizeClockInput(wizardData.startTime, '09:00');
  const win = getTripDayWindows(wizardData);
  const endTime = win.multi ? win.day2End : win.end;
  const lines = [
    win.multi
      ? `1. 這是「兩天一夜」行程：${(win.dayWindows || []).map(w => `第${w.day}天 ${w.start}–${w.end}`).join('、')}。請依 ${win.dayCount} 天分配景點，最後一天行程明顯較短（玩到 ${win.day2End} 後返程）`
      : `1. 針對 ${days} 的時間量身打造，行程時間窗口為 ${startTime} ～ ${endTime}`,
    `2. ${getPeopleProfile(people).planRule}`,
    `3. 符合 ${pace} 的節奏`,
    `4. 強調 ${interests} 面向`
  ];

  if (detailed) {
    const { min, max } = getDurationStopRange(days, people);
    lines.push(`5. 必須包含 ${min}-${max} 個主要停留點（不得低於 ${min} 個），行程從 ${startTime} 開始、行程最後一站的結束時間必須在 ${endTime} 前後 15 分鐘內，嚴禁行程在 ${endTime} 的 15 分鐘前完全結束；所有停留點與用餐站都「只能」從上方提供的景點快取／餐廳候選清單中挑選，若清單景點不足，寧可安排較少的停留點或延長各站停留時間，也嚴禁加入清單以外的任何地點（禁止使用「在地午餐」等模糊名稱）`);
    lines.push('6. 每站包含時間、景點名稱、推薦理由、營業時間；請勿在 JSON 中輸出 lat/lng 座標或廁所資料，座標與廁所由系統自動查詢');
    lines.push('7. 景點須考慮營業時間，使用正式可定位名稱；嚴禁使用「在地午餐」、「當地早餐」、「附近餐廳」、「在地美食」等任何模糊飲食描述作為景點名稱——餐飲景點必須填入具體店家名稱（例如「池上飯包文化故事館」、「春一枝冰棒」），確保遊客能透過 Google Maps 直接搜尋到');
    lines.push('8. duration 為建議停留分鐘數（正整數，勿省略），依景點實際規模與特性靈活設定，禁止固定使用 30、60、90、120 等整數倍，應依景點規模自行判斷填入合理的非整數倍值；參考上限：觀景台/制高點 20-45、步道/健行路線 35-70、湖泊/海灘 25-55、公園/廣場 20-45、美食/小吃 15-35、咖啡廳 25-50、博物館/文化館 45-85、市場/夜市 35-65、廟宇/歷史景點 15-40；例：小型展館填 40、大型步道填 55、湖畔散步填 35');
    lines.push('9. 景點名稱禁止在名稱後自行附加縣市或行政區名稱（如「台東」、「臺東市」、「卑南鄉」等）；若上方快取清單有對應景點，名稱必須與快取完全一致，不得改寫或加上任何後綴');
    lines.push('10. 大型景區（如三仙台、伯朗大道、鯉魚潭）請拆成該景區內 2–4 個具體子景點／觀景點（例：三仙台觀景台、三仙台跨海拱橋、比西里岸部落、礫石灘），用正式可定位名稱，不要只填一個籠統的景區名；系統會自動把鄰近子景點合併成一站並標示範圍');
    if (!win.multi) {
      // 單日行程：時間窗涵蓋用餐時段就強制安排具體店名的用餐站（餐廳候選由系統即時提供）
      const _sM = timeStringToMinutes(startTime);
      const _eM = timeStringToMinutes(endTime);
      const _overlaps = (a, b) => _sM <= b && _eM >= a;
      const _meals = [];
      if (_overlaps(11 * 60 + 30, 13 * 60 + 30)) _meals.push('午餐（安排在約 12:00–13:00）');
      if (_overlaps(17 * 60 + 30, 19 * 60 + 30)) _meals.push('晚餐（安排在約 18:00–19:00）');
      if (_meals.length) {
        lines.push(`${lines.length + 1}. 必須安排${_meals.join('與')}用餐站，使用具體店家名稱（優先從上方「即時餐廳候選」清單挑選，名稱需完全一致；嚴禁「在地午餐」「附近餐廳」等模糊名稱），並排在對應用餐時段`);
      }
    }
    if (isLongTrip(days)) {
      const _dc = win.dayCount || 2;
      lines.push(`13. ${_dc} 天分配：${(win.dayWindows || []).map(w => `第${w.day}天 ${w.start}–${w.end}`).join('、')}；第一天安排主要景點與過夜，最後一天（第${_dc}天）最後一站需在 ${win.day2End} 前後 15 分鐘結束並返程，最後一天景點數量明顯少於其他天`);
      lines.push(`14. 每個 stop 必須輸出 dayIndex 欄位（整數 1–${_dc}），標明該站屬於第幾天；同一天的站依時間排序，dayIndex 不可倒退`);
      lines.push('15. 行程規劃應避開塞車路段，優先交通順暢、少折返路線；若時間落在 07:00-09:30 或 17:00-19:30，降低幹道與商圈壅塞路段經過頻率');
      lines.push(`16. 第一天安排 12:00-13:30 彈性午餐；最後一天若於 ${win.day2End} 前結束，午餐視結束時間彈性安排`);
      const _lodging = String(wizardData.lodgingName || '').trim();
      lines.push(_lodging
        ? `17. 住宿錨點（必須輸出成站）：住宿地點為「${_lodging}」。除最後一天外，每天的「最後一站」必須就是這個住宿本身（name 完全等於「${_lodging}」、emoji 🏨、desc 寫「回住宿休息」、停留 0 分），隔天的「第一站」也必須是同一個住宿（desc 寫「從住宿出發」、停留 0 分）；其餘景點請安排在住宿車程 20 分鐘內，避免每天大幅折返`
        : `17. 未指定住宿：請在 reply 用一句話推薦適合的住宿區域，並讓每天最後一站鄰近該區域、隔天第一站由該區域出發`);
    }
    const dest = wizardData.dest || wizardData.destCustom || '台東';
    const defaultHub = getDefaultTransitHub(dest);
    const startLoc = (wizardData.startLocation || '').trim() || defaultHub;
    const endLoc = (wizardData.endLocation || '').trim() || defaultHub;
    lines.push(`${lines.length + 1}. 行程第一站必須從「${startLoc}」出發，這是旅客的集合起始點，需計算從此處到第一景點的移動時間`);
    lines.push(`${lines.length + 1}. 行程最後一站必須返回「${endLoc}」，這是旅客的結束點，需計算從最後景點返回此處的移動時間`);
    const _rawStartLoc = (wizardData.startLocation || '').trim();
    const _rawEndLoc   = (wizardData.endLocation   || '').trim();
    if (_rawStartLoc || _rawEndLoc) {
      const _from = _rawStartLoc || dest;
      const _to   = _rawEndLoc   || dest;
      lines.push(`${lines.length + 1}. 景點請沿「${_from}」→「${dest}」→「${_to}」路線廊道分布（各景點距此路線盡量在 5 公里內，景點不足時可擴展至 10 公里），行程方向由起點往目的地核心再往終點收尾，不要安排需要大幅折返的景點，避免景點全部集中在${dest}核心而忽略沿途地點`);
    }
    if (wizardData.transportMode === 'car' || wizardData.transportMode === 'scooter') {
      const _vehicle = wizardData.transportMode === 'scooter' ? '機車' : '汽車';
      lines.push(`${lines.length + 1}. 本行程以${_vehicle}自駕為主：挑選景點時請一併考量停車可行性，盡量避開停車極度困難的點；對停車較不易的景點（如熱門老街、夜市、假日海灘、市區廟宇），請在該站 desc 末尾用一句話提醒停車狀況與建議（例如改停就近付費停車場、預留找車位的時間）。`);
    }
    // F3 自動避雨：行程期間有降雨機率偏高的日子 → 該日優先室內景點
    const _rain = wizardData._rainOutlook;
    if (_rain && Array.isArray(_rain.rainyDates) && _rain.rainyDates.length) {
      const _rainTxt = _rain.rainyDates.map(r => `${r.date}（降雨機率約 ${r.pop}%）`).join('、');
      lines.push(`${lines.length + 1}. ☔ 天氣預報：${_rainTxt} 降雨機率偏高，該日請優先安排室內景點（博物館、文化館、市場、咖啡廳、展館等），戶外景點（海灘、步道、觀景台）盡量集中在其他日子或改為可避雨的替代點，並在 reply 用一句話提醒使用者記得帶傘`);
    }
  } else {
    lines.push('5. 僅需回傳可用於預覽的 title + stops 骨架（每站至少 name/time/desc）');
  }
  return lines;
}

// 節奏顯示名稱：UI 顯示更直白，但內部值維持 輕快/平衡/悠閒（slotMinutes 等邏輯依賴）
const PACE_LABELS = { '輕快': '緊湊充實', '平衡': '標準步調', '悠閒': '放鬆慢遊' };
const PACE_EMOJI = { '輕快': '🏃', '平衡': '🚶', '悠閒': '🐢' };
function paceLabel(v) { return PACE_LABELS[v] || v || '標準步調'; }

// 帳號長期偏好（註冊時設定）與本趟精靈選擇的合併規則：
// - 本趟選擇（wizData.interests / pace）為「這趟行程」的權威值，主導景點安排與時間節奏。
// - 帳號長期偏好（profilePrefs，來自 selectTripMode 快照，否則退回 currentUser.preferences）
//   作為「次要口味參考」與「跨行程硬性禁忌（avoid）」注入 prompt，不與本趟選擇衝突。
function getEffectivePrefs(wizardData) {
  const wd = wizardData || {};
  const profile = wd.profilePrefs
    || (typeof currentUser !== 'undefined' && currentUser && currentUser.preferences)
    || null;
  const tripInterests = Array.isArray(wd.interests) ? wd.interests.filter(Boolean) : [];
  const longInterests = (profile && Array.isArray(profile.interests)) ? profile.interests.filter(Boolean) : [];
  const tripPace = wd.pace || (profile && profile.pace) || '平衡';
  const longPace = (profile && profile.pace) || '';
  const avoid = (profile && typeof profile.avoid === 'string') ? profile.avoid.trim() : '';
  const avoidTags = (profile && Array.isArray(profile.avoidTags)) ? profile.avoidTags.filter(Boolean) : [];
  // 長期興趣中、本趟未涵蓋的部分（差異 = 衝突來源）→ 降為次要參考
  const extraLong = longInterests.filter(i => !tripInterests.includes(i));
  return { tripInterests, longInterests, extraLong, tripPace, longPace, avoid, avoidTags, hasProfile: !!profile };
}

// 產生 prompt 的「個人偏好」段落：本趟優先、長期次要、禁忌硬性遵守，三者層級分明不衝突
function buildPreferenceLines(wizardData) {
  // 多人共作：以團體彙整偏好（興趣聯集/節奏多數決/預算平均/禁忌聯集）取代單人偏好
  if (wizardData && wizardData.groupProfile && window.WAI_COLLAB) {
    return WAI_COLLAB.buildGroupPreferenceLines(wizardData.groupProfile);
  }
  const p = getEffectivePrefs(wizardData);
  const lines = [];
  if (p.longPace && p.longPace !== p.tripPace) {
    lines.push(`行程節奏：${paceLabel(p.tripPace)}（${p.tripPace}）（本趟指定，以此為準；使用者長期偏好節奏為「${paceLabel(p.longPace)}（${p.longPace}）」，僅供參考）`);
  } else {
    lines.push(`行程節奏：${paceLabel(p.tripPace)}（${p.tripPace}）`);
  }
  const tripInt = p.tripInterests.length ? p.tripInterests.join('、') : '多元體驗';
  lines.push(`本趟興趣方向：${tripInt}（規劃景點類型以此為主）`);
  if (p.extraLong.length) {
    lines.push(`使用者長期興趣偏好：${p.extraLong.join('、')}（次要參考；可在不影響本趟興趣的前提下適度融入，若與本趟方向衝突一律以本趟為準）`);
  }
  const avoidTagStr = (p.avoidTags || []).join(' ');
  const avoidBoth = [avoidTagStr, p.avoid].filter(Boolean).join('；其他：');
  if (avoidBoth) {
    lines.push(`⚠️ 個人禁忌／需避免（此為帳號設定，所有行程務必全程遵守，包含餐廳與景點挑選）：${avoidBoth}`);
  }
  return lines;
}

// === 希望景點 ↔ 個人禁忌衝突偵測（純 UI 警告用）===
// 從帳號偏好的 avoidTags（去 #）與 avoid 自由文字取出可比對的關鍵字，去除常見前後綴。
function getAvoidKeywords() {
  const pf = (typeof currentUser !== 'undefined' && currentUser && currentUser.preferences) || {};
  const tags = Array.isArray(pf.avoidTags) ? pf.avoidTags : [];
  const free = typeof pf.avoid === 'string' ? pf.avoid : '';
  const raw = [
    ...tags.map(t => String(t).replace(/^#/, '')),
    ...free.split(/[、,，;；。\s/|]+/)
  ];
  const seen = new Set();
  const out = [];
  raw.forEach(s => {
    const k = String(s || '')
      .replace(/^(不吃|不喝|避開|避免|想避開|想避免|別|拒)/, '')
      .replace(/(過敏|類)$/, '')
      .trim();
    if (k && !seen.has(k)) { seen.add(k); out.push(k); }
  });
  return out;
}
// 回傳 text 中命中的禁忌關鍵字（去重）
function findDesiredSpotConflicts(text) {
  const t = String(text || '');
  if (!t.trim()) return [];
  return getAvoidKeywords().filter(k => t.includes(k));
}
// 有命中時回傳警告 div；否則空字串
function desiredSpotsWarningHtml(text) {
  const hits = findDesiredSpotConflicts(text);
  if (!hits.length) return '';
  return `<div style="margin-top:8px;padding:10px 12px;border:1px solid var(--accent2);background:var(--accent2-light);border-radius:10px;font-size:13px;line-height:1.5;color:var(--accent2-dark);">`
    + `ⓘ 你填的景點文字提到你想避免的：<b>${hits.join('、')}</b>。AI 生成時會自動改用不衝突的鄰近替代並在摘要說明，你也可自行調整。</div>`;
}
// 供 textarea oninput 即時更新警告
function updateDesiredSpotsWarning() {
  const ta = document.getElementById('wizDesiredSpots');
  const box = document.getElementById('wizDesiredWarn');
  if (!ta || !box) return;
  box.innerHTML = desiredSpotsWarningHtml(ta.value);
}

function buildPrompt(wizardData, firebaseHint = '', mode = 'final') {
  const destination = wizardData.dest || wizardData.destCustom || '台東';
  const win = getTripDayWindows(wizardData);
  const tripStartTime = win.start;
  const isSolo = (wizardData.people || '2人') === '1人';

  const contextBlock = [
    `目的地：${destination}`,
    wizardData.departureDate ? `出發日期：${wizardData.departureDate}` : null,
    wizardData.returnDate ? `回程日期：${wizardData.returnDate}` : null,
    win.multi
      ? `時間長度：兩天一夜（${(win.dayWindows || []).map(w => `第${w.day}天 ${w.start}–${w.end}`).join('；')}；最後一天玩到 ${win.day2End} 後返程）`
      : (() => {
          // 明確給「含交通的總時長目標」讓 AI 一次排滿，減少事後 fillTripTimeBudget 補站輪數
          const _totalMin = parseDurationMinutes(wizardData.days || '1天');
          return `時間長度：${wizardData.days || '1天'}（${win.start} ～ ${win.end}）。全程目標約 ${_totalMin} 分鐘（含站間交通時間，站間車程可粗估每段 15–30 分鐘）；請把「停留＋交通」的總和排到 ${Math.max(60, _totalMin - 30)}～${_totalMin} 分鐘之間，寧可多排一站也不要留大段空檔`;
        })(),
    isSolo ? '旅行方式：獨旅' : `同行人數：${wizardData.people || '2人'}`,
    `主要交通工具：${({ taxi: '計程車', scooter: '機車', car: '汽車' }[wizardData.transportMode]) || '汽車'}（各段移動以此工具為主，短程可步行）`,
    ...buildPreferenceLines(wizardData),
    `旅程風格：${wizardData.theme || '經典旅人'}`,
    wizardData.budget ? `預算：${(describeBudget(wizardData.budget, wizardData.people) || {}).promptText || wizardData.budget}` : null,
    wizardData.budget ? (() => {
      const tr = estimateTransportRough(destination, wizardData.transportMode, wizardData.people, wizardData.days);
      const bd = buildBudgetBreakdown(wizardData.budget, wizardData.people, tr.totalPerPerson);
      if (!bd || tr.totalPerPerson <= 0) return null;
      const ferryPart = tr.ferryPerPerson > 0 ? `離島船票 $${tr.ferryPerPerson}、` : '';
      return `交通預估：每人約 $${tr.totalPerPerson}（${ferryPart}站間移動約 $${tr.movePerPerson}）。可動用於餐飲與付費體驗：每人約 ${bd.label}，請在此額度內安排，避免規劃會超支的高消費景點；用餐站請優先挑選人均消費落在此額度內的餐廳（餐廳候選已附人均消費）`;
    })() : null,
    wizardData.accommodation ? `住宿安排：${wizardData.accommodation}` : null,
    (win.multi && String(wizardData.lodgingName || '').trim())
      ? `住宿地點：${String(wizardData.lodgingName).trim()}（每晚行程以此為終點、隔日清晨由此出發）`
      : (win.multi ? '住宿地點：未指定（請在 reply 推薦住宿區域，每日最後一站鄰近該區域）' : null),
    wizardData.desiredSpots ? `用戶希望去的景點：${wizardData.desiredSpots}` : null,
    `出發站點：${(wizardData.startLocation || '').trim() || getDefaultTransitHub(destination)}`,
    `回程站點：${(wizardData.endLocation || '').trim() || getDefaultTransitHub(destination)}`
  ].filter(Boolean).join('\n');

  const rules = getPromptRuleLines(wizardData, mode).join('\n');
  const outputSchema = mode === 'preview'
    ? `請用 JSON 格式回應：{"title":"行程標題","reply":"一句話摘要","stops":[{"order":1,"name":"景點名","time":"09:00","desc":"簡短描述"${win.multi ? ',"dayIndex":1' : ''}}]}${win.multi ? '；每站必須附 dayIndex（1 或 2），時間使用該天的 HH:MM' : ''}`
    : `請用 JSON 格式回應，包含：title, reply, stops[{order,emoji,name,time,address,desc,duration(必填·正整數·分鐘，禁止固定填整數倍，應依規模靈活設定：例觀景台填38、美食填22、博物館填65、海灘填35、步道填55),businessHours,transportMode${win.multi ? ',dayIndex(整數1-' + (win.dayCount || 2) + '，該站屬於第幾天)' : ''}}]`;

  const firebaseSection = mode === 'final' && firebaseHint
    ? `\n\n${firebaseHint}`
    : '';

  const destCenter = getDestinationCenter(destination);
  const destRadiusKm = Math.round(getDestinationMaxDistanceMeters(destination) / 1000);
  const _startLocRaw = (wizardData.startLocation || '').trim();
  const _endLocRaw   = (wizardData.endLocation   || '').trim();
  const geoConstraint = (_startLocRaw || _endLocRaw)
    ? `景點請沿「${_startLocRaw || destination}」→「${destination}」→「${_endLocRaw || destination}」的路線廊道分布，各景點距此路線盡量在 5 公里內（景點不足時可擴展至 10 公里）；行程方向由起點方向出發、途經${destination}核心、往終點方向收尾，嚴禁安排需大幅折返的景點，也不要把景點全部集中在${destination}核心附近而忽略沿途地點。`
    : destCenter
      ? `所有景點必須真實位於「${destination}」範圍內（中心座標 lat ${destCenter.lat}, lng ${destCenter.lng}，有效半徑約 ${destRadiusKm} 公里），嚴禁規劃其他地區的景點。`
      : `所有景點必須真實位於「${destination}」指定地區，嚴禁規劃目的地以外的景點。`;

  const intro = `你是「${destination}」微旅行的 AI 規劃助手。${geoConstraint}${getPeopleProfile(wizardData.people).introSentence}`;

  const desiredNote = wizardData.desiredSpots
    ? `\n\n⚠️【最高優先·希望景點例外】用戶特別希望前往：${wizardData.desiredSpots}。這些是「必須安排」的景點——即使它們不在上方「已驗證景點快取／景點清單」中，也一定要排進 stops（此條優先於前面「只能從清單挑選、嚴禁清單以外景點」的限制；這些希望景點的真實性與座標會由系統後續自行驗證，你只需照用戶輸入的名稱填入）。時間不足時保留最重要的幾個，並圍繞它們規劃其餘行程。`
      + `\n但若其中某個景點與上方「個人禁忌／需避免」衝突（例如使用者對海鮮過敏卻指定海鮮餐廳、吃素卻指定燒肉店），一律以禁忌為最高優先：請勿安排該景點，改以附近、性質相近且不違反禁忌的替代景點取代；並在 reply 以一句話說明「哪個希望景點因禁忌被替換、換成了什麼」。`
    : '';

  const visitedPlaces = (() => {
    try {
      const all = JSON.parse(localStorage.getItem('wai_visited_places') || '[]');
      return all.filter(p => !p.region || p.region === destination).map(p => p.name).slice(-30);
    } catch { return []; }
  })();
  const visitedNote = visitedPlaces.length
    ? `\n\n🚫 以下景點用戶已去過，請勿再次安排（可改推附近其他景點）：${visitedPlaces.join('、')}`
    : '';

  const prompt = `${intro}${firebaseSection}\n\n行程規則：\n${rules}\n\n${outputSchema}\n\n用戶需求：\n${contextBlock}${desiredNote}${visitedNote}`;

  return { prompt, contextBlock };
}

function extractGeminiResponseText(data) {
  return data && data.candidates && data.candidates[0] && data.candidates[0].content && data.candidates[0].content.parts && data.candidates[0].content.parts[0]
    ? String(data.candidates[0].content.parts[0].text || '')
    : '';
}

function parsePlanJsonFromText(text) {
  const raw = String(text || '').trim();
  if (!raw) throw new Error('AI 回傳內容為空。');
  // thinking 關閉時模型偶爾把 {title,stops} 包成單元素陣列 [{...}] → 解包取第一個物件
  const unwrap = (v) => (Array.isArray(v) ? v[0] : v);
  try {
    return unwrap(JSON.parse(raw));
  } catch (error) {
    const start = raw.indexOf('{');
    const end = raw.lastIndexOf('}');
    if (start >= 0 && end > start) {
      return unwrap(JSON.parse(raw.slice(start, end + 1)));
    }
    throw new Error('AI 回傳格式不是有效 JSON。');
  }
}

// 代理模式的 Vertex 呼叫需要登入（後端驗 Firebase ID token，防止陌生人燒 Vertex 額度）。
// 回傳含 Authorization 的標頭；未登入時不帶（後端會回 401，由呼叫端顯示友善訊息）。
// ── Places API (New) 送出（成本統計第 5 步）──
// 設了 API_PROXY_BASE 就走後端代理：金鑰不進瀏覽器，而且後端才數得到實際請求次數
// （5 個呼叫位置 ≠ 5 次請求——有 Promise.all 併發與逐站驗證）。
// 沒設代理（file:// 直開、本機開發）維持直連，行為與改動前一致。
async function placesFetch(method, fieldMask, body, timeoutMs, label, directKey) {
  const cfg = window.TRAVEL_APP_CONFIG || {};
  const base = String(cfg.API_PROXY_BASE || '').replace(/\/+$/, '');
  if (base) {
    const headers = {
      'Content-Type': 'application/json',
      'X-Goog-FieldMask': fieldMask   // 後端靠它判定 SKU，必須原樣傳過去
    };
    try {
      const u = (typeof firebaseAuth !== 'undefined' && firebaseAuth) ? firebaseAuth.currentUser : null;
      // getIdToken 要有上限：它不在 fetchWithTimeout 的管轄內，
      // token 更新卡住時會把整條 Places 流程無限拖住。
      if (u) {
        const _tok = await Promise.race([
          u.getIdToken(),
          new Promise((r) => setTimeout(() => r(null), 3000))
        ]);
        if (_tok) headers.Authorization = 'Bearer ' + _tok;
      }
    } catch (_e) {}
    if (window.WAI_COST && WAI_COST.currentRunId()) headers['X-Run-Id'] = WAI_COST.currentRunId();
    return fetchWithTimeout(`${base}/places/${method}`, {
      method: 'POST', headers, body: JSON.stringify(body)
    }, timeoutMs, label);
  }
  return fetchWithTimeout(`https://places.googleapis.com/v1/places:${method}`, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      'X-Goog-Api-Key': directKey,
      'X-Goog-FieldMask': fieldMask
    },
    body: JSON.stringify(body)
  }, timeoutMs, label);
}

async function vertexAuthHeaders() {
  const headers = { 'Content-Type': 'application/json' };
  try {
    const u = (typeof firebaseAuth !== 'undefined' && firebaseAuth) ? firebaseAuth.currentUser : null;
    if (u) headers.Authorization = 'Bearer ' + (await u.getIdToken());
  } catch (_e) { /* token 取失敗就不帶，讓後端 401 */ }
  // 成本統計：所有 Vertex 呼叫都經過這裡，runId 從這一處帶上去即可，
  // 不必逐一改動每個呼叫點（少了漏改某一次呼叫、導致用量統計不全的風險）。
  if (window.WAI_COST && WAI_COST.currentRunId()) headers['X-Run-Id'] = WAI_COST.currentRunId();
  return headers;
}

function vertexHttpError(status, kind) {
  if (status === 401) return new Error('請先登入，登入後才能使用 AI 生成功能。');
  if (status === 429) return new Error('AI 請求過於頻繁，請休息一下再試。');
  return new Error(`${kind}（${status}）`);
}

// ── [gen-perf] 生成效能打點：純 console 量測（正式站 F12 可讀），不影響流程 ──
const _genPerf = {
  _t0: 0, _last: 0, _marks: [],
  start() { this._t0 = this._last = performance.now(); this._marks = []; },
  mark(label) {
    const now = performance.now();
    this._marks.push({ 階段: label, 耗時ms: Math.round(now - this._last) });
    this._last = now;
  },
  table(tag) {
    try {
      if (!this._marks.length) return;
      console.info(`[gen-perf] ${tag || ''} 總耗時 ${Math.round(performance.now() - this._t0)}ms`);
      console.table(this._marks);
    } catch (_e) {}
  }
};

// [gen-perf] 印出 Gemini 回應的 token 用量（thoughtsTokenCount = thinking 開銷的直接證據）
function logGeminiUsage(tag, usage) {
  if (!usage) return;
  try {
    console.info(`[gen-perf][tokens] ${tag}`, {
      prompt: usage.promptTokenCount ?? null,
      thoughts: usage.thoughtsTokenCount ?? null,
      output: usage.candidatesTokenCount ?? null,
      total: usage.totalTokenCount ?? null
    });
  } catch (_e) {}
}

// 統一組 generationConfig：gemini-3 flash 預設開 thinking 且不限輸出長度，是生成延遲的最大單一來源。
// 正式站實測（2026-07-15，同一提示詞）：預設 thinking 1224 tokens/9.6s、'low' 1037/8.9s、budget 0 → 0/3.5s；
// 主生成大 prompt 用 'low' 時 thinking 仍破數千 tokens（TTFT>30s、6144 上限被吃爆→JSON 截斷）。
// 因此全部行程 JSON 呼叫一律 thinking:0——品質防線在前端：景點只能從已驗證清單挑選、
// 座標逐站驗證、路線由 reorderStopsAlongRoute/sortStopsByNearestRoute 客戶端重排。
// 注意 maxOutputTokens 涵蓋 thinking＋輸出總量，設太緊會把輸出吃光（實測 256 會回空字串）。
function buildGenConfig({ temperature, maxOutputTokens, thinking }) {
  return {
    responseMimeType: 'application/json',
    temperature,
    maxOutputTokens: maxOutputTokens || 8192,
    thinkingConfig: thinking === 'low' ? { thinkingLevel: 'low' } : { thinkingBudget: 0 }
  };
}

// 帶逾時的 fetch：任一 Places / Vertex 呼叫卡住都不該讓整條生成掛死。
// 逾時 abort 後 throw（帶 label），呼叫端既有的 catch 降級路徑會自然接手。
async function fetchWithTimeout(url, options, timeoutMs, label) {
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), timeoutMs || 10000);
  try {
    return await fetch(url, { ...(options || {}), signal: controller.signal });
  } catch (error) {
    if (error && error.name === 'AbortError') throw new Error(`${label || '網路請求'}逾時（${timeoutMs}ms）`);
    throw error;
  } finally {
    clearTimeout(timer);
  }
}

// ── F3 自動避雨：生成前抓 CWA 一週預報，找出行程期間降雨機率偏高的日子 ──
// 只支援台東（CWA F-D0047-091 臺東縣）；出發日在 7 天內才有預報價值。
// 與 planner 共用 localStorage 快取鍵 'wai_cwa_taitung_wk'（同 {exp,data} 形狀，30 分鐘）。
async function fetchTripRainOutlook(wizardData) {
  try {
    if (!VERTEX_PROXY_BASE) return null;
    const dest = String((wizardData && (wizardData.dest || wizardData.destCustom)) || '').replace(/臺/g, '台');
    if (!dest.includes('台東')) return null;
    const depStr = wizardData && wizardData.departureDate;
    if (!depStr) return null;
    const dep = new Date(String(depStr).replace(/-/g, '/'));
    if (isNaN(dep.getTime())) return null;
    const today = new Date(); today.setHours(0, 0, 0, 0);
    const diffDays = Math.round((dep - today) / 86400000);
    if (diffDays < 0 || diffDays > 7) return null;

    const CACHE_KEY = 'wai_cwa_taitung_wk';
    let data = null;
    try {
      const cached = JSON.parse(localStorage.getItem(CACHE_KEY) || 'null');
      if (cached && cached.exp > Date.now() && cached.data) data = cached.data;
    } catch (_e) {}
    if (!data) {
      const url = `${VERTEX_PROXY_BASE}/cwa/v1/rest/datastore/F-D0047-091?LocationName=${encodeURIComponent('臺東縣')}`;
      const r = await fetchWithTimeout(url, null, 5000, 'CWA 天氣預報');
      if (!r.ok) return null;
      const json = await r.json();
      const locs = (json.records && json.records.Locations && json.records.Locations[0] && json.records.Locations[0].Location) || [];
      const tt = locs.find((x) => x.LocationName === '臺東縣') || locs[0];
      if (!tt || !Array.isArray(tt.WeatherElement)) return null;
      const byName = {};
      tt.WeatherElement.forEach((e) => { byName[e.ElementName] = e.Time || []; });
      const wxT = byName['天氣現象'] || [];
      const valByStart = (arr, start, field) => {
        const m = (arr || []).find((x) => x.StartTime === start);
        return (m && m.ElementValue && m.ElementValue[0] && m.ElementValue[0][field]) || '';
      };
      const periods = wxT.map((t) => ({
        start: t.StartTime, end: t.EndTime,
        wx: (t.ElementValue && t.ElementValue[0] && t.ElementValue[0].Weather) || '',
        pop: Number(valByStart(byName['12小時降雨機率'], t.StartTime, 'ProbabilityOfPrecipitation')) || 0
      })).filter((p) => p.wx);
      if (!periods.length) return null;
      data = { locationName: '臺東縣', periods, fetchedAt: Date.now() };
      try { localStorage.setItem(CACHE_KEY, JSON.stringify({ exp: Date.now() + 30 * 60 * 1000, data })); } catch (_e) {}
    }

    // 行程日期集合（出發日～回程日，最多抓 7 天避免失控）
    const tripDates = [];
    const ret = wizardData.returnDate ? new Date(String(wizardData.returnDate).replace(/-/g, '/')) : null;
    const last = (ret && !isNaN(ret.getTime()) && ret >= dep) ? ret : dep;
    for (let d = new Date(dep); d <= last && tripDates.length < 7; d.setDate(d.getDate() + 1)) {
      tripDates.push(`${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`);
    }
    // 每日取各時段 pop 最大值；>=50 視為降雨機率偏高
    const rainyDates = [];
    tripDates.forEach((dateStr) => {
      let maxPop = -1;
      (data.periods || []).forEach((p) => {
        const s = String(p.start || '');
        if (s.slice(0, 10) === dateStr) maxPop = Math.max(maxPop, Number(p.pop) || 0);
      });
      if (maxPop >= 50) rainyDates.push({ date: dateStr, pop: maxPop });
    });
    return rainyDates.length ? { rainyDates } : null;
  } catch (_e) {
    return null; // 天氣抓不到不影響生成（靜默降級）
  }
}

async function fetchGeminiJson({ apiKey, model, payload }) {
  const vertex = getVertexConfig();
  if (!vertex.ready) throw new Error('尚未設定 Vertex AI：請在 weather.env.js 填入 VERTEX_PROJECT_ID 與 VERTEX_API_KEY。');
  const endpoint = `${VERTEX_API_BASE}/publishers/google/models/${model}:generateContent?key=${encodeURIComponent(vertex.apiKey)}`;
  const response = await fetchWithTimeout(endpoint, {
    method: 'POST',
    headers: await vertexAuthHeaders(),
    body: JSON.stringify(payload)
  }, 60000, 'AI 生成');
  if (!response.ok) {
    throw vertexHttpError(response.status, 'Vertex API 失敗');
  }
  const data = await response.json();
  logGeminiUsage(`generateContent:${model}`, data && data.usageMetadata);
  return data;
}

async function fetchGeminiStream({ apiKey, model, payload, onChunk }) {
  const vertex = getVertexConfig();
  if (!vertex.ready) throw new Error('尚未設定 Vertex AI：請在 weather.env.js 填入 VERTEX_PROJECT_ID 與 VERTEX_API_KEY。');
  const endpoint = `${VERTEX_API_BASE}/publishers/google/models/${model}:streamGenerateContent?alt=sse&key=${encodeURIComponent(vertex.apiKey)}`;
  // 串流的逾時策略：連線階段 90s（離島/多日大 prompt 首 token 常 >60s，E2E #8）——實測 SSE 的 response headers 會等到「第一個 token」才送出，
  // 大 prompt＋thinking 的 TTFT 可超過 30s（實測 15s/30s 都會誤殺正常請求）；此逾時只防真掛死。
  // 開始收流後不設硬限，改用「距上次 chunk 90s 無資料」的 idle 偵測 abort。
  const _streamCtrl = new AbortController();
  let _idleTimer = setTimeout(() => _streamCtrl.abort(), 90000);
  const _resetIdle = (ms) => { clearTimeout(_idleTimer); _idleTimer = setTimeout(() => _streamCtrl.abort(), ms); };
  let response;
  try {
    response = await fetch(endpoint, {
      method: 'POST',
      headers: await vertexAuthHeaders(),
      body: JSON.stringify(payload),
      signal: _streamCtrl.signal
    });
  } catch (error) {
    clearTimeout(_idleTimer);
    if (error && error.name === 'AbortError') throw new Error('AI 連線逾時（90s），請再試一次。');
    throw error;
  }
  if (!response.ok || !response.body) {
    clearTimeout(_idleTimer);
    throw vertexHttpError(response.status, 'Vertex stream 失敗');
  }
  _resetIdle(90000);

  const reader = response.body.getReader();
  const decoder = new TextDecoder();
  let fullText = '';
  let sseBuffer = '';
  let lastUsage = null; // [gen-perf] 串流最後一個事件帶 usageMetadata

  try {
  while (true) {
    let value, done;
    try { ({ value, done } = await reader.read()); }
    catch (error) {
      if (error && error.name === 'AbortError') throw new Error('AI 串流閒置逾時（90s 無資料），請再試一次。');
      throw error;
    }
    if (done) break;
    _resetIdle(90000);
    sseBuffer += decoder.decode(value, { stream: true });
    const lines = sseBuffer.split('\n');
    sseBuffer = lines.pop() || '';
    for (const line of lines) {
      const trimmed = line.trim();
      if (!trimmed || !trimmed.startsWith('data:')) continue;
      const payloadText = trimmed.slice(5).trim();
      if (!payloadText || payloadText === '[DONE]') continue;
      try {
        const eventJson = JSON.parse(payloadText);
        if (eventJson && eventJson.usageMetadata) lastUsage = eventJson.usageMetadata;
        const chunkText = extractGeminiResponseText(eventJson);
        if (!chunkText) continue;
        fullText += chunkText;
        if (typeof onChunk === 'function') onChunk(chunkText, fullText);
      } catch (error) {
        console.warn('Ignored non-json stream chunk:', error);
      }
    }
  }
  } finally {
    clearTimeout(_idleTimer); // 收流結束（成功或失敗）都要清掉 idle 計時器
  }

  logGeminiUsage(`streamGenerateContent:${model}`, lastUsage);
  return fullText;
}

function getGeminiApiKey() {
  const fromEnv = window.TRAVEL_APP_CONFIG && window.TRAVEL_APP_CONFIG.GEMINI_API_KEY;
  if (typeof fromEnv === 'string' && fromEnv.trim()) {
    return fromEnv.trim();
  }
  const fromStorage = window.localStorage.getItem(GEMINI_LOCAL_KEY);
  if (typeof fromStorage === 'string' && fromStorage.trim()) {
    return fromStorage.trim();
  }
  return '';
}

function getVertexConfig() {
  const cfg = window.TRAVEL_APP_CONFIG || {};
  const apiKey = (cfg.VERTEX_API_KEY || '').trim() || (window.localStorage.getItem(VERTEX_LOCAL_KEY) || '').trim();
  const projectId = (cfg.VERTEX_PROJECT_ID || '').trim() || (window.localStorage.getItem(VERTEX_LOCAL_PROJECT) || '').trim();
  // 代理模式下前端沒有金鑰也視為 ready（金鑰在伺服器）；直連模式仍需 apiKey + projectId。
  const proxied = !!VERTEX_PROXY_BASE;
  return { apiKey, projectId, proxied, ready: proxied || !!(apiKey && projectId) };
}

function setGeminiApiKey(apiKey) {
  const next = String(apiKey || '').trim();
  if (!next) return;
  window.localStorage.setItem(GEMINI_LOCAL_KEY, next);
}

function ensureGeminiApiKey() {
  const existing = getGeminiApiKey();
  if (existing) return existing;
  const input = window.prompt('請先輸入 Gemini API Key（只會保存在你的瀏覽器 localStorage）');
  const apiKey = String(input || '').trim();
  if (!apiKey) return '';
  setGeminiApiKey(apiKey);
  return apiKey;
}

function getWizardPlanCacheKey(wizardData, mode) {
  return [
    mode || 'final',
    wizardData.dest || wizardData.destCustom || '',
    wizardData.days || '',
    wizardData.startTime || '',
    wizardData.day1Hours || '',
    wizardData.day2StartTime || '',
    wizardData.day2EndTime || '',
    wizardData.departureDate || '',
    wizardData.returnDate || '',
    wizardData.people || '',
    wizardData.pace || '',
    (wizardData.interests || []).join('|'),
    wizardData.theme || '',
    wizardData.transportMode || '',
    wizardData.startLocation || '',
    wizardData.endLocation || '',
    wizardData.desiredSpots || '',
    wizardData.budget || '',
    wizardData.accommodation || '',
    wizardData.lodgingName || ''
  ].join('::');
}

function renderAiSkeletonPreview(plan) {
  if (!plan || !Array.isArray(plan.stops) || !plan.stops.length) return;
  const flowNodes = document.getElementById('flowNodes');
  const flowSummary = document.getElementById('flowSummary');
  const flowTitle = document.getElementById('flowTitle');
  if (!flowNodes || !flowSummary || !flowTitle) return;

  const stops = plan.stops.slice(0, 6);
  flowTitle.textContent = plan.title || flowTitle.textContent;
  let secondDayIndex = isLongTrip(wizData.days)
    ? stops.findIndex((spot) => Number(spot && spot.dayIndex) >= 2)
    : -1;
  if (isLongTrip(wizData.days) && secondDayIndex < 0) {
    // 舊預覽資料沒有 dayIndex 時，以時鐘從晚跳回早辨識第二天。
    for (let i = 1; i < stops.length; i++) {
      if (/^次日\s*/.test(String(stops[i] && stops[i].time || ''))) {
        secondDayIndex = i;
        break;
      }
      const previous = normalizeClockInput(stops[i - 1] && stops[i - 1].time, '');
      const current = normalizeClockInput(stops[i] && stops[i].time, '');
      if (previous && current && timeStringToMinutes(current) < timeStringToMinutes(previous)) {
        secondDayIndex = i;
        break;
      }
    }
  }
  flowNodes.innerHTML = stops.map((spot, index) => {
    const name = spot && spot.name ? spot.name : `景點 ${index + 1}`;
    const desc = spot && spot.desc ? spot.desc : 'AI 預覽骨架';
    const rawTime = spot && spot.time ? String(spot.time) : '--:--';
    const time = secondDayIndex >= 0 && index >= secondDayIndex
      && /^\d{1,2}:\d{2}$/.test(rawTime)
      ? `次日 ${normalizeClockInput(rawTime, rawTime)}` : rawTime;
    return `
    ${index === secondDayIndex ? '<div class="wizard-day-break"><span>🌙 過夜</span><strong>第 2 天</strong></div>' : ''}
    <div class="wizard-node${index === secondDayIndex - 1 ? ' wizard-node-day-end' : ''}">
      <div class="wizard-node-top">
        <h4 class="wizard-node-title">🧭 ${escapeHtml(name)}</h4>
        <span style="font-size:14px;color:#4e6b87;white-space:nowrap;">${escapeHtml(time)}</span>
      </div>
      <p class="wizard-node-desc">${escapeHtml(desc)}</p>
    </div>`;
  }).join('');
  flowSummary.textContent = plan.reply || 'AI 已先生成預覽骨架，按下建立行程後會在背景補齊完整細節。';
  setWizardPreviewBanner('AI 景點預覽已更新，可直接查看最新安排。', 'done');
}

function setWizardPreviewBanner(text, state = 'loading') {
  const banner = document.getElementById('wizardPreviewStatus');
  if (!banner) return;
  banner.hidden = !text;
  banner.textContent = text || '';
  banner.dataset.state = state;
}

function setWizardStreamingHint(text, state = 'loading') {
  const flowSummary = document.getElementById('flowSummary');
  if (flowSummary) flowSummary.textContent = text;
  setWizardPreviewBanner(text, state);
}

function getWizardPreviewStatusText(reason, streaming = false) {
  const labels = {
    'step1-destination': '目的地',
    'step0-people': '旅行人數',
    'step2-days': '旅行天數',
    'step3-departure-date': '旅行日期',
    'step3-start-time': '第一天出發時間',
    'step0-day1': '第一天遊玩時間',
    'step0-day2': '第二天結束時間',
    'day2-start-time': '第二天出發時間',
    'day2-start-default': '第二天出發時間',
    'step2-pace': '旅行節奏',
    'step3-interests': '旅遊偏好',
    'step2-theme': '旅程風格',
    'step4-theme': '旅程風格',
    'start-location': '出發地點',
    'end-location': '回程地點',
    'desired-spots': '希望景點',
    'transport-mode': '交通方式',
    'preview-start-time': '第一天出發時間',
    'preview-day2-end-time': '第二天結束時間'
  };
  const label = labels[reason] || '行程設定';
  return streaming
    ? `AI 正在依照「${label}」安排景點，預覽即將更新…`
    : `AI 正在依照「${label}」更新景點預覽…`;
}

function clearWizardPrefetchState() {
  wizardPreviewPlan = null;
  wizardPreviewPromise = null;
  wizardPreviewCacheKey = '';
  wizardPreviewRequestKey = '';
  wizardPreviewStreamBuffer = '';
  setWizardPreviewBanner('', 'loading');
  if (wizardPreviewDebounceTimer) {
    clearTimeout(wizardPreviewDebounceTimer);
    wizardPreviewDebounceTimer = null;
  }
}

function triggerFirebaseHintPrefetch() {
  const destination = getWizardDestination(wizData);
  if (!destination) return;
  prefetchFirebasePoiHint(wizData).catch((error) => {
    console.warn('Prefetch firebase hint failed:', error);
  });
}

function requestWizardPreviewInBackground(reason = 'wizard') {
  const destination = getWizardDestination(wizData);
  if (!destination) return;
  if (!getVertexConfig().ready) return;

  const requestKey = getWizardPlanCacheKey(wizData, 'preview');
  if (wizardPreviewCacheKey === requestKey && wizardPreviewPlan) return;
  wizardPreviewRequestKey = requestKey;
  setWizardStreamingHint(getWizardPreviewStatusText(reason));

  const requestData = {
    dest: wizData.dest,
    destCustom: wizData.destCustom,
    days: wizData.days,
    startTime: wizData.startTime,
    day1Hours: wizData.day1Hours,
    day2StartTime: wizData.day2StartTime,
    day2EndTime: wizData.day2EndTime,
    transportMode: wizData.transportMode,
    startLocation: wizData.startLocation,
    endLocation: wizData.endLocation,
    desiredSpots: wizData.desiredSpots,
    budget: wizData.budget,
    accommodation: wizData.accommodation,
    lodgingName: wizData.lodgingName,
    departureDate: wizData.departureDate,
    returnDate: wizData.returnDate,
    people: wizData.people,
    pace: wizData.pace,
    interests: Array.isArray(wizData.interests) ? [...wizData.interests] : [],
    theme: wizData.theme,
    profilePrefs: wizData.profilePrefs
  };

  wizardPreviewPromise = requestGeminiMicroTravelPlan(requestData, {
    mode: 'preview',
    includeFirebase: false,
    useStreaming: true,
    promptForKey: false,
    onChunk: (_chunk, fullText) => {
      if (wizardPreviewRequestKey !== requestKey) return;
      wizardPreviewStreamBuffer = fullText;
      const size = fullText.length;
      if (size > 0) {
        setWizardStreamingHint(getWizardPreviewStatusText(reason, true));
      }
    }
  })
    .then((plan) => {
      if (wizardPreviewRequestKey !== requestKey) return;
      if (!plan || !Array.isArray(plan.stops) || !plan.stops.length) throw new Error('AI 預覽缺少景點');
      wizardPreviewPlan = plan;
      wizardPreviewCacheKey = requestKey;
      renderAiSkeletonPreview(plan);
    })
    .catch((error) => {
      if (wizardPreviewRequestKey !== requestKey) return;
      console.warn('Preview prewarm failed:', error);
      setWizardStreamingHint('AI 景點預覽暫時無法更新；目前顯示示意安排，建立行程仍可繼續。', 'error');
    })
    .finally(() => {
      if (wizardPreviewRequestKey === requestKey) {
        wizardPreviewPromise = null;
      }
    });
}

function scheduleWizardPreviewRequest(reason = 'wizard') {
  if (wizardPreviewDebounceTimer) clearTimeout(wizardPreviewDebounceTimer);
  // 在 450ms debounce 期間就淘汰舊請求，避免舊天數／時間的回覆蓋掉剛更新的本地預覽。
  wizardPreviewRequestKey = getWizardPlanCacheKey(wizData, 'preview');
  wizardPreviewDebounceTimer = setTimeout(() => {
    requestWizardPreviewInBackground(reason);
  }, 450);
}

function getCachedWizardPreviewPlan() {
  const key = getWizardPlanCacheKey(wizData, 'preview');
  if (wizardPreviewPlan && wizardPreviewCacheKey === key) return wizardPreviewPlan;
  return null;
}

async function requestGeminiMicroTravelPlan(wizardData, options = {}) {
  // 一律走 Vertex AI（不再退回 Gemini API key）
  const vertex = getVertexConfig();
  if (!vertex.ready) {
    throw new Error('尚未設定 Vertex AI：請在 weather.env.js 填入 VERTEX_PROJECT_ID 與 VERTEX_API_KEY。');
  }
  const apiKey = '';

  const mode = options.mode === 'preview' ? 'preview' : 'final';
  const includeFirebase = options.includeFirebase !== false && mode === 'final';
  const useStreaming = options.useStreaming === true;
  // 本地優先：有本地景點清單就用它，跳過 Firebase poi_cache 的網路讀取
  const localBlock = buildLocalPoiHintBlock(getWizardDestination(wizardData));
  const firebasePromptBlock = (!localBlock && includeFirebase)
    ? (await prefetchFirebasePoiHint(wizardData).catch(() => ''))
    : '';

  const livePoiBlock = (!localBlock && !firebasePromptBlock && options.livePoiHint !== undefined)
    ? (options.livePoiHint || '')
    : '';

  // 餐廳候選獨立疊加：上面三個景點清單互斥（本地優先），但餐廳清單來源不同（restaurant-data.js
  // ／Places），本地景點快取只含 kind==='scenic' 的景點、永遠不含餐廳。若讓餐廳跟著三選一被吃掉，
  // prompt 只剩景點清單又寫著「只能從清單挑選」，AI 就排不出用餐站（見 _doGeneration 的 foodHint）。
  const foodBlock = String(options.foodHint || '').trim();
  const hintBlock = [localBlock || firebasePromptBlock || livePoiBlock, foodBlock].filter(Boolean).join('\n\n');
  // 在 DevTools Console 標明這次景點清單來源：本地 / Firebase / live Maps / 無
  const _poiSource = localBlock ? '本地 poi-data.js'
    : (firebasePromptBlock ? 'Firebase poi_cache'
    : (livePoiBlock ? 'live Google Maps' : '無清單（AI 自行生成）'));
  console.info(`[POI來源] ${_poiSource}｜餐廳候選：${foodBlock ? '有' : '無'}｜目的地：${getWizardDestination(wizardData)}｜mode：${mode}`);
  const built = buildPrompt(wizardData, hintBlock, mode);
  const preferredModel = GEMINI_MODEL;
  const payload = {
    contents: [
      {
        role: 'user',
        parts: [{ text: built.prompt }]
      }
    ],
    generationConfig: buildGenConfig({ temperature: 0.6, maxOutputTokens: 8192, thinking: 0 })
  };

  async function requestOnce(model, streamMode) {
    if (streamMode) {
      return fetchGeminiStream({
        apiKey,
        model,
        payload,
        onChunk: options.onChunk
      });
    }
    const data = await fetchGeminiJson({ apiKey, model, payload });
    return extractGeminiResponseText(data);
  }

  let text = '';
  try {
    text = await requestOnce(preferredModel, useStreaming);
  } catch (error) {
    const is404 = /\(404\)/.test(String(error && error.message || ''));
    if (!is404) throw error;

    if (useStreaming) {
      // Fallback 1: same model without streaming.
      try {
        text = await requestOnce(preferredModel, false);
      } catch (fallbackError) {
        const fallback404 = /\(404\)/.test(String(fallbackError && fallbackError.message || ''));
        if (!fallback404) throw fallbackError;
        // Fallback 2: stable default model without streaming.
        text = await requestOnce(GEMINI_MODEL, false);
      }
    } else {
      // Non-stream 404: fallback to stable default model.
      text = await requestOnce(GEMINI_MODEL, false);
    }
  }
  return parsePlanJsonFromText(text);
}

function getStopCoordinate(stop) {
  if (!stop || typeof stop !== 'object') return null;

  function parseCoordinateValue(rawValue) {
    if (rawValue === null || rawValue === undefined) return null;
    if (typeof rawValue === 'number') return Number.isFinite(rawValue) ? rawValue : null;
    const source = String(rawValue).trim();
    if (!source) return null;
    const marker = /[SWsw]/.test(source) ? -1 : 1;
    const match = source.replace(/，/g, ',').match(/[-+]?\d+(?:[\.,]\d+)?/);
    if (!match) return null;
    const parsed = Number(match[0].replace(',', '.'));
    if (!Number.isFinite(parsed)) return null;
    return marker < 0 ? -Math.abs(parsed) : parsed;
  }

  function parseCoordinatePairFromText(rawValue) {
    const source = String(rawValue || '').trim();
    if (!source) return null;
    const pairMatch = source.match(/([-+]?\d+(?:\.\d+)?)\s*[,|\/]\s*([-+]?\d+(?:\.\d+)?)/);
    if (!pairMatch) return null;
    const first = parseCoordinateValue(pairMatch[1]);
    const second = parseCoordinateValue(pairMatch[2]);
    if (!Number.isFinite(first) || !Number.isFinite(second)) return null;

    if (first >= -90 && first <= 90 && second >= -180 && second <= 180) {
      return { lat: first, lng: second };
    }
    if (first >= -180 && first <= 180 && second >= -90 && second <= 90) {
      return { lat: second, lng: first };
    }
    return null;
  }

  const directLat = parseCoordinateValue(
    stop.lat
    ?? stop.latitude
    ?? (stop.location && stop.location.lat)
    ?? (stop.coordinate && stop.coordinate.lat)
    ?? (stop.coordinates && stop.coordinates.lat)
    ?? (stop.geo && stop.geo.lat)
  );
  const directLng = parseCoordinateValue(
    stop.lng
    ?? stop.longitude
    ?? (stop.location && stop.location.lng)
    ?? (stop.coordinate && stop.coordinate.lng)
    ?? (stop.coordinates && stop.coordinates.lng)
    ?? (stop.geo && stop.geo.lng)
  );

  let lat = directLat;
  let lng = directLng;

  if (!Number.isFinite(lat) || !Number.isFinite(lng)) {
    if (Array.isArray(stop.coordinates) && stop.coordinates.length >= 2) {
      const first = parseCoordinateValue(stop.coordinates[0]);
      const second = parseCoordinateValue(stop.coordinates[1]);
      if (Number.isFinite(first) && Number.isFinite(second)) {
        if (first >= -90 && first <= 90 && second >= -180 && second <= 180) {
          lat = first;
          lng = second;
        } else if (first >= -180 && first <= 180 && second >= -90 && second <= 90) {
          lat = second;
          lng = first;
        }
      }
    }
  }

  if (!Number.isFinite(lat) || !Number.isFinite(lng)) {
    const textPair = parseCoordinatePairFromText(
      stop.coordinate
      || stop.coordinates
      || stop.location
      || stop.mapUrl
      || stop.googleMapsUrl
      || ''
    );
    if (textPair) {
      lat = textPair.lat;
      lng = textPair.lng;
    }
  }

  if (!Number.isFinite(lat) || !Number.isFinite(lng)) return null;
  if (lat < -90 || lat > 90 || lng < -180 || lng > 180) return null;
  return { lat, lng };
}

function getDistanceMeters(a, b) {
  const earthRadius = 6371000;
  const toRad = (value) => value * Math.PI / 180;
  const dLat = toRad(b.lat - a.lat);
  const dLng = toRad(b.lng - a.lng);
  const lat1 = toRad(a.lat);
  const lat2 = toRad(b.lat);
  const h = Math.sin(dLat / 2) ** 2 +
    Math.cos(lat1) * Math.cos(lat2) * Math.sin(dLng / 2) ** 2;
  return 2 * earthRadius * Math.asin(Math.sqrt(h));
}

function distancePointToSegmentMeters(point, segA, segB) {
  const dx = segB.lng - segA.lng;
  const dy = segB.lat - segA.lat;
  const lenSq = dx * dx + dy * dy;
  if (lenSq < 1e-12) return getDistanceMeters(point, segA);
  const t = Math.max(0, Math.min(1,
    ((point.lng - segA.lng) * dx + (point.lat - segA.lat) * dy) / lenSq));
  return getDistanceMeters(point, { lat: segA.lat + t * dy, lng: segA.lng + t * dx });
}

function isWithinCorridorKm(coord, startCoord, destCoord, endCoord, corridorKm) {
  const limit = corridorKm * 1000;
  return distancePointToSegmentMeters(coord, startCoord, destCoord) <= limit ||
         distancePointToSegmentMeters(coord, destCoord, endCoord) <= limit;
}

const MAX_NEARBY_TOILET_DISTANCE_METERS = 500;

function filterNearbyToiletsByDistance(stop) {
  const stopCoord = getStopCoordinate(stop);
  if (!stopCoord || !Array.isArray(stop.nearbyToiletLocations)) return;
  stop.nearbyToiletLocations = stop.nearbyToiletLocations.filter(toilet => {
    const toiletCoord = getStopCoordinate(toilet);
    if (!toiletCoord) return false;
    return getDistanceMeters(stopCoord, toiletCoord) <= MAX_NEARBY_TOILET_DISTANCE_METERS;
  });
}

const DESTINATION_CENTER_MAP = {
  '台北': { lat: 25.033, lng: 121.565 },
  '新北': { lat: 25.016, lng: 121.462 },
  '桃園': { lat: 24.993, lng: 121.301 },
  '台中': { lat: 24.147, lng: 120.674 },
  '台南': { lat: 22.999, lng: 120.227 },
  '高雄': { lat: 22.627, lng: 120.301 },
  '台東': { lat: 22.755, lng: 121.144 },
  '花蓮': { lat: 23.987, lng: 121.601 },
  '宜蘭': { lat: 24.759, lng: 121.754 },
  '屏東': { lat: 22.676, lng: 120.494 },
  '南投': { lat: 23.960, lng: 120.972 },
  '嘉義': { lat: 23.480, lng: 120.449 },
  '苗栗': { lat: 24.560, lng: 120.821 },
  '新竹': { lat: 24.804, lng: 120.971 },
  '基隆': { lat: 25.128, lng: 121.739 },
  '綠島': { lat: 22.666, lng: 121.494 },
  '蘭嶼': { lat: 22.046, lng: 121.548 },
  '小琉球': { lat: 22.341, lng: 120.372 },
  '土坂': { lat: 22.3797, lng: 120.8953 },
  '土坂村': { lat: 22.3797, lng: 120.8953 },
  '達仁': { lat: 22.3797, lng: 120.8953 },
  '達仁鄉': { lat: 22.3797, lng: 120.8953 },
  '澎湖': { lat: 23.571, lng: 119.579 },
  '金門': { lat: 24.432, lng: 118.317 },
  '馬祖': { lat: 26.160, lng: 119.920 },
  '日本': { lat: 35.681, lng: 139.767 },
  '韓國': { lat: 37.566, lng: 126.978 },
  '歐洲': { lat: 48.857, lng: 2.352 }
};

// 地標型目的地 → 所屬地理區域（僅用於座標解析/範圍驗證/Places 查詢；標題與顯示仍用原目的地）。
// 海濱公園/三仙台/知本等是台東「境內地標」，不是縣市區域，直接拿來做地理判定會查無中心而失準。
const DESTINATION_REGION_ALIAS = { '海濱公園': '台東', '三仙台': '台東', '知本': '台東' };
function resolveGeoRegion(dest) {
  const t = String(dest || '').trim();
  return DESTINATION_REGION_ALIAS[t] || t;
}

const MILITARY_EXCLUSION_KEYWORDS = ['空軍', '海軍基地', '軍用', '軍港', '軍機場', '基地', '海巡署'];

const CIVIL_TRANSIT_HUB_MAP = {
  // 台東縣 — 市區
  '台東': '台東車站', '台東縣': '台東車站', '台東市': '台東車站',
  // 台東縣 — 各鄉鎮
  '卑南': '台東車站', '知本': '知本車站',
  '太麻里': '太麻里車站', '金峰': '金崙車站',
  '大武': '大武車站', '土坂': '大武車站', '達仁': '大武車站',
  '鹿野': '鹿野車站', '關山': '關山車站',
  '池上': '池上車站', '富里': '富里車站',
  '成功': '成功漁港', '長濱': '成功漁港', '東河': '台東車站',
  // 離島：預設起終點用「島內港」，讓行程聚焦島上（使用者自填則尊重）。本島搭船港由 ferry-config 管理。
  '綠島': '南寮漁港', '蘭嶼': '開元漁港',
  // 花蓮縣
  '花蓮': '花蓮車站', '玉里': '玉里車站', '瑞穗': '瑞穗車站',
  '光復': '光復車站', '壽豐': '壽豐車站',
  '秀林': '新城車站', '太魯閣': '新城車站', '七星潭': '花蓮車站',
  // 宜蘭縣
  '宜蘭': '宜蘭車站', '羅東': '羅東車站', '蘇澳': '蘇澳新車站',
  '礁溪': '礁溪車站', '頭城': '頭城車站', '龜山島': '烏石港', '龜山': '烏石港',
  // 新北、台北、基隆
  '基隆': '基隆車站', '台北': '台北車站', '新北': '板橋車站',
  // 桃竹苗
  '桃園': '桃園車站', '新竹': '新竹車站', '苗栗': '苗栗車站',
  // 中部
  '台中': '台中車站', '彰化': '彰化車站',
  // 雲嘉南
  '嘉義': '嘉義車站', '台南': '台南車站',
  // 高屏
  '高雄': '高雄車站', '屏東': '屏東車站',
  '恆春': '枋寮車站', '墾丁': '枋寮車站', '枋寮': '枋寮車站',
  // 離島
  '澎湖': '馬公港', '金門': '水頭碼頭', '馬祖': '南竿福澳港',
  '小琉球': '白沙港',
};

// 離島（綠島/蘭嶼/小琉球）預設起終點用「島內港」，名稱以資料端 ferry-config.js
// （window.WAI_FERRY_CONFIG.islandHarbor）為單一來源；缺檔時沿用上方字面值。
// 澎湖/金門/馬祖本就用島上港/機場，不在此同步。
['綠島', '蘭嶼', '小琉球'].forEach((dest) => {
  const cfg = (typeof window !== 'undefined' && window.WAI_FERRY_CONFIG) ? window.WAI_FERRY_CONFIG[dest] : null;
  const harborName = cfg && cfg.islandHarbor && cfg.islandHarbor.name;
  if (harborName) CIVIL_TRANSIT_HUB_MAP[dest] = harborName;
});

const TRAFFIC_RISK_KEYWORDS = [
  '交流道', '匝道', '國道', '快速道路', '高架', '環河', '環東', '環南', '橋', '隧道',
  '中山路', '中正路', '民權路', '民族路', '復興路', '建國路', '成功路', '忠孝路',
  '火車站', '車站', '轉運站', '機場', '港口', '商圈', '夜市'
];

function normalizeLookupText(value) {
  return String(value || '').toLowerCase().replace(/\s+/g, '').replace(/[()（）,，.。·•\-_/]/g, '');
}

function clearStopCoordinates(stop) {
  if (!stop || typeof stop !== 'object') return;
  delete stop.lat;
  delete stop.lng;
  delete stop.latitude;
  delete stop.longitude;
  if (stop.location && typeof stop.location === 'object') {
    delete stop.location.lat;
    delete stop.location.lng;
  }
}

function getDestinationCenter(destinationText) {
  const lookup = resolveGeoRegion(destinationText);
  if (!lookup) return null;
  if (DESTINATION_CENTER_MAP[lookup]) return DESTINATION_CENTER_MAP[lookup];
  const hit = Object.keys(DESTINATION_CENTER_MAP).find((key) => lookup.includes(key));
  return hit ? DESTINATION_CENTER_MAP[hit] : null;
}

function getDefaultTransitHub(destination) {
  if (!destination) return '台東車站';
  const text = resolveGeoRegion(destination);
  if (CIVIL_TRANSIT_HUB_MAP[text]) return CIVIL_TRANSIT_HUB_MAP[text];
  const hit = Object.keys(CIVIL_TRANSIT_HUB_MAP).find(key => text.includes(key));
  return hit ? CIVIL_TRANSIT_HUB_MAP[hit] : '台東車站';
}

function getDestinationMaxDistanceMeters(destinationText) {
  const lookup = resolveGeoRegion(destinationText);
  if (!lookup) return 45000;
  if (/日本|韓國|歐洲/.test(lookup)) return 120000;
  if (/台北|新北|桃園|台中|台南|高雄|基隆|新竹/.test(lookup)) return 40000;
  if (/台東|花蓮|宜蘭|屏東|南投|嘉義|苗栗/.test(lookup)) return 55000;
  if (/土坂|達仁/.test(lookup)) return 8000;
  // 離島：地域極小，嚴格限縮半徑防止 AI 座標嚴重偏移通過驗證
  if (/綠島/.test(lookup)) return 6000;
  if (/蘭嶼/.test(lookup)) return 7000;
  if (/小琉球/.test(lookup)) return 4000;
  if (/澎湖/.test(lookup)) return 20000;
  if (/金門/.test(lookup)) return 15000;
  if (/馬祖/.test(lookup)) return 18000;
  return 50000;
}

function getDurationMaxLegDistanceMeters(days) {
  if (isLongTrip(days)) return 90000;
  return Math.min(60000, Math.max(6000, Math.round(parseDurationMinutes(days) / 480 * 50000)));
}

function getPoiCacheCenter(poiCache) {
  const list = Array.isArray(poiCache) ? poiCache : [];
  if (!list.length) return null;
  const points = list
    .map((poi) => ({ lat: Number(poi.lat), lng: Number(poi.lng) }))
    .filter((coord) => Number.isFinite(coord.lat) && Number.isFinite(coord.lng));
  if (!points.length) return null;
  return {
    lat: points.reduce((sum, point) => sum + point.lat, 0) / points.length,
    lng: points.reduce((sum, point) => sum + point.lng, 0) / points.length
  };
}

function getDepartureMinutesByLeg(legIndex, wizardData = {}) {
  const startMinutes = timeStringToMinutes(normalizeClockInput(wizardData.startTime, '09:00'));
  const pace = wizardData.pace || '平衡';
  const gapMinutes = Number(wizardData.slotMinutes) || getDefaultSlotMinutes(pace);
  return startMinutes + Math.max(0, legIndex) * gapMinutes;
}

function getPeakTrafficMultiplier(minutesOfDay) {
  const minutes = ((Number(minutesOfDay) % (24 * 60)) + (24 * 60)) % (24 * 60);
  const morningPeak = minutes >= (7 * 60) && minutes < (9 * 60 + 30);
  const eveningPeak = minutes >= (17 * 60) && minutes < (19 * 60 + 30);
  const shoulder = (minutes >= (6 * 60 + 30) && minutes < (7 * 60)) || (minutes >= (9 * 60 + 30) && minutes < (10 * 60 + 30))
    || (minutes >= (16 * 60 + 30) && minutes < (17 * 60)) || (minutes >= (19 * 60 + 30) && minutes < (20 * 60));
  if (morningPeak || eveningPeak) return 1.35;
  if (shoulder) return 1.15;
  return 1;
}

function getStopTrafficRiskScore(stop) {
  if (!stop || typeof stop !== 'object') return 0;
  const marker = normalizeLookupText([
    stop.name,
    stop.title,
    stop.desc,
    stop.description,
    stop.address,
    stop.location && stop.location.address
  ].filter(Boolean).join(' '));
  if (!marker) return 0;

  let score = 0;
  TRAFFIC_RISK_KEYWORDS.forEach((keyword) => {
    if (marker.includes(normalizeLookupText(keyword))) score += 1;
  });
  return Math.min(5, score);
}

function getWeightedLegCostMeters(fromStop, toStop, legIndex, wizardData = {}) {
  const fromCoord = getStopCoordinate(fromStop);
  const toCoord = getStopCoordinate(toStop);
  if (!fromCoord || !toCoord) return Infinity;

  const baseDistance = getDistanceMeters(fromCoord, toCoord);
  const departureMinutes = getDepartureMinutesByLeg(legIndex, wizardData);
  const peakMultiplier = getPeakTrafficMultiplier(departureMinutes);
  const riskScore = (getStopTrafficRiskScore(fromStop) + getStopTrafficRiskScore(toStop)) / 2;
  const riskMultiplier = 1 + (riskScore * 0.06);
  return baseDistance * peakMultiplier * riskMultiplier;
}

function getRouteDistanceMeters(stops, wizardData = {}) {
  if (!Array.isArray(stops) || stops.length < 2) return 0;
  const coords = stops.map(getStopCoordinate);
  if (coords.some(coord => !coord)) return Infinity;

  let totalDistance = 0;
  for (let index = 1; index < coords.length; index += 1) {
    totalDistance += getWeightedLegCostMeters(stops[index - 1], stops[index], index - 1, wizardData);
  }
  return totalDistance;
}

function buildNearestNeighborRoute(stops, startIndex, wizardData = {}) {
  const seed = Array.isArray(stops) ? stops : [];
  if (!seed.length) return [];
  const safeStartIndex = Math.max(0, Math.min(seed.length - 1, Number(startIndex) || 0));
  const route = [seed[safeStartIndex]];
  const remaining = seed.filter((_, index) => index !== safeStartIndex);

  while (remaining.length) {
    const currentCoord = getStopCoordinate(route[route.length - 1]);
    if (!currentCoord) return [...seed];

    let nearestIndex = 0;
    let nearestDistance = Infinity;
    remaining.forEach((candidateStop, index) => {
      const candidateCoord = getStopCoordinate(candidateStop);
      if (!candidateCoord) return;
      const distance = getWeightedLegCostMeters(route[route.length - 1], candidateStop, route.length - 1, wizardData);
      if (distance < nearestDistance) {
        nearestDistance = distance;
        nearestIndex = index;
      }
    });

    route.push(remaining.splice(nearestIndex, 1)[0]);
  }

  return route;
}

function improveRouteWithTwoOpt(route, lockFirstStop = false, wizardData = {}, lockLastStop = false) {
  if (!Array.isArray(route) || route.length < 4) return Array.isArray(route) ? [...route] : [];
  let bestRoute = [...route];
  let bestDistance = getRouteDistanceMeters(bestRoute, wizardData);
  if (!Number.isFinite(bestDistance)) return [...route];

  const segmentStart = lockFirstStop ? 1 : 0;
  // lockLastStop：終點錨點必須留在原位，否則 2-opt 會把它翻到中間，
  // 回程那一段就不再是「最後一段」，整個閉環的意義就沒了。
  const segmentEnd = lockLastStop ? bestRoute.length - 1 : bestRoute.length;
  let improved = true;

  while (improved) {
    improved = false;
    for (let i = segmentStart; i < bestRoute.length - 2; i += 1) {
      for (let j = i + 1; j < segmentEnd; j += 1) {
        const candidate = [...bestRoute];
        const reversedSegment = candidate.slice(i, j + 1).reverse();
        candidate.splice(i, reversedSegment.length, ...reversedSegment);

        const candidateDistance = getRouteDistanceMeters(candidate, wizardData);
        if (candidateDistance + 1e-6 < bestDistance) {
          bestRoute = candidate;
          bestDistance = candidateDistance;
          improved = true;
        }
      }
    }
  }

  return bestRoute;
}

async function fetchDestinationPoiCache(destinationText) {
  if (!firebaseEnabled || !firebaseDb) return [];
  const destination = String(destinationText || '').trim();
  if (!destination) return [];

  try {
    const snapshot = await firebaseDb.collection('poi_cache').where('destination', '==', destination).limit(80).get();
    if (snapshot.empty) return [];
    const pois = [];
    snapshot.forEach((doc) => {
      const data = doc.data();
      const coord = getStopCoordinate(data);
      if (!coord) return;
      pois.push({
        name: data.name || '',
        address: data.address || data.location || '',
        lat: coord.lat,
        lng: coord.lng
      });
    });
    return pois;
  } catch (error) {
    console.warn('Firebase POI cache 讀取失敗：', error);
    return [];
  }
}

function splitLookupTerms(value) {
  const source = String(value || '')
    .toLowerCase()
    .replace(/\s+/g, ' ')
    .replace(/[()（）,，.。·•\-_/|]/g, ' ')
    .trim();
  if (!source) return [];
  const tokens = source
    .split(/\s+/)
    .filter(Boolean)
    .flatMap((token) => (token.length > 2 ? [token, ...token.split('').filter((char) => char.trim())] : [token]));
  return Array.from(new Set(tokens));
}

function getPoiMatchScore(stop, poi) {
  if (!stop || !poi) return 0;

  const stopNameRaw = String(stop.name || stop.title || '').trim();
  const stopAddressRaw = String(stop.address || (stop.location && stop.location.address) || '').trim();
  const stopDescRaw = String(stop.desc || stop.description || '').trim();
  const poiNameRaw = String(poi.name || '').trim();
  const poiAddressRaw = String(poi.address || '').trim();
  const poiDescRaw = String(poi.desc || poi.description || '').trim();

  const stopName = normalizeLookupText(stopNameRaw);
  const poiName = normalizeLookupText(poiNameRaw);
  if (!stopName || !poiName) return 0;

  let score = 0;
  if (stopName === poiName) score += 120;
  else if (stopName.includes(poiName) || poiName.includes(stopName)) score += 90;

  const stopAddress = normalizeLookupText(stopAddressRaw);
  const poiAddress = normalizeLookupText(poiAddressRaw);
  if (stopAddress && poiAddress) {
    if (stopAddress === poiAddress) score += 60;
    else if (stopAddress.includes(poiAddress) || poiAddress.includes(stopAddress)) score += 40;
  }

  const stopDesc = normalizeLookupText(stopDescRaw);
  const poiDesc = normalizeLookupText(poiDescRaw);
  if (stopDesc && poiDesc) {
    if (stopDesc.includes(poiName) || poiDesc.includes(stopName)) score += 20;
    if (stopDesc.includes(poiAddress) || poiDesc.includes(stopAddress)) score += 12;
  }

  const stopTokens = splitLookupTerms([stopNameRaw, stopAddressRaw, stopDescRaw].filter(Boolean).join(' '));
  const poiTokens = splitLookupTerms([poiNameRaw, poiAddressRaw, poiDescRaw].filter(Boolean).join(' '));
  const sharedTokens = stopTokens.filter((token) => poiTokens.includes(token));
  score += Math.min(28, sharedTokens.length * 4);

  const genericPattern = /(景點|公園|市場|老街|商圈|車站|夜市|海灘|步道|寺|廟|館)$/;
  if (genericPattern.test(poiNameRaw) && !stopAddress && !poiAddress) {
    score -= 8;
  }

  if (stopName.length <= 3 || poiName.length <= 3) {
    score -= 6;
  }

  return score;
}

function isBroadPlaceLabel(value) {
  const normalized = normalizeLookupText(value);
  if (!normalized) return true;
  if (normalized.length <= 2) return true;
  const str = String(value || '').trim();
  if (/^(公園|景點|市場|老街|商圈|車站|夜市|海灘|步道|寺|廟|館|橋|港|湖|山|公路|道路|大道)$/.test(str)) return true;
  // 模糊飲食標籤：在地午餐、當地早餐、附近餐廳、在地美食 等
  if (/^(在地|當地|附近).{0,4}[餐飯食吃]/.test(str) || /[在當].{0,3}[早午晚]餐/.test(str)) return true;
  return false;
}

function findPoiCacheCoordinate(stop, poiCache) {
  const list = Array.isArray(poiCache) ? poiCache : [];
  if (!list.length) return null;
  const stopNameRaw = String(stop && (stop.name || stop.title) || '').trim();

  let bestMatch = null;
  let bestScore = 0;

  for (const poi of list) {
    const score = getPoiMatchScore(stop, poi);
    if (score > bestScore) {
      const lat = Number(poi.lat);
      const lng = Number(poi.lng);
      if (!Number.isFinite(lat) || !Number.isFinite(lng)) continue;
      bestScore = score;
      bestMatch = { lat, lng, source: 'firebase_cache', score };
    }
  }

  if (!bestMatch) return null;
  const minScore = isBroadPlaceLabel(stopNameRaw) ? 100 : 70;
  if (bestScore < minScore) return null;
  return bestMatch;
}

function sanitizeStopCoordinates(stops, wizardData = {}, poiCache = []) {
  const sourceStops = Array.isArray(stops) ? stops : [];
  const destinationText = wizardData.dest || wizardData.destCustom || wizardData.region || '';
  const destinationCenter = getDestinationCenter(destinationText) || getPoiCacheCenter(poiCache);
  const maxDistanceToCenterMeters = getDestinationMaxDistanceMeters(destinationText);
  const maxLegDistanceMeters = getDurationMaxLegDistanceMeters(wizardData.days || '1天');
  const maxCacheDisagreementMeters = Math.min(15000, Math.floor(maxDistanceToCenterMeters * 0.35));

  const normalized = sourceStops.map((rawStop) => {
    const stop = rawStop && typeof rawStop === 'object' ? { ...rawStop } : rawStop;
    if (!stop || typeof stop !== 'object') return stop;

    const fallbackCoord = findPoiCacheCoordinate(stop, poiCache);
    let coord = getStopCoordinate(stop);

    if (!coord && fallbackCoord) {
      stop.lat = fallbackCoord.lat;
      stop.lng = fallbackCoord.lng;
      stop.coordinateSource = fallbackCoord.source;
      coord = getStopCoordinate(stop);
    }

    if (coord && fallbackCoord) {
      const disagreement = getDistanceMeters(coord, fallbackCoord);
      if (disagreement > maxCacheDisagreementMeters) {
        stop.lat = fallbackCoord.lat;
        stop.lng = fallbackCoord.lng;
        stop.coordinateSource = fallbackCoord.source;
        stop.coordinateWarning = 'replaced_with_cache_coordinate';
        coord = getStopCoordinate(stop);
      }
    }

    if (coord && destinationCenter) {
      const distanceToCenter = getDistanceMeters(coord, destinationCenter);
      if (distanceToCenter > maxDistanceToCenterMeters) {
        if (fallbackCoord) {
          stop.lat = fallbackCoord.lat;
          stop.lng = fallbackCoord.lng;
          stop.coordinateSource = fallbackCoord.source;
        } else {
          clearStopCoordinates(stop);
          stop.coordinateWarning = 'out_of_destination_range';
        }
      }
    }

    return stop;
  });

  const validCoords = normalized
    .map((stop) => ({ stop, coord: getStopCoordinate(stop) }))
    .filter((item) => item.coord);

  if (validCoords.length >= 3) {
    const centroid = {
      lat: validCoords.reduce((sum, item) => sum + item.coord.lat, 0) / validCoords.length,
      lng: validCoords.reduce((sum, item) => sum + item.coord.lng, 0) / validCoords.length
    };
    const maxDistanceToCentroidMeters = Math.min(maxDistanceToCenterMeters, Math.max(12000, Math.floor(maxLegDistanceMeters * 1.2)));

    normalized.forEach((stop) => {
      if (!stop || typeof stop !== 'object') return;
      const coord = getStopCoordinate(stop);
      if (!coord) return;
      const distanceToCentroid = getDistanceMeters(coord, centroid);
      if (distanceToCentroid > maxDistanceToCentroidMeters) {
        const fallbackCoord = findPoiCacheCoordinate(stop, poiCache);
        if (fallbackCoord) {
          stop.lat = fallbackCoord.lat;
          stop.lng = fallbackCoord.lng;
          stop.coordinateSource = fallbackCoord.source;
        } else {
          clearStopCoordinates(stop);
          stop.coordinateWarning = 'outlier_coordinate';
        }
      }
    });
  }

  for (let index = 1; index < normalized.length; index += 1) {
    const currentStop = normalized[index];
    const previousStop = normalized[index - 1];
    if (!currentStop || !previousStop) continue;
    const currentCoord = getStopCoordinate(currentStop);
    const previousCoord = getStopCoordinate(previousStop);
    if (!currentCoord || !previousCoord) continue;

    const legDistance = getDistanceMeters(previousCoord, currentCoord);
    if (legDistance > maxLegDistanceMeters) {
      const fallbackCoord = findPoiCacheCoordinate(currentStop, poiCache);
      if (fallbackCoord) {
        currentStop.lat = fallbackCoord.lat;
        currentStop.lng = fallbackCoord.lng;
        currentStop.coordinateSource = fallbackCoord.source;
        currentStop.coordinateWarning = 'long_leg_replaced_with_cache_coordinate';
      } else {
        clearStopCoordinates(currentStop);
        currentStop.coordinateWarning = 'long_leg_outlier_coordinate';
      }
    }
  }

  return normalized;
}

function shouldKeepFirstStopAsAnchor(stop) {
  if (!stop || typeof stop !== 'object') return false;
  const marker = `${stop.name || ''} ${stop.title || ''} ${(stop.tags || []).join(' ')}`;
  return /集合|出發|起點|報到|車站|機場|港口/.test(marker);
}

function sortStopsByNearestRoute(stops, wizardData = {}) {
  if (!Array.isArray(stops) || stops.length < 3) return Array.isArray(stops) ? [...stops] : [];

  const indexedStops = stops.map((stop, index) => ({
    index,
    stop,
    coord: getStopCoordinate(stop)
  }));
  const coordinateStops = indexedStops.filter((item) => item.coord);
  if (coordinateStops.length < 3) return [...stops];

  const routeCandidates = coordinateStops.map((item) => item.stop);
  const lockFirstStop = shouldKeepFirstStopAsAnchor(routeCandidates[0]);
  const startIndexes = lockFirstStop
    ? [0]
    : routeCandidates.map((_, index) => index);

  let bestRoute = [...routeCandidates];
  let bestDistance = getRouteDistanceMeters(bestRoute, wizardData);

  startIndexes.forEach((startIndex) => {
    const nearestRoute = buildNearestNeighborRoute(routeCandidates, startIndex, wizardData);
    const optimizedRoute = improveRouteWithTwoOpt(nearestRoute, lockFirstStop, wizardData);
    const optimizedDistance = getRouteDistanceMeters(optimizedRoute, wizardData);
    if (optimizedDistance < bestDistance) {
      bestRoute = optimizedRoute;
      bestDistance = optimizedDistance;
    }
  });

  const sortedKnownQueue = [...bestRoute];
  return indexedStops.map((item) => {
    if (!item.coord) return item.stop;
    return sortedKnownQueue.shift() || item.stop;
  });
}

// 最後輸出前再重排中段站，避免合併/補景點後出現南北來回跑：
// 以「起點座標」為錨點（鎖第一站）跑最近鄰 + 2-opt，讓中段沿路單調前進；無座標站附到尾端。
function reorderStopsAlongRoute(middleStops, startCoords, wizardData = {}, endCoords = null) {
  if (!Array.isArray(middleStops) || middleStops.length < 3) return middleStops;
  const coordStops = middleStops.filter(s => getStopCoordinate(s));
  const noCoordStops = middleStops.filter(s => !getStopCoordinate(s));
  if (coordStops.length < 3 || !startCoords || !Number.isFinite(Number(startCoords.lat))) return middleStops;
  const anchor = { name: '出發', type: 'start', lat: Number(startCoords.lat), lng: Number(startCoords.lng) };

  // ⚠ 終點錨點不能省。原本只鎖起點跑「開放路徑」最佳化，等於告訴演算法「走到哪算哪」，
  //   但實際行程一定要回到終點（多半就是出發的台東車站）。少了回程那一段的成本，
  //   離起點最遠的景點很自然會被排到倒數——實測就出現「加路蘭 → 初鹿牧場(11km 往內陸)
  //   → 鯉魚山(12.7km 回市區) → 車站」這種折返。把終點放進序列並鎖住尾端，
  //   最佳化看到的才是真正的閉環。
  const end = endCoords && Number.isFinite(Number(endCoords.lat)) ? endCoords : startCoords;
  const endAnchor = { name: '返回', type: 'end', lat: Number(end.lat), lng: Number(end.lng) };

  const seq = [anchor, ...coordStops, endAnchor];
  const nn = buildNearestNeighborRoute(seq, 0, wizardData);
  // 最近鄰會把終點錨點吃進中間，先抽出來再放回尾端，2-opt 才有正確的閉環可改善
  const nnNoEnd = nn.filter(s => s !== endAnchor);
  const opt = improveRouteWithTwoOpt([...nnNoEnd, endAnchor], true, wizardData, true);
  const ordered = opt.filter(s => s !== anchor && s !== endAnchor);
  if (ordered.length !== coordStops.length) return middleStops; // 防呆：數量對不上就不動
  return noCoordStops.length ? [...ordered, ...noCoordStops] : ordered;
}

function inferStopEmoji(name, desc) {
  const text = `${name || ''} ${desc || ''}`;
  const rules = [
    [/燈塔/, '🗼'],
    [/海灘|沙灘|海水浴場/, '🏖️'],
    [/博物館|紀念館|展覽館|美術館|文化館|史前/, '🏛️'],
    [/植物園/, '🌿'],
    [/公園|森林/, '🌳'],
    [/廟|宮|寺|佛|觀音/, '⛩️'],
    [/瀑布/, '💦'],
    [/步道|登山|健行/, '🥾'],
    [/溫泉/, '♨️'],
    [/夜市|市場|老街/, '🛍️'],
    [/餐廳|食堂|小吃|料理|美食|麵|飯/, '🍽️'],
    [/咖啡|茶館/, '☕'],
    [/民宿|飯店|旅館/, '🏨'],
    [/機場/, '✈️'],
    [/火車站|車站|鐵路/, '🚉'],
    [/漁港|港口|碼頭/, '⚓'],
    [/教堂|教會/, '⛪'],
    [/湖|水庫/, '🏞️'],
    [/牧場|草原/, '🌾'],
    [/農場|果園/, '🌻'],
    [/藝術|文創/, '🎨'],
    [/音樂|表演/, '🎵'],
    [/劇場|電影/, '🎭'],
    [/潛水|浮潛/, '🤿'],
    [/衝浪/, '🏄'],
    [/自行車|腳踏車/, '🚴'],
    [/觀景台|瞭望台|展望台/, '🔭'],
    [/夜景/, '🌃'],
    [/島|嶼/, '🏝️'],
    [/橋/, '🌉'],
    [/峽谷|峭壁|懸崖/, '🏔️'],
    [/洞穴|石窟/, '🕳️'],
    [/火山/, '🌋'],
    [/山|嶺|峰/, '⛰️'],
    [/海岸|海邊|海崖/, '🌊'],
    [/遊客中心|服務中心/, 'ℹ️'],
  ];
  for (const [pattern, emoji] of rules) {
    if (pattern.test(text)) return emoji;
  }
  return null;
}

function isActualEmoji(str) {
  if (!str || typeof str !== 'string') return false;
  const cp = str.codePointAt(0);
  // Reject CJK ideographs (0x3400–0x9FFF) and plain ASCII — those are text, not emoji
  if (cp >= 0x3400 && cp <= 0x9FFF) return false;
  if (cp < 0x2000) return false;
  return true;
}

function normalizeGeneratedStop(stop, index, fallbackTime) {
  const safeStop = stop && typeof stop === 'object' ? { ...stop } : {};
  safeStop.order = index + 1;
  safeStop.emoji = safeStop.emoji || '📍';
  safeStop.name = safeStop.name || safeStop.title || `景點 ${index + 1}`;

  // If AI filled emoji field with Chinese text (e.g. "燈塔") instead of an actual emoji,
  // strip all leading occurrences of that word from the name, then reset to '📍'
  if (safeStop.emoji !== '📍' && !isActualEmoji(safeStop.emoji)) {
    let nm = typeof safeStop.name === 'string' ? safeStop.name : '';
    const fakeEmoji = safeStop.emoji;
    while (nm.startsWith(fakeEmoji)) {
      nm = nm.slice(fakeEmoji.length).trimStart();
    }
    safeStop.name = nm || `景點 ${index + 1}`;
    safeStop.emoji = '📍';
  }

  // Strip leading actual-emoji from name (prevents double-emoji when AI embeds emoji into name field)
  if (safeStop.emoji !== '📍' && typeof safeStop.name === 'string' && safeStop.name.startsWith(safeStop.emoji)) {
    safeStop.name = safeStop.name.slice(safeStop.emoji.length).trimStart();
  }
  if (!safeStop.name) safeStop.name = `景點 ${index + 1}`;
  // Infer a meaningful emoji from name/desc keywords when AI provided no specific emoji
  if (safeStop.emoji === '📍') {
    const inferred = inferStopEmoji(safeStop.name, safeStop.desc || safeStop.description);
    if (inferred) safeStop.emoji = inferred;
  }
  safeStop.time = normalizeClockInput(safeStop.time, fallbackTime);
  safeStop.desc = safeStop.desc || safeStop.description || '依照你的偏好安排的行程停留點。';
  safeStop.duration = Math.max(10, Math.min(180, Number(safeStop.duration) || 30));
  const normalizedCoord = getStopCoordinate(safeStop);
  if (normalizedCoord) {
    safeStop.lat = normalizedCoord.lat;
    safeStop.lng = normalizedCoord.lng;
  }
  filterNearbyToiletsByDistance(safeStop);
  return safeStop;
}

function parseBusinessHoursWindow(value) {
  const source = String(value || '');
  const match = source.match(/(\d{1,2}):(\d{2})\s*(?:-|~|to)\s*(\d{1,2}):(\d{2})/i);
  if (!match) return null;
  const open = Number(match[1]) * 60 + Number(match[2]);
  const close = Number(match[3]) * 60 + Number(match[4]);
  if (!Number.isFinite(open) || !Number.isFinite(close) || close <= open) return null;
  return { open, close };
}

// 該站所屬的實際日期：多日行程第 N 天 = 出發日 + (N-1) 天。公休檢查必須用這個，
// 不能一律用出發日（否則第二天的站會被拿第一天的星期去判斷；E2E #5）。
function stopServiceDate(stop, wizardData) {
  const base = wizardData && wizardData.departureDate;
  if (!base) return '';
  const di = Math.max(1, Number(stop && stop.dayIndex) || 1);
  if (di === 1) return base;
  const d = new Date(base + 'T00:00:00');
  if (Number.isNaN(d.getTime())) return base;
  d.setDate(d.getDate() + (di - 1));
  const p = (n) => String(n).padStart(2, '0');
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}`;
}

function extractDayHoursWindow(businessHoursStr, departureDate) {
  if (!businessHoursStr) return null;
  if (!departureDate) return parseBusinessHoursWindow(businessHoursStr);
  // 解析一律委派給全站唯一來源 business-hours.js（window.WAI_HOURS），避免各畫面各自解析
  // 而對同一家店給出不同答案。unknown 一律回 null＝「資訊不足」，呼叫端不得據以判公休。
  const H = (typeof WAI_HOURS !== 'undefined' && WAI_HOURS) ? WAI_HOURS : null;
  if (!H) return parseBusinessHoursWindow(businessHoursStr);
  const st = H.parseDayStatus(businessHoursStr, departureDate);
  if (st.status === 'closed') return { closed: true, label: st.label };
  if (st.status === 'open') return { open: st.open, close: st.close, closed: false, label: st.label };
  return null;
}

// ══════════════════════════════════════════════════
// 用餐時段錨定
// 行程時間軸是「停留時長一站接一站串起來」的（applyTripPlanningRules），不含任何用餐概念。
// 所以只要前面有站被濾掉（當天公休、驗證失敗），整條鏈就往前縮，AI 排在 12:10 的午餐會被
// 重算成 10:05。加上地理排序只看距離，晚餐還可能被挪到第 3 站。以下兩件事分開處理：
//   1. placeMealStopsAtMealTimes：把用餐站「移到時鐘真的走到那一餐的位置」（順序）
//   2. applyTripPlanningRules 內的錨定：時鐘太早就延到開餐時間（時間）
// aiFrom/aiTo＝從 AI 給的時間判斷這站是哪一餐；anchor/latest＝可接受的開餐區間。
// ══════════════════════════════════════════════════
// aiFrom/aiTo 刻意抓得窄：只認「明顯就是正餐」的時間。放寬到 15:00 會把下午茶咖啡廳
// 當成午餐拉到 11:30，那是把使用者沒要求的東西改掉。
const MEAL_WINDOWS = [
  { key: 'lunch',  aiFrom: 11 * 60,      aiTo: 14 * 60,      anchor: 11 * 60 + 30, latest: 13 * 60 + 30 },
  { key: 'dinner', aiFrom: 17 * 60,      aiTo: 20 * 60 + 30, anchor: 17 * 60 + 30, latest: 19 * 60 + 30 }
];

// 這站是不是「AI 刻意排的某一餐」。判斷依據是 AI 給的原始時間——
// 早餐店、下午茶咖啡廳落在任一用餐時段之外，回 null＝不動它，維持原本行為。
function getStopMealWindow(stop) {
  if (!stop || typeof isFoodStop !== 'function' || !isFoodStop(stop)) return null;
  const raw = String(stop.time || '').trim();
  if (!raw) return null;
  const within = ((timeStringToMinutes(raw) % 1440) + 1440) % 1440;
  return MEAL_WINDOWS.find((w) => within >= w.aiFrom && within <= w.aiTo) || null;
}

// 插隊成本：把用餐站插在 prev 與 next 之間多繞的公尺數。缺座標就當 0（不因此排除該位置）。
function mealInsertDetourMeters(prev, next, meal) {
  const a = prev ? getStopCoordinate(prev) : null;
  const b = next ? getStopCoordinate(next) : null;
  const m = getStopCoordinate(meal);
  if (!m || (!a && !b)) return 0;
  if (!a) return getDistanceMeters(m, b);
  if (!b) return getDistanceMeters(a, m);
  return getDistanceMeters(a, m) + getDistanceMeters(m, b) - getDistanceMeters(a, b);
}

// 單日內：把用餐站抽出來，再插回「開餐時間落在用餐時段」的位置；同時段有多個位置可選時挑繞路最少的。
function arrangeMealsWithinDay(dayStops, dayStart, dayIndex) {
  const meals = [];
  const rest = [];
  dayStops.forEach((s) => {
    const win = getStopMealWindow(s);
    if (win) meals.push({ stop: s, win }); else rest.push(s);
  });
  if (!meals.length) return dayStops;

  const result = rest.slice();
  meals.sort((a, b) => a.win.anchor - b.win.anchor);
  for (const { stop, win } of meals) {
    const dayBase = (dayIndex - 1) * 1440;
    const anchorAt = dayBase + win.anchor;
    const latestAt = dayBase + win.latest;
    // 依停留時長推算每個插入位置的開餐時間（與 applyTripPlanningRules 同一套鏈算法）
    let clock = dayStart;
    let fallbackIdx = -1; // 沒有位置能落在用餐時段內時：第一個已過開餐時間的位置（寧可晚吃，不提前）
    let bestIdx = -1;
    let bestDetour = Infinity;
    for (let i = 0; i <= result.length; i++) {
      if (clock >= anchorAt) {
        if (fallbackIdx < 0) fallbackIdx = i;
        if (clock <= latestAt) {
          const detour = mealInsertDetourMeters(result[i - 1] || null, result[i] || null, stop);
          if (detour < bestDetour) { bestDetour = detour; bestIdx = i; }
        }
      }
      if (i < result.length) {
        // 已插入的用餐站稍後會被錨定到開餐時間，這裡的時鐘要跟著往後推，
        // 否則推算晚餐位置時會少算「午餐等到 11:30」的那段，晚餐位置偏早。
        const placed = getStopMealWindow(result[i]);
        if (placed) clock = Math.max(clock, dayBase + placed.anchor);
        clock += Math.max(10, Number(result[i].duration) || 30);
      }
    }
    const insertAt = bestIdx >= 0 ? bestIdx : (fallbackIdx >= 0 ? fallbackIdx : result.length);
    result.splice(insertAt, 0, stop);
  }
  return result;
}

// 用餐站只在「自己那一天」內移動，不跨天，也不改變其他站的相對順序。
function placeMealStopsAtMealTimes(stops, wizardData = {}) {
  if (!Array.isArray(stops) || stops.length < 2) return stops;
  const windows = getTripDayWindows(wizardData);
  const startMinutes = timeStringToMinutes(normalizeClockInput(wizardData.startTime, '09:00'));
  const dayOf = (s) => (windows.multi
    ? Math.max(1, Math.min(windows.dayCount, Math.round(Number(s && s.dayIndex)) || 1))
    : 1);
  const dayStartOf = (day) => {
    const w = windows.multi && windows.dayWindows[day - 1];
    return w ? (day - 1) * 1440 + timeStringToMinutes(w.start) : startMinutes;
  };

  const groups = new Map();
  stops.forEach((s) => {
    const d = dayOf(s);
    if (!groups.has(d)) groups.set(d, []);
    groups.get(d).push(s);
  });

  const out = [];
  [...groups.keys()].sort((a, b) => a - b).forEach((d) => {
    out.push(...arrangeMealsWithinDay(groups.get(d), dayStartOf(d), d));
  });
  return out;
}

function applyTripPlanningRules(stops, wizardData = {}) {
  const pace = wizardData.pace || '平衡';
  const maxGapMinutes = Number(wizardData.slotMinutes) || getDefaultSlotMinutes(pace);
  const startMinutes = timeStringToMinutes(normalizeClockInput(wizardData.startTime, '09:00'));
  const windows = getTripDayWindows(wizardData);
  let previousEndMinutes = null;

  return stops.map((stop, index) => {
    // 過夜行程的第二天要從該日窗口重新開始，不能把兩天接成一條連續時間軸。
    const dayIndex = windows.multi
      ? Math.max(1, Math.min(windows.dayCount, Math.round(Number(stop && stop.dayIndex)) || 1))
      : 1;
    const dayWindow = windows.multi && windows.dayWindows[dayIndex - 1];
    const dayStart = dayWindow
      ? (dayIndex - 1) * 1440 + timeStringToMinutes(dayWindow.start)
      : startMinutes;
    const defaultStart = index === 0 ? startMinutes : Math.max(previousEndMinutes ?? startMinutes, dayStart);
    const normalized = normalizeGeneratedStop(stop, index, minutesToTimeString(defaultStart));
    let currentMinutes = defaultStart;

    // 用餐站錨定：時鐘比開餐時間早就延到開餐時間（只延後、不提前——提前要壓縮前面各站，
    // 那是使用者沒要求的改動）。沒有這段，被濾掉幾站之後午餐就會落在 10 點。
    // 但錨定不得把用餐站推到當天結束時間之後（例：行程只到 15:00 卻把晚餐推到 17:30）。
    const mealWindow = getStopMealWindow(stop);
    if (mealWindow) {
      const dayBase = Math.floor(currentMinutes / 1440) * 1440;
      const anchorAt = dayBase + mealWindow.anchor;
      const dayEnd = dayWindow
        ? (dayIndex - 1) * 1440 + timeStringToMinutes(dayWindow.end)
        : timeStringToMinutes(windows.end || normalizeClockInput(wizardData.endTime, '18:00'));
      // 連「吃完」都要在當天結束前：只看開餐時間會排出 17:30 開始、18:10 才吃完卻說行程 18:00 結束
      const stayMin = Math.max(10, Number(normalized.duration) || 30);
      if (currentMinutes < anchorAt && anchorAt + stayMin <= dayEnd) currentMinutes = anchorAt;
    }

    // 營業時間調整：若需等候且等候時長在上限內，推遲開始。
    // 其餘情況（當天公休、等太久、已過打烊）只是「不調整時間」，不再寫 scheduleWarning ——
    // 那個旗標沒有任何消費端（不在 serializeStopForPersistence 白名單裡，到不了 planner），
    // 而 planner 的行程卡與重排畫面本來就用同一份 business-hours.js 即時重算並顯示
    // （formatDayBusinessHours 的 🔴、getBusinessHoursWarning 的 ⚠️）。
    // 留著等於又養出第二份營業時間狀態，正是這條修復線要消滅的東西。
    const businessWindow = extractDayHoursWindow(normalized.businessHours, stopServiceDate(normalized, wizardData));
    if (businessWindow && !businessWindow.closed) {
      const openMinutes = Math.floor(currentMinutes / 1440) * 1440 + businessWindow.open;
      if (currentMinutes < openMinutes && openMinutes - currentMinutes <= maxGapMinutes) {
        currentMinutes = openMinutes;
      }
    }

    normalized.time = minutesToTimeString(currentMinutes);
    normalized.order = index + 1;

    // 以建議停留時長推算結束時間，作為下一站的起點
    const duration = normalized.duration || 30;
    previousEndMinutes = currentMinutes + duration;

    return normalized;
  });
}

function assignTransportModes(stops, preferredMode) {
  // 依使用者選擇的交通工具（計程車/機車/汽車）統一指派；短程（≤500m）改為步行。
  const VALID_VEHICLES = ['taxi', 'scooter', 'car'];
  const vehicle = VALID_VEHICLES.includes(String(preferredMode || '').trim().toLowerCase())
    ? String(preferredMode).trim().toLowerCase()
    : 'car';

  return stops.map((stop, index) => {
    if (index >= stops.length - 1) return stop;
    const fromCoord = getStopCoordinate(stop);
    const toCoord = getStopCoordinate(stops[index + 1]);
    const dist = (fromCoord && toCoord) ? getDistanceMeters(fromCoord, toCoord) : null;
    // 有座標且距離很短 → 步行；其餘（含無座標）一律使用所選交通工具
    const transitMode = (dist !== null && dist <= 500) ? 'walk' : vehicle;
    return { ...stop, transitMode };
  });
}

function fuzzyMatchLocation(stopName, locationName) {
  if (!stopName || !locationName) return false;
  // 「火車站→車站」正規化：AI 常把起訖站寫成「台東火車站」，須與「台東車站」視為同一地，
  // 否則樞紐站會被當成一般景點留在行程中（起點旁邊再排一個 26 分鐘的「台東火車站」）。
  const norm = (s) => s.trim().toLowerCase().replace(/\s/g, '').replace(/火車站/g, '車站');
  const a = norm(stopName);
  const b = norm(locationName);
  return a.includes(b) || b.includes(a);
}

function injectEndpointStops(stops, wizardData = {}) {
  const startLoc = (wizardData.startLocation || '').trim();
  const endLoc = (wizardData.endLocation || '').trim();
  let result = [...stops];

  if (startLoc) {
    if (result.length && fuzzyMatchLocation(result[0].name, startLoc)) {
      result[0] = { ...result[0], type: 'start' };
    } else {
      result.unshift({
        order: 0, type: 'start', emoji: '🚩', name: startLoc,
        time: normalizeClockInput(wizardData.startTime, '09:00'),
        desc: '旅程起始點，從這裡出發。', duration: 0,
        ...(wizardData.startCoords ? { lat: wizardData.startCoords.lat, lng: wizardData.startCoords.lng } : {})
      });
    }
  }

  if (endLoc) {
    const last = result[result.length - 1];
    if (last && fuzzyMatchLocation(last.name, endLoc)) {
      result[result.length - 1] = { ...last, type: 'end' };
    } else {
      result.push({
        order: result.length + 1, type: 'end', emoji: '🏁', name: endLoc,
        time: last ? last.time : normalizeClockInput(wizardData.startTime, '09:00'),
        desc: '旅程結束點，回到這裡。', duration: 0,
        ...(wizardData.endCoords ? { lat: wizardData.endCoords.lat, lng: wizardData.endCoords.lng } : {})
      });
    }
  }

  result.forEach((s, i) => { s.order = i + 1; });
  return result;
}

const INTEREST_QUERY_TERMS = {
  '美食': ['美食', '餐廳'],
  '文化': ['文化', '部落', '工藝'],
  '自然': ['步道', '自然景觀'],
  '休閒': ['公園', '景點'],
  '藝術': ['藝術', '博物館'],
  '歷史': ['歷史', '古蹟'],
  '購物': ['市集'],
  '戶外': ['戶外', '山林'],
};

async function fetchGoogleMapsPoiList(destination, interests = [], destCenter = null, termsOverride = null) {
  const key = window.TRAVEL_APP_CONFIG?.GOOGLE_MAPS_API_KEY;
  if (!key || !destination) return [];

  const terms = termsOverride ? new Set(termsOverride) : new Set(['景點', '餐廳']);
  if (!termsOverride) {
    (interests || []).forEach(interest => {
      (INTEREST_QUERY_TERMS[interest] || []).forEach(t => terms.add(t));
    });
  }
  const queries = Array.from(terms).slice(0, 5).map(t => `${resolveGeoRegion(destination)} ${t}`);

  const radius = getDestinationMaxDistanceMeters(destination) || 15000;
  const seenNames = new Set();
  const allPlaces = [];

  // 平行發出所有 query（原本逐個 await 串行，5 個 query 吃 5 個 RTT）；
  // 去重與 25 筆上限仍照原 query 順序在結果陣列上處理，輸出與序列版一致。
  const fetchOne = async (query) => {
    const body = {
      textQuery: query,
      languageCode: 'zh-TW',
      maxResultCount: 10,
    };
    if (destCenter) {
      body.locationBias = {
        circle: {
          center: { latitude: destCenter.lat, longitude: destCenter.lng },
          radius: Math.min(radius, 50000)
        }
      };
    }
    try {
      const res = await placesFetch('searchText',
        'places.displayName,places.location,places.types,places.regularOpeningHours,places.formattedAddress,places.rating',
        body, 10000, '景點清單', key);
      const data = await res.json();
      return Array.isArray(data.places) ? data.places : [];
    } catch (e) {
      console.warn('fetchGoogleMapsPoiList 失敗:', e);
      return [];
    }
  };
  const resultsByQuery = await Promise.all(queries.map(fetchOne));

  for (const places of resultsByQuery) {
    for (const place of places) {
      const name = place.displayName?.text;
      if (!name || seenNames.has(name)) continue;
      seenNames.add(name);
      allPlaces.push({
        name,
        lat: place.location?.latitude,
        lng: place.location?.longitude,
        businessHours: (place.regularOpeningHours?.weekdayDescriptions || []).join('；') || '未知',
        address: place.formattedAddress || '',
        rating: place.rating ?? null,
      });
      if (allPlaces.length >= 25) break;
    }
    if (allPlaces.length >= 25) break;
  }
  return allPlaces;
}

function buildLiveMapsPoiHintBlock(places) {
  if (!Array.isArray(places) || !places.length) return '';
  const lines = places.map(p => {
    const coordLine = (p.lat != null && p.lng != null)
      ? `景點座標：lat ${p.lat}, lng ${p.lng}`
      : '景點座標：未知';
    return [
      `景點名稱：${p.name}`,
      coordLine,
      `營業時間：${p.businessHours}`,
      `地址：${p.address || '無'}`,
      p.rating ? `評分：${p.rating}` : ''
    ].filter(Boolean).join('\n');
  });
  return `【Google Maps 景點清單】以下景點已直接從 Google Maps 取得，座標均已驗證。你的任務是從此清單中挑選景點並排成行程，禁止自行創造或加入清單以外的景點，景點名稱必須與清單完全一致：\n\n${lines.join('\n\n')}`;
}

// 餐廳本地優先：有 restaurant-data.js 快取就直接用（省 Places），否則即時抓最新。
async function fetchGoogleMapsFoodList(destination, destCenter = null) {
  const cached = getLocalFoodList(destination);
  const list = cached.length
    ? cached
    : await fetchGoogleMapsPoiList(destination, [], destCenter, ['餐廳', '美食', '小吃']);
  // 標記來源為餐廳：isFoodStop 靠名稱關鍵字＋emoji 猜，認不出「榕樹下米苔目」「阿鋐炸雞」
  // 這類真實店名（也不該靠 AI 有沒有給對 emoji）。這些項目本來就是從餐廳資料來的，直接掛 tag，
  // 後續的用餐時段錨定、合併閘門、餐費計算才判得準。複製一份，不污染 restaurant-data.js 的快取物件。
  return (Array.isArray(list) ? list : []).map((p) => (p && p.tag === 'food' ? p : { ...p, tag: 'food' }));
}

function buildLiveFoodHintBlock(places) {
  if (!Array.isArray(places) || !places.length) return '';
  const lines = places.map(p => {
    const coordLine = (p.lat != null && p.lng != null) ? `座標：lat ${p.lat}, lng ${p.lng}` : '座標：未知';
    const costLine = p.costNote ? `人均消費：${p.costNote}` : (Number.isFinite(p.costPerPerson) ? `人均消費：約 $${p.costPerPerson}` : '');
    return [`餐廳名稱：${p.name}`, coordLine, `營業時間：${p.businessHours}`, `地址：${p.address || '無'}`, p.rating ? `評分：${p.rating}` : '', costLine].filter(Boolean).join('\n');
  });
  return `【即時餐廳候選（Google Maps）】用餐站「只能」從以下餐廳挑選，名稱需與清單完全一致，嚴禁使用清單以外的餐廳；請依使用者預算優先選擇人均消費相符的餐廳：\n\n${lines.join('\n\n')}`;
}

function findBestPoiMatch(stopName, livePlaces) {
  if (!stopName || !Array.isArray(livePlaces) || !livePlaces.length) return null;
  const exact = livePlaces.find(p => p.name === stopName);
  if (exact) return exact;
  return livePlaces.find(p => fuzzyMatchLocation(stopName, p.name)) || null;
}

function matchStopsToLivePlaces(stops, livePlaces) {
  if (!Array.isArray(livePlaces) || !livePlaces.length) return stops;
  return stops.map(stop => {
    if (!stop || typeof stop !== 'object') return stop;
    if (stop.type === 'start' || stop.type === 'end') return stop;
    const match = findBestPoiMatch(stop.name || stop.title, livePlaces);
    if (!match) return stop;
    return {
      ...stop,
      name: match.name,
      lat: match.lat,
      lng: match.lng,
      businessHours: stop.businessHours || match.businessHours || null,
      address: stop.address || match.address || '',
      coordinateSource: 'google_places_matched',
      // 命中的是餐廳清單 → 把 tag 帶到站上，讓 isFoodStop 不必靠店名關鍵字猜
      ...(match.tag === 'food' ? { tag: 'food' } : {}),
      // 餐廳人均消費（若有）隨 stop 帶走，供 planner 卡片/預算顯示
      ...(Number.isFinite(match.costPerPerson) ? { costPerPerson: match.costPerPerson } : {}),
      ...(match.costNote ? { costNote: match.costNote } : {})
    };
  });
}

async function fetchCoordinateFromGooglePlaces(name, destination) {
  const key = window.TRAVEL_APP_CONFIG && window.TRAVEL_APP_CONFIG.GOOGLE_MAPS_API_KEY;
  if (!key || !name) return null;
  try {
    const body = {
      textQuery: name,
      languageCode: 'zh-TW',
      maxResultCount: 3
    };
    const destCenter = destination ? getDestinationCenter(destination) : null;
    if (destCenter) {
      body.locationBias = {
        circle: {
          center: { latitude: destCenter.lat, longitude: destCenter.lng },
          // Places API (New) 的 locationBias.circle.radius 上限為 50000 公尺，超過會回 400 Bad Request
          radius: Math.min(getDestinationMaxDistanceMeters(destination) || 50000, 50000)
        }
      };
    }
    const res = await placesFetch('searchText', 'places.location,places.displayName',
      body, 10000, '座標查詢', key);
    const data = await res.json();
    const places = Array.isArray(data.places) ? data.places : [];
    // 只接受名稱嚴格配對者；找不到就回 null（不要退回 places[0]，避免套到只共用通用後綴的別處地點）
    const matched = places.find(p => p && p.location && placeNameMatchesQuery(p.displayName && p.displayName.text, name));
    if (matched && matched.location) {
      return { lat: matched.location.latitude, lng: matched.location.longitude, source: 'google_places' };
    }
  } catch (e) { /* silent */ }
  return null;
}

async function enrichMissingCoordinates(stops, destinationText) {
  if (!Array.isArray(stops) || !destinationText) return stops;
  await Promise.all(stops.map(async (stop) => {
    if (!stop || typeof stop !== 'object' || getStopCoordinate(stop)) return;
    const name = stop.name || stop.title;
    if (!name) return;
    const coord = await fetchCoordinateFromGooglePlaces(name, destinationText);
    if (coord) {
      stop.lat = coord.lat;
      stop.lng = coord.lng;
      stop.coordinateSource = coord.source;
    }
  }));
  return stops;
}

// 地名通用後綴／類別詞：兩個名稱只共用這些詞不算同一地點（如「成功漁港」vs「富岡漁港」只共用「漁港」）
const GENERIC_PLACE_SUFFIXES = [
  '觀光漁港', '漁港', '港口', '碼頭', '港',
  '火車站', '高鐵站', '捷運站', '轉運站', '客運站', '車站', '站',
  '國家公園', '森林公園', '地質公園', '公園',
  '國家風景區', '風景區', '遊客中心', '文化園區', '園區',
  '部落', '老街', '夜市', '步道', '古道', '大橋', '吊橋', '橋',
  '溫泉', '瀑布', '農場', '牧場', '林場',
  '博物館', '美術館', '紀念館', '故事館', '展覽館',
  '寺', '宮', '廟', '教堂', '神社'
];
// 取「特徵核心」：去掉尾端一個通用後綴（如 成功漁港→成功、比西里岸部落→比西里岸）
function stripGenericPlaceSuffix(s) {
  let t = String(s || '');
  for (const suf of GENERIC_PLACE_SUFFIXES) {
    if (t.length > suf.length && t.endsWith(suf)) return t.slice(0, -suf.length);
  }
  return t;
}
function placeNameMatchesQuery(displayName, queryName) {
  if (!displayName || !queryName) return true;
  const clean = s => String(s).replace(/[\s（）()[\]「」·\-_\/,.。，、！!？?～~]/g, '').toLowerCase();
  const dn = clean(displayName);
  const qn = clean(queryName);
  if (!dn || !qn) return true;
  // 一方完整包含另一方 → 同地點（涵蓋別名內含，如「成功漁港(新港漁港)」含「新港漁港」）
  if (dn.includes(qn) || qn.includes(dn)) return true;
  // 去通用後綴後比特徵核心：互相包含、或前 2 字相同才算同地點，否則拒絕
  const dCore = clean(stripGenericPlaceSuffix(displayName));
  const qCore = clean(stripGenericPlaceSuffix(queryName));
  if (dCore && qCore) {
    if (dCore.includes(qCore) || qCore.includes(dCore)) return true;
    if (dCore.length >= 2 && qCore.length >= 2 && dCore.slice(0, 2) === qCore.slice(0, 2)) return true;
  }
  return false;
}

async function verifyAndFilterStopsWithPlaces(stops, destination, wizardData = {}) {
  const key = window.TRAVEL_APP_CONFIG?.GOOGLE_MAPS_API_KEY;
  if (!key || !destination || !Array.isArray(stops)) return stops;

  // 先吃本地「已驗證」景點：名稱命中就用本地座標/營業時間，跳過 Places 呼叫
  // （poi-data.js 的這些景點已由 crawler 的 verify:places 校正成 Places 等級）。
  const _cleanName = (s) => String(s || '').replace(/[\s（）()[\]「」·\-_\/,.。，、！!？?～~]/g, '').replace(/臺/g, '台').toLowerCase();
  const _localVerifiedByName = new Map();
  for (const p of getLocalPoiList(destination)) {
    if (p && p.placeVerified && Number.isFinite(Number(p.lat)) && Number.isFinite(Number(p.lng))) {
      _localVerifiedByName.set(_cleanName(p.name), p);
    }
  }
  // 快取餐廳皆 Places 來源，視為已驗證 → 選中的餐廳站也跳過 Places
  const _localFoodNames = new Set();
  for (const p of getLocalFoodList(destination)) {
    if (p && Number.isFinite(Number(p.lat)) && Number.isFinite(Number(p.lng))) {
      _localVerifiedByName.set(_cleanName(p.name), p);
      _localFoodNames.add(_cleanName(p.name));
    }
  }

  const results = await Promise.all(stops.map(async (stop) => {
    if (stop.type === 'start' || stop.type === 'end') return stop;
    const name = stop.name || stop.title;
    if (!name) return null;

    // 命中本地已驗證景點 → 直接採用本地座標/時間，省一次 Places searchText
    const _localHit = _localVerifiedByName.get(_cleanName(name));
    if (_localHit) {
      stop.lat = Number(_localHit.lat);
      stop.lng = Number(_localHit.lng);
      stop.coordinateSource = 'local_verified';
      if (_localHit.businessHours) {
        stop.businessHours = _localHit.businessHours;
        if (wizardData.departureDate) {
          const dayWindow = extractDayHoursWindow(stop.businessHours, stopServiceDate(stop, wizardData));
          if (dayWindow && dayWindow.closed) return null;
        }
      }
      stop.placeVerified = true;
      stop.coordVerified = true; // 本地座標已由 crawler verify:places 校正過 → planner 載入免重驗
      // 名字在餐廳快取裡＝這站確定是餐廳。這是每一站都會經過的地方，比名稱關鍵字可靠，
      // 用餐時段錨定與合併閘門都靠這個 tag。
      if (_localFoodNames.has(_cleanName(name))) stop.tag = 'food';
      return stop;
    }

    // live Maps 清單命中的站（matchStopsToLivePlaces 已貼上 searchText 來源的座標/營業時間）
    // 資料與本函式要抓的完全同源，重打一次沒有資訊增益 → 直接短路，省一次 Places。
    if (stop.coordinateSource === 'google_places_matched'
        && Number.isFinite(Number(stop.lat)) && Number.isFinite(Number(stop.lng))) {
      if (stop.businessHours && wizardData.departureDate) {
        const dayWindow = extractDayHoursWindow(stop.businessHours, stopServiceDate(stop, wizardData));
        if (dayWindow && dayWindow.closed) return null; // 出發日休息 → 照舊過濾
      }
      stop.placeVerified = true;
      stop.coordVerified = true; // searchText 來源座標，與本函式驗證同級
      return stop;
    }

    try {
      // 取多筆候選，從中挑「名稱嚴格配對且在目的地範圍內」者，避免只取 top1 而被通用後綴（漁港/部落）誤配到別的地點
      const res = await placesFetch('searchText',
        'places.displayName,places.location,places.regularOpeningHours',
        { textQuery: `${name} ${resolveGeoRegion(destination)}`, languageCode: 'zh-TW', maxResultCount: 5 },
        10000, '景點驗證', key);
      const data = await res.json();
      const places = Array.isArray(data.places) ? data.places : [];
      if (!places.length) return null;

      const destCenter = getDestinationCenter(destination);
      const maxDist = getDestinationMaxDistanceMeters(destination) || 55000;
      const matched = places.find((p) => {
        if (!p || !p.location) return false;
        const pName = (p.displayName?.text || '').trim();
        if (pName && !placeNameMatchesQuery(pName, name)) return false;
        const pCoord = { lat: p.location.latitude, lng: p.location.longitude };
        if (destCenter && getDistanceMeters(destCenter, pCoord) > maxDist) return false;
        return true;
      });

      if (!matched) {
        // 找不到名稱嚴格配對者 → 保留該站與 AI 既有座標，不丟站、也不套到錯誤地點（標記未驗證）
        stop.placeVerified = false;
        return stop;
      }

      stop.lat = matched.location.latitude;
      stop.lng = matched.location.longitude;
      stop.coordinateSource = 'google_places';
      const hours = matched.regularOpeningHours?.weekdayDescriptions;
      if (Array.isArray(hours) && hours.length) {
        stop.businessHours = hours.join('\n');
        if (wizardData.departureDate) {
          const dayWindow = extractDayHoursWindow(stop.businessHours, stopServiceDate(stop, wizardData));
          if (dayWindow && dayWindow.closed) return null;
        }
      }
      stop.placeVerified = true;
      stop.coordVerified = true; // Places 嚴格配對成功的座標 → planner 載入免重驗
      return stop;
    } catch (e) {
      return null;
    }
  }));

  const verified = results.filter(Boolean);
  // Exclude transit hub stops (start/end location) from the "real attraction" count.
  // Without this, if the AI includes the hub (e.g. 大武車站) as both first and last stop,
  // those 2 verified hub entries would satisfy the >= 2 threshold even though every actual
  // attraction was filtered out, causing all middle stops to be silently discarded.
  const defaultHub = getDefaultTransitHub(destination);
  const startLoc = (wizardData.startLocation || '').trim() || defaultHub;
  const endLoc   = (wizardData.endLocation   || '').trim() || defaultHub;
  const mainStops = verified.filter(s => {
    if (s.type === 'start' || s.type === 'end') return false;
    if (fuzzyMatchLocation(s.name, startLoc) || fuzzyMatchLocation(s.name, endLoc)) return false;
    return true;
  });
  // 樞紐站去重：AI 有時會把起訖站又排成中途「景點」（起點台東車站後緊接「台東火車站」26 分鐘），
  // 它不是景點——直接剔除，避免多出無意義的停留與步行段。
  const withoutHubDupes = verified.filter(s => {
    if (s.type === 'start' || s.type === 'end') return true;
    return !(fuzzyMatchLocation(s.name, startLoc) || fuzzyMatchLocation(s.name, endLoc));
  });
  return mainStops.length >= 2 ? withoutHubDupes : stops;
}

// === 同一大景區內密集子景點合併（與 ai-travel-planner-v8.html 行為一致）===
// 以「距離為主」：同一大景區 800m 內的景點（即使不同名，如三仙台周邊的礫石灘）即合併成
// 單一「三仙台」站；同名（同前綴家族）再放寬到 2km；缺座標才退回名稱判定。避免景點密集擠在目的地端。
const SUB_SPOT_MERGE_RADIUS_M = 800;
const SUB_SPOT_NAME_MERGE_RADIUS_M = 2000;
const SUB_SPOT_SAME_SPOT_RADIUS_M = 300; // 不同名景點僅在此極近距離內才視為「同一處」而合併

// 計算一組名稱的最長共同前綴（作為「大景點」顯示名）
function subSpotCommonPrefix(names) {
  const list = (names || []).map(n => String(n || '')).filter(Boolean);
  if (!list.length) return '';
  let prefix = list[0];
  for (let i = 1; i < list.length; i++) {
    const cur = list[i];
    let j = 0;
    while (j < prefix.length && j < cur.length && prefix[j] === cur[j]) j++;
    prefix = prefix.slice(0, j);
    if (!prefix) break;
  }
  return prefix.trim();
}

// 是否為同一大景區的兄弟子景點（名稱共享主體）
function isSameAttractionFamily(a, b) {
  const aN = normalizeLookupText((a && a.name) || '');
  const bN = normalizeLookupText((b && b.name) || '');
  if (!aN || !bN) return false;
  const shorter = aN.length <= bN.length ? aN : bN;
  if (shorter.length >= 3 && (aN.includes(bN) || bN.includes(aN))) return true;
  // 正規化後共同前綴 ≥ 3 字（涵蓋「三仙台」案例，避免「台東」這類兩字地名誤判）
  if (subSpotCommonPrefix([aN, bN]).length >= 3) return true;
  return false;
}

// 是否為「餐廳／用餐站」：用餐站永不參與合併（既不被景點吸收、也不吸收景點），維持獨立用餐停留。
// AI 生成的景點無 type 欄位，故以 tag（社群範例）＋ emoji ＋ 名稱關鍵字判定。
const FOOD_EMOJI_SET = new Set(['🍜','🍱','☕','🍽️','🍽','🍦','🍢','🐟','🍲','🍛','🍔','🍕','🍻','🍸','🧋','🍵','🥟','🍤','🍧','🍨','🥘','🍰']);
const FOOD_NAME_RE = /餐廳|食堂|小吃|料理|美食|便當|海鮮|餐酒|甜點|冰淇淋|冰品|火鍋|燒烤|烘焙|早午餐|咖啡|茶館|茶屋|夜市|cafe|coffee|restaurant/i;
function isFoodStop(stop) {
  if (!stop) return false;
  if (stop.tag === 'food') return true;
  if (stop.emoji && FOOD_EMOJI_SET.has(String(stop.emoji).trim())) return true;
  const name = String(stop.name || stop.title || '');
  if (!name) return false;
  if (/飯店|飯館|麵店/.test(name)) return false; // 防誤判：飯店（住宿）等含「飯/麵」但非用餐站
  return FOOD_NAME_RE.test(name);
}

// 是否為「住宿站」：住宿永不參與合併——多日行程的住宿會在「前一天最後一站」與「隔天第一站」
// 各出現一次（同名同座標），若被同名家族規則併成一站，隔天「從住宿出發」就消失（E2E #4）。
const LODGING_NAME_RE = /民宿|飯店|旅館|旅宿|旅店|汽車旅館|客棧|青年旅|渡假村|度假村|hotel|hostel|motel|resort/i;
function isLodgingStop(stop) {
  if (!stop) return false;
  if (stop.type === 'lodging') return true;
  if (String(stop.emoji || '').trim() === '🏨') return true;
  return LODGING_NAME_RE.test(String(stop.name || stop.title || ''));
}

// 是否該歸入同一大景區（以距離為主）
function shouldClusterStops(a, b) {
  if (isFoodStop(a) || isFoodStop(b)) return false; // 餐廳閘門：用餐站不與他站合併
  if (isLodgingStop(a) || isLodgingStop(b)) return false; // 住宿閘門：住宿站不與他站合併（含同名的隔日住宿站）
  const ca = getStopCoordinate(a);
  const cb = getStopCoordinate(b);
  if (ca && cb) {
    const d = getDistanceMeters(ca, cb);
    // 只併「真正同一景區」：同名家族放寬到 2km；不同名僅在極近(同一入口/同一處)才併，
    // 避免市區密集但不同的景點(海濱公園/生命之樹…)被 800m 規則塌成一站、壓縮整日時數。
    if (isSameAttractionFamily(a, b)) return d <= SUB_SPOT_NAME_MERGE_RADIUS_M;
    return d <= SUB_SPOT_SAME_SPOT_RADIUS_M;
  }
  return isSameAttractionFamily(a, b); // 缺座標退回名稱判定
}

// 將多個兄弟子景點合併成一個大景點站
// 從一組名稱挑出「大景點名」：有共同前綴就用前綴，否則取「被最多其他成員名稱包含」者，同分取最短。
function pickClusterName(names) {
  const prefix = subSpotCommonPrefix(names);
  // 前綴 ≥ 3 字、去掉行政區後綴（市/縣/鄉…）後仍 ≥ 3 字、且前綴本身即某個實際成員名時才採用，
  // 避免「台東」「花蓮市」等城市名或「綠島小」這類截斷片段變成合併站名（應落在具體地標）。
  if (prefix && prefix.length >= 3
    && prefix.replace(/[市縣鄉鎮區村里]$/, '').length >= 3
    && names.some(n => String(n) === prefix)) return prefix;
  let best = '', bestScore = -1;
  for (const cand of names) {
    const cn = normalizeLookupText(cand);
    if (!cn) continue;
    const core = String(cand || '').trim().replace(/[市縣鄉鎮區村里]$/, '');
    if (core.length < 3) continue; // 跳過「台東」「花蓮市」等純地名，避免吃掉真實地標
    const score = names.reduce((acc, other) => acc + (normalizeLookupText(other).includes(cn) ? 1 : 0), 0);
    if (score > bestScore || (score === bestScore && (best === '' || cand.length < best.length))) {
      best = cand; bestScore = score;
    }
  }
  return best
    || names.find(n => String(n || '').trim().replace(/[市縣鄉鎮區村里]$/, '').length >= 3)
    || names[0] || '景點';
}

// 去掉描述中既有的「（含 A、B…）」括號，回傳乾淨 prose（「（含 …）」改由渲染時用 mergedSubSpots 生成）
function stripIncludedNote(desc) {
  return String(desc || '').replace(/[（(]\s*含\s*[^）)]*[）)]/g, '').replace(/\s+/g, ' ').trim();
}

function buildMergedAttraction(members) {
  const names = members.map(m => String(m.name || '')).filter(Boolean);
  const mergedName = pickClusterName(names);
  // 母景點：優先名稱等於大景點名者，否則名稱最短者（承接基底欄位）
  const parent = members.find(m => String(m.name || '') === mergedName)
    || [...members].sort((a, b) => String(a.name || '').length - String(b.name || '').length)[0];
  const subLabels = names
    .filter(n => n !== mergedName)
    .map(n => (n.startsWith(mergedName) ? n.slice(mergedName.length) : n))
    .map(n => n.replace(/^[\s·、,，\-]+/, '').trim())
    .filter(Boolean);
  // 合併後停留時間 = 取最長子景點停留 + 緩衝（上限 90 分，不灌水）；縮掉的時間由「補景點」補回
  const maxStay = members.reduce((mx, m) => Math.max(mx, Number(m.duration) || Number(m.stayMin) || 0), 0);
  const mergedStay = Math.min(120, (maxStay || 30) + 30 * (members.length - 1));
  // 入口/代表座標：優先名稱等於合併名者（大景點本體），否則用所有成員座標的質心
  const memberCoords = members.map(getStopCoordinate).filter(Boolean);
  const namedCoord = getStopCoordinate(members.find(m => String(m.name || '') === mergedName && getStopCoordinate(m)));
  let coord = namedCoord;
  if (!coord && memberCoords.length) {
    coord = {
      lat: memberCoords.reduce((s, c) => s + c.lat, 0) / memberCoords.length,
      lng: memberCoords.reduce((s, c) => s + c.lng, 0) / memberCoords.length
    };
  }
  let mergedRadiusMeters = 0;
  if (coord && memberCoords.length) {
    const maxDist = memberCoords.reduce((mx, c) => Math.max(mx, getDistanceMeters(coord, c)), 0);
    mergedRadiusMeters = Math.min(2500, Math.max(150, Math.round(maxDist + 80)));
  }
  // 描述只保留乾淨 prose（去掉成員描述中既有的「（含 …）」），不把括號接進 desc（改由渲染時用 mergedSubSpots 生成）
  const rawBaseDesc = String(parent.desc || (members.find(m => m.desc) || {}).desc || '').trim();
  const baseDesc = stripIncludedNote(rawBaseDesc);
  const merged = {
    ...parent,
    name: mergedName,
    emoji: members.map(m => m.emoji).find(Boolean) || '📍',
    desc: baseDesc,
    duration: mergedStay,
    businessHours: members.map(m => m.businessHours).find(Boolean) || null,
    isMergedAttraction: true,
    mergedSubSpots: subLabels,
    mergedRadiusMeters,
    mergedMemberCoords: memberCoords
  };
  if (coord) { merged.lat = coord.lat; merged.lng = coord.lng; }
  return merged;
}

// 對站點做貪婪群集（單一連結）：同一大景區（距離為主）者併成一個大景點；跳過 start/end 錨點站
function mergeNearbySubAttractions(stops) {
  if (!Array.isArray(stops) || stops.length < 2) return stops;
  const assigned = new Array(stops.length).fill(false);
  const result = [];
  for (let i = 0; i < stops.length; i++) {
    if (assigned[i]) continue;
    const base = stops[i];
    assigned[i] = true;
    if (base && (base.type === 'start' || base.type === 'end')) { result.push(base); continue; }
    const members = [base];
    for (let j = i + 1; j < stops.length; j++) {
      if (assigned[j]) continue;
      const cand = stops[j];
      if (cand && (cand.type === 'start' || cand.type === 'end')) continue;
      if (members.some(m => shouldClusterStops(m, cand))) { members.push(cand); assigned[j] = true; }
    }
    result.push(members.length === 1 ? base : buildMergedAttraction(members));
  }
  return result;
}

// === 單站大景區補子景點：合併不到鄰近站時，用 Google Places 附近搜尋補出子景點並標記為合併站 ===
const BIG_AREA_KEYWORDS = ['潭', '步道', '大道', '園區', '國家風景區', '瀑布', '山', '岬', '灣', '古道', '部落', '濕地', '牧場'];
const _nearbySubSpotCache = new Map();

// 用新版 Places REST「附近搜尋」取回某座標周邊的景點類 POI（沿用 verifyAndFilterStopsWithPlaces 的金鑰/標頭模式）
async function fetchNearbySubSpots(coord, radius) {
  const key = window.TRAVEL_APP_CONFIG && window.TRAVEL_APP_CONFIG.GOOGLE_MAPS_API_KEY;
  if (!key || !coord) return [];
  const cacheKey = `${coord.lat.toFixed(4)},${coord.lng.toFixed(4)}@${radius}`;
  if (_nearbySubSpotCache.has(cacheKey)) return _nearbySubSpotCache.get(cacheKey);
  let out = [];
  try {
    const res = await placesFetch('searchNearby', 'places.displayName,places.location,places.types', {
      languageCode: 'zh-TW',
      maxResultCount: 10,
      rankPreference: 'DISTANCE',
      includedTypes: ['tourist_attraction', 'park'],
      locationRestriction: { circle: { center: { latitude: coord.lat, longitude: coord.lng }, radius } }
    }, 10000, '附近景點', key);
    const data = await res.json();
    out = (Array.isArray(data.places) ? data.places : [])
      .map(p => ({
        name: ((p.displayName && p.displayName.text) || '').trim(),
        lat: p.location && p.location.latitude,
        lng: p.location && p.location.longitude
      }))
      .filter(p => p.name && Number.isFinite(p.lat) && Number.isFinite(p.lng));
  } catch (e) { out = []; }
  _nearbySubSpotCache.set(cacheKey, out);
  return out;
}

// 對「合併不到、仍是單站」的大景區站，補出鄰近子景點並標記為合併站（不新增路線站、不改 duration）
// 從成員名＋附近POI名找「主導分支共同前綴」當大景點名（如三仙台）：
// 長度≥3、被≥2名稱共享、其後一字在這些名稱間有差異（真分支點）或前綴本身即一個POI，且來源至少有一個附近POI。
function deriveBigAreaName(memberNames, nearbyNames) {
  const members = (memberNames || []).map(n => String(n || '').trim()).filter(Boolean);
  const nears = (nearbyNames || []).map(n => String(n || '').trim()).filter(Boolean);
  const all = [...members, ...nears];
  if (all.length < 2) return '';
  const prefixGroups = new Map();
  for (let i = 0; i < all.length; i++) {
    for (let j = i + 1; j < all.length; j++) {
      const p = subSpotCommonPrefix([all[i], all[j]]);
      if (p.length < 3) continue;
      if (p.replace(/[市縣鄉鎮區村里]$/, '').length < 3) continue; // 過濾「台東市」「花蓮縣」等行政區名
      if (!prefixGroups.has(p)) prefixGroups.set(p, new Set());
      prefixGroups.get(p).add(all[i]); prefixGroups.get(p).add(all[j]);
    }
  }
  let best = '', bestCount = 0;
  for (const [p, set] of prefixGroups) {
    const names = [...set];
    if (names.length < 2) continue;
    const exactIsPlace = all.some(n => n === p);
    // 只採用「前綴本身即一個真實 POI（成員或附近景點）」的名稱，避免取到截斷片段
    // （如「綠島小長城」「綠島小夜市」→ 前綴「綠島小」並非真實地點）。三仙台等本身是 POI 者不受影響。
    if (!exactIsPlace) continue;
    if (!names.some(n => nears.includes(n))) continue;      // 需有附近POI佐證
    if (names.length > bestCount || (names.length === bestCount && (best === '' || p.length < best.length))) {
      best = p; bestCount = names.length;
    }
  }
  return best;
}

// 補子景點 + 大景點名解析：對每個非端點站（含已合併站），用 Places 附近搜尋補子景點並把顯示名換成大景點名
async function enrichBigAttractionSubSpots(stops, destination) {
  if (!Array.isArray(stops) || !stops.length) return stops;
  const key = window.TRAVEL_APP_CONFIG && window.TRAVEL_APP_CONFIG.GOOGLE_MAPS_API_KEY;
  if (!key) return stops;
  const otherNorms = new Set(stops.map(s => normalizeLookupText(s && s.name)).filter(Boolean));
  // 閘門條件抽成共用：預抓與決策迴圈用同一組，避免多抓被跳過站的 nearby 浪費呼叫
  const _passesGate = (stop) => {
    if (!stop || stop.type === 'start' || stop.type === 'end') return false;
    if (isFoodStop(stop)) return false; // 餐廳閘門：用餐站不被標為合併大景點、不補子景點
    if (isLodgingStop(stop)) return false; // 住宿閘門：住宿站不被標為合併大景點、不補子景點（E2E #4）
    if (/火車站|車站|捷運|高鐵|轉運站|客運站|機場|航空站/.test(String(stop.name || ''))) return false; // 交通樞紐閘門
    return !!getStopCoordinate(stop);
  };
  // 平行預抓所有站的附近 POI（原本迴圈內逐站 await 串行，每站一個 RTT 疊加）；
  // 決策迴圈讀寫共享的 otherNorms／跨站查重，必須維持串行——只平行化網路段。
  const _nearbyByStop = new Map();
  await Promise.allSettled(stops.filter(_passesGate).map(async (stop) => {
    const coord = getStopCoordinate(stop);
    try { _nearbyByStop.set(stop, await fetchNearbySubSpots(coord, SUB_SPOT_MERGE_RADIUS_M)); }
    catch (_e) { /* 失敗＝該站沒有 nearby，決策迴圈自然跳過 */ }
  }));
  for (const stop of stops) {
    if (!_passesGate(stop)) continue;
    const coord = getStopCoordinate(stop);
    const parentName = String(stop.name || '');
    const parentNorm = normalizeLookupText(parentName);
    const nearby = _nearbyByStop.get(stop);
    if (!nearby || !nearby.length) continue;
    const nearbyNames = nearby.map(n => n.name);
    const nearbyNormSet = new Set(nearby.map(n => normalizeLookupText(n.name)).filter(Boolean));

    // 800m 內、距代表座標 60–800m 的近景點候選（地理）
    const cands = [];
    for (const p of nearby) {
      const pn = normalizeLookupText(p.name);
      if (!pn || pn === parentNorm) continue;
      if (otherNorms.has(pn)) continue;
      if (isFoodStop({ name: p.name })) continue; // 餐廳閘門：附近餐廳 POI 不被拉進景點子景點
      const dist = getDistanceMeters(coord, { lat: p.lat, lng: p.lng });
      if (dist < 60 || dist > SUB_SPOT_MERGE_RADIUS_M) continue;
      cands.push({ name: p.name, lat: p.lat, lng: p.lng, dist });
    }
    cands.sort((a, b) => a.dist - b.dist);
    const picked = cands.slice(0, 6);
    const hasPrefixChild = picked.some(c => subSpotCommonPrefix([parentNorm, normalizeLookupText(c.name)]).length >= 3);
    const isBigKeyword = BIG_AREA_KEYWORDS.some(k => parentName.includes(k));

    let subs;

    if (stop.isMergedAttraction) {
      // (A1) 既有合併站 → 地理再驗證：只留「800m 內附近 POI 有此名」或「與大景點名共享 ≥3 字前綴」者，丟掉遠景點；再聯集近景點
      const existing = Array.isArray(stop.mergedSubSpots) ? stop.mergedSubSpots : [];
      const kept = existing.filter(n => {
        const nn = normalizeLookupText(n);
        if (!nn) return false;
        return nearbyNormSet.has(nn) || subSpotCommonPrefix([parentNorm, nn]).length >= 3;
      });
      subs = [...kept, ...picked.map(c => c.name)];
    } else if (picked.length >= 2 && (hasPrefixChild || isBigKeyword)) {
      // (A2) 未合併單站 → 以近景點補子景點並標記合併（閘門避免誤標餐廳/漁港）
      const maxDist = picked.reduce((mx, c) => Math.max(mx, c.dist), 0);
      stop.isMergedAttraction = true;
      stop.mergedMemberCoords = [coord, ...picked.map(c => ({ lat: c.lat, lng: c.lng }))];
      stop.mergedRadiusMeters = Math.min(2500, Math.max(150, Math.round(maxDist + 80)));
      subs = picked.map(c => c.name);
      picked.forEach(c => otherNorms.add(normalizeLookupText(c.name)));
    } else {
      subs = Array.isArray(stop.mergedSubSpots) ? stop.mergedSubSpots.slice() : [];
    }

    // (B) 大景點名解析：把顯示名換成大景區名（如礫石灘＋觀景台 → 三仙台），舊名收進子景點
    const bigName = deriveBigAreaName([parentName, ...subs], nearbyNames);
    const bigNorm = normalizeLookupText(bigName);
    const usedByOthers = stops.some(x => x !== stop && normalizeLookupText(x.name) === bigNorm);
    if (bigName && bigNorm !== parentNorm && !usedByOthers) {
      stop.name = bigName;
      subs = [parentName, ...subs];
      stop.isMergedAttraction = true;
      const hit = nearby.find(n => normalizeLookupText(n.name) === bigNorm);
      if (hit) { stop.lat = hit.lat; stop.lng = hit.lng; }
      otherNorms.add(bigNorm);
    }

    // 合併站的代表座標應落在它的大地標上（如卑南遺址→卑南遺址公園），而非沿用 AI 給的錯位座標。
    // 用 Places 以「站名」查大地標（fetchCoordinateFromGooglePlaces 內含嚴格名稱比對），在範圍內且偏移 >300m 才校正。
    if (stop.isMergedAttraction) {
      try {
        const lm = await fetchCoordinateFromGooglePlaces(String(stop.name || ''), destination);
        if (lm && Number.isFinite(Number(lm.lat)) && Number.isFinite(Number(lm.lng))) {
          const destCenter = getDestinationCenter(destination);
          const maxDist = getDestinationMaxDistanceMeters(destination) || 55000;
          const inRange = !destCenter || getDistanceMeters(destCenter, lm) <= maxDist;
          const curC = getStopCoordinate(stop);
          if (inRange && (!curC || getDistanceMeters(curC, lm) > 300)) {
            stop.lat = lm.lat;
            stop.lng = lm.lng;
            stop.coordinateSource = 'merged_landmark';
          }
        }
      } catch (e) { /* Places 不可用 → 維持原座標 */ }
    }

    // 去重、排除大景點名本身，回寫 mergedSubSpots（desc 維持乾淨 prose，不嵌入「（含 …）」）
    if (stop.isMergedAttraction || (subs && subs.length)) {
      const bigN = normalizeLookupText(stop.name);
      const seen = new Set();
      const finalSubs = [];
      for (const n of (subs || [])) {
        const nn = normalizeLookupText(n);
        if (!nn || nn === bigN || seen.has(nn)) continue;
        seen.add(nn); finalSubs.push(String(n).trim());
      }
      stop.mergedSubSpots = finalSubs;
      if (finalSubs.length) stop.isMergedAttraction = true;
      // 補了子景點 → 大景點實際遊覽時間變長，依子景點數加長建議停留（只加長、不縮短）。
      // target 以固定基準算（非當前值），確保重開重跑時冪等、不會逐次累加。
      if (finalSubs.length) {
        const cur = Number(stop.duration) || Number(stop.stayMin) || 30;
        const target = Math.min(120, 30 + 20 * finalSubs.length);
        const bumped = Math.max(cur, target);
        stop.duration = bumped;
        stop.stayMin = bumped;
      }
    }
  }
  return stops;
}

// === 合併後時間預算回填工具 ===
// 單段交通估算（距離為主）：≤800m 走路 10 分；否則車程 ≈ 距離km/40*60，最低 5 分；缺座標退回 15 分
function estimateLegMinutes(a, b) {
  const ca = getStopCoordinate(a), cb = getStopCoordinate(b);
  if (!ca || !cb) return 15;
  const m = getDistanceMeters(ca, cb);
  if (m <= 800) return 10;
  return Math.max(5, Math.round((m / 1000) / 40 * 60));
}
// 完整站序（含起點…中段…終點）預估總分鐘 = Σ停留 + Σ路段交通（含最後一站→終點回程）
function estimateTripMinutes(stops) {
  if (!Array.isArray(stops) || !stops.length) return 0;
  let total = 0;
  for (let i = 0; i < stops.length; i++) {
    const s = stops[i];
    const isEndpoint = s && (s.type === 'start' || s.type === 'end');
    if (!isEndpoint) total += Math.max(0, Number(s.duration) || Number(s.stayMin) || 0);
    if (i < stops.length - 1) total += estimateLegMinutes(s, stops[i + 1]);
  }
  return total;
}

// 建立行程時：若總時長超出設定時長，把要縮短的時間「平均分攤」到各景點（餐廳/用餐站例外，不扣），
// 每站最多只縮原本的 35%（保底 65%），以水位填平方式反覆均攤直到符合或已無可縮空間。回傳是否有調整。
function fitGeneratedStopsToTimeLimit(stops, wizardData = {}) {
  if (!Array.isArray(stops) || !stops.length) return false;
  const targetMin = getTripActiveMinutes(wizardData);
  if (!Number.isFinite(targetMin) || targetMin <= 0) return false;
  if (estimateTripMinutes(stops) <= targetMin) return false;

  const MIN_RATIO = 0.65; // 每站最多縮 35%
  const stayOf = (s) => Math.max(0, Math.round(Number(s.duration) || Number(s.stayMin) || 0));
  const isEndpoint = (s) => s && (s.type === 'start' || s.type === 'end');
  const info = stops.map((s) => {
    const eligible = !isEndpoint(s) && !isFoodStop(s); // 端點與餐廳例外
    const orig = stayOf(s);
    return { stop: s, eligible, orig, floor: Math.ceil(orig * MIN_RATIO) };
  });

  let changed = false;
  let guard = 0;
  while (guard++ < 4000) {
    const overflow = estimateTripMinutes(stops) - targetMin;
    if (overflow <= 0) break;
    const cands = info.filter(t => t.eligible && stayOf(t.stop) > t.floor);
    if (!cands.length) break; // 已無可縮，盡力而為
    // 平均分攤：本輪每站各扣約 overflow/N（至少 1 分），不超過各自剩餘可縮空間；碰到下限者由其餘站再均攤
    const share = Math.max(1, Math.floor(overflow / cands.length));
    let applied = 0;
    for (const t of cands) {
      const remain = overflow - applied;
      if (remain <= 0) break;
      const cur = stayOf(t.stop);
      const room = cur - t.floor;
      if (room <= 0) continue;
      const cut = Math.min(room, share, remain);
      if (cut <= 0) continue;
      const nv = cur - cut;
      t.stop.duration = nv;
      t.stop.stayMin = nv;
      applied += cut;
      changed = true;
    }
    if (applied <= 0) break;
  }

  // 殘量收尾：守 35% 後若仍超出，再從「停留最久」的景點多扣一點。
  //
  // ⚠ 這裡原本的下限是 5 分鐘，結果是把時間硬塞進固定站數——實測 8 小時排 7 站時，
  //   poi-data 建議停留 40 分的卑南大圳水利公園被壓到 12 分。開 2.2 公里去待 12 分鐘
  //   不是一個「站」，使用者看到的是一份每站都來不及看的行程。
  //
  //   改成：下限提高到「還算得上有去過」的 MEANINGFUL_MIN，但不超過該站原本的建議時長
  //   （資料本來就說 15 分鐘的小景點，不該被硬拉到 20）。扣到下限仍然放不下時，
  //   寧可整站拿掉，也不要讓每一站都變成打卡點——少一站的行程比七站走馬看花好。
  const MEANINGFUL_MIN = 20;
  const floorOf = (t) => Math.min(MEANINGFUL_MIN, t.orig || MEANINGFUL_MIN);
  guard = 0;
  while (guard++ < 4000) {
    const overflow = estimateTripMinutes(stops) - targetMin;
    if (overflow <= 0) break;
    const cands = info.filter(t => t.eligible && stayOf(t.stop) > floorOf(t));
    if (cands.length) {
      cands.sort((a, b) => stayOf(b.stop) - stayOf(a.stop));
      const t = cands[0];
      const cur = stayOf(t.stop);
      const cut = Math.min(cur - floorOf(t), overflow);
      if (cut > 0) {
        const nv = cur - cut;
        t.stop.duration = nv;
        t.stop.stayMin = nv;
        changed = true;
        continue;
      }
    }
    // 全部都到下限了還是超時 → 拿掉一站（評分最低的；沒有評分就拿最後一個非端點站）
    const removable = info.filter((t) => t.eligible && stops.indexOf(t.stop) >= 0);
    if (removable.length <= 1) break;   // 至少保留一個真正的景點
    removable.sort((a, b) => {
      const ra = Number(a.stop.rating) || 0, rb = Number(b.stop.rating) || 0;
      if (ra !== rb) return ra - rb;
      return stops.indexOf(b.stop) - stops.indexOf(a.stop);
    });
    const drop = stops.indexOf(removable[0].stop);
    if (drop < 0) break;
    stops.splice(drop, 1);
    changed = true;
  }
  return changed;
}

// 合併後時間偏短時：沿路線補景點填滿剩餘時段（不要集中同一點）
function buildTimeFillPrompt(dest, needed, shortfallMin, excludedNames, wizardData = {}) {
  const interests = (wizardData.interests || []).join('、') || '多元體驗';
  const theme = wizardData.theme || '經典旅人';
  const startLoc = (wizardData.startLocation || '').trim();
  const endLoc = (wizardData.endLocation || '').trim();
  const excluded = (excludedNames || []).slice(0, 40).join('、');
  const _avoid = getEffectivePrefs(wizardData).avoid;
  // 只能從本地端：補景點也只從本地清單挑（排除已用），本地無資料才退回 Google Maps 描述。
  const _norm = (s) => String(s || '').replace(/\s/g, '').replace(/臺/g, '台');
  const _excludeSet = new Set((excludedNames || []).map(_norm));
  const _localPool = (typeof getLocalPoiList === 'function' ? getLocalPoiList(dest) : [])
    .filter((p) => p && p.name && !_excludeSet.has(_norm(p.name)));
  const _localBlock = _localPool.length
    ? `景點「只能」從以下本地清單挑選未使用者（名稱需完全一致，嚴禁清單以外的任何景點）：\n${_localPool.slice(0, 40).map((p) => `・${p.name}`).join('\n')}`
    : `景點須為 ${dest} 周邊真實存在、能在 Google Maps 搜尋到的正式名稱`;
  return [
    `你是台灣微旅行規劃 AI。目前 ${dest} 行程時間偏短，請沿行程路線補 ${needed + 3} 個景點（多補 3 個備用，供座標驗證淘汰後仍夠用），用來填滿約 ${shortfallMin} 分鐘的空檔。`,
    _avoid ? `⚠️ 個人禁忌／需避免（務必遵守）：${_avoid}` : null,
    `已使用景點（絕對禁止重複）：${excluded || '（無）'}`,
    (startLoc || endLoc)
      ? `景點請沿「${startLoc || dest}」→「${dest}」→「${endLoc || dest}」路線廊道分散（距路線 10 公里內），不要全部集中在同一點`
      : '景點沿行程路線分散，不要集中在同一點',
    _localBlock,
    `duration 為停留分鐘（15–90）；風格 ${theme}、興趣 ${interests}`,
    '請勿輸出 lat/lng（座標由系統查詢）。只回傳 JSON：{"stops":[{"name":"景點正式名稱","emoji":"📍","duration":45,"desc":"推薦理由","businessHours":"週一至週日 09:00-17:00"}]}'
  ].filter(Boolean).join('\n');
}

// 合併後若行程縮水超過 45 分，沿路線補景點填回目標時段（含回終點交通、不超時）。傳入/回傳「中段站」。
async function fillTripTimeBudget(middleStops, wizardData, destination, startCoords, endCoords) {
  const targetMin = getTripActiveMinutes(wizardData);
  const startStub = startCoords ? { type: 'start', lat: startCoords.lat, lng: startCoords.lng } : null;
  const endStub = endCoords ? { type: 'end', lat: endCoords.lat, lng: endCoords.lng } : null;
  const fullSeq = (arr) => [startStub, ...arr, endStub].filter(Boolean);
  let stops = [...middleStops];
  const usedNorm = new Set(stops.map(s => normalizeLookupText(s.name)));
  // 由 3 輪縮為 1 輪：每輪＝1 次 Gemini＋2 波 Places，3 輪串行是生成尾端最大延遲；
  // 改以「單輪多要 3 個備用候選」補足命中率（buildTimeFillPrompt 的 needed+3）。
  for (let iter = 0; iter < 1; iter++) {
    const shortfall = targetMin - estimateTripMinutes(fullSeq(stops));
    if (shortfall <= 45) break;
    const need = Math.max(1, Math.min(4, Math.ceil(shortfall / 50)));
    let text;
    try {
      const payload = {
        contents: [{ role: 'user', parts: [{ text: buildTimeFillPrompt(destination, need, shortfall, stops.map(s => s.name), wizardData) }] }],
        generationConfig: buildGenConfig({ temperature: 0.9, maxOutputTokens: 2048, thinking: 0 })
      };
      text = extractGeminiResponseText(await fetchGeminiJson({ apiKey: '', model: GEMINI_MODEL, payload }));
    } catch (e) { break; }
    let parsed; try { parsed = parsePlanJsonFromText(text); } catch (e) { break; }
    let cands = (parsed && Array.isArray(parsed.stops))
      ? parsed.stops.filter(s => s && s.name && !usedNorm.has(normalizeLookupText(s.name)))
        .map(s => ({ name: s.name, emoji: s.emoji || '📍', desc: s.desc || '', duration: Math.max(15, Math.min(90, Number(s.duration) || 45)), businessHours: s.businessHours || null, dayIndex: Number(s.dayIndex) || undefined }))
      : [];
    if (!cands.length) break;
    cands = await enrichMissingCoordinates(cands, destination);
    cands = await verifyAndFilterStopsWithPlaces(cands, destination, wizardData);
    let addedAny = false;
    for (const c of cands) {
      const nrm = normalizeLookupText(c.name);
      if (usedNorm.has(nrm)) continue;
      stops.push(c);
      usedNorm.add(nrm);
      let after = estimateTripMinutes(fullSeq(stops));
      if (after > targetMin) {
        const over = after - (targetMin);
        if ((Number(c.duration) || 45) - over >= 15) { c.duration = (Number(c.duration) || 45) - over; after = estimateTripMinutes(fullSeq(stops)); }
        if (after > targetMin) { stops.pop(); continue; }
      }
      addedAny = true;
      if (estimateTripMinutes(fullSeq(stops)) >= targetMin - 15) break;
    }
    stops = mergeNearbySubAttractions(stops);
    if (!addedAny) break;
  }
  return stops;
}

async function optimizeGeneratedTripStops(stops, wizardData = {}, livePlaces = []) {
  const sourceStops = Array.isArray(stops) ? stops.filter(Boolean) : [];
  if (!sourceStops.length) return [];
  // When Places API POIs are available, match AI-selected names back to those objects
  // to assign authoritative coordinates before any further processing.
  const preMatchedStops = livePlaces.length > 0
    ? matchStopsToLivePlaces(sourceStops, livePlaces)
    : sourceStops;
  const destination = wizardData.dest || wizardData.destCustom || wizardData.region || '';
  // step2「對應地圖」涵蓋多個子階段，逐段更新 subLabel 讓等待有敘事（僅生成流程中顯示）
  const _sub = (label) => { if (isGeneratingTrip && typeof setWizGenStep === 'function') setWizGenStep(2, label); };
  _sub('讀取景點快取中…');
  const poiCache = await fetchDestinationPoiCache(destination);
  _genPerf.mark('opt: poiCache 讀取');

  // Fetch start/end coords early so they are available for corridor filtering below
  const _defaultHub   = getDefaultTransitHub(destination);
  const _userStartLoc = (wizardData.startLocation || '').trim();
  const _userEndLoc   = (wizardData.endLocation   || '').trim();
  const _startLocCalc = _userStartLoc || _defaultHub;
  const _endLocCalc   = _userEndLoc   || _defaultHub;
  // 離島且使用者未自填起/終點：直接採用 ferry-config 的島內港座標，避免「南寮漁港」等同名港
  // 被 geocode 到本島（如新竹也有南寮漁港）。使用者自填時仍走正常 Places 解析。
  const _ferryCfg = (typeof window !== 'undefined' && window.WAI_FERRY_CONFIG) ? window.WAI_FERRY_CONFIG[destination] : null;
  const _islandHarborCoord = (_ferryCfg && _ferryCfg.islandHarbor
    && Number.isFinite(Number(_ferryCfg.islandHarbor.lat)) && Number.isFinite(Number(_ferryCfg.islandHarbor.lng)))
    ? { lat: Number(_ferryCfg.islandHarbor.lat), lng: Number(_ferryCfg.islandHarbor.lng), source: 'ferry_config' }
    : null;
  const _resolveStart = (!_userStartLoc && _islandHarborCoord)
    ? Promise.resolve(_islandHarborCoord)
    : fetchCoordinateFromGooglePlaces(_startLocCalc, destination).catch(() => null);
  const _resolveEnd = (_startLocCalc === _endLocCalc)
    ? Promise.resolve(null)
    : ((!_userEndLoc && _islandHarborCoord)
        ? Promise.resolve(_islandHarborCoord)
        : fetchCoordinateFromGooglePlaces(_endLocCalc, destination).catch(() => null));
  const [_startCoords, _rawEndCoords] = await Promise.all([_resolveStart, _resolveEnd]);
  const _endCoords = _startLocCalc === _endLocCalc ? _startCoords : _rawEndCoords;
  _genPerf.mark('opt: 起訖點解析');

  const sanitizedStops = sanitizeStopCoordinates(preMatchedStops, wizardData, poiCache);
  _sub('補齊景點座標中…');
  const enrichedStops = await enrichMissingCoordinates(sanitizedStops, destination);
  _genPerf.mark('opt: 缺座標補齊');

  // Corridor filter: try 5km first; if too few stops, expand to 10km
  let stopsForVerify = enrichedStops;
  if (_startCoords && _endCoords) {
    const _destCoord = getDestinationCenter(destination) || _startCoords;
    const _minStops = getDurationStopRange(wizardData.days || '1天', wizardData.people || '2人').min;
    const _corridorFilter = (km) => enrichedStops.filter(s => {
      const c = getStopCoordinate(s);
      return !c || isWithinCorridorKm(c, _startCoords, _destCoord, _endCoords, km);
    });
    const _narrow = _corridorFilter(5);
    if (_narrow.length >= _minStops) {
      stopsForVerify = _narrow;
    } else {
      const _wide = _corridorFilter(10);
      if (_wide.length >= 2) stopsForVerify = _wide;
    }
  }

  _sub('驗證景點真實性中…');
  const verifiedStops = await verifyAndFilterStopsWithPlaces(stopsForVerify, destination, wizardData);
  _genPerf.mark('opt: Places 逐站驗證');
  const geographicallySorted = sortStopsByNearestRoute(verifiedStops, wizardData);
  // 合併同一大景區內密集子景點（如三仙台觀景台／跨海步橋 → 單一「三仙台」站）
  const mergedNearby = mergeNearbySubAttractions(geographicallySorted);
  // 大景區以單站進來、合併不到鄰近站時，用 Places 附近搜尋補出子景點並標記合併（只貼標籤，不加站）
  _sub('比對大景區子景點中…');
  const enrichedNearby = await enrichBigAttractionSubSpots(mergedNearby, destination).catch(() => mergedNearby);
  _genPerf.mark('opt: 大景區子景點');
  // 合併後若時間縮水，沿路線補景點填回目標時段（含回終點交通、不超時）
  _sub('檢查行程時段長度中…');
  const filledStops = await fillTripTimeBudget(enrichedNearby, wizardData, destination, _startCoords, _endCoords).catch(() => enrichedNearby);
  _genPerf.mark('opt: 補時段(fillTripTimeBudget)');
  _sub('整理路線順序中…');
  // 最後輸出前再重排一次，避免合併/補景點後路線南北來回跑
  const orderedStops = reorderStopsAlongRoute(filledStops, _startCoords, wizardData, _endCoords);
  // 上面兩次排序只看路線順不順，會把晚餐排到第 3 站；這裡把用餐站移回時鐘走到那一餐的位置
  const mealArrangedStops = placeMealStopsAtMealTimes(orderedStops, wizardData);
  const ruledStops = applyTripPlanningRules(mealArrangedStops, wizardData);
  const withTransport = assignTransportModes(ruledStops, wizardData.transportMode);

  const enrichedWizardData = {
    ...wizardData,
    startLocation: _userStartLoc || _defaultHub,
    endLocation:   _userEndLoc   || _defaultHub,
    startCoords:   _startCoords  || null,
    endCoords:     _endCoords    || null,
  };
  const withEndpoints = injectEndpointStops(withTransport, enrichedWizardData);
  const finalStops = assignTransportModes(withEndpoints, enrichedWizardData.transportMode);
  // 建立行程：若仍超出設定時長，平均分攤縮短各景點停留（餐廳例外、每站最多縮 35%）
  fitGeneratedStopsToTimeLimit(finalStops, wizardData);
  // F5 多日：把 AI 輸出的 dayIndex 重新掛回最終站點（中間各步驟可能建新物件或增刪站）。
  // 名稱命中 → 用 AI 給的 dayIndex；未命中（合併/補站）→ 沿用前一站的天次；end 站＝最後一天。
  if (isLongTrip(wizardData.days)) {
    const dayCount = getWizardDayCount(wizardData.days);
    const _dnorm = (s) => String(s || '').replace(/\s/g, '').replace(/臺/g, '台').toLowerCase();
    const dayByName = new Map();
    sourceStops.forEach((s) => {
      const di = Math.max(1, Math.min(dayCount, Math.round(Number(s && s.dayIndex)) || 0));
      if (s && s.name && Number(s.dayIndex) >= 1) dayByName.set(_dnorm(s.name), di);
    });
    let cursor = 1;
    finalStops.forEach((s) => {
      if (!s) return;
      if (s.type === 'start') { s.dayIndex = 1; cursor = 1; return; }
      if (s.type === 'end') { s.dayIndex = dayCount; return; }
      const hit = dayByName.get(_dnorm(s.name));
      const existing = Math.round(Number(s.dayIndex)) || 0;
      let di = hit || existing || cursor;
      di = Math.max(cursor, Math.min(dayCount, Math.max(1, di))); // 不可倒退、不可超過天數
      s.dayIndex = di;
      cursor = di;
    });
    // 缺日防護：AI 常回「1,1,3,3,3」缺第 2 天，cursor 邏輯防倒退卻補不了缺日 →
    // 中段站的相異日若沒連續覆蓋 1..dayCount，改用比例切分（避免 planner 時間軸連續跨夜爆表）。
    const _mid = finalStops.filter((s) => s && s.type !== 'start' && s.type !== 'end');
    const _distinct = [...new Set(_mid.map((s) => s.dayIndex))].sort((a, b) => a - b);
    const _valid = _mid.length > 0 && _distinct.length === dayCount && _distinct.every((d, i) => d === i + 1);
    if (!_valid && _mid.length) {
      _mid.forEach((s, i) => { s.dayIndex = Math.max(1, Math.min(dayCount, Math.floor(i / _mid.length * dayCount) + 1)); });
    }
  }
  // 公休最終再檢查：前面的 Places 驗證時，補時候選／被重建的站可能還沒有 dayIndex，
  // 公休會退回用第 1 天判斷；等 dayIndex 在上方正規化完成後，依「該站所屬日」再檢查一次。
  //
  // ★ 這裡刻意「只標記、不刪站」：本函式已跑完缺日防護、applyTripPlanningRules（時間已寫進
  //   stop.time）與 fitGeneratedStopsToTimeLimit。在最末端刪站會推翻這三道保證——實測會造成
  //   「某一天變成空白」與「後續站點時間留下空檔」。標記讓使用者自己決定要不要換，風險最低。
  //
  // 這裡不寫任何旗標，只統計後記一筆 log：stop.businessHours 本身會被存進 Firestore，
  // planner 載入後用同一份 business-hours.js 即時判斷並在行程卡標 🔴、在重排畫面標 ⚠️。
  // 再存一份 scheduleWarning 只會變成對不上的第二份真相（且它根本沒進序列化白名單）。
  let closedCount = 0;
  finalStops.forEach((s) => {
    if (!s || s.type === 'start' || s.type === 'end' || !s.businessHours) return;
    if (typeof isLodgingStop === 'function' && isLodgingStop(s)) return;
    const win = extractDayHoursWindow(s.businessHours, stopServiceDate(s, wizardData));
    if (win && win.closed) closedCount++;
  });
  if (closedCount) console.info('[公休檢查] 本趟有', closedCount, '站在所屬日公休（保留站點，由 planner 顯示提示）');

  // ── 暫停開放的景點：末端再擋一次 ─────────────────────────────
  // getLocalPoiList 已經把它們濾掉了，AI 照理看不到；但 AI 有可能自己掰出清單外的
  // 名稱，或 Places 驗證階段補回來的站帶著「目前暫停開放」的營業時間。
  //
  // ★ 這裡「替換」而不是「刪除」。末端刪站會推翻前面的缺日防護與時間排程，
  //   實測會造成「某一天變成空白」與「後續站點留下時間空檔」——那正是先前修掉的 bug。
  //   找不到替代時就保留原站，由 planner 的行程卡顯示提示，讓使用者自己決定。
  const H = (typeof WAI_HOURS !== 'undefined' && WAI_HOURS) ? WAI_HOURS : null;
  if (H && typeof H.classifyAccess === 'function') {
    const destKey = (wizardData && (wizardData.dest || wizardData.destination)) || '';
    // 候選已排除 suspended 與非景點（getLocalPoiList 內建過濾）
    const pool = destKey ? getLocalPoiList(destKey) : [];
    const used = new Set(finalStops.map((s) => String((s && s.name) || '').trim()));
    let swapped = 0, kept = 0;
    finalStops.forEach((s, i) => {
      if (!s || s.type === 'start' || s.type === 'end') return;
      if (!H.classifyAccess(s).avoid) return;
      // 找地理上最近、還沒用過的替代景點
      let best = null, bestD = Infinity;
      const sLat = Number(s.lat), sLng = Number(s.lng);
      pool.forEach((c) => {
        const nm = String(c.name || '').trim();
        if (!nm || used.has(nm)) return;
        if (!Number.isFinite(c.lat) || !Number.isFinite(c.lng)) return;
        const d = (Number.isFinite(sLat) && Number.isFinite(sLng))
          ? Math.hypot(c.lat - sLat, (c.lng - sLng) * Math.cos(c.lat * Math.PI / 180))
          : 0;
        if (d < bestD) { bestD = d; best = c; }
      });
      if (!best) { kept++; return; }
      used.delete(String(s.name || '').trim());
      used.add(String(best.name).trim());
      // 只換「景點本身」的欄位，保留排程結果（time／order／dayIndex）
      finalStops[i] = Object.assign({}, s, {
        name: best.name,
        desc: best.desc || s.desc || '',
        lat: best.lat, lng: best.lng,
        scenicCoordinates: { lat: best.lat, lng: best.lng },
        businessHours: best.businessHours || '',
        duration: best.duration || s.duration,
        fee: best.fee, feeNote: best.feeNote,
        placeVerified: best.placeVerified === true
      });
      swapped++;
      console.info('[暫停開放] 以「' + best.name + '」替換「' + s.name + '」');
    });
    if (swapped || kept) {
      console.info('[暫停開放] 替換', swapped, '站；找不到替代而保留', kept, '站（由 planner 顯示提示）');
    }
  }
  // 前面的補站、刪站與缺日修正都可能改變 dayIndex；最後才依確定的天次排時間。
  // 替換景點若改了停留時長，也一併在此重新計算後續站點。
  const scheduledStops = applyTripPlanningRules(finalStops, wizardData);
  finalStops.splice(0, finalStops.length, ...scheduledStops);
  return finalStops;
}

function sanitizeTripForFirestore(value) {
  if (Array.isArray(value)) return Array.from(value, item => item === undefined ? null : sanitizeTripForFirestore(item));
  if (value && Object.getPrototypeOf(value) === Object.prototype) {
    return Object.fromEntries(Object.entries(value)
      .filter(([, item]) => item !== undefined)
      .map(([key, item]) => [key, sanitizeTripForFirestore(item)]));
  }
  return value; // 保留 Timestamp、GeoPoint、Date 與 FieldValue 等 SDK 型別。
}

async function saveMicroTripToFirebase(trip) {
  if (!firebaseEnabled || !firebaseDb) return false;
  try {
    const tripRef = firebaseDb.collection('micro_trips').doc(trip.id);
    // 剝除暫存旗標 __saving，避免把 UI 狀態寫進 Firestore
    // 並拿掉 role：那是「本機這個人」的角色，owner 存檔會把 role:'owner' 漏進共用文件，害加入者讀到後誤判成可編輯。
    //
    // ⚠ 協作欄位必須一起剝掉，只留 role 是不夠的。
    //   merge:true 只保護「沒出現在 payload 裡」的欄位；只要 cleanTrip 帶著本機那份過期的
    //   memberEmails（建立 lobby 時的快照，裡面只有 owner），這一寫就會把後來加入的成員
    //   整個洗掉。實測：B 加入後 owner 生成團體行程，B 就從 memberEmails 消失，於是
    //   isTripMemberOrOwner() 判他不是成員——共同相簿、presence、回憶全部 permission-denied，
    //   而且因為規則要求 editorEmails ⊆ memberEmails，連「升成可編輯」都永久失敗。
    //   planner 的 persistCurrentTripStops 早就這樣做了，這支漏了。
    const {
      role, members: _m, memberEmails: _me, memberUids: _mu, editorEmails: _ee,
      ownerEmail: _oe, ownerUid: _ou, ownerName: _on,
      guestReadable: _gr, shareToken: _stk, inviteCode: _ivc, maxMembers: _mmx,
      collabCreatedAt: _ccat,
      ...cleanTrip
    } = sanitizeTripForFirestore(serializeTripForStorage(trip));
    // merge:true：共編行程的協作欄位（members / inviteCode / memberEmails…）由 collab.js 另外維護，
    // 這裡只更新行程內容，不可整份覆寫把它們清掉。
    const payload = {
      ...cleanTrip,
      stops: cleanTrip.stops || [],
      createdAt: firebase.firestore.FieldValue.serverTimestamp(),
      updatedAt: firebase.firestore.FieldValue.serverTimestamp()
    };
    // userEmail 在共編行程不能寫：安全規則的 isOwner() 也認 userEmail，
    // 若由 editor 存檔就會把他升成擁有者等級。planner 的 persist 早就這樣擋了。
    if (!trip.collab) {
      payload.userEmail = currentUser && currentUser.email ? currentUser.email : 'unknown';
    }
    await tripRef.set(payload, { merge: true });
    console.log('微旅行已保存到 Firebase:', trip.id);
    return true;
  } catch (error) {
    console.warn('Firebase 寫入失敗：', error);
    return false;
  }
}

function sanitizeFirebaseDocId(text) {
  return (text || '').toString().trim().toLowerCase()
    .replace(/[^\p{L}\p{N}]+/gu, '_')
    .replace(/^_+|_+$/g, '');
}

function normalizePoiName(name, destination) {
  if (!name) return '';
  let normalized = String(name).trim();
  if (destination) {
    const dest = String(destination).trim();
    // Strip exact destination suffix (e.g. "鐵花村台東" → "鐵花村")
    if (normalized.endsWith(dest) && normalized.length > dest.length) {
      normalized = normalized.slice(0, -dest.length).trim();
    } else {
      // Strip destination + admin suffix variant (e.g. "綠島燈塔綠島鄉" with dest="綠島" → "綠島燈塔")
      for (const suffix of ['縣', '市', '鄉', '鎮', '區', '村']) {
        const variant = dest + suffix;
        if (normalized.endsWith(variant) && normalized.length > variant.length) {
          normalized = normalized.slice(0, -variant.length).trim();
          break;
        }
      }
    }
  }
  return normalized;
}

// ══════════════════════════════════════════════════
// PERSIST STATE
// ══════════════════════════════════════════════════
function serializeTripForStorage(trip) {
  if (!trip || typeof trip !== 'object') return trip;
  const { __saving, ...rest } = trip;
  return rest;
}

// 本機「我的微旅行」依帳號隔離：登入用 wai_mytrips:<email 小寫>，未登入（訪客）用 wai_mytrips。
// 修「同一個 Chrome Profile 換帳號登入，會看到前一個帳號的本機行程」（E2E #1，隱私風險）。
// planner 頁的 myTripsStorageKey 必須同規則。
function myTripsStorageKey() {
  try {
    const email = (currentUser && currentUser.email) || '';
    return email ? ('wai_mytrips:' + String(email).toLowerCase()) : 'wai_mytrips';
  } catch (_e) { return 'wai_mytrips'; }
}
// 訪客期間建立的行程，在登入後搬進該帳號（一次性）；帶有「其他人 ownerEmail」的一律不搬，避免舊資料外洩。
function migrateGuestTripsToUser() {
  try {
    const key = myTripsStorageKey();
    if (key === 'wai_mytrips') return;
    const guest = JSON.parse(localStorage.getItem('wai_mytrips') || '[]');
    if (!Array.isArray(guest) || !guest.length) return;
    const me = String((currentUser && currentUser.email) || '').toLowerCase();
    const mine = JSON.parse(localStorage.getItem(key) || '[]');
    const ids = new Set(mine.map(t => t && t.id));
    const keep = [];
    guest.forEach(t => {
      const owner = String((t && t.ownerEmail) || '').toLowerCase();
      if (owner && owner !== me) { keep.push(t); return; }   // 別人的：留在訪客鍵、不搬
      if (t && !ids.has(t.id)) { mine.push(t); ids.add(t.id); }
    });
    localStorage.setItem(key, JSON.stringify(mine));
    localStorage.setItem('wai_mytrips', JSON.stringify(keep));
  } catch (_e) {}
}

// 可寫入帳號鍵的行程：排除 __transientGuest（Firebase 不可用時「僅暫時顯示」的訪客行程）。
// 那些行程尚未確認歸屬，寫進去等於歸給快取裡的舊帳號，且之後再也搬不回正確帳號。
function persistableMyTrips() {
  return myTrips.filter((t) => !(t && t.__transientGuest)).map(serializeTripForStorage);
}

function saveState() {
  try {
    localStorage.setItem('wai_user', JSON.stringify({isLoggedIn, currentUser}));
    localStorage.setItem(myTripsStorageKey(), JSON.stringify(persistableMyTrips()));
  } catch(e){}
}
async function loadState() {
  try {
    const u = JSON.parse(localStorage.getItem('wai_user')||'{}');
    if(u.isLoggedIn){isLoggedIn=true;currentUser=u.currentUser;}
    // 訪客行程搬移「只在 Firebase Auth 確認真實帳號後」做（見 onAuthStateChanged 登入分支）——
    // 搬移會清空訪客鍵、不可逆，依過期快取身分搬會把行程送給錯的帳號、之後也搬不回來。
    myTrips = JSON.parse(localStorage.getItem(myTripsStorageKey())||'[]').map(serializeTripForStorage);
    // Firebase 不可用時（CDN 未載入／離線）不會有 auth callback，也就不會有搬移。
    // 這時把訪客鍵的行程標成 __transientGuest 併進 myTrips「只供顯示」——
    // ★ 不可直接混進去：saveState() 會把整個 myTrips 寫回帳號鍵，等於偷偷把訪客行程
    //   歸給了「快取裡的那個帳號」（可能是別人）。saveState 會濾掉這個旗標。
    //   真正的歸屬仍等 Auth 確認後由 migrateGuestTripsToUser() 處理。
    if (isLoggedIn && (typeof firebase === 'undefined' || !firebaseEnabled)) {
      try {
        const guestTrips = JSON.parse(localStorage.getItem('wai_mytrips') || '[]').map(serializeTripForStorage);
        const seen = new Set(myTrips.map((t) => t && t.id));
        guestTrips.forEach((t) => {
          if (t && !seen.has(t.id)) { myTrips.push({ ...t, __transientGuest: true }); seen.add(t.id); }
        });
      } catch (_e) {}
    }
    
    // 必須等 firebaseAuth.currentUser 真的就緒才查 Firestore：開機時 localStorage 說「已登入」
    // 但 Auth token 尚未還原（request.auth=null），查詢會被安全規則擋下、噴 permission 錯誤。
    // auth 還原後 onAuthStateChanged 會再呼叫一次 loadState，屆時才真正同步。
    if (isLoggedIn && typeof firebase !== 'undefined' && firebaseEnabled && firebaseDb && currentUser && currentUser.email
        && typeof firebaseAuth !== 'undefined' && firebaseAuth && firebaseAuth.currentUser) {
      try {
        // 不用 .orderBy('createdAt')：等式查詢 + 不同欄位排序會要求複合索引（就是 console 那個 "requires an index" 錯誤）。
        // 改成只用等式查詢（單欄位自動索引），抓回來後在前端依 createdAt 排序。
        const snapshot = await firebaseDb.collection('micro_trips')
          .where('userEmail', '==', currentUser.email)
          .get();
        if (!snapshot.empty) {
           const _ms = (c) => (c && typeof c.toMillis === 'function') ? c.toMillis() : (c && c.seconds ? c.seconds * 1000 : 0);
           const fbTrips = snapshot.docs
             .slice()
             .sort((a, b) => _ms(b.data().createdAt) - _ms(a.data().createdAt))
             .map(doc => {
               const data = doc.data();
               if (data.createdAt && typeof data.createdAt.toDate === 'function') {
                 data.createdAt = data.createdAt.toDate().toLocaleDateString('zh-TW');
               } else if (data.createdAt && typeof data.createdAt === 'object' && data.createdAt.seconds) {
                 data.createdAt = new Date(data.createdAt.seconds * 1000).toLocaleDateString('zh-TW');
               }
               return data;
           });
           const mergedTrips = [...fbTrips];
           myTrips.forEach(localTrip => {
              if (!mergedTrips.find(t => t.id === localTrip.id)) {
                  mergedTrips.push(localTrip);
              }
           });
           myTrips = mergedTrips;
           localStorage.setItem(myTripsStorageKey(), JSON.stringify(persistableMyTrips()));
           if (document.getElementById('myTripsView').style.display !== 'none') {
             renderMyTrips();
           }
           renderSideMyTrips();
        }
      } catch(err) {
        console.warn('Failed to sync trips from Firebase:', err);
      }
      // 載入「我以成員身分加入」的共編行程（owner 的查詢以 userEmail 為準，抓不到別人的行程）
      try {
        if (window.WAI_COLLAB) {
           const collabTrips = await WAI_COLLAB.fetchMyCollabTrips(currentUser.email);
           collabTrips.forEach(t => upsertCollabTripLocal(t));
           startMyCollabTripsLiveSync(currentUser.email);
          localStorage.setItem(myTripsStorageKey(), JSON.stringify(persistableMyTrips()));
          renderSideMyTrips();
          const mtv = document.getElementById('myTripsView');
          if (mtv && mtv.style.display !== 'none') renderMyTrips();
        }
      } catch(err) {
        console.warn('Failed to load collab trips:', err);
      }
    }
  } catch(e){}
}

// ══════════════════════════════════════════════════
// NAV VIEWS
// ══════════════════════════════════════════════════
function showMainView(view) {
  document.getElementById('exploreView').style.display = view==='explore' ? '' : 'none';
  document.getElementById('myTripsView').style.display = view==='mytrips' ? '' : 'none';
  const friendsEl = document.getElementById('friendsView');
  if (friendsEl) friendsEl.style.display = view==='friends' ? '' : 'none';
  document.getElementById('navExplore').classList.toggle('active', view==='explore');
  document.getElementById('navMyTrips').classList.toggle('active', view==='mytrips');
  const navFriends = document.getElementById('navFriends');
  if (navFriends) navFriends.classList.toggle('active', view==='friends');
  // UIUX#1：手機底部導覽 active 同步
  const mbnE = document.getElementById('mbnExplore');
  const mbnM = document.getElementById('mbnMyTrips');
  const mbnF = document.getElementById('mbnFriends');
  if (mbnE) mbnE.classList.toggle('active', view==='explore');
  if (mbnM) mbnM.classList.toggle('active', view==='mytrips');
  if (mbnF) mbnF.classList.toggle('active', view==='friends');
  if (view==='mytrips') renderMyTrips();
  if (view==='friends') renderFriendsView();
}

// ══════════════════════════════════════════════════
// USER AUTH
// ══════════════════════════════════════════════════
function renderUserMenu() {
  const wrap = document.getElementById('userMenuWrap');
  // 「📱 邀請碼快速加入」只在登入後顯示（加入共編需要已登入帳號）
  const journeyBtn = document.getElementById('journeyJoinBtn');
  if (journeyBtn) journeyBtn.style.display = isLoggedIn ? '' : 'none';
  if (!isLoggedIn) {
    // 桌機顯示「登入/註冊」按鈕；手機改用漢堡（☰）把導覽與動作收進下拉選單
    wrap.innerHTML = `
      <button class="login-prompt-btn" onclick="openLogin()">登入 / 註冊</button>
      <button class="user-avatar-btn hamburger-btn" onclick="toggleUserDropdown()" aria-label="選單" title="選單">☰</button>
      <div class="user-dropdown" id="userDropdown">
        <div class="user-dd-item dd-mobile-only" onclick="showMainView('explore');toggleUserDropdown()">🧭 探索</div>
        <div class="user-dd-item dd-mobile-only" onclick="showMainView('mytrips');toggleUserDropdown()">📋 我的微旅行</div>
        <div class="user-dd-item dd-mobile-only" onclick="showMainView('friends');toggleUserDropdown()">👥 好友</div>
        <div class="user-dd-item dd-mobile-only" onclick="openWizard();toggleUserDropdown()">＋ 建立微旅行</div>
        <div class="user-dd-sep dd-mobile-only"></div>
        <div class="user-dd-item dd-mobile-only" onclick="openLogin();toggleUserDropdown()">👤 登入 / 註冊</div>
      </div>`;
  } else {
    const u = currentUser || {};
    // 僅 Email/密碼帳號顯示「修改密碼」（社群登入無密碼）
    const _fu = (typeof firebaseAuth !== 'undefined' && firebaseAuth) ? firebaseAuth.currentUser : null;
    const _hasPwd = !!(_fu && (_fu.providerData || []).some(p => p && p.providerId === 'password'));
    wrap.innerHTML = `
      <div class="user-avatar-btn" onclick="toggleUserDropdown()" title="${escapeHtml(u.name || '')}">
        ${escapeHtml(u.emoji || '😊')}
      </div>
      <div class="user-dropdown" id="userDropdown">
        <div class="user-dropdown-header">
          <div class="user-dropdown-name">${escapeHtml(u.name || '')}</div>
          <div class="user-dropdown-email">${escapeHtml(u.email || '')}</div>
        </div>
        <div class="user-dd-item dd-mobile-only" onclick="showMainView('explore');toggleUserDropdown()">🧭 探索</div>
        <div class="user-dd-item" onclick="showMainView('mytrips');toggleUserDropdown()">📋 我的微旅行 <span style="margin-left:auto;background:var(--accent-light);color:var(--accent);font-size:13px;padding:1px 7px;border-radius:8px">${myTrips.length}</span></div>
        <div class="user-dd-item dd-mobile-only" onclick="showMainView('friends');toggleUserDropdown()">👥 好友</div>
        <div class="user-dd-item" onclick="openWizard();toggleUserDropdown()">＋ 建立微旅行</div>
        <div class="user-dd-item dd-mobile-only" onclick="openJourneyJoin();toggleUserDropdown()">📱 邀請碼快速加入</div>
        <div class="user-dd-item" onclick="openInvite();toggleUserDropdown()">🔑 輸入邀請碼加入</div>
        <div class="user-dd-item" onclick="showMainView('friends');toggleUserDropdown()">👥 我的好友${friendsPendingIncoming.length ? ` <span style="margin-left:auto;background:#fde8e6;color:#c0392b;font-size:13px;padding:1px 7px;border-radius:8px">${friendsPendingIncoming.length}</span>` : ''}</div>
        <div class="user-dd-sep"></div>
        <div class="user-dd-item" onclick="openPrefWizard();toggleUserDropdown()">🎯 修改個人喜好</div>
        ${_hasPwd ? `<div class="user-dd-item" onclick="openChangePwd();toggleUserDropdown()">🔒 修改密碼</div>` : ''}
        <div class="user-dd-sep"></div>
        <div class="user-dd-item danger" onclick="doLogout()">👋 登出</div>
      </div>`;
  }
  enhanceUserMenuA11y(wrap);
}

// 選單的觸發器與項目都是 <div onclick>，鍵盤完全按不到，讀屏也只會唸成一段文字。
// 改成在產生後統一補語意，而不是去改十幾行樣板字串——那樣容易漏，也會動到既有樣式。
function enhanceUserMenuA11y(wrap) {
  if (!wrap) return;
  const trigger = wrap.querySelector('.user-avatar-btn');
  const dd = wrap.querySelector('#userDropdown');
  if (trigger) {
    trigger.setAttribute('role', 'button');
    trigger.setAttribute('tabindex', '0');
    trigger.setAttribute('aria-haspopup', 'menu');
    trigger.setAttribute('aria-controls', 'userDropdown');
    trigger.setAttribute('aria-expanded', dd && dd.classList.contains('open') ? 'true' : 'false');
    if (!trigger.getAttribute('aria-label')) trigger.setAttribute('aria-label', '帳號選單');
  }
  if (dd) {
    dd.setAttribute('role', 'menu');
    dd.setAttribute('aria-label', '帳號選單');
    dd.querySelectorAll('.user-dd-item').forEach((item) => {
      item.setAttribute('role', 'menuitem');
      item.setAttribute('tabindex', '0');
    });
    dd.querySelectorAll('.user-dd-sep').forEach((sep) => sep.setAttribute('role', 'separator'));
  }
}

// Enter／空白鍵啟動；Esc 關閉並把焦點送回觸發器。
document.addEventListener('keydown', (e) => {
  const wrap = document.getElementById('userMenuWrap');
  if (!wrap) return;
  const dd = document.getElementById('userDropdown');
  if (e.key === 'Escape' && dd && dd.classList.contains('open')) {
    e.preventDefault();
    dd.classList.remove('open');
    const trigger = wrap.querySelector('.user-avatar-btn');
    if (trigger) { trigger.setAttribute('aria-expanded', 'false'); trigger.focus(); }
    return;
  }
  if (e.key !== 'Enter' && e.key !== ' ') return;
  const target = e.target && e.target.closest ? e.target.closest('.user-dd-item, .user-avatar-btn') : null;
  if (!target || !wrap.contains(target)) return;
  e.preventDefault();
  target.click();
});

function toggleUserDropdown() {
  const dd = document.getElementById('userDropdown');
  if (dd) dd.classList.toggle('open');
  const trigger = document.querySelector('#userMenuWrap .user-avatar-btn');
  if (trigger) trigger.setAttribute('aria-expanded', dd && dd.classList.contains('open') ? 'true' : 'false');
  // 用鍵盤開啟時把焦點帶進第一個項目，否則 Tab 會直接跳出選單
  if (dd && dd.classList.contains('open')) {
    const first = dd.querySelector('.user-dd-item');
    if (first && document.activeElement === trigger) first.focus();
  }
}
document.addEventListener('click', e => {
  const wrap = document.getElementById('userMenuWrap');
  if (wrap && !wrap.contains(e.target)) {
    const dd = document.getElementById('userDropdown');
    if (dd) dd.classList.remove('open');
    const trigger = wrap.querySelector('.user-avatar-btn');
    if (trigger) trigger.setAttribute('aria-expanded', 'false');
  }
});

// ══════════════════════════════════════════════════
// WEEK4 D1：好友（資料層在 friends.js / WAI_FRIENDS）
// ══════════════════════════════════════════════════
let friendsAccepted = [];        // 已成立好友（friendship docs）
let friendsPendingIncoming = []; // 收到的邀請（toEmail == 我）
let friendsPendingOutgoing = []; // 送出的邀請（fromEmail == 我）
let friendsUnsub = null;

// 登入後啟動好友即時訂閱（onAuthStateChanged 呼叫；登出時 stopFriendsSubscriptions 清理）
function startFriendsSubscriptions(myEmail) {
  stopFriendsSubscriptions();
  if (!window.WAI_FRIENDS || !myEmail) return;
  friendsUnsub = WAI_FRIENDS.subscribeFriendships(myEmail, (list) => {
    friendsAccepted = list.filter(f => f.status === 'accepted');
    friendsPendingIncoming = list.filter(f => f.status === 'pending' && f.toEmail === myEmail);
    friendsPendingOutgoing = list.filter(f => f.status === 'pending' && f.fromEmail === myEmail);
    renderUserMenu(); // 更新「我的好友」列的待確認數字
    const view = document.getElementById('friendsView');
    if (view && view.style.display !== 'none') renderFriendsView();
  }, (err) => console.warn('好友訂閱失敗：', err));
}
function stopFriendsSubscriptions() {
  if (friendsUnsub) { friendsUnsub(); friendsUnsub = null; }
  friendsAccepted = []; friendsPendingIncoming = []; friendsPendingOutgoing = [];
}

// ══════════════════════════════════════════════════
// F1 通知中心（資料層在 notifications.js / WAI_NOTIFY）
// ══════════════════════════════════════════════════
let notifItems = [];
let notifUnsub = null;
// UIUX#8：大視窗的「全部／未讀」篩選狀態，只影響呈現不動資料。
let notifFilter = 'all';
// 是否已收到第一次 onSnapshot。用來分辨「還在載入」與「真的沒有通知」——
// 兩者都是空陣列，但該給使用者看的東西完全不同。
let notifLoaded = false;

function startNotifSubscription(myEmail) {
  stopNotifSubscription();
  if (!window.WAI_NOTIFY || !myEmail) return;
  const wrap = document.getElementById('notifWrap');
  if (wrap) wrap.style.display = '';
  notifUnsub = WAI_NOTIFY.subscribe(myEmail, (list) => {
    notifItems = list;
    notifLoaded = true;
    updateNotifBadge();
    // 大視窗開啟時小面板必定是關的（openNotifFullView 會移除 .open），
    // 只判斷 .open 會讓大視窗收不到推播——接受/拒絕後按鈕會永久卡在 disabled。
    const panel = document.getElementById('notifPanel');
    if ((panel && panel.classList.contains('open')) || document.getElementById('notifFullOverlay')) {
      renderNotifPanel(); // 內部會連帶刷新大視窗
    }
    // F2 好友動態：動態流吃 notifItems，好友頁顯示中就即時重繪
    const view = document.getElementById('friendsView');
    if (view && view.style.display !== 'none') renderFriendsView();
  }, () => {});
}

function stopNotifSubscription() {
  if (notifUnsub) { notifUnsub(); notifUnsub = null; }
  notifItems = [];
  notifLoaded = false;
  notifFilter = 'all';
  const wrap = document.getElementById('notifWrap');
  if (wrap) wrap.style.display = 'none';
  const panel = document.getElementById('notifPanel');
  if (panel) panel.classList.remove('open');
  updateNotifBadge();
}

function updateNotifBadge() {
  const badge = document.getElementById('notifBadge');
  if (!badge) return;
  const unread = notifItems.filter(n => n && !n.read).length;
  badge.style.display = unread ? '' : 'none';
  badge.textContent = unread > 9 ? '9+' : String(unread);
}

const NOTIF_TYPE_META = {
  collab_invite:         { emoji: '🎒', label: '共編邀請' },
  friend_invite:         { emoji: '✉️', label: '好友邀請' },
  friend_accept:         { emoji: '🎉', label: '好友成立' },
  trip_renamed:          { emoji: '✏️', label: '行程改名' },
  trip_regenerated:      { emoji: '🔄', label: '行程重生成' },
  friend_trip_completed: { emoji: '🏁', label: '好友完成行程' },
  trip_join_request:     { emoji: '🙋', label: '加入申請' },
  trip_join_accepted:    { emoji: '✅', label: '申請已接受' },
  trip_join_rejected:    { emoji: '↩', label: '申請結果' }
};

function notifItemText(n) {
  const who = n.fromName || n.fromEmail || '旅伴';
  const title = n.tripTitle || '行程';
  switch (n.type) {
    case 'trip_join_request':
      if (n.requestStatus === 'accepted') return `已接受 ${who} 加入「${title}」`;
      if (n.requestStatus === 'rejected') return `已拒絕 ${who} 加入「${title}」`;
      return `${who} 申請加入「${title}」`;
    case 'trip_join_accepted':    return `${who} 已接受你加入「${title}」`;
    case 'trip_join_rejected':    return `${who} 未接受你加入「${title}」`;
    case 'collab_invite':         return `${who} 邀請你共編「${title}」${n.message ? ` · ${n.message}` : ''}`;
    case 'friend_invite':         return `${who} 邀請你成為好友`;
    case 'friend_accept':         return `${who} 接受了你的好友邀請`;
    case 'trip_renamed':          return `${who} 把共編行程改名為「${title}」`;
    case 'trip_regenerated':      return `${who} 重新生成了「${title}」的行程`;
    case 'friend_trip_completed': return `${who} 完成了行程「${title}」`;
    default: return n.message || '新通知';
  }
}

function notifTimeText(ts) {
  try {
    const d = ts && typeof ts.toDate === 'function' ? ts.toDate() : null;
    if (!d) return '';
    const diff = Date.now() - d.getTime();
    if (diff < 60000) return '剛剛';
    if (diff < 3600000) return `${Math.floor(diff / 60000)} 分鐘前`;
    if (diff < 86400000) return `${Math.floor(diff / 3600000)} 小時前`;
    return `${d.getMonth() + 1}/${d.getDate()}`;
  } catch (_e) { return ''; }
}

function toggleNotifPanel() {
  const panel = document.getElementById('notifPanel');
  if (!panel) return;
  const willOpen = !panel.classList.contains('open');
  panel.classList.toggle('open', willOpen);
  if (willOpen) renderNotifPanel();
}

// 通知列 HTML：小面板與展開大視窗共用同一份，避免兩邊行為分歧。
// indices：要呈現哪幾筆（值是 notifItems 的「原始索引」）。大視窗分區／篩選後
// 順序會變，但 onclick 傳的必須始終是原始索引，否則會操作到別筆通知。
// 不傳則等同全部。
function buildNotifRowsHtml(expanded, indices) {
  const itemCls = expanded ? 'notif-item notif-item-expanded' : 'notif-item';
  const list = Array.isArray(indices)
    ? indices.map(i => [notifItems[i], i]).filter(pair => pair[0])
    : notifItems.map((n, i) => [n, i]);
  return list.map(([n, i]) => {
    const meta = NOTIF_TYPE_META[n.type] || { emoji: '🔔', label: '通知' };
    const content = `<span class="notif-item-emoji">${meta.emoji}</span>
      <span class="notif-item-main">
        <span class="notif-item-text">${escapeHtml(notifItemText(n))}</span>
        <span class="notif-item-sub">${escapeHtml(meta.label)}${notifTimeText(n.createdAt) ? ' · ' + escapeHtml(notifTimeText(n.createdAt)) : ''}</span>
      </span>
      ${n.read ? '' : '<span class="notif-dot"></span>'}`;
    if (n.type === 'trip_join_request' && (!n.requestStatus || n.requestStatus === 'pending')) {
      return `<div class="notif-request-row">
        <div class="${itemCls}${n.read ? '' : ' unread'}">${content}</div>
        <div class="notif-request-actions">
          <button type="button" class="notif-request-btn accept" onclick="resolveNotifJoinRequest(${i},'accept',event)">接受</button>
          <button type="button" class="notif-request-btn" onclick="resolveNotifJoinRequest(${i},'reject',event)">拒絕</button>
        </div>
      </div>`;
    }
    // 大視窗裡點通知要先關掉視窗，否則導頁後彈窗殘留在下一頁的 DOM 上。
    const act = expanded ? `closeNotifFullView();handleNotifClick(${i})` : `handleNotifClick(${i})`;
    return `<button type="button" class="${itemCls}${n.read ? '' : ' unread'}" onclick="${act}">
      ${content}
    </button>`;
  }).join('');
}

function renderNotifPanel() {
  const panel = document.getElementById('notifPanel');
  if (!panel) return;
  const unread = notifItems.filter(n => n && !n.read).length;
  panel.innerHTML = `
    <div class="notif-panel-head">
      <button type="button" class="notif-head-expand" onclick="openNotifFullView()" aria-label="展開完整通知列表">🔔 通知 <span class="notif-head-chevron" aria-hidden="true">⤢</span></button>
      ${unread ? `<button class="notif-mark-all" onclick="notifMarkAllRead()">全部標為已讀</button>` : ''}
    </div>
    ${buildNotifRowsHtml(false) || '<div class="notif-empty">目前沒有通知</div>'}`;
  // 大視窗開著時一併刷新（接受/拒絕、標已讀後列表會變）
  if (document.getElementById('notifFullOverlay')) renderNotifFullView();
}

function openNotifFullView() {
  const panel = document.getElementById('notifPanel');
  if (panel) panel.classList.remove('open');
  if (!document.getElementById('notifFullOverlay')) {
    const overlay = document.createElement('div');
    overlay.id = 'notifFullOverlay';
    overlay.className = 'notif-full-overlay';
    overlay.addEventListener('click', (e) => { if (e.target === overlay) closeNotifFullView(); });
    document.body.appendChild(overlay);
  }
  renderNotifFullView();
}

// UIUX#8：待處理的加入申請要獨立成一區——它是「需要你動作」的事項，
// 混在一般通知裡容易被滑過去。
function isPendingJoinRequest(n) {
  return !!n && n.type === 'trip_join_request'
    && (!n.requestStatus || n.requestStatus === 'pending');
}

function setNotifFilter(mode) {
  notifFilter = (mode === 'unread') ? 'unread' : 'all';
  renderNotifFullView();
}
window.setNotifFilter = setNotifFilter;

function renderNotifFullView() {
  const overlay = document.getElementById('notifFullOverlay');
  if (!overlay) return;
  const unread = notifItems.filter(n => n && !n.read).length;

  // 先分區：待處理申請 vs 其餘；篩選只作用在「其餘」，
  // 待處理申請永遠顯示（就算已讀），否則使用者會找不到要處理的東西。
  const requestIdx = [];
  const otherIdx = [];
  notifItems.forEach((n, i) => {
    if (!n) return;
    if (isPendingJoinRequest(n)) requestIdx.push(i);
    else if (notifFilter === 'all' || !n.read) otherIdx.push(i);
  });

  let bodyHtml;
  if (!notifLoaded) {
    // 載入狀態：與「沒有通知」區分開來
    bodyHtml = `<div class="notif-loading-hint">載入通知中…</div>`
      + (window.waiSkeletonRows ? waiSkeletonRows(4, 'notif-skeleton') : '');
  } else if (!requestIdx.length && !otherIdx.length) {
    bodyHtml = notifFilter === 'unread'
      ? '<div class="notif-empty">沒有未讀通知<br><span class="notif-empty-sub">切到「全部」可以看過去的紀錄</span></div>'
      : '<div class="notif-empty">目前沒有通知<br><span class="notif-empty-sub">有人邀你共編或申請加入行程時，會出現在這裡</span></div>';
  } else {
    bodyHtml = (requestIdx.length ? `
      <div class="notif-section">
        <div class="notif-section-title">待處理的加入申請 <span class="notif-section-count">${requestIdx.length}</span></div>
        ${buildNotifRowsHtml(true, requestIdx)}
      </div>` : '')
      + (otherIdx.length ? `
      <div class="notif-section">
        ${requestIdx.length ? '<div class="notif-section-title">其他通知</div>' : ''}
        ${buildNotifRowsHtml(true, otherIdx)}
      </div>`
      : (notifFilter === 'unread' ? '<div class="notif-empty">沒有其他未讀通知</div>' : ''));
  }

  overlay.innerHTML = `<section class="notif-full-card" role="dialog" aria-modal="true" aria-label="全部通知">
    <header class="notif-full-head">
      <div class="notif-full-title">🔔 全部通知${unread ? ` <span class="notif-full-count">${unread}</span>` : ''}</div>
      <div class="notif-full-actions">
        ${unread ? `<button type="button" class="notif-mark-all" onclick="notifMarkAllRead()">全部標為已讀</button>` : ''}
        <button type="button" class="notif-full-close" onclick="closeNotifFullView()" aria-label="關閉通知列表">✕</button>
      </div>
    </header>
    <div class="notif-filter-bar" role="tablist" aria-label="通知篩選">
      <button type="button" role="tab" aria-selected="${notifFilter === 'all'}"
        class="notif-filter-btn${notifFilter === 'all' ? ' active' : ''}"
        onclick="setNotifFilter('all')">全部</button>
      <button type="button" role="tab" aria-selected="${notifFilter === 'unread'}"
        class="notif-filter-btn${notifFilter === 'unread' ? ' active' : ''}"
        onclick="setNotifFilter('unread')">未讀${unread ? ` (${unread})` : ''}</button>
    </div>
    <div class="notif-full-body">
      ${bodyHtml}
    </div>
  </section>`;
}

function closeNotifFullView() {
  const overlay = document.getElementById('notifFullOverlay');
  if (overlay) overlay.remove();
}

async function resolveNotifJoinRequest(idx, decision, event) {
  if (event) event.stopPropagation();
  const n = notifItems[idx];
  if (!n || !n.tripId || !n.requesterUid || !window.WAI_COLLAB) return;
  // UIUX#11：與 planner 的 resolvePlannerJoinRequest 同一套處理，兩頁行為一致。
  const clicked = event && event.currentTarget;
  const actions = clicked && clicked.closest('.notif-request-actions');
  const siblings = actions ? [...actions.querySelectorAll('button')].filter(b => b !== clicked) : [];
  siblings.forEach(btn => { btn.disabled = true; });
  const restore = window.waiSetBusy
    ? waiSetBusy(clicked, decision === 'accept' ? '接受中…' : '拒絕中…')
    : () => {};
  try {
    await WAI_COLLAB.resolveTripJoinRequest(n.tripId, n.requesterUid, decision);
    showToast(decision === 'accept' ? '已接受加入申請' : '已拒絕加入申請', 'green');
    // 成功後推播會重繪列表，clicked 通常已離開 DOM——不主動還原，避免畫面閃動。
    // 但不能「只」依賴推播：listener 斷線／離線時列表不會重繪，按鈕會永久卡住。
    // 掛安全網：逾時後若節點仍在，還原成可操作。
    if (window.waiBusyUntilRerender) {
      waiBusyUntilRerender(() => {
        restore();
        siblings.forEach(btn => { btn.disabled = false; });
      }, clicked);
    }
  } catch (error) {
    restore();
    siblings.forEach(btn => { btn.disabled = false; });
    showToast((error && error.message) || '處理加入申請失敗', 'red');
  }
}

function notifMarkAllRead() {
  const myEmail = (currentUser && currentUser.email) || '';
  if (!myEmail || !window.WAI_NOTIFY) return;
  WAI_NOTIFY.markAllRead(myEmail, notifItems);
  notifItems = notifItems.map(n => ({ ...n, read: true })); // 樂觀更新，onSnapshot 會再校正
  updateNotifBadge();
  renderNotifPanel();
}

function handleNotifClick(idx) {
  const n = notifItems[idx];
  if (!n) return;
  const myEmail = (currentUser && currentUser.email) || '';
  if (!n.read && myEmail && window.WAI_NOTIFY) {
    WAI_NOTIFY.markRead(myEmail, n.id);
    notifItems[idx] = { ...n, read: true };
    updateNotifBadge();
  }
  const panel = document.getElementById('notifPanel');
  if (panel) panel.classList.remove('open');
  // 依類型導頁
  // 加入申請相關（自己被接受、或自己已處理完別人的申請）→ 直接開那份行程的畫面。
  // 這類通知的重點是「行程可以看了」，不是成員名單，故不走共編面板。
  if ((n.type === 'trip_join_accepted' || n.type === 'trip_join_request') && n.tripId) {
    location.href = `ai-travel-planner-v8.html?id=${encodeURIComponent(n.tripId)}`;
    return;
  }
  if ((n.type === 'collab_invite' || n.type === 'trip_renamed' || n.type === 'trip_regenerated') && n.tripId) {
    // 已是成員（改名/重生成/曾受邀）→ 開共編面板；不是成員時 openCollabPanel 內部會擋
    const isMine = (myTrips || []).some(t => t.id === n.tripId);
    if (isMine) { openCollabPanel(n.tripId); return; }
    if (n.type === 'collab_invite') {
      const codeMatch = String(n.message || '').match(/邀請碼\s*([A-Z0-9-]+)/i);
      openJourneyJoin(codeMatch ? codeMatch[1] : '');
      return;
    }
    showMainView('mytrips');
    return;
  }
  showMainView('friends');
}
// 面板外點擊自動收合
document.addEventListener('click', (e) => {
  const wrap = document.getElementById('notifWrap');
  if (wrap && !wrap.contains(e.target)) {
    const panel = document.getElementById('notifPanel');
    if (panel) panel.classList.remove('open');
  }
});

// 從 friendship doc 取「對方」的 email 與顯示名
// 共編面板「從好友邀請」：組含邀請碼的訊息複製到剪貼簿（idx 對應 renderCollabPanel 存的候選陣列）
function collabInviteFriend(idx) {
  const o = (window.__collabFriendCands || [])[idx];
  const d = collabState && collabState.data;
  if (!o || !d || !d.inviteCode) return showToast('邀請碼尚未就緒', 'orange');
  const code = WAI_COLLAB.formatInviteCode(d.inviteCode);
  const msg = `嗨 ${o.name || ''}！來一起共編行程「${d.title || '未命名行程'}」吧 🎒\n開啟 ${location.origin}${location.pathname} 後點「加入共編」，輸入邀請碼：${code}`;
  navigator.clipboard.writeText(msg)
    .then(() => showToast(`已複製給 ${o.name || o.email} 的邀請訊息，貼到聊天視窗即可`, 'green'))
    .catch(() => showToast(msg, 'green'));
  // F1：同步發站內通知（失敗靜默）
  if (window.WAI_NOTIFY && currentUser && currentUser.email) {
    const myKey = WAI_COLLAB.identityKey(currentUser.email);
    WAI_NOTIFY.push(o.email, WAI_NOTIFY.nid(['collab_invite', d.id, myKey]), {
      type: 'collab_invite', tripId: d.id, tripTitle: d.title || '未命名行程',
      fromName: currentUser.name || '', message: `邀請碼 ${code}`
    });
  }
}

function friendOtherParty(f) {
  const myEmail = (currentUser && currentUser.email) || '';
  const other = (f.emails || []).find(e => e !== myEmail) || '';
  const name = (f.names && (f.names[WAI_COLLAB.identityKey(other)] || f.names[WAI_COLLAB.emailKey(other)])) || other;
  return { email: other, name };
}

// F2 好友動態：不開新查詢——合併「通知流（friend_accept / friend_trip_completed）」
// 與 friendsAccepted 的 acceptedAt，依時間新→舊取前 10 筆。
function buildFriendsFeedItems() {
  const toMs = (ts) => {
    try { return ts && typeof ts.toDate === 'function' ? ts.toDate().getTime() : (Number(ts) || 0); }
    catch (_e) { return 0; }
  };
  const items = [];
  (friendsAccepted || []).forEach(f => {
    const o = friendOtherParty(f);
    items.push({ ts: toMs(f.acceptedAt), emoji: '🤝', text: `你和 ${o.name || o.email || '旅伴'} 成為好友` });
  });
  (notifItems || []).forEach(n => {
    if (!n) return;
    const who = n.fromName || n.fromEmail || '旅伴';
    // 成為好友已由 friendships.acceptedAt 呈現；不再把 friend_accept 通知重複加入動態。
    if (n.type === 'friend_trip_completed') {
      items.push({ ts: toMs(n.createdAt), emoji: '🏁', text: `${who} 完成了行程「${n.tripTitle || '微旅行'}」` });
    }
  });
  return items.sort((a, b) => b.ts - a.ts).slice(0, 10);
}

function renderFriendsFeed(host) {
  const feed = buildFriendsFeedItems();
  const timeTxt = (ms) => {
    if (!ms) return '';
    const d = new Date(ms);
    return `${d.getMonth() + 1}/${d.getDate()}`;
  };
  host.innerHTML = `<div class="friends-section-title">📣 好友動態</div>`
    + (feed.length
      ? feed.map(it => `<div class="friends-feed-row"><span class="friends-feed-emoji">${it.emoji}</span><span class="friends-feed-text">${escapeHtml(it.text)}</span><span class="friends-feed-time">${escapeHtml(timeTxt(it.ts))}</span></div>`).join('')
      : `<div class="friends-empty" style="padding:14px 16px;">近期沒有好友動態</div>`);
}

function renderFriendsView() {
  const feedHost = document.getElementById('friendsFeed');
  const pendingHost = document.getElementById('friendsPendingList');
  const listHost = document.getElementById('friendsList');
  if (!pendingHost || !listHost) return;
  if (!isLoggedIn) {
    if (feedHost) feedHost.innerHTML = '';
    pendingHost.innerHTML = '';
    listHost.innerHTML = `<div class="friends-empty">登入後即可管理好友。<button class="wai-action-btn primary" style="margin-left:10px" onclick="openLogin()">登入 / 註冊</button></div>`;
    return;
  }
  if (feedHost) renderFriendsFeed(feedHost); // F2 好友動態

  // 待確認邀請（收到的在前，送出的在後）
  const pendingRows = [
    ...friendsPendingIncoming.map(f => {
      const o = friendOtherParty(f);
      return `<div class="friends-row">
        <div class="friends-row-main"><b>${escapeHtml(o.name)}</b><span class="friends-row-sub">${escapeHtml(o.email)} · 邀請你成為好友</span></div>
        <button class="friends-mini-btn primary" onclick="friendsAcceptInvite('${escapeHtml(f.id)}')">✓ 接受</button>
        <button class="friends-mini-btn ghost" onclick="friendsRemove('${escapeHtml(f.id)}','reject')">✕ 拒絕</button>
      </div>`;
    }),
    ...friendsPendingOutgoing.map(f => {
      const o = friendOtherParty(f);
      return `<div class="friends-row">
        <div class="friends-row-main"><b>${escapeHtml(o.name)}</b><span class="friends-row-sub">${escapeHtml(o.email)} · 等待對方確認</span></div>
        <button class="friends-mini-btn ghost" onclick="friendsRemove('${escapeHtml(f.id)}','cancel')">取消邀請</button>
      </div>`;
    })
  ];
  pendingHost.innerHTML = pendingRows.length
    ? `<div class="friends-section-title">⏳ 待確認邀請（${pendingRows.length}）</div>${pendingRows.join('')}`
    : '';

  // 好友列表（空狀態＝引導卡：三步驟＋內嵌主按鈕，不再只寫「按右上」）
  listHost.innerHTML = `<div class="friends-section-title">💛 我的好友（${friendsAccepted.length}）</div>`
    + (friendsAccepted.length
      ? friendsAccepted.map(f => {
          const o = friendOtherParty(f);
          return `<div class="friends-row">
            <div class="friends-row-main"><b>${escapeHtml(o.name)}</b><span class="friends-row-sub">${escapeHtml(o.email)}</span></div>
            <button class="friends-mini-btn ghost danger" onclick="friendsRemove('${escapeHtml(f.id)}','unfriend')">解除好友</button>
          </div>`;
        }).join('')
      : `<div class="friends-empty friends-empty-hero">
          <div class="friends-empty-emoji">🧑‍🤝‍🧑</div>
          <div class="friends-empty-title">邀請旅伴，一起規劃共編行程</div>
          <ol class="friends-empty-steps">
            <li>輸入旅伴的 email 送出邀請</li>
            <li>對方登入 TravelLinkAI 按「接受」</li>
            <li>建立共編行程，用邀請碼把好友拉進來</li>
          </ol>
          <button class="wai-action-btn primary" onclick="openAddFriendModal()">➕ 加好友</button>
        </div>`);
}

// ── 加好友：email 精確搜尋 user_profiles → 送出邀請 ──
function openAddFriendModal() {
  if (!isLoggedIn) { openLogin(); return; }
  openWaiActionModal({
    title: '➕ 加好友',
    subtitle: '輸入對方的 email（對方需登入過 TravelLinkAI 才能被搜尋到）。',
    body(content) {
      content.innerHTML = `
        <div style="display:flex;gap:8px;">
          <input class="wai-action-input" id="friendSearchInput" type="email" placeholder="friend@example.com" style="flex:1;">
          <button class="wai-action-btn primary" data-action="search" style="flex-shrink:0;">搜尋</button>
        </div>
        <div class="wai-action-error" role="alert"></div>
        <div id="friendSearchResult"></div>`;
      const input = content.querySelector('#friendSearchInput');
      const error = content.querySelector('.wai-action-error');
      const resultHost = content.querySelector('#friendSearchResult');
      const doSearch = async () => {
        const q = String(input.value || '').trim();
        error.textContent = ''; resultHost.innerHTML = '';
        if (!q) { error.textContent = '請輸入 email。'; return; }
        if (q.toLowerCase() === String(currentUser.email).toLowerCase()) { error.textContent = '不能加自己為好友。'; return; }
        resultHost.innerHTML = '<div class="friends-row-sub">搜尋中…</div>';
        try {
          const profile = await WAI_FRIENDS.findProfileByEmail(q);
          if (!profile) {
            resultHost.innerHTML = '';
            error.textContent = '找不到這個 email。請確認拼字，或請對方在新版網站重新整理並登入一次。';
            return;
          }
          resultHost.innerHTML = `<div class="friends-row" style="margin-top:10px;">
            <div class="friends-row-main"><b>${profile.emoji ? escapeHtml(profile.emoji) + ' ' : ''}${escapeHtml(profile.name || profile.email)}</b><span class="friends-row-sub">${escapeHtml(profile.email)}</span></div>
            <button class="friends-mini-btn primary" data-action="invite">送出邀請</button>
          </div>`;
          resultHost.querySelector('[data-action="invite"]').onclick = async (ev) => {
            // async/await 之後 Event.currentTarget 會被瀏覽器清空；先保存按鈕參照，
            // 否則 Firestore 拒絕寫入時，錯誤處理本身會再丟出 null.disabled。
            const inviteBtn = ev.currentTarget;
            // UIUX#11：原本只 disabled 變灰，使用者看不出是在送出還是壞了
            const restoreInvite = window.waiSetBusy ? waiSetBusy(inviteBtn, '送出中…') : (inviteBtn.disabled = true, () => {});
            try {
              const inviteResult = await WAI_FRIENDS.sendInvite(
                { email: currentUser.email, name: currentUser.name },
                { email: profile.email, name: profile.name });
              closeWaiActionModal();
              showToast('✉️ 邀請已送出，等待對方確認', 'green');
              // F1：好友邀請通知（失敗靜默）
              if (window.WAI_NOTIFY) {
                try {
                  Promise.resolve(WAI_NOTIFY.push(profile.email, WAI_NOTIFY.nid(['friend_invite', inviteResult.id, inviteResult.cycleId]), {
                    type: 'friend_invite', fromName: currentUser.name || '', message: ''
                  })).catch(() => {});
                } catch (_notifyError) { /* 通知失敗不影響已成立的好友邀請 */ }
              }
            } catch (e) {
              // 成功路徑走 closeWaiActionModal()，按鈕已隨彈窗消失，不需要還原；
              // 失敗才把按鈕還原成可再按一次的狀態。
              restoreInvite();
              if (inviteBtn && inviteBtn.isConnected) inviteBtn.disabled = false;
              const message = e && e.code === 'permission-denied'
                ? '邀請未送出，好友權限尚未生效，請重新整理後再試。'
                : ((e && e.message) || '送出失敗，請稍後再試。');
              if (error && error.isConnected) error.textContent = message;
              else showToast(message, 'red');
            }
          };
        } catch (e) {
          resultHost.innerHTML = '';
          error.textContent = (e && e.message) || '搜尋失敗，請稍後再試。';
        }
      };
      content.querySelector('[data-action="search"]').onclick = doSearch;
      input.addEventListener('keydown', (ev) => { if (ev.key === 'Enter') doSearch(); });
    },
    onMount(modal) { setTimeout(() => modal.querySelector('#friendSearchInput').focus(), 0); }
  });
}

async function friendsAcceptInvite(friendshipDocId) {
  // 接受前先從 pending 名單記下邀請人（accept 後 onSnapshot 會把它移出 pending）
  const invite = friendsPendingIncoming.find(f => f.id === friendshipDocId);
  try {
    await WAI_FRIENDS.acceptInvite(friendshipDocId);
    showToast('🎉 已成為好友！', 'green');
    // F1：通知邀請人「對方接受了」（失敗靜默）
    if (window.WAI_NOTIFY && invite && invite.fromEmail) {
      WAI_NOTIFY.push(invite.fromEmail, WAI_NOTIFY.nid(['friend_accept', friendshipDocId, invite.cycleId || 'legacy']), {
        type: 'friend_accept', fromName: (currentUser && currentUser.name) || '', message: ''
      });
    }
  } catch (e) { showToast('接受失敗：' + ((e && e.message) || e), 'red'); }
}

// kind: 'reject'（拒絕收到的邀請）/ 'cancel'（取消已送邀請）/ 'unfriend'（解除好友，需確認）
async function friendsRemove(friendshipDocId, kind) {
  if (kind === 'unfriend' && !window.confirm('確定要解除這位好友嗎？（對方列表也會同步移除）')) return;
  try {
    await WAI_FRIENDS.removeFriendship(friendshipDocId);
    showToast(kind === 'unfriend' ? '已解除好友' : (kind === 'cancel' ? '已取消邀請' : '已拒絕邀請'), 'blue');
  } catch (e) { showToast('操作失敗：' + ((e && e.message) || e), 'red'); }
}

function openLogin() { document.getElementById('loginOverlay').classList.add('open'); }
function closeLogin() { document.getElementById('loginOverlay').classList.remove('open'); }
function switchAuthTab(tab) {
  document.getElementById('loginTab').classList.toggle('active', tab==='login');
  document.getElementById('registerTab').classList.toggle('active', tab==='register');
  document.getElementById('loginForm').style.display = tab==='login' ? '' : 'none';
  document.getElementById('registerForm').style.display = tab==='register' ? '' : 'none';
}

function isValidEmail(s){ return /^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(s); }

// 資安：把 Firebase 原始錯誤轉成「不可區分」的通用訊息。
// 絕不能把 e.message 直接秀給使用者——「user-not-found」vs「wrong-password」的差異
// 會讓攻擊者列舉出哪些 email 有註冊（撞庫前的偵察）。登入失敗一律同一句話。
function authErrorMessage(e, kind) {
  const code = (e && e.code) || '';
  if (code === 'auth/too-many-requests') return '嘗試次數過多，帳號已暫時鎖定，請稍後再試。';
  if (code === 'auth/network-request-failed') return '網路連線異常，請檢查網路後再試。';
  if (kind === 'register') {
    if (code === 'auth/email-already-in-use') return '這個 Email 無法使用，請改用其他信箱，或直接嘗試登入。';
    if (code === 'auth/weak-password') return '密碼強度不足，請使用至少 8 個字元並混合英數。';
    if (code === 'auth/invalid-email') return 'Email 格式不正確。';
    return '註冊失敗，請稍後再試。';
  }
  // 登入：帳號不存在／密碼錯誤／憑證無效 → 一律同一句，不洩漏差異
  return '帳號或密碼錯誤，請確認後再試。';
}

// 信箱驗證只屬於註冊流程；既有帳號登入時不再以 emailVerified 阻擋。
function isUnverifiedPasswordUser(user) {
  return !!user && !user.emailVerified
    && (user.providerData || []).some(p => p && p.providerId === 'password');
}

async function doLogin() {
  if (!firebaseEnabled || !firebaseAuth) return showToast('Firebase 尚未初始化', 'orange');
  const email = document.getElementById('loginEmail').value.trim();
  const pwd = document.getElementById('loginPwd').value;
  if (!email || !pwd) { showToast('請填寫帳號和密碼', 'orange'); return; }
  if (!isValidEmail(email)) { showToast('請輸入正確的電子信箱格式', 'orange'); return; }
  try {
    await firebaseAuth.signInWithEmailAndPassword(email, pwd);
    showToast(`👋 歡迎回來！`, 'green');
    closeLogin();
  } catch(e) {
    showToast(authErrorMessage(e, 'login'), 'red');
  }
}

async function doRegister() {
  if (!firebaseEnabled || !firebaseAuth) return showToast('Firebase 尚未初始化', 'orange');
  const name = document.getElementById('regName').value.trim();
  const email = document.getElementById('regEmail').value.trim();
  const pwd = document.getElementById('regPwd').value;
  if (!name || !email || !pwd) { showToast('請填寫所有欄位', 'orange'); return; }
  if (!isValidEmail(email)) { showToast('請輸入正確的電子信箱格式', 'orange'); return; }
  if (pwd.length < 8) { showToast('密碼至少需要 8 個字元', 'orange'); return; }
  try {
    const userCredential = await firebaseAuth.createUserWithEmailAndPassword(email, pwd);
    const user = userCredential.user;
    if (firebaseDb) {
      await firebaseDb.collection('users').doc(user.uid).set({
        uid: user.uid,
        email: email,
        name: name,
        emoji: '🌟',
        preferences: { interests: [], pace: '平衡', avoid: '', avoidTags: [] },
        visitedSpots: [],
        createdAt: firebase.firestore.FieldValue.serverTimestamp(),
        updatedAt: firebase.firestore.FieldValue.serverTimestamp()
      });
      // Week4 D1：同步公開檔案（好友搜尋靠它）——註冊後就能被搜尋到，失敗不擋註冊
      if (window.WAI_FRIENDS) {
        try { await WAI_FRIENDS.syncMyProfile({ uid: user.uid, email, name, emoji: '🌟' }); }
        catch (_e) { /* 登入時 onAuthStateChanged 會再同步一次 */ }
      }
    }
    await user.sendEmailVerification();
    await firebaseAuth.signOut();
    showToast('註冊完成！驗證信已寄出，請到信箱完成驗證。', 'green');
    closeLogin();
  } catch(e) {
    if (isUnverifiedPasswordUser(firebaseAuth.currentUser)) {
      try { await firebaseAuth.signOut(); } catch (_e) {}
    }
    showToast(authErrorMessage(e, 'register'), 'red');
  }
}

// === 修改密碼（僅 Email/密碼帳號） ===
function openChangePwd() {
  const u = (typeof firebaseAuth !== 'undefined' && firebaseAuth) ? firebaseAuth.currentUser : null;
  if (!u) return showToast('請先登入', 'orange');
  const hasPwd = (u.providerData || []).some(p => p && p.providerId === 'password');
  if (!hasPwd) return showToast('你以社群帳號登入，請至 Google／Facebook 修改密碼', 'orange');
  ['cpwCurrent', 'cpwNew', 'cpwConfirm'].forEach(id => { const el = document.getElementById(id); if (el) el.value = ''; });
  document.getElementById('changePwdOverlay').classList.add('open');
}
function closeChangePwd() { document.getElementById('changePwdOverlay').classList.remove('open'); }
async function doChangePassword() {
  if (!firebaseEnabled || !firebaseAuth) return showToast('Firebase 尚未初始化', 'orange');
  const u = firebaseAuth.currentUser;
  if (!u) return showToast('請先登入', 'orange');
  const cur = document.getElementById('cpwCurrent').value;
  const np = document.getElementById('cpwNew').value;
  const cf = document.getElementById('cpwConfirm').value;
  if (!cur || !np || !cf) { showToast('請填寫所有欄位', 'orange'); return; }
  if (np.length < 8) { showToast('新密碼至少需要 8 個字元', 'orange'); return; }
  if (np !== cf) { showToast('兩次輸入的新密碼不一致', 'orange'); return; }
  if (np === cur) { showToast('新密碼不可與目前密碼相同', 'orange'); return; }
  try {
    const cred = firebase.auth.EmailAuthProvider.credential(u.email, cur);
    await u.reauthenticateWithCredential(cred);
    await u.updatePassword(np);
    showToast('🔒 密碼已更新', 'green');
    closeChangePwd();
  } catch(e) {
    const code = e && e.code;
    let msg;
    if (code === 'auth/wrong-password' || code === 'auth/invalid-credential') msg = '目前密碼不正確';
    else if (code === 'auth/weak-password') msg = '新密碼強度不足';
    else if (code === 'auth/too-many-requests') msg = '嘗試次數過多，請稍後再試';
    else msg = '密碼更新失敗：' + ((e && e.message) || '未知錯誤');
    showToast(msg, 'red');
  }
}

async function doSocialLogin(providerName) {
  if (!firebaseEnabled || !firebaseAuth) return showToast('Firebase 尚未初始化', 'orange');
  try {
    let provider = null;
    if (providerName === 'Google') provider = new firebase.auth.GoogleAuthProvider();
    else if (providerName === 'Facebook') provider = new firebase.auth.FacebookAuthProvider();
    else return;
    
    const userCredential = await firebaseAuth.signInWithPopup(provider);
    const user = userCredential.user;
    
    if (firebaseDb) {
      const docRef = firebaseDb.collection('users').doc(user.uid);
      const doc = await docRef.get();
      if (!doc.exists) {
        await docRef.set({
          uid: user.uid,
          email: user.email || '',
          name: user.displayName || '社群用戶',
          emoji: providerName === 'Google' ? '🌐' : '📘',
          preferences: { interests: [], pace: '平衡', avoid: '', avoidTags: [] },
          visitedSpots: [],
          createdAt: firebase.firestore.FieldValue.serverTimestamp(),
          updatedAt: firebase.firestore.FieldValue.serverTimestamp()
        });
        openPrefWizard(); // 新用戶引導設定
      }
    }
    showToast(`✅ 已透過 ${providerName} 登入`, 'green');
    closeLogin();
  } catch(e) {
    // 社群登入取消/失敗：也不洩漏原始錯誤（popup-closed-by-user 等由使用者自行重試）
    const code = (e && e.code) || '';
    showToast(code === 'auth/popup-closed-by-user' ? '已取消登入' : authErrorMessage(e, 'login'), 'red');
  }
}

async function doLogout() {
  try {
    if (firebaseAuth) await firebaseAuth.signOut();
    showToast('已登出，跳轉回首頁…');
    setTimeout(() => { window.location.href = 'ai-travel-explore-final.html'; }, 800);
  } catch (err) {
    console.error('登出失敗：', err);
    showToast('登出失敗，請稍後再試。', 'red');
  }
}

// === Preferences Wizard ===
let tempPrefs = { interests: [], pace: '平衡', avoid: '', avoidTags: [] };
function openPrefWizard() {
  // 編輯時帶入既有偏好（之前每次開啟都會清空，導致無法修改）
  const src = (currentUser && currentUser.preferences) || {};
  tempPrefs = {
    interests: Array.isArray(src.interests) ? [...src.interests] : [],
    pace: src.pace || '平衡',
    avoid: typeof src.avoid === 'string' ? src.avoid : '',
    avoidTags: Array.isArray(src.avoidTags) ? [...src.avoidTags] : []
  };
  renderPrefWizard();
  document.getElementById('prefWizardOverlay').classList.add('open');
}
function closePrefWizard() { document.getElementById('prefWizardOverlay').classList.remove('open'); }
function togglePrefInterest(btn, int) {
  const idx = tempPrefs.interests.indexOf(int);
  if (idx > -1) {
    tempPrefs.interests.splice(idx, 1);
    btn.classList.remove('active');
  } else {
    if (tempPrefs.interests.length >= 3) return showToast('最多選擇三個興趣');
    tempPrefs.interests.push(int);
    btn.classList.add('active');
  }
}
function setPrefPace(pace) {
  tempPrefs.pace = pace;
  document.querySelectorAll('#prefPaceGrid .wizard-tag').forEach(btn => {
    btn.classList.toggle('active', btn.dataset.pace === pace);
  });
}
function togglePrefAvoidTag(btn, tag) {
  if (!Array.isArray(tempPrefs.avoidTags)) tempPrefs.avoidTags = [];
  const idx = tempPrefs.avoidTags.indexOf(tag);
  if (idx > -1) {
    tempPrefs.avoidTags.splice(idx, 1);
    btn.classList.remove('active');
  } else {
    tempPrefs.avoidTags.push(tag);
    btn.classList.add('active');
  }
}
// \u4f9d\u56fa\u5b9a\u6a19\u7c64\u5b57\u5178\uff08avoid-tags.js\uff09\u6e32\u67d3\u53ef\u9ede\u9078\u7684\u7981\u5fcc chip\uff0c\u4e26\u4f9d\u76ee\u524d\u9078\u53d6\u72c0\u614b\u6a19 active
function renderAvoidTagChips() {
  const host = document.getElementById('prefAvoidTags');
  if (!host) return;
  const groups = Array.isArray(window.WAI_AVOID_TAGS) ? window.WAI_AVOID_TAGS : [];
  const selected = Array.isArray(tempPrefs.avoidTags) ? tempPrefs.avoidTags : [];
  host.innerHTML = groups.map(g => `
    <div style="margin-bottom:6px">
      <div style="font-size:13px;color:#8fa4b8;font-weight:700;margin-bottom:4px">${g.group}</div>
      <div class="wizard-chips">
        ${(g.items || []).map(it => `
          <button class="wizard-tag${selected.includes(it.tag) ? ' active' : ''}" type="button"
            onclick="togglePrefAvoidTag(this, '${it.tag}')">${it.label}</button>
        `).join('')}
      </div>
    </div>
  `).join('');
}
function renderPrefWizard() {
  setPrefPace(tempPrefs.pace);
  document.querySelectorAll('#prefInterestsGrid .wizard-tag').forEach(btn => {
    const int = btn.textContent.replace(/[^\u4e00-\u9fa5]/g, '').trim();
    btn.classList.toggle('active', tempPrefs.interests.includes(int));
  });
  renderAvoidTagChips();
  const av = document.getElementById('prefAvoid');
  if (av) av.value = tempPrefs.avoid || '';
}
async function saveUserPreferences() {
  if (!isLoggedIn || !currentUser || !firebaseDb) return closePrefWizard();
  try {
    const uid = firebaseAuth.currentUser?.uid;
    if (uid) {
      await firebaseDb.collection('users').doc(uid).set({
        preferences: tempPrefs,
        updatedAt: firebase.firestore.FieldValue.serverTimestamp()
      }, { merge: true });
    }
    currentUser.preferences = { ...tempPrefs };
    localStorage.setItem('wai_user', JSON.stringify({ isLoggedIn, currentUser }));
    showToast('✨ 喜好設定已儲存', 'green');
    closePrefWizard();
  } catch(e) {
    showToast('儲存失敗', 'red');
  }
}

// === Auth State Listener ===
if (typeof firebase !== 'undefined') {
  firebase.auth().onAuthStateChanged(async (user) => {
    if (user) {
      isLoggedIn = true;
      let userData = {
        name: user.displayName || user.email?.split('@')[0] || '使用者',
        email: user.email,
        emoji: '😊',
        preferences: { interests: [], pace: '平衡', avoid: '', avoidTags: [] },
        visitedSpots: []
      };
      if (firebaseDb) {
        try {
          const doc = await firebaseDb.collection('users').doc(user.uid).get();
          if (doc.exists) {
            const data = doc.data();
            userData = { ...userData, ...data };
            // 身分 email 一律以 Firebase Auth 為準：users/{uid} 註冊時寫入的 email 可能已過期
            //（使用者改過 Auth email），被展開覆蓋後會害帳號鍵與訪客搬移落到舊 email。
            userData.email = user.email;
            if (data.visitedSpots && Array.isArray(data.visitedSpots)) {
              localStorage.setItem('wai_visited_places', JSON.stringify(data.visitedSpots));
            }
          }
        } catch(e) { console.warn('無法讀取使用者資料', e); }
      }
      currentUser = userData;
      localStorage.setItem('wai_user', JSON.stringify({ isLoggedIn, currentUser }));
      // Week4 D1：同步公開檔案（好友搜尋靠它）＋啟動好友/群組即時訂閱（fire-and-forget，失敗不擋登入）
      if (firebaseDb && window.WAI_FRIENDS) {
        WAI_FRIENDS.syncMyProfile({ uid: user.uid, email: user.email, name: userData.name, emoji: userData.emoji })
          .catch((e) => console.warn('同步公開檔案失敗：', e));
        startFriendsSubscriptions(user.email);
      }
      startNotifSubscription(user.email); // F1 通知中心：登入後啟動訂閱
      // Auth 已確認真實帳號、currentUser 已指派 → 此時才把訪客行程一次性搬進該帳號
      //（審查 Bug1：原本在 loadState 依過期的 wai_user 快取就搬，會把訪客行程搬給錯的帳號並清掉訪客鍵）。
      migrateGuestTripsToUser();
      await loadState();
      renderUserMenu();
      renderGrid();

      // 檢查是否為剛從 landing page 註冊跳轉進來的
      if (sessionStorage.getItem('wai_just_registered')) {
        sessionStorage.removeItem('wai_just_registered');
        setTimeout(openPrefWizard, 500); // 稍微延遲一下，等畫面渲染好
      }
    } else {
      if (myCollabTripsUnsub) { myCollabTripsUnsub(); myCollabTripsUnsub = null; }
      stopFriendsSubscriptions(); // Week4 D1：登出清訂閱，比照 myCollabTripsUnsub
      stopNotifSubscription(); // F1 通知中心：登出清訂閱＋藏鈴鐺
      isLoggedIn = false;
      currentUser = null;
      localStorage.removeItem('wai_user');
      // 依帳號隔離後：此刻記憶體裡的 myTrips 仍是剛登出帳號的，直接 saveState 會寫進「訪客鍵」
      // 反向外洩給下一個登入者。改成先讀回訪客鍵的內容，再存（等於不動訪客資料）。
      try { myTrips = JSON.parse(localStorage.getItem('wai_mytrips') || '[]').map(serializeTripForStorage); }
      catch (_e) { myTrips = []; }
      saveState();
      renderUserMenu();
      renderGrid();
    }
  });
}

// ══════════════════════════════════════════════════
// EXPLORE GRID
// ══════════════════════════════════════════════════
function tagColorClass(tag) {
  if (['自然','親子','海岸'].includes(tag)) return 'g';
  if (['美食','低預算'].includes(tag)) return 'o';
  if (['文化','深度遊'].includes(tag)) return 'b';
  if (['都市','購物','蜜月','單人','文青'].includes(tag)) return 'p';
  return 'gold';
}
// ── 官方精選範本：初始化與渲染 ──────────────────────────────
// poi-data.js 與 explore-templates.js 都是 defer，要等 DOM 就緒後才有值。
function initOfficialTemplates() {
  if (typeof WAI_TEMPLATES === 'undefined' || !WAI_TEMPLATES) {
    console.warn('[templates] explore-templates.js 未載入，官方精選範本停用');
    return;
  }
  const poi = (typeof window !== 'undefined' && window.WAI_POI_DATA) ? window.WAI_POI_DATA : null;
  if (!poi) {
    console.warn('[templates] poi-data.js 未載入，官方精選範本停用');
    return;
  }
  DISTRICT_BUCKETS = WAI_TEMPLATES.bucketByDistrict(poi);
  OFFICIAL_TEMPLATES = WAI_TEMPLATES.GROUPS
    .reduce((acc, g) => acc.concat(g.keys), [])
    .map((k) => WAI_TEMPLATES.buildTemplate(k, DISTRICT_BUCKETS))
    .filter(Boolean);
}

// 首頁只放 6 張（桌機 3 欄＝兩排）。首頁要的是「挑一個開始」，不是把 16 區攤成型錄。
const HOME_PICKS = ['台東', '綠島', '蘭嶼', '卑南', '成功', '東河'];

function getFiltered() {
  if (activeCat !== 'all') return OFFICIAL_TEMPLATES.filter((t) => t.key === activeCat);
  if (searchQ) {
    const q = searchQ.toLowerCase();
    return OFFICIAL_TEMPLATES.filter((t) =>
      t.key.includes(q) || t.title.toLowerCase().includes(q) || t.group.includes(q)
      || t.stops.some((s) => String(s.name).toLowerCase().includes(q)));
  }
  const picked = HOME_PICKS.map((k) => OFFICIAL_TEMPLATES.find((t) => t.key === k)).filter(Boolean);
  return picked.length ? picked : OFFICIAL_TEMPLATES.slice(0, 6);
}

function renderGrid() {
  const grid = document.getElementById('tripsGrid');
  if (!grid) return;
  const list = getFiltered();
  const note = document.getElementById('secNote');
  if (note) {
    note.textContent = !OFFICIAL_TEMPLATES.length ? ''
      : (activeCat === 'all' ? '挑一份直接開始，或切上面的地區看別的' : '目前只看「' + activeCat + '」');
  }
  if (!list.length) {
    grid.innerHTML = '<div style="grid-column:1/-1;text-align:center;padding:60px;color:var(--ink3)">'
      + '<div style="font-size:48px;margin-bottom:12px">\u{1F5FA}</div>'
      + '<div>' + (OFFICIAL_TEMPLATES.length ? '這個地區還沒有足夠的景點資料' : '景點資料尚未載入') + '</div></div>';
    return;
  }
  grid.innerHTML = list.map((t) => {
    const gate = WAI_TEMPLATES.GATE.name;
    const last = t.stops[t.stops.length - 1];
    const routeText = t.island ? '島上環線'
      : escapeHtml(gate) + ' <i>→</i> ' + escapeHtml(last.name) + ' <i>→</i> 返回';
    const stopsHtml = t.stops.map((s) =>
      '<div class="tpl-stop' + (s.via ? ' via' : '') + '">'
      + '<span class="tpl-dot"></span>'
      + '<span class="tpl-stop-name">' + escapeHtml(s.name) + '</span>'
      + '<span class="tpl-kind ' + (s.via ? 'via' : 'main') + '">'
      + (s.via ? escapeHtml(s.fromDistrict || '') + '・沿途' : '主軸') + '</span></div>').join('');
    const mixHtml = t.island
      ? '島上 <b>' + t.mainCount + '</b> 站環線，沒有陸路往返，不需要沿途補點。'
      : '<b>' + t.mainCount + '</b> 站在' + escapeHtml(t.key) + '，<b>' + t.viaCount + '</b> 站是'
        + escapeHtml(gate) + '往返途中順路加入的。';
    const farHtml = t.farForOneDay
      ? '<br><span class="tpl-far">⚠️ 來回 ' + Math.round(t.km) + ' km，當天往返太趕，建議排兩日。</span>'
      : '';
    // 「4/4」單看是無字天書。補 title／aria-label 說明那是「幾站有 Google 評分」，
    // 平均分只由有評分的站算出來，沒評分的站不會被當成 0 分拉低平均。
    const ratingHint = t.ratedCount + ' 站有 Google 評分（共 ' + t.stops.length + ' 站），平均 ' + t.rating.toFixed(1) + ' 星';
    const ratingHtml = t.rating
      ? '<span class="tpl-fact" title="' + escapeHtml(ratingHint) + '" aria-label="' + escapeHtml(ratingHint) + '">★ <b>' + t.rating.toFixed(1) + '</b>'
        + '<span class="tpl-rated-n">' + t.ratedCount + '/' + t.stops.length + '</span></span>'
      : '';
    // 站點清單預設收合：一張卡列 5 站會把「停留時長／評分／預覽」推到很下面，
    // 首頁一次放 6 張就變得很長。標題列右上角的箭頭負責展開。
    const stopsId = 'tplstops-' + String(t.key).split('').map(function (ch) {
      return ch.charCodeAt(0).toString(36);
    }).join('');
    const isOpen = expandedTplKeys.has(t.key);
    return '<article class="tpl-card">'
      + '<div class="tpl-cover">'
      + WAI_TEMPLATES.routeSvg(t, t.key)
      + '<div class="tpl-cover-top">'
      + '<span class="tpl-chip official">✦ 官方精選</span>'
      + '<span class="tpl-chip' + (t.farForOneDay ? ' far' : '') + '">'
      + t.stops.length + ' 站 · ' + Math.round(t.km) + ' km</span></div>'
      + '<div class="tpl-cover-foot">'
      + '<span class="tpl-region">' + escapeHtml(t.emoji + ' ' + t.key) + '</span>'
      + '<span class="tpl-route">' + routeText + '</span></div></div>'
      + '<div class="tpl-body">'
      + '<div class="tpl-title-row">'
      + '<h3 class="tpl-title">' + escapeHtml(t.title) + '</h3>'
      + '<button type="button" class="tpl-toggle" aria-expanded="' + (isOpen ? 'true' : 'false') + '"'
      + ' aria-controls="' + stopsId + '"'
      + ' data-tpl-key="' + escapeHtml(t.key) + '"'
      + ' aria-label="' + (isOpen ? '收合' : '展開') + escapeHtml(t.key) + '的景點清單"'
      + ' onclick="toggleTplStops(this)">'
      + '<svg class="tpl-chev" viewBox="0 0 16 16" width="14" height="14" aria-hidden="true" focusable="false">'
      + '<path d="M4 6.5L8 10.5L12 6.5" fill="none" stroke="currentColor" stroke-width="2"'
      + ' stroke-linecap="round" stroke-linejoin="round"/></svg></button>'
      + '</div>'
      + '<div class="tpl-stops" id="' + stopsId + '"' + (isOpen ? '' : ' hidden') + '>' + stopsHtml + '</div>'
      + '<p class="tpl-mix">' + mixHtml + farHtml + '</p>'
      + '<div class="tpl-facts">'
      + '<span class="tpl-fact">⏱ 停留 <b>' + WAI_TEMPLATES.fmtDuration(t.stayMin) + '</b></span>'
      + ratingHtml
      + '<button class="tpl-btn" onclick="openPreview(\'' + escapeHtml(t.key) + '\')">預覽</button>'
      + '</div></div></article>';
  }).join('');
}

// 哪幾張卡是展開的。
// 必須記在模組層級：renderGrid 會因為 auth 狀態變化、切換地區分頁等原因重跑，
// 整個 innerHTML 重建。不記的話使用者展開後只要有任何重繪就被收回去——
// 實測 Firebase onAuthStateChanged 在載入後幾百毫秒才回，剛好把剛展開的卡收掉。
const expandedTplKeys = new Set();

// 展開／收合單張卡的景點清單。
// 箭頭用 CSS rotate：收合時轉 90° 指向左邊，展開時回正指向下方。
window.toggleTplStops = function (btn) {
  if (!btn) return;
  const panel = document.getElementById(btn.getAttribute('aria-controls'));
  if (!panel) return;
  const willOpen = panel.hasAttribute('hidden');
  if (willOpen) panel.removeAttribute('hidden');
  else panel.setAttribute('hidden', '');
  btn.setAttribute('aria-expanded', willOpen ? 'true' : 'false');
  const key = (btn.dataset && btn.dataset.tplKey) || '';
  if (key) { if (willOpen) expandedTplKeys.add(key); else expandedTplKeys.delete(key); }
  btn.setAttribute('aria-label', (willOpen ? '收合' : '展開') + key + '的景點清單');
};

// ── 行政區分頁（依地理分組） ──
// 全部分頁排成「一橫排」，地理分組改用行內的組別標籤當分隔。
// 原本一組一列（共 5 列）太佔版面，把行程卡整個推到摺線下面。
// 橫排放不下時可左右捲動；分組資訊仍保留，只是從「列」變成「分隔標籤」。
function renderDistrictTabs() {
  const host = document.getElementById('districtTabs');
  if (!host || typeof WAI_TEMPLATES === 'undefined') return;
  const count = (k) => (DISTRICT_BUCKETS[k] || []).length;
  const parts = ['<button class="dtab' + (activeCat === 'all' ? ' on' : '') + '" data-k="all">全部</button>'];
  WAI_TEMPLATES.GROUPS.forEach((g) => {
    const keys = g.keys.filter(count);
    if (!keys.length) return;
    parts.push('<span class="grouplab">' + escapeHtml(g.label) + '</span>');
    keys.forEach((k) => {
      parts.push('<button class="dtab' + (k === activeCat ? ' on' : '')
        + (count(k) < 10 ? ' thin' : '') + '" data-k="' + escapeHtml(k) + '">'
        + escapeHtml(k) + '<b>' + count(k) + '</b></button>');
    });
  });
  host.innerHTML = '<div class="tabrow">' + parts.join('') + '</div>';
}

function onDistrictTabClick(e) {
  const btn = e.target.closest('.dtab');
  if (!btn) return;
  activeCat = btn.dataset.k;
  searchQ = '';
  document.getElementById('searchInput').value = '';
  renderDistrictTabs();
  renderGrid();
}

// ── 側欄：可規劃的目的地（台東本島合計 / 綠島 / 蘭嶼） ──
function renderDestSidebar() {
  const host = document.getElementById('destSidebar');
  if (!host || typeof WAI_TEMPLATES === 'undefined') return;
  const islands = ['綠島', '蘭嶼'];
  const allKeys = WAI_TEMPLATES.GROUPS.reduce((acc, g) => acc.concat(g.keys), []);
  const mainlandKeys = allKeys.filter((k) => islands.indexOf(k) < 0);
  const mainland = mainlandKeys.reduce((a, k) => a + (DISTRICT_BUCKETS[k] || []).length, 0);
  const townCount = mainlandKeys.filter((k) => (DISTRICT_BUCKETS[k] || []).length).length;
  const rows = [
    { k: '台東', emoji: '\u{1F30A}', n: mainland, sub: '含 ' + townCount + ' 個鄉鎮市' },
    { k: '綠島', emoji: '\u{1F3DD}', n: (DISTRICT_BUCKETS['綠島'] || []).length, sub: '船班往返' },
    { k: '蘭嶼', emoji: '⛰', n: (DISTRICT_BUCKETS['蘭嶼'] || []).length, sub: '船班往返' }
  ].filter((r) => r.n);
  if (!rows.length) { host.innerHTML = ''; return; }
  const max = Math.max.apply(null, rows.map((r) => r.n));
  host.innerHTML = rows.map((r) =>
    '<div class="dest-row" onclick="filterCatByName(\'' + escapeHtml(r.k) + '\')">'
    + '<div class="dest-mark">' + r.emoji + '</div>'
    + '<div class="dest-body">'
    + '<div class="dest-name">' + escapeHtml(r.k) + '</div>'
    + '<div class="dest-sub">' + escapeHtml(r.sub) + '</div>'
    + '<div class="dest-bar"><i style="width:' + Math.round(r.n / max * 100) + '%"></i></div></div>'
    + '<div class="dest-n">' + r.n + '</div></div>').join('');
}

// ── Hero 數據：實算，取代原本寫死的 2,481 / 18,340 / 4.8 ──
function renderHeroStats() {
  const poi = (typeof window !== 'undefined' && window.WAI_POI_DATA) ? window.WAI_POI_DATA : null;
  if (!poi) return;
  const all = Object.keys(poi).filter((k) => Array.isArray(poi[k]))
    .reduce((a, k) => a.concat(poi[k]), []);
  const seen = new Set();
  const uniq = all.filter((p) => {
    if (!p || !p.name || seen.has(p.name)) return false;
    seen.add(p.name); return true;
  });
  const rated = uniq.filter((p) => Number(p.rating) > 0);
  const towns = Object.keys(DISTRICT_BUCKETS).length;
  const set = (id, v) => { const el = document.getElementById(id); if (el) el.textContent = v; };
  set('hstatSpots', uniq.length.toLocaleString('en-US'));
  set('hstatDistricts', towns || '--');
  set('totalRating', rated.length
    ? (rated.reduce((a, p) => a + Number(p.rating), 0) / rated.length).toFixed(1) : '--');
}

// 從首頁的行程卡開啟。沿用「我的微旅行」頁既有的行為：
// 生成到一半的個人行程要繼續生成，不能直接跳去 planner（那邊會拿到空行程）。
function openMyTrip(id) {
  const t = (myTrips || []).find((x) => String(x.id) === String(id));
  if (!t) return;
  if (typeof isUnfinishedPersonalTrip === 'function' && isUnfinishedPersonalTrip(t)
      && typeof resumePersonalTripGeneration === 'function') {
    resumePersonalTripGeneration(t.id);
    return;
  }
  window.location = 'ai-travel-planner-v8.html?id=' + encodeURIComponent(t.id);
}

// ── 首頁的「我的行程」區塊 ──
function renderHomeMyTrips() {
  const host = document.getElementById('homeMyTrips');
  const note = document.getElementById('myTripsNote');
  if (!host) return;
  if (!isLoggedIn) {
    if (note) note.textContent = '';
    host.innerHTML = '<div class="home-mt-empty">'
      + '<div class="ico">\u{1F5FA}</div>'
      + '<p>登入後，你生成的行程會留在這裡，換裝置也看得到。<br>'
      + '也可以直接挑上面的範本開始規劃。</p>'
      + '<button onclick="openLogin()">登入 / 註冊</button></div>';
    return;
  }
  const list = (myTrips || []).slice()
    .sort((a, b) => String(b.id).localeCompare(String(a.id)))
    .slice(0, 6);
  if (note) note.textContent = list.length ? ('最近 ' + list.length + ' 份') : '';
  if (!list.length) {
    host.innerHTML = '<div class="home-mt-empty">'
      + '<div class="ico">✨</div>'
      + '<p>還沒有行程。挑一份上面的精選範本開始，或用 AI 規劃你自己的。</p>'
      + '<button onclick="openWizard()">＋ 建立微旅行</button></div>';
    return;
  }
  const label = (typeof MT_STATUS_LABEL !== 'undefined' && MT_STATUS_LABEL) ? MT_STATUS_LABEL : {};
  host.innerHTML = '<div class="home-mt-grid">' + list.map((t) => {
    const stops = Array.isArray(t.stops) ? t.stops.length : 0;
    return '<div class="home-mt">'
      + '<div class="home-mt-top">'
      + '<div class="home-mt-emoji">' + escapeHtml(t.emoji || '\u{1F9ED}') + '</div>'
      + '<div style="min-width:0">'
      + '<div class="home-mt-name">' + escapeHtml(t.title || '未命名行程') + '</div>'
      + '<div class="home-mt-when">' + escapeHtml(t.createdAt || '') + '</div></div>'
      + '<span class="home-mt-status tc-tag ' + tagColorClass(t.status || '') + '">'
      + escapeHtml(label[t.status] || '規劃中') + '</span></div>'
      + '<div class="home-mt-meta">'
      + '<span>\u{1F4CD} ' + (stops ? stops + ' 站' : '尚未生成') + '</span>'
      + '<span>\u{1F5D3} ' + escapeHtml(String(t.days || '--')) + '</span>'
      + '<span>\u{1F465} ' + escapeHtml(String(t.people || '--')) + '</span></div>'
      + '<div class="home-mt-acts">'
      + '<button class="go" onclick="openMyTrip(\'' + escapeHtml(String(t.id)) + '\')">開啟</button>'
      + '</div></div>';
  }).join('') + '</div>';
}

function filterCat(btn, cat) {
  activeCat = cat;
  renderDistrictTabs();
  renderGrid();
}
// 側欄的「台東」指的是整個本島（14 個鄉鎮的合計），沒有單一分頁對應它，
// 所以視為回到「全部」；綠島／蘭嶼本身就是分頁，直接切過去。
function filterCatByName(cat) {
  const hasTab = !!(DISTRICT_BUCKETS && DISTRICT_BUCKETS[cat]
    && DISTRICT_BUCKETS[cat].length && cat !== '台東');
  activeCat = hasTab ? cat : 'all';
  searchQ = '';
  const input = document.getElementById('searchInput');
  if (input) input.value = '';
  renderDistrictTabs();
  renderGrid();
  const grid = document.getElementById('tripsGrid');
  if (grid && grid.scrollIntoView) grid.scrollIntoView({ behavior: 'smooth', block: 'start' });
}
// 搜尋 debounce：oninput 每鍵都整格 innerHTML 重建，清單長時輸入卡頓
let _searchDebounceTimer = null;
function handleSearch() {
  searchQ = document.getElementById('searchInput').value.trim();
  clearTimeout(_searchDebounceTimer);
  _searchDebounceTimer = setTimeout(renderGrid, 250);
}
function searchTag(tag) {
  activeCat = 'all';
  searchQ = tag;
  document.getElementById('searchInput').value = tag;
  renderDistrictTabs();
  renderGrid();
}

// ══════════════════════════════════════════════════
// PREVIEW MODAL
// ══════════════════════════════════════════════════
function openPreview(key) {
  const t = OFFICIAL_TEMPLATES.find((x) => x.key === key);
  if (!t) return;
  currentPreviewId = key;
  const hdr = document.getElementById('pmHeader');
  const THEME_CC = { '海線': 'c0', '市區': 'c1', '縱谷': 'c0', '南迴': 'c3', '離島': 'c2' };
  hdr.className = 'pm-header ' + (THEME_CC[t.group] || 'c0');
  document.getElementById('pmTitle').textContent = t.title;
  document.getElementById('pmTags').innerHTML =
    [t.group, t.island ? '離島' : '本島', t.farForOneDay ? '建議兩日' : '一日']
      .map((tag) => '<span class="pm-tag">' + escapeHtml(tag) + '</span>').join('');
  document.getElementById('pmStats').innerHTML =
    '<div><div class="pmstat-val">' + t.stops.length + '</div><div class="pmstat-label">站</div></div>'
    + '<div><div class="pmstat-val">' + Math.round(t.km) + '</div><div class="pmstat-label">公里</div></div>'
    + '<div><div class="pmstat-val">' + WAI_TEMPLATES.fmtDuration(t.stayMin) + '</div><div class="pmstat-label">停留</div></div>';
  document.getElementById('pmAuthorSub').textContent = t.island
    ? ('島上 ' + t.mainCount + ' 站環線')
    : (t.mainCount + ' 站在' + t.key + '，' + t.viaCount + ' 站沿途順路');

  // 時間是「從台東車站出發後依停留時長累加」的示意，不是排好的行程——
  // 真正的時間要等 AI 依營業時間與車程重排，所以這裡標明是預估。
  let clock = 9 * 60;
  const rows = t.stops.map((s2) => {
    const hh = String(Math.floor(clock / 60) % 24).padStart(2, '0');
    const mm = String(clock % 60).padStart(2, '0');
    clock += (s2.duration || 45) + 20;   // 停留 + 粗估車程
    const kind = s2.via ? 'transit' : 'sight';
    const kindLabel = s2.via ? ((s2.fromDistrict || '') + '・沿途') : '主軸';
    // 「主軸／沿途」是內部用語，卡片上沒有圖例。補 title 讓滑過去就看得懂。
    const kindHint = s2.via
      ? '沿途順路：不在' + t.key + '，是往返台東車站途中順道經過的' + (s2.fromDistrict || '鄰近鄉鎮') + '景點'
      : '主軸景點：位在' + t.key + '，是這份範本的主要目的地';
    return '<div class="pm-spot">'
      + '<span class="pm-spot-emoji">' + (s2.via ? '\u{1F697}' : '\u{1F4CD}') + '</span>'
      + '<span class="pm-spot-name">' + escapeHtml(s2.name) + '</span>'
      + '<span class="pm-spot-time">' + hh + ':' + mm + '</span>'
      + '<span class="pm-spot-tag ' + kind + '" title="' + escapeHtml(kindHint) + '" aria-label="' + escapeHtml(kindHint) + '">' + escapeHtml(kindLabel) + '</span>'
      + '</div>';
  }).join('');
  document.getElementById('pmBody').innerHTML =
    '<div class="pm-day">'
    + '<div class="pm-day-hdr">'
    + '<div class="pm-day-icon">' + t.emoji + '</div>'
    + '<div><div class="pm-day-label">' + escapeHtml(t.key) + '</div>'
    + '<div class="pm-day-title">' + escapeHtml(t.group) + '・'
    + escapeHtml(t.island ? '島上環線' : (WAI_TEMPLATES.GATE.name + ' 往返')) + '</div></div></div>'
    + '<div class="pm-spots">' + rows + '</div>'
    + '<p style="font-size:13px;color:var(--ink3);margin:10px 4px 0;line-height:1.7;text-wrap:pretty;">'
    + 'ℹ 時間為依停留時長累加的預估；實際行程會由 AI 依各站營業時間與車程重新安排。</p>'
    + '</div>';
  updatePmCopy();
  document.getElementById('previewOverlay').classList.add('open');
}
function closePreview() {
  document.getElementById('previewOverlay').classList.remove('open');
  currentPreviewId = null;
}
function updatePmCopy() {
  const btn = document.getElementById('pmCopyBtn');
  if (!btn) return;
  btn.textContent = '✨ 用這份開始規劃';
  btn.className = 'pm-copy-btn';
}

// ══════════════════════════════════════════════════
// 用範本開始規劃
// ══════════════════════════════════════════════════
// 原本的 copyTrip 是把假行程複製成 myTrips 的一筆（status:'copied'）。
// 範本沒有既成的行程內容可以「複製」——它是一組起點建議，所以改成帶著
// 目的地與想去的景點開精靈，讓 AI 真的生成一份。
function copyTrip() {
  if (!isLoggedIn) { closePreview(); openLogin(); return; }
  const t = OFFICIAL_TEMPLATES.find((x) => x.key === currentPreviewId);
  if (!t) return;
  closePreview();
  // 離島的 dest 就是它自己；本島各鄉鎮共用「台東」這個目的地鍵（poi-data 的分桶）
  const dest = t.island ? t.key : '台東';
  const desired = t.stops.filter((s) => !s.via).map((s) => s.name).join('、');

  // ⚠ 不能在這裡直接寫 wizData：openWizard() 只是打開「單人／共編」模式選擇，
  //   真正的精靈是使用者選完模式後由 selectTripMode() 開的，而那一步會
  //   `wizData = buildInitialWizData(mode)` 整個重設——先寫進去的目的地、
  //   想去景點、行程名稱全部會被沖掉（實測 wizData 只剩 {days:'8小時'}）。
  //   改成先存成待套用的種子，等模式選定後再併進初始值。
  pendingTemplateSeed = {
    key: t.key,
    title: t.title,
    dest,
    destCustom: t.island ? t.key : '',
    desiredSpots: desired,
    tripName: t.title,
    days: t.farForOneDay ? '2天1夜' : undefined
  };
  if (typeof openWizard === 'function') openWizard();
  showToast('已選擇「' + t.title + '」，選好製作方式就會帶入', 'green');
}
function closeCopySuccess() { document.getElementById('copySuccessOverlay').classList.remove('open'); }
function goToMyTrips() { closeCopySuccess(); showMainView('mytrips'); }

// ══════════════════════════════════════════════════
// MY TRIPS VIEW
// ══════════════════════════════════════════════════
// ── 我的行程：排序 + 關鍵字搜尋 ──
let mtSortMode = (() => {
  let m = 'date_desc';
  try { m = localStorage.getItem('mt_sort') || 'date_desc'; } catch (e) {}
  return ['date_desc', 'date_asc', 'region', 'status', 'collab'].includes(m) ? m : 'date_desc';
})();
let mtSearchQuery = '';
const MT_STATUS_LABEL = { ongoing: '進行中', planning: '規劃中', upcoming: '即將出發', completed: '已完成', copied: '已複製' };
const MT_STATUS_ORDER = { ongoing: 0, planning: 1, upcoming: 2, completed: 3, copied: 4 };
function mtTs(s) { const n = new Date(String(s || '').replace(/-/g, '/')).getTime(); return Number.isFinite(n) ? n : 0; }
// days 可能是純數字（社群複製，如 1）或已含單位的字串（精靈產生，如「8小時」「2天」「兩天一夜」）。
// 純數字才補「天」，已含單位就原樣顯示，避免出現「8小時天」。
function mtDurationLabel(d) {
  if (d == null || d === '') return '';
  const s = String(d).trim();
  return /^\d+(\.\d+)?$/.test(s) ? s + '天' : s;
}
function sortMyTripsList(list, mode) {
  const a = list.slice();
  switch (mode) {
    case 'date_asc': a.sort((x, y) => mtTs(x.createdAt) - mtTs(y.createdAt)); break;
    case 'region':   a.sort((x, y) => String(x.region || '').localeCompare(String(y.region || ''), 'zh-Hant')); break;
    case 'status':   a.sort((x, y) => (MT_STATUS_ORDER[x.status] ?? 9) - (MT_STATUS_ORDER[y.status] ?? 9)); break;
    case 'collab':   a.sort((x, y) => (y.collab ? 1 : 0) - (x.collab ? 1 : 0)); break;
    default:         a.sort((x, y) => mtTs(y.createdAt) - mtTs(x.createdAt)); // date_desc
  }
  // 「進行中」行程一律優先置頂（不論排序模式），讓使用者一眼回到正在跑的行程。
  // Array.sort 穩定 + filter 保序，故各群組內原本排序不受影響。
  const ongoing = a.filter(t => t.status === 'ongoing');
  const rest = a.filter(t => t.status !== 'ongoing');
  return ongoing.concat(rest);
}
function filterMyTripsList(list, q) {
  q = String(q || '').trim().toLowerCase();
  if (!q) return list;
  const tokens = q.split(/\s+/).filter(Boolean); // 多個關鍵字皆需命中（地區＋日期可組合）
  return list.filter(t => {
    const hay = `${t.title || ''} ${t.region || ''} ${t.budget || ''} ${t.createdAt || ''} ${mtDurationLabel(t.days)} ${MT_STATUS_LABEL[t.status] || ''} ${t.collab ? '共編 多人' : ''}`.toLowerCase();
    return tokens.every(tok => hay.includes(tok));
  });
}
let _mtSearchDebounceTimer = null;
function mtOnSearch(v) {
  mtSearchQuery = v;
  const clr = document.getElementById('mtSearchClear');
  if (clr) clr.style.display = String(v || '').length ? 'flex' : 'none';
  clearTimeout(_mtSearchDebounceTimer);
  _mtSearchDebounceTimer = setTimeout(renderMyTrips, 250);
}
function mtClearSearch() {
  const input = document.getElementById('mtSearch');
  if (input) input.value = '';
  mtOnSearch('');
}
function mtOnSort(v) {
  mtSortMode = v;
  try { localStorage.setItem('mt_sort', v); } catch (e) {}
  renderMyTrips();
}

function isUnfinishedPersonalTrip(trip) {
  return !!(trip && !trip.collab && trip.wizardData
    && (!Array.isArray(trip.stops) || trip.stops.length === 0));
}

function resumePersonalTripGeneration(tripId) {
  const trip = myTrips.find(t => t.id === tripId);
  if (!isUnfinishedPersonalTrip(trip)) return;
  if (isGeneratingTrip) {
    reopenWizGen();
    showToast('目前有行程正在生成，請等待完成後再試。', 'orange');
    return;
  }
  // 使用原草稿與原始偏好，避免重試時另建一筆行程或套用其他行程的精靈設定。
  wizData = { ...trip.wizardData, tripMode: trip.tripMode || 'solo' };
  localStorage.setItem('wai_pending_gen', JSON.stringify({ tripId, wData: wizData }));
  renderWizard();
  reopenWizGen();
  showWizGenProgress();
  return _doGeneration(trip, { ...wizData });
}

function renderMyTrips() {
  const grid = document.getElementById('myTripsGrid');
  if (!grid) return;
  const title = document.getElementById('myPageTitle');
  if (title) {
    if (isLoggedIn && currentUser) title.textContent = `${currentUser.name} 的行程`;
    else title.textContent = `我的微旅行 (本機未登入)`;
  }
  // 同步工具列狀態（重整／切頁後保留排序與搜尋）
  const sortSel = document.getElementById('mtSort');
  if (sortSel && sortSel.value !== mtSortMode) sortSel.value = mtSortMode;

  const newCard = `
    <div class="new-trip-card" onclick="openWizard()">
      <div class="new-trip-card-icon">＋</div>
      <div class="new-trip-card-label">建立微旅行</div>
    </div>`;

  if (!myTrips.length) {
    grid.innerHTML = `
      <div class="new-trip-card" onclick="openWizard()">
        <div class="new-trip-card-icon">＋</div>
        <div class="new-trip-card-label">建立第一趟微旅行</div>
      </div>`;
    return;
  }

  const list = sortMyTripsList(filterMyTripsList(myTrips, mtSearchQuery), mtSortMode);
  if (!list.length) {
    grid.innerHTML = `<div class="mt-empty-search"><div class="mt-empty-emoji">🔍</div><div>找不到符合的行程，換個關鍵字試試</div></div>` + newCard;
    return;
  }

  const statusHtml = (s) => s === 'ongoing' ? '⚡ 進行中' : s === 'planning' ? '✏️ 規劃中' : s === 'upcoming' ? '✈️ 即將出發' : s === 'completed' ? '🎉 已完成' : '📋 複製的行程';
  const cards = list.map(t => `
    <div class="my-trip-card">
      <div class="mt-topbar ${t.cc || 'c0'}"></div>
      <div class="mt-body">
        <div class="mt-head">
          <div class="mt-emoji">${t.emoji || '📍'}</div>
          <div class="mt-title">${t.title || '未命名行程'}</div>
        </div>
        <div class="mt-status ${t.status}">${isUnfinishedPersonalTrip(t) ? '⏳ 尚未產生景點' : statusHtml(t.status)}</div>
        <div class="mt-meta">
          <span class="mt-chip">🗓 ${mtDurationLabel(t.days)}</span>
          <span class="mt-chip">📍 ${t.region}</span>
          <span class="mt-chip">💰 ${t.budget}</span>
          <span class="mt-chip">🕑 ${t.createdAt}</span>
        </div>
        <div class="mt-actions">
          ${t.__saving ? `<button class="mt-action-btn primary" disabled onclick="showToast('行程儲存中，請稍候...', 'orange')">⏳ 儲存中...</button>` : isUnfinishedPersonalTrip(t) ? `<button class="mt-action-btn primary" style="white-space:nowrap;flex-shrink:0" onclick="resumePersonalTripGeneration('${t.id}')">繼續生成</button>` : (t.collab && !canRenameCollabTrip(t) ? `<button class="mt-action-btn replan" onclick="window.location='ai-travel-planner-v8.html?id=${t.id}'">👁 檢視</button>` : `<button class="mt-action-btn ${t.collab ? 'replan' : 'primary'}" onclick="window.location='ai-travel-planner-v8.html?id=${t.id}'">✏️ 編輯</button>`) }
          ${t.collab ? `<button class="mt-action-btn primary" onclick="openCollabPanel('${t.id}')">👥 成員</button>` : ''}
          ${(!t.collab || ['owner', 'editor'].includes(t.role) || (t.ownerEmail && currentUser && currentUser.email && t.ownerEmail.toLowerCase() === currentUser.email.toLowerCase())) ? `<button class="mt-action-btn share" onclick="renameMyTrip('${t.id}')">📝 改名</button>` : ''}
          <button class="mt-action-btn share" onclick="shareTrip('${t.id}')">📤 分享</button>
          ${(t.collab && t.role !== 'owner')
            ? `<button class="mt-action-btn delete" title="退出共編（不會刪除擁有者的行程）" aria-label="退出共編" onclick="deleteMyTrip('${t.id}')">🚪</button>`
            : `<button class="mt-action-btn delete" title="刪除行程" aria-label="刪除行程" onclick="deleteMyTrip('${t.id}')">🗑</button>`}
        </div>
      </div>
    </div>`).join('');
  grid.innerHTML = cards + newCard;
}
function closeWaiActionModal({ restoreFocus = true } = {}) {
  const modal = document.getElementById('waiActionModal');
  if (!modal) return;
  const returnFocus = modal._returnFocus;
  modal.remove();
  if (restoreFocus && returnFocus && returnFocus.isConnected && typeof returnFocus.focus === 'function') {
    returnFocus.focus();
  }
}

function openWaiActionModal({ title, subtitle = '', body, onMount }) {
  const currentModal = document.getElementById('waiActionModal');
  const activeElement = document.activeElement;
  const returnFocus = currentModal && currentModal.contains(activeElement)
    ? currentModal._returnFocus
    : activeElement;
  closeWaiActionModal({ restoreFocus: false });
  const modal = document.createElement('div');
  modal.id = 'waiActionModal';
  modal.className = 'wai-action-modal';
  modal.innerHTML = `<div class="wai-action-card" role="dialog" aria-modal="true" aria-label="${escapeHtml(title)}">
    <button type="button" class="wai-action-close" aria-label="關閉${escapeHtml(title.replace(/^\S+\s*/, ''))}視窗" onclick="closeWaiActionModal()">×</button>
    <div class="wai-action-title">${escapeHtml(title)}</div>
    ${subtitle ? `<div class="wai-action-sub">${escapeHtml(subtitle)}</div>` : ''}
    <div class="wai-action-content"></div>
  </div>`;
  modal._returnFocus = returnFocus;
  modal.addEventListener('click', (event) => { if (event.target === modal) closeWaiActionModal(); });
  document.body.appendChild(modal);
  const content = modal.querySelector('.wai-action-content');
  if (typeof body === 'function') body(content);
  if (typeof onMount === 'function') onMount(modal);
  return modal;
}

function canRenameCollabTrip(t) {
  const isOwner = t && t.ownerEmail && currentUser && currentUser.email
    && t.ownerEmail.toLowerCase() === currentUser.email.toLowerCase();
  return !t || !t.collab || ['owner', 'editor'].includes(t.role) || isOwner;
}

async function persistTripRename(t, name, expectedTitleVersion, forceOverwrite = false) {
  if (firebaseEnabled && firebaseDb && typeof firebaseAuth !== 'undefined' && firebaseAuth && firebaseAuth.currentUser) {
    const ref = firebaseDb.collection('micro_trips').doc(t.id);
    let writtenVersion = Number(expectedTitleVersion || 0) + 1;
    let notifyEmails = []; // F1：交易內記下成員名單，成功後通知其他成員
    try {
      await firebaseDb.runTransaction(async (tx) => {
        const snap = await tx.get(ref);
        const remote = snap.exists ? snap.data() : {};
        notifyEmails = Array.isArray(remote.memberEmails) ? remote.memberEmails.slice() : [];
        const remoteVersion = Number(remote.titleVersion || 0);
        if (t.collab && !forceOverwrite && remoteVersion !== Number(expectedTitleVersion || 0)) {
          const conflict = new Error('title-conflict');
          conflict.code = 'title-conflict';
          conflict.remote = remote;
          throw conflict;
        }
        writtenVersion = remoteVersion + 1; // 以交易內實際版本為準（強制覆寫時避免回傳過期版本號）
        tx.set(ref, {
          title: name,
          customTitle: true,
          titleVersion: writtenVersion,
          updatedAt: firebase.firestore.FieldValue.serverTimestamp(),
          lastEditedBy: firebaseAuth.currentUser.email || '',
          lastEditedByName: (currentUser && currentUser.name) || firebaseAuth.currentUser.email || ''
        }, { merge: true });
      });
      // F1：共編改名成功 → 通知其他成員（id 帶版本號，同版本只發一次；失敗靜默）
      if (t.collab && window.WAI_NOTIFY && notifyEmails.length) {
        WAI_NOTIFY.pushToMany(notifyEmails, WAI_NOTIFY.nid(['trip_renamed', t.id, 'v' + writtenVersion]), {
          type: 'trip_renamed', tripId: t.id, tripTitle: name,
          fromName: (currentUser && currentUser.name) || '', message: ''
        });
      }
      return { ok: true, titleVersion: writtenVersion };
    } catch (error) {
      return { ok: false, error };
    }
  }
  return { ok: true, titleVersion: Number(expectedTitleVersion || 0) + 1 };
}

function openRenameConflictModal(t, name, result) {
  const remote = (result.error && result.error.remote) || {};
  const remoteTitle = remote.title || '未命名行程';
  const remoteVersion = Number(remote.titleVersion || 0);
  openWaiActionModal({
    title: '行程名稱已更新',
    subtitle: `${remote.lastEditedByName || remote.lastEditedBy || '其他成員'} 剛剛修改了名稱。`,
    body(content) {
      content.innerHTML = `<div class="wai-confirm-list"><div>目前版本：<b>${escapeHtml(remoteTitle)}</b></div><div>你的名稱：<b>${escapeHtml(name)}</b></div></div>
        <div class="wai-action-actions"><button class="wai-action-btn" data-action="latest">使用最新版本</button><button class="wai-action-btn primary" data-action="overwrite">保留我的修改</button></div>`;
      content.querySelector('[data-action="latest"]').onclick = () => {
        t.title = remoteTitle; t.customTitle = !!remote.customTitle; t.titleVersion = remoteVersion;
        saveState(); renderMyTrips(); renderSideMyTrips(); closeWaiActionModal();
        showToast('已使用其他成員的最新名稱', 'blue');
      };
      content.querySelector('[data-action="overwrite"]').onclick = async (event) => {
        event.currentTarget.disabled = true;
        const retry = await persistTripRename(t, name, remoteVersion, true);
        if (!retry.ok) { event.currentTarget.disabled = false; showToast('名稱同步失敗，請稍後重試', 'red'); return; }
        t.title = name; t.customTitle = true; t.titleVersion = retry.titleVersion;
        saveState(); renderMyTrips(); renderSideMyTrips(); closeWaiActionModal();
        showToast('已保留你的名稱修改', 'green');
      };
    }
  });
}

// 自製改名彈窗：避免原生 prompt 在手機與自動化環境不一致。
async function renameMyTrip(id) {
  const t = myTrips.find(x => x.id === id);
  if (!t) return;
  if (!canRenameCollabTrip(t)) {
    showToast('你目前只有唯讀權限，無法修改行程名稱', 'orange');
    return;
  }
  openWaiActionModal({
    title: '修改行程名稱',
    subtitle: '名稱最多 40 個字，儲存後會同步給共編成員。',
    body(content) {
      content.innerHTML = `<input class="wai-action-input" maxlength="40" aria-label="新的行程名稱">
        <div class="wai-action-meta"><span>請使用容易辨識的名稱</span><span data-count>0 / 40</span></div>
        <div class="wai-action-error" role="alert"></div>
        <div class="wai-action-actions"><button class="wai-action-btn" data-action="cancel">取消</button><button class="wai-action-btn primary" data-action="save">儲存</button></div>`;
      const input = content.querySelector('input');
      const count = content.querySelector('[data-count]');
      const error = content.querySelector('.wai-action-error');
      const save = content.querySelector('[data-action="save"]');
      input.value = t.title || '';
      const updateCount = () => { count.textContent = `${input.value.length} / 40`; };
      input.oninput = updateCount; updateCount();
      content.querySelector('[data-action="cancel"]').onclick = closeWaiActionModal;
      save.onclick = async () => {
        const name = String(input.value || '').trim();
        if (!name) { error.textContent = '請輸入行程名稱。'; input.focus(); return; }
        save.disabled = true; error.textContent = '';
        const result = await persistTripRename(t, name, t.titleVersion || 0);
        if (!result.ok) {
          save.disabled = false;
          if (result.error && result.error.code === 'title-conflict') { openRenameConflictModal(t, name, result); return; }
          error.textContent = '同步失敗，請檢查網路後再試。';
          return;
        }
        t.title = name; t.customTitle = true; t.titleVersion = result.titleVersion;
        saveState(); renderMyTrips(); renderSideMyTrips(); closeWaiActionModal();
        showToast('📝 已更新行程名稱', 'green');
      };
    },
    onMount(modal) { setTimeout(() => modal.querySelector('.wai-action-input').focus(), 0); }
  });
}

async function deleteMyTrip(id) {
  const t = myTrips.find(x => x.id === id);
  // 共編成員按的是「退出」不是「刪除」——語意要清楚、且要有確認（E2E #10）
  const isLeave = !!(t && t.collab && t.role !== 'owner');
  const tripName = (t && (t.title || t.name)) || '';
  const msg = isLeave
    ? `要退出共編行程「${tripName}」嗎？\n退出後你將不再看到此行程；擁有者的行程不會被刪除。`
    : `確定要刪除行程「${tripName}」嗎？此動作無法復原。`;
  if (!window.confirm(msg)) return;
  myTrips = myTrips.filter(x => x.id !== id);
  if (collabState && collabState.tripId === id) closeCollabPanel(); // 刪到正在看的就先關面板
  saveState(); renderMyTrips(); renderSideMyTrips();
  // 共編行程：owner 刪除時連遠端一起清（避免孤兒佔 Firestore）；
  // 非 owner 成員則要真正「離開」——把自己從遠端 memberEmails/members 移除，
  // 否則只清本機、email 仍留在 memberEmails，下次登入 fetchMyCollabTrips 又會把行程抓回來。
  if (t && t.collab && firebaseEnabled && firebaseDb && window.WAI_COLLAB) {
    const myEmail = (currentUser && currentUser.email) || '';
    if (t.role === 'owner') {
      try { await WAI_COLLAB.deleteSharedTrip(id, t.inviteCode); } catch (e) { console.warn('刪除共用行程失敗：', e); }
    } else if (myEmail) {
      try { await WAI_COLLAB.leaveSharedTrip(id, myEmail); } catch (e) { console.warn('離開共用行程失敗：', e); }
    }
  }
  showToast(t && t.collab && t.role !== 'owner' ? '👋 已離開共編行程' : '🗑 已刪除行程', 'red');
}
// B2：所有對外分享都使用含 shareToken 的訪客唯讀連結；
// 單純 ?id=... 只供本人／已加入成員開啟，不具備跨帳號分享權限。
function buildTripDeepLink(t) {
  let path;
  if (t && t.shareToken && window.WAI_COLLAB) {
    path = WAI_COLLAB.buildShareLink(t.id, t.shareToken); // ...planner.html?sharedId=..&token=..&guest=1
  } else {
    path = 'ai-travel-planner-v8.html?id=' + encodeURIComponent(t.id);
  }
  try { return new URL(path, window.location.href).href; } // 轉成絕對網址，貼出去才可用
  catch (_e) { return path; }
}

async function copyTextToClipboard(text) {
  try {
    if (navigator.clipboard && navigator.clipboard.writeText) {
      await navigator.clipboard.writeText(text);
      return true;
    }
  } catch (_e) { /* file:// 或權限不足時退回 textarea 方案 */ }
  try {
    const ta = document.createElement('textarea');
    ta.value = text;
    ta.style.cssText = 'position:fixed;top:-9999px;left:-9999px;opacity:0;';
    document.body.appendChild(ta);
    ta.select();
    const ok = document.execCommand('copy');
    document.body.removeChild(ta);
    return ok;
  } catch (_e) { return false; }
}

async function shareTrip(id) {
  const t = (typeof myTrips !== 'undefined' ? myTrips : []).find(x => x.id === id);
  if (!t) { showToast('找不到這份行程', 'red'); return; }
  if (!t.shareToken) {
    if (t.collab) {
      showToast('這份共編行程缺少分享權杖，請由行程擁有者重新開啟共編設定。', 'red');
      return;
    }
    if (!window.WAI_COLLAB || typeof WAI_COLLAB.ensureTripShareToken !== 'function') {
      showToast('分享服務尚未載入，請重新整理後再試。', 'red');
      return;
    }
    try {
      showToast('正在建立安全的唯讀分享連結…', 'blue');
      t.shareToken = await WAI_COLLAB.ensureTripShareToken(t);
      saveState();
    } catch (error) {
      showToast((error && error.message) || '目前無法建立分享連結，請稍後再試。', 'red');
      return;
    }
  }
  const link = buildTripDeepLink(t);
  const ok = await copyTextToClipboard(link);
  if (ok) {
    showToast('🔗 唯讀分享連結已複製！', 'green');
    return;
  }
  // 複製失敗（例如無使用者手勢或瀏覽器阻擋）→ 用 prompt 讓使用者手動複製；
  // 某些環境 prompt 亦被封鎖（會拋錯），故 try/catch 後退回 toast 顯示連結，確保絕不中斷。
  try {
    if (typeof window.prompt === 'function') {
      const res = window.prompt('複製這個行程連結：', link);
      if (res !== null) return;
    }
    throw new Error('prompt unavailable');
  } catch (_e) {
    showToast('🔗 連結：' + link, 'blue');
  }
}

// 側欄的「我的微旅行」已升格成首頁主區塊（#homeMyTrips）。
// 這個函式有 20 個既有呼叫點散在各處，保留名稱轉呼叫新的渲染，
// 比逐一改動安全——也讓之後任何新增的呼叫點自動保持同步。
function renderSideMyTrips() {
  renderHomeMyTrips();
}

// ══════════════════════════════════════════════════
// NEW TRIP WIZARD
// ══════════════════════════════════════════════════
// 從範本進來時暫存的起始值；由 selectTripMode() 在建立 wizData 時併入。
let pendingTemplateSeed = null;

// 使用者剛從預覽選了一份範本，模式選擇彈窗要講出來是哪一份——
// 否則點完「用這份開始規劃」只看到一個空白的「選擇你的製作方式」，
// 會以為剛才的選擇沒生效。
function renderModeChoiceSeedNote() {
  const sub = document.querySelector('#modeChoiceOverlay .lm-sub');
  if (!sub) return;
  if (!sub.dataset.baseText) sub.dataset.baseText = sub.textContent;
  sub.textContent = pendingTemplateSeed
    ? `將以「${pendingTemplateSeed.title}」為起點 · ${sub.dataset.baseText}`
    : sub.dataset.baseText;
}

function openWizard() {
  renderModeChoiceSeedNote();
  document.getElementById('modeChoiceOverlay').classList.add('open');
}
function closeModeChoice() {
  document.getElementById('modeChoiceOverlay').classList.remove('open');
  // 取消就放掉範本種子，否則下次自己按「建立微旅行」會莫名其妙帶入舊範本
  pendingTemplateSeed = null;
  renderModeChoiceSeedNote();
}
function selectTripMode(mode) {
  const seed = pendingTemplateSeed;
  closeModeChoice();
  // 多人共作：先進「成員 + 邀請碼」lobby，確認有人加入後，才由 owner 開始規劃行程內容
  if (mode === 'collab') { pendingTemplateSeed = seed; startCollabLobby(); return; }
  wizStep = 0;
  wizData = buildInitialWizData(mode, templateSeedExtra(seed));
  clearWizardPrefetchState();
  renderWizard();
  document.getElementById('wizardOverlay').classList.add('open');
  if (seed) showToast(`已帶入「${seed.title}」的目的地與景點`, 'green');
}

// 把範本種子轉成 buildInitialWizData 的 extra；undefined 的欄位不覆蓋預設值。
function templateSeedExtra(seed) {
  if (!seed) return undefined;
  const extra = {
    dest: seed.dest,
    desiredSpots: seed.desiredSpots,
    tripName: seed.tripName,
    fromTemplateKey: seed.key
  };
  if (seed.destCustom) extra.destCustom = seed.destCustom;
  if (seed.days) extra.days = seed.days;
  return extra;
}

// 以帳號偏好初始化 wizData（solo 與 collab 共用）
function buildInitialWizData(mode, extra) {
  const wd = { days: '8小時', pace: '平衡', tripMode: mode, people: mode === 'solo' ? '1人' : '2人', ...(extra || {}) };
  if (currentUser && currentUser.preferences) {
    // 「旅程風格」persona 改為每趟行程現場選，不再從偏好帶入
    if (currentUser.preferences.interests) wd.interests = [...currentUser.preferences.interests];
    if (currentUser.preferences.pace) wd.pace = currentUser.preferences.pace;
    // 快照長期偏好供 buildPrompt 分層使用（本趟覆寫 interests/pace 後仍保有原始長期值與禁忌）
    wd.profilePrefs = {
      interests: Array.isArray(currentUser.preferences.interests) ? [...currentUser.preferences.interests] : [],
      pace: currentUser.preferences.pace || '',
      avoid: typeof currentUser.preferences.avoid === 'string' ? currentUser.preferences.avoid : '',
      avoidTags: Array.isArray(currentUser.preferences.avoidTags) ? [...currentUser.preferences.avoidTags] : []
    };
  }
  return wd;
}

// 建立共用行程「空殼」並開成員面板（lobby）——行程內容稍後由 owner 從面板開精靈填寫
async function startCollabLobby() {
  if (!isLoggedIn) { openLogin(); return; }
  if (!firebaseEnabled || !firebaseDb || !window.WAI_COLLAB) {
    showToast('共編需要登入並連線 Firebase，請稍後再試。', 'orange');
    return;
  }
  const newTrip = {
    id: 'my_' + Date.now(),
    title: '未命名共編行程',
    emoji: '👥',
    cc: 'c2',
    days: '8小時',
    region: '',
    budget: '',
    people: '2人',
    status: 'planning',
    createdAt: new Date().toLocaleDateString('zh-TW'),
    tripMode: 'collab',
    collab: true,
    role: 'owner',
    wizardData: {},
    stops: []
  };
  myTrips.unshift(newTrip);
  saveState(); renderSideMyTrips(); renderMyTrips();
  try {
    const owner = {
      uid: (firebaseAuth && firebaseAuth.currentUser) ? firebaseAuth.currentUser.uid : null,
      email: currentUser && currentUser.email ? currentUser.email : null,
      name: currentUser && currentUser.name ? currentUser.name : (currentUser && currentUser.email) || '擁有者',
      prefs: (typeof currentUser !== 'undefined' && currentUser && currentUser.preferences) || {}
    };
    const res = await WAI_COLLAB.createSharedTrip(newTrip, owner);
    newTrip.inviteCode = res.code;
    newTrip.shareToken = res.shareToken;
    saveState();
    openCollabPanel(newTrip.id);
  } catch (err) {
    console.warn('建立共用行程失敗：', err);
    showToast('建立共用行程失敗：' + (err && err.message || err), 'red');
  }
}

// 從 lobby 進四步精靈規劃行程內容（行程已存在，finishWizard 走「更新」分支）
function startCollabPlanning(tripId) {
  wizStep = 0;
  // 共編也可能是從範本進來的（預覽 → 用這份開始規劃 → 共編行程），種子要一路帶到這裡
  const seed = pendingTemplateSeed;
  pendingTemplateSeed = null;
  wizData = buildInitialWizData('collab', { collabTripId: tripId, ...(templateSeedExtra(seed) || {}) });
  closeCollabPanel();
  clearWizardPrefetchState();
  renderWizard();
  document.getElementById('wizardOverlay').classList.add('open');
}
function closeWizard() {
  const ov = document.getElementById('wizardOverlay');
  if (ov) ov.classList.remove('open');
  if (isGeneratingTrip) showGenMiniBar(); // 生成中關閉 → 改用右下角浮動小進度卡
}
// 「開始規劃行程內容」底下的說明。原本固定寫「先邀請朋友加入、各自填好偏好，再開始規劃」，
// 不管幾個人加入、偏好填了沒都照印——兩個人都填完了還在叫擁有者去邀請，看起來像沒生效。
function collabPlanningHint(memberList) {
  const list = Array.isArray(memberList) ? memberList.filter(Boolean) : [];
  const filled = list.filter((m) => {
    const pf = m.prefs || m.preferences || {};
    return (pf.interests || []).length || pf.pace || pf.budget;
  }).length;
  if (list.length <= 1) return '還沒有旅伴加入。把上面的邀請碼分享出去，或直接開始規劃。';
  const waiting = list.length - filled;
  if (waiting > 0) return `${list.length} 位旅伴中還有 ${waiting} 位沒填偏好；現在就開始規劃也可以，生成時以已填的為準。`;
  return `${list.length} 位旅伴的偏好都填好了，可以開始規劃。`;
}

// 只允許往回跳（往前必須通過每一步的驗證，例如 Step 3 的出發日期必填）
function goToWizStep(i) {
  const target = Number(i);
  if (!Number.isFinite(target) || target < 0 || target >= wizStep) return;
  wizStep = target;
  renderWizard();
}

function renderWizardDurationField() {
  const multi = isLongTrip(wizData.days);
  const start = normalizeClockInput(wizData.startTime, '09:00');
  const hours = Math.min(12, Math.max(1, Math.round(parseDurationMinutes(wizData.days) / 60)));
  const day1Hours = Math.min(12, Math.max(1, Math.round(Number(wizData.day1Hours) || 8)));
  const day1End = minutesToTimeString(timeStringToMinutes(start) + day1Hours * 60);
  const day2End = normalizeClockInput(wizData.day2EndTime, '12:00');
  const day2Start = getDay2StartTime(wizData, timeStringToMinutes(day1End));
  const stepBtn = (label, fn, disabled) => `<button type="button" onclick="${fn}" ${disabled ? 'disabled' : ''} style="width:42px;height:42px;border-radius:12px;border:1px solid #d8e2ef;background:#f7fbff;font-size:26px;font-weight:700;color:#2b4c6b;cursor:pointer;${disabled ? 'opacity:.4;cursor:not-allowed;' : ''}">${label}</button>`;
  return `<div class="wizard-field">
    <label>整體旅行時間</label>
    <div class="wizard-chips" style="margin-bottom:12px">
      <button class="wizard-tag${multi ? '' : ' active'}" type="button" onclick="setTripDurationMode('single')">☀️ 單日</button>
      <button class="wizard-tag${multi ? ' active' : ''}" type="button" onclick="setTripDurationMode('multi')">🌙 兩天一夜</button>
    </div>
    ${multi ? `
      <div style="display:flex;flex-direction:column;gap:14px;">
        <div>
          <div style="font-size:16px;font-weight:700;color:#2b4c6b;margin-bottom:8px;">第一天遊玩時數</div>
          <div style="display:flex;align-items:center;justify-content:center;gap:16px;">
            ${stepBtn('−', 'adjustDay1Hours(-1)', day1Hours <= 1)}
            <div style="min-width:96px;text-align:center;font-size:24px;font-weight:800;color:#1f3a52;">${day1Hours} 小時</div>
            ${stepBtn('＋', 'adjustDay1Hours(1)', day1Hours >= 12)}
          </div>
        </div>
        <div>
          <div style="font-size:16px;font-weight:700;color:#2b4c6b;margin-bottom:8px;">第二天出發時間</div>
          <div class="wai-dt-field" onclick="pickWizDay2StartTime()" role="button" tabindex="0" onkeydown="if(event.key==='Enter'||event.key===' '){event.preventDefault();pickWizDay2StartTime()}">
            <span>${day2Start}${wizData.day2StartTime ? '' : '（預設）'}</span><span class="wai-dt-ic">🕒</span>
          </div>
          ${wizData.day2StartTime ? '<button type="button" class="wiz-focus-toggle" onclick="clearDay2StartTime()" style="margin-top:6px;">恢復預設時間</button>' : '<p style="margin-top:6px;font-size:13px;color:#5f6876;">不修改時會以 09:00 開始；早於 10:00 返程時會自動提前。</p>'}
        </div>
        <div>
          <div style="font-size:16px;font-weight:700;color:#2b4c6b;margin-bottom:8px;">第二天玩到幾點</div>
          <div class="wai-dt-field" onclick="pickWizDay2EndTime('${day2End}')" role="button" tabindex="0" onkeydown="if(event.key==='Enter'||event.key===' '){event.preventDefault();pickWizDay2EndTime('${day2End}')}">
            <span>${day2End}</span><span class="wai-dt-ic">🕒</span>
          </div>
        </div>
      </div>
      <p style="margin-top:10px;font-size:14px;color:#5f6876;text-align:center;line-height:1.6;">🌙 <span style="white-space:nowrap;">第一天 ${start}–${day1End}</span><br><span style="white-space:nowrap;">第二天 ${day2Start}–${day2End}</span><br>玩完即返程</p>
    ` : `
      <div style="display:flex;align-items:center;justify-content:center;gap:16px;">
        ${stepBtn('−', 'adjustTripHours(-1)', hours <= 1)}
        <div style="min-width:96px;text-align:center;font-size:24px;font-weight:800;color:#1f3a52;">${hours} 小時</div>
        ${stepBtn('＋', 'adjustTripHours(1)', hours >= 12)}
      </div>
      <div class="wizard-chips" style="justify-content:center;margin-top:10px">
        ${[2,4,6,8,10].map(n => `<button class="wizard-tag${hours === n ? ' active' : ''}" type="button" onclick="setTripHours(${n})">${n}h</button>`).join('')}
      </div>
      <p style="margin-top:10px;font-size:14px;color:#5f6876;text-align:center;">🕘 預計 ${start}–${calcTripEndTime(start, wizData.days)}・約 ${hours} 小時</p>
    `}
  </div>`;
}

function renderWizard() {
  const steps = document.getElementById('wizSteps');
  const stepTitles = ['目的地', '日期 & 遊玩時間', '交通 & 偏好', '預算 & 其他'];
  // 這排 chip 長得就像分頁，使用者會去點它——原本是純 <div>，點了完全沒反應，
  // 無障礙樹裡也找不到（整個精靈只有「上一步／下一步」兩個可聚焦元素）。
  // 已完成的步驟可以直接跳回去；還沒走到的維持不可點（往前仍要通過各步驟的驗證）。
  steps.innerHTML = Array(WIZ_TOTAL).fill(0).map((_, i) => {
    const state = i < wizStep ? 'done' : (i === wizStep ? 'active' : '');
    const canGo = i < wizStep;
    return `<button type="button" class="wizard-step ${state}"
      ${canGo ? `onclick="goToWizStep(${i})"` : 'aria-disabled="true" tabindex="-1"'}
      ${i === wizStep ? 'aria-current="step"' : ''}
      aria-label="${canGo ? '回到' : ''}步驟 ${i + 1}：${escapeHtml(stepTitles[i])}${canGo ? '' : (i === wizStep ? '（目前）' : '（尚未完成）')}"
      ><span class="step-num">${i + 1}</span><span class="step-label">${escapeHtml(stepTitles[i])}</span></button>`;
  }).join('');
  document.getElementById('wizardBadge').textContent = `Step ${wizStep+1} / ${WIZ_TOTAL}`;
  document.getElementById('wizBackBtn').style.visibility = wizStep===0 ? 'hidden' : '';
  document.getElementById('wizNextBtn').textContent = wizStep===WIZ_TOTAL-1 ? '🚀 建立行程' : '下一步 →';
  // 新的精靈互動 → 還原導覽鈕顯示、收起上次的進度條與浮動卡
  _restoreWizNav();
  const _wgp = document.getElementById('wizGenProgress');
  if (_wgp) _wgp.hidden = true;
  hideGenMiniBar();

  const body = document.getElementById('wizBody');

  if (wizStep===0) {
    // Step 0：目的地
    wizData.slotMinutes = getSafeSlotMinutes(wizData.days || '1天', wizData.pace || '平衡', wizData.people);
    body.innerHTML = `<h3 class="wizard-block-title">✈️ 開始規劃你的微旅行</h3>
      <p class="wizard-block-help">先為行程取個名字，再選擇想去的地方</p>
      <div class="wizard-field">
        <label>行程名稱 <span style="font-size:13px;color:#8fa4b8;font-weight:normal;">（選填，留空自動以「目的地＋天數」命名）</span></label>
        <input type="text" id="wizTripName" maxlength="40" placeholder="例如：台東畢旅、週末放空之旅"
          value="${String(wizData.tripName || '').replace(/"/g, '&quot;')}"
          oninput="setWizTripName(this.value)"
          style="width:100%;box-sizing:border-box;padding:12px 14px;border:1px solid #d8e2ef;border-radius:12px;font-size:15px;background:#f7fbff;color:#1f3a52;">
      </div>
      <div class="wizard-field">
        <label>目的地・自由輸入</label>
        <input type="text" id="wizDestInput" placeholder="例：台東市、三仙台、知本…" value="${wizData.destCustom||''}" oninput="onDestInput(this.value)">
      </div>
      <div class="wizard-field">
        <label>熱門景點</label>
        <div class="wizard-choice-grid" style="grid-template-columns:repeat(3,1fr)">
          ${WIZ_DESTS.map(d=>`<button class="wizard-tag${wizData.dest===d.label?' active':''}" onclick="selectDest('${d.label}')" type="button" style="background:${wizData.dest===d.label?'#dff1ff':'#f7fbff'};border-color:${wizData.dest===d.label?'#7db8ee':'#d8e2ef'}">${d.emoji} ${d.label}</button>`).join('')}
        </div>
      </div>
      <div class="wizard-field">
        <label>旅行人數</label>
        ${(() => {
          const people = Math.min(20, Math.max(1, parseInt(wizData.people, 10) || 2));
          const pBtn = (label, fn, disabled) => `<button type="button" onclick="${fn}" ${disabled ? 'disabled' : ''} style="width:42px;height:42px;border-radius:12px;border:1px solid #d8e2ef;background:#f7fbff;font-size:26px;font-weight:700;color:#2b4c6b;cursor:pointer;${disabled ? 'opacity:.4;cursor:not-allowed;' : ''}">${label}</button>`;
          return `
            <div style="display:flex;align-items:center;justify-content:center;gap:16px;">
              ${pBtn('−', 'adjustTripPeople(-1)', people <= 1)}
              <div style="min-width:96px;text-align:center;font-size:24px;font-weight:800;color:#1f3a52;">👥 ${people} 人</div>
              ${pBtn('＋', 'adjustTripPeople(1)', people >= 20)}
            </div>
            <div class="wizard-chips" style="justify-content:center;margin-top:10px">
              ${[1,2,4,6].map(n => `<button class="wizard-tag${people === n ? ' active' : ''}" type="button" onclick="setTripPeople(${n})">${n}人</button>`).join('')}
            </div>
          `;
        })()}
      </div>
      `;

  } else if (wizStep===2) {
    // Step 3：交通 + 偏好（合併）
    const autoHub = getDefaultTransitHub(wizData.dest || wizData.destCustom || '');
    const startHint = autoHub ? `留空則自動使用「${autoHub}」` : '可填車站名、停車場或民宿名稱';
    const endHint = autoHub ? `留空則自動使用「${autoHub}」` : (isLongTrip(wizData.days) ? '可填飯店或車站名稱' : '可填停車場或車站名稱');
    // 「本趟想偏重」折疊區：有設過個人興趣 → 預設折疊成摘要，可展開微調；禁忌一律以小提示呈現
    const _pf = (currentUser && currentUser.preferences) || null;
    // 有設「興趣」或「節奏」任一長期偏好就視為有 profile focus → 預設收合成摘要（只設節奏也算）
    const _hasProfileFocus = !!(_pf && ((Array.isArray(_pf.interests) && _pf.interests.length) || (typeof _pf.pace === 'string' && _pf.pace.trim())));
    const _avoid = (_pf && typeof _pf.avoid === 'string') ? _pf.avoid.trim() : '';
    const _focusExpanded = (wizData._focusExpanded === undefined) ? !_hasProfileFocus : !!wizData._focusExpanded;
    body.innerHTML = `<h3 class="wizard-block-title">🚆 交通 & 旅遊偏好</h3>
      <p class="wizard-block-help">設定交通安排，並調整本趟想偏重的方向與風格</p>
      <div class="wizard-field">
        <label>出發站點 / 集合地點</label>
        <input type="text" id="wizStartLocation" placeholder="${startHint}" value="${wizData.startLocation||''}" oninput="wizData.startLocation=this.value;renderFlowPreview();scheduleWizardPreviewRequest('start-location')">
        <p style="margin-top:5px;font-size:14px;color:#5f6876;">可填車站、停車場或民宿；留空自動使用目的地最近的交通樞紐</p>
      </div>
      <div class="wizard-field">
        <label>回程站點 / 返回地點</label>
        <input type="text" id="wizEndLocation" placeholder="${endHint}" value="${wizData.endLocation||''}" oninput="wizData.endLocation=this.value;renderFlowPreview();scheduleWizardPreviewRequest('end-location')">
        <p style="margin-top:5px;font-size:14px;color:#5f6876;">旅程結束的返回地點；留空則與出發站點相同</p>
      </div>
      <div class="wizard-field" style="margin-top:16px">
        <label>主要交通工具<span style="font-size:13px;color:#8fa4b8;font-weight:normal;display:block;margin-top:2px;">生成行程時各段移動會以此工具為主，短程則步行</span></label>
        <div class="wizard-chips">
          ${[{v:'taxi',t:'🚕 計程車'},{v:'scooter',t:'🛵 機車'},{v:'car',t:'🚗 汽車'}].map(o=>`
            <button class="wizard-tag${(wizData.transportMode||'car')===o.v?' active':''}" type="button" data-transport="${o.v}" onclick="selectTransport('${o.v}')">${o.t}</button>
          `).join('')}
        </div>
      </div>
      ${wizData.collabTripId ? `<div class="wizard-field" style="margin-top:16px"><div class="wiz-focus-avoid" style="background:#eef4ff;border-color:#cfe0f7;color:#2b4c6b">🧑‍🤝‍🧑 興趣與節奏已在「成員偏好」設定，生成時會綜合所有成員，這裡不再重複填寫。</div></div>` : `
      <div class="wizard-field" id="wizTripFocusBlock" style="margin-top:16px">
        <div class="wiz-focus-head">
          <label style="margin:0;">本趟想偏重<span style="font-size:13px;color:#8fa4b8;font-weight:normal;display:block;margin-top:2px;">決定 AI 選擇的景點類型比重與行程節奏</span></label>
          ${_hasProfileFocus ? `<button type="button" class="wiz-focus-toggle" id="wizFocusToggle" onclick="toggleTripFocus()">${_focusExpanded ? '收合 ▴' : '本趟調整 ▾'}</button>` : ''}
        </div>
        ${_hasProfileFocus ? `<div class="wiz-focus-summary" id="wizFocusSummary" style="${_focusExpanded ? 'display:none' : ''}">${_tripFocusSummary()}</div>` : ''}
        ${_avoid ? `<div class="wiz-focus-avoid">🚫 你的禁忌「${_avoid}」會自動套用於每趟行程</div>` : ''}
        <div id="wizFocusPickers" style="${(_hasProfileFocus && !_focusExpanded) ? 'display:none' : ''}">
          <div class="wizard-choice-grid" style="grid-template-columns:repeat(3,1fr);gap:10px;">
            ${(()=>{
              const emojiMap={'美食':'🍜','文化':'🏛️','自然':'🌿','打卡':'📸','運動':'🏃','放鬆':'😌'};
              return ['美食','文化','自然','打卡','運動','放鬆'].map(item=>`
                <label class="wizard-check-pill">
                  <input type="checkbox" value="${item}" ${wizData.interests&&wizData.interests.includes(item)?'checked':''}>
                  <span class="pill-emoji">${emojiMap[item]}</span>
                  <span class="pill-label">${item}</span>
                </label>
              `).join('');
            })()}
          </div>
          <div class="wizard-field" style="margin-top:14px;margin-bottom:0;">
            <label>本趟節奏</label>
            <div class="wizard-chips" id="wizPaceGrid">
              ${['輕快','平衡','悠閒'].map(p=>`<button class="wizard-tag${wizData.pace===p?' active':''}" type="button" data-pace="${p}" onclick="updateWizardPace('${p}')">${PACE_EMOJI[p]} ${paceLabel(p)}</button>`).join('')}
            </div>
          </div>
        </div>
      </div>`}
      <div class="wizard-field">
        <label>旅程風格<span style="font-size:13px;color:#8fa4b8;font-weight:normal;display:block;margin-top:2px;">決定整體旅行氛圍與 AI 敘述感，與本趟想偏重互補，各有作用</span></label>
        <textarea id="theme" placeholder="例：想要輕鬆散步、品嚐在地美食、發現隱藏景點…" oninput="wizData.theme=this.value;renderFlowPreview();scheduleWizardPreviewRequest('step2-theme')">${wizData.theme||''}</textarea>
      </div>
      <div class="wizard-chips">
        ${['冒險獵人','療癒漫步者','美食尋寶家','在地故事家','拍照家'].map(t=>`
          <button class="wizard-tag${wizData.theme===t?' active':''}" type="button" data-theme="${t}" onclick="selectTheme('${t}')">${t}</button>
        `).join('')}
      </div>`;

  } else if (wizStep===1) {
    // Step 2：出發日期、每日出發時間與遊玩時數
    body.innerHTML = `<h3 class="wizard-block-title">📅 日期 & 遊玩時間</h3>
      <p class="wizard-block-help">設定出發日期，以及每天開始與結束的時間</p>
      <div class="wizard-field">
        <label for="wizDepartureDateField">出發日期 <span class="wizard-required" aria-hidden="true">必填</span></label>
        <div class="wai-dt-field" id="wizDepartureDateField" onclick="pickWizDepartureDate()"
          aria-label="出發日期，必填" aria-required="true"
          aria-describedby="wizDepartureDateErr"
          aria-invalid="${wizData.departureDate ? 'false' : 'true'}">
          <span class="${wizData.departureDate?'':'wai-dt-ph'}">${wizData.departureDate ? wizData.departureDate.replace(/-/g,'/') : '請選擇出發日期'}</span>
          <span class="wai-dt-ic">📅</span>
        </div>
        <p class="wizard-field-err" id="wizDepartureDateErr" role="alert" hidden>請先選擇出發日期</p>
        ${wizData.departureDate && wizData.returnDate ? `<p style="margin-top:6px;font-size:14px;color:#4a7fad;">📅 預計回程：${wizData.returnDate}${(() => { const dc = getWizardDayCount(wizData.days); return dc >= 2 ? `（第 ${dc} 天）` : '（當天）'; })()}</p>` : ''}
      </div>

      <div class="wizard-field">
        <label>${isLongTrip(wizData.days) ? '第一天出發時間' : '出發時間'}</label>
        <div class="wai-dt-field" onclick="pickWizStartTime()">
          <span>${wizData.startTime||'09:00'}</span>
          <span class="wai-dt-ic">🕒</span>
        </div>
        <p style="margin-top:8px;font-size:14px;color:#5f6876;">行程將依「${paceLabel(wizData.pace||'平衡')}」節奏自動計算每站間距</p>
      </div>
      ${renderWizardDurationField()}`;

  } else if (wizStep===3) {
    // Step 3：預算、住宿、希望景點
    body.innerHTML = `<h3 class="wizard-block-title">💰 預算 & 其他</h3>
      <p class="wizard-block-help">設定旅行預算，並可選填希望拜訪的景點</p>
      ${wizData.collabTripId ? `<div class="wizard-field"><div class="wiz-focus-avoid" style="background:#eef4ff;border-color:#cfe0f7;color:#2b4c6b">💰 預算已在「成員偏好」設定（取全員平均），這裡不再重複填寫。</div></div>` : `
      <div class="wizard-field">
        <label>預算 <span style="font-size:13px;color:#8fa4b8;font-weight:normal;">（每人預算，下方自動換算 ${getPeopleCount(wizData.people)} 人總額）</span></label>
        <div class="wizard-choice-grid" style="grid-template-columns:repeat(2,1fr)">
          ${BUDGET_TIERS.map(t=>{
            const open=t.perMax==null;
            const per=open?`每人 ${formatMoney(t.perMin)} 以上`:(t.perMin>0?`每人 ${formatMoney(t.perMin)}–${formatMoney(t.perMax)}`:`每人 ${formatMoney(t.perMax)} 內`);
            const token=`${t.key}（${per}）`;
            const on=wizData.budget===token;
            return `<button class="wizard-tag${on?' active':''}" type="button"
              onclick="wizData.budget='${token}';renderWizard()"
              style="display:flex;flex-direction:column;gap:2px;align-items:center;line-height:1.3;background:${on?'#dff1ff':'#f7fbff'};border-color:${on?'#7db8ee':'#d8e2ef'}">
              <span style="font-weight:600;">${t.key}</span><span style="font-size:13px;color:#5f6876;">${per}</span></button>`;
          }).join('')}
        </div>
        <p style="margin-top:8px;font-size:13px;color:#8fa4b8;line-height:1.5;">ℹ️ 此預算為當地餐飲與付費體驗的花費，<b>不含往返目的地的車票／機票等交通旅費</b>（站間移動與離島船票會另行估算）。</p>
        ${(()=>{ const d=describeBudget(wizData.budget, wizData.people); return d?`<p style="margin-top:8px;font-size:14px;color:#4a7fad;">👥 ${d.groupLabel}</p>`:''; })()}
      </div>`}
      ${isLongTrip(wizData.days)?`
      <div class="wizard-field">
        <label>住宿安排</label>
        <select id="wizAccommodation" onchange="wizData.accommodation=this.value">
          ${['飯店','民宿','背包客棧','露營','自備住宿'].map(a=>`<option ${wizData.accommodation===a?'selected':''}>${a}</option>`).join('')}
        </select>
      </div>
      <div class="wizard-field">
        <label>住宿地點名稱 <span style="font-size:13px;color:#8fa4b8;font-weight:normal;">（選填，AI 會以此為每晚終點與隔日起點）</span></label>
        <input type="text" id="wizLodgingName" maxlength="60" placeholder="例：知本老爺酒店、台東市區民宿…" value="${escapeHtml(wizData.lodgingName||'')}" oninput="wizData.lodgingName=this.value">
      </div>`:''}
      ${wizData.collabTripId ? `<div class="wizard-field"><div class="wiz-focus-avoid" style="background:#eef4ff;border-color:#cfe0f7;color:#2b4c6b">📍 想去的景點已在「成員偏好」設定（會綜合所有成員），這裡不再重複填寫。</div></div>` : `
      <div class="wizard-field">
        <label>希望去的景點 <span style="font-size:13px;color:#8fa4b8;font-weight:normal;">（選填，AI 會優先安排）</span></label>
        <textarea id="wizDesiredSpots" rows="3" placeholder="例：太麻里金針山、知本溫泉、多良車站…（可多個，逗號分隔）" oninput="wizData.desiredSpots=this.value;updateDesiredSpotsWarning();scheduleWizardPreviewRequest('desired-spots')">${wizData.desiredSpots||''}</textarea>
        <div id="wizDesiredWarn">${desiredSpotsWarningHtml(wizData.desiredSpots||'')}</div>
      </div>`}`;
  }

  // 興趣勾選事件（Step 1，交通 & 偏好合併頁）
  if (wizStep === 2) {
    setTimeout(() => {
      const checkboxes = document.querySelectorAll('.wizard-check-pill input[type="checkbox"]');
      checkboxes.forEach(cb => {
        cb.addEventListener('change', () => {
          const selected = Array.from(checkboxes).filter(c => c.checked).map(c => c.value);
          wizData.interests = selected;
          renderFlowPreview();
          scheduleWizardPreviewRequest('step3-interests');
        });
      });
    }, 10);
  }

  renderFlowPreview();
  syncWizardTimeOptionAvailability();
}

function clampPreviewStopsByDuration(days, people) {
  const r = getDurationStopRange(days, people);
  return Math.min(8, Math.round((r.min + r.max) / 2));
}

function getPreviewDurationMinutes(days) {
  return parseDurationMinutes(days);
}

function getMaxSlotMinutes(days, stopCount) {
  const durationMinutes = getPreviewDurationMinutes(days);
  if (stopCount <= 1) return durationMinutes;
  return Math.max(15, Math.floor(durationMinutes / (stopCount - 1)));
}


function doesScheduleFitDuration(days, slotMinutes) {
  const stopCount = clampPreviewStopsByDuration(days);
  const durationMinutes = getPreviewDurationMinutes(days);
  const requiredMinutes = Math.max(0, stopCount - 1) * (Number(slotMinutes) || 0);
  return requiredMinutes <= durationMinutes;
}

function getSafeSlotMinutes(days, pace, people) {
  const stopCount = clampPreviewStopsByDuration(days || '1天', people);
  const maxSlotMinutes = getMaxSlotMinutes(days || '1天', stopCount);
  const defaultSlot = getDefaultSlotMinutes(pace || '平衡');
  return Math.min(defaultSlot, maxSlotMinutes);
}

function updateWizardDays(value) {
  wizData.days = value;
  wizData.slotMinutes = getSafeSlotMinutes(value, wizData.pace || '平衡', wizData.people);
  autoUpdateReturnDate();
  scheduleWizardPreviewRequest('step2-days');
  renderWizard();
}

// 切換「單日 / 兩天一夜」（本專案上限兩天一夜）
function setTripDurationMode(mode) {
  if (isLongTrip(wizData.days) !== (mode === 'multi') && wizData.customNodeTimes) {
    // 單日與過夜預覽的相同索引代表不同天次，不能沿用舊的手動站點時間。
    Object.keys(wizData.customNodeTimes).forEach((key) => {
      if (key !== '0') delete wizData.customNodeTimes[key];
    });
  }
  if (mode === 'multi') {
    if (!wizData.day1Hours) wizData.day1Hours = 8;        // 第一天預設 8 小時
    if (!wizData.day2EndTime) wizData.day2EndTime = '12:00'; // 第二天預設玩到中午
    updateWizardDays('2天');
  } else {
    // 由多日切回單日預設 8 小時；本來就是單日則沿用目前時數
    const h = isLongTrip(wizData.days)
      ? 8
      : Math.min(12, Math.max(1, Math.round(parseDurationMinutes(wizData.days) / 60)));
    updateWizardDays(`${h}小時`);
  }
}

// 單日：直接設定小時數（1–12）
function setTripHours(h) {
  const hours = Math.min(12, Math.max(1, Math.round(h)));
  updateWizardDays(`${hours}小時`);
}

// 單日：步進調整小時數
function adjustTripHours(delta) {
  setTripHours(Math.round(parseDurationMinutes(wizData.days) / 60) + delta);
}

// 旅行人數：直接設定（1–20，存成「N人」字串）
function setTripPeople(n) {
  const people = Math.min(20, Math.max(1, Math.round(n)));
  wizData.people = `${people}人`;
  wizData.slotMinutes = getSafeSlotMinutes(wizData.days, wizData.pace || '平衡', wizData.people);
  renderFlowPreview();
  scheduleWizardPreviewRequest('step0-people');
  renderWizard();
}
// 旅行人數：步進調整
function adjustTripPeople(delta) {
  setTripPeople((parseInt(wizData.people, 10) || 2) + delta);
}

// 行程名稱（選填）：只記到 wizData，不觸發重繪（重繪會讓輸入框失焦）
function setWizTripName(v) {
  wizData.tripName = String(v || '').slice(0, 40);
}

// 兩天一夜：第一天遊玩時數（1–12）
function setDay1Hours(h) {
  wizData.day1Hours = Math.min(12, Math.max(1, Math.round(h)));
  wizData.slotMinutes = getSafeSlotMinutes(wizData.days, wizData.pace || '平衡', wizData.people);
  if (reconcileDay2StartTime()) showToast('第二天出發時間已配合第一天結束時間調整', 'blue');
  if (reconcilePreviewCustomTimes()) showToast('已重新調整超出第一天時間的預覽站點', 'blue');
  scheduleWizardPreviewRequest('step0-day1');
  renderWizard();
}
function adjustDay1Hours(delta) {
  setDay1Hours((Number(wizData.day1Hours) || 8) + delta);
}

function setDay2StartTime(value) {
  const selected = normalizeClockInput(value, '09:00');
  const firstEnd = timeStringToMinutes(wizData.startTime || '09:00')
    + (Number(wizData.day1Hours) || 8) * 60;
  const selectedMinutes = 1440 + timeStringToMinutes(selected);
  const secondEnd = 1440 + timeStringToMinutes(wizData.day2EndTime || '12:00');
  if (selectedMinutes < firstEnd || selectedMinutes >= secondEnd) {
    showToast('第二天出發時間須在第一天結束後、第二天返程前', 'orange');
    return;
  }
  wizData.day2StartTime = selected;
  const breakIndex = buildPreviewBaseTimes((wizData.previewTimes || []).length || 4, wizData).dayBreakIndex;
  if (wizData.customNodeTimes && breakIndex >= 0) delete wizData.customNodeTimes[String(breakIndex)];
  if (reconcilePreviewCustomTimes()) showToast('已重新調整超出第二天時間的預覽站點', 'blue');
  scheduleWizardPreviewRequest('day2-start-time');
  renderWizard();
}
function clearDay2StartTime() {
  wizData.day2StartTime = '';
  if (reconcilePreviewCustomTimes()) showToast('已重新調整第二天預覽站點時間', 'blue');
  scheduleWizardPreviewRequest('day2-start-default');
  renderWizard();
}

// 兩天一夜：第二天結束時間（玩到幾點）
function setDay2EndTime(value) {
  wizData.day2EndTime = normalizeClockInput(value, '12:00');
  if (reconcileDay2StartTime()) showToast('第二天出發時間已配合回程時間調整', 'blue');
  const lastIndex = (wizData.previewTimes || []).length - 1;
  if (lastIndex >= 0 && wizData.customNodeTimes) delete wizData.customNodeTimes[String(lastIndex)];
  if (reconcilePreviewCustomTimes()) showToast('已重新調整超出第二天時間的預覽站點', 'blue');
  scheduleWizardPreviewRequest('step0-day2');
  renderWizard();
}

// 本地（非 UTC）今日字串，修台灣半夜用 toISOString 偏一天的問題
function waiLocalDateStr() {
  var d = new Date(), m = d.getMonth() + 1, day = d.getDate();
  return d.getFullYear() + '-' + (m < 10 ? '0' + m : m) + '-' + (day < 10 ? '0' + day : day);
}
function waiLocalTimeStr() {
  var d = new Date(), h = d.getHours(), mi = d.getMinutes();
  return (h < 10 ? '0' + h : h) + ':' + (mi < 10 ? '0' + mi : mi);
}

// ── 滾輪日期/時間選擇器包裝（取代原生 input；onSet 沿用原欄位副作用並重繪以更新觸發欄位）──
function pickWizDepartureDate() {
  if (!window.WAIPicker) return;
  WAIPicker.openDate({
    value: wizData.departureDate || '',
    min: waiLocalDateStr(),
    title: '設定出發日期',
    onSet: function (v) { wizData.departureDate = v; autoUpdateReturnDate(); renderWizard(); scheduleWizardPreviewRequest('step3-departure-date'); },
    onClear: function () { wizData.departureDate = ''; wizData.returnDate = ''; renderWizard(); scheduleWizardPreviewRequest('step3-departure-date'); }
  });
}
function pickWizStartTime() {
  if (!window.WAIPicker) return;
  // 出發日期是今天 → 出發時間不能早於現在
  var minTime = (wizData.departureDate && wizData.departureDate === waiLocalDateStr()) ? waiLocalTimeStr() : null;
  WAIPicker.openTime({
    value: wizData.startTime || '09:00',
    min: minTime,
    title: '設定出發時間',
    onSet: function (v) {
      wizData.startTime = v;
      if (reconcileDay2StartTime()) showToast('第二天出發時間已配合第一天結束時間調整', 'blue');
      if (wizData.customNodeTimes) delete wizData.customNodeTimes['0'];
      if (reconcilePreviewCustomTimes()) showToast('已重新調整超出遊玩時間的預覽站點', 'blue');
      renderWizard();
      scheduleWizardPreviewRequest('step3-start-time');
    }
  });
}
function pickWizDay2EndTime(current) {
  if (!window.WAIPicker) return;
  WAIPicker.openTime({
    value: wizData.day2EndTime || current || '18:00',
    title: '設定第二天結束時間',
    onSet: function (v) { setDay2EndTime(v); }
  });
}
function pickWizDay2StartTime() {
  if (!window.WAIPicker) return;
  WAIPicker.openTime({
    value: getDay2StartTime(wizData, timeStringToMinutes(wizData.startTime || '09:00') + (Number(wizData.day1Hours) || 8) * 60),
    title: '設定第二天出發時間',
    onSet: function (v) { setDay2StartTime(v); }
  });
}
function pickPreviewNodeTime(index, current) {
  if (!window.WAIPicker) return;
  WAIPicker.openTime({
    // 滾輪選擇器只接受 HH:MM；「次日 09:00」直接傳入會跳回預設時刻。
    value: minutesToTimeString(timeStringToMinutes(current || '09:00') % 1440),
    title: '設定時間',
    onSet: function (v) { updatePreviewNodeTime(index, v); }
  });
}

function updateWizardPace(value) {
  wizData.pace = value;
  wizData.slotMinutes = getSafeSlotMinutes(wizData.days || '', value, wizData.people);
  scheduleWizardPreviewRequest('step2-pace');
  renderWizard();
}

// Step 1 折疊摘要文字：本趟值與個人喜好相同 → 「沿用」；被改過 → 「本趟偏重」
function _tripFocusSummary() {
  const pf = (currentUser && currentUser.preferences) || null;
  const ints = (wizData.interests && wizData.interests.length) ? wizData.interests.join('、') : '多元體驗';
  const txt = `${ints}｜${paceLabel(wizData.pace || '平衡')}節奏`;
  const inherited = pf && Array.isArray(pf.interests)
    && wizData.interests && wizData.interests.length === pf.interests.length
    && wizData.interests.every(i => pf.interests.includes(i))
    && (wizData.pace || '平衡') === (pf.pace || '平衡');
  return (inherited ? '✨ 沿用你的個人喜好：' : '✏️ 本趟偏重：') + txt;
}

// Step 1「本趟想偏重」折疊/展開：純 DOM 切換，不重繪（保留現有 checkbox 監聽）
function toggleTripFocus() {
  const pickers = document.getElementById('wizFocusPickers');
  if (!pickers) return;
  const expanded = !wizData._focusExpanded;
  wizData._focusExpanded = expanded;
  pickers.style.display = expanded ? '' : 'none';
  const btn = document.getElementById('wizFocusToggle');
  if (btn) btn.textContent = expanded ? '收合 ▴' : '本趟調整 ▾';
  const summary = document.getElementById('wizFocusSummary');
  if (summary) {
    if (expanded) {
      summary.style.display = 'none';
    } else {
      summary.textContent = _tripFocusSummary();
      summary.style.display = '';
    }
  }
}

function syncWizardTimeOptionAvailability() {
  if (wizStep !== 1) return;
  const daysSelect = document.getElementById('wizDays');
  if (!daysSelect) return;

  const currentSlot = Number(wizData.slotMinutes) || getDefaultSlotMinutes(wizData.pace || '平衡');
  Array.from(daysSelect.options).forEach((option) => {
    const fits = doesScheduleFitDuration(option.value, currentSlot);
    option.disabled = !fits;
    option.hidden = !fits;
    option.textContent = option.value;
  });
}

function getDefaultSlotMinutes(pace) {
  if (pace === '輕快') return 30;
  if (pace === '悠閒') return 60;
  return 45;
}

function timeStringToMinutes(value) {
  const source = String(value || '09:00').trim();
  let offset = 0;
  let text = source;
  if (text.startsWith('次日 ')) { offset = 24 * 60; text = text.slice(3); }
  else {
    const dayPrefix = /^第\s*(\d+)\s*天\s*/.exec(text);
    if (dayPrefix) { offset = Math.max(0, Number(dayPrefix[1]) - 1) * 1440; text = text.slice(dayPrefix[0].length); }
  }
  const match = text.match(/^(\d{1,2}):(\d{2})$/);
  if (!match) return 9 * 60;
  const hour = Number(match[1]);
  const minute = Number(match[2]);
  if (!Number.isFinite(hour) || !Number.isFinite(minute)) return 9 * 60;
  if (hour < 0 || hour > 23 || minute < 0 || minute > 59) return 9 * 60;
  return Math.max(0, offset + hour * 60 + minute);
}

function minutesToTimeString(totalMinutes) {
  const safe = Math.max(0, Number(totalMinutes));
  // 跨日換算：day2=次日、day3+=「第 N 天」（原本只減一次 24h，三天行程第 3 天會顯示「次日 33:00」）
  const dayOffset = Math.floor(safe / (24 * 60));
  const within = safe % (24 * 60);
  const hhmm = `${String(Math.floor(within / 60)).padStart(2, '0')}:${String(within % 60).padStart(2, '0')}`;
  if (dayOffset === 1) return `次日 ${hhmm}`;
  if (dayOffset >= 2) return `第 ${dayOffset + 1} 天 ${hhmm}`;
  return hhmm;
}

function normalizeClockInput(value, fallback = '09:00') {
  const source = String(value || '').trim();
  if (/^\d{1,2}:\d{2}$/.test(source)) {
    const [hText, mText] = source.split(':');
    const h = Number(hText);
    const m = Number(mText);
    if (Number.isFinite(h) && Number.isFinite(m) && h >= 0 && h <= 23 && m >= 0 && m <= 59) {
      return `${h.toString().padStart(2, '0')}:${m.toString().padStart(2, '0')}`;
    }
  }
  return fallback;
}

function buildAutoDelayedPreviewTimes(baseTimes, customTimes, minimumGapMinutes) {
  const gap = Math.max(0, Number(minimumGapMinutes) || 0);
  let previousMinutes = null;

  return baseTimes.map((baseTime, index) => {
    const custom = customTimes ? customTimes[String(index)] : null;
    // 多日預覽的自訂時間是時鐘值；保留該節點原本所屬的日子。
    const baseDay = Math.floor(timeStringToMinutes(baseTime) / 1440);
    const normalizedTime = custom
      ? normalizeClockInput(custom, baseTime.replace(/^次日\s*/, ''))
      : baseTime;
    let currentMinutes = custom
      ? baseDay * 1440 + timeStringToMinutes(normalizedTime)
      : timeStringToMinutes(normalizedTime);

    if (previousMinutes !== null && currentMinutes <= previousMinutes) {
      currentMinutes = previousMinutes + gap;
    }

    previousMinutes = currentMinutes;
    return minutesToTimeString(currentMinutes);
  });
}

function updatePreviewNodeTime(index, value) {
  const key = String(index);
  if (!wizData.customNodeTimes || typeof wizData.customNodeTimes !== 'object') {
    wizData.customNodeTimes = {};
  }
  const normalized = normalizeClockInput(value, '09:00');
  const multi = isLongTrip(wizData.days);
  const lastIndex = (wizData.previewTimes || []).length - 1;
  if (multi && lastIndex >= 1) {
    const dayBreakIndex = buildPreviewBaseTimes(lastIndex + 1, wizData).dayBreakIndex;
    if (index === dayBreakIndex) {
      const firstEnd = timeStringToMinutes(wizData.startTime || '09:00')
        + (Number(wizData.day1Hours) || 8) * 60;
      const selected = 1440 + timeStringToMinutes(normalized);
      const day2End = 1440 + timeStringToMinutes(wizData.day2EndTime || '12:00');
      if (selected < firstEnd || selected >= day2End) {
        showToast('第二天出發時間須在第一天結束後、第二天返程前', 'orange');
        return;
      }
      setDay2StartTime(normalized);
      return;
    }
  }
  let adjustedLastTime = normalized;
  if (multi && lastIndex >= 1) {
    const baseTimes = buildPreviewBaseTimes(lastIndex + 1, wizData);
    const tentative = { ...wizData.customNodeTimes, [key]: normalized };
    const tentativeTimes = buildAutoDelayedPreviewTimes(baseTimes, tentative,
      Number(wizData.slotMinutes) || getDefaultSlotMinutes(wizData.pace || '平衡'));
    const firstEnd = timeStringToMinutes(wizData.startTime || '09:00')
      + (Number(wizData.day1Hours) || 8) * 60;
    const lastEnd = 1440 + timeStringToMinutes(wizData.day2EndTime || '12:00');
    const firstDayOverruns = timeStringToMinutes(tentativeTimes[baseTimes.dayBreakIndex - 1]) > firstEnd;
    const finalMinutes = timeStringToMinutes(tentativeTimes[lastIndex]);
    if (firstDayOverruns || finalMinutes >= 2880 || (index !== lastIndex && finalMinutes > lastEnd)) {
      showToast('預覽時間超過當日設定的結束時間，請先調整遊玩時數或回程時間', 'orange');
      return;
    }
    if (index === lastIndex) adjustedLastTime = minutesToTimeString(finalMinutes).replace(/^次日\s*/, '');
  }
  wizData.customNodeTimes[key] = normalized;
  // ★ 第一個節點的時間「就是」出發時間。customNodeTimes 只活在精靈的暫存狀態裡，
  //   存檔時（newTrip.wizardData）沒有帶走，於是使用者在預覽改成 14:00、生成出來
  //   仍從 startTime 的 09:00 開始——這是實測回報的「預覽與保存後時間不同」。
  //   同步寫回 startTime 後，摘要列、結束時間、送去生成的 prompt 與存檔會一起跟上。
  if (index === 0) wizData.startTime = normalized;
  if (multi && index === lastIndex) {
    wizData.day2EndTime = adjustedLastTime;
  }
  if (index === 0 || (multi && index === lastIndex)) {
    renderWizard();
    scheduleWizardPreviewRequest(index === 0 ? 'preview-start-time' : 'preview-day2-end-time');
  } else {
    renderFlowPreview();
  }
}

function buildPreviewBaseTimes(stopCount, wizardData) {
  const count = Math.max(2, stopCount);
  const start = timeStringToMinutes(normalizeClockInput(wizardData.startTime, '09:00'));
  if (!isLongTrip(wizardData.days)) {
    const duration = parseDurationMinutes(wizardData.days);
    return Array.from({ length: count }, (_, i) =>
      minutesToTimeString(start + Math.floor(duration * i / (count - 1))));
  }

  const firstHours = Math.min(12, Math.max(1, Math.round(Number(wizardData.day1Hours) || 8)));
  const lastEnd = timeStringToMinutes(normalizeClockInput(wizardData.day2EndTime, '12:00'));
  // 第二天重新開始，不把過夜時段當成連續遊玩時間。若回程在 09:00 前，
  // 至少保留一小時供第二天的示意站點分配。
  const secondStart = timeStringToMinutes(getDay2StartTime(wizardData, start + firstHours * 60));
  const secondDuration = Math.max(0, lastEnd - secondStart);
  const firstCount = count === 2 ? 1 : Math.max(2, Math.min(count - (count >= 4 ? 2 : 1),
    Math.round(count * firstHours * 60 / (firstHours * 60 + secondDuration))));
  const times = Array.from({ length: count }, (_, i) => {
    if (i < firstCount) {
      return minutesToTimeString(start + (firstCount === 1 ? 0 : Math.floor(firstHours * 60 * i / (firstCount - 1))));
    }
    const secondIndex = i - firstCount;
    const secondCount = count - firstCount;
    const minutes = secondStart + (secondCount === 1
      ? secondDuration
      : Math.floor(secondDuration * secondIndex / (secondCount - 1)));
    return minutesToTimeString(1440 + minutes);
  });
  times.dayBreakIndex = firstCount;
  return times;
}

function reconcilePreviewCustomTimes() {
  if (!isLongTrip(wizData.days) || !wizData.customNodeTimes) return false;
  const count = (wizData.previewTimes || []).length;
  if (count < 2) return false;
  const baseTimes = buildPreviewBaseTimes(count, wizData);
  let changed = false;
  const oldBreak = Number(wizData.previewDayBreakIndex);
  if (Number.isInteger(oldBreak) && oldBreak > 0 && oldBreak !== baseTimes.dayBreakIndex) {
    for (let i = Math.min(oldBreak, baseTimes.dayBreakIndex); i < Math.max(oldBreak, baseTimes.dayBreakIndex); i++) {
      if (Object.prototype.hasOwnProperty.call(wizData.customNodeTimes, String(i))) {
        delete wizData.customNodeTimes[String(i)];
        changed = true;
      }
    }
  }
  const firstEnd = timeStringToMinutes(wizData.startTime || '09:00')
    + (Number(wizData.day1Hours) || 8) * 60;
  const lastEnd = 1440 + timeStringToMinutes(wizData.day2EndTime || '12:00');
  const gap = Number(wizData.slotMinutes) || getDefaultSlotMinutes(wizData.pace || '平衡');
  for (let attempt = 0; attempt < count; attempt++) {
    const times = buildAutoDelayedPreviewTimes(baseTimes, wizData.customNodeTimes, gap);
    const firstOverflow = timeStringToMinutes(times[baseTimes.dayBreakIndex - 1]) > firstEnd;
    const lastOverflow = timeStringToMinutes(times[count - 1]) > lastEnd;
    if (!firstOverflow && !lastOverflow) break;
    const candidates = Object.keys(wizData.customNodeTimes)
      .map(Number).filter((i) => i > 0 && i < count
        && (firstOverflow ? i < baseTimes.dayBreakIndex : i >= baseTimes.dayBreakIndex));
    if (!candidates.length) break;
    delete wizData.customNodeTimes[String(Math.max(...candidates))];
    changed = true;
  }
  return changed;
}

function buildInterestDrivenStops(dest, interests = [], pace = '平衡', startLocation = '', endLocation = '') {
  const interestStops = {
    美食: { title: '在地美食', desc: '安排口碑店家或小吃，保留彈性用餐與休息時間。' },
    文化: { title: '文化散策', desc: '串接歷史街區或展館，讓行程有在地故事線。' },
    自然: { title: '自然步道', desc: '優先安排綠地與風景路段，增加慢走停留體驗。' },
    打卡: { title: '拍照打卡', desc: '挑選視覺亮點與地標角度，保留拍照緩衝時段。' },
    運動: { title: '動態活動', desc: '納入可活動身體的段落，節奏更有活力。' },
    放鬆: { title: '放鬆休息', desc: '加入咖啡館或靜態停留點，降低整體移動壓力。' }
  };

  const orderedInterests = ['美食', '文化', '自然', '打卡', '運動', '放鬆'];
  const selected = orderedInterests.filter((item) => interests.includes(item));
  const middleStops = selected.map((item) => interestStops[item]);

  if (!middleStops.length) {
    middleStops.push(
      { title: '主要景點', desc: '先完成目的地代表亮點，建立旅程主軸。' },
      { title: '自由探索', desc: '依現場氣氛調整停留，保留即興彈性。' }
    );
  }

  const paceTail = pace === '輕快'
    ? '以較快節奏銜接下一站。'
    : pace === '悠閒'
      ? '留更多緩衝時間慢慢體驗。'
      : '維持移動與停留的平衡感。';

  const firstStop = { title: startLocation || `${dest}附近車站`, desc: `從${startLocation || dest}出發，先確認旅伴與路線重點。` };
  const lastStop = { title: endLocation || startLocation || `${dest}附近車站`, desc: `返回${endLocation || startLocation || dest}，完成本次旅程。${paceTail}` };
  return [firstStop, ...middleStops, lastStop];
}

// 自駕（汽車／機車）才提醒：本地資料沒有即時車位，誠實告知未涵蓋停車，請使用者預留找車位時間。
function parkingAdvisoryText(mode) {
  if (mode !== 'car' && mode !== 'scooter') return '';
  const vehicle = mode === 'scooter' ? '機車' : '汽車';
  return `🅿️ ${vehicle}自駕提醒：此行程未包含各景點的即時停車位資訊。熱門景點、老街、夜市與假日海灘的車位可能有限，請預留找車位與步行的時間，並留意現場停車規定與收費。`;
}
function updateFlowParkingNote() {
  const el = document.getElementById('flowParkingNote');
  if (!el) return;
  const text = parkingAdvisoryText(wizData.transportMode || 'car');
  if (text) { el.textContent = text; el.style.display = ''; }
  else { el.textContent = ''; el.style.display = 'none'; }
}

function renderFlowPreview() {
  const dest = wizData.dest || wizData.destCustom || '台東';
  const days = wizData.days || '1天';
  const theme = wizData.theme || '經典旅人';
  const pace = wizData.pace || '平衡';
  const interests = Array.isArray(wizData.interests) ? wizData.interests : [];
  const startTime = wizData.startTime || '09:00';
  const slotMinutes = Number(wizData.slotMinutes) || getDefaultSlotMinutes(pace);
  wizData.slotMinutes = slotMinutes;

  document.getElementById('themeNote').textContent = `目前主題：${theme}`;
  document.getElementById('flowTitle').textContent = `${dest}・${days}微旅行`;

  const defaultHub = getDefaultTransitHub(dest);
  const allStops = buildInterestDrivenStops(dest, interests, pace, wizData.startLocation || defaultHub, wizData.endLocation || defaultHub);
  const stopCount = clampPreviewStopsByDuration(days, wizData.people);
  const previewStops = allStops.slice(0, Math.max(2, stopCount));

  const previewStopCount = previewStops.length;
  const maxSlotMinutes = getMaxSlotMinutes(days, previewStopCount);
  let effectiveSlotMinutes = Math.min(slotMinutes, maxSlotMinutes);
  wizData.slotMinutes = effectiveSlotMinutes;
  const baseTimes = buildPreviewBaseTimes(previewStopCount, wizData);
  wizData.previewDayBreakIndex = baseTimes.dayBreakIndex ?? -1;

  if (!wizData.customNodeTimes || typeof wizData.customNodeTimes !== 'object') {
    wizData.customNodeTimes = {};
  }
  const allowedKeys = new Set(previewStops.map((_, index) => String(index)));
  Object.keys(wizData.customNodeTimes).forEach((key) => {
    if (!allowedKeys.has(key)) {
      delete wizData.customNodeTimes[key];
    }
  });

  const times = buildAutoDelayedPreviewTimes(baseTimes, wizData.customNodeTimes, effectiveSlotMinutes);
  wizData.previewTimes = times;
  const secondDayIndex = baseTimes.dayBreakIndex ?? -1;

  document.getElementById('flowNodes').innerHTML = previewStops.map((spot, i) => `
    ${i === secondDayIndex ? '<div class="wizard-day-break"><span>🌙 過夜</span><strong>第 2 天</strong></div>' : ''}
    <div class="wizard-node${i === secondDayIndex - 1 ? ' wizard-node-day-end' : ''}">
      <div class="wizard-node-top">
        <h4 class="wizard-node-title">🧭 ${escapeHtml(spot.title)}</h4>
        <div class="wizard-node-time-input" style="cursor:pointer;display:inline-flex;align-items:center;justify-content:center;gap:4px;white-space:nowrap;" onclick="pickPreviewNodeTime(${i}, '${times[i]}')">${times[i]}<span style="font-size:13px;opacity:.6;">🕒</span></div>
      </div>
      <p class="wizard-node-desc">${escapeHtml(spot.desc)}</p>
    </div>
  `).join('') + `
    <p class="wizard-node-desc" style="margin-top:10px;opacity:.75;">
      ⓘ 第一格＝出發時間，會照著走；下面幾格是節奏示意，實際站點與時間由 AI 依營業時間與車程重新安排。
    </p>
  `;

  const endTime = isLongTrip(days)
    ? normalizeClockInput(wizData.day2EndTime, '12:00')
    : calcTripEndTime(startTime, days);
  const interestSummary = interests.length ? interests.join('、') : '多元探索';
  const effectiveHub = getDefaultTransitHub(dest);
  const effectiveStart = wizData.startLocation || effectiveHub;
  const effectiveEnd = wizData.endLocation || effectiveHub;
  const startLocSummary = `出發：${effectiveStart}`;
  const endLocSummary = effectiveStart !== effectiveEnd ? `結束：${effectiveEnd}` : '';
  const locationSummary = [startLocSummary, endLocSummary].filter(Boolean).join('，');
  const peopleStr = wizData.people === '1人' ? '獨旅' : `${wizData.people || '2人'}人同行`;
  const secondStartTime = getDay2StartTime(wizData,
    timeStringToMinutes(startTime) + (Number(wizData.day1Hours) || 8) * 60);
  const timeSummary = isLongTrip(days)
    ? `第一天 ${startTime}–${minutesToTimeString(timeStringToMinutes(startTime) + (Number(wizData.day1Hours) || 8) * 60)}、第二天約 ${secondStartTime}–${endTime}`
    : `${startTime} - ${endTime}`;
  document.getElementById('flowSummary').textContent = `以「${theme}」風格生成，${peopleStr}，時間 ${timeSummary}（${pace}節奏），重點偏好：${interestSummary}。${locationSummary ? ' ' + locationSummary + '。' : ''}`;
  updateFlowParkingNote();
  const cachedPreview = getCachedWizardPreviewPlan();
  if (cachedPreview && !Object.keys(wizData.customNodeTimes).length) renderAiSkeletonPreview(cachedPreview);
}

function onDestInput(val) {
  wizData.destCustom = val;
  const match = WIZ_DESTS.find(d => d.label === val.trim());
  wizData.dest = match ? match.label : '';
  document.querySelectorAll('.wizard-choice-grid .wizard-tag').forEach(btn => {
    const label = btn.textContent.trim().split(' ')[1];
    const isActive = label === wizData.dest;
    btn.classList.toggle('active', isActive);
    btn.style.background = isActive ? '#dff1ff' : '#f7fbff';
    btn.style.borderColor = isActive ? '#7db8ee' : '#d8e2ef';
  });
  triggerFirebaseHintPrefetch();
  scheduleWizardPreviewRequest('step1-destination');
  renderFlowPreview();
}

function selectDest(label) {
  wizData.dest = (wizData.dest === label) ? '' : label;
  wizData.destCustom = wizData.dest;
  const inp = document.getElementById('wizDestInput');
  if (inp) inp.value = wizData.dest;
  triggerFirebaseHintPrefetch();
  scheduleWizardPreviewRequest('step1-destination');
  renderWizard();
}

function selectTheme(theme) {
  // 再點一次同一顆標籤＝取消選取（回到未選任何主題的狀態）
  wizData.theme = (wizData.theme === theme) ? '' : theme;
  const input = document.getElementById('theme');
  if (input) input.value = wizData.theme;
  scheduleWizardPreviewRequest('step4-theme');
  renderWizard();
}

function selectTransport(mode) {
  const valid = ['taxi', 'scooter', 'car'];
  wizData.transportMode = valid.includes(mode) ? mode : 'car';
  scheduleWizardPreviewRequest('transport-mode');
  renderFlowPreview();
  renderWizard();
}

function autoUpdateReturnDate() {
  if (!wizData.departureDate) return;
  const addDays = getWizardDayCount(wizData.days) - 1; // F5：回程日＝出發日＋(天數−1)
  const d = new Date(wizData.departureDate);
  d.setDate(d.getDate() + addDays);
  wizData.returnDate = d.toISOString().split('T')[0];
  const el = document.getElementById('wizReturnDate');
  if (el) el.value = wizData.returnDate;
}

function wizNext() {
  if (isGeneratingTrip) { showToast('正在生成行程中，請稍候…', 'orange'); return; }
  if (wizStep===0 && !wizData.dest && !wizData.destCustom) { showToast('請選擇或輸入目的地', 'orange'); return; }
  if (!hasValidMultiDayWindow(wizData)) {
    showToast('第一天結束時間與第二天回程時間重疊，請調整出發時間、第一天時數或第二天結束時間', 'orange');
    return;
  }
  // Step 2（出發日期 & 時間）：必須先選好出發日期才能進下一步
  // toast 會自己消失，消失後畫面上沒有任何線索說是哪一欄出錯——
  // 錯誤要留在欄位旁邊，並把焦點帶過去。
  if (wizStep===1 && !wizData.departureDate) {
    const err = document.getElementById('wizDepartureDateErr');
    const field = document.getElementById('wizDepartureDateField');
    if (err) err.hidden = false;
    if (field) { field.setAttribute('aria-invalid', 'true'); field.classList.add('is-invalid'); field.focus(); }
    showToast('請先選擇出發日期', 'orange');
    return;
  }
  // Step 2：有出發日期但回程日期空，自動推算
  if (wizStep===1 && wizData.departureDate && !wizData.returnDate) autoUpdateReturnDate();
  if (wizStep===WIZ_TOTAL-1) { finishWizard(); return; }
  wizStep++; renderWizard();
}

function wizPrev() { if(wizStep>0){wizStep--;renderWizard();} }

function persistMicroTripInBackground(trip) {
  if (!firebaseEnabled) return Promise.resolve(false);
  return (async () => {
    try {
      // 標記為正在保存，UI 上會禁用編輯鈕
      trip.__saving = true;
      saveState(); renderSideMyTrips(); renderMyTrips();

      // 失敗時重試，避免單次網路抖動造成行程沒存進 Firebase（後續編輯就看不到景點）
      let firebaseSaved = await saveMicroTripToFirebase(trip);
      for (let attempt = 1; !firebaseSaved && attempt <= 2; attempt++) {
        console.warn(`Firebase 保存失敗，第 ${attempt} 次重試…`);
        await new Promise(r => setTimeout(r, 1500));
        firebaseSaved = await saveMicroTripToFirebase(trip);
      }
      if (firebaseSaved) {
        // 成功：顯示成功提示，之後才啟用編輯按鍵
        showToast(`✅ 微旅行已保存到 Firebase: ${trip.id}`, 'green');
        // 在 toast 顯示後延遲 2400ms（toast 的顯示時間）再啟用編輯
        setTimeout(() => {
          trip.__saving = false;
          saveState(); renderSideMyTrips(); renderMyTrips();
        }, 2400);
      } else {
        // 失敗：顯示失敗提示，立即啟用編輯按鍵
        showToast('Firebase 保存失敗，已存到本地', 'orange');
        trip.__saving = false;
        saveState(); renderSideMyTrips(); renderMyTrips();
      }

      // POI／營業時間是全站共用資料，只能由受控後端或 crawler 維護。
      // 行程內的 businessHours 已包含在 trip.stops，不再由瀏覽器寫入 poi_cache。
      return firebaseSaved;
    } catch (error) {
      console.warn('Firebase background save failed:', error);
      showToast('Firebase 背景同步失敗，已存到本地', 'orange');
      // 錯誤時也立即啟用編輯按鍵
      trip.__saving = false;
      saveState(); renderSideMyTrips(); renderMyTrips();
      return false;
    }
  })();
}

// === Gen Panel helpers ===
function showGenPanel(title) {
  const ov = document.getElementById('genOverlay');
  if (!ov) return;
  document.getElementById('genTitle').textContent = '🤖 ' + title + ' · 生成中';
  document.getElementById('genPhase').textContent = 'AI 正在思考你的行程…';
  document.getElementById('genOutput').innerHTML = '';
  document.getElementById('genFooter').innerHTML = '';
  const cur = document.getElementById('genCursor');
  if (cur) cur.style.display = '';
  ov.style.display = 'flex';
  addGenLine('$ TravelLinkAI --generate --dest ' + title, 'info');
}
function hideGenPanel() {
  const ov = document.getElementById('genOverlay');
  if (ov) ov.style.display = 'none';
}
function addGenLine(text, type) {
  const out = document.getElementById('genOutput');
  if (!out) return;
  const d = document.createElement('div');
  d.className = 'gen-line gen-line-' + (type || 'sys');
  d.textContent = text;
  out.appendChild(d);
  out.scrollTop = out.scrollHeight;
}
function setGenPhase(text) {
  const el = document.getElementById('genPhase');
  if (el) el.textContent = text;
}
function extractNamesFromStream(text) {
  const names = [];
  const re = /"name"\s*:\s*"([^"\\\n]{1,60})"/g;
  let m;
  while ((m = re.exec(text)) !== null) {
    const n = m[1].trim();
    if (n && n.length > 1 && !names.includes(n)) names.push(n);
  }
  return names;
}
function showGenDoneButton(tripId) {
  const cur = document.getElementById('genCursor');
  if (cur) cur.style.display = 'none';
  const footer = document.getElementById('genFooter');
  if (!footer) return;
  const btn = document.createElement('button');
  btn.className = 'gen-edit-btn';
  btn.textContent = '✏️ 開始編輯行程';
  btn.onclick = function() { hideGenPanel(); window.location = 'ai-travel-planner-v8.html?id=' + tripId; };
  footer.appendChild(btn);
  const skip = document.createElement('button');
  skip.className = 'gen-skip-link';
  skip.textContent = '前往我的行程列表';
  skip.onclick = function() { hideGenPanel(); showMainView('mytrips'); };
  footer.appendChild(skip);
}

// === 建立行程：分段步驟進度條（顯示在行程預覽下方，取代舊 CLI 面板）===
const WGP_PHASES = ['查景點', 'AI 規劃', '對應地圖', '完成'];
const _genMini = { index: 0, label: '', status: 'running', tripId: null }; // running | done | error
function showWizGenProgress() {
  const host = document.getElementById('wizGenProgress');
  if (!host) return;
  host.classList.remove('is-error');
  const cta = document.getElementById('wgpCta');
  if (cta) cta.innerHTML = '';
  host.querySelectorAll('.wgp-step').forEach(s => s.classList.remove('done', 'active', 'error'));
  _genMini.status = 'running';
  host.hidden = false;
  setWizGenStep(0);
  // 按下建立行程後，隱藏「上一步／建立行程」兩顆鈕，只剩步驟條
  const back = document.getElementById('wizBackBtn');
  const next = document.getElementById('wizNextBtn');
  if (back) back.style.display = 'none';
  if (next) next.style.display = 'none';
  hideGenMiniBar(); // 重開精靈時收起浮動卡
  const wrap = host.closest('.wizard-flow-wrap');
  if (wrap) wrap.scrollTop = wrap.scrollHeight;
  // 手機是單欄、由整個 wizard-body 捲動：進度條在表單下方一千多 px，
  // 只捲 wrap 的話畫面停在第 4 步表單、按鈕又被收掉，看起來像按了沒反應。
  if (typeof host.scrollIntoView === 'function') host.scrollIntoView({ block: 'nearest', behavior: 'smooth' });
}
function setWizGenStep(index, subLabel) {
  _genMini.index = index;
  _genMini.status = 'running';
  _genMini.label = subLabel || ('正在' + (WGP_PHASES[index] || '處理') + '…');
  const host = document.getElementById('wizGenProgress');
  if (host && !host.hidden) {
    host.querySelectorAll('.wgp-step').forEach((s, i) => {
      s.classList.remove('error');
      s.classList.toggle('done', i < index);
      s.classList.toggle('active', i === index);
    });
    const title = document.getElementById('wgpTitle');
    if (title) title.textContent = _genMini.label;
  }
  renderGenMini();
}
function wizGenDone(tripId) {
  _genMini.status = 'done';
  _genMini.tripId = tripId;
  const host = document.getElementById('wizGenProgress');
  if (host && !host.hidden) {
    host.querySelectorAll('.wgp-step').forEach(s => { s.classList.remove('active', 'error'); s.classList.add('done'); });
    const title = document.getElementById('wgpTitle');
    if (title) title.textContent = '🎉 行程就緒！';
    const cta = document.getElementById('wgpCta');
    if (cta) {
      cta.innerHTML = '';
      const edit = document.createElement('button');
      edit.className = 'wizard-btn primary';
      edit.textContent = '✏️ 開始編輯行程';
      edit.onclick = function () { window.location = 'ai-travel-planner-v8.html?id=' + tripId; };
      cta.appendChild(edit);
      const list = document.createElement('button');
      list.className = 'wgp-cta-link';
      list.textContent = '前往我的行程列表';
      list.onclick = function () { closeWizard(); showMainView('mytrips'); };
      cta.appendChild(list);
      // 按鈕是生成完才長出來的，手機上會落在可視範圍外：捲到「開始編輯行程」
      if (typeof cta.scrollIntoView === 'function') cta.scrollIntoView({ block: 'nearest', behavior: 'smooth' });
    }
  }
  renderGenMini();
}
function wizGenError(msg) {
  _genMini.status = 'error';
  const host = document.getElementById('wizGenProgress');
  if (host && !host.hidden) {
    host.classList.add('is-error');
    const active = host.querySelector('.wgp-step.active');
    if (active) { active.classList.remove('active'); active.classList.add('error'); }
    const title = document.getElementById('wgpTitle');
    if (title) title.textContent = '生成失敗，請重試';
  }
  _restoreWizNav(); // 還原導覽鈕，讓使用者可重試
  renderGenMini();
  showToast('生成行程失敗：' + msg, 'red');
}
// 還原精靈導覽鈕顯示（生成失敗 / 開新一輪精靈時）
function _restoreWizNav() {
  const back = document.getElementById('wizBackBtn');
  const next = document.getElementById('wizNextBtn');
  if (back) back.style.display = '';
  if (next) next.style.display = '';
}

// === 浮動小進度卡：關閉精靈後仍可看進度並點回 ===
function showGenMiniBar() {
  const bar = document.getElementById('genMiniBar');
  if (!bar) return;
  bar.hidden = false;
  renderGenMini();
}
function hideGenMiniBar() {
  const bar = document.getElementById('genMiniBar');
  if (bar) bar.hidden = true;
}
function renderGenMini() {
  const bar = document.getElementById('genMiniBar');
  if (!bar || bar.hidden) return;
  bar.classList.toggle('is-done', _genMini.status === 'done');
  bar.classList.toggle('is-error', _genMini.status === 'error');
  const title = bar.querySelector('.gmb-title');
  if (title) {
    if (_genMini.status === 'done') title.textContent = '🎉 行程就緒 · 點我查看';
    else if (_genMini.status === 'error') title.textContent = '⚠ 生成失敗 · 點我查看';
    else title.textContent = '生成中 · ' + (WGP_PHASES[_genMini.index] || '') + ' (' + Math.min(_genMini.index + 1, 4) + '/4)';
  }
  bar.querySelectorAll('.gmb-dot').forEach((d, i) => {
    d.classList.toggle('done', _genMini.status === 'done' || i < _genMini.index);
    d.classList.toggle('active', _genMini.status === 'running' && i === _genMini.index);
  });
}
function reopenWizGen() {
  const ov = document.getElementById('wizardOverlay');
  if (ov) ov.classList.add('open');
  hideGenMiniBar();
}

async function finishWizard() {
  if (isGeneratingTrip) return;

  const dest = wizData.dest || wizData.destCustom || '台東';
  const days = wizData.days || '8小時';
  const _durM = parseDurationMinutes(days);
  const defaultBudget = _durM <= 120 ? '$300' : _durM <= 240 ? '$800' : _durM <= 480 ? '$1,500' : '$3,000';
  const tripMode = wizData.tripMode || 'solo';
  const emojiMap = {'台東':'🌊','花蓮':'🏔','台北':'🏙','日本':'⛩️','韓國':'🌸','歐洲':'🏛'};
  // 使用者在精靈填了名稱就用它（並標記 customTitle，AI 生成的標題不覆蓋）；留空則自動命名
  const _customName = String(wizData.tripName || '').trim().slice(0, 40);
  const newTrip = {
    id: 'my_' + Date.now(), title: _customName || `${dest} ${days}微旅行`,
    customTitle: !!_customName,
    emoji: emojiMap[dest] || '✈️',
    cc: ['c0','c1','c2','c3'][Math.floor(Math.random()*4)],
    days, region: dest, budget: wizData.budget || defaultBudget,
    people: wizData.people || (wizData.tripMode === 'solo' ? '1人' : '2人'), status: 'planning',
    createdAt: new Date().toLocaleDateString('zh-TW'),
    tripMode,
    wizardData: {
      dest, days,
      people: wizData.people || (wizData.tripMode === 'solo' ? '1人' : '2人'),
      pace: wizData.pace || '平衡',
      interests: wizData.interests || [], theme: wizData.theme || '經典旅人',
      transportMode: wizData.transportMode || 'car',
      startTime: wizData.startTime || '09:00',
      startLocation: wizData.startLocation || '', endLocation: wizData.endLocation || '',
      departureDate: wizData.departureDate || '',
      returnDate: wizData.returnDate || '',
      budget: wizData.budget || '',
      accommodation: isLongTrip(days) ? (wizData.accommodation || '飯店') : '',
      lodgingName: isLongTrip(days) ? String(wizData.lodgingName || '').trim().slice(0, 60) : '',
      ...(isLongTrip(days) ? { day1Hours: wizData.day1Hours || 8, day2StartTime: wizData.day2StartTime || '', day2EndTime: wizData.day2EndTime || '12:00' } : {}),
      desiredSpots: (wizData.desiredSpots || '').trim()
    },
    stops: []
  };

  // ── 多人共作：共用行程已在 lobby 建立，這裡只「更新」行程參數，再回成員面板由 owner 隨時生成 ──
  if (tripMode === 'collab') {
    if (!firebaseEnabled || !firebaseDb || !window.WAI_COLLAB) {
      showToast('共編需要登入並連線 Firebase，請稍後再試。', 'orange');
      return;
    }
    const collabTripId = wizData.collabTripId;
    if (!collabTripId) { showToast('找不到共編行程，請從「共編行程」重新建立。', 'orange'); return; }
    try {
      const patch = {
        title: newTrip.title,
        emoji: newTrip.emoji,
        region: dest,
        days,
        budget: newTrip.budget,
        people: newTrip.people,
        wizardData: newTrip.wizardData
      };
      await WAI_COLLAB.updateSharedTripParams(collabTripId, patch);
      // 同步本機 myTrips 該筆（lobby 建立時已 unshift 過）
      const idx = myTrips.findIndex(t => t.id === collabTripId);
      if (idx !== -1) myTrips[idx] = { ...myTrips[idx], ...patch };
      saveState(); renderSideMyTrips(); renderMyTrips();
      // 像單人一樣：按下建立行程就直接生成（用當下成員的偏好彙整），精靈保持開啟顯示進度
      showWizGenProgress();
      await runCollabGeneration(collabTripId);
    } catch (err) {
      console.warn('更新共用行程失敗：', err);
      showToast('更新共用行程失敗：' + (err && err.message || err), 'red');
    }
    return;
  }

  // ── 單人：立即生成（維持原行為）──
  // 立即存入 localStorage，切換頁面後資料不消失
  myTrips.unshift(newTrip);
  saveState(); renderSideMyTrips(); renderMyTrips();

  // 記錄待完成的生成任務，供頁面重新載入後恢復
  localStorage.setItem('wai_pending_gen', JSON.stringify({ tripId: newTrip.id, wData: { ...wizData } }));

  showWizGenProgress(); // 精靈保持開啟，進度顯示在行程預覽下方

  await _doGeneration(newTrip, wizData);
}

// 從 wizData 抽出「成員偏好」（用於團體彙整）；avoid 來自帳號設定。
function collabPrefsFromWizData(wd) {
  const prof = (typeof currentUser !== 'undefined' && currentUser && currentUser.preferences) || {};
  return WAI_COLLAB.normalizePrefs({
    interests: Array.isArray(wd.interests) ? wd.interests : [],
    pace: wd.pace || prof.pace || '平衡',
    budget: wd.budget || '',
    desiredSpots: (wd.desiredSpots || '').trim(),
    avoid: typeof prof.avoid === 'string' ? prof.avoid : '',
    avoidTags: Array.isArray(prof.avoidTags) ? prof.avoidTags : []
  });
}

async function _doGeneration(trip, wData) {
  if (isGeneratingTrip) return;
  isGeneratingTrip = true;
  try {
    _genPerf.start();
    // 成本統計：整趟生成（含補景點、fillTripTimeBudget、逐站驗證…）的所有 API 呼叫
    // 都會歸到這一個 runId。拿不到就是不統計，絕不擋住生成本身。
    if (window.WAI_COST) await WAI_COST.startRun();
    setWizGenStep(0);
    // F3 自動避雨：出發日近且台東有雨 → 注入 prompt 規則（_rainOutlook 為暫態欄位，不入庫）
    wData._rainOutlook = await fetchTripRainOutlook(wData).catch(() => null);
    const _liveDest = wData.dest || wData.destCustom || '';
    // 本地優先：有本地景點資料就直接用，跳過 Google Maps 即時抓取
    const _localHint = buildLocalPoiHintBlock(_liveDest);
    // 景點與餐廳互不相依：兩個 promise 先發再各自 await（原本串行，兩段 RTT 疊加）
    const _liveCenter = getDestinationCenter(_liveDest);
    const _poiPromise = _localHint
      ? Promise.resolve([])
      : fetchGoogleMapsPoiList(_liveDest, wData.interests || [], _liveCenter).catch(() => []);
    // 餐廳一律即時抓（本地 poi-data 不含餐廳），讓 AI 有用餐站候選——不吃本地、每次都撈最新
    const _foodPromise = fetchGoogleMapsFoodList(_liveDest, _liveCenter).catch(() => []);
    let livePlaces = await _poiPromise;
    const livePoiHint = _localHint || (livePlaces.length > 0 ? buildLiveMapsPoiHintBlock(livePlaces) : '');
    const _foodPlaces = await _foodPromise;
    // 餐廳候選必須和景點清單分開送：requestGeminiMicroTravelPlan 會自己重算本地景點快取，
    // 一旦本地有資料就整塊丟掉 livePoiHint。以前把餐廳併進 livePoiHint，等於在台東/綠島/蘭嶼
    // 這些有本地快取的目的地把餐廳清單一起丟掉，AI 只看得到景點又被要求「只能從清單挑」→ 排不出用餐站。
    let foodHint = '';
    if (_foodPlaces.length) {
      livePlaces = livePlaces.concat(_foodPlaces);
      foodHint = buildLiveFoodHintBlock(_foodPlaces);
    }
    _genPerf.mark('step0 景點/餐廳清單');
    setWizGenStep(1);

    let lastNames = [];
    const finalPlan = await requestGeminiMicroTravelPlan(wData, {
      mode: 'final',
      includeFirebase: !_localHint && livePlaces.length === 0,
      livePoiHint,
      foodHint,
      useStreaming: true,
      onChunk: (_chunk, fullText) => {
        const names = extractNamesFromStream(fullText);
        if (names.length > lastNames.length) {
          lastNames = names;
          // 滾動顯示最新識別的景點名，讓等待「看得到進度」
          const latest = String(names[names.length - 1] || '').slice(0, 12);
          setWizGenStep(1, `已識別 ${names.length} 個景點${latest ? `：${latest}` : ''}…`);
        }
      }
    });

    _genPerf.mark('step1 Gemini 主生成');
    setWizGenStep(2);

    if (finalPlan && finalPlan.stops) {
      trip.stops = await optimizeGeneratedTripStops(finalPlan.stops, wData, livePlaces);
      // Plan B：生成時為每站保留附近替代景點＋室內/戶外標記（供 planner 的「查看替換」與天氣驅動替換）
      attachStopAlternatives(trip.stops, wData.dest || wData.destCustom || wData.region || '');
      // 使用者自訂名稱（customTitle）優先：AI 標題只在未自訂時作為別名保存
      trip.aiTitle = trip.customTitle ? trip.title : (finalPlan.title || trip.title);
      trip.aiReply = finalPlan.reply || '';
      // F4 預算硬約束：估算超支就把貴門票景點換成免費替代；換完仍超支則標記警示
      try {
        const _budget = enforceBudgetCap(trip, wData);
        if (_budget) {
          if (_budget.swaps.length) {
            const _swapTxt = _budget.swaps.map(s => `「${s.from}」→「${s.to}」`).join('、');
            trip.aiReply = `${trip.aiReply ? trip.aiReply + ' ' : ''}💰 為符合你的預算，已把 ${_swapTxt} 換成免費替代景點。`;
          }
          if (_budget.overrunPerPerson > 0) {
            trip.budgetOverrun = _budget.overrunPerPerson;
            if (typeof showToast === 'function') showToast(`⚠️ 估算仍超出人均預算約 $${_budget.overrunPerPerson}（估算值），可再調整景點或預算`, 'orange');
          } else {
            delete trip.budgetOverrun;
          }
        }
      } catch (e) { console.warn('enforceBudgetCap failed:', e); }
    }
    if (!Array.isArray(trip.stops) || trip.stops.length === 0) {
      throw new Error('這次沒有產生景點，原本的規劃偏好已保留，請重試生成。');
    }
    _genPerf.mark('step2 後處理');
    _genPerf.table('行程生成');

    const _idx = myTrips.findIndex(t => t.id === trip.id);
    if (_idx !== -1) myTrips[_idx] = trip;
    saveState(); renderSideMyTrips(); renderMyTrips();

    localStorage.removeItem('wai_pending_gen');
    setWizGenStep(3);
    wizGenDone(trip.id);

    const cloudSaved = firebaseEnabled ? await persistMicroTripInBackground(trip) : false;

    // 成本統計收尾：綁定行程後標記完成。失敗不影響行程本身，
    // 頂多是這筆 run 停在 incomplete（顯示「統計未完成」而不是 0 元）。
    // 用 completeRun 而不是 bindTrip().then(finishRun)：
    // 後者的 .then() 回呼會回頭讀全域 activeRun，若使用者在 bind 重試期間
    // 已開始下一趟生成，收尾到的會是新的那個 run。
    if (window.WAI_COST && WAI_COST.currentRunId()) {
      if (cloudSaved) WAI_COST.completeRun(trip.id, true).catch(function () {});
      else WAI_COST.finishRun(false).catch(function () {});
    }

  } catch (error) {
    localStorage.removeItem('wai_pending_gen');
    isGeneratingTrip = false;
    // 生成失敗也要收尾，否則這個 run 會一直留在伺服器記憶體等 TTL 過期
    // 生成失敗：收尾為 incomplete，不可標成完成（否則畫面會顯示一筆零用量的「完整」紀錄）
    if (window.WAI_COST && WAI_COST.currentRunId()) WAI_COST.finishRun(false).catch(function () {});
    wizGenError(error.message);
    console.error('_doGeneration error:', error);
    return;
  }
  isGeneratingTrip = false;
}

// ══════════════════════════════════════════════════
// INVITE CODE
// ══════════════════════════════════════════════════
function normalizeInviteCode(code) {
  return String(code || '').toUpperCase().replace(/[^A-Z0-9]/g, '');
}

function openInvite() {
  if (!isLoggedIn) { openLogin(); return; }
  document.getElementById('inviteCodeInput').value = '';
  document.getElementById('inviteOverlay').classList.add('open');
}
function closeInvite() { document.getElementById('inviteOverlay').classList.remove('open'); }
async function submitInviteCode() {
  const code = document.getElementById('inviteCodeInput').value;
  await joinSharedTripByCode(code, closeInvite);
}

function openInviteConfirmation(code, preview, closeFn) {
  openWaiActionModal({
    title: preview.isMember ? '你已加入此共編行程' : '確認加入共編行程',
    subtitle: preview.isMember ? '你可以直接開啟行程檢視最新內容。' : '加入後預設為唯讀成員；擁有者可再調整你的權限。',
    body(content) {
      content.innerHTML = `<div class="wai-confirm-list">
        <div>行程：<b>${escapeHtml(preview.title)}</b></div>
        <div>擁有者：<b>${escapeHtml(preview.ownerName)}</b></div>
        <div>成員：<b>${preview.memberCount} / ${preview.maxMembers} 人</b></div>
        <div>加入權限：<b>${preview.isMember ? '依目前設定' : '唯讀'}</b></div>
      </div><div class="wai-action-actions"><button class="wai-action-btn" data-action="cancel">取消</button><button class="wai-action-btn primary" data-action="confirm">${preview.isMember ? '開啟行程' : '確認加入'}</button></div>`;
      content.querySelector('[data-action="cancel"]').onclick = closeWaiActionModal;
      content.querySelector('[data-action="confirm"]').onclick = async (event) => {
        event.currentTarget.disabled = true;
        closeWaiActionModal();
        await joinSharedTripByCode(code, closeFn, true);
      };
    }
  });
}

// 共用：以邀請碼「真實加入」共編行程（「🔑 輸入邀請碼」與「📱 邀請碼快速加入」共用；planner 的「流程碼」另指現場切旅遊模式）
async function joinSharedTripByCode(rawCode, closeFn, confirmed = false) {
  const code = String(rawCode || '').trim();
  if (!code) { showToast('請輸入邀請碼', 'orange'); return; }
  if (!isLoggedIn) { if (closeFn) closeFn(); openLogin(); return; }
  if (!firebaseEnabled || !firebaseDb || !window.WAI_COLLAB) {
    showToast('加入共編需要連線 Firebase，請稍後再試。', 'orange'); return;
  }
  try {
    if (!confirmed) {
      const preview = await WAI_COLLAB.previewByCode(code);
      openInviteConfirmation(code, preview, closeFn);
      return;
    }
    const user = {
      uid: (firebaseAuth && firebaseAuth.currentUser) ? firebaseAuth.currentUser.uid : null,
      email: currentUser && currentUser.email ? currentUser.email : null,
      name: currentUser && currentUser.name ? currentUser.name : (currentUser && currentUser.email) || '旅伴',
      prefs: (typeof currentUser !== 'undefined' && currentUser && currentUser.preferences) || {}
    };
    const trip = await WAI_COLLAB.joinByCode(code, user);
    upsertCollabTripLocal(trip, 'viewer');
    saveState(); renderSideMyTrips(); renderMyTrips();
    if (closeFn) closeFn();
    showToast(trip.alreadyMember
      ? `你已在「${trip.title || '共編行程'}」中`
      : `✅ 已加入「${trip.title || '共編行程'}」！`, 'green');
    openCollabPanel(trip.id); // 開成員面板：owner 端透過 onSnapshot 即時看到人數 +1
  } catch (err) {
    console.warn('加入共編失敗：', err);
    showToast((err && err.message) || '加入失敗，請確認邀請碼。', 'red');
  }
}

// 把共用行程 doc 併入本機 myTrips（成員端只存精簡指標 + 內容快取）
function upsertCollabTripLocal(trip, fallbackRole) {
  const myEmail = (currentUser && currentUser.email) || '';
  const role = WAI_COLLAB.resolveRole(trip, myEmail, firebaseAuth && firebaseAuth.currentUser && firebaseAuth.currentUser.uid);
  const entry = {
    id: trip.id,
    title: trip.title || (trip.region ? `${trip.region} 共編行程` : '共編行程'),
    emoji: trip.emoji || '👥',
    cc: trip.cc || 'c2',
    days: trip.days || 1,
    region: trip.region || (trip.wizardData && trip.wizardData.dest) || '',
    budget: trip.budget || '',
    people: trip.people || `${(trip.memberEmails || []).length}人`,
    status: trip.status || 'planning',
    createdAt: trip.createdAt && typeof trip.createdAt === 'string' ? trip.createdAt : new Date().toLocaleDateString('zh-TW'),
    tripMode: 'collab',
    collab: true,
    role: role,
    ownerEmail: trip.ownerEmail || '',
    titleVersion: Number(trip.titleVersion || 0),
    inviteCode: trip.inviteCode || '',
    shareToken: trip.shareToken || '',
    wizardData: trip.wizardData || {},
    stops: trip.stops || []
  };
  const idx = myTrips.findIndex(t => t.id === trip.id);
  if (idx !== -1) myTrips[idx] = { ...myTrips[idx], ...entry };
  else myTrips.unshift(entry);
}

// ════════════════════════════════════════════════════
// COLLAB PANEL（多人共作：成員清單 + 偏好 + 團體生成）
// ════════════════════════════════════════════════════
const COLLAB_INTERESTS = [
  { v: '美食', t: '🍜 美食' }, { v: '文化', t: '🏛️ 文化' }, { v: '自然', t: '🌿 自然' },
  { v: '打卡', t: '📸 打卡' }, { v: '運動', t: '🏃 運動' }, { v: '放鬆', t: '😌 放鬆' }
];

// Week4 D2：從成員清單與 aggregateGroupProfile 輸出派生「視覺化用」資料。
// 只讀 profile／members，不動彙整演算法（AI prompt 的 buildGroupPreferenceLines 吃同一份 profile）。
function buildCollabAggView(profile, memberList) {
  const total = Math.max(1, memberList.length);
  const _label = (v) => { const hit = COLLAB_INTERESTS.find(i => i.v === v); return hit ? hit.t : v; };

  // 1) 興趣票數橫條（票數高→低；aggregateGroupProfile 已排序 interests）
  const bars = (profile.interests || []).map(v => {
    const n = (profile.interestVotes && profile.interestVotes[v]) || 0;
    return { label: _label(v), votes: n, pct: Math.round(n / total * 100) };
  });

  // 2) 節奏平手偵測：profile.pace 只回結果，自掃成員票（同 majorityPace 的計票邏輯）
  const paceCounts = {};
  memberList.forEach(m => {
    const p = (m.prefs || m.preferences || {}).pace;
    if (p) paceCounts[p] = (paceCounts[p] || 0) + 1;
  });
  const paceKeys = Object.keys(paceCounts);
  let paceTie = null;
  if (paceKeys.length > 1) {
    const maxN = Math.max(...paceKeys.map(k => paceCounts[k]));
    const top = paceKeys.filter(k => paceCounts[k] === maxN);
    if (top.length > 1) {
      const ownerMember = memberList.find(m => m && m.role === 'owner');
      paceTie = {
        detail: top.map(k => `${k} ${paceCounts[k]} 票`).join('：'),
        ownerName: (ownerMember && ownerMember.name) || '發起人'
      };
    }
  }

  // 3) 預算差距：各成員 parseBudgetNumber（取 tier 下限），差 2 倍以上且絕對差 ≥500 才提示
  const budgets = memberList
    .map(m => WAI_COLLAB.parseBudgetNumber((m.prefs || m.preferences || {}).budget))
    .filter(n => n > 0);
  let budgetGap = null;
  if (budgets.length >= 2) {
    const min = Math.min(...budgets), max = Math.max(...budgets);
    if (max >= min * 2 && max - min >= 500) budgetGap = { min, max };
  }

  // 4) avoid vs desired 衝突：avoid term（去 # 前綴）出現在他人想去清單文字中
  const conflicts = [];
  const desired = profile.desired || [];
  (profile.avoid || []).forEach(a => {
    const term = String(a.term || '').replace(/^#/, '');
    if (!term) return;
    desired.forEach(d => {
      const text = String(d.text || '');
      if (text.toLowerCase().includes(term.toLowerCase())) {
        conflicts.push({ term, avoidBy: a.by || [], desireBy: d.by || '', desireText: text });
      }
    });
  });
  const conflictTerms = new Set(conflicts.map(c => c.term));

  return { bars, paceTie, budgetGap, conflicts, conflictTerms, total };
}

function openCollabPanel(tripId) {
  if (!window.WAI_COLLAB || !firebaseDb) { showToast('多人協作需連線 Firebase', 'orange'); return; }
  if (collabState && collabState.unsub) collabState.unsub();
  if (collabState && collabState.prefsUnsub) collabState.prefsUnsub();
  collabState = { tripId, data: null, baseData: null, memberPrefs: {}, unsub: null, prefsUnsub: null, myEmail: (currentUser && currentUser.email) || '', myPrefsDraft: null, syncStatus: 'synced', notice: '' };
  const ov = document.getElementById('collabPanelOverlay');
  if (ov) ov.classList.add('open');
  const body = document.getElementById('collabPanelBody');
  if (body) body.innerHTML = '<div style="text-align:center;padding:40px;color:var(--ink3)">載入中…</div>';
  collabState.unsub = WAI_COLLAB.subscribeSharedTrip(tripId, (data) => {
    collabState.baseData = data;
    collabApplyMemberPrefs();
    if (collabState.syncStatus !== 'error') collabState.syncStatus = 'synced';
    upsertCollabTripLocal(collabState.data);
    saveState(); renderSideMyTrips();
    renderCollabPanel();
  }, (err) => {
    showToast('讀取共編行程失敗：' + (err && err.message || err), 'red');
  });
  collabState.prefsUnsub = WAI_COLLAB.subscribeMemberPrefs(tripId, (prefs) => {
    collabState.memberPrefs = prefs;
    collabApplyMemberPrefs();
    if (collabState.data) renderCollabPanel();
  }, () => collabSetSyncStatus('error', '成員偏好同步失敗，請點右上角重試重新讀取。'));
}
function collabApplyMemberPrefs() {
  if (!collabState || !collabState.baseData) return;
  const members = { ...(collabState.baseData.members || {}) };
  Object.entries(collabState.memberPrefs || {}).forEach(([key, pref]) => {
    if (members[key] && WAI_COLLAB.sameEmail(members[key].email, pref.email)) {
      members[key] = { ...members[key], prefs: pref.prefs, ready: pref.ready };
    }
  });
  collabState.data = { ...collabState.baseData, members };
}
function closeCollabPanel() {
  if (collabState && collabState.unsub) collabState.unsub();
  if (collabState && collabState.prefsUnsub) collabState.prefsUnsub();
  collabState = null;
  const ov = document.getElementById('collabPanelOverlay');
  if (ov) ov.classList.remove('open');
}

function startMyCollabTripsLiveSync(email) {
  if (myCollabTripsUnsub) { myCollabTripsUnsub(); myCollabTripsUnsub = null; }
  if (!email || !window.WAI_COLLAB || typeof WAI_COLLAB.subscribeMyCollabTrips !== 'function') return;
  myCollabTripsUnsub = WAI_COLLAB.subscribeMyCollabTrips(email, (trips) => {
    const liveIds = new Set(trips.map((t) => t.id));
    myTrips = myTrips.filter((t) => !t.collab || liveIds.has(t.id));
    trips.forEach((t) => upsertCollabTripLocal(t));
    saveState();
    renderSideMyTrips();
    const mtv = document.getElementById('myTripsView');
    if (mtv && mtv.style.display !== 'none') renderMyTrips();
  }, (err) => console.warn('共編行程列表即時同步中斷：', err));
}
window.addEventListener('pagehide', () => {
  if (collabState && collabState.unsub) collabState.unsub();
  if (collabState && collabState.prefsUnsub) collabState.prefsUnsub();
  collabState = null;
});
function collabMyRole() {
  if (!collabState || !collabState.data) return 'viewer';
  return WAI_COLLAB.resolveRole(collabState.data, collabState.myEmail,
    firebaseAuth && firebaseAuth.currentUser && firebaseAuth.currentUser.uid);
}
function collabSetSyncStatus(status, notice = '') {
  if (!collabState) return;
  collabState.syncStatus = status;
  collabState.notice = notice;
  renderCollabPanel();
}
function collabRetrySync() {
  if (!collabState) return;
  const tripId = collabState.tripId;
  openCollabPanel(tripId);
}
function collabSetBudget(v) { if (collabState && collabState.myPrefsDraft) collabState.myPrefsDraft.budget = v; }
function collabPickBudget(token) { if (collabState && collabState.myPrefsDraft) { collabState.myPrefsDraft.budget = token; renderCollabPanel(); } }
function collabSetDesired(v) { if (collabState && collabState.myPrefsDraft) collabState.myPrefsDraft.desiredSpots = v; }

function renderCollabPanel() {
  const body = document.getElementById('collabPanelBody');
  if (!body || !collabState || !collabState.data) return;
  // 若使用者正在面板內的輸入框打字（例如「想去的景點」），先別整塊重繪，否則 Firestore
  // 快照一到就 innerHTML 重建、害輸入框失焦。改記下「待重繪」，等 blur 後再補繪一次。
  const active = document.activeElement;
  if (active && body.contains(active) && (active.tagName === 'INPUT' || active.tagName === 'TEXTAREA')) {
    collabState._pendingRender = true;
    if (!active._collabBlurHooked) {
      active._collabBlurHooked = true;
      active.addEventListener('blur', () => {
        if (collabState && collabState._pendingRender) { collabState._pendingRender = false; renderCollabPanel(); }
      }, { once: true });
    }
    return;
  }
  collabState._pendingRender = false;
  const d = collabState.data;
  const isOwner = collabMyRole() === 'owner';
  const members = d.members || {};
  const memberList = Object.keys(members).map(k => members[k]);
  const link = WAI_COLLAB.buildShareLink(d.id, d.shareToken);
  // QR 改在本地產生。原本把分享連結（含 sharedId 與 token）當 query string 送給
  // api.qrserver.com——等於把私密邀請權杖交給第三方，而且服務慢或掛掉時 QR 就不見。
  const qrUrl = (typeof WAIQR !== 'undefined' && WAIQR) ? WAIQR.toDataURL(link, { size: 144 }) : '';
  const profile = WAI_COLLAB.aggregateGroupProfile(members);
  const myMember = WAI_COLLAB.memberForEmail(d, collabState.myEmail);
  if (!collabState.myPrefsDraft) {
    collabState.myPrefsDraft = WAI_COLLAB.normalizePrefs((myMember && myMember.prefs) || {});
  }
  const myPrefs = collabState.myPrefsDraft;
  const hasStops = Array.isArray(d.stops) && d.stops.length;
  const isPlanned = !!(d.wizardData && d.wizardData.dest); // owner 是否已用精靈填過行程內容
  const myAvoidStr = [...(myPrefs.avoidTags || []).map(t => t.replace(/^#/, '')), myPrefs.avoid].filter(Boolean).join('、');

  body.innerHTML = `
    <div class="collab-trip-head">
      <div class="collab-trip-head-main"><div class="collab-trip-title">${d.emoji || '👥'} ${escapeHtml(d.title || '共編行程')}</div>
      <div class="collab-trip-sub">${escapeHtml(d.region || '')} · ${escapeHtml(d.days || '')} · ${memberList.length}/${d.maxMembers || 10} 人</div></div>
      <button class="collab-sync ${collabState.syncStatus}" ${collabState.syncStatus === 'error' ? 'onclick="collabRetrySync()"' : ''}>${collabState.syncStatus === 'saving' ? '同步中…' : collabState.syncStatus === 'error' ? '同步失敗・重試' : '已同步'}</button>
    </div>
    ${collabState.notice ? `<div class="collab-inline-notice">${escapeHtml(collabState.notice)}</div>` : ''}
    <div class="collab-section">
      <div class="collab-section-title">邀請朋友（上限 ${d.maxMembers || 10} 人）</div>
      <div class="collab-invite-row">
        <span class="collab-code">${d.inviteCode ? WAI_COLLAB.formatInviteCode(d.inviteCode) : '—'}</span>
        <button class="collab-mini-btn" onclick="collabCopy('${d.inviteCode ? WAI_COLLAB.formatInviteCode(d.inviteCode) : ''}','邀請碼')">📋 複製碼</button>
      </div>
      <div class="collab-invite-row">
        <input class="collab-link-input" id="collabShareLink" readonly value="${link}">
        <button class="collab-mini-btn" onclick="collabCopy(document.getElementById('collabShareLink').value,'唯讀分享連結')">🔗 複製唯讀連結</button>
      </div>
      <div class="collab-qr-row">${qrUrl ? `<img class="collab-qr" src="${qrUrl}" alt="行程邀請 QR Code">` : '<div class="collab-qr-fallback">連結太長，無法產生 QR；請改用邀請碼或複製連結加入。</div>'}<div class="collab-qr-fallback">掃描 QR Code 即可開啟唯讀預覽；邀請朋友共作請使用上方邀請碼。</div></div>
      ${(() => {
        // 好友一鍵邀請：列出尚未加入這份行程的好友，點一下複製含邀請碼的訊息（零 schema 變動）
        try {
          const joined = new Set((d.memberEmails || []).map(x => String(x).toLowerCase()));
          const cands = (friendsAccepted || []).map(f => friendOtherParty(f))
            .filter(o => o && o.email && !joined.has(String(o.email).toLowerCase()));
          window.__collabFriendCands = cands.slice(0, 12);
          if (!window.__collabFriendCands.length) return '';
          return `<div class="collab-pref-label" style="margin-top:8px;">從好友邀請（點一下複製邀請訊息，貼給對方即可）</div>
            <div class="collab-friend-chips">${window.__collabFriendCands.map((o, i) =>
              `<button class="collab-mini-btn" onclick="collabInviteFriend(${i})">👤 ${escapeHtml(String(o.name || o.email))}</button>`).join('')}</div>`;
        } catch (_e) { return ''; }
      })()}
      ${isOwner ? `<div class="collab-danger-row"><button class="collab-mini-btn ghost" onclick="collabRevoke()">停用邀請碼</button></div>` : ''}
    </div>
    <div class="collab-section">
      <div class="collab-section-title">成員（${memberList.length}）${isOwner ? '<span style="font-weight:normal;color:#8fa4b8;"> · 擁有者可調整每位成員的權限</span>' : ''}</div>
      ${memberList.map(m => collabMemberRowHtml(m, isOwner)).join('')}
    </div>
    <div class="collab-section">
      <div class="collab-section-title">我的偏好（會納入團體生成）</div>
      <div class="collab-pref-label">興趣（最多 3）</div>
      <div class="wizard-chips">
        ${COLLAB_INTERESTS.map(o => `<button class="wizard-tag${(myPrefs.interests || []).includes(o.v) ? ' active' : ''}" type="button" onclick="collabToggleInterest('${o.v}')">${o.t}</button>`).join('')}
      </div>
      <div class="collab-pref-label">節奏</div>
      <div class="wizard-chips">
        ${['輕快', '平衡', '悠閒'].map(p => `<button class="wizard-tag${myPrefs.pace === p ? ' active' : ''}" type="button" onclick="collabSetPace('${p}')">${PACE_EMOJI[p]} ${paceLabel(p)}</button>`).join('')}
      </div>
      <div class="collab-pref-label">每人預算（當地餐飲與付費體驗）</div>
      <div class="wizard-choice-grid" style="grid-template-columns:repeat(2,1fr)">
        ${BUDGET_TIERS.map(t => {
          const open = t.perMax == null;
          const per = open ? `每人 ${formatMoney(t.perMin)} 以上` : (t.perMin > 0 ? `每人 ${formatMoney(t.perMin)}–${formatMoney(t.perMax)}` : `每人 ${formatMoney(t.perMax)} 內`);
          const token = `${t.key}（${per}）`;
          const on = myPrefs.budget === token;
          return `<button class="wizard-tag${on ? ' active' : ''}" type="button" onclick="collabPickBudget('${token}')">${t.key}<br><span style="font-size:12px;opacity:.7">${per}</span></button>`;
        }).join('')}
      </div>
      <div class="collab-pref-label">想去的景點（選填）</div>
      <input class="collab-text-input" placeholder="例如 三仙台、伯朗大道" value="${(myPrefs.desiredSpots || '').replace(/"/g, '&quot;')}" oninput="collabSetDesired(this.value)">
      ${myAvoidStr ? `<div class="collab-pref-note">你的帳號禁忌會自動納入全團硬限制：${myAvoidStr}</div>` : ''}
      <button class="collab-save-btn" onclick="collabSaveMyPrefs()">儲存我的偏好</button>
    </div>
    <div class="collab-section">
      <div class="collab-section-title">團體綜合（即時）</div>
      <div class="collab-agg">
        ${(() => {
          // Week4 D2：純呈現層視覺化（票數橫條／平手／預算差距／衝突標紅），彙整演算法不動
          const agg = buildCollabAggView(profile, memberList);
          const barsHtml = agg.bars.length
            ? `<div class="collab-agg-bars">${agg.bars.map(b => `
                <div class="collab-agg-bar-row">
                  <span class="collab-agg-bar-label">${escapeHtml(b.label)}</span>
                  <span class="collab-agg-bar-track"><span class="collab-agg-bar-fill" style="width:${b.pct}%"></span></span>
                  <span class="collab-agg-bar-count">${b.votes}/${agg.total}</span>
                </div>`).join('')}</div>`
            : `<div class="collab-agg-empty">成員填寫偏好後，這裡會顯示興趣票數統計</div>`;
          // 節奏（多數決）與預算（平均）都把成員意見「收斂成一個值」，禁忌刻意不收斂——
          // 任一成員提出就整條保留，不因人數被多數決排除（過敏／宗教／身體狀況不該投票決定）。
          // 另外兩行都標了方法，這行不標的話，使用者只會看到一串詞，看不出這是刻意的差別待遇。
          const avoidHtml = profile.avoid.length
            ? `<div>避免：${profile.avoid.map(a => {
                const term = a.term.replace(/^#/, '');
                const cls = agg.conflictTerms.has(term) ? ' class="collab-agg-conflict"' : '';
                return `<span${cls}>${escapeHtml(term)}</span>`;
              }).join('、')}（任一人提出就保留）</div>`
            : '';
          const desiredHtml = (profile.desired || []).length
            ? `<div>想去：${profile.desired.map(dd => {
                const hit = agg.conflicts.some(c => String(dd.text || '').toLowerCase().includes(c.term.toLowerCase()));
                return `<span${hit ? ' class="collab-agg-conflict"' : ''}>${escapeHtml(dd.text)}</span>${dd.by ? `<span class="collab-agg-by">(${escapeHtml(dd.by)})</span>` : ''}`;
              }).join('、')}</div>`
            : '';
          return `
            ${barsHtml}
            <div>節奏：<b>${escapeHtml(profile.pace)}</b>（多數決）</div>
            ${agg.paceTie ? `<div class="collab-agg-hint">⚖️ 節奏平手（${escapeHtml(agg.paceTie.detail)}），依發起人「${escapeHtml(agg.paceTie.ownerName)}」的偏好為準</div>` : ''}
            <div>預算：<b>${escapeHtml(profile.budget || '—')}</b>（平均）</div>
            ${agg.budgetGap ? `<div class="collab-agg-hint">💰 成員預算差距較大：最低 $${agg.budgetGap.min.toLocaleString('en-US')}／最高 $${agg.budgetGap.max.toLocaleString('en-US')}，已取全員平均</div>` : ''}
            ${avoidHtml}
            ${desiredHtml}
            ${agg.conflicts.map(c => `<div class="collab-agg-hint collab-agg-conflict">⚠️ 「${escapeHtml(c.term)}」被 ${escapeHtml((c.avoidBy || []).join('、') || '成員')} 列為避免，但 ${escapeHtml(c.desireBy || '成員')} 的想去清單提到它——生成時以避免為最高優先</div>`).join('')}`;
        })()}
      </div>
    </div>
    <div class="collab-actions">
      ${isOwner
        ? (!isPlanned
            ? `<button class="collab-primary" onclick="startCollabPlanning('${d.id}')">🧭 開始規劃行程內容</button>
               <div class="collab-wait">${escapeHtml(collabPlanningHint(memberList))}</div>`
            : `<button class="collab-primary" onclick="collabGenerate()">🚀 生成團體行程</button>
               <button class="collab-secondary" onclick="startCollabPlanning('${d.id}')">✏️ 修改行程設定</button>`)
        : `<div class="collab-wait">由擁有者規劃與生成行程；你可先填好偏好。</div>`}
      ${hasStops ? `<button class="collab-secondary" onclick="collabOpenInEditor()">${WAI_COLLAB.canEdit(collabMyRole()) ? '✏️ 在編輯器開啟' : '👁 唯讀檢視'}</button>` : ''}
    </div>`;
}

function collabMemberRowHtml(m, ownerControls) {
  const isMe = WAI_COLLAB.sameEmail(m.email, collabState.myEmail);
  const readyDot = m.ready ? '<span class="collab-ready on" title="已填偏好">●</span>' : '<span class="collab-ready" title="未填偏好">○</span>';
  let roleCell;
  if (ownerControls && m.role !== 'owner') {
    roleCell = `<select class="collab-role-sel" onchange="collabSetRole('${m.email}', this.value)">
        ${['viewer', 'editor'].map(r => `<option value="${r}" ${m.role === r ? 'selected' : ''}>${WAI_COLLAB.roleLabel(r)}</option>`).join('')}
      </select>`;
  } else {
    roleCell = `<span class="collab-role-badge ${m.role}">${WAI_COLLAB.roleLabel(m.role)}</span>`;
  }
  return `<div class="collab-member-row">${readyDot}<span class="collab-member-name">${escapeHtml(m.name || m.email)}${isMe ? '（你）' : ''}</span>${roleCell}</div>`;
}

function collabCopy(text, label) {
  navigator.clipboard.writeText(text).then(() => showToast((label || '內容') + '已複製！', 'green')).catch(() => showToast(text, 'green'));
}
function collabToggleInterest(v) {
  if (!collabState || !collabState.myPrefsDraft) return;
  const arr = collabState.myPrefsDraft.interests = collabState.myPrefsDraft.interests || [];
  const i = arr.indexOf(v);
  if (i > -1) arr.splice(i, 1);
  else { if (arr.length >= 3) return showToast('最多選擇三個興趣', 'orange'); arr.push(v); }
  renderCollabPanel();
}
function collabSetPace(p) { if (collabState && collabState.myPrefsDraft) { collabState.myPrefsDraft.pace = p; renderCollabPanel(); } }
async function collabSaveMyPrefs() {
  if (!collabState || !collabState.data) return;
  collabSetSyncStatus('saving');
  try {
    await WAI_COLLAB.setMemberPrefs(collabState.tripId, collabState.myEmail, collabState.myPrefsDraft);
    collabSetSyncStatus('synced');
    showToast('已儲存你的偏好', 'green');
  } catch (e) { collabSetSyncStatus('error', '偏好同步失敗，請點右上角重試重新讀取。'); showToast('儲存失敗：' + (e && e.message || e), 'red'); }
}
async function collabSetRole(email, role) {
  collabSetSyncStatus('saving');
  try { await WAI_COLLAB.setMemberRole(collabState.tripId, email, role); collabSetSyncStatus('synced'); showToast('已更新角色', 'green'); }
  catch (e) { collabSetSyncStatus('error', '角色更新失敗，請重新讀取後再試。'); showToast('更新角色失敗：' + (e && e.message || e), 'red'); }
}
async function collabRevoke() {
  if (!collabState || !collabState.data) return;
  if (!window.confirm('停用後，新的朋友將無法再用此邀請碼加入。要繼續嗎？')) return;
  collabSetSyncStatus('saving');
  try { await WAI_COLLAB.revokeInvite(collabState.tripId, collabState.data.inviteCode); collabSetSyncStatus('synced'); showToast('邀請碼已停用', 'green'); }
  catch (e) { collabSetSyncStatus('error', '邀請碼停用失敗，請重新讀取後再試。'); showToast('停用失敗', 'red'); }
}
function collabOpenInEditor() {
  if (!collabState) return;
  // 登入成員（含 viewer）一律直接開啟：planner 依 members 角色自動套唯讀。
  // 不可帶 guest=1——訪客路徑已改走後端 shareToken 核對，登入成員沒有 token 會被誤判成「分享連結無效」。
  window.location = 'ai-travel-planner-v8.html?sharedId=' + encodeURIComponent(collabState.tripId);
}
// 共用：彙整當下成員偏好 → 直接生成團體行程（「精靈建立完」與「面板再生成」共用）
async function runCollabGeneration(tripId) {
  // 取重生成鎖：避免 owner 與可編輯成員同時重生成互相覆蓋
  let locked = false;
  try {
    const lock = await WAI_COLLAB.acquireRegenLock(tripId, {
      email: (currentUser && currentUser.email) || '',
      name: (currentUser && currentUser.name) || ''
    });
    if (!lock.ok) {
      showToast(`${lock.holder} 正在重新生成，請稍候`, 'orange');
      _restoreWizNav(); hideGenMiniBar();
      return;
    }
    locked = true;
  } catch (e) { console.warn('取重生成鎖失敗（略過鎖）：', e); }

  try {
    const d = await WAI_COLLAB.getSharedTrip(tripId);
    if (!d) { showToast('找不到共編行程', 'red'); return; }
    const profile = WAI_COLLAB.aggregateGroupProfile(d.members || {});
    const wData = { ...(d.wizardData || {}) };
    wData.groupProfile = profile;            // 觸發 buildPreferenceLines 團體分支
    wData.interests = profile.interests.slice(0, 4);
    wData.pace = profile.pace;
    if (profile.budget) wData.budget = profile.budget;
    if (profile.desired && profile.desired.length) wData.desiredSpots = profile.desired.map(x => x.text).join('、');
    let trip = myTrips.find(t => t.id === d.id);
    if (!trip) { trip = { id: d.id, title: d.title, region: d.region, days: d.days, collab: true, role: 'owner', wizardData: wData, stops: [] }; myTrips.unshift(trip); }
    trip.wizardData = wData;
    await _doGeneration(trip, wData);
    // F1：重生成完成 → 通知其他成員（id 帶小時戳，一小時內重複生成只發一次；失敗靜默）
    if (window.WAI_NOTIFY && Array.isArray(d.memberEmails) && d.memberEmails.length && Array.isArray(trip.stops) && trip.stops.length) {
      const _now = new Date();
      const _hourStamp = `${_now.getFullYear()}${String(_now.getMonth() + 1).padStart(2, '0')}${String(_now.getDate()).padStart(2, '0')}${String(_now.getHours()).padStart(2, '0')}`;
      WAI_NOTIFY.pushToMany(d.memberEmails, WAI_NOTIFY.nid(['trip_regen', tripId, _hourStamp]), {
        type: 'trip_regenerated', tripId: tripId, tripTitle: trip.title || d.title || '共編行程',
        fromName: (currentUser && currentUser.name) || '', message: ''
      });
    }
  } finally {
    if (locked) { try { await WAI_COLLAB.releaseRegenLock(tripId); } catch (e) {} }
  }
}

async function collabGenerate() {
  if (!collabState || !collabState.data) return;
  if (collabMyRole() !== 'owner') { showToast('只有擁有者可以生成', 'orange'); return; }
  const tripId = collabState.tripId;
  closeCollabPanel();
  showGenMiniBar(); // 面板再生成時精靈未開，改用右下角浮動進度卡顯示
  await runCollabGeneration(tripId);
}

function openJourneyJoin(presetCode = '') {
  if (!isLoggedIn) { openLogin(); return; }
  const input = document.getElementById('journeyCodeInput');
  if (input) input.value = presetCode ? WAI_COLLAB.formatInviteCode(presetCode) : '';
  const title = document.getElementById('journeyPreviewTitle');
  const meta = document.getElementById('journeyPreviewMeta');
  if (title) title.textContent = '加入朋友的共編行程';
  if (meta) meta.textContent = '輸入朋友分享的邀請碼即可加入並查看行程';
  document.getElementById('journeyOverlay').classList.add('open');
}

function closeJourneyJoin() { document.getElementById('journeyOverlay').classList.remove('open'); }

async function submitJourneyCode() {
  const input = document.getElementById('journeyCodeInput');
  const code = input ? input.value : '';
  // 改走真實加入：輸入朋友分享的邀請碼即可加入共編行程（不再比對寫死的 prototype 碼）
  await joinSharedTripByCode(code, closeJourneyJoin);
}

function openJourneyItineraryPreview() {
  document.getElementById('journeyItineraryTitle').textContent = currentTripTitle;
  document.getElementById('journeyItinerarySub').textContent = '先快速確認集合資訊與沿途節奏，沒問題後就能直接開始這趟旅程。';
  document.getElementById('journeyItineraryTime').textContent = `${currentTripWindow.start} - ${currentTripWindow.end}`;
  document.getElementById('journeyItineraryMeeting').textContent = currentJourneyMeetingPoint;
  document.getElementById('journeyItineraryCode').textContent = currentInviteCode;
  document.getElementById('journeyItineraryStops').innerHTML = currentJourneyPreviewStops.map(stop => `
    <div class="journey-stop">
      <div class="journey-stop-time">${stop.time}</div>
      <div class="journey-stop-card">
        <div class="journey-stop-title">${stop.title}</div>
        <div class="journey-stop-desc">${stop.desc}</div>
        <div class="journey-stop-tags">
          ${stop.tags.map(tag => `<span class="journey-stop-tag">${tag}</span>`).join('')}
        </div>
      </div>
    </div>
  `).join('');
  document.getElementById('journeyItineraryOverlay').classList.add('open');
}

function closeJourneyItineraryPreview() {
  document.getElementById('journeyItineraryOverlay').classList.remove('open');
}

function confirmJourneyEntry() {
  const existingTrip = myTrips.find(t =>
    t.source === 'mobile-journey-code' &&
    t.itineraryId === currentItineraryId &&
    t.inviteCode === currentInviteCode
  );

  if (!existingTrip) {
    myTrips.unshift({
      id: 'my_'+Date.now(), title: `${currentTripTitle} (手機進入)`, emoji: '📱', cc: 'c2',
      days: 1, region: '高雄', budget: '$500', people: '2人',
      status: 'upcoming', createdAt: new Date().toLocaleDateString('zh-TW'),
      inviteCode: currentInviteCode, itineraryId: currentItineraryId, source: 'mobile-journey-code'
    });
    saveState(); renderSideMyTrips(); renderMyTrips();
  }

  closeJourneyItineraryPreview();
  pendingJourneyEntry = null;
  showToast('已進入旅遊，並打開行程預覽。', 'green');
  showMainView('mytrips');
}

// ══════════════════════════════════════════════════
// UTILS
// ══════════════════════════════════════════════════
let toastTimer;
function showToast(msg, type='') {
  const t = document.getElementById('toast');
  t.textContent = msg; t.className = `toast${type?' '+type:''} show`;
  clearTimeout(toastTimer); toastTimer = setTimeout(()=>t.classList.remove('show'), 2400);
}

// ── a11y：Esc 關閉最上層彈窗（原本所有 overlay 只能點外部/右上關閉，鍵盤無法操作）──
document.addEventListener('keydown', (e) => {
  if (e.key !== 'Escape') return;
  // 動態建立的彈窗不是 .overlay，需優先處理（通知大視窗疊在最上層，先關它）。
  if (document.getElementById('notifFullOverlay')) {
    e.preventDefault();
    closeNotifFullView();
    return;
  }
  if (document.getElementById('waiActionModal')) {
    e.preventDefault();
    closeWaiActionModal();
    return;
  }
  const opens = [...document.querySelectorAll('.overlay.open')];
  if (!opens.length) return;
  // 取 z-index 最高者（login/prefWizard 是 300，一般 overlay 較低）
  let top = opens[0], tz = -1;
  opens.forEach((o) => { const z = parseInt(getComputedStyle(o).zIndex, 10) || 0; if (z >= tz) { tz = z; top = o; } });
  // wizardOverlay 有生成中收合邏輯，走專屬 closeWizard；其餘直接關
  if (top.id === 'wizardOverlay' && typeof closeWizard === 'function') closeWizard();
  else top.classList.remove('open');
});
// 彈窗補 dialog 語意（螢幕報讀器才會宣告對話框情境）
document.querySelectorAll('.overlay').forEach((ov) => {
  const modal = ov.firstElementChild;
  if (modal && !modal.hasAttribute('role')) { modal.setAttribute('role', 'dialog'); modal.setAttribute('aria-modal', 'true'); }
});
