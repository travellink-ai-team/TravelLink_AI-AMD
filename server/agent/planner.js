'use strict';
/* ══════════════════════════════════════════════════
   行程生成 Agent 的「算」：排序、排時間、驗證（純程式，不呼叫 LLM）
   ──────────────────────────────────────────────────
   方案 C 的分工：AI 負責「選」（挑哪些景點、哪幾家餐廳），
   這支程式負責「算」——
     1. 景點依地理順路排序（最近鄰 + 2-opt，起訖點固定）
     2. 午餐／晚餐插到時鐘走到用餐時段的位置
     3. 依車程逐站排時間；早到就等開門（最多等 90 分）
     4. 用 itinerary.js 的 validate 檢查營業時間、重疊、預算、降雨，
        再加上生成專用的規則（行程太短、缺用餐、回不到終點）
   結果（含 violations）回給 LLM 修改，最多 3 輪。

   只處理「一日遊」；多日行程維持前端原本的一次生成流程。
   前端 ai-travel-explore-final.js 的排序（sortStopsByNearestRoute、
   placeMealStopsAtMealTimes、applyTripPlanningRules）綁著 DOM 與 Places，
   沒辦法直接 require，這裡用同樣的原則在 Node 端重寫一份精簡版。
   ══════════════════════════════════════════════════ */
const D = require('./data');
const I = require('./itinerary');
const F = require('./ferry');
const B = require('./boundaries');

const MAX_DRAFT_STOPS = 14;
const MAX_WAIT_FOR_OPEN = 90;      // 早到最多等幾分鐘開門
const ISLANDS = new Set(['綠島', '蘭嶼']);

// 目的地中心（與前端 DESTINATION_CENTER_MAP 一致的那幾個；其他用本地資料該區的重心）
const DEST_CENTER = {
  台東: { lat: 22.755, lng: 121.144 },
  台東市: { lat: 22.755, lng: 121.144 },
  綠島: { lat: 22.666, lng: 121.494 },
  蘭嶼: { lat: 22.046, lng: 121.548 },
  知本: { lat: 22.695, lng: 121.017 },
  三仙台: { lat: 23.123, lng: 121.414 },
  海濱公園: { lat: 22.755, lng: 121.158 }
};

// 興趣 → 名稱／簡介關鍵字（search_local_poi 用來加分，不是硬篩）
const INTEREST_RE = {
  自然: /步道|森林|公園|海岸|瀑布|山|湖|溪|濕地|草原|自然|生態|海灘/,
  文化: /博物|館|文化|部落|廟|宮|寺|教堂|藝術|藝文|歷史|古蹟|聚落|紀念/,
  打卡: /打卡|網美|地標|觀景|大橋|彩繪|裝置|天空|燈塔|夕陽/,
  運動: /自行車|單車|衝浪|浮潛|潛水|登山|獨木舟|步道|騎/,
  放鬆: /溫泉|海灘|沙灘|咖啡|書店|草原|休憩|公園/,
  親子: /牧場|農場|公園|園區|博物|動物|遊樂|親子/,
  美食: /市場|夜市|老街|小吃|美食/
};

const normDest = (s) => String(s || '').replace(/臺/g, '台').trim();

// ── 同一處景點的不同名稱 ──
// 本地資料常把一個景點拆成好幾筆：「睡美人岩」「哈巴狗與睡美人岩」「小長城步道 & 睡美人與哈巴狗」、
// 「小野柳」「富岡地質公園 (小野柳)」。名稱不互相包含、座標也可能差幾百公尺（觀景步道 vs 岩石本身），
// 所以拆出「地標詞」比對：共用地標詞且相距 3 km 內，或兩點不到 25 m，就當同一處。
// 限 3 km 是為了擋「三和海濱公園」與「海濱公園」這種只是撞名、實際差 15 km 的；
// 純距離只抓 25 m，市區景點常相鄰（臺東故事館與鐵花新聚落差 80 m，是兩個景點）。
// 只比景點對景點，景點旁邊的餐廳不算重複。
const LANDMARK_SUFFIX = /(觀景平台|觀景台|休憩區|遊憩區|風景區|步道|公園|遺址|部落|海灘|沙灘|園區|燈塔|漁港|岩|洞)$/;
const LANDMARK_STOPWORDS = new Set(['台東', '臺東', '綠島', '蘭嶼', '東河', '成功', '長濱', '池上', '關山', '鹿野', '太麻里',
  '知本', '卑南', '大武', '金峰', '達仁', '延平', '海端', '小長城', '海濱']);
const SAME_SPOT_KM = 0.025;
const SAME_LANDMARK_KM = 3;

function landmarkTokens(name) {
  return String(name || '').replace(/[A-Za-z0-9'’.,-]+/g, ' ')
    .split(/[&＆、與和及()（）\s・·—~～/]+/)
    .map((s) => s.trim().replace(LANDMARK_SUFFIX, '').replace(LANDMARK_SUFFIX, ''))
    .filter((t) => t.length >= 2 && !LANDMARK_STOPWORDS.has(t));
}

// 「X遊客中心」和 X 開頭的景點（卑南遊客中心 × 卑南遺址公園，相距 2.4 km）：遊客中心不值得另佔一站
const VISITOR_CENTER_RE = /(遊客中心|旅遊服務中心|服務中心|旅客服務中心)$/;
const VISITOR_CENTER_KM = 5;
const VISITOR_CENTER_REGION_PREFIX = new Set(['台東', '綠島', '蘭嶼']);
function placeCore(name) { return String(name || '').replace(/[（(][^）)]*[）)]/g, '').replace(/臺/g, '台').replace(/\s/g, ''); }
function visitorCenterOf(vc, other) {
  const n = placeCore(vc.name), o = placeCore(other.name);
  const m = n.match(VISITOR_CENTER_RE);
  if (!m || VISITOR_CENTER_RE.test(o)) return false;
  const prefix = n.slice(0, n.length - m[0].length);
  // 前綴只是地區名（綠島遊客中心 × 綠島人權文化園區）就不算：那是同一個島上的不同景點
  return prefix.length >= 2 && !VISITOR_CENTER_REGION_PREFIX.has(prefix) && o.startsWith(prefix);
}

// 飲料、冰品、咖啡、甜點不算正餐：不能當午餐或晚餐，也不算「兩餐太近」
const SNACK_NAME_RE = /茶飲|飲料|手搖|冰城|冰店|冰品|豆花|甜點|咖啡|cafe|coffee|烘焙|麵包|甜湯|剉冰|雪花冰/i;
const isSnack = (place) => SNACK_NAME_RE.test(String((place && place.name) || ''));
const MEAL_GAP_MIN = 150;       // 兩間正餐開始時間至少隔 2.5 小時
const MEAL_GAP_NOTICE_MIN = 90; // 使用者指定時不擋，太近才提醒

/** 兩筆地點是不是同一處景點（需要 lat/lng） */
function sameAttraction(a, b) {
  if (!a || !b || a === b) return false;
  const km = D.haversineKm(a, b);
  if (!Number.isFinite(km)) return false;   // 缺座標就無法用距離把關，寧可不合併
  if (km <= SAME_SPOT_KM) return true;
  if (km <= VISITOR_CENTER_KM && (visitorCenterOf(a, b) || visitorCenterOf(b, a))) return true;
  if (km > SAME_LANDMARK_KM) return false;
  // 名稱不共用字、但在同一個園區裡（鷺鷥湖在臺東森林公園裡、龍鳳佛堂在鯉魚山上）：看座標是否落在對方的 OSM 輪廓內
  if (B.contains(a, b) || B.contains(b, a) || B.sharedOutline(a, b)) return true;
  const ta = landmarkTokens(a.name);
  const tb = landmarkTokens(b.name);
  return ta.some((t) => tb.some((u) => u === t || (t.length >= 3 && u.includes(t)) || (u.length >= 3 && t.includes(u))));
}

/** 目的地 → 島名（綠島／蘭嶼），本島回 null */
function islandOf(dest) {
  const d = normDest(dest);
  for (const x of ISLANDS) if (d.includes(x)) return x;
  return null;
}

function centerOf(dest) {
  const d = normDest(dest);
  if (DEST_CENTER[d]) return DEST_CENTER[d];
  const hit = Object.keys(DEST_CENTER).find((k) => d.includes(k));
  if (hit) return DEST_CENTER[hit];
  const pts = D.data().pois.filter((p) => p.region === d);
  if (pts.length) {
    return { lat: pts.reduce((a, p) => a + p.lat, 0) / pts.length, lng: pts.reduce((a, p) => a + p.lng, 0) / pts.length };
  }
  return DEST_CENTER['台東'];
}

/**
 * 起點／終點：使用者填的地名在本地資料找得到就用它；離島用島內港（ferry-config）；
 * 其他用目的地中心。回傳 { name, lat, lng, source }。
 */
// 使用者座標離目的地中心多遠以內才採用：本島一日遊 80 km、離島只收島上的點
const USER_POINT_MAX_KM = { mainland: 80, island: 12 };

function endpointOf(name, dest, coord) {
  const island = islandOf(dest);
  const n = String(name || '').trim();
  if (n) {
    const p = D.findPlace(n);
    if (p) return { name: p.name, lat: p.lat, lng: p.lng, source: 'local' };
  }
  // 本地資料認不得的門牌、路名：用前端 Places 查好的座標，排程才會和地圖畫的是同一個點
  if (n && coord && Number.isFinite(coord.lat) && Number.isFinite(coord.lng)
      && D.haversineKm(coord, centerOf(dest)) <= (island ? USER_POINT_MAX_KM.island : USER_POINT_MAX_KM.mainland)) {
    return { name: n, lat: coord.lat, lng: coord.lng, source: 'user' };
  }
  if (island && F.CONFIG[island] && F.CONFIG[island].islandHarbor) {
    const h = F.CONFIG[island].islandHarbor;
    return { name: h.name, lat: h.lat, lng: h.lng, source: 'harbor' };
  }
  const c = centerOf(dest);
  return { name: n || `${normDest(dest) || '台東'}市區`, lat: c.lat, lng: c.lng, source: 'center' };
}

/** 一日遊的用餐時段：時段內有涵蓋才需要排 */
function mealSlots(startMin, endMin) {
  const slots = [];
  if (startMin <= 12 * 60 + 30 && endMin >= 13 * 60 + 30) slots.push({ meal: 'lunch', label: '午餐', target: 12 * 60, from: 11 * 60, to: 14 * 60 });
  if (startMin <= 18 * 60 && endMin >= 19 * 60) slots.push({ meal: 'dinner', label: '晚餐', target: 18 * 60, from: 17 * 60, to: 20 * 60 + 30 });
  return slots;
}

/** 依時長建議站數（含餐廳），與前端 getDurationStopRange 同一個量級，但扣掉車程後略保守 */
function stopRange(durationMin, pace) {
  let min = Math.max(2, Math.round(durationMin / 100));
  let max = Math.max(min + 1, Math.round(durationMin / 55));
  if (/輕鬆|慢/.test(String(pace || ''))) { max = Math.max(min, max - 2); min = Math.max(2, min - 1); }
  if (/緊湊|充實|快/.test(String(pace || ''))) { min += 1; max += 1; }
  return { min, max: Math.min(MAX_DRAFT_STOPS, max) };
}

const ceil5 = (m) => Math.ceil(m / 5) * 5;

/**
 * 依序排時間。stops：[{ place, stayMin }]（已排好順序）。
 * 回傳 [{ ...stop, start, end, wait }] 與回到終點的時間。
 */
function schedule(stops, startPoint, endPoint, startMin, date) {
  let t = startMin;
  let prev = startPoint;
  const out = [];
  for (const s of stops) {
    const move = D.travelMinutes(prev, s.place);
    let start = ceil5(t + move);
    let wait = 0;
    const h = D.hoursOn(s.place, date);
    if (h.status === 'open' && h.windows.length) {
      // 還沒開門：等到最近一個能完整待完的營業時段開始（不超過 90 分）
      const w = h.windows.find((x) => start < x.open && x.open - start <= MAX_WAIT_FOR_OPEN && x.open + s.stayMin <= x.close);
      if (w && !D.fitsHours(h, start, start + s.stayMin)) { wait = w.open - start; start = w.open; }
    }
    out.push({ ...s, start, end: start + s.stayMin, wait, moveMin: move });
    t = start + s.stayMin;
    prev = s.place;
  }
  const back = D.travelMinutes(prev, endPoint);
  return { stops: out, endMin: t, backMin: back, returnMin: t + back };
}

function routeCost(order, startPoint, endPoint) {
  let sum = 0;
  let prev = startPoint;
  for (const s of order) { sum += D.travelMinutes(prev, s.place); prev = s.place; }
  return sum + D.travelMinutes(prev, endPoint);
}

/** 最近鄰 + 2-opt（起訖點固定） */
function orderByRoute(items, startPoint, endPoint) {
  const rest = items.slice();
  const order = [];
  let cur = startPoint;
  while (rest.length) {
    let bi = 0, bd = Infinity;
    rest.forEach((s, i) => { const d = D.haversineKm(cur, s.place); if (d < bd) { bd = d; bi = i; } });
    cur = rest[bi].place;
    order.push(rest.splice(bi, 1)[0]);
  }
  let best = order, bestCost = routeCost(order, startPoint, endPoint);
  for (let improved = true, guard = 0; improved && guard < 50; guard++) {
    improved = false;
    for (let i = 0; i < best.length - 1; i++) {
      for (let k = i + 1; k < best.length; k++) {
        const cand = best.slice(0, i).concat(best.slice(i, k + 1).reverse(), best.slice(k + 1));
        const c = routeCost(cand, startPoint, endPoint);
        if (c + 0.01 < bestCost) { best = cand; bestCost = c; improved = true; }
      }
    }
  }
  return best;
}

/** 把餐廳插到最接近用餐時間、繞路最少的位置 */
function insertMeals(route, meals, startPoint, endPoint, startMin, date) {
  let seq = route.slice();
  for (const m of meals) {
    let best = null;
    for (let pos = 0; pos <= seq.length; pos++) {
      const cand = seq.slice(0, pos).concat([m], seq.slice(pos));
      const sch = schedule(cand, startPoint, endPoint, startMin, date);
      const at = sch.stops[pos].start;
      const off = at < m.slot.from ? m.slot.from - at : (at > m.slot.to ? at - m.slot.to : 0);
      const score = off * 3 + Math.abs(at - m.slot.target) * 0.5 + routeCost(cand, startPoint, endPoint);
      if (!best || score < best.score) best = { score, cand };
    }
    seq = best.cand;
  }
  return seq;
}

/**
 * 營業時間優先的小幅調整：在路線順序上試「兩兩交換」，
 * 能減少營業時間違規或等開門的時間就採用（車程只多一點點也接受）。最多 3 輪。
 * 例：09:00 出發，11:00 才開的鯉魚山排第二站會空等一小時 → 換到午餐後。
 */
function fixHoursBySwaps(seq, startPoint, endPoint, startMin, date) {
  const bad = (s) => {
    const sch = schedule(s, startPoint, endPoint, startMin, date);
    let n = 0, wait = 0;
    for (const x of sch.stops) {
      wait += x.wait;
      const h = D.hoursOn(x.place, date);
      if (h.status === 'closed' || (h.status === 'open' && !D.fitsHours(h, x.start, x.end))) n++;
      if (x.slot && (x.start < x.slot.from || x.start > x.slot.to)) n++;
    }
    // 等開門 1 分鐘約當多開 1 分鐘車：寧可多繞一點，也不要讓旅客在門口乾等
    return n * 1000 + routeCost(s, startPoint, endPoint) + wait;
  };
  let best = seq, bestScore = bad(seq);
  const hasWait = () => schedule(best, startPoint, endPoint, startMin, date).stops.some((x) => x.wait >= 20);
  if (bestScore < 1000 && !hasWait()) return best;
  for (let round = 0; round < 3; round++) {
    let improved = false;
    for (let i = 0; i < best.length; i++) {
      for (let k = i + 1; k < best.length; k++) {
        const cand = best.slice();
        [cand[i], cand[k]] = [cand[k], cand[i]];
        const sc = bad(cand);
        if (sc < bestScore) { best = cand; bestScore = sc; improved = true; }
      }
    }
    if (!improved || (bestScore < 1000 && !hasWait())) break;
  }
  return best;
}

// 時間有剩就由程式把景點停留拉長，不要叫 LLM 算：實測它常不帶 stayMin、同一份草稿連送 4 輪，整趟失敗。
// 每輪每個景點加 10 分（上限 STAY_FILL_MAX），不能超過營業時間，回到終點前留 STAY_FILL_MARGIN 分。
const STAY_FILL_MAX = 120;
const STAY_FILL_MARGIN = 20;
function fillIdleWithStay(seq, req) {
  let out = seq;
  const tooLate = (s) => s.returnMin > req.endMin - STAY_FILL_MARGIN
    || s.stops.some((x) => { const h = D.hoursOn(x.place, req.date); return h.status === 'open' && h.windows.length && !D.fitsHours(h, x.start, x.end); });
  for (let round = 0; round < 12; round++) {
    let grew = false;
    for (let i = 0; i < out.length; i++) {
      if (out[i].kind === 'food' || out[i].stayMin >= STAY_FILL_MAX) continue;
      const cand = out.slice();
      cand[i] = { ...cand[i], stayMin: Math.min(STAY_FILL_MAX, cand[i].stayMin + 10) };
      if (tooLate(schedule(cand, req.startPoint, req.endPoint, req.startMin, req.date))) continue;
      out = cand;
      grew = true;
    }
    if (!grew) break;
  }
  return out;
}

/**
 * 把 LLM 選的站排成一日行程並驗證。
 * @param {object} req   normalizeRequest() 的結果
 * @param {Array}  picks [{ name, stayMin? }]
 * @param {object} opts  { allowed: Set<_key>（工具回傳過的名稱）, forecast }
 */
function buildDraft(req, picks, opts = {}) {
  const allowed = opts.allowed || null;
  const rejected = [];
  const items = [];
  const seen = new Set();
  // 使用者在「希望景點」指定的站：一定要排、不被同處規則擋下，排法有疑慮只提醒（notices），不替使用者決定
  const desiredList = resolveDesired(req);
  // 用名稱互相包含比對：指定「阿鋐炸雞」時，搜尋回的「阿鋐炸雞-正氣店」也算
  const desiredKeys = desiredList.filter((d) => d.name).map((d) => D.normName(d.input)).filter((k) => k.length >= 2);
  const matchesDesired = (key, place) => { const k = String(place._key); return k.includes(key) || key.includes(k); };
  const isDesired = (place) => desiredKeys.some((key) => matchesDesired(key, place));
  const notices = desiredList.filter((d) => !d.name).map((d) => `你想去的「${d.input}」在景點資料裡找不到，這次沒有排進去，可以到行程頁手動加入。`);
  for (const p of (Array.isArray(picks) ? picks : []).slice(0, MAX_DRAFT_STOPS + 4)) {
    const name = String((p && (p.name || p)) || '').trim();
    if (!name) continue;
    const place = D.findPlace(name);
    if (!place) { rejected.push({ name, reason: '本地資料找不到，只能用搜尋工具回傳的名稱' }); continue; }
    if (allowed && !allowed.has(place._key) && !isDesired(place)) { rejected.push({ name, reason: '這個名稱不是搜尋工具回傳的結果，請先用 search_local_poi／search_restaurants 找到它' }); continue; }
    if (D.accessOf(place).avoid) { rejected.push({ name: place.name, reason: '目前暫停開放' }); continue; }
    if (place.kind === 'transit') { rejected.push({ name: place.name, reason: '交通站不是景點' }); continue; }
    if (seen.has(place._key)) continue;
    seen.add(place._key);
    const twin = place.kind === 'food' ? null : items.find((x) => x.place.kind !== 'food' && sameAttraction(x.place, place));
    if (twin && isDesired(place) && isDesired(twin.place)) {
      notices.push(`你指定的「${twin.place.name}」和「${place.name}」在同一區或很靠近，都幫你排了。不需要的話可以刪掉一站。`);
    } else if (twin && isDesired(place)) {
      // 新來的是使用者指定、先排的不是：留使用者要的
      items.splice(items.indexOf(twin), 1);
      rejected.push({ name: twin.place.name, reason: `和旅客指定的「${place.name}」是同一個景點，請拿掉` });
    } else if (twin) {
      rejected.push({ name: place.name, reason: `和「${twin.place.name}」是同一個景點，請只留一個，改挑別的景點` });
      continue;
    }
    const stay = Number(p && p.stayMin);
    items.push({
      place,
      kind: place.kind === 'food' ? 'food' : 'scenic',
      stayMin: Math.min(240, Math.max(20, Number.isFinite(stay) && stay > 0 ? Math.round(stay) : (place.kind === 'food' ? 60 : (place.duration || 60))))
    });
  }
  if (items.length > MAX_DRAFT_STOPS) {
    items.splice(MAX_DRAFT_STOPS).forEach((x) => rejected.push({ name: x.place.name, reason: `最多 ${MAX_DRAFT_STOPS} 站` }));
  }

  const startPoint = req.startPoint;
  const endPoint = req.endPoint;
  const slots = mealSlots(req.startMin, req.endMin);
  const foods = items.filter((x) => x.kind === 'food');
  const meals = [];
  const others = items.filter((x) => x.kind !== 'food');
  // 每一餐配一家「那個時段有營業」的店（營業時間未知的也可以）；都配不到就照送來的順序配
  const servesSlot = (f, slot) => {
    const h = D.hoursOn(f.place, req.date);
    if (h.status !== 'open' || !h.windows.length) return h.status !== 'closed';
    return h.windows.some((w) => Math.min(w.close, slot.to) - Math.max(w.open, slot.from) >= f.stayMin);
  };
  const freeFoods = foods.slice();
  slots.forEach((slot) => {
    // 飲料店、冰店不算這一餐；使用者指定的正餐優先
    const realMeal = (f) => !isSnack(f.place);
    let k = freeFoods.findIndex((f) => realMeal(f) && isDesired(f.place) && servesSlot(f, slot));
    if (k < 0) k = freeFoods.findIndex((f) => realMeal(f) && servesSlot(f, slot));
    if (k < 0) k = freeFoods.findIndex(realMeal);
    if (k >= 0) meals.push({ ...freeFoods.splice(k, 1)[0], slot });
  });
  freeFoods.forEach((f) => others.push(f));   // 多出來的餐廳（咖啡、點心）當一般站排

  let seq = insertMeals(orderByRoute(others, startPoint, endPoint), meals, startPoint, endPoint, req.startMin, req.date);
  seq = fixHoursBySwaps(seq, startPoint, endPoint, req.startMin, req.date);
  seq = fillIdleWithStay(seq, req);
  const sch = schedule(seq, startPoint, endPoint, req.startMin, req.date);

  // ── 驗證：共用 itinerary.js 的硬規則 ──
  const trip = {
    title: req.title, region: req.dest, startDate: req.date, endTime: I.toClock(Math.min(req.endMin, 1439)),
    people: req.people, budgetPerPerson: req.budgetPerPerson, island: null, travelFactor: 1, days: 1,
    stops: sch.stops.map((x, i) => ({
      id: 'p' + (i + 1), day: 1, time: I.toClock(x.start), stayMin: x.stayMin, name: x.place.name,
      lat: x.place.lat, lng: x.place.lng, kind: x.kind, timeLocked: false, keepReason: ''
    }))
  };
  const base = I.validate(trip, opts.forecast || null, null);
  const violations = [];
  const warnings = base.warnings.slice();
  // 降雨：一日遊允許 1 個戶外站（短暫停留、看雨勢再決定），2 個以上才擋
  // 使用者指定的戶外景點不因下雨被擋（提醒就好）；離島室內景點太少，下雨也排不出室內行程，同樣只提醒
  const desiredRain = (v) => { const p = D.findPlace(v.stop); return !!p && isDesired(p); };
  const rain = base.violations.filter((v) => v.code === 'rain' && !desiredRain(v));
  base.violations.forEach((v) => {
    if (v.code === 'rain' && desiredRain(v)) {
      warnings.push({ ...v, code: 'rain_minor' });
      notices.push(`你指定的「${v.stop}」是戶外景點，當天預報可能下雨，建議出發前再確認。`);
    } else if (v.code === 'rain' && req.island) {
      warnings.push({ ...v, code: 'rain_minor' });
      notices.push(`${req.island}當天預報可能下雨，島上室內景點不多，行程仍以戶外為主，出發前請再確認天氣。`);
    } else if (v.code === 'rain' && rain.length <= 1) warnings.push({ ...v, code: 'rain_minor' });
    else violations.push(v);
  });

  rejected.forEach((r) => violations.push({ code: 'unknown_place', stop: r.name, message: `「${r.name}」無法使用：${r.reason}` }));
  if (!items.length) violations.push({ code: 'empty', message: '草稿沒有任何可用的站' });

  // 用餐：時段有涵蓋就要排（餐廳只能來自 search_restaurants）
  slots.forEach((slot) => {
    const m = sch.stops.find((x) => x.slot === slot);
    if (!m) violations.push({ code: 'meal', message: `行程涵蓋${slot.label}時段（${I.toClock(slot.from)}–${I.toClock(slot.to)}），請用 search_restaurants 找一家加進來` });
    else if (m.start < slot.from || m.start > slot.to) {
      violations.push({ code: 'meal_time', stop: m.place.name, message: `${slot.label}「${m.place.name}」排在 ${I.toClock(m.start)}，不在 ${I.toClock(slot.from)}–${I.toClock(slot.to)} 之間（附近景點太多或太少，請調整站數）` });
    }
  });

  // 希望景點：查得到、能去的都要排
  desiredList.forEach((d) => {
    if (!d.name) return;
    const place = D.findPlace(d.name);
    if (!place || place.kind === 'transit' || D.accessOf(place).avoid) {
      notices.push(`你想去的「${d.name}」目前無法安排（交通站或暫停開放）。`);
      return;
    }
    const key = D.normName(d.input);
    if (!items.some((x) => x.place._key === place._key || matchesDesired(key, x.place))) {
      violations.push({ code: 'desired_missing', stop: d.name, message: `旅客指定要去「${d.name}」，一定要排進行程（可以拿掉別站或縮短停留）` });
    }
  });

  // 連續三站以上都在吃喝：不是使用者指定就擋，指定的只提醒
  for (let i = 0; i < sch.stops.length;) {
    let j = i;
    while (j < sch.stops.length && sch.stops[j].kind === 'food') j += 1;
    if (j - i >= 3) {
      const run = sch.stops.slice(i, j);
      const names = run.map((x) => `「${x.place.name}」`).join('');
      if (run.some((x) => isDesired(x.place))) notices.push(`${names}連續 ${run.length} 站都在吃喝，這是照你指定的排法，想分散可以到行程頁調整。`);
      else violations.push({ code: 'food_streak', message: `${names}連續 ${run.length} 站都在吃喝，請拿掉多的餐廳或中間排景點` });
    }
    i = Math.max(j, i + 1);
  }

  // 兩間正餐太近：飲料、冰品不算
  const realMeals = sch.stops.filter((x) => x.kind === 'food' && !isSnack(x.place));
  for (let i = 0; i + 1 < realMeals.length; i++) {
    const a = realMeals[i], b = realMeals[i + 1];
    const gap = b.start - a.start;
    if (isDesired(a.place) || isDesired(b.place)) {
      if (gap <= MEAL_GAP_NOTICE_MIN) notices.push(`「${a.place.name}」和「${b.place.name}」都是正餐，只隔大約 ${gap} 分鐘。已照你指定的保留，覺得太趕可以刪掉一間。`);
    } else if (gap < MEAL_GAP_MIN) {
      violations.push({ code: 'meal_gap', message: `「${a.place.name}」和「${b.place.name}」都是正餐，只隔 ${gap} 分鐘，請拿掉一間` });
    }
  }

  // 回到終點
  if (sch.returnMin > req.endMin + 15) {
    const over = sch.returnMin - req.endMin;
    const fix = over <= 60 ? `請把幾站的 stayMin 合計縮短約 ${over + 10} 分鐘（不要刪站）` : `請刪掉 1 站，或把幾站的 stayMin 合計縮短約 ${over + 10} 分鐘`;
    violations.push({ code: 'return_late', message: `最後一站結束 ${I.toClock(sch.endMin)}，回到「${endPoint.name}」約 ${I.toClock(Math.min(sch.returnMin, 1439))}，超過結束時間 ${I.toClock(req.endMin)} 約 ${over} 分鐘：${fix}` });
  }
  // 站數與空檔
  const range = stopRange(req.durationMin, req.pace);
  if (items.length && items.length < range.min) {
    violations.push({ code: 'too_few', message: `只有 ${items.length} 站，${Math.round(req.durationMin / 60)} 小時的行程建議至少 ${range.min} 站` });
  }
  // 原本 120 分才擋，實測 09–18 的行程 16:00 就回到終點也會通過；前端還會在起點加約 30 分停留，
  // 所以這裡收到 90 分：實際畫面上的空檔約 1 小時內
  const idle = req.endMin - sch.returnMin;
  if (items.length && idle > 60) {
    // 空檔不大就延長停留（加一站常常一下子超時），差很多才加站；目標是回到終點時離結束還有 30–60 分
    const need = idle - 30;
    const fix = idle <= 120
      ? `請把幾站的 stayMin 合計延長約 ${need} 分鐘（不要加站）`
      : `請再加 ${Math.max(1, Math.round(need / 90))} 站（每站約 60–75 分），或延長停留合計約 ${need} 分鐘`;
    violations.push({ code: 'idle', message: `行程 ${I.toClock(sch.returnMin)} 就結束，離 ${I.toClock(req.endMin)} 還空 ${idle} 分鐘：${fix}` });
  }
  else if (items.length && idle > 30) warnings.push({ code: 'idle_minor', message: `離結束時間還有約 ${idle} 分鐘空檔` });
  const longWaits = sch.stops.filter((x) => x.wait >= 45);
  longWaits.forEach((x) => warnings.push({ code: 'wait', stop: x.place.name, message: `「${x.place.name}」要等 ${x.wait} 分鐘才開門` }));

  const stops = sch.stops.map((x, i) => ({
    order: i + 1,
    name: x.place.name,
    kind: x.kind,
    ...(x.slot ? { meal: x.slot.meal } : {}),
    time: I.toClock(x.start),
    stayMin: x.stayMin,
    moveMin: x.moveMin,
    ...(x.wait ? { waitMin: x.wait } : {}),
    indoor: D.indoorOf(x.place),
    hours: D.hoursText(D.hoursOn(x.place, req.date)),
    place: x.place
  }));
  return {
    ok: violations.length === 0,
    violations,
    warnings,
    stops,
    startPoint,
    endPoint,
    endTime: I.toClock(Math.min(sch.endMin, 1439)),
    returnTime: I.toClock(Math.min(sch.returnMin, 1439)),
    costPerPerson: I.costPerPerson(trip),
    range,
    notices: [...new Set(notices)]
  };
}

/** 前端送來的條件一律重新組裝（限長度、限範圍），不信任其他欄位 */
function normalizeRequest(raw) {
  const r = raw || {};
  const dest = normDest(r.dest || r.destination || '台東').slice(0, 20) || '台東';
  const clock = (v, def) => (I.toMin(v) !== null ? I.toMin(v) : def);
  const startMin = Math.min(20 * 60, Math.max(5 * 60, clock(r.startTime, 9 * 60)));
  const durationMin = Math.min(14 * 60, Math.max(120, Math.round(Number(r.durationMin) || 480)));
  const endMin = Math.min(23 * 60 + 30, startMin + durationMin);
  const date = /^\d{4}-\d{2}-\d{2}$/.test(String(r.date || '')) ? r.date : (() => {
    const d = new Date(Date.now() + 8 * 3600e3); d.setUTCDate(d.getUTCDate() + 1); return d.toISOString().slice(0, 10);
  })();
  const interests = (Array.isArray(r.interests) ? r.interests : []).map((x) => String(x).slice(0, 12)).filter(Boolean).slice(0, 8);
  const desired = String(r.desiredSpots || '').slice(0, 200);
  const req = {
    dest,
    island: islandOf(dest),
    date,
    startMin,
    durationMin: endMin - startMin,
    endMin,
    people: Math.min(20, Math.max(1, parseInt(r.people, 10) || 2)),
    pace: String(r.pace || '平衡').slice(0, 10),
    theme: String(r.theme || '').slice(0, 20),
    interests,
    budgetPerPerson: Number(r.budgetPerPerson) > 0 ? Math.min(100000, Math.round(Number(r.budgetPerPerson))) : null,
    desiredSpots: desired,
    transportMode: String(r.transportMode || 'car').slice(0, 12),
    title: String(r.title || `${dest}一日遊`).slice(0, 40),
    // 分流：最近排過的站（正規化後的名稱），搜尋時稍微降低優先順序
    avoidKeys: new Set((Array.isArray(r.avoidNames) ? r.avoidNames : []).slice(0, 20)
      .map((x) => String(x).replace(/臺/g, '台').replace(/\s+/g, '').toLowerCase()).filter(Boolean))
  };
  req.startPoint = endpointOf(r.startLocation, dest, r.startCoord);
  req.endPoint = String(r.endLocation || '').trim() ? endpointOf(r.endLocation, dest, r.endCoord || r.startCoord) : req.startPoint;
  req.center = req.island ? centerOf(dest) : (req.startPoint.source !== 'center' && req.startPoint.source !== 'harbor' ? req.startPoint : centerOf(dest));
  return req;
}

/** 想去的景點（使用者自填）對回本地資料：只收唯一對得上的 */
function resolveDesired(req) {
  return String(req.desiredSpots || '').split(/[、,，;；\n/]+/).map((s) => s.trim()).filter((s) => s.length >= 2).slice(0, 6)
    .map((s) => ({ input: s, place: D.findPlace(s) }))
    .map((x) => ({ input: x.input, name: x.place ? x.place.name : null, kind: x.place ? x.place.kind : null }));
}

/** 本地景點／餐廳的搜尋池（離島只看該島；本島排除兩個離島） */
function poolOf(req, kind) {
  const all = kind === 'food' ? D.data().foods : D.data().pois.filter((p) => p.kind !== 'transit');
  return all.filter((p) => {
    if (D.accessOf(p).avoid) return false;
    if (req.island) return p.region === req.island || F.sideOf(p, req.island) === 'island';
    return !ISLANDS.has(p.region);
  });
}

function interestScore(place, interests) {
  const text = `${place.name}${place.desc || ''}`;
  let n = 0;
  for (const it of interests) {
    const key = Object.keys(INTEREST_RE).find((k) => String(it).includes(k));
    if (key && INTEREST_RE[key].test(text)) n++;
  }
  return n;
}

module.exports = {
  normalizeRequest, buildDraft, resolveDesired, poolOf, interestScore, centerOf, islandOf, endpointOf,
  isSnack, mealSlots, stopRange, schedule, orderByRoute, INTEREST_RE, MAX_DRAFT_STOPS, landmarkTokens, sameAttraction
};
