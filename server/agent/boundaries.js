'use strict';
/* ══════════════════════════════════════════════════
   同一處判斷：讀前端同一份人工確認清單（app/big-attraction-config.js）
   ──────────────────────────────────────────────────
   只有清單列出的大景點與別名才算同一處；OSM 輪廓（osm-boundary-data.js）只用在清單裡的大景點。
   全自動用輪廓或距離會判錯（都歷遊客中心算進阿美族民俗中心、天龍古道與天龍吊橋座標疊在一起）。
   檔案讀不到時一律回 false（退回 planner.js 的名稱判斷），不影響排程。
   ══════════════════════════════════════════════════ */
const fs = require('fs');
const path = require('path');
const vm = require('vm');

const APP_DIR = path.join(__dirname, '..', '..', 'app');

let list;
function load() {
  if (list !== undefined) return list;
  list = null;
  try {
    const sandbox = { window: {} };
    for (const file of ['osm-boundary-data.js', 'big-attraction-config.js']) {
      vm.runInNewContext(fs.readFileSync(path.join(APP_DIR, file), 'utf8'), sandbox, { filename: file });
    }
    list = sandbox.window.WAI_BIG_ATTRACTIONS || null;
  } catch (_e) { /* 沒有清單：維持 null */ }
  return list;
}

/** 兩個地點是否在人工確認清單裡算同一處（同名，或屬於同一個大景點） */
function isListedSamePlace(a, b) {
  const l = load();
  if (!l || !a || !b) return false;
  const coord = (p) => (Number.isFinite(p.lat) && Number.isFinite(p.lng) ? { lat: p.lat, lng: p.lng } : null);
  return !!l.isSamePlace(a.name, coord(a), b.name, coord(b));
}

module.exports = { isListedSamePlace };
