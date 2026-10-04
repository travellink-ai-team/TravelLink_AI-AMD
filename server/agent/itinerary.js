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
const F = require('./ferry');

/** 兩站間移動分鐘：船班用航程，島上↔本島沒經過港口回 Infinity，其餘用車程估算 */
// 車程：用戶端（planner 用 Google 路線）送來的實際分鐘數優先——同一天原本就相鄰的兩站直接用；
// 刪站後才相鄰的新路段沒有實際資料，用這趟行程「實際 ÷ 直線估算」的比例校正（山路、繞路多的行程會偏慢）。
// 兩邊車程對不上時，套用後 planner 重算的時間會跟提案差十幾分鐘。
function travel(trip, a, b) {
  const f = F.ferryMinutesBetween(a, b, trip.island);
  if (f !== null) return f;
  if (a && b && a.legTo && a.legTo === b.id && Number.isFinite(a.legMin)) return a.legMin;
  const est = D.travelMinutes(a, b);
  const k = Number(trip && trip.travelFactor) || 1;
  return k === 1 ? est : Math.max(5, Math.round(est * k));
}

const legMinOf = (v) => {
  const n = Number(v);
  return Number.isFinite(n) && n > 0 && n <= 600 ? Math.round(n) : null;
};

// 已知路段的「實際 ÷ 估算」取中位數，限制在 0.7–2.5 倍，避免一段塞車或資料錯誤把整趟帶偏
function calibrateTravel(trip) {
  const ratios = [];
  for (let i = 0; i < trip.stops.length - 1; i += 1) {
    const a = trip.stops[i];
    const b = trip.stops[i + 1];
    if (a.legTo !== b.id || !Number.isFinite(a.lat) || !Number.isFinite(b.lat)) continue;
    if (F.ferryMinutesBetween(a, b, trip.island) !== null) continue;
    ratios.push(a.legMin / D.travelMinutes(a, b));
  }
  if (!ratios.length) return 1;
  ratios.sort((x, y) => x - y);
  const mid = ratios.length % 2 ? ratios[(ratios.length - 1) / 2] : (ratios[ratios.length / 2 - 1] + ratios[ratios.length / 2]) / 2;
  return Math.round(Math.min(2.5, Math.max(0.7, mid)) * 100) / 100;
}

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

const ANCHORS = new Set(['station', 'lodging', 'ferry']);
const STOP_TYPES = new Set(['scenic', 'food', 'transit']);

/** 錨點站與 keepReason 站（訂位、預約）：Agent 不能刪除、替換或移到別天 */
function isFixedStop(s) {
  return Boolean(s && (s.anchor || s.keepReason));
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
    const anchor = ANCHORS.has(s.anchor) ? s.anchor : '';
    const stopType = STOP_TYPES.has(s.stopType) ? s.stopType : '';
    trip.stops.push({
      // id 要原樣送回（用戶端靠它把提案套回 collabStopId），只去掉控制字元，不能過濾成英數——
      // App 的舊 id 長得像 web_{order}_{站名}，含中文
      id: String(s.id || 's' + (i + 1)).replace(/[\u0000-\u001f"\\]/g, '').trim().slice(0, 80) || 's' + (i + 1),
      day: Math.min(14, Math.max(1, Number(s.day) || 1)),
      time: clock(s.time, '09:00'),
      // 有送就照送的（0 也算有送：回程車站、港口停 0 分是正常的）；沒送才用本地資料的建議停留。
      // 原本用 ||，0 會被當成沒填，台東車站變成停 60 分、當天結束時間被推晚一小時。
      stayMin: Math.min(600, Number.isFinite(Number(s.stayMin)) && s.stayMin !== null && s.stayMin !== ''
        ? Math.max(anchor || stopType === 'transit' ? 0 : 5, Math.round(Number(s.stayMin)))
        : Math.max(5, (place && place.duration) || 60)),
      name: String(s.name).slice(0, 80),
      lat: Number.isFinite(lat) ? lat : null,
      lng: Number.isFinite(lng) ? lng : null,
      kind: s.kind === 'food' || stopType === 'food' || (!stopType && place && place.kind === 'food') ? 'food' : 'scenic',
      ...(stopType ? { stopType } : {}),
      // 錨點站（車站、住宿、船班港口）一律固定：不刪、不移、不改時間
      ...(anchor ? { anchor } : {}),
      // 用戶端送來的營業時間原文優先於本地資料（兩端判斷公休的依據一致，也避開同名抓錯）
      ...(s.businessHours ? { businessHours: String(s.businessHours).slice(0, 600) } : {}),
      ...(legMinOf(s.transitToNextMin) ? { legMin: legMinOf(s.transitToNextMin) } : {}),   // 到「下一站」的實際車程，下面配對
      timeLocked: s.timeLocked === true || Boolean(anchor),   // 使用者手動改過的時間：不自動挪
      keepReason: s.keepReason ? String(s.keepReason).slice(0, 60) : ''
    });
  });
  // transitToNextMin 是「依送來的順序」到下一站：排序前配對，記下下一站的 id（換掉、刪掉之後就不會誤用）
  trip.stops.forEach((s, i) => {
    const next = trip.stops[i + 1];
    if (Number.isFinite(s.legMin) && next && next.day === s.day) s.legTo = next.id;
    else delete s.legMin;
  });
  trip.days = Math.max(1, ...trip.stops.map((s) => s.day));
  sortStops(trip.stops);
  trip.island = F.islandOfTrip(trip);   // 綠島／蘭嶼行程才有值
  trip.travelFactor = calibrateTravel(trip);
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
function reflow(trip) {
  const stops = sortStops(trip.stops);
  for (let i = 1; i < stops.length; i++) {
    const prev = stops[i - 1], cur = stops[i];
    if (prev.day !== cur.day) continue;
    const move = travel(trip, prev, cur);
    if (!Number.isFinite(move)) continue;   // 沒搭船就跨海：留給 validate 報 wrong_side
    const earliest = toMin(prev.time) + prev.stayMin + move;
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
 * 把回程（或去程）船班整段改到另一天／另一個時間，簿記由程式處理：
 * - 兩個港口站一起移；島內港的時間＝開船時間 − 候船分鐘
 * - 回程：抵達本島後的本島站跟著移，保持與抵達時間的相對間隔
 * - 新開船時間之後還排在島上的站一律刪除（提前回程時一定會發生），回報給 LLM
 * 去程目前只支援改時間／日期，不會自動處理島上的站。
 */
function moveFerry(draft, op, maxDay) {
  if (!draft.island) return { error: '這不是離島行程' };
  const dir = op.direction === 'outbound' ? 'outbound' : 'return';
  const leg = F.legsOf(draft).find((l) => l.direction === dir);
  if (!leg) return { error: `行程裡找不到${dir === 'return' ? '回程' : '去程'}船班` };
  const day = Number(op.day);
  if (!Number.isInteger(day) || day < 1 || day > maxDay) return { error: `day 必須是 1～${maxDay}（${maxDay} 代表多住一晚）` };
  const depart = toMin(op.time) !== null ? toMin(op.time) : leg.departMin;
  if (depart < 6 * 60 || depart > 18 * 60) return { error: '開船時間要在 06:00～18:00 之間' };

  const from = draft.stops.find((s) => s.id === leg.fromStopId);
  const to = draft.stops.find((s) => s.id === leg.toStopId);
  const arriveOld = toMin(to.time);
  const idxTo = draft.stops.indexOf(to);
  // 回程後面的本島站（同一段旅程的延續）
  const tail = dir === 'return' ? draft.stops.slice(idxTo + 1).filter((s) => F.sideOf(s, draft.island) === 'mainland') : [];
  const tailOffsets = tail.map((s) => ({ s, dayDelta: s.day - to.day, offset: toMin(s.time) - arriveOld }));

  from.day = day;
  from.time = toClock(Math.max(0, depart - from.stayMin));
  from.timeLocked = false;
  to.day = day;
  to.time = toClock(depart + (F.FERRY_MINUTES[draft.island] || 60));
  to.timeLocked = false;
  const arriveNew = toMin(to.time);
  for (const t of tailOffsets) {
    t.s.day = Math.min(maxDay, day + t.dayDelta);
    t.s.time = toClock(Math.min(23 * 60, Math.max(arriveNew + 10, arriveNew + t.offset)));
    t.s.timeLocked = false;
  }

  const removed = [];
  if (dir === 'return') {
    const boarding = toMin(from.time);
    draft.stops = draft.stops.filter((s) => {
      if (s === from || s === to || F.sideOf(s, draft.island) !== 'island') return true;
      const after = s.day > day || (s.day === day && toMin(s.time) + s.stayMin > boarding);
      if (after) removed.push(s.name);
      return !after;
    });
  }
  return { direction: dir === 'return' ? '回程' : '去程', day, depart: toClock(depart), arrive: to.time, removedIslandStops: removed, movedMainlandStops: tail.map((s) => s.name) };
}

/**
 * 套用修改（只動草稿）。每個 op 個別成功或失敗，失敗原因回給 LLM 修正。
 * 支援：replace {stopId, name}、insert {day, afterStopId?, time?, name}、remove {stopId}、
 *       retime {stopId, time?, stayMin?}、move {stopId, day, time?}（day 可到「天數 + 1」＝多住一晚）
 * 港口站代表搭船：不能 remove／replace，只能 move／retime 改搭船的日期與時間。
 */
function applyOps(trip, ops) {
  const draft = { ...trip, stops: trip.stops.map((s) => ({ ...s })) };
  const results = [];
  const maxDay = Math.min(14, trip.days + 1);
  for (const op of (Array.isArray(ops) ? ops.slice(0, 16) : [])) {
    const type = op && op.type;
    const idx = draft.stops.findIndex((s) => s.id === String(op && op.stopId || ''));
    const fail = (msg) => results.push({ op: type, ok: false, error: msg });
    const isHarbor = idx >= 0 && trip.island && F.isHarbor(draft.stops[idx].name, trip.island);
    const target = idx >= 0 ? draft.stops[idx] : null;
    if (target && isFixedStop(target) && ['remove', 'replace', 'move'].includes(type)) {
      fail(`「${target.name}」是${target.anchor ? '錨點站（車站／住宿／港口）' : `固定行程（${target.keepReason}）`}，不能刪除、替換或移動`);
      continue;
    }
    if (target && target.anchor && type === 'retime') {
      fail(`「${target.name}」是錨點站，時間不能改`);
      continue;
    }
    if ((type === 'remove' || type === 'replace') && isHarbor) {
      fail(`「${draft.stops[idx].name}」是搭船的港口站，不能刪除或替換；要改搭船的日期或時間請用 move／retime`);
      continue;
    }
    if (type === 'move_ferry') {
      const r = moveFerry(draft, op, maxDay);
      if (r.error) fail(r.error);
      else results.push({ op: type, ok: true, ...r });
      continue;
    }
    if (type === 'move') {
      if (idx < 0) { fail(`找不到 stopId ${op.stopId}`); continue; }
      const day = Number(op.day);
      if (!Number.isInteger(day) || day < 1 || day > maxDay) { fail(`day 必須是 1～${maxDay}（${maxDay} 代表多住一晚）`); continue; }
      if (op.time !== undefined && op.time !== null && op.time !== '' && toMin(op.time) === null) { fail('time 必須是 HH:MM'); continue; }
      const s = draft.stops[idx];
      const fromDay = s.day;
      s.day = day;
      if (toMin(op.time) !== null) s.time = toClock(toMin(op.time));
      s.timeLocked = false;
      results.push({ op: type, ok: true, from: s.name, fromDay, day, time: s.time });
      continue;
    }
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
        const day = Math.min(maxDay, Math.max(1, Number(op.day) || 1));
        const after = draft.stops.find((s) => s.id === String(op.afterStopId || ''));
        let time = toMin(op.time);
        if (time === null) {
          const move = after ? travel(draft, after, place) : 0;
          time = after ? toMin(after.time) + after.stayMin + (Number.isFinite(move) ? move : 30) : 9 * 60;
        }
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
  draft.days = Math.max(1, ...draft.stops.map((s) => s.day));
  draft.extraNights = Math.max(0, draft.days - trip.days);
  reflow(draft);
  return { draft, results };
}

/**
 * 硬規則驗證。violations 會擋下提案；warnings 只提醒。
 * forecast 為 null（拿不到預報）時跳過天氣規則，並在 warnings 註明。
 * seaByDate：{ 'YYYY-MM-DD': sea.seaOn() 的結果 }，離島行程才需要，用來檢查船班的停航風險。
 */
function validate(trip, forecast, seaByDate) {
  const violations = [];
  const warnings = [];
  const add = (list, code, s, message) => list.push({ code, stopId: s && s.id, day: s && s.day, stop: s && s.name, message });
  const endMin = toMin(trip.endTime);
  const seen = new Map();

  trip.stops.forEach((s, i) => {
    const date = dateOfDay(trip.startDate, s.day);
    const start = toMin(s.time), end = start + s.stayMin;
    // 用戶端有送營業時間原文就用它（App／網頁判斷公休的依據），沒有才用本地資料
    const found = D.findPlace(s.name);
    const place = s.businessHours ? { ...(found || { name: s.name, kind: s.kind }), businessHours: s.businessHours } : found;

    // 港口站去回程各出現一次是正常的，也沒有營業時間可言
    const harbor = trip.island && F.isHarbor(s.name, trip.island);
    const key = D.normName(s.name);
    if (seen.has(key) && !harbor) add(violations, 'duplicate', s, `「${s.name}」重複出現`);
    seen.set(key, true);

    // 錨點站（車站、住宿、港口）與交通站是「到那裡」，不是去參觀：不查營業時間，否則永遠跳「營業時間未知」
    if (place && !harbor && !s.anchor && s.stopType !== 'transit') {
      if (D.accessOf(place).avoid) add(violations, 'suspended', s, `「${s.name}」目前暫停開放`);
      const h = D.hoursOn(place, date);
      if (h.status === 'closed') add(violations, 'closed', s, `「${s.name}」${date} 公休`);
      else if (h.status === 'open' && !D.fitsHours(h, start, end)) {
        add(violations, 'hours', s, `「${s.name}」營業 ${D.hoursText(h)}，排在 ${s.time}–${toClock(end)} 不在營業時間內`);
      } else if (h.status === 'unknown') add(warnings, 'hours_unknown', s, `「${s.name}」營業時間未知`);
    }

    const next = trip.stops[i + 1];
    const move = next ? travel(trip, s, next) : 0;
    if (next && !Number.isFinite(move)) {
      add(violations, 'wrong_side', next, `「${next.name}」在${F.sideOf(next, trip.island) === 'island' ? '島上' : '本島'}，但前一站「${s.name}」在${F.sideOf(s, trip.island) === 'island' ? '島上' : '本島'}，中間沒有搭船`);
    } else if (next && next.day === s.day) {
      const need = end + move;
      if (toMin(next.time) < need) add(violations, 'overlap', next, `「${next.name}」${next.time} 來不及（前一站結束加${F.ferryMinutesBetween(s, next, trip.island) ? '航程' : '車程'}要 ${toClock(need)}）`);
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

  // 船班停航風險：高＝擋下，中＝提醒。港口站有 keepReason（使用者堅持）就只提醒。
  // seaByDate 是 null＝不檢查（停航功能關閉，或延誤情境）
  if (seaByDate) for (const leg of F.legsOf(trip)) {
    const date = dateOfDay(trip.startDate, leg.day);
    const sea = seaByDate && seaByDate[date];
    const from = trip.stops.find((x) => x.id === leg.fromStopId);
    const label = `第 ${leg.day} 天 ${toClock(leg.departMin)} ${leg.direction === 'return' ? '回程' : '去程'}船班`;
    if (!sea || sea.risk === 'unknown') {
      warnings.push({ code: 'sea_unknown', stopId: leg.fromStopId, day: leg.day, message: `${label}：拿不到海象預報，無法判斷停航風險` });
    } else if (sea.risk === 'high' && !(from && from.keepReason)) {
      violations.push({ code: 'ferry_risk', stopId: leg.fromStopId, day: leg.day, message: `${label}停航風險高：${sea.reasons.join('、')}${sea.simulated ? '（模擬）' : ''}` });
    } else if (sea.risk === 'high' || sea.risk === 'medium') {
      warnings.push({ code: 'ferry_risk', stopId: leg.fromStopId, day: leg.day, message: `${label}停航風險${sea.risk === 'high' ? '高' : '中'}：${sea.reasons.join('、')}` });
    }
  }

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
      if (old && old.day !== s.day) {
        changes.push({ type: 'move', name: s.name, fromDay: old.day, day: s.day, from: old.time, to: s.time });
      } else if (old && (old.time !== s.time || old.stayMin !== s.stayMin)) {
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
  toMin, toClock, dateOfDay, todayIso, normalizeTrip, legMinOf, reflow, applyOps, validate, costPerPerson, dayEnds, diffTrips, isFixedStop, travel
};
