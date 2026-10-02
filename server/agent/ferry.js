'use strict';
/* ══════════════════════════════════════════════════
   離島船班：從行程的港口站點找出「搭船」的那幾段
   ──────────────────────────────────────────────────
   planner 的行程沒有獨立的船班欄位，搭船是用港口站點表示的
   （ai-travel-planner-v8.js 的 normalizeIslandHarborStops）：
     本島港（富岡漁港）→ 島內港（南寮漁港）＝ 去程
     島內港 → 本島港 ＝ 回程
   這裡讀同一份 app/ferry-config.js，判斷方式與 planner 一致。
   ══════════════════════════════════════════════════ */
const fs = require('fs');
const path = require('path');
const vm = require('vm');
const D = require('./data');

const CONFIG = (() => {
  try {
    const sandbox = { window: {} };
    vm.runInNewContext(fs.readFileSync(path.join(__dirname, '..', '..', 'app', 'ferry-config.js'), 'utf8'), sandbox);
    return sandbox.window.WAI_FERRY_CONFIG || {};
  } catch (_e) {
    return {};
  }
})();

// 航程分鐘：TDX 航線描述「富岡—綠島約 1 小時」「富岡—蘭嶼約 2 小時」
const FERRY_MINUTES = { 綠島: 60, 蘭嶼: 120 };
// 島上的站離島內港多遠以內算「在島上」（綠島約 4km 寬、蘭嶼約 10km）
const ISLAND_RADIUS_KM = { 綠島: 8, 蘭嶼: 14 };

const TAITUNG_ISLANDS = ['綠島', '蘭嶼'];

function islandOfTrip(trip) {
  const r = String(trip.region || '').replace(/臺/g, '台');
  if (TAITUNG_ISLANDS.includes(r)) return r;
  for (const s of trip.stops) {
    for (const island of TAITUNG_ISLANDS) if (isIslandHarbor(s.name, island)) return island;
  }
  return null;
}

function matches(name, target) {
  const a = D.normName(name), b = D.normName(target);
  return Boolean(a && b && (a === b || a.includes(b) || b.includes(a)));
}
function isMainlandHarbor(name, island) {
  const c = CONFIG[island];
  return Boolean(c && (c.mainlandHarborAlias || []).concat(c.mainlandHarbor ? [c.mainlandHarbor.name] : []).some((x) => matches(name, x)));
}
function isIslandHarbor(name, island) {
  const c = CONFIG[island];
  return Boolean(c && c.islandHarbor && matches(name, c.islandHarbor.name));
}
function isHarbor(name, island) {
  return isMainlandHarbor(name, island) || isIslandHarbor(name, island);
}

/** 這一站在島上還是本島（港口依港口判斷，其他依離島內港的距離） */
function sideOf(stop, island) {
  if (!island) return 'mainland';
  if (isIslandHarbor(stop.name, island)) return 'island';
  if (isMainlandHarbor(stop.name, island)) return 'mainland';
  const h = CONFIG[island] && CONFIG[island].islandHarbor;
  if (!h || !Number.isFinite(stop.lat)) {
    const place = D.findPlace(stop.name);
    return place && place.region === island ? 'island' : 'mainland';
  }
  return D.haversineKm(stop, h) <= (ISLAND_RADIUS_KM[island] || 8) ? 'island' : 'mainland';
}

/** 行程（已依時間排序）中的船班：[{ direction, day, fromStopId, toStopId, departTime, departMin, arriveMin }] */
function legsOf(trip) {
  const island = islandOfTrip(trip);
  if (!island) return [];
  const legs = [];
  const toMin = (t) => { const m = /^(\d{1,2}):(\d{2})$/.exec(t || ''); return m ? Number(m[1]) * 60 + Number(m[2]) : 0; };
  for (let i = 0; i + 1 < trip.stops.length; i++) {
    const a = trip.stops[i], b = trip.stops[i + 1];
    let direction = null;
    if (isMainlandHarbor(a.name, island) && isIslandHarbor(b.name, island)) direction = 'outbound';
    else if (isIslandHarbor(a.name, island) && isMainlandHarbor(b.name, island)) direction = 'return';
    if (!direction) continue;
    const departMin = toMin(a.time) + a.stayMin;   // 港口站的停留＝候船，結束時開船
    legs.push({
      island, direction, day: a.day, fromStopId: a.id, toStopId: b.id,
      departMin, arriveMin: departMin + (FERRY_MINUTES[island] || 60)
    });
  }
  return legs;
}

/** 兩站之間若是船班，回傳航程分鐘；不是就回 null（交給一般車程估算） */
function ferryMinutesBetween(a, b, island) {
  if (!island) return null;
  if ((isMainlandHarbor(a.name, island) && isIslandHarbor(b.name, island))
    || (isIslandHarbor(a.name, island) && isMainlandHarbor(b.name, island))) {
    return FERRY_MINUTES[island] || 60;
  }
  // 島上與本島之間沒有經過港口：不可能用開車到達
  if (sideOf(a, island) !== sideOf(b, island)) return Infinity;
  return null;
}

module.exports = { CONFIG, islandOfTrip, isHarbor, isIslandHarbor, isMainlandHarbor, sideOf, legsOf, ferryMinutesBetween, FERRY_MINUTES };
