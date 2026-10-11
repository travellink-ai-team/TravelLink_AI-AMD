// 從網頁端匯出「臺東縣府公有／民營路外停車場」清單，讓 App 與網頁用同一份停車場資料。
//
// 網頁的 parking-data.js 是它自己的爬蟲（crawl:parking＋export:local）整理縣府資料產生的，
// 約 30 筆、含車位數；TDX 對臺東只登錄了 4 座，App 過去只用 TDX，8 站只找得到 2～3 站。
// 這裡直接執行網頁的檔案取出資料，不在 App 端另外爬——網頁資料更新後重跑一次即可。
//
// 用法：
//   node scripts/export_parking_data.mjs              # 從線上網頁抓最新檔案
//   node scripts/export_parking_data.mjs --local DIR  # 改讀本機 DIR/parking-data.js
// 輸出：app/src/main/assets/parking_taitung.json

import { readFileSync, writeFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import path from 'node:path';
import vm from 'node:vm';

const WEB_BASE = 'https://travel-link-ai.duckdns.org';
const HERE = path.dirname(fileURLToPath(import.meta.url));
const OUT = path.join(HERE, '..', 'app', 'src', 'main', 'assets', 'parking_taitung.json');

async function load(name) {
  const i = process.argv.indexOf('--local');
  if (i > 0) return readFileSync(path.join(process.argv[i + 1], name), 'utf8');
  const res = await fetch(`${WEB_BASE}/${name}`);
  if (!res.ok) throw new Error(`${name} 下載失敗：HTTP ${res.status}`);
  return res.text();
}

// parking-data.js：`window.WAI_PARKING_DATA = { taitungCounty: [...] }`
const sandbox = { window: {} };
vm.runInNewContext(await load('parking-data.js'), sandbox);
const data = sandbox.window.WAI_PARKING_DATA;
if (!data || !Array.isArray(data.taitungCounty)) throw new Error('網頁檔案格式改變了，找不到 WAI_PARKING_DATA.taitungCounty');

const num = (v) => (Number.isFinite(Number(v)) ? Number(v) : 0);
const lots = data.taitungCounty
  .filter((p) => p && p.name && Number.isFinite(Number(p.lat)) && Number.isFinite(Number(p.lng)))
  .map((p) => ({
    name: p.name,
    lat: Number(p.lat),
    lng: Number(p.lng),
    address: p.address || '',
    smallSpots: num(p.smallSpots),
    motoSpots: num(p.motoSpots),
    largeSpots: num(p.largeSpots),
    source: p.source || 'taitung_gov_parking',
    // 社群回報點（網頁端 ≥3 位回報者才算正式）；目前資料裡沒有，先照原樣帶著
    reports: num(p.reports)
  }));

const out = {
  version: 1,
  generatedAt: new Date().toISOString(),
  webGeneratedAt: data.__generatedAt || '',
  source: WEB_BASE,
  lots
};
writeFileSync(OUT, JSON.stringify(out, null, 1) + '\n', 'utf8');
console.log(`✅ ${lots.length} 座停車場 → ${path.relative(process.cwd(), OUT)}（網頁資料 ${out.webGeneratedAt}）`);
