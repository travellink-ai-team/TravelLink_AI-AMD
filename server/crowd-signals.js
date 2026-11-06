// 分流第二、三層的共用邏輯（不碰資料庫，方便離線測試）。
// 第二層：統計「某天某時段有幾份行程排了這個景點」。只存人數，不存是誰。
// 第三層：今天停車回報「已滿」的地點，生成時降低這些景點的優先度。
'use strict';

const SLOTS = ['am', 'noon', 'pm', 'night'];

function slotOf(time) {
  const m = /(\d{1,2}):(\d{2})/.exec(String(time || ''));
  if (!m) return null;
  const hour = Number(m[1]);
  if (hour < 11) return 'am';
  if (hour < 14) return 'noon';
  if (hour < 18) return 'pm';
  return 'night';
}

// Firestore 欄位路徑不能含 . ~ * / [ ]；臺／台 統一，兩種寫法算同一個景點
function stopKey(name) {
  return String(name || '')
    .replace(/臺/g, '台')
    .replace(/[（(][^）)]*[）)]/g, '')
    .replace(/[.~*/[\]`\s]/g, '')
    .slice(0, 60);
}

function isValidDate(date) {
  const text = String(date || '');
  if (!/^\d{4}-\d{2}-\d{2}$/.test(text)) return false;
  const parsed = new Date(`${text}T00:00:00Z`); // 2026-13-01 是 Invalid Date，2026-02-30 會滾到 3 月
  return !Number.isNaN(parsed.getTime()) && parsed.toISOString().slice(0, 10) === text;
}

// 多日行程第 N 天要算到那一天的日期
function dateForDay(date, dayIndex) {
  const day = Math.max(1, Math.round(Number(dayIndex)) || 1);
  const base = new Date(`${date}T00:00:00Z`);
  base.setUTCDate(base.getUTCDate() + day - 1);
  return base.toISOString().slice(0, 10);
}

// 把一趟行程的站轉成要累加的人數：[{ date, key, slot }]
function countsFromTrip(stops, departureDate) {
  const out = [];
  (Array.isArray(stops) ? stops : []).forEach((stop) => {
    if (!stop || stop.type === 'start' || stop.type === 'end' || stop.type === 'lodging') return;
    const key = stopKey(stop.name);
    if (!key) return;
    out.push({
      date: isValidDate(departureDate) ? dateForDay(departureDate, stop.dayIndex) : null,
      key,
      slot: slotOf(stop.time)
    });
  });
  return out;
}

// parking-report 的 destKey（d22p7537_121p1497）→ 座標
function coordsFromDestKey(destKey) {
  const m = /^d(m?\d+p\d+)_(m?\d+p\d+)$/.exec(String(destKey || ''));
  if (!m) return null;
  const parse = (s) => Number(s.replace('m', '-').replace('p', '.'));
  const lat = parse(m[1]), lng = parse(m[2]);
  return Number.isFinite(lat) && Number.isFinite(lng) ? { lat, lng } : null;
}

// 同一個地點只要有一筆「已滿」就回傳；只給座標，不帶回報者與備註
function fullSpotsFromReports(reports) {
  const seen = new Map();
  (Array.isArray(reports) ? reports : []).forEach((report) => {
    if (!report || report.type !== 'full' || seen.has(report.destKey)) return;
    const coords = coordsFromDestKey(report.destKey);
    if (coords) seen.set(report.destKey, coords);
  });
  return [...seen.values()];
}

// 台灣時間當天 00:00 的毫秒數
function startOfTaiwanDay(nowMs = Date.now()) {
  const shifted = nowMs + 8 * 3600000;
  return shifted - (shifted % 86400000) - 8 * 3600000;
}

module.exports = { SLOTS, slotOf, stopKey, isValidDate, dateForDay, countsFromTrip, coordsFromDestKey, fullSpotsFromReports, startOfTaiwanDay };
