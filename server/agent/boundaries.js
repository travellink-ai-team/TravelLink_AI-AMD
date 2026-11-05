'use strict';
/* ══════════════════════════════════════════════════
   景點範圍（OpenStreetMap 輪廓）：判斷「A 景點是不是在 B 景點裡面」
   ──────────────────────────────────────────────────
   讀前端同一份 app/osm-boundary-data.js（crawler 產生，ODbL）。
   名稱不共用字、但其實在同一個園區裡的景點（鷺鷥湖在臺東森林公園裡、
   龍鳳佛堂在鯉魚山上），sameAttraction 光看名稱抓不到，改看座標有沒有落在對方的輪廓內。
   檔案不存在或讀不到時，一律回 false（退回原本的名稱＋距離判斷），不影響排程。
   ══════════════════════════════════════════════════ */
const fs = require('fs');
const path = require('path');
const vm = require('vm');

const FILE = path.join(__dirname, '..', '..', 'app', 'osm-boundary-data.js');
const MAX_CENTROID_KM = 2;   // 景點座標離輪廓質心超過 2 km，就不是這個景點的輪廓

const norm = (s) => String(s || '')
  .replace(/[（(][^）)]*[）)]/g, '')
  .replace(/臺/g, '台')
  .toLowerCase()
  .replace(/[^\p{L}\p{N}]+/gu, '');

function haversineKm(a, b) {
  const R = 6371, rad = (x) => (x * Math.PI) / 180;
  const dLat = rad(b.lat - a.lat), dLng = rad(b.lng - a.lng);
  const s = Math.sin(dLat / 2) ** 2 + Math.cos(rad(a.lat)) * Math.cos(rad(b.lat)) * Math.sin(dLng / 2) ** 2;
  return 2 * R * Math.asin(Math.sqrt(s));
}

let items = null;
function load() {
  if (items) return items;
  items = [];
  try {
    const sandbox = { window: {} };
    vm.runInNewContext(fs.readFileSync(FILE, 'utf8'), sandbox, { filename: 'osm-boundary-data.js' });
    const list = (sandbox.window.WAI_OSM_BOUNDARY_DATA && sandbox.window.WAI_OSM_BOUNDARY_DATA.items) || [];
    for (const it of list) {
      if (!it || !it.n || !Array.isArray(it.r) || it.r.length < 3) continue;
      const ring = it.r.map(([lat, lng]) => ({ lat, lng }));
      items.push({
        norm: norm(it.n),
        ring,
        center: { lat: ring.reduce((s, p) => s + p.lat, 0) / ring.length, lng: ring.reduce((s, p) => s + p.lng, 0) / ring.length }
      });
    }
  } catch (_e) { /* 沒有邊界檔：維持空清單 */ }
  return items;
}

/** 某個景點自己的輪廓：OSM 名稱與景點名互相包含、質心在 2 km 內，取最近的一個 */
const ownCache = new Map();
function boundaryOf(place) {
  const n = norm(place && place.name);
  if (n.length < 2 || !Number.isFinite(place.lat) || !Number.isFinite(place.lng)) return null;
  const key = `${n}@${place.lat.toFixed(4)},${place.lng.toFixed(4)}`;
  if (ownCache.has(key)) return ownCache.get(key);
  let best = null, bestKm = Infinity;
  for (const b of load()) {
    if (!(b.norm.includes(n) || (b.norm.length >= 3 && n.includes(b.norm)))) continue;
    const km = haversineKm(place, b.center);
    if (km <= MAX_CENTROID_KM && km < bestKm) { best = b; bestKm = km; }
  }
  ownCache.set(key, best);
  return best;
}

function pointInRing(ring, p) {
  let inside = false;
  for (let i = 0, j = ring.length - 1; i < ring.length; j = i++) {
    const a = ring[i], b = ring[j];
    if ((a.lat > p.lat) !== (b.lat > p.lat) && p.lng < ((b.lng - a.lng) * (p.lat - a.lat)) / (b.lat - a.lat) + a.lng) inside = !inside;
  }
  return inside;
}

/** 兩個點是否在同一個輪廓裡（鷺鷥湖與活水湖都在臺東森林公園裡，但彼此不包含對方） */
function sharedOutline(a, b) {
  if (!a || !b || ![a.lat, a.lng, b.lat, b.lng].every(Number.isFinite)) return false;
  return load().some((o) => pointInRing(o.ring, a) && pointInRing(o.ring, b));
}

/** place 的座標是否落在 container 這個景點的輪廓內 */
function contains(container, place) {
  if (!place || !Number.isFinite(place.lat) || !Number.isFinite(place.lng)) return false;
  const b = boundaryOf(container);
  return !!b && pointInRing(b.ring, place);
}

module.exports = { contains, sharedOutline, boundaryOf, _reset: () => { items = null; ownCache.clear(); } };
