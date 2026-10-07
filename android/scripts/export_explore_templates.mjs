// 從網頁端產生「官方精選範本」JSON，讓 App 與網頁看到完全相同的範本（計畫 D5 方案 B）。
//
// 直接執行網頁自己的 explore-templates.js（它本來就支援 Node 載入）與 poi-data.js，
// 照網頁 initOfficialTemplates() 的同一套步驟組出範本——不在 App 端重寫演算法，
// 兩端就不會因為各自實作而飄移。
//
// 用法：
//   node scripts/export_explore_templates.mjs              # 從線上網頁抓最新檔案
//   node scripts/export_explore_templates.mjs --local DIR  # 改讀本機 DIR 下的兩個 js
// 輸出：app/src/main/assets/explore_templates.json（App 內建備援）
// 上傳到 Firestore：python scripts/upload_explore_templates.py

import { readFileSync, writeFileSync } from 'node:fs';
import { createRequire } from 'node:module';
import { fileURLToPath } from 'node:url';
import path from 'node:path';
import vm from 'node:vm';

const WEB_BASE = 'https://travel-link-ai.duckdns.org';
const HERE = path.dirname(fileURLToPath(import.meta.url));
const OUT = path.join(HERE, '..', 'app', 'src', 'main', 'assets', 'explore_templates.json');
// 網頁首頁只放這 6 張（ai-travel-explore-final.js HOME_PICKS）
const HOME_PICKS = ['台東', '綠島', '蘭嶼', '卑南', '成功', '東河'];

async function load(name) {
  const i = process.argv.indexOf('--local');
  if (i > 0) return readFileSync(path.join(process.argv[i + 1], name), 'utf8');
  const res = await fetch(`${WEB_BASE}/${name}`);
  if (!res.ok) throw new Error(`${name} 下載失敗：HTTP ${res.status}`);
  return res.text();
}

const [tplSrc, poiSrc] = await Promise.all([load('explore-templates.js'), load('poi-data.js')]);

// explore-templates.js：UMD，在有 module 的環境會掛到 module.exports
const require = createRequire(import.meta.url);
const tplModule = { exports: {} };
vm.runInNewContext(tplSrc, { module: tplModule, exports: tplModule.exports, require, globalThis: {} });
const T = tplModule.exports;

// poi-data.js：`window.WAI_POI_DATA = {...}`
const sandbox = { window: {} };
vm.runInNewContext(poiSrc, sandbox);
const poi = sandbox.window.WAI_POI_DATA;
if (!T.buildTemplate || !poi) throw new Error('網頁檔案格式改變了，找不到 buildTemplate 或 WAI_POI_DATA');

// 與網頁 initOfficialTemplates() 相同
const buckets = T.bucketByDistrict(poi);
const templates = T.GROUPS
  .reduce((acc, g) => acc.concat(g.keys), [])
  .map((k) => T.buildTemplate(k, buckets))
  .filter(Boolean)
  .map((t) => ({
    ...t,
    km: Math.round(t.km * 10) / 10,
    rating: t.rating == null ? null : Math.round(t.rating * 100) / 100,
    // 去掉演算法內部欄位（_detour/_outbound），其餘原樣保留
    stops: t.stops.map(({ _detour, _outbound, ...s }) => s)
  }));

const out = {
  version: 1,
  generatedAt: new Date().toISOString(),
  poiGeneratedAt: poi.__generatedAt || '',
  source: WEB_BASE,
  gate: T.GATE,
  groups: T.GROUPS,
  homePicks: HOME_PICKS,
  // 各鄉鎮可規劃景點數（網頁地區分頁上的數字）
  districtCounts: Object.fromEntries(Object.entries(buckets).map(([k, v]) => [k, v.length])),
  templates
};
writeFileSync(OUT, JSON.stringify(out, null, 1) + '\n', 'utf8');
console.log(`✅ ${templates.length} 份範本 → ${path.relative(process.cwd(), OUT)}（景點資料 ${out.poiGeneratedAt}）`);
