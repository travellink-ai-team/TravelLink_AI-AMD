'use strict';
/* ══════════════════════════════════════════════════
   行程生成 Agent（方案 C，gpt-oss-120b on AMD，一日遊）
   ──────────────────────────────────────────────────
   流程：
     get_trip_request 讀條件 → get_weather_forecast 決定室內外比例
     → search_local_poi／search_restaurants 篩候選（離島另查 get_ferry_status）
     → draft_itinerary 送「選了哪些站」→ 程式（planner.js）排序、算車程、
       排時間、驗證，回 violations → 模型修改（最多 4 輪）
     → present_itinerary 結束
   原則：AI 負責「選」，程式負責「算」；站名只能用工具回傳過的名稱。
   防護比照 loop.js：工具白名單、步數上限、總時限、5xx 重試。
   失敗不在後端降級——回 error，由前端退回原本的一次生成流程。
   ══════════════════════════════════════════════════ */
const amd = require('../amd-llm');
const D = require('./data');
const I = require('./itinerary');
const SEA = require('./sea');
const P = require('./planner');

const MAX_TOOL_CALLS = 20;
const MAX_LLM_CALLS = 18;
// 3 輪時，短行程偶爾在「太短 → 加站變太長 → 刪站」之間來回用完次數；多給一輪，每輪約 0.5 秒
const MAX_FAILED_ROUNDS = 4;
const SEARCH_LIMIT = 8;
const timeoutMs = () => Number(process.env.AGENT_PLAN_TIMEOUT_MS) || 90 * 1000;

const SYSTEM_PROMPT = [
  '你是 TravelLink AI 的「行程規劃代理人」，用工具一步一步為旅客組出台東地區的一日遊。',
  '分工：你負責「選」哪些景點與餐廳；排順序、算車程、排時間、檢查營業時間都由程式做，你不用自己算時間。',
  '工作方式：',
  '1. 先呼叫 get_trip_request 讀旅客條件（日期、時段、人數、興趣、節奏、預算、想去的景點、建議站數）。',
  '2. 呼叫 get_weather_forecast。降雨機率 ≥ 50% 的時段要以室內景點（indoor=indoor）為主，戶外景點最多 1 個；天氣好就以戶外與興趣相符的景點為主。',
  '3. 用 search_local_poi 找候選景點（可依興趣帶 interest、下雨帶 indoor=true、或用 nearName 以某個景點為中心再找）。旅客指定想去的景點要優先放進去。',
  '4. 離島（綠島／蘭嶼）行程要呼叫 get_ferry_status 確認船班與停航公告。',
  '5. 時段涵蓋午餐（或晚餐）時，用 search_restaurants 找餐廳，每一餐選一家（nearName 帶附近的景點，比較順路）。',
  '6. 呼叫 draft_itinerary，stops 只放「名稱」與可選的 stayMin，順序不重要，程式會排。站名只能用搜尋工具回傳的名稱，不可自己編。',
  '7. draft_itinerary 回傳 violations 時，針對問題增刪或替換站點後重送（stops 每次都要完整重給）；最多改 3 次。',
  '8. ok=true 之後呼叫 present_itinerary：title 是行程標題（15 字內），summary 一句話介紹這趟行程，reasons 2–4 點說明安排理由（引用降雨機率、營業時間、距離等工具給的數字，不可宣稱沒做到的事）。',
  '9. 每一輪都要呼叫工具，不要只回文字。全程使用繁體中文。'
].join('\n');

const fn = (name, description, properties, required) => ({
  type: 'function',
  function: { name, description, parameters: { type: 'object', properties, required: required || [] } }
});

const INTEREST_ENUM = Object.keys(P.INTEREST_RE);

const DEFINITIONS = [
  fn('get_trip_request', '讀取旅客的條件：目的地、日期與星期、開始與結束時間、人數、興趣、節奏、預算、想去的景點、起訖點、建議站數與需要安排的餐。第一步一定先呼叫。', {}),
  fn('get_weather_forecast', '取得旅遊日的降雨機率與天氣描述（中央氣象署一週預報，12 小時一段）。', {}),
  fn('get_ferry_status', '離島（綠島／蘭嶼）行程用：查官方停航公告與海況，提醒搭船注意事項。', {}),
  fn('search_local_poi', '在本地景點資料中找候選景點，附室內外、當天營業時間、門票、建議停留分鐘。依興趣相符度、評分、距離排序。', {
    interest: { type: 'string', enum: INTEREST_ENUM, description: '依興趣加權' },
    keyword: { type: 'string', description: '名稱或簡介要包含的字，例如「溫泉」「博物館」' },
    indoor: { type: 'boolean', description: 'true 只回室內景點（下雨時用）' },
    nearName: { type: 'string', description: '以這個地點為中心搜尋（必須是之前工具回傳過的名稱）；不給就以目的地／起點為中心' },
    radiusKm: { type: 'number', description: '搜尋半徑，預設 20（離島整座島）' }
  }),
  fn('search_restaurants', '在本地餐廳資料中找餐廳，附當天營業時間、每人花費、評分，依距離排序。', {
    nearName: { type: 'string', description: '以這個景點為中心（建議帶午餐前後會去的景點）' },
    meal: { type: 'string', enum: ['lunch', 'dinner'], description: '只找這一餐有營業的店；不給就找任一餐' },
    radiusKm: { type: 'number', description: '預設 10' }
  }),
  fn('check_business_hours', '查某個地點在旅遊日的營業狀態。', {
    name: { type: 'string' }
  }, ['name']),
  fn('draft_itinerary', '送出這趟要去的站（景點與餐廳），程式會排順序、算車程、排時間並驗證，回傳排好的行程與 violations。重送時 stops 要完整重給。', {
    stops: {
      type: 'array',
      description: '要去的站，順序不重要',
      items: {
        type: 'object',
        properties: {
          name: { type: 'string', description: '搜尋工具回傳的名稱' },
          stayMin: { type: 'integer', description: '停留分鐘（可不填，用資料的建議值）' }
        },
        required: ['name']
      }
    }
  }, ['stops']),
  fn('present_itinerary', '提出最終行程。必須先有一份 ok=true 的 draft_itinerary。呼叫後流程結束。', {
    title: { type: 'string', description: '行程標題，15 字內' },
    summary: { type: 'string', description: '一句話介紹這趟行程' },
    reasons: { type: 'array', items: { type: 'string' }, description: '2–4 點安排理由' }
  }, ['title', 'summary', 'reasons'])
];

const TERMINAL = new Set(['present_itinerary']);

const LABELS = {
  get_trip_request: () => '讀取你的旅遊條件',
  get_weather_forecast: () => '查詢旅遊日天氣',
  get_ferry_status: (_a, ctx) => `正在查${(ctx && ctx.req.island) || '離島'}船班與停航公告`,
  search_local_poi: (a) => `搜尋${a.indoor ? '室內' : ''}${a.interest ? a.interest + '類' : ''}景點${a.keyword ? `「${a.keyword}」` : ''}${a.nearName ? `（${a.nearName}附近）` : ''}`,
  search_restaurants: (a) => `搜尋${a.nearName ? `「${a.nearName}」附近` : ''}餐廳`,
  check_business_hours: (a) => `確認「${a.name}」營業時間`,
  draft_itinerary: (a) => `排順序、算車程並檢查（${Array.isArray(a.stops) ? a.stops.length : 0} 站）`,
  present_itinerary: () => '整理最終行程'
};

function labelOf(name, args, ctx) {
  try { return (LABELS[name] || (() => name))(args || {}, ctx); } catch (_e) { return name; }
}

function definitions(req) {
  return req.island ? DEFINITIONS : DEFINITIONS.filter((d) => d.function.name !== 'get_ferry_status');
}

const WEEKDAY = ['日', '一', '二', '三', '四', '五', '六'];

function candidateView(place, origin, date) {
  const h = D.hoursOn(place, date);
  return {
    name: place.name,
    distanceKm: Math.round(D.haversineKm(origin, place) * 10) / 10,
    indoor: D.indoorOf(place),
    hours: D.hoursText(h),
    ...(place.kind === 'food'
      ? { costPerPerson: Number(place.costPerPerson) || null }
      : { fee: Number.isFinite(Number(place.fee)) ? Number(place.fee) : null, stayMin: place.duration || 60 }),
    ...(place.rating ? { rating: place.rating } : {}),
    ...(place.kind !== 'food' && place.desc ? { desc: String(place.desc).slice(0, 40) } : {})
  };
}

// 旅遊日有營業、而且營業時段與行程時段重疊至少 45 分鐘（例如 17:00 才開的店不給 09–17 的行程）。
// 營業時間未知的照樣列出，由 draft 驗證時標 warning。
function openDuringTrip(place, r) {
  const h = D.hoursOn(place, r.date);
  if (h.status === 'closed') return false;
  if (h.status !== 'open' || !h.windows.length) return true;
  return h.windows.some((w) => Math.min(w.close, r.endMin) - Math.max(w.open, r.startMin) >= 45);
}

function originOf(ctx, nearName) {
  if (nearName) {
    const p = D.findPlace(nearName);
    if (p && ctx.allowed.has(p._key)) return p;
  }
  return ctx.req.center;
}

const IMPL = {
  get_trip_request(ctx) {
    const r = ctx.req;
    const day = new Date(r.date + 'T00:00:00Z').getUTCDay();
    const desired = P.resolveDesired(r);
    desired.forEach((d) => { if (d.name) ctx.allowed.add(D.normName(d.name)); });
    return {
      dest: r.dest,
      ...(r.island ? { island: r.island, note: '離島一日遊：起訖點是島內港，只排島上的站' } : {}),
      date: r.date, weekday: '星期' + WEEKDAY[day],
      startTime: I.toClock(r.startMin), endTime: I.toClock(r.endMin), durationHours: Math.round(r.durationMin / 6) / 10,
      start: r.startPoint.name, end: r.endPoint.name,
      people: r.people, pace: r.pace, interests: r.interests, ...(r.theme ? { theme: r.theme } : {}),
      budgetPerPerson: r.budgetPerPerson,
      transport: r.transportMode,
      desiredSpots: desired.length ? desired.map((d) => (d.name ? d.name : `${d.input}（本地資料找不到，不能排）`)) : [],
      suggestedStops: `${P.stopRange(r.durationMin, r.pace).min}–${P.stopRange(r.durationMin, r.pace).max} 站（含餐廳）`,
      meals: P.mealSlots(r.startMin, r.endMin).map((m) => `${m.label}（${I.toClock(m.from)}–${I.toClock(m.to)}）`)
    };
  },

  async get_weather_forecast(ctx) {
    const f = await ctx.getForecast();
    if (!f || !f.periods.length) return { source: f ? f.source : 'unavailable', note: '拿不到天氣預報，請以戶外與室內混搭安排' };
    const r = ctx.req;
    const periods = f.periods.filter((p) => p.start.slice(0, 10) === r.date || p.end.slice(0, 10) === r.date);
    const inWindow = [];
    for (let m = r.startMin; m < r.endMin; m += 60) {
      const w = D.popAt(f, r.date, m);
      if (w) inWindow.push(w.pop);
    }
    const maxPop = inWindow.length ? Math.max(...inWindow) : null;
    return {
      source: f.source,
      periods: periods.map((p) => ({ from: p.start.replace('T', ' '), to: p.end.replace('T', ' '), wx: p.wx, pop: p.pop, ...(p.simulated ? { simulated: true } : {}) })),
      maxPopInTrip: maxPop,
      advice: maxPop === null ? '旅遊日超出一週預報範圍，室內外混搭' : (maxPop >= 50 ? `行程時段降雨機率最高 ${maxPop}%，以室內景點為主，戶外最多 1 個` : `降雨機率最高 ${maxPop}%，可以安排戶外景點`)
    };
  },

  async get_ferry_status(ctx) {
    const r = ctx.req;
    if (!r.island) return { note: '這不是離島行程，不需要查船班' };
    const alerts = await SEA.fetchTdxAlerts();
    const related = (alerts || []).filter((a) => a.title.includes(r.island) || a.description.includes(r.island));
    const out = {
      island: r.island,
      route: `富岡漁港 ⇄ ${r.island === '綠島' ? '南寮漁港' : '開元漁港'}，航程約 ${r.island === '綠島' ? 50 : 120} 分鐘`,
      officialAlerts: alerts === null ? '查不到（交通部航運資料暫時無法取得）' : (related.length ? related.slice(0, 3) : '目前沒有停航公告'),
      note: '一日遊行程只排島上的站；船班時刻與是否停航以船公司公告為準，出發前請再確認'
    };
    if (SEA.enabled()) {
      const sea = await SEA.seaOn(r.island, r.date, ctx.scenario);
      out.sea = { risk: sea.risk, reasons: sea.reasons, ...(sea.wave ? { wave: sea.wave, wind: sea.wind } : {}) };
    }
    return out;
  },

  search_local_poi(ctx, args) {
    const r = ctx.req;
    const origin = originOf(ctx, args.nearName);
    const radius = Math.min(60, Math.max(1, Number(args.radiusKm) || (r.island ? 20 : 20)));
    const interests = args.interest ? [String(args.interest)] : r.interests;
    const kw = String(args.keyword || '').trim().slice(0, 12);
    const seenBase = [];
    const kept = [];
    const results = P.poolOf(r, 'scenic')
      .filter((p) => !args.indoor || D.indoorOf(p) === 'indoor')
      .filter((p) => !kw || `${p.name}${p.desc || ''}`.includes(kw))
      .filter((p) => openDuringTrip(p, r))
      .map((p) => ({ p, km: D.haversineKm(origin, p) }))
      .filter((x) => x.km <= radius)
      .map((x) => ({ ...x, score: P.interestScore(x.p, interests) * 2 + (Number(x.p.rating) || 4) - x.km / 8 }))
      .sort((a, b) => b.score - a.score)
      .filter((x) => {
        // 同一地點常有多筆：「中文名 + 英文名」、「臺東鐵道藝術村 (鐵花新聚落)」與「鐵花新聚落」。
        // 拿掉英文、拆出括號內外的名稱，任一段互相包含就算同一個，只留分數高的那筆
        const parts = x.p._key.replace(/[a-z0-9&'’.,-]+/g, '').split(/[()（）]/).map((s) => s.trim()).filter((s) => s.length >= 3);
        if (parts.some((a) => seenBase.some((b) => a.includes(b) || b.includes(a)))) return false;
        // 名稱不互相包含的別名（「哈巴狗與睡美人岩」vs「小長城步道 & 睡美人與哈巴狗」）交給地標詞＋距離判斷
        if (kept.some((k) => P.sameAttraction(k, x.p))) return false;
        seenBase.push(...parts);
        kept.push(x.p);
        return true;
      })
      .slice(0, SEARCH_LIMIT);
    results.forEach((x) => ctx.allowed.add(x.p._key));
    return {
      date: r.date,
      center: args.nearName && origin !== r.center ? origin.name : (r.startPoint.source === 'local' ? r.startPoint.name : r.dest),
      results: results.map((x) => candidateView(x.p, origin, r.date)),
      note: results.length ? undefined : '範圍內沒有符合的景點，可拿掉 keyword／interest 或加大 radiusKm'
    };
  },

  search_restaurants(ctx, args) {
    const r = ctx.req;
    const origin = originOf(ctx, args.nearName);
    const radius = Math.min(40, Math.max(1, Number(args.radiusKm) || 10));
    // 只列「這趟行程的用餐時段」有營業的店（早餐店、傍晚才開的鹹酥雞不給午餐用）
    const slots = P.mealSlots(r.startMin, r.endMin).filter((s) => !args.meal || s.meal === args.meal);
    const mealsOf = (p) => {
      const h = D.hoursOn(p, r.date);
      if (h.status !== 'open' || !h.windows.length) return h.status === 'closed' ? [] : slots;   // 未知：不排除
      return slots.filter((s) => h.windows.some((w) => Math.min(w.close, s.to) - Math.max(w.open, s.from) >= 60));
    };
    const results = P.poolOf(r, 'food')
      .filter((p) => openDuringTrip(p, r))
      .map((p) => ({ p, km: D.haversineKm(origin, p), meals: mealsOf(p) }))
      .filter((x) => x.km <= radius && (!slots.length || x.meals.length))
      .sort((a, b) => a.km - b.km)
      .slice(0, SEARCH_LIMIT);
    results.forEach((x) => ctx.allowed.add(x.p._key));
    return {
      date: r.date,
      results: results.map((x) => ({ ...candidateView(x.p, origin, r.date), ...(x.meals.length ? { goodFor: x.meals.map((m) => m.label) } : {}) })),
      note: results.length ? undefined : '範圍內沒有餐廳，可加大 radiusKm 或換 nearName'
    };
  },

  check_business_hours(ctx, args) {
    const place = D.findPlace(args.name);
    if (!place) return { error: '本地資料找不到這個地點' };
    const h = D.hoursOn(place, ctx.req.date);
    return { name: place.name, date: ctx.req.date, status: h.status, hours: h.status === 'open' ? D.hoursText(h) : h.label || '', access: D.accessOf(place).label || 'open' };
  },

  async draft_itinerary(ctx, args) {
    const draft = P.buildDraft(ctx.req, args.stops, { allowed: ctx.allowed, forecast: await ctx.getForecast() });
    ctx.draft = draft;
    if (!draft.ok) ctx.failedRounds += 1;
    return {
      ok: draft.ok,
      violations: draft.violations.map((v) => v.message),
      warnings: draft.warnings.slice(0, 4).map((w) => w.message),
      itinerary: draft.stops.map((s) => ({
        time: s.time, name: s.name, stayMin: s.stayMin, type: s.kind === 'food' ? (s.meal === 'dinner' ? '晚餐' : s.meal === 'lunch' ? '午餐' : '餐廳') : '景點',
        indoor: s.indoor, hours: s.hours, driveMin: s.moveMin, ...(s.waitMin ? { waitMin: s.waitMin } : {})
      })),
      endAt: draft.endTime,
      backTo: `${draft.endPoint.name} ${draft.returnTime}`,
      costPerPerson: draft.costPerPerson,
      next: draft.ok ? '驗證通過，可以呼叫 present_itinerary' : '請針對 violations 增刪或替換站點後再呼叫 draft_itinerary（stops 要完整重給）'
    };
  },

  present_itinerary(ctx, args) {
    if (!ctx.draft) return { error: '還沒有草稿，請先呼叫 draft_itinerary' };
    if (!ctx.draft.ok) return { error: '草稿還有違規，不能提出：' + ctx.draft.violations.map((v) => v.message).join('；') };
    return {
      title: String(args.title || ctx.req.title).slice(0, 30),
      summary: String(args.summary || '').slice(0, 160),
      reasons: (Array.isArray(args.reasons) ? args.reasons : []).slice(0, 4).map((x) => String(x).slice(0, 120))
    };
  }
};

async function runTool(ctx, name, rawArgs) {
  if (!Object.prototype.hasOwnProperty.call(IMPL, name)) return { error: `沒有這個工具：${name}` };
  if (name === 'get_ferry_status' && !ctx.req.island) return { error: '這不是離島行程，沒有 get_ferry_status' };
  let args = rawArgs;
  if (typeof args === 'string') {
    try { args = args.trim() ? JSON.parse(args) : {}; } catch (_e) { return { error: '參數不是有效的 JSON' }; }
  }
  if (!args || typeof args !== 'object' || Array.isArray(args)) args = {};
  return IMPL[name](ctx, args);
}

function summarizeResult(name, r) {
  if (!r || r.error) return r && r.error ? `⚠️ ${r.error}` : '';
  switch (name) {
    case 'get_trip_request': return `${r.date}（${r.weekday}）${r.startTime}–${r.endTime}，${r.people} 人`;
    case 'get_weather_forecast': return r.advice || r.note || '';
    case 'get_ferry_status': return typeof r.officialAlerts === 'string' ? r.officialAlerts : `有 ${r.officialAlerts.length} 則停航相關公告`;
    case 'search_local_poi':
    case 'search_restaurants':
      return r.results.length ? `找到 ${r.results.length} 個：${r.results.slice(0, 3).map((x) => x.name).join('、')}` : '範圍內沒有符合的地點';
    case 'check_business_hours': return `${r.name}：${r.status === 'open' ? r.hours : r.status}`;
    case 'draft_itinerary': return r.ok ? `✅ ${r.itinerary.length} 站排好了，${r.endAt} 結束` : `❌ ${r.violations.length} 個問題：${r.violations.slice(0, 2).join('；')}`;
    default: return '';
  }
}

/** 給前端的最終行程：欄位對齊前端 AI JSON（name/time/duration/desc/lat/lng/businessHours/emoji），再加 tag/費用 */
function itineraryView(draft) {
  return draft.stops.map((s) => {
    const p = s.place;
    const food = s.kind === 'food';
    return {
      order: s.order,
      name: p.name,
      time: s.time,
      duration: s.stayMin,
      stayMin: s.stayMin,
      dayIndex: 1,
      desc: String(p.desc || (food ? (p.costNote ? `人均 ${p.costNote}` : '在地餐廳') : '')).slice(0, 120),
      lat: p.lat,
      lng: p.lng,
      ...(p.businessHours ? { businessHours: p.businessHours } : {}),
      ...(p.address ? { address: p.address } : {}),
      emoji: food ? '🍽️' : (p.emoji || '📍'),
      ...(food ? { tag: 'food' } : {}),
      ...(s.meal ? { meal: s.meal } : {}),
      ...(food && Number.isFinite(Number(p.costPerPerson)) ? { costPerPerson: Number(p.costPerPerson) } : {}),
      ...(food && p.costNote ? { costNote: p.costNote } : {}),
      ...(!food && Number.isFinite(Number(p.fee)) ? { fee: Number(p.fee) } : {}),
      ...(!food && p.feeNote ? { feeNote: p.feeNote } : {}),
      ...(p.placeVerified ? { placeVerified: true } : {}),
      indoor: s.indoor,
      agentPlanned: true
    };
  });
}

/**
 * @param {object} opts
 * @param {object} opts.request   前端送來的旅遊條件（會重新正規化）
 * @param {object} [opts.scenario] demo 情境注入（{ rain: { date, from, to, pop } }）
 * @param {function} opts.onEvent 每一步呼叫一次，參數是可直接送給前端的事件物件
 * @param {function} [opts.onUsage] 每次 LLM 呼叫後回報 OpenAI usage（記帳用）
 * @param {function} [opts.chat]  測試用：取代 amd.chat
 * @returns {Promise<object>} 最後一個事件（itinerary / error）
 */
async function runPlanAgent({ request, scenario, onEvent, onUsage, chat }) {
  const t0 = Date.now();
  const TIMEOUT_MS = timeoutMs();
  const emit = (e) => { try { onEvent && onEvent({ ...e, ms: Date.now() - t0 }); } catch (_e) {} };
  const req = P.normalizeRequest(request);
  let forecastPromise = null;
  const ctx = {
    req,
    scenario: scenario && scenario.rain ? scenario : null,
    allowed: new Set(),
    draft: null,
    failedRounds: 0,
    getForecast: () => (forecastPromise || (forecastPromise = D.getForecast(req.island || req.dest, ctx.scenario).then((f) => (f.periods.length ? f : null)).catch(() => null)))
  };
  const usage = { promptTokens: 0, completionTokens: 0, llmCalls: 0 };
  const upstreamModels = new Set();
  let toolCalls = 0;
  const finish = (e) => {
    const out = { ...e, usage, steps: toolCalls, model: amd.USAGE_MODEL_ID, upstreamModels: [...upstreamModels], ms: Date.now() - t0 };
    emit(out);
    return out;
  };

  emit({ type: 'start', mode: 'plan', dest: req.dest, date: req.date, island: req.island || null });
  const callLlm = chat || amd.chat;
  if (!chat && !amd.isEnabled()) return finish({ type: 'error', code: 'disabled', message: 'AI 規劃代理人未啟用（AI_PROVIDER 不是 amd）' });

  const messages = [
    { role: 'system', content: SYSTEM_PROMPT },
    { role: 'user', content: `請幫我規劃 ${req.dest} 的一日遊（${req.date}，${I.toClock(req.startMin)}–${I.toClock(req.endMin)}）。今天是 ${I.todayIso()}。` }
  ];
  const tools = definitions(req);
  let nudged = false;

  async function chatWithRetry() {
    let lastErr;
    for (let attempt = 0; attempt < 3; attempt++) {
      const budget = TIMEOUT_MS - (Date.now() - t0);
      if (budget <= 0) break;
      try {
        return await callLlm({ messages, tools, temperature: 0.3, maxTokens: 2048, signal: AbortSignal.timeout(budget) });
      } catch (err) {
        lastErr = err;
        if (!(err.status >= 500)) throw err;
        console.warn(`[plan-agent] LLM ${err.status}，重試第 ${attempt + 1} 次`);
      }
    }
    throw lastErr || Object.assign(new Error('timeout'), { code: 'timeout' });
  }

  try {
    for (let call = 0; call < MAX_LLM_CALLS; call++) {
      if (TIMEOUT_MS - (Date.now() - t0) <= 0) throw Object.assign(new Error('timeout'), { code: 'timeout' });
      const res = await chatWithRetry();
      usage.llmCalls += 1;
      if (res.model) upstreamModels.add(res.model);
      if (res.usage) {
        usage.promptTokens += Number(res.usage.prompt_tokens) || 0;
        usage.completionTokens += Number(res.usage.completion_tokens) || 0;
        if (onUsage) { try { onUsage(res.usage); } catch (_e) {} }
      }
      messages.push(res.message);

      const calls = res.message.tool_calls || [];
      if (!calls.length) {
        if (nudged) throw Object.assign(new Error('model stopped calling tools'), { code: 'no_tools' });
        nudged = true;
        messages.push({ role: 'user', content: '請用工具繼續，最後一定要呼叫 present_itinerary。' });
        continue;
      }

      for (const c of calls) {
        const name = c.function && c.function.name;
        let args = {};
        try { args = JSON.parse((c.function && c.function.arguments) || '{}'); } catch (_e) {}
        toolCalls += 1;
        if (toolCalls > MAX_TOOL_CALLS) throw Object.assign(new Error('too many tool calls'), { code: 'max_steps' });
        emit({ type: 'tool_call', tool: name, label: labelOf(name, args, ctx) });
        const result = await runTool(ctx, name, args);
        emit({ type: 'tool_result', tool: name, ok: !result.error && result.ok !== false, detail: summarizeResult(name, result) });
        messages.push({ role: 'tool', tool_call_id: c.id, content: JSON.stringify(result) });

        if (TERMINAL.has(name) && !result.error) {
          return finish({
            type: 'itinerary',
            title: result.title,
            summary: result.summary,
            reasons: result.reasons,
            dest: req.dest,
            date: req.date,
            startTime: I.toClock(req.startMin),
            endTime: ctx.draft.endTime,
            start: ctx.draft.startPoint.name,
            end: ctx.draft.endPoint.name,
            costPerPerson: ctx.draft.costPerPerson,
            warnings: ctx.draft.warnings.slice(0, 5).map((w) => w.message),
            stops: itineraryView(ctx.draft),
            draftRounds: ctx.failedRounds + 1
          });
        }
      }
      if (ctx.failedRounds >= MAX_FAILED_ROUNDS) throw Object.assign(new Error(`validation failed ${MAX_FAILED_ROUNDS} times`), { code: 'validation' });
    }
    throw Object.assign(new Error('too many llm calls'), { code: 'max_steps' });
  } catch (err) {
    const code = err.code || (err.name === 'TimeoutError' ? 'timeout' : 'upstream');
    const why = {
      timeout: 'AI 規劃代理人逾時', max_steps: 'AI 規劃代理人步數超過上限', validation: `AI 草稿連續 ${MAX_FAILED_ROUNDS} 輪驗證失敗`, no_tools: 'AI 規劃代理人沒有完成流程'
    }[code] || 'AI 服務暫時無法回應';
    console.warn('[plan-agent] 失敗：' + why + (err.code ? '' : '｜' + String(err.message || err).slice(0, 300)));
    return finish({ type: 'error', code, message: why + '，改用一般生成。' });
  }
}

module.exports = { runPlanAgent, DEFINITIONS, definitions, runTool, labelOf, itineraryView, SYSTEM_PROMPT };
