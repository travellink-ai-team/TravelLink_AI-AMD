'use strict';
/* ══════════════════════════════════════════════════
   行程的純程式邏輯：正規化、套用修改、驗證、差異
   ──────────────────────────────────────────────────
   設計原則（docs/amd-agent/agent-tools-and-replanning.md 第 1 節）：
   LLM 負責判斷，程式負責驗證。營業時間、時間順序、天氣這些硬規則
   一律在這裡檢查，不交給 LLM 猜；LLM 提的地點也必須存在於本地資料，
   座標一律由這裡填，不收 LLM 給的座標。
   ══════════════════════════════════════════════════ */
const D = require('./data');

const toMin = (hhmm) => {
  const m = /^(\d{1,2}):(\d{2})$/.exec(String(hhmm || ''));
  return m ? Number(m[1]) * 60 + Number(m[2]) : null;
};
const toClock = (min) => `${String(Math.floor(min / 60)).padStart(2, '0')}:${String(min % 60).padStart(2, '0')}`;
const ceil5 = (min) => Math.ceil(min / 5) * 5;

function dateOfDay(startDate, day) {
  const d = new Date(startDate + 'T00:00:00Z');
  d.setUTCDate(d.getUTCDate() + day - 1);
  return d.toISOString().slice(0, 10);
}

function todayIso() {
  return new Date(Date.now() + 8 * 3600 * 1000).toISOString().slice(0, 10);   // 台灣時間
}

/** 前端送來的行程一律重新組裝：限長度、限數量，座標缺的從本地資料補，不信任其他欄位 */
function normalizeTrip(raw) {
  const r = raw || {};
  const clock = (v, def) => (toMin(v) !== null ? toClock(toMin(v)) : def);
  const trip = {
    title: String(r.title || '我的行程').slice(0, 60),
    region: String(r.region || '台東').replace(/臺/g, '台').slice(0, 20),
    startDate: /^\d{4}-\d{2}-\d{2}$/.test(r.startDate) ? r.startDate : todayIso(),
    endTime: clock(r.endTime, '20:00'),
    people: Math.min(20, Math.max(1, Number(r.people) || 2)),
    budgetPerPerson: Number(r.budgetPerPerson) > 0 ? Number(r.budgetPerPerson) : null,
    stops: []
  };
  const list = Array.isArray(r.stops) ? r.stops.slice(0, 60) : [];
  list.forEach((s, i) => {
    if (!s || !s.name || toMin(s.time) === null) return;
    const place = D.findPlace(s.name);
    const lat = Number.isFinite(s.lat) ? s.lat : place && place.lat;
    const lng = Number.isFinite(s.lng) ? s.lng : place && place.lng;
    trip.stops.push({
      id: String(s.id || 's' + (i + 1)).replace(/[^\w-]/g, '').slice(0, 40) || 's' + (i + 1),
      day: Math.min(14, Math.max(1, Number(s.day) || 1)),
      time: clock(s.time, '09:00'),
      stayMin: Math.min(600, Math.max(10, Number(s.stayMin) || (place && place.duration) || 60)),
      name: String(s.name).slice(0, 80),
      lat: Number.isFinite(lat) ? lat : null,
      lng: Number.isFinite(lng) ? lng : null,
      kind: s.kind === 'food' || (place && place.kind === 'food') ? 'food' : 'scenic',
      timeLocked: s.timeLocked === true,       // 使用者手動改過的時間：不自動挪
      keepReason: s.keepReason ? String(s.keepReason).slice(0, 60) : ''
    });
  });
  trip.days = Math.max(1, ...trip.stops.map((s) => s.day));
  sortStops(trip.stops);
  return trip;
}

function sortStops(stops) {
  stops.sort((a, b) => a.day - b.day || toMin(a.time) - toMin(b.time));
  return stops;
}

/**
 * 依車程把同一天的站往後推，消除重疊。
 * 使用者手動鎖定的時間不動——推不開就留給 validate 報違規，交給 Agent 或使用者決定。
 */
function reflow(stops) {
  sortStops(stops);
  for (let i = 1; i < stops.length; i++) {
    const prev = stops[i - 1], cur = stops[i];
    if (prev.day !== cur.day) continue;
    const earliest = toMin(prev.time) + prev.stayMin + D.travelMinutes(prev, cur);
    if (toMin(cur.time) < earliest && !cur.timeLocked) cur.time = toClock(ceil5(earliest));
  }
  return stops;
}

let opSeq = 0;
function stopFromPlace(place, base) {
  opSeq += 1;
  return {
    id: 'a' + Date.now().toString(36).slice(-4) + opSeq,
    day: base.day,
    time: base.time,
    stayMin: Math.min(240, Math.max(20, Number(base.stayMin) || place.duration || 60)),
    name: place.name,
    lat: place.lat,
    lng: place.lng,
    kind: place.kind === 'food' ? 'food' : 'scenic',
    timeLocked: false,
    keepReason: '',
    agentAdded: true
  };
}

/**
 * 套用修改（只動草稿）。每個 op 個別成功或失敗，失敗原因回給 LLM 修正。
 * 支援：replace {stopId, name}、insert {day, afterStopId?, time?, name}、remove {stopId}、retime {stopId, time}
 */
function applyOps(trip, ops) {
  const draft = { ...trip, stops: trip.stops.map((s) => ({ ...s })) };
  const results = [];
  for (const op of (Array.isArray(ops) ? ops.slice(0, 12) : [])) {
    const type = op && op.type;
    const idx = draft.stops.findIndex((s) => s.id === String(op && op.stopId || ''));
    const fail = (msg) => results.push({ op: type, ok: false, error: msg });
    if (type === 'replace' || type === 'insert') {
      const place = D.findPlace(op.name);
      if (!place) { fail(`「${op.name}」不在本地資料裡，只能用 search_local_poi／search_restaurants 回傳的名稱`); continue; }
      if (D.accessOf(place).avoid) { fail(`「${place.name}」目前暫停開放`); continue; }
      if (draft.stops.some((s) => D.normName(s.name) === place._key)) { fail(`「${place.name}」已經在行程裡`); continue; }
      if (type === 'replace') {
        if (idx < 0) { fail(`找不到 stopId ${op.stopId}`); continue; }
        const old = draft.stops[idx];
        draft.stops[idx] = { ...stopFromPlace(place, { day: old.day, time: old.time, stayMin: op.stayMin }), replaces: old.id };
        results.push({ op: type, ok: true, from: old.name, to: place.name });
      } else {
        const day = Math.min(draft.days, Math.max(1, Number(op.day) || 1));
        const after = draft.stops.find((s) => s.id === String(op.afterStopId || ''));
        let time = toMin(op.time);
        if (time === null) time = after ? toMin(after.time) + after.stayMin + D.travelMinutes(after, place) : 9 * 60;
        draft.stops.push(stopFromPlace(place, { day: after ? after.day : day, time: toClock(ceil5(time)), stayMin: op.stayMin }));
        results.push({ op: type, ok: true, to: place.name });
      }
    } else if (type === 'remove') {
      if (idx < 0) { fail(`找不到 stopId ${op.stopId}`); continue; }
      results.push({ op: type, ok: true, from: draft.stops[idx].name });
      draft.stops.splice(idx, 1);
    } else if (type === 'retime') {
      // 改時間、改停留長度，或兩者都改
      if (idx < 0) { fail(`找不到 stopId ${op.stopId}`); continue; }
      const hasTime = op.time !== undefined && op.time !== null && op.time !== '';
      const hasStay = Number(op.stayMin) > 0;
      if (!hasTime && !hasStay) { fail('retime 要帶 time（HH:MM）或 stayMin'); continue; }
      if (hasTime && toMin(op.time) === null) { fail('time 必須是 HH:MM'); continue; }
      const s = draft.stops[idx];
      if (hasTime) { s.time = toClock(toMin(op.time)); s.timeLocked = false; }
      if (hasStay) s.stayMin = Math.min(600, Math.max(10, Math.round(Number(op.stayMin))));
      results.push({ op: type, ok: true, from: s.name, time: s.time, stayMin: s.stayMin });
    } else {
      fail(`不支援的 op：${type}`);
    }
  }
  reflow(draft.stops);
  return { draft, results };
}

/**
 * 硬規則驗證。violations 會擋下提案；warnings 只提醒。
 * forecast 為 null（拿不到預報）時跳過天氣規則，並在 warnings 註明。
 */
function validate(trip, forecast) {
  const violations = [];
  const warnings = [];
  const add = (list, code, s, message) => list.push({ code, stopId: s && s.id, day: s && s.day, stop: s && s.name, message });
  const endMin = toMin(trip.endTime);
  const seen = new Map();

  trip.stops.forEach((s, i) => {
    const date = dateOfDay(trip.startDate, s.day);
    const start = toMin(s.time), end = start + s.stayMin;
    const place = D.findPlace(s.name);

    const key = D.normName(s.name);
    if (seen.has(key)) add(violations, 'duplicate', s, `「${s.name}」重複出現`);
    seen.set(key, true);

    if (place) {
      if (D.accessOf(place).avoid) add(violations, 'suspended', s, `「${s.name}」目前暫停開放`);
      const h = D.hoursOn(place, date);
      if (h.status === 'closed') add(violations, 'closed', s, `「${s.name}」${date} 公休`);
      else if (h.status === 'open' && !D.fitsHours(h, start, end)) {
        add(violations, 'hours', s, `「${s.name}」營業 ${D.hoursText(h)}，排在 ${s.time}–${toClock(end)} 不在營業時間內`);
      } else if (h.status === 'unknown') add(warnings, 'hours_unknown', s, `「${s.name}」營業時間未知`);
    }

    const next = trip.stops[i + 1];
    if (next && next.day === s.day) {
      const need = end + D.travelMinutes(s, next);
      if (toMin(next.time) < need) add(violations, 'overlap', next, `「${next.name}」${next.time} 來不及（前一站結束加車程要 ${toClock(need)}）`);
    }
    if ((!next || next.day !== s.day) && end > endMin) add(violations, 'day_end', s, `第 ${s.day} 天結束在 ${toClock(end)}，超過 ${trip.endTime}`);

    if (forecast) {
      const w = D.popAt(forecast, date, start) || D.popAt(forecast, date, Math.min(end, 1439));
      const indoor = place ? D.indoorOf(place) : (s.kind === 'food' ? 'indoor' : 'unknown');
      if (w && w.pop >= 50 && indoor !== 'indoor' && !s.keepReason) {
        add(violations, 'rain', s, `「${s.name}」${indoor === 'outdoor' ? '是戶外景點' : '室內外不明'}，${date} ${s.time} 降雨機率 ${w.pop}%${w.simulated ? '（模擬）' : ''}`);
      }
    }
  });
  if (!forecast) warnings.push({ code: 'weather_unknown', message: '拿不到天氣預報，未檢查降雨' });

  if (trip.budgetPerPerson) {
    const cost = costPerPerson(trip);
    if (cost > trip.budgetPerPerson * 1.1) {
      violations.push({ code: 'budget', message: `每人門票加餐費約 NT$${cost}，超過預算 NT$${trip.budgetPerPerson} 的 1.1 倍` });
    }
  }
  return { ok: violations.length === 0, violations, warnings };
}

function costPerPerson(trip) {
  return trip.stops.reduce((sum, s) => sum + D.costOf(D.findPlace(s.name)), 0);
}

/** 每天最後一站的結束時間（給 LLM 寫摘要時引用正確數字） */
function dayEnds(trip) {
  const out = {};
  for (const s of trip.stops) {
    const end = toMin(s.time) + s.stayMin;
    if (!out[s.day] || end > toMin(out[s.day])) out[s.day] = toClock(end);
  }
  return out;
}

/** 修改前後對照，給提案卡用 */
function diffTrips(before, after) {
  const changes = [];
  const afterIds = new Set(after.stops.map((s) => s.id));
  const replacedIds = new Set(after.stops.filter((s) => s.replaces).map((s) => s.replaces));
  for (const s of after.stops) {
    if (s.replaces) {
      const old = before.stops.find((b) => b.id === s.replaces);
      changes.push({ type: 'replace', day: s.day, time: s.time, from: old && old.name, to: s.name });
    } else if (s.agentAdded) {
      changes.push({ type: 'insert', day: s.day, time: s.time, to: s.name });
    } else {
      const old = before.stops.find((b) => b.id === s.id);
      if (old && (old.time !== s.time || old.stayMin !== s.stayMin)) {
        changes.push({ type: 'retime', day: s.day, name: s.name, from: old.time, to: s.time, stayFrom: old.stayMin, stayTo: s.stayMin });
      }
    }
  }
  for (const b of before.stops) {
    if (!afterIds.has(b.id) && !replacedIds.has(b.id)) changes.push({ type: 'remove', day: b.day, time: b.time, from: b.name });
  }
  return changes;
}

module.exports = {
  toMin, toClock, dateOfDay, todayIso, normalizeTrip, reflow, applyOps, validate, costPerPerson, dayEnds, diffTrips
};
