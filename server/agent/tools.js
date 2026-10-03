'use strict';
/* ══════════════════════════════════════════════════
   Agent 工具（OpenAI tool calling 格式）
   ──────────────────────────────────────────────────
   白名單就是這個檔案：LLM 呼叫清單外的名稱一律回錯誤，不執行。
   參數全部在這裡重新驗證，LLM 給的地名必須存在於本地資料，座標由程式填。
   工具結果刻意精簡——每一輪都會送回 LLM，欄位越多 prompt 越肥、越慢。
   ══════════════════════════════════════════════════ */
const D = require('./data');
const I = require('./itinerary');
const F = require('./ferry');
const SEA = require('./sea');

const SEARCH_LIMIT = 6;

const fn = (name, description, properties, required) => ({
  type: 'function',
  function: { name, description, parameters: { type: 'object', properties, required: required || [] } }
});

const SEA_TOOLS = new Set(['get_sea_conditions', 'get_ferry_status']);

const DEFINITIONS = [
  fn('get_trip_state', '取得目前行程：每天的站點（stopId、時間、停留分鐘、室內/戶外）、日期、結束時間、人數、預算。第一步一定先呼叫。', {}),
  fn('get_weather_forecast', '取得行程期間每個時段的降雨機率與天氣描述，並標出哪些站落在降雨機率 ≥ 50% 的時段。', {}),
  fn('get_sea_conditions', '離島（綠島／蘭嶼）行程用：查每一天的浪高、風力與停航風險（依中央氣象署海面預報推估，高＝很可能停航）。', {}),
  fn('get_ferry_status', '離島行程用：列出行程中的每一段船班（去程／回程、日期、開船時間），以及官方停航公告與推估的停航風險。', {}),
  fn('check_business_hours', '查某個地點某一天的營業狀態。', {
    name: { type: 'string', description: '地點名稱' },
    day: { type: 'integer', description: '行程第幾天（1 起算）' }
  }, ['name', 'day']),
  fn('search_local_poi', '在本地景點資料中找替代景點，依距離排序，會附上當天營業狀態。下雨時請設 indoor=true。', {
    nearStopId: { type: 'string', description: '以這個站的位置為中心搜尋' },
    day: { type: 'integer', description: '要排在第幾天（用來判斷營業）' },
    radiusKm: { type: 'number', description: '搜尋半徑，預設 15' },
    indoor: { type: 'boolean', description: 'true 只回室內景點' }
  }, ['nearStopId', 'day']),
  fn('search_restaurants', '在本地餐廳資料中找附近餐廳，依距離排序，附營業狀態與每人花費。', {
    nearStopId: { type: 'string' },
    day: { type: 'integer' },
    radiusKm: { type: 'number', description: '預設 10' }
  }, ['nearStopId', 'day']),
  fn('estimate_travel', '估算兩站之間的車程分鐘數。', {
    fromStopId: { type: 'string' },
    toName: { type: 'string', description: '目的地名稱（本地資料中的地點）' }
  }, ['fromStopId', 'toName']),
  fn('propose_patch', '對行程提出修改草稿（不會直接寫入）。程式會自動依車程調整時間並驗證，回傳違規項目；有違規就要再修。多次呼叫時以原始行程為基準重算，所以每次都要帶完整的 ops。', {
    ops: {
      type: 'array',
      description: '修改清單',
      items: {
        type: 'object',
        properties: {
          type: { type: 'string', enum: ['replace', 'insert', 'remove', 'retime', 'move', 'move_ferry'], description: 'move_ferry：把整段船班改到別天或別的時間（帶 direction、day、time＝開船時間），港口站、之後的本島站與來不及去的島上站由程式自動處理' },
          direction: { type: 'string', enum: ['return', 'outbound'], description: 'move_ferry 用' },
          stopId: { type: 'string', description: 'replace/remove/retime/move 的目標站' },
          name: { type: 'string', description: 'replace/insert 的新地點，必須是搜尋工具回傳的名稱' },
          day: { type: 'integer', description: 'insert/move 用；move 到「天數 + 1」代表多住一晚' },
          afterStopId: { type: 'string', description: 'insert：插在哪一站之後' },
          time: { type: 'string', description: 'HH:MM，retime/insert 用' },
          stayMin: { type: 'integer', description: '停留分鐘；retime 只帶 stayMin 就是縮短或延長停留' }
        },
        required: ['type']
      }
    }
  }, ['ops']),
  fn('ask_user', '有明顯取捨需要使用者決定時才用（例如多花錢 vs 少一個景點）。呼叫後流程結束，等使用者回覆。', {
    question: { type: 'string' },
    options: { type: 'array', items: { type: 'string' }, description: '2–3 個選項' }
  }, ['question', 'options']),
  fn('present_proposal', '提出最終方案給使用者確認。必須先有一份通過驗證的 propose_patch 草稿。呼叫後流程結束。', {
    summary: { type: 'string', description: '一句話說明改了什麼' },
    reasons: { type: 'array', items: { type: 'string' }, description: '2–4 點原因' }
  }, ['summary', 'reasons']),
  fn('no_change_needed', '檢查後確認行程不需要修改時呼叫，流程結束。', {
    reason: { type: 'string' }
  }, ['reason'])
];

const TERMINAL = new Set(['ask_user', 'present_proposal', 'no_change_needed']);

// 給 UI 動態面板顯示的一句話
const LABELS = {
  get_trip_state: () => '讀取目前行程',
  get_weather_forecast: () => '查詢天氣預報',
  get_sea_conditions: () => '查詢綠島蘭嶼海面浪況',
  get_ferry_status: () => '確認船班與停航公告',
  check_business_hours: (a) => `確認「${a.name}」營業時間`,
  search_local_poi: (a) => `搜尋附近${a.indoor ? '室內' : ''}景點`,
  search_restaurants: () => '搜尋附近餐廳',
  estimate_travel: (a) => `估算到「${a.toName}」的車程`,
  propose_patch: () => '組合修改草稿並驗證',
  ask_user: () => '需要你做決定',
  present_proposal: () => '提出方案',
  no_change_needed: () => '行程不需要修改'
};

function stopView(trip, s) {
  const place = D.findPlace(s.name);
  return {
    stopId: s.id, day: s.day, time: s.time, stayMin: s.stayMin, name: s.name,
    type: s.kind === 'food' ? '餐廳' : '景點',
    indoor: place ? D.indoorOf(place) : (s.kind === 'food' ? 'indoor' : 'unknown'),
    ...(trip.island ? { side: F.sideOf(s, trip.island) === 'island' ? '島上' : '本島' } : {}),
    ...(trip.island && F.isHarbor(s.name, trip.island) ? { harbor: true } : {}),
    ...(s.timeLocked ? { timeLocked: true } : {}),
    ...(s.keepReason ? { keepReason: s.keepReason } : {}),
    ...(s.anchor ? { anchor: s.anchor, fixed: '錨點站，不能刪、不能改時間' } : {})
  };
}

function legsView(trip) {
  return F.legsOf(trip).map((l) => ({
    direction: l.direction === 'return' ? '回程' : '去程',
    day: l.day, date: I.dateOfDay(trip.startDate, l.day),
    depart: I.toClock(l.departMin), arrive: I.toClock(l.arriveMin),
    fromStopId: l.fromStopId, toStopId: l.toStopId
  }));
}

function candidateView(place, origin, date) {
  const h = D.hoursOn(place, date);
  return {
    name: place.name,
    distanceKm: Math.round(D.haversineKm(origin, place) * 10) / 10,
    indoor: D.indoorOf(place),
    hours: D.hoursText(h),
    ...(place.kind === 'food' ? { costPerPerson: Number(place.costPerPerson) || null } : { fee: Number.isFinite(Number(place.fee)) ? Number(place.fee) : null }),
    stayMin: place.duration || 60,
    ...(place.rating ? { rating: place.rating } : {})
  };
}

function originOf(ctx, stopId) {
  const s = ctx.trip.stops.find((x) => x.id === String(stopId || '')) || ctx.trip.stops[0];
  return s && Number.isFinite(s.lat) ? s : null;
}

function search(ctx, args, pool, defaultRadius) {
  const origin = originOf(ctx, args.nearStopId);
  if (!origin) return { error: '找不到 nearStopId，或該站沒有座標' };
  const day = Math.min(ctx.trip.days, Math.max(1, Number(args.day) || origin.day));
  const date = I.dateOfDay(ctx.trip.startDate, day);
  const radius = Math.min(40, Math.max(1, Number(args.radiusKm) || defaultRadius));
  const inTrip = new Set(ctx.trip.stops.map((s) => D.normName(s.name)));
  // 同一地點常有中文名與「中文名 + 英文名」兩筆，只留一筆，免得 6 個名額被重複佔掉
  const seenBase = new Set();
  const results = pool
    .filter((p) => !inTrip.has(p._key) && !D.accessOf(p).avoid)
    .filter((p) => {
      const base = p._key.replace(/[a-z0-9&'’.,()-]+/g, '');
      if (seenBase.has(base)) return false;
      seenBase.add(base);
      return true;
    })
    .filter((p) => !args.indoor || D.indoorOf(p) === 'indoor')
    .filter((p) => D.hoursOn(p, date).status !== 'closed')
    .map((p) => ({ p, km: D.haversineKm(origin, p) }))
    .filter((x) => x.km <= radius)
    .sort((a, b) => a.km - b.km)
    .slice(0, SEARCH_LIMIT)
    .map((x) => candidateView(x.p, origin, date));
  return { date, results, note: results.length ? undefined : '範圍內沒有符合的地點，可加大 radiusKm' };
}

const IMPL = {
  get_trip_state(ctx) {
    const t = ctx.trip;
    return {
      title: t.title, region: t.region, startDate: t.startDate, days: t.days,
      endTime: t.endTime, people: t.people, budgetPerPerson: t.budgetPerPerson,
      ...(t.island ? { island: t.island, ferryLegs: legsView(t), note: '港口站代表搭船，不能刪除或替換，只能 move／retime' } : {}),
      stops: t.stops.map((s) => ({ ...stopView(t, s), date: I.dateOfDay(t.startDate, s.day) }))
    };
  },

  async get_sea_conditions(ctx) {
    const t = ctx.trip;
    if (!t.island) return { note: '這不是離島行程，不需要查海況' };
    const byDate = await ctx.getSea(t);
    return {
      area: '綠島蘭嶼海面',
      days: Object.values(byDate).map((x) => ({
        date: x.date, risk: x.risk, reasons: x.reasons,
        ...(x.wave ? { wave: x.wave, wind: x.wind, waveType: x.waveType } : {}),
        ...(x.simulated ? { simulated: true } : {})
      })),
      thresholds: `高風險：浪高 ≥ ${SEA.THRESHOLDS.high.waveM} 公尺或平均風 ≥ ${SEA.THRESHOLDS.high.windLevel} 級或颱風海上警報；中風險：浪高 ≥ ${SEA.THRESHOLDS.medium.waveM} 公尺或陣風 ≥ ${SEA.THRESHOLDS.medium.gustLevel} 級`,
      note: '依中央氣象署海面預報推估，實際是否停航以船公司公告為準'
    };
  },

  async get_ferry_status(ctx) {
    const t = ctx.trip;
    if (!t.island) return { note: '這不是離島行程' };
    const byDate = await ctx.getSea(t);
    const alerts = await SEA.fetchTdxAlerts();
    const related = (alerts || []).filter((a) => a.title.includes(t.island) || a.description.includes(t.island));
    return {
      legs: legsView(t).map((l) => ({ ...l, risk: (byDate[l.date] || {}).risk || 'unknown', riskReasons: (byDate[l.date] || {}).reasons || [] })),
      officialAlerts: alerts === null ? '查不到（交通部航運資料暫時無法取得）' : (related.length ? related : '目前沒有停航公告'),
      schedule: '沒有船班時刻資料，改搭其他班次時只能估計時段，並提醒使用者向船公司確認'
    };
  },

  async get_weather_forecast(ctx) {
    const t = ctx.trip;
    const f = await ctx.getForecast();
    if (!f || !f.periods.length) return { source: f ? f.source : 'unavailable', note: '拿不到天氣預報' };
    const dates = new Set(Array.from({ length: t.days }, (_, i) => I.dateOfDay(t.startDate, i + 1)));
    const periods = f.periods.filter((p) => dates.has(p.start.slice(0, 10)));
    const affected = t.stops.map((s) => {
      const w = D.popAt(f, I.dateOfDay(t.startDate, s.day), I.toMin(s.time));
      return w && w.pop >= 50 ? { stopId: s.id, name: s.name, time: s.time, pop: w.pop, indoor: stopView(t, s).indoor } : null;
    }).filter(Boolean);
    return {
      source: f.source,
      periods: periods.map((p) => ({ from: p.start.replace('T', ' '), to: p.end.replace('T', ' '), wx: p.wx, pop: p.pop, ...(p.simulated ? { simulated: true } : {}) })),
      stopsInRain: affected,
      note: f.periods.length && !periods.length ? '行程日期超出一週預報範圍' : undefined
    };
  },

  check_business_hours(ctx, args) {
    const place = D.findPlace(args.name);
    if (!place) return { error: '本地資料找不到這個地點' };
    const date = I.dateOfDay(ctx.trip.startDate, Number(args.day) || 1);
    const h = D.hoursOn(place, date);
    return { name: place.name, date, status: h.status, hours: h.status === 'open' ? D.hoursText(h) : h.label || '', access: D.accessOf(place).label || 'open' };
  },

  search_local_poi(ctx, args) {
    return search(ctx, args, D.data().pois.filter((p) => p.kind !== 'transit'), 15);
  },

  search_restaurants(ctx, args) {
    return search(ctx, args, D.data().foods, 10);
  },

  estimate_travel(ctx, args) {
    const from = originOf(ctx, args.fromStopId);
    const to = D.findPlace(args.toName);
    if (!from || !to) return { error: '找不到起點或目的地' };
    const trip = ctx.draft || ctx.trip;
    const k = Number(trip && trip.travelFactor) || 1;
    return { minutes: I.travel(trip, from, to), distanceKm: Math.round(D.haversineKm(from, to) * 1.35 * 10) / 10, method: k === 1 ? '直線距離 × 1.35 估算' : `直線距離估算，再依這趟行程的實際車程校正（× ${k}）` };
  },

  async propose_patch(ctx, args) {
    if (!SEA.enabled() && Array.isArray(args.ops) && args.ops.some((o) => o && o.type === 'move_ferry')) {
      return { error: '船班不在這次的調整範圍：港口站與船班時間不能改，請只調整其他站' };
    }
    const { draft, results } = I.applyOps(ctx.trip, args.ops);
    const check = await ctx.check(draft);
    ctx.draft = draft;
    ctx.draftCheck = check;
    if (!check.ok) ctx.failedRounds += 1;
    return {
      ops: results,
      ok: check.ok,
      violations: check.violations.map((v) => v.message),
      warnings: check.warnings.slice(0, 4).map((w) => w.message),
      draft: draft.stops.map((s) => stopView(draft, s)),
      dayEnds: I.dayEnds(draft),
      dayEndsBefore: I.dayEnds(ctx.trip),
      ...(draft.island ? { ferryLegs: legsView(draft) } : {}),
      ...(draft.extraNights ? { extraNights: draft.extraNights } : {}),
      next: check.ok ? '驗證通過，可以呼叫 present_proposal' : '請針對 violations 修改 ops 後再呼叫 propose_patch（ops 要完整重給）'
    };
  },

  ask_user(_ctx, args) {
    return { question: String(args.question || '').slice(0, 200), options: (Array.isArray(args.options) ? args.options : []).slice(0, 3).map((o) => String(o).slice(0, 120)) };
  },

  present_proposal(ctx, args) {
    if (!ctx.draft) return { error: '還沒有草稿，請先呼叫 propose_patch' };
    if (!ctx.draftCheck.ok) return { error: '草稿還有違規，不能提案：' + ctx.draftCheck.violations.map((v) => v.message).join('；') };
    return { summary: String(args.summary || '').slice(0, 120), reasons: (Array.isArray(args.reasons) ? args.reasons : []).slice(0, 4).map((r) => String(r).slice(0, 120)) };
  },

  no_change_needed(_ctx, args) {
    return { reason: String(args.reason || '').slice(0, 200) };
  }
};

async function runTool(ctx, name, rawArgs) {
  if (!Object.prototype.hasOwnProperty.call(IMPL, name) || (SEA_TOOLS.has(name) && !SEA.enabled())) return { error: `沒有這個工具：${name}` };
  let args = rawArgs;
  if (typeof args === 'string') {
    try { args = args.trim() ? JSON.parse(args) : {}; } catch (_e) { return { error: '參數不是有效的 JSON' }; }
  }
  if (!args || typeof args !== 'object' || Array.isArray(args)) args = {};
  return IMPL[name](ctx, args);
}

function labelOf(name, args) {
  try { return (LABELS[name] || (() => name))(args || {}); } catch (_e) { return name; }
}

// 停航功能關閉時：不給海象／船班工具，propose_patch 也拿掉 move_ferry 選項
function definitions() {
  if (SEA.enabled()) return DEFINITIONS;
  return DEFINITIONS.filter((d) => !SEA_TOOLS.has(d.function.name)).map((d) => {
    if (d.function.name !== 'propose_patch') return d;
    const c = JSON.parse(JSON.stringify(d));
    const p = c.function.parameters.properties.ops.items.properties;
    p.type.enum = p.type.enum.filter((x) => x !== 'move_ferry');
    delete p.type.description;
    delete p.direction;
    return c;
  });
}

module.exports = { definitions, DEFINITIONS, TERMINAL, runTool, labelOf, stopView };
