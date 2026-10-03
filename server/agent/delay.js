'use strict';
/* ══════════════════════════════════════════════════
   T4 延誤觸發（docs/amd-agent/t4-delay-trigger-spec.md）
   ──────────────────────────────────────────────────
   順序：驗證請求 → 程式先順延當天剩下的站 → 驗證（營業時間、車程、收工時間、
   回程火車／末班船期限、固定站）→ 全部排得下就回「只改時間」的提案、不呼叫 AI；
   排不下才交給 Agent（只能刪站、縮短停留）。AI 失敗時用規則從後面刪不固定的站。
   時間一律用用戶端送來的（demo 假時鐘），不用伺服器時間。
   ══════════════════════════════════════════════════ */
const I = require('./itinerary');

const ISO_RE = /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}(:\d{2}(\.\d+)?)?(Z|[+-]\d{2}:\d{2})$/;
const TRAIN_BUFFER_MIN = 10;   // 進站、找月台
const FERRY_BUFFER_MIN = 20;   // 劃位、登船

/** ISO 時間（必須帶時區）→ 台灣時間的 { date, min }；格式不對回 null */
function parseIso(value) {
  if (typeof value !== 'string' || !ISO_RE.test(value)) return null;
  const t = Date.parse(value);
  if (!Number.isFinite(t)) return null;
  const tw = new Date(t + 8 * 3600 * 1000);
  return { date: tw.toISOString().slice(0, 10), min: tw.getUTCHours() * 60 + tw.getUTCMinutes() };
}

const bad = (message) => ({ error: message });

/**
 * 驗證並整理 delay 請求。回傳 { error } 或整理好的 { day, now, from, leave, delayMin, returnTrain, lastFerry, appConflicts }。
 * 規格第 5 節：每一站都要有已存進 Firestore 的 id，缺 id 直接拒絕，不產生套不回去的提案。
 */
function parseDelayRequest(trigger, trip, scenario) {
  const t = trigger || {};
  const day = Number(t.day);
  if (!Number.isInteger(day) || day < 1 || day > 14) return bad('trigger.day 必須是 1～14 的整數');
  const now = parseIso(t.now);
  if (!now) return bad('trigger.now 必須是帶時區的 ISO 時間，例如 2026-11-07T14:30:00+08:00');
  const f = t.from || {};
  if (!f.stopId || !String(f.name || '').trim()) return bad('trigger.from 需要 stopId 與 name');
  const leave = parseIso(f.leaveAt);
  if (!leave) return bad('trigger.from.leaveAt 必須是帶時區的 ISO 時間');
  const stops = Array.isArray(trip && trip.stops) ? trip.stops : [];
  const missing = stops.filter((s) => !s || !String(s.id || '').trim());
  if (missing.length) return bad(`有 ${missing.length} 站缺少 id（Firestore 的 collabStopId）。請先把 id 寫進 Firestore 再送出`);

  let delayMin = Number.isFinite(Number(f.delayMin)) ? Math.round(Number(f.delayMin)) : null;
  let leaveMin = leave.min;
  // demo：固定延誤 N 分鐘（原定離開＝leaveAt − delayMin，再加上 N）
  const demo = scenario && scenario.delay && Number(scenario.delay.minutes);
  if (Number.isFinite(demo) && demo >= 0) {
    leaveMin = leave.min - (delayMin || 0) + Math.round(demo);
    delayMin = Math.round(demo);
  }
  const deadline = (x) => {
    if (!x || !x.departAt) return null;
    const p = parseIso(x.departAt);
    return p ? { date: p.date, min: p.min, name: String(x.station || x.harbor || '').slice(0, 40) } : null;
  };
  return {
    day,
    now,
    from: {
      id: String(f.stopId).slice(0, 80),
      name: String(f.name).slice(0, 80),
      lat: Number.isFinite(Number(f.lat)) ? Number(f.lat) : null,
      lng: Number.isFinite(Number(f.lng)) ? Number(f.lng) : null
    },
    leave: { date: leave.date, min: leaveMin },
    delayMin,
    returnTrain: deadline(t.returnTrain),
    lastFerry: deadline(t.lastFerry),
    appConflicts: (Array.isArray(t.appConflicts) ? t.appConflicts : []).slice(0, 20).map((c) => ({
      kind: String((c && c.kind) || '').slice(0, 30), stopId: String((c && c.stopId) || '').slice(0, 80), message: String((c && c.message) || '').slice(0, 200)
    })),
    simulated: Number.isFinite(demo)
  };
}

/** 只留當天的站，並把 startDate 對齊到 leaveAt 的日期（營業時間要看「那一天」） */
function dayTrip(trip, req) {
  const d = new Date(req.leave.date + 'T00:00:00Z');
  d.setUTCDate(d.getUTCDate() - (req.day - 1));
  const stops = trip.stops.filter((s) => s.day === req.day).map((s) => ({ ...s }));
  // 用戶端的收工時間可能比原本排好的錨點站還早（App 實測：endTime 14:00、回程車站排 14:07）。
  // 錨點站時間固定、刪再多站也不會變，不放寬的話 day_end 永遠過不了，AI 只能一直問使用者。
  let endTime = trip.endTime;
  for (const s of stops) {
    if (!s.anchor) continue;
    const end = I.toMin(s.time) + (Number(s.stayMin) || 0);
    if (!endTime || end > I.toMin(endTime)) endTime = I.toClock(end);
  }
  return { ...trip, startDate: d.toISOString().slice(0, 10), stops, days: req.day, ...(endTime ? { endTime } : {}) };
}

const fixedTime = (s) => s.anchor || s.keepReason || s.timeLocked;

/** 從 leaveAt 開始依車程順延；固定時間的站不動（排不到就交給驗證報違規） */
function shift(base, req) {
  const draft = { ...base, stops: base.stops.map((s) => ({ ...s })) };
  let prev = { lat: req.from.lat, lng: req.from.lng, name: req.from.name };
  let cursor = req.leave.min;
  for (const s of draft.stops) {
    const move = I.travel(draft, prev, s);
    const arrive = cursor + (Number.isFinite(move) ? move : 15);
    if (!fixedTime(s)) {
      const planned = I.toMin(s.time);
      s.time = I.toClock(Math.min(23 * 60 + 55, Math.max(planned, Math.ceil(arrive / 5) * 5)));
    }
    cursor = I.toMin(s.time) + s.stayMin;
    prev = s;
  }
  return draft;
}

/**
 * 延誤情境的驗證：既有規則（營業時間、車程、收工時間、重複）＋
 * 從目前位置趕得上第一站嗎＋回程火車／末班船期限＋固定站有沒有被刪或被挪。
 */
function check(draft, base, req) {
  const v = I.validate(draft, null, null);
  const violations = v.violations.slice();
  const warnings = v.warnings.filter((w) => w.code !== 'weather_unknown');

  const first = draft.stops[0];
  if (first) {
    const move = I.travel(draft, { lat: req.from.lat, lng: req.from.lng, name: req.from.name }, first);
    const need = req.leave.min + (Number.isFinite(move) ? move : 15);
    if (I.toMin(first.time) < need) {
      violations.push({ code: 'late_start', stopId: first.id, day: first.day, stop: first.name, message: `從「${req.from.name}」${I.toClock(req.leave.min)} 出發，到「${first.name}」最快 ${I.toClock(need)}，趕不上 ${first.time}` });
    }
  }

  // 期限看的是「最快什麼時候到得了車站／港口」：前一站結束＋車程。
  // 不能拿錨點站自己的表定時間比——那是使用者原本的計畫，刪再多站它也不會變，永遠過不了。
  const earliestArrival = (anchorType) => {
    const idx = draft.stops.map((s) => s.anchor).lastIndexOf(anchorType);
    const before = idx >= 0 ? draft.stops.slice(0, idx) : draft.stops;
    const prev = before[before.length - 1];
    const target = idx >= 0 ? draft.stops[idx] : null;
    if (!prev) {
      const move = target ? I.travel(draft, { lat: req.from.lat, lng: req.from.lng, name: req.from.name }, target) : 20;
      return { target, arrive: req.leave.min + (Number.isFinite(move) ? move : 20) };
    }
    const move = target ? I.travel(draft, prev, target) : 20;
    return { target, arrive: I.toMin(prev.time) + prev.stayMin + (Number.isFinite(move) ? move : 20) };
  };
  const deadlineCheck = (dl, anchorType, buffer, label) => {
    if (!dl) return;
    const { target, arrive } = earliestArrival(anchorType);
    const limit = dl.min - buffer;
    if (arrive > limit) {
      violations.push({ code: 'deadline', stopId: target && target.id, day: req.day, stop: target ? target.name : dl.name, message: `趕不上 ${I.toClock(dl.min)} 的${label}${dl.name ? `（${dl.name}）` : ''}：最快 ${I.toClock(arrive)} 才到，至少要提早 ${buffer} 分鐘` });
    }
  };
  deadlineCheck(req.returnTrain, 'station', TRAIN_BUFFER_MIN, '回程火車');
  deadlineCheck(req.lastFerry, 'ferry', FERRY_BUFFER_MIN, '末班船');

  for (const b of base.stops) {
    if (!b.anchor && !b.keepReason) continue;
    const d = draft.stops.find((s) => s.id === b.id);
    if (!d) violations.push({ code: 'fixed_removed', stopId: b.id, day: b.day, stop: b.name, message: `「${b.name}」是固定的站，不能刪除` });
    else if (d.time !== b.time) violations.push({ code: 'fixed_moved', stopId: b.id, day: b.day, stop: b.name, message: `「${b.name}」是固定的站，時間不能從 ${b.time} 改成 ${d.time}` });
  }
  return { ok: violations.length === 0, violations, warnings };
}

/** 規則式降級：有違規就刪掉一個可刪的站再重新順延，直到排得下（最多刪一半） */
function fallback(base, req) {
  let remaining = base.stops.map((s) => ({ ...s }));
  const removed = [];
  for (let round = 0; round <= Math.ceil(base.stops.length / 2); round++) {
    const draft = shift({ ...base, stops: remaining }, req);
    const c = check(draft, base, req);
    if (c.ok) return { draft, check: c, removed };
    const removable = draft.stops.filter((s) => !I.isFixedStop(s) && !s.timeLocked);
    if (!removable.length) break;
    // 先刪有違規的那站；違規都在固定站或期限上，就刪固定站之前最後一個可刪的站
    const hit = c.violations.map((v) => removable.find((s) => s.id === v.stopId)).find(Boolean);
    const firstBad = c.violations.map((v) => draft.stops.findIndex((s) => s.id === v.stopId)).filter((i) => i >= 0).sort((a, b) => a - b)[0];
    const before = Number.isFinite(firstBad) ? removable.filter((s) => draft.stops.indexOf(s) < firstBad) : removable;
    const victim = hit || (before.length ? before[before.length - 1] : removable[removable.length - 1]);
    removed.push(victim.name);
    remaining = remaining.filter((s) => s.id !== victim.id);
  }
  return null;
}

/** 給 LLM 的觸發說明 */
function triggerText(req, issues, base) {
  // 固定時間逐一列出：模型容易把「火車 19:40」寫成「19:40 抵達車站」
  const fixed = (base ? base.stops : []).filter((s) => s.anchor || s.keepReason)
    .map((s) => `「${s.name}」固定在 ${s.time}${s.keepReason ? `（${s.keepReason}）` : ''}`);
  const deadlines = [
    req.returnTrain ? `回程火車 ${I.toClock(req.returnTrain.min)} 開，要在 ${I.toClock(req.returnTrain.min - TRAIN_BUFFER_MIN)} 前到車站` : '',
    req.lastFerry ? `末班船 ${I.toClock(req.lastFerry.min)} 開，要在 ${I.toClock(req.lastFerry.min - FERRY_BUFFER_MIN)} 前到港口` : ''
  ].filter(Boolean);
  return [
    `旅途中延誤：使用者在「${req.from.name}」，預計 ${I.toClock(req.leave.min)} 才離開${req.delayMin != null ? `（比原定晚 ${req.delayMin} 分鐘）` : ''}。`,
    ...(fixed.length ? ['固定的時間（不會變）：' + fixed.join('、')] : []),
    ...(deadlines.length ? ['期限：' + deadlines.join('；')] : []),
    '程式已依車程把當天後面的站順延，但仍有衝突：',
    ...issues.map((m) => '・' + m),
    '請只用 remove（刪站）或 retime 帶 stayMin（縮短停留）解決，不要新增景點。',
    '錨點站（anchor：車站、住宿、港口）與有 keepReason 的站不能刪也不能改。',
    '先呼叫 get_trip_state 看順延後的時間，再用 propose_patch；全部解決後呼叫 present_proposal。',
    '這個情境不能詢問使用者，也不能延後結束時間或改到隔天：一定要用刪站、縮短停留自己排出來。',
    'summary 與 reasons 不要寫任何時刻（程式會補上幾點到車站／港口），reasons 只說明為什麼刪這站、留那站。'
  ].join('\n');
}

module.exports = { parseIso, parseDelayRequest, dayTrip, shift, check, fallback, triggerText };
