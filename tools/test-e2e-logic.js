/*
 * E2E 修正的純邏輯測試（不需 Firebase／Maps／瀏覽器／登入）。
 *
 * 為什麼要有這支：先前修 E2E issue 時只跑 `node --check`（僅驗語法），
 * 行為錯誤全靠事後審查才發現。這支把「可以在 Node 跑」的那部分邏輯抽出來實測：
 *   1. 營業時間／公休判斷（含多日第 N 天）——用 app/restaurant-data.js 真實資料
 *   2. 生成末端的公休過濾會不會造成「空白天」與「時間空檔」
 *   3. 本機行程帳號鍵與訪客搬移（含換帳號、無 Firebase 情境）
 *
 * 用法：node tools/test-e2e-logic.js
 * 退出碼非 0 代表有測試失敗。
 */
'use strict';

const fs = require('fs');
const path = require('path');
const vm = require('vm');

const APP = path.join(__dirname, '..', 'app');
const SRC = fs.readFileSync(path.join(APP, 'ai-travel-explore-final.js'), 'utf8');

// ── 從原始檔抽出具名函式（大括號配對，忽略字串/註解中的括號）──────────────
function extractFunction(src, name) {
  const re = new RegExp('(?:^|\\n)\\s*(?:async\\s+)?function\\s+' + name + '\\s*\\(', 'g');
  const m = re.exec(src);
  if (!m) throw new Error('找不到函式：' + name);
  const start = src.indexOf('function', m.index);
  let i = src.indexOf('{', start);
  if (i < 0) throw new Error('函式無主體：' + name);
  let depth = 0, inStr = null, inLine = false, inBlock = false, prev = '';
  for (; i < src.length; i++) {
    const c = src[i];
    if (inLine) { if (c === '\n') inLine = false; prev = c; continue; }
    if (inBlock) { if (prev === '*' && c === '/') inBlock = false; prev = c; continue; }
    if (inStr) {
      if (c === '\\') { i++; prev = ''; continue; }
      if (c === inStr) inStr = null;
      prev = c; continue;
    }
    if (c === '/' && src[i + 1] === '/') { inLine = true; i++; prev = ''; continue; }
    if (c === '/' && src[i + 1] === '*') { inBlock = true; i++; prev = ''; continue; }
    if (c === '"' || c === "'" || c === '`') { inStr = c; prev = c; continue; }
    if (c === '{') depth++;
    else if (c === '}') { depth--; if (depth === 0) return src.slice(start, i + 1); }
    prev = c;
  }
  throw new Error('大括號未配對：' + name);
}

// ── 沙箱：提供最小 stub，讓抽出來的函式能跑 ────────────────────────────
function makeSandbox(extra) {
  const store = Object.create(null);
  const localStorage = {
    getItem: (k) => (k in store ? store[k] : null),
    setItem: (k, v) => { store[k] = String(v); },
    removeItem: (k) => { delete store[k]; },
    _dump: () => JSON.parse(JSON.stringify(store)),
    _reset: () => { for (const k of Object.keys(store)) delete store[k]; }
  };
  const ctx = Object.assign({
    localStorage,
    currentUser: null,
    console,
    JSON, Math, Number, String, Boolean, Array, Object, Date, RegExp, Set, Map, isNaN, parseInt, parseFloat,
    serializeTripForStorage: (t) => t,   // 測試用：不改形狀
    // 真實依賴：解析器必須是 app 內同一支，否則測到的是 fallback 而不是實際路徑
    WAI_HOURS: require(path.join(__dirname, '..', 'app', 'business-hours.js')),
    escapeHtml: (s) => String(s == null ? '' : s)
      .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
      .replace(/"/g, '&quot;').replace(/'/g, '&#39;')
  }, extra || {});
  vm.createContext(ctx);
  return ctx;
}

// 這些函式依賴的模組級常數，必須一併注入沙箱，否則呼叫時 ReferenceError
const CONSTS = ['FOOD_EMOJI_SET', 'FOOD_NAME_RE', 'LODGING_NAME_RE'];
const FNS = ['parseBusinessHoursWindow', 'stopServiceDate', 'extractDayHoursWindow', 'isFoodStop', 'isLodgingStop',
  'myTripsStorageKey', 'migrateGuestTripsToUser'];
const ctx = makeSandbox();
CONSTS.forEach((n) => {
  const m = new RegExp('^const\\s+' + n + '\\s*=[^;]+;', 'm').exec(SRC);
  if (!m) throw new Error('找不到常數：' + n);
  vm.runInContext(m[0], ctx);
});
FNS.forEach((n) => vm.runInContext(extractFunction(SRC, n), ctx));

// ── 迷你測試框架 ───────────────────────────────────────────────
let pass = 0, fail = 0;
const failures = [];
function check(name, cond, detail) {
  if (cond) { pass++; console.log('  ✓ ' + name); }
  else { fail++; failures.push(name + (detail ? ' → ' + detail : '')); console.log('  ✗ ' + name + (detail ? ' → ' + detail : '')); }
}
function section(t) { console.log('\n── ' + t + ' ─────────────────────────'); }

// ── 真實餐廳資料 ──────────────────────────────────────────────
function loadRestaurants() {
  const c = { window: {} };
  vm.createContext(c);
  vm.runInContext(fs.readFileSync(path.join(APP, 'restaurant-data.js'), 'utf8'), c);
  const d = c.window.WAI_RESTAURANT_DATA || {};
  let all = [];
  Object.keys(d).filter((k) => Array.isArray(d[k])).forEach((k) => { all = all.concat(d[k]); });
  return all;
}
const RESTAURANTS = loadRestaurants();
const dow = (iso) => ['日', '一', '二', '三', '四', '五', '六'][new Date(iso + 'T00:00:00').getDay()];
// 以「本地時間」格式化，不能用 toISOString（UTC+8 會倒退一天，造成假不一致）
const isoLocal = (d) => d.getFullYear() + '-' + String(d.getMonth() + 1).padStart(2, '0') + '-' + String(d.getDate()).padStart(2, '0');

// ══════════════════════════════════════════════════════════════
section('1. 公休判斷（真實 restaurant-data.js）');

const withHours = RESTAURANTS.filter((r) => r.businessHours);
check('真實資料皆為 7 段', withHours.every((r) => r.businessHours.split(/\r?\n|；|;/).map(s => s.trim()).filter(Boolean).length === 7),
  '段數不一致');

// 針對每一筆有公休的餐廳，逐日檢查「函式判定」與「資料字面」是否一致
let mismatch = 0, checkedDays = 0;
const base = '2026-08-31'; // 星期一
withHours.forEach((r) => {
  const lines = r.businessHours.split('\n');
  for (let i = 0; i < 7; i++) {
    const d = new Date(base + 'T00:00:00'); d.setDate(d.getDate() + i);
    const iso = isoLocal(d);
    const win = ctx.extractDayHoursWindow(r.businessHours, iso);
    const lineSaysClosed = /休息|closed|不營業/i.test(lines[i]);
    const fnSaysClosed = !!(win && win.closed);
    checkedDays++;
    if (lineSaysClosed !== fnSaysClosed) { mismatch++; if (mismatch <= 3) console.log('    mismatch:', r.name, iso, 'line=', lines[i], 'fn=', JSON.stringify(win)); }
  }
});
check('逐日公休判定與資料字面一致（' + checkedDays + ' 天次）', mismatch === 0, mismatch + ' 筆不一致');

// 報告點名的個案
const onlySea = RESTAURANTS.find((r) => /只有海/.test(r.name || ''));
check('找得到報告點名的「只有海」', !!onlySea);
if (onlySea) {
  const testDate = '2026-09-01';
  const w = ctx.extractDayHoursWindow(onlySea.businessHours, testDate);
  console.log('    2026-09-01 是星期' + dow(testDate) + '；資料公休日=星期三');
  check('2026-09-01（星期' + dow(testDate) + '）判定為「未公休」符合資料', !(w && w.closed),
    '函式判定 closed=' + !!(w && w.closed));
  const wed = ctx.extractDayHoursWindow(onlySea.businessHours, '2026-09-02');
  check('2026-09-02（星期' + dow('2026-09-02') + '）判定為公休', !!(wed && wed.closed));
}

// ══════════════════════════════════════════════════════════════
section('2. stopServiceDate：多日第 N 天');
check('第 1 天 = 出發日', ctx.stopServiceDate({ dayIndex: 1 }, { departureDate: '2026-09-01' }) === '2026-09-01');
check('第 2 天 = 出發日+1', ctx.stopServiceDate({ dayIndex: 2 }, { departureDate: '2026-09-01' }) === '2026-09-02');
check('第 3 天 = 出發日+2', ctx.stopServiceDate({ dayIndex: 3 }, { departureDate: '2026-09-01' }) === '2026-09-03');
check('跨月 2026-08-31 第 2 天 = 2026-09-01', ctx.stopServiceDate({ dayIndex: 2 }, { departureDate: '2026-08-31' }) === '2026-09-01');
check('缺 dayIndex → 退回第 1 天', ctx.stopServiceDate({}, { departureDate: '2026-09-01' }) === '2026-09-01');
check('缺 departureDate → 空字串', ctx.stopServiceDate({ dayIndex: 2 }, {}) === '');

// ══════════════════════════════════════════════════════════════
section('3. 生成末端公休過濾：空白天 / 時間空檔 / 刪過頭');

// 注意：這是 app 末端公休處理的鏡像。它與 app 本尊分歧時測試會失去意義，
// 因此下方 section 6 有一致性守門（比對 app 原始碼仍不刪站、也不另存旗標）。
// app 末端只「辨識並記 log」，公休狀態不落地成欄位——planner 用 businessHours 即時重算。
function closedStopNames(finalStops, wizardData) {
  return finalStops.filter((s) => {
    if (!s || s.type === 'start' || s.type === 'end' || !s.businessHours) return false;
    if (ctx.isLodgingStop(s)) return false;
    const win = ctx.extractDayHoursWindow(s.businessHours, ctx.stopServiceDate(s, wizardData));
    return !!(win && win.closed);
  }).map((s) => s.name);
}
const CLOSED_WED = '星期一: 09:00 – 17:00\n星期二: 09:00 – 17:00\n星期三: 休息\n星期四: 09:00 – 17:00\n星期五: 09:00 – 17:00\n星期六: 09:00 – 17:00\n星期日: 09:00 – 17:00';

// 情境：2 天行程，第 2 天（2026-09-02 星期三）只有一個景點，且該景點週三公休
const twoDay = [
  { type: 'start', name: '出發', dayIndex: 1, time: '09:00' },
  { name: 'A景點', dayIndex: 1, time: '09:30', businessHours: CLOSED_WED },
  { name: 'B景點', dayIndex: 2, time: '10:00', businessHours: CLOSED_WED },
  { type: 'end', name: '返回', dayIndex: 2, time: '17:00' }
];
const wd = { departureDate: '2026-09-01' };
const twoDayClosed = closedStopNames(twoDay, wd);
const midAfter = twoDay.filter((s) => s.type !== 'start' && s.type !== 'end');
const day2After = midAfter.filter((s) => s.dayIndex === 2);
check('第 2 天不會變成空白天（不刪站，站數不變）', day2After.length > 0,
  '第 2 天剩 ' + day2After.length + ' 站');
check('當天公休的站有被辨識出來', twoDayClosed.includes('B景點'));
check('非公休日的站不列入', !twoDayClosed.includes('A景點'));
check('公休檢查不會在站點上留下欄位', twoDay.every((s) => !('scheduleWarning' in s) && !('closedOnDate' in s)),
  '公休狀態不應落地成欄位；planner 以 businessHours 即時重算');

// 時間軸不被破壞（不刪站就不會有空檔）
const gapCase = [
  { type: 'start', name: '出發', dayIndex: 1, time: '09:00' },
  { name: '公休站', dayIndex: 1, time: '10:00', businessHours: CLOSED_WED },
  { name: '後面站', dayIndex: 1, time: '13:00' },
  { type: 'end', name: '返回', dayIndex: 1, time: '17:00' }
];
const gapClosed = closedStopNames(gapCase, { departureDate: '2026-09-02' }); // 星期三
check('站數不變、時間軸完整', gapCase.length === 4 && gapCase.find((s) => s.name === '後面站').time === '13:00');
check('公休站仍留在行程裡（不刪站）', !!gapCase.find((s) => s.name === '公休站') && gapClosed.includes('公休站'));

// 保護條件（不列入公休檢查）
check('start/end 不列入', closedStopNames([{ type: 'start', businessHours: CLOSED_WED }], { departureDate: '2026-09-02' }).length === 0);
check('住宿站不列入', closedStopNames([{ name: '綠島海景民宿', businessHours: CLOSED_WED }], { departureDate: '2026-09-02' }).length === 0);
// 非「週一起 7 行」但 ≥5 段 → 以固定索引取行會對錯日子（雙向皆錯）
// 這筆只有「星期日」公休；2026-08-31 是星期一（應營業）、2026-09-06 是星期日（應公休）
const SUNDAY_FIRST = '星期日: 休息\n星期一: 09:00 – 17:00\n星期二: 09:00 – 17:00\n星期三: 09:00 – 17:00\n星期四: 09:00 – 17:00\n星期五: 09:00 – 17:00\n星期六: 09:00 – 17:00';
const monWin = ctx.extractDayHoursWindow(SUNDAY_FIRST, '2026-08-31'); // 星期一 → 應營業
check('週日起格式：星期一不得誤判為公休', !(monWin && monWin.closed),
  '取到 lines[0]=星期日:休息 → 星期一被誤判公休（會誤刪正常店）');
const sunWin = ctx.extractDayHoursWindow(SUNDAY_FIRST, '2026-09-06'); // 星期日 → 應公休
check('週日起格式：星期日應判為公休', !!(sunWin && sunWin.closed),
  '取到 lines[6]=星期六 → 真正公休日沒被抓到（漏掉公休店）');

// ══════════════════════════════════════════════════════════════
section('4. 本機行程帳號鍵與訪客搬移');
const L = ctx.localStorage;

L._reset(); ctx.currentUser = null;
check('未登入 → 訪客鍵', ctx.myTripsStorageKey() === 'wai_mytrips');
ctx.currentUser = { email: 'A@Example.com' };
check('登入 → 依 email 小寫隔離', ctx.myTripsStorageKey() === 'wai_mytrips:a@example.com');

// 換帳號不得看到前一帳號資料
L._reset();
L.setItem('wai_mytrips:a@example.com', JSON.stringify([{ id: 't-A' }]));
L.setItem('wai_mytrips:b@example.com', JSON.stringify([{ id: 't-B' }]));
ctx.currentUser = { email: 'b@example.com' };
const bSees = JSON.parse(L.getItem(ctx.myTripsStorageKey()) || '[]');
check('B 只看到自己的行程', bSees.length === 1 && bSees[0].id === 't-B');

// 訪客搬移：自己的搬走、別人的留下
L._reset();
L.setItem('wai_mytrips', JSON.stringify([
  { id: 'g1' },                                   // 無 owner → 視為自己的
  { id: 'g2', ownerEmail: 'b@example.com' },      // 自己的
  { id: 'g3', ownerEmail: 'other@example.com' }   // 別人的 → 不搬
]));
ctx.currentUser = { email: 'b@example.com' };
ctx.migrateGuestTripsToUser();
const mine = JSON.parse(L.getItem('wai_mytrips:b@example.com') || '[]').map((t) => t.id).sort();
const guestLeft = JSON.parse(L.getItem('wai_mytrips') || '[]').map((t) => t.id);
check('自己的訪客行程已搬入帳號鍵', JSON.stringify(mine) === JSON.stringify(['g1', 'g2']), JSON.stringify(mine));
check('別人的行程留在訪客鍵', JSON.stringify(guestLeft) === JSON.stringify(['g3']), JSON.stringify(guestLeft));

// 冪等：重複觸發（onAuthStateChanged 可能多次）
ctx.migrateGuestTripsToUser();
const mine2 = JSON.parse(L.getItem('wai_mytrips:b@example.com') || '[]').map((t) => t.id).sort();
check('重複搬移為冪等', JSON.stringify(mine2) === JSON.stringify(['g1', 'g2']), JSON.stringify(mine2));

// 未登入時不得搬移
L._reset();
L.setItem('wai_mytrips', JSON.stringify([{ id: 'g9' }]));
ctx.currentUser = null;
ctx.migrateGuestTripsToUser();
check('未登入不搬移', JSON.parse(L.getItem('wai_mytrips')).length === 1);

// 無 Firebase 情境：不會有 auth callback，loadState 會改用快取身分搬移（否則訪客行程等於消失）
L._reset();
L.setItem('wai_mytrips', JSON.stringify([{ id: 'guest-only' }]));
ctx.currentUser = { email: 'c@example.com' };
ctx.migrateGuestTripsToUser();   // 模擬 loadState 在 firebase 不可用時的呼叫
const cSees = JSON.parse(L.getItem(ctx.myTripsStorageKey()) || '[]');
check('無 Firebase 時訪客行程仍看得到', cSees.length === 1 && cSees[0].id === 'guest-only',
  JSON.stringify(cSees));

// ══════════════════════════════════════════════════════════════
section('5. 兩端同口徑：planner 的公休顯示 vs explore 的公休判定');
// planner 用 formatDayBusinessHours 顯示「🔴 當天公休」，explore 用 extractDayHoursWindow 判定。
// 同一家店、同一天，兩邊結論必須一致，否則畫面說公休、資料卻沒標（或反過來）。
const PSRC = fs.readFileSync(path.join(APP, 'ai-travel-planner-v8.js'), 'utf8');
const pctx = makeSandbox();
vm.runInContext(extractFunction(PSRC, 'formatDayBusinessHours'), pctx);

let crossMismatch = 0, crossChecked = 0;
withHours.forEach((r) => {
  for (let i = 0; i < 7; i++) {
    const d = new Date(base + 'T00:00:00'); d.setDate(d.getDate() + i);
    const iso = isoLocal(d);
    const win = ctx.extractDayHoursWindow(r.businessHours, iso);
    const shown = pctx.formatDayBusinessHours(r.businessHours, iso);
    const exploreClosed = !!(win && win.closed);
    const plannerClosed = shown.startsWith('🔴');
    crossChecked++;
    if (exploreClosed !== plannerClosed) {
      crossMismatch++;
      if (crossMismatch <= 3) console.log('    mismatch:', r.name, iso, 'explore=', exploreClosed, 'planner=', JSON.stringify(shown));
    }
  }
});
check('兩端公休結論一致（' + crossChecked + ' 天次）', crossMismatch === 0, crossMismatch + ' 筆不一致');

// 週日起格式：兩端都必須答對
check('週日起格式：planner 星期一不顯示公休', !pctx.formatDayBusinessHours(SUNDAY_FIRST, '2026-08-31').startsWith('🔴'));
check('週日起格式：planner 星期日顯示公休', pctx.formatDayBusinessHours(SUNDAY_FIRST, '2026-09-06').startsWith('🔴'));

// ══════════════════════════════════════════════════════════════
section('6. 共用解析器 business-hours.js（全站唯一來源）');
const H = require(path.join(APP, 'business-hours.js'));

// 區間標籤
const RANGE_CLOSED = '星期一至星期五: 休息\n星期六: 09:00 – 17:00\n星期日: 09:00 – 17:00';
check('區間標籤：星期二（區間內）判為公休', H.parseDayStatus(RANGE_CLOSED, '2026-09-01').status === 'closed',
  H.parseDayStatus(RANGE_CLOSED, '2026-09-01').status);
check('區間標籤：星期六（區間外）判為營業', H.parseDayStatus(RANGE_CLOSED, '2026-09-05').status === 'open');
check('區間標籤：週一~週五 寫法也支援',
  H.parseDayStatus('週一~週五: 休息\n週六: 09:00 – 17:00\n週日: 休息', '2026-09-02').status === 'closed');
check('跨週區間：週五至週一 涵蓋星期日',
  H.parseDayStatus('星期五至星期一: 休息', '2026-09-06').status === 'closed');

// 英文與縮寫
check('英文全名', H.parseDayStatus('Monday: Closed\nTuesday: 09:00 – 17:00', '2026-08-31').status === 'closed');
check('英文縮寫 Mon/Tue', H.parseDayStatus('Mon: Closed\nTue: 09:00 – 17:00', '2026-08-31').status === 'closed');
check('英文縮寫不誤判他日', H.parseDayStatus('Mon: Closed\nTue: 09:00 – 17:00', '2026-09-01').status === 'open');

// 有標籤但缺當天 → unknown（不臆測）
check('有標籤但缺當天 → unknown', H.parseDayStatus('星期日: 休息\n星期一: 09:00 – 17:00', '2026-09-02').status === 'unknown');
// 混合單行（句中提及星期）→ unknown，不可誤判成該星期專屬
check('混合單行（句中提及星期）→ unknown', H.parseDayStatus('每日 09:00-18:00，週三休息', '2026-09-02').status === 'unknown');
check('自由描述 → unknown', H.parseDayStatus('開放時間依部落為主', '2026-09-02').status === 'unknown');
check('「未知」→ unknown', H.parseDayStatus('未知', '2026-09-02').status === 'unknown');
check('全天候開放 → open', H.parseDayStatus('全天候開放', '2026-09-02').status === 'open');
check('全天開放 → open', H.parseDayStatus('全天開放', '2026-09-02').status === 'open');
// 無標籤但剛好 7 段 → 允許週一起推測
check('無標籤 7 段 → 週一起推測', H.parseDayStatus('休息\n09:00-17:00\n09:00-17:00\n09:00-17:00\n09:00-17:00\n09:00-17:00\n09:00-17:00', '2026-08-31').status === 'closed');
check('缺日期 → unknown', H.parseDayStatus('星期一: 休息', '').status === 'unknown');

// 真實資料仍全對
let sharedMismatch = 0;
withHours.forEach((r) => {
  const lines = r.businessHours.split('\n');
  for (let i = 0; i < 7; i++) {
    const d = new Date(base + 'T00:00:00'); d.setDate(d.getDate() + i);
    const st = H.parseDayStatus(r.businessHours, isoLocal(d));
    const lineClosed = /休息|closed|不營業/i.test(lines[i]);
    if (lineClosed !== (st.status === 'closed')) sharedMismatch++;
  }
});
check('共用解析器對真實資料 637 天次全對', sharedMismatch === 0, sharedMismatch + ' 筆不一致');

// 24 小時營業 / 單行無標籤：語意明確，不可判 unknown（景點資料大量使用這兩種寫法）
check('24 小時營業 → open', H.parseDayStatus('星期一: 24 小時營業\n星期二: 24 小時營業\n星期三: 24 小時營業\n星期四: 24 小時營業\n星期五: 24 小時營業\n星期六: 24 小時營業\n星期日: 24 小時營業', '2026-09-02').status === 'open');
check('單行時間（無星期標籤）→ 每天適用 open', H.parseDayStatus('10:00–22:00', '2026-09-02').status === 'open');
check('單行時間在任一天皆 open', H.parseDayStatus('10:00–22:00', '2026-09-06').status === 'open');
check('單行「休息」仍判 closed', H.parseDayStatus('休息', '2026-09-02').status === 'closed');
check('Open 24 hours → open', H.parseDayStatus('Open 24 hours', '2026-09-02').status === 'open');

// 真實景點資料（poi-data.js）：不可有大量 unknown，否則畫面會退化成 ℹ️ 概覽
(function () {
  const c = { window: {} };
  vm.createContext(c);
  vm.runInContext(fs.readFileSync(path.join(APP, 'poi-data.js'), 'utf8'), c);
  const d = c.window.WAI_POI_DATA || {};
  let pois = [];
  Object.keys(d).filter((k) => Array.isArray(d[k])).forEach((k) => { pois = pois.concat(d[k]); });
  const wh = pois.filter((p) => p && p.businessHours);
  let unknown = 0, total = 0;
  const unknownPatterns = new Map();
  wh.forEach((p) => {
    for (let i = 0; i < 7; i++) {
      const dt = new Date(base + 'T00:00:00'); dt.setDate(dt.getDate() + i);
      total++;
      if (H.parseDayStatus(p.businessHours, isoLocal(dt)).status === 'unknown') {
        unknown++;
        const key = String(p.businessHours).replace(/\s+/g, ' ').trim();
        unknownPatterns.set(key, (unknownPatterns.get(key) || 0) + 1);
      }
    }
  });
  // 原本用「unknown 比例 < 5%」當門檻，但那個數字會隨資料量浮動——匯入台東觀光網
  // 的 265 筆之後就變成 8.9%，測試變紅卻不代表解析器漏掉任何樣態（那 210 筆全是
  // 「開放時間依部落為主」這類自由描述）。比例本身不是我們真正關心的東西。
  //
  // 改成看「unknown 的寫法是不是都屬於已知無法解析的類型」：
  // 只要出現沒見過的樣態就變紅，那才是「有可解析的格式被漏掉」的訊號。
  const KNOWN_UNPARSEABLE = [
    /依部落為主/,            // 開放時間／實際收費及營運時間依部落為主
    /預約/,                  // 事先電話預約、需線上預約
    /暫停開放/,              // 目前暫停開放，另行公告
    /無對外開放/,            // 僅供外部參觀
    /^未知$/,
    /末班機/                 // 每日 7:00~末班機起飛（沒有固定時刻）
  ];
  const novel = [...unknownPatterns.keys()].filter((k) => !KNOWN_UNPARSEABLE.some((re) => re.test(k)));
  check('unknown 的營業時間寫法都屬於已知無法解析的類型（' + unknown + '/' + total
      + '，' + unknownPatterns.size + ' 種寫法）',
    novel.length === 0,
    novel.length ? ('出現沒見過的樣態，可能是解析器漏掉：' + novel.slice(0, 3).join(' ／ ')) : '');
})();

// 標籤必須錨定段首：說明文字不可被當成日別標籤（Codex 四輪：段首規則原為死碼）
check('「非星期三休息日」不得判為公休', H.parseDayStatus('非星期三休息日', '2026-09-02').status === 'unknown',
  H.parseDayStatus('非星期三休息日', '2026-09-02').status);
check('「星期三 09:00-17:00」（無冒號）仍正確', H.parseDayStatus('星期三 09:00-17:00', '2026-09-02').status === 'open');
check('前導全形空白仍正確', H.parseDayStatus('　星期三：休息', '2026-09-02').status === 'closed');

// 時間值必須合法（原本 25:99 會被當成正常營業時間）
check('不合法時間 25:99 → 不視為 open', H.parseDayStatus('Monday: 25:99-26:99', '2026-08-31').status !== 'open',
  H.parseDayStatus('Monday: 25:99-26:99', '2026-08-31').status);
check('英文 to 分隔符可解析', H.parseDayStatus('Monday: 09:00 to 17:00', '2026-08-31').status === 'open');

// 跨午夜：共用模組把收店正規化成隔日分鐘數
(function () {
  const st = H.parseDayStatus('星期一: 18:00 – 01:30', '2026-08-31');
  check('跨午夜營業 → open 且 close > open', st.status === 'open' && st.close > st.open,
    JSON.stringify(st));
})();

// 單段文字的危險寫法：若誤當成「每天適用」，整句狀態會套到所有日子。
// 實測「週末公休」曾讓星期一也被判公休 → 這類一律 unknown。
check('「週末公休」星期一不得判公休', H.parseDayStatus('週末公休', '2026-09-07').status === 'unknown',
  H.parseDayStatus('週末公休', '2026-09-07').status);
check('「例假日休息」星期一不得判公休', H.parseDayStatus('例假日休息', '2026-09-07').status === 'unknown');
check('「假日不營業」星期一不得判公休', H.parseDayStatus('假日不營業', '2026-09-07').status === 'unknown');
check('「非全天開放」不得判全天營業', H.parseDayStatus('非全天開放', '2026-09-02').status === 'unknown',
  H.parseDayStatus('非全天開放', '2026-09-02').status);
check('「僅夏季全天開放」不得判全天營業', H.parseDayStatus('僅夏季全天開放', '2026-09-02').status === 'unknown');
// 收緊後不可誤傷正常寫法
check('「全天開放」仍為 open', H.parseDayStatus('全天開放', '2026-09-02').status === 'open');
check('「24 小時營業」仍為 open', H.parseDayStatus('24 小時營業', '2026-09-02').status === 'open');
check('單段「休息」仍為 closed', H.parseDayStatus('休息', '2026-09-02').status === 'closed');

// 收店 24:00（＝午夜）是合法寫法，不可被時間驗證擋掉；開店 24:00 則不合法
check('收店 24:00 → open', H.parseDayStatus('星期三: 09:00-24:00', '2026-09-02').status === 'open',
  H.parseDayStatus('星期三: 09:00-24:00', '2026-09-02').status);
check('開店 24:00 → 不視為 open', H.parseDayStatus('星期三: 24:00-25:00', '2026-09-02').status !== 'open');

// Places 的 weekdayText 是「週一起算」，getDay() 是「週日起算」——直接當索引會差一天。
// 這裡用「只有星期日公休」的資料驗證：星期日必須是公休，星期一必須營業。
(function () {
  const weekdayText = ['星期一: 09:00 – 17:00', '星期二: 09:00 – 17:00', '星期三: 09:00 – 17:00',
    '星期四: 09:00 – 17:00', '星期五: 09:00 – 17:00', '星期六: 09:00 – 17:00', '星期日: 休息'];
  const joined = weekdayText.join('\n');
  check('weekdayText：星期日判公休（不可差一天）', H.parseDayStatus(joined, '2026-09-06').status === 'closed');
  check('weekdayText：星期一判營業（不可差一天）', H.parseDayStatus(joined, '2026-09-07').status === 'open');
  // 守門：todayHours 不得再用 getDay() 當索引
  const PS = fs.readFileSync(path.join(APP, 'ai-travel-planner-v8.js'), 'utf8');
  check('todayHours 不再用 getDay() 當 weekdayText 索引',
    !/weekdayText\[new Date\(\)\.getDay\(\)\]/.test(PS));
})();

// 三處呼叫端都已委派給共用解析器（防止有人再寫第四份）
const ESRC = SRC, PSRC2 = fs.readFileSync(path.join(APP, 'ai-travel-planner-v8.js'), 'utf8');
check('explore extractDayHoursWindow 委派共用解析器', /extractDayHoursWindow[\s\S]{0,600}?WAI_HOURS/.test(ESRC));
check('planner formatDayBusinessHours 委派共用解析器', /formatDayBusinessHours[\s\S]{0,600}?WAI_HOURS/.test(PSRC2));
check('planner getBusinessHoursWarning 委派共用解析器', /getBusinessHoursWarning[\s\S]{0,600}?WAI_HOURS/.test(PSRC2));
check('已無殘留的 lines[apiIndex] 固定索引解析', !/const apiIndex[\s\S]{0,200}?lines\[apiIndex\]/.test(PSRC2));

// 末端處理必須「不刪站」，且公休狀態不得再落地成第二份欄位
check('app 末端不刪站', !/dropClosed/.test(ESRC));
// 只擋「賦值」不擋整個字：解釋它為何被移除的註解要留著，否則下一個人會再加回來。
check('scheduleWarning／closedOnDate 不再被寫入', !/(scheduleWarning|closedOnDate)\s*[:=]/.test(ESRC),
  '公休狀態只能有一份真相：stop.businessHours + business-hours.js 即時重算');
check('planner 也沒有消費這兩個欄位', !/scheduleWarning|closedOnDate/.test(PSRC2));

// 移除旗標的前提是「顯示端本來就即時重算」。這兩處若消失，使用者就再也看不到公休提示。
check('planner 行程卡顯示當日營業狀態', /formatDayBusinessHours\(stop\.businessHours/.test(PSRC2),
  '行程卡的 🔴 當天公休來自這裡');
// 要抓「呼叫點」不是函式定義——`function getBusinessHoursWarning(stop)` 也長這樣，
// 用寬鬆寫法會在呼叫點被刪掉後仍然綠燈（突變測試實測到的假綠）。
check('planner 重排畫面顯示公休警告', /const w = getBusinessHoursWarning\(stop\);/.test(PSRC2),
  '重排畫面的 ⚠️ 當天公休來自這裡');

// ── P1 回饋修補（原始碼守門；UI 行為無法在 Node 沙箱執行）──
check('預覽第一格時間同步回 startTime', /index === 0\) wizData\.startTime = normalized/.test(ESRC),
  '否則預覽改 14:00、存檔仍是 09:00');
check('applyReplan 有成功回饋', /function applyReplan\(\)[\s\S]{0,1200}?feedbackToast\(/.test(PSRC2));
check('reorderReplanStops 對唯讀成員有說明', /collabReadOnly\) \{ feedbackToast\('訪客或唯讀成員無法調整順序'/.test(PSRC2));
check('reorderReplanStops 對錨點站有說明', /isAnchor\(replanStops\[targetIndex\]\)\) \{[\s\S]{0,200}?feedbackToast\(/.test(PSRC2));
check('moveReplanStop 不再自行靜默擋掉唯讀', !/moveReplanStop = function\(stopId, direction\) \{\s*\n\s*if \(collabReadOnly\) return;/.test(PSRC2));

// 無 Firebase 時不得清空訪客鍵（改為合併顯示）
check('無 Firebase 分支不呼叫不可逆搬移',
  !/typeof firebase === 'undefined' \|\| !firebaseEnabled\) migrateGuestTripsToUser/.test(ESRC));
check('無 Firebase 時改為合併顯示訪客行程', /合併顯示|guestTrips/.test(ESRC));

// formatDayBusinessHours 輸出需 escapeHtml（會進 innerHTML）
check('formatDayBusinessHours 輸出經 escapeHtml', /formatDayBusinessHours[\s\S]{0,800}?escapeHtml/.test(PSRC2));
check('getBusinessHoursWarning 不再二次解析時間窗',
  !/getBusinessHoursWarning[\s\S]{0,900}?parseBusinessHoursWindow\(checkLine\)/.test(PSRC2));

// 行為測試：無 Firebase 的暫時訪客行程，絕不可被 saveState 寫進帳號鍵
(function () {
  const sctx = makeSandbox();
  ['myTripsStorageKey', 'persistableMyTrips'].forEach((n) => vm.runInContext(extractFunction(ESRC, n), sctx));
  sctx.currentUser = { email: 'd@example.com' };
  sctx.myTrips = [{ id: 'own-1' }, { id: 'guest-x', __transientGuest: true }];
  const out = sctx.persistableMyTrips();
  check('persistableMyTrips 濾掉暫時訪客行程', out.length === 1 && out[0].id === 'own-1',
    JSON.stringify(out.map((t) => t.id)));
  check('三處寫入皆改用 persistableMyTrips',
    (ESRC.match(/localStorage\.setItem\(myTripsStorageKey\(\), JSON\.stringify\(persistableMyTrips\(\)\)\)/g) || []).length === 3);
  check('已無直接寫入 myTrips 全量的路徑',
    !/setItem\(myTripsStorageKey\(\), JSON\.stringify\(myTrips\.map/.test(ESRC));
})();

// ══════════════════════════════════════════════════════════════
section('7. 官方精選範本（explore-templates.js × 真實 poi-data）');
(() => {
  const T = require(path.join(APP, 'explore-templates.js'));
  const c = { window: {} };
  vm.createContext(c);
  vm.runInContext(fs.readFileSync(path.join(APP, 'poi-data.js'), 'utf8'), c);
  const buckets = T.bucketByDistrict(c.window.WAI_POI_DATA);
  const allKeys = T.GROUPS.reduce((a, g) => a.concat(g.keys), []);

  check('16 個鄉鎮全部分得到桶', allKeys.every((k) => (buckets[k] || []).length),
    allKeys.filter((k) => !(buckets[k] || []).length).join('、') + ' 沒有景點');

  // 交通節點與住宿不該進範本：poi-data 混了車站（同一站三種寫法）與民宿
  const flat = allKeys.reduce((a, k) => a.concat(buckets[k] || []), []);
  check('桶裡沒有車站／機場／碼頭', !flat.some((p2) => T.isTransit(p2.name)),
    (flat.find((p2) => T.isTransit(p2.name)) || {}).name);
  check('桶裡沒有住宿', !flat.some((p2) => T.isLodging(p2.name)),
    (flat.find((p2) => T.isLodging(p2.name)) || {}).name);

  const tpls = allKeys.map((k) => T.buildTemplate(k, buckets));
  check('16 個鄉鎮全部組得出範本', tpls.every(Boolean),
    allKeys.filter((k, i) => !tpls[i]).join('、') + ' 組不出來');

  // ★ 走廊規則：沿途候選只能來自同走廊或市區。
  //   台東的幹道從台東市呈 V 字分岔，只看直線繞路會把縱谷景點判成去海線的順路點。
  const crossCorridor = [];
  tpls.filter(Boolean).forEach((t) => {
    t.stops.filter((s2) => s2.via).forEach((s2) => {
      const g = T.groupOf(s2.fromDistrict);
      if (g !== t.group && g !== '市區') crossCorridor.push(t.key + '←' + s2.name + '(' + s2.fromDistrict + ')');
    });
  });
  check('沿途景點不跨走廊', crossCorridor.length === 0, crossCorridor.slice(0, 3).join('、'));

  // 同一份行程不該出現重複站名
  const dupIn = tpls.filter(Boolean).filter((t) => {
    const names = t.stops.map((s2) => s2.name);
    return new Set(names).size !== names.length;
  }).map((t) => t.key);
  check('同一份範本沒有重複站名', dupIn.length === 0, dupIn.join('、'));

  // 評分只平均「有評分」的站：把 rating=0 算進去會變成 ★1.1 這種假象
  const badRating = tpls.filter(Boolean).filter((t) => t.rating !== null && (t.rating < 3 || t.rating > 5));
  check('範本平均評分落在合理範圍（3–5 或 null）', badRating.length === 0,
    badRating.map((t) => t.key + ':' + t.rating).join('、'));

  // ★ SVG 漸層 id 必須唯一。原本把非 ASCII 濾掉，中文鄉鎮名整個被清空、
  //   每張卡的 id 都變成 'wt-'，於是全部指向第一個漸層（實測六張卡全變橘色）。
  const ids = tpls.filter(Boolean).map((t) => {
    const m = T.routeSvg(t, t.key).match(/<linearGradient id="([^"]+)"/);
    return m ? m[1] : null;
  });
  check('每個範本的漸層 id 唯一', new Set(ids).size === ids.length && !ids.includes(null),
    '重複或缺漏：' + ids.length + ' 個 id 只有 ' + new Set(ids).size + ' 種');

  // 離島沒有陸路往返，不該有沿途站
  const islandVia = tpls.filter(Boolean).filter((t) => t.island && t.viaCount > 0).map((t) => t.key);
  check('離島範本沒有沿途站', islandVia.length === 0, islandVia.join('、'));

  // 產生的 HTML 片段不得含未跳脫的角括號（站名來自資料，需經 escapeHtml）
  const svgAll = tpls.filter(Boolean).map((t) => T.routeSvg(t, t.key)).join('');
  check('routeSvg 只輸出數字與固定色碼', !/[\u4e00-\u9fff]/.test(svgAll),
    '路線 SVG 不應包含中文（站名要由呼叫端 escape 後輸出）');
})();

// ══════════════════════════════════════════════════════════════
section('8. 探索首頁：假社群資料已移除');
(() => {
  const H = fs.readFileSync(path.join(APP, 'ai-travel-explore-final.html'), 'utf8');
  check('沒有寫死的社群行程', !/COMMUNITY_TRIPS\s*=\s*\[\s*\{/.test(ESRC),
    '假行程資料應已由 explore-templates 取代');
  // 只抓「物件屬性」不抓註解文字：解釋為何移除的註解要留著，
  // 否則下一個人不知道那些假作者是刻意拿掉的（跟 scheduleWarning 同一個教訓）。
  check('沒有假作者', !/authorTrips\s*:|ava\s*:\s*'|author\s*:\s*'/.test(ESRC));
  check('沒有按讚／評分狀態', !/likedTrips|ratedTrips|copiedTrips/.test(ESRC));
  check('分頁不再寫死日本／韓國／歐洲', !/filterCat\(this,'(日本|韓國|歐洲|台北)'\)/.test(H));
  // 同理：抓「渲染出來的值」而不是註解裡提到的舊數字
  check('hero 數據不再寫死', !/hstat-num[^>]*>\s*(2,481|18,340|4\.8)\s*</.test(H));
  check('範本模組有被載入', /explore-templates\.js/.test(H));
  // 時序：初始化必須等 defer 腳本（poi-data.js）執行完
  check('範本初始化等 defer 腳本就緒', /whenDeferredScriptsReady\(\s*\(\)\s*=>\s*\{[\s\S]{0,200}?initOfficialTemplates\(\)/.test(ESRC),
    '直接在 IIFE 裡初始化會拿到還沒載入的 WAI_POI_DATA（實測 0 個範本）');
})();

// ══════════════════════════════════════════════════════════════
section('9. poi-data.js 資料規格');
(() => {
  const c = { window: {} };
  vm.createContext(c);
  vm.runInContext(fs.readFileSync(path.join(APP, 'poi-data.js'), 'utf8'), c);
  const d = c.window.WAI_POI_DATA || {};
  const all = Object.keys(d).filter((k) => Array.isArray(d[k]))
    .reduce((a, k) => a.concat(d[k]), []);

  check('有景點資料', all.length > 300, all.length + ' 筆');

  // 必填欄位：缺任何一個，前端就得到處寫 fallback
  const REQUIRED = ['name', 'lat', 'lng', 'duration', 'kind', 'district'];
  REQUIRED.forEach((f) => {
    const miss = all.filter((x) => x[f] === undefined || x[f] === null || x[f] === '');
    check('必填欄位 ' + f + ' 無缺漏', miss.length === 0,
      miss.length + ' 筆缺，例：' + (miss[0] || {}).name);
  });

  // ★「有欄位」就等於「有值」。原本 desc/address/businessHours 一律寫入，
  //   於是覆蓋率看起來 100% 但其中 104/86/44 筆是空字串——報表會騙人。
  const withEmpty = all.filter((x) => Object.keys(x).some((k) => x[k] === '' || x[k] === null));
  check('沒有空字串／null 欄位', withEmpty.length === 0,
    withEmpty.length + ' 筆，例：' + (withEmpty[0] || {}).name);

  // 型別一致
  const types = {};
  all.forEach((x) => Object.keys(x).forEach((k) => {
    const t = Array.isArray(x[k]) ? 'array' : typeof x[k];
    (types[k] = types[k] || new Set()).add(t);
  }));
  const mixed = Object.keys(types).filter((k) => types[k].size > 1);
  check('每個欄位型別一致', mixed.length === 0,
    mixed.map((k) => k + ':' + [...types[k]].join('/')).join('、'));

  // kind 只能是這四種
  const KINDS = ['scenic', 'food', 'transit', 'lodging'];
  const badKind = all.filter((x) => KINDS.indexOf(x.kind) < 0);
  check('kind 只用約定的四種值', badKind.length === 0,
    [...new Set(badKind.map((x) => x.kind))].join('、'));

  // 值域
  const badLat = all.filter((x) => !(x.lat > 21.5 && x.lat < 23.6));
  const badLng = all.filter((x) => !(x.lng > 120.5 && x.lng < 122.1));
  check('座標落在台東縣範圍內', badLat.length === 0 && badLng.length === 0,
    (badLat[0] || badLng[0] || {}).name);
  const badRating = all.filter((x) => x.rating !== undefined && !(x.rating > 0 && x.rating <= 5));
  check('rating 落在 0–5', badRating.length === 0, (badRating[0] || {}).name);
  const badDur = all.filter((x) => !(x.duration > 0 && x.duration <= 180));
  check('duration 落在 1–180 分', badDur.length === 0,
    badDur.map((x) => x.name + ':' + x.duration).slice(0, 2).join('、'));

  // 推定的行政區必須標記出來，不能跟地址判定的混為一談
  const inferred = all.filter((x) => x.districtInferred);
  check('districtInferred 只出現在有 district 的項目上',
    inferred.every((x) => !!x.district), '有標記卻沒有 district');
  check('推定比例合理（< 40%）', inferred.length / all.length < 0.4,
    (inferred.length / all.length * 100).toFixed(0) + '% 是推定的');

  // 名稱不得只是地區名
  const bare = all.filter((x) => /^(台東|臺東|綠島|蘭嶼)$/.test(String(x.name).trim()));
  check('名稱不是純地區名', bare.length === 0, bare.length + ' 筆');

  // 交通節點與餐廳必須被分類出來（不是留在 scenic 裡）
  const transitInScenic = all.filter((x) => x.kind === 'scenic' && /車站|機場|碼頭/.test(x.name));
  check('車站／機場／碼頭不在 scenic', transitInScenic.length === 0,
    transitInScenic.map((x) => x.name).slice(0, 3).join('、'));
})();

// ══════════════════════════════════════════════════════════════
section('10. 前端依 kind 取用');
(() => {
  const P = fs.readFileSync(path.join(APP, 'ai-travel-planner-v8.js'), 'utf8');
  const T = fs.readFileSync(path.join(APP, 'explore-templates.js'), 'utf8');
  const re = /poi\.kind && poi\.kind !== 'scenic'/;
  check('explore getLocalPoiList 過濾非景點', re.test(ESRC));
  check('planner getLocalPoiList 過濾非景點', re.test(P));
  check('範本引擎改用 kind', /p\.kind \|\| \(isTransit/.test(T));

  // 實測：把交通節點餵進範本引擎，不該出現在結果裡
  const TM = require(path.join(APP, 'explore-templates.js'));
  const c = { window: {} };
  vm.createContext(c);
  vm.runInContext(fs.readFileSync(path.join(APP, 'poi-data.js'), 'utf8'), c);
  const buckets = TM.bucketByDistrict(c.window.WAI_POI_DATA);
  const flat = Object.keys(buckets).reduce((a, k) => a.concat(buckets[k]), []);
  check('範本桶內全是 scenic', flat.every((x) => !x.kind || x.kind === 'scenic'),
    (flat.find((x) => x.kind && x.kind !== 'scenic') || {}).name);
})();

// ══════════════════════════════════════════════════════════════
section('11. 入場條件 classifyAccess（暫停開放／僅外部參觀／需預約）');
(() => {
  const A = (o) => H.classifyAccess(o);

  // 基本分類
  check('暫停開放 → suspended 且 avoid',
    A({ businessHours: '目前暫停開放。開放時間將另行公告' }).level === 'suspended'
    && A({ businessHours: '目前暫停開放。' }).avoid === true);
  check('僅供外部參觀 → exterior_only 但不 avoid',
    A({ businessHours: '僅供外部參觀，無對外開放' }).level === 'exterior_only'
    && A({ businessHours: '僅供外部參觀，無對外開放' }).avoid === false);
  check('需預約 → reservation 但不 avoid',
    A({ businessHours: '需線上預約並由環境解說員帶領進入' }).level === 'reservation'
    && A({ businessHours: '需線上預約' }).avoid === false);
  check('一般營業時間 → open',
    A({ businessHours: '星期一: 09:00 – 17:00' }).level === 'open');
  check('沒有任何資訊 → open', A({}).level === 'open');

  // 判定順序：suspended > exterior_only > reservation
  check('同時命中時以 suspended 優先',
    A({ businessHours: '暫停開放', feeNote: '僅供外部參觀，需預約' }).level === 'suspended');
  check('exterior_only 優先於 reservation',
    A({ businessHours: '僅供外部參觀，無對外開放', feeNote: '請提前預約' }).level === 'exterior_only');

  // ★ 陷阱一：絕對不能讀 desc
  check('desc 裡的「不對外開放」不得影響判定',
    A({ businessHours: '星期一: 24 小時營業', desc: '燈塔園區不對外開放，但沿途風景仍是一大看點' }).level === 'open',
    '蘭嶼燈塔是 24 小時開放的熱門景點，掃 desc 會把它誤判成禁止進入');
  check('desc 裡的「預約」不得影響判定',
    A({ businessHours: '星期一: 09:00 – 17:00', desc: '可預約導覽' }).level === 'open');

  // ★ 陷阱二：feeNote 一定要讀
  check('feeNote 的「預約制」要被讀到',
    A({ feeNote: '採完全預約制，請務必事先預約' }).level === 'reservation',
    '訊號有一半在 feeNote，先前沒有任何程式碼在看這個欄位');

  // ★ 陷阱三：「暫不開放預約」是預約不開放，不是園區暫停
  check('「暫不開放預約」不得判成 suspended',
    A({ feeNote: '導覽解說10人成團，且採預約制 週一~周五 08:30-16:00(國定假日暫不開放預約)' }).level === 'reservation',
    '台東糖廠會被整個排除在行程之外');

  // 真實資料
  const c = { window: {} };
  vm.createContext(c);
  vm.runInContext(fs.readFileSync(path.join(APP, 'poi-data.js'), 'utf8'), c);
  const all = Object.keys(c.window.WAI_POI_DATA || {}).filter((k) => Array.isArray(c.window.WAI_POI_DATA[k]))
    .reduce((a, k) => a.concat(c.window.WAI_POI_DATA[k]), []);
  const byLevel = {};
  all.forEach((x) => { const l = A(x).level; byLevel[l] = (byLevel[l] || 0) + 1; });
  check('真實資料：avoid 的比例極低（< 2%）', (byLevel.suspended || 0) / all.length < 0.02,
    JSON.stringify(byLevel));

  const named = (n) => all.find((x) => String(x.name).includes(n));
  const lighthouse = named('蘭嶼燈塔');
  check('真實資料：蘭嶼燈塔未被誤擋', !lighthouse || A(lighthouse).level === 'open',
    lighthouse ? A(lighthouse).level : '(資料中沒有)');
  const sugar = named('台東糖廠');
  check('真實資料：台東糖廠判為 reservation 而非 suspended',
    !sugar || A(sugar).level === 'reservation', sugar ? A(sugar).level : '(資料中沒有)');

  // 僅可外部參觀 → 停留時間要短
  const ext = all.filter((x) => A(x).level === 'exterior_only');
  check('exterior_only 的停留時長 ≤ 20 分', ext.every((x) => x.duration <= 20),
    ext.map((x) => x.name + ':' + x.duration).join('、'));

  // 消費端接線
  const P = fs.readFileSync(path.join(APP, 'ai-travel-planner-v8.js'), 'utf8');
  const filterRe = /WAI_HOURS\.classifyAccess\(poi\)\.avoid\) continue/;
  check('explore getLocalPoiList 濾掉 avoid', filterRe.test(ESRC));
  check('planner getLocalPoiList 濾掉 avoid', filterRe.test(P));
  check('explore 生成末端以「替換」處理暫停開放',
    /\[暫停開放\][\s\S]{0,200}?替換/.test(ESRC) && /finalStops\[i\] = Object\.assign/.test(ESRC),
    '末端刪站會造成空白天與時間空檔——必須是替換');
  check('planner 行程卡顯示入場提醒', /formatAccessNote\(stop\)/.test(P));
  check('入場提醒經 escapeHtml', /escapeHtml\(a\.label\)/.test(P));
})();

// ══════════════════════════════════════════════════════════════
section('12. 範本卡：景點清單摺疊');
(() => {
  const C = fs.readFileSync(path.join(APP, 'ai-travel-explore-final.css'), 'utf8');
  check('站點清單預設收合', /class="tpl-stops" id=[\s\S]{0,80}?isOpen \? '' : ' hidden'/.test(ESRC),
    '預設應為收合，一張卡 5 站會把按鈕推到很下面');
  check('摺疊鈕有 aria-expanded', /aria-expanded="' \+ \(isOpen \? 'true' : 'false'\)/.test(ESRC));
  check('摺疊鈕有 aria-controls 指向清單', /aria-controls="' \+ stopsId/.test(ESRC));
  check('箭頭用 inline SVG 而非字元', /<svg class="tpl-chev"/.test(ESRC),
    '▾ 字元在 13px 下看不出方向，且各平台字型差異大');
  check('收合時箭頭轉 90 度', /\.tpl-chev\{[^}]*rotate\(90deg\)/.test(C));
  check('展開時箭頭回正', /\.tpl-toggle\[aria-expanded="true"\] \.tpl-chev\{[^}]*rotate\(0deg\)/.test(C));
  // ★ 重繪會清掉展開狀態——renderGrid 因 auth 狀態變化重跑時整個 innerHTML 重建
  check('展開狀態記在模組層級，重繪後保留',
    /const expandedTplKeys = new Set\(\)/.test(ESRC)
    && /const isOpen = expandedTplKeys\.has\(t\.key\)/.test(ESRC),
    'renderGrid 重跑會把剛展開的卡收回去');
  check('切換時同步更新狀態集合',
    /expandedTplKeys\.add\(key\)[\s\S]{0,40}?expandedTplKeys\.delete\(key\)/.test(ESRC));
  // Grid 預設 stretch 會讓同列卡片等高：展開一張，旁邊沒展開的會被拉長，
  // 底部多出一塊空白，使用者看起來像「同一列的也跟著展開了」。
  check('卡片格線不拉伸（align-items:start）', /\.trips-grid\{[\s\S]{0,300}?align-items:\s*start/.test(C),
    '否則展開一張卡會把同列其他卡一起拉長');
})();

// ══════════════════════════════════════════════════════════════
section('13. 展示 Sandbox 與桌機單站抽屜回歸');
(() => {
  const P = fs.readFileSync(path.join(APP, 'ai-travel-planner-v8.js'), 'utf8');
  const H = fs.readFileSync(path.join(APP, 'ai-travel-planner-v8.html'), 'utf8');
  const persist = extractFunction(P, 'persistCurrentTripStops');
  const schedulePersist = extractFunction(P, 'schedulePersistTrip');
  const syncVehicle = extractFunction(P, 'syncTripPrimaryVehicleSelect');
  const setVehicle = extractFunction(P, 'setTripPrimaryVehicle');
  const routeAlternative = extractFunction(P, 'applyRouteAlternative');
  const openStop = extractFunction(P, 'openItineraryStop');

  check('正式存檔出口會拒絕展示模擬',
    /if\s*\(tripSimulation\.enabled\)\s*return/.test(persist));
  check('展示模擬不會排入延遲存檔',
    /if\s*\(tripSimulation\.enabled\)\s*return/.test(schedulePersist));
  check('展示／進行中會隱藏並停用主要交通工具',
    /currentTripStatus\s*===\s*'ongoing'\s*\|\|\s*tripSimulation\.enabled/.test(syncVehicle)
    && /sel\.disabled\s*=\s*locked/.test(syncVehicle));
  check('主要交通工具 setter 有展示守衛',
    /tripSimulation\.enabled\)\s*return/.test(setVehicle));
  check('替代路線有展示守衛',
    /if\s*\(tripSimulation\.enabled\)/.test(routeAlternative));

  const mainClose = H.indexOf('</main>');
  const drawer = H.indexOf('id="stopEditorBackdrop"');
  check('單站抽屜已移到 main 外、body 根層', mainClose >= 0 && drawer > mainClose,
    '抽屜若仍在 .left-panel，z-index 會被 stacking context 鎖住');
  check('桌機開抽屜前會關閉重複的地圖浮窗',
    /useDesktopEditor[\s\S]{0,120}?closePinInfo\(\)[\s\S]{0,80}?openStopEditor/.test(openStop));
})();

// ══════════════════════════════════════════════════════════════
section('14. 首次使用流程的 13 項修正');
(() => {
  const P = fs.readFileSync(path.join(APP, 'ai-travel-planner-v8.js'), 'utf8');
  const PH = fs.readFileSync(path.join(APP, 'ai-travel-planner-v8.html'), 'utf8');
  const EH = fs.readFileSync(path.join(APP, 'ai-travel-explore-final.html'), 'utf8');
  const EC = fs.readFileSync(path.join(APP, 'ai-travel-explore-final.css'), 'utf8');
  const W  = fs.readFileSync(path.join(APP, 'wheel-picker.js'), 'utf8');

  // 1 API 成本面板預設不給一般使用者看
  check('API 用量統計改為明確開啟制',
    /function isApiCostPanelVisible/.test(P) && /if \(!isApiCostPanelVisible\(\)\)/.test(P),
    '預算頁原本直接印出 NT$49.35 與 places:searchNearby|… 給終端使用者');

  // 2 停車：Directions 沒回應要退回推估；找不到也不能謊稱附近沒有
  check('步行驗證失敗時退回直線推估', /walkEstimated: true/.test(P) && /function estimateWalkSeconds/.test(P));
  // 鎖在真正的賦值那一行；只寫 /outInfo\.nearestFar/ 會被上面的判空式一起命中，拿掉賦值也不會紅。
  check('記錄「最近但超過門檻」的停車場',
    /outInfo\.nearestFar = \{ name: cand\.name, walkSeconds: sec \}/.test(P));
  // ⚠ 不能直接搜「找不到鄰近停車場」——檔案裡還有 4 處註解在解釋這個 bug，
  //   那樣會永遠紅。只比對真正會渲染出去的那兩句舊文案。
  check('不再宣稱「找不到鄰近停車場」',
    !/找不到鄰近停車場，請預留路邊或付費停車的時間/.test(P)
    && !/找不到鄰近停車場，請自行尋找路邊或付費停車/.test(P),
    '593 公尺外就有停車場時說找不到，是在講假話');
  check('停車訊息用 index+1 對應 replanStops',
    /_nearestFarParkingByStopIndex\[index \+ 1\]/.test(P),
    'nextStop 是 schedule 項目，用 indexOf 會永遠拿到 -1');

  // 3 + 10 範本要一路帶進精靈
  check('範本改存成 pendingTemplateSeed', /pendingTemplateSeed = \{/.test(ESRC));
  check('選完模式後併入初始值', /buildInitialWizData\(mode, templateSeedExtra\(seed\)\)/.test(ESRC),
    'selectTripMode 會重設 wizData，先寫進去的會被沖掉');
  check('模式對話框顯示已選範本', /renderModeChoiceSeedNote/.test(ESRC));

  // 4 日期/時間選擇器的鍵盤與語意
  check('滾輪欄位是 listbox 且可聚焦',
    /col\.setAttribute\('role', 'listbox'\)/.test(W) && /col\.setAttribute\('tabindex', '0'\)/.test(W));
  check('選擇器有 dialog 語意', /card\.setAttribute\('aria-modal', 'true'\)/.test(W));
  check('方向鍵可操作滾輪', /e\.key === 'ArrowDown'/.test(W) && /e\.key === 'ArrowUp'/.test(W));
  check('觸發欄位補上 role/tabindex', /function decorateFields/.test(W) && /startFieldWatcher/.test(W));
  check('關閉後焦點送回觸發欄位', /returnFocus\.focus\(\)/.test(W));

  // 5 停留時間不得被壓成打卡
  check('停留下限提高到有意義的長度', /MEANINGFUL_MIN = 20/.test(ESRC) && !/const HARD_MIN = 5;/.test(ESRC),
    '原本會把 40 分的景點壓到 12 分');
  check('擠不下時改為拿掉一站', /stops\.splice\(drop, 1\)/.test(ESRC));

  // 6 + 13 精靈步驟與必填
  check('步驟 chip 變成可操作的 button', /goToWizStep\(/.test(ESRC) && /aria-current="step"/.test(ESRC));
  check('出發日期有必填標示與 inline 錯誤',
    /wizard-required/.test(ESRC) && /wizDepartureDateErr/.test(ESRC) && /wizard-field-err/.test(EC));

  // 7 除錯 chip 不給一般使用者
  check('原型 id chip 改為明確開啟制', /wai_show_debug_chip/.test(P) && !/const isPrototypeMode = true;/.test(P));

  // 8 使用者選單語意
  check('explore 使用者選單有 menu 語意', /enhanceUserMenuA11y/.test(ESRC) && /'menuitem'/.test(ESRC));
  check('planner 使用者選單有 menu 語意', /enhanceUserMenuA11y/.test(P) && /'menuitem'/.test(P));
  check('使用者名稱進 innerHTML 前有 escape', /escapeHtml\(u\.name \|\| ''\)/.test(ESRC));

  // 9 標籤說明
  check('範本評分有說明', /\u7ad9\u6709 Google \u8a55\u5206/.test(ESRC));
  check('主軸／沿途有說明', /kindHint/.test(ESRC));

  // 11 intro.html 站內入口
  check('首頁有 intro.html 入口', /href="intro\.html"/.test(EH));

  // 12 路線最佳化要含回程
  // 同理：lockLastStop 在參數與註解都出現，要鎖在真正改變迴圈上界的那一行。
  check('2-opt 可鎖定終點',
    /const segmentEnd = lockLastStop \? bestRoute\.length - 1 : bestRoute\.length;/.test(ESRC));
  check('重排時帶入終點錨點', /const endAnchor = \{/.test(ESRC) && /reorderStopsAlongRoute\(filledStops, _startCoords, wizardData, _endCoords\)/.test(ESRC));

  // 快取
  check('改動檔案都 bump 過 ?v=',
    /wheel-picker\.js\?v=/.test(EH) && /wheel-picker\.js\?v=/.test(PH));
})();

// ══════════════════════════════════════════════════════════════
section('15. 相簿上傳者標籤：撞名消歧');
(() => {
  const G = require(path.join(APP, 'trip-photo-gallery.js'));
  const U = G.utils;
  const fake = [
    { id: 'p1', ownerUid: 'uidAAAA1111', ownerName: '小明', url: 'x', capturedAt: 1 },
    { id: 'p2', ownerUid: 'uidBBBB2222', ownerName: '小明', url: 'x', capturedAt: 2 },
    { id: 'p3', ownerUid: 'uidCCCC3333', ownerName: '阿華', url: 'x', capturedAt: 3 }
  ];
  const opts = U.memberOptions(fake);
  const byUid = Object.fromEntries(opts.map((m) => [m.uid, m.name]));

  check('沒撞名的維持原本的名字', byUid.uidCCCC3333 === '阿華');
  check('撞名的兩個帳號標籤不同', byUid.uidAAAA1111 !== byUid.uidBBBB2222,
    '身分是 uid，但畫面只給人看名字——兩個「小明」分不出誰是誰');
  check('撞名時以 uid 尾碼消歧',
    byUid.uidAAAA1111 === '小明 #1111' && byUid.uidBBBB2222 === '小明 #2222');
  check('每個 uid 只出現一次', opts.length === 3);

  // 換成 email 前綴一樣會撞，所以不能只是換欄位
  const sameLocalPart = [
    { id: 'q1', ownerUid: 'u1', ownerName: 'tim', url: 'x', capturedAt: 1 },
    { id: 'q2', ownerUid: 'u2', ownerName: 'tim', url: 'x', capturedAt: 2 }
  ];
  const two = U.memberOptions(sameLocalPart);
  check('email 前綴相同時也會被消歧', two[0].name !== two[1].name);

  const P = fs.readFileSync(path.join(APP, 'ai-travel-planner-v8.js'), 'utf8');
  check('顯示名稱優先用 app 內暱稱', /function currentPhotoDisplayName/.test(P)
    && /stored\.currentUser && stored\.currentUser\.name/.test(P));
})();

// ══════════════════════════════════════════════════════════════
section('16. EXIF 時區偏移與 capturedTimezone（與 Android 對齊）');
(() => {
  const M = fs.readFileSync(path.join(APP, 'trip-photo-manager.js'), 'utf8');
  const ctx = { Number: Number, String: String, Date: Date, Math: Math };
  vm.createContext(ctx);
  vm.runInContext(extractFunction(M, 'exifOffsetToMinutes'), ctx);
  vm.runInContext(extractFunction(M, 'exifDateToEpoch'), ctx);
  const off = ctx.exifOffsetToMinutes;
  const toEpoch = ctx.exifDateToEpoch;

  check('+08:00 → 480 分', off('+08:00') === 480);
  check('-05:00 → -300 分', off('-05:00') === -300);
  check('沒有冒號也要認得（+0800）', off('+0800') === 480);
  check('空字串／亂碼 → null', off('') === null && off('Asia/Taipei') === null);

  const wall = '2026:09:28 09:32:00';
  // 有偏移時是絕對時間：台北 09:32 = UTC 01:32
  check('有偏移時以 UTC 換算', toEpoch(wall, 480) === Date.UTC(2026, 8, 28, 1, 32, 0));
  // 沒有偏移時退回「裝置當地時區」的解讀
  check('沒有偏移時用裝置當地時區',
    toEpoch(wall, null) === new Date(2026, 8, 28, 9, 32, 0).getTime());
  // 同一個牆上時間，不同偏移必須相差正確的時數
  check('+08:00 與 -05:00 相差 13 小時',
    toEpoch(wall, -300) - toEpoch(wall, 480) === 13 * 3600 * 1000,
    '這正是「兩端時區解讀不一致」會造成的偏移量');
  check('無法解析的日期回 null', toEpoch('not a date', 480) === null);

  // 偏移標籤必須與日期標籤配對，拿錯配對比沒有更糟
  check('DateTimeOriginal 配 0x9011',
    /readAsciiTag\(exif, 0x9003\)[\s\S]{0,160}?readAsciiTag\(exif, 0x9011\)/.test(M));
  check('DateTimeDigitized 配 0x9012',
    /readAsciiTag\(exif, 0x9004\)[\s\S]{0,160}?readAsciiTag\(exif, 0x9012\)/.test(M));
  check('IFD0 DateTime 沒有偏移可用', /rawDate = readAsciiTag\(ifd0, 0x0132\); rawOffset = ''/.test(M));

  check('有偏移時標記 exif-offset',
    /timeAssumption = Number\.isFinite\(parsed\.offsetMinutes\) \? 'exif-offset' : 'device-local'/.test(M));

  // capturedTimezone：用 IANA 名稱，不是 ±08:00
  check('capturedTimezone 取 IANA 名稱',
    /function deviceTimeZone/.test(M)
    && /Intl\.DateTimeFormat\(\)\.resolvedOptions\(\)\.timeZone/.test(M));
  check('建立照片紀錄時寫入 capturedTimezone',
    /capturedTimezone: deviceTimeZone\(\)/.test(M));

  // 讀取遠端照片不得重新分類（Android 端明確要求）
  check('ingestRemote 不重新分類',
    !/classifyPhoto/.test(extractFunction(M, 'ingestRemote')),
    '兩端都只讀 stopId 欄位，重算會讓同一張照片在兩端落到不同站');
})();

// ══════════════════════════════════════════════════════════════
section('17. 照片刪除政策：方案 B（owner/editor 可協助管理）');
(() => {
  const G = require(path.join(APP, 'trip-photo-gallery.js'));
  const canUI = G.utils.defaultCan;
  const mine = { id: 'p1', ownerUid: 'me', url: 'x' };
  const theirs = { id: 'p2', ownerUid: 'other', url: 'x' };
  const ro = { id: 'p3', ownerUid: 'other', url: 'x', readOnly: true };
  const as = (role) => ({ uid: 'me', role: role });

  // 三個角色 × 自己／他人
  check('行程 owner 可刪他人照片', canUI('delete', theirs, as('owner')) === true);
  check('editor 可刪他人照片', canUI('delete', theirs, as('editor')) === true,
    '方案 B：owner/editor 可協助管理');
  check('一般成員不可刪他人照片', canUI('delete', theirs, as('member')) === false);
  check('一般成員可刪自己的照片', canUI('delete', mine, as('member')) === true);
  check('viewer 不可刪任何照片',
    canUI('delete', mine, as('viewer')) === false && canUI('delete', theirs, as('viewer')) === false);

  // 舊版 memories 併進來的唯讀資料仍然擋住
  check('唯讀（舊 memories）照片一律不可刪', canUI('delete', ro, as('editor')) === false,
    '那是 memories 併進來的顯示用資料，不在 photos 集合裡，刪不掉也不該給按鈕');

  // 三層政策必須一致，否則又會回到「owner 可以、editor 不行」
  const M = fs.readFileSync(path.join(APP, 'trip-photo-manager.js'), 'utf8');
  const P = fs.readFileSync(path.join(APP, 'ai-travel-planner-v8.js'), 'utf8');
  const R = fs.readFileSync(path.join(APP, '..', 'firestore.rules'), 'utf8');
  check('層級 A：TripPhotoManager.can 允許 editor 刪他人',
    /if \(action === 'delete'\) return role === 'editor' \|\| \(ownsPhoto && role === 'member'\);/.test(M));
  check('層級 B：sync 的 authorize 允許 owner\/editor remove',
    /\['overwrite', 'remove'\]\.includes\(action\) && \(role === 'owner' \|\| role === 'editor'\)/.test(P));
  // 刪除要真的刪到遠端——這是方案 B 才浮現的既有問題
  check('遠端刪除的判斷不依賴 photo.remote',
    /var hasRemoteCopy = !!\(photo\.remote \|\| photo\.storagePath \|\| photo\.url\);/.test(M)
    && /if \(hasRemoteCopy && typeof config\.removeRemote === 'function'\)/.test(M),
    'photo.remote 只有「這台裝置上傳過」的照片才有；別人的或自己他機傳的都沒有，遠端會刪不掉');

  // 別人刪掉的照片要從本機快取消失，否則會變成只有自己看得到的幽靈
  check('ingestRemote 會修剪遠端已消失的紀錄',
    /options\.tripId && options\.prune !== false/.test(M)
    && /if \(!isPrunableWhenMissing\(candidate, now\)\) continue;/.test(M)
    && /emit\('remote-removed'/.test(M),
    '哪些可清交給 isPrunableWhenMissing（第 19 節有行為測試）');

  check('層級 C：Rules 允許 editor 刪他人',
    /allow delete: if isTripMemberOrOwner\(tripId\)[\s\S]{0,160}?isEditor\(get\(/.test(R));
})();

// ══════════════════════════════════════════════════════════════
// 18. photos 的 storagePath 防護（Cloud Function 以管理員權限依此路徑刪檔）
// ══════════════════════════════════════════════════════════════
(function () {
  console.log('\n── 18. photos storagePath 防護 ──');
  const R = fs.readFileSync(path.join(APP, '..', 'firestore.rules'), 'utf8').replace(/\r\n/g, '\n');
  const P = fs.readFileSync(path.join(APP, 'ai-travel-planner-v8.js'), 'utf8');
  const photosBlock = (R.match(/match \/photos\/\{photoId\} \{[\s\S]*?\n      \}\n/) || [''])[0];
  const createRule = (photosBlock.match(/allow create:[\s\S]*?;/) || [''])[0];
  const updateRule = (photosBlock.match(/allow update:[\s\S]*?;/) || [''])[0];

  const m = createRule.match(/storagePath\.matches\('trip-photos\/' \+ request\.auth\.uid \+ '([^']+)'\)/);
  check('create 限制 storagePath 在上傳者自己的資料夾', !!m,
    '少了這條，任何成員都能填別人的檔案路徑，刪文件時 Function 就會代刪');
  check('update 禁止改 storagePath 與 url',
    /request\.resource\.data\.storagePath == resource\.data\.storagePath/.test(updateRule)
    && /request\.resource\.data\.url == resource\.data\.url/.test(updateRule),
    'editor 可以改別人照片的分類；若也能改路徑，改完再刪就能讓 Function 刪錯檔');

  // 網頁實際上傳的路徑必須通過 create 條件，否則上傳成功、寫文件卻被拒
  const tpl = P.match(/const path = `trip-photos\/\$\{uid\}\/\$\{tripId \|\| 'no-trip'\}\/\$\{name\}\.jpg`;/);
  check('網頁上傳路徑模板未變', !!tpl, '改了路徑格式要一併確認 Rules 的 matches');
  if (m) {
    const uid = 'BEXvnUBc1Mat01ep2rvLW2UHSSa2';
    const re = new RegExp('^trip-photos/' + uid + m[1] + '$');   // Rules 的 matches 是全字串比對
    check('網頁路徑通過 Rules', re.test(`trip-photos/${uid}/my_1790138720174/9f1c2a7e-photo.jpg`));
    check('別人的資料夾被擋', !re.test('trip-photos/someoneElse/my_1790138720174/a.jpg'));
    check('其他服務的檔案被擋', !re.test(`recap-videos/${uid}/my_1790138720174/recap.mp4`));
    check('多一層資料夾被擋', !re.test(`trip-photos/${uid}/a/b/c.jpg`));
  }
})();









// ══════════════════════════════════════════════════════════════
// 19. 照片被刪後：本機快取、旅記紀錄、Storage 檔案都要跟著走
// ══════════════════════════════════════════════════════════════
async function section19() {
  console.log('\n── 19. 刪除後的清理 ──');
  const M = fs.readFileSync(path.join(APP, 'trip-photo-manager.js'), 'utf8');
  const P = fs.readFileSync(path.join(APP, 'ai-travel-planner-v8.js'), 'utf8');

  // (a) 相簿快取：哪些「遠端已消失」的紀錄可以清
  const mctx = { STATUS: { SYNCED: 'synced' } };
  vm.createContext(mctx);
  vm.runInContext((M.match(/var LOCAL_PRUNE_GRACE_MS = [^;]+;/) || [''])[0], mctx);
  vm.runInContext(extractFunction(M, 'isPrunableWhenMissing'), mctx);
  const prunable = (photo, now) => vm.runInContext('isPrunableWhenMissing', mctx)(photo, now);
  const NOW = 10 * 60 * 1000;
  check('遠端下載、已同步的 → 清', prunable({ source: 'remote', status: 'synced' }, NOW) === true);
  check('自己上傳、同步完成超過一分鐘 → 清', prunable({ source: 'local', status: 'synced', syncedAt: NOW - 61000 }, NOW) === true,
    '上傳後這台沒看過任何快照、別人就把它刪了——原本永遠清不掉');
  check('自己上傳、剛同步完成 → 不清', prunable({ source: 'local', status: 'synced', syncedAt: NOW - 5000 }, NOW) === false,
    '那份快照可能是發布前抓的，還沒有這張');
  ['queued', 'uploading', 'publishing', 'failed', 'local-only'].forEach((st) => {
    check('待上傳（' + st + '）→ 不清', prunable({ source: 'local', status: st, syncedAt: 0 }, NOW) === false,
      '清掉就弄丟使用者還沒傳上去的照片');
  });

  // (b) 旅記紀錄對齊
  const pctx = {};
  vm.createContext(pctx);
  ['visitedPlaceNameKey', 'visitedPlaceMatches', 'reconcileVisitedTripPhotos'].forEach((n) => vm.runInContext(extractFunction(P, n), pctx));
  const reconcile = (...a) => vm.runInContext('reconcileVisitedTripPhotos', pctx)(...a);
  const T = 'my_trip';
  const START = 1800000000000;
  const U = (n) => 'https://firebasestorage.googleapis.com/v0/b/x/o/' + n + '?alt=media&token=t';
  const fresh = () => [{
    name: '三仙台', tripId: T, photos: [
      { url: U('mine-old'), path: 'trip-photos/me/my_trip/old.jpg', ts: 1 },                                     // 正式 photos 出現前的舊照片
      { url: U('mine-new'), path: 'trip-photos/me/my_trip/p1.jpg', photoId: 'p1', uploadedAt: START - 3600e3 },  // 自己上傳、已發布
      { url: U('mine-just'), path: 'trip-photos/me/my_trip/p2.jpg', photoId: 'p2', uploadedAt: START - 10e3 },   // 剛上傳
      { url: U('friend'), ts: 5, foreign: true, owner: '小美' }                                                   // 旅伴的
    ]
  }, { name: '別趟的景點', tripId: 'other', photos: [{ url: U('other-trip'), photoId: 'zz', uploadedAt: 1 }] }];
  const optsFor = (complete) => ({ myUid: 'me', nameByStopId: { s1: '三仙台' }, complete, startedAt: START, newRecord: (name) => ({ name, tripId: T, photos: [] }) });
  const urlsOf = (places, name) => (places.find((p) => p.name === name) || { photos: [] }).photos.map((p) => p.url);

  // editor 刪掉「自己上傳的 p1」與「旅伴那張」之後的雲端清單
  let places = fresh();
  let r = reconcile(places, T, [{ photoId: 'p2', url: U('mine-just'), storagePath: 'trip-photos/me/my_trip/p2.jpg', stopId: 's1', ownerUid: 'me' }], optsFor(true));
  let kept = urlsOf(places, '三仙台');
  check('被刪的自己照片（有 photoId）從旅記移除', !kept.includes(U('mine-new')));
  check('被刪的旅伴照片從旅記移除', !kept.includes(U('friend')));
  check('舊照片（沒 photoId、雲端無從比對）保留', kept.includes(U('mine-old')), '清掉會讓只存在本機的舊照片消失');
  check('剛上傳的照片保留', kept.includes(U('mine-just')));
  check('別趟行程不受影響', urlsOf(places, '別趟的景點').includes(U('other-trip')));
  check('回報刪除數', r.removed === 2 && r.added === 0, JSON.stringify(r));

  // 剛上傳、清單還沒有它 → 寬限期內保留
  places = fresh();
  reconcile(places, T, [{ photoId: 'p1', url: U('mine-new'), stopId: 's1', ownerUid: 'me' }, { url: U('friend'), stopName: '三仙台', ownerUid: 'f' }], optsFor(true));
  check('寬限期內的新照片不因清單較舊而被刪', urlsOf(places, '三仙台').includes(U('mine-just')));

  // 讀取不完整（舊版 memories 讀失敗）→ 只併不刪
  places = fresh();
  r = reconcile(places, T, [], optsFor(false));
  check('資料不完整時不刪任何東西', r.removed === 0 && urlsOf(places, '三仙台').length === 4);

  // 新增：旅伴在正式 photos 新傳的照片（網頁原本只讀 memories，看不到）
  places = fresh();
  r = reconcile(places, T, [
    { photoId: 'p1', url: U('mine-new'), stopId: 's1', ownerUid: 'me' },
    { photoId: 'p2', url: U('mine-just'), stopId: 's1', ownerUid: 'me' },
    { url: U('friend'), stopName: '三仙台', ownerUid: 'f' },
    { photoId: 'f9', url: U('friend-formal'), storagePath: 'trip-photos/f/my_trip/f9.jpg', stopId: 's1', ownerUid: 'f', ownerName: '小美' },
    { photoId: 'f10', url: U('new-stop'), stopName: '鹿野高台', ownerUid: 'f', ownerName: '小美' },
    { photoId: 'f11', url: U('unsorted'), stopId: '', stopName: '', ownerUid: 'f' },
    { photoId: 'm5', url: U('mine-other-device'), stopId: 's1', ownerUid: 'me' }
  ], optsFor(true));
  const sanxian = places.find((p) => p.name === '三仙台').photos;
  const added = sanxian.find((p) => p.url === U('friend-formal'));
  check('旅伴的正式照片併進旅記', !!added && added.foreign === true && added.owner === '小美');
  check('併進來的照片帶 photoId/path，之後刪除才對得到', !!added && added.photoId === 'f9' && added.path === 'trip-photos/f/my_trip/f9.jpg');
  check('沒有造訪紀錄的景點會補一筆', urlsOf(places, '鹿野高台').includes(U('new-stop')));
  check('未分類照片不進旅記', !places.some((p) => (p.photos || []).some((ph) => ph.url === U('unsorted'))));
  const otherDevice = sanxian.find((p) => p.url === U('mine-other-device'));
  check('自己他機的照片 owner 為 null', !!otherDevice && otherDevice.owner === null);
  check('已存在的不重複加', urlsOf(places, '三仙台').filter((u) => u === U('mine-new')).length === 1 && r.added === 3, JSON.stringify(r));

  // Cloud Function 沒跑到：正式文件已刪，但自己 memories 裡的網址還在（sync.list 會把它當舊版照片回傳）
  places = fresh();
  r = reconcile(places, T, [
    { photoId: 'p2', url: U('mine-just'), stopId: 's1', ownerUid: 'me', source: 'formal' },
    { photoId: 'legacy_x', url: U('mine-new'), stopId: 's1', ownerUid: 'me', source: 'legacy-memory' },
    { photoId: 'legacy_y', url: U('friend'), stopId: 's1', ownerUid: 'f', source: 'legacy-memory' }
  ], optsFor(true));
  kept = urlsOf(places, '三仙台');
  check('自己的照片以正式 photos 為準：memories 殘留的網址不算「還在」', !kept.includes(U('mine-new')),
    '實測 2026-09-27：Function 沒動作時，照片會以舊版 memories 的身分一直回來');
  check('回報要從自己 memories 清掉的網址', r.removedOwn.length === 1 && r.removedOwn[0].url === U('mine-new'), JSON.stringify(r.removedOwn));
  check('旅伴只存在 memories 的照片（App 舊資料）照樣保留', kept.includes(U('friend')));
  const mergeSrc = extractFunction(P, 'mergeTripMemoriesIntoLocal');
  check('有自己的照片被刪時清自己的 memories', /if \(result\.removedOwn\.length && uid\) await dropFromOwnMemory\(/.test(mergeSrc));
  const dropSrc = extractFunction(P, 'dropFromOwnMemory');
  check('清 memories 用 merge 寫入（不覆蓋 migratedPhotoKeys 等 App 欄位）', /await ref\.set\(update, \{ merge: true \}\)/.test(dropSrc));

  // 相簿：舊版 memories 的照片唯讀；名稱以正式照片為準
  check('相簿把舊版 memories 照片標成唯讀', /p\.source === 'legacy-memory' \? \{ \.\.\.p, readOnly: true \}/.test(P),
    '實測：B 看得到「移除」，按了只會在下一次快照回來');
  const Gm = require(path.join(APP, 'trip-photo-gallery.js'));
  const names = Object.fromEntries(Gm.utils.memberOptions([
    { id: 'l1', ownerUid: 'uA', ownerName: 'travelowner090141b1', url: 'x', capturedAt: 1, readOnly: true },
    { id: 'f1', ownerUid: 'uA', ownerName: 'Codex測試擁有者A', url: 'y', capturedAt: 2 }
  ]).map((m) => [m.uid, m.name]));
  check('同一人的名稱以正式照片為準（不被 memories 的 email 開頭蓋掉）', names.uA === 'Codex測試擁有者A', JSON.stringify(names));

  // memories 只寫備註（組員確認 2026-09-28：App 旅記每站列出全員的 note），照片走正式 photos
  const save = extractFunction(P, 'saveMyTripMemory');
  check('saveMyTripMemory 不再寫照片網址／封面', !/photos|coverUrl/.test(save.replace(/\/\/.*$/gm, '')),
    '寫回 memories 會跟 Cloud Function 清理打架，刪掉的照片又跑回來');
  check('saveMyTripMemory 用 merge 寫入（保留 App 的 photos、migratedPhotoKeys、spotAliases）',
    /\{ merge: true \}/.test(save) && !/\.set\(\{[^}]*\}\);/.test(save));
  vm.runInContext('var MEMO_FIELD_UNSAFE = /[.~*/\\[\\]]/g;', pctx);
  ['memoryStableStopId', 'buildMemoryNotePatch'].forEach((n) => vm.runInContext(extractFunction(P, n), pctx));
  const notePatch = (...a) => vm.runInContext('buildMemoryNotePatch', pctx)(...a);
  const nStops = [{ name: '三仙台', stopId: 's1' }, { name: '鹿野高台', order: 1 }, { name: '池上' }];
  const byNm = (recs) => Object.fromEntries(recs.map((r) => [vm.runInContext('visitedPlaceNameKey', pctx)(r.name), r]));
  let np = notePatch(nStops, byNm([{ name: '三仙台', note: '看日出', noteUpdatedAt: 500 }]), { s1: { note: 'App 舊的', updatedAt: 100, photos: ['x'] } }, 999);
  check('本機較新的備註蓋過雲端，只帶備註欄位', np.s1 && np.s1.note === '看日出' && np.s1.updatedAt === 500 && !('photos' in np.s1), JSON.stringify(np));
  np = notePatch(nStops, byNm([{ name: '三仙台', note: '看日出', noteUpdatedAt: 50 }]), { s1: { note: 'App 新寫的', updatedAt: 100 } }, 999);
  check('雲端（App）較新就不蓋', !np.s1, JSON.stringify(np));
  np = notePatch(nStops, byNm([{ name: '三仙台', note: '', noteUpdatedAt: 500 }]), { s1: { note: '要刪掉', updatedAt: 100 } }, 999);
  check('網頁清空備註 → 雲端也清空（旅伴那邊跟著消失）', np.s1 && np.s1.note === '', JSON.stringify(np));
  np = notePatch(nStops, byNm([{ name: '鹿野高台', note: '舊備註' }, { name: '池上', note: '便當' }]), { web_1_鹿野高台: { note: 'App 寫的', updatedAt: 1 } }, 999);
  check('舊資料沒有 noteUpdatedAt：只補雲端空的，不蓋 App 的', !np['web_1_鹿野高台'] && np['web_2_池上'] && np['web_2_池上'].note === '便當', JSON.stringify(np));
  check('key 與 App stableStopId 同算法，並帶 spotName 供 reconcileSpotKeys 接回', np['web_2_池上'].stopId === 'web_2_池上' && np['web_2_池上'].spotName === '池上');
  check('沒有備註的站不寫', Object.keys(notePatch(nStops, byNm([{ name: '三仙台', note: '' }]), {}, 999)).length === 0);
  check('儲存備註時記下 noteUpdatedAt', /p\.note = val; p\.noteUpdatedAt = Date\.now\(\);/.test(P));
  check('備註視窗說明旅伴看得到', /同行旅伴也看得到/.test(fs.readFileSync(path.join(APP, 'ai-travel-planner-v8.html'), 'utf8'))
    && !/只有你看得到/.test(fs.readFileSync(path.join(APP, 'ai-travel-planner-v8.html'), 'utf8')));
  const merge = extractFunction(P, 'mergeTripMemoriesIntoLocal');
  check('有刪除時連雲端備份一起存', /if \(result\.removed\) saveVisitedPlaces\(places\)/.test(merge),
    '只寫 localStorage 的話，下次登入會從 users/{uid}.visitedSpots 還原回來');

  // (c) Storage：只刪自己的檔案
  const Sync = require(path.join(APP, 'trip-photo-sync.js'));
  const run = async (actorUid, ownerUid) => {
    const deleted = { doc: 0, file: [] };
    const doc = {
      get: async () => ({ exists: true, data: () => ({ photoId: 'x1', tripId: T, ownerUid, url: 'https://a/b', storagePath: 'trip-photos/' + ownerUid + '/' + T + '/x1.jpg', stopId: 's1', stopName: '三仙台' }) }),
      delete: async () => { deleted.doc += 1; }
    };
    const db = { collection: () => ({ doc: () => ({ collection: () => ({ doc: () => doc }) }) }) };
    const storage = { ref: (p) => ({ delete: async () => { deleted.file.push(p); } }) };
    Sync.configure({ db, storage, auth: { currentUser: { uid: actorUid } }, authorize: () => true, logger: () => {} });
    await Sync.remove(T, 'x1', { actor: { uid: actorUid, name: 'n' } });
    return deleted;
  };
  let d = await run('me', 'me');
  check('刪自己的照片：文件＋檔案都刪', d.doc === 1 && d.file.length === 1, JSON.stringify(d));
  d = await run('editor', 'me');
  check('editor 刪別人的照片：只刪文件，檔案交給 Cloud Function', d.doc === 1 && d.file.length === 0,
    'storage.rules 只准本人刪檔，照刪只會得到 403 紅字');
}

// ══════════════════════════════════════════════════════════════
// 20. 行程中調整「預計離開時間」：後續順延、原訂不變、衝突只報新出現的
// ══════════════════════════════════════════════════════════════
function section20() {
  console.log('\n── 20. 預計離開時間 ──');
  const P = fs.readFileSync(path.join(APP, 'ai-travel-planner-v8.js'), 'utf8');
  const c = {
    Math, Number, String, Array, Object, Set, Date,
    currentTripStatus: 'ongoing', currentTripPreferences: {}, replanStops: [],
    ensureStopDayIndexes: () => {}, getReplanStartMinutes: () => 540, isMultiDayTrip: () => false,
    getMultiDayWindow: () => ({ days: [] }), clampDayIndex: (v, f) => Math.max(1, Math.round(Number(v)) || f),
    normalizeTransitMode: (m) => m || 'car', getEffectiveStopTransitMode: (s) => s.transitMode || 'car',
    normalizeTransitMinutesValue: (v) => (Number.isFinite(v) ? v : null), getDefaultTransitMinutes: () => 15
  };
  vm.createContext(c);
  ['buildReplanSchedule', 'clockToScheduleMinutes', 'assessLeaveImpact'].forEach((n) => vm.runInContext(extractFunction(P, n), c));
  const build = () => vm.runInContext('buildReplanSchedule()', c);
  const mk = () => [
    { id: 'a', type: 'start', name: '出發', stayMin: 0, transitMin: 15 },
    { id: 'b', name: '三仙台', stayMin: 60, transitMin: 15 },
    { id: 'c', name: '都蘭', stayMin: 45, transitMin: 15 },
    { id: 'd', name: '市區', stayMin: 30, transitMin: 15 },
    { id: 'e', type: 'end', name: '返回', stayMin: 0 }
  ];
  c.replanStops = mk();
  const base = build();
  const startOf = (sch, id) => sch.find((r) => r.id === id).start;
  const endOf = (sch, id) => sch.find((r) => r.id === id).end;

  // 正在三仙台（b），想多待 30 分
  c.replanStops[1].expectedLeaveMin = endOf(base, 'b') + 30;
  let sch = build();
  check('本站結束改成預計離開時間', endOf(sch, 'b') === endOf(base, 'b') + 30);
  check('後面每一站依序順延 30 分', ['c', 'd', 'e'].every((id) => startOf(sch, id) === startOf(base, id) + 30),
    JSON.stringify(sch.map((r) => [r.id, r.start])));
  check('原訂停留不被改寫', c.replanStops[1].stayMin === 60, '延長停留是預測，不是改規劃');

  // 規劃中不採用（例如重設進度後殘留）
  c.currentTripStatus = 'planning';
  check('規劃中不套用預計離開時間', endOf(build(), 'b') === endOf(base, 'b'));
  c.currentTripStatus = 'ongoing';

  // 提早走：後面跟著提早
  c.replanStops = mk();
  c.replanStops[1].expectedLeaveMin = endOf(base, 'b') - 20;
  sch = build();
  check('提早離開，後面跟著提早', startOf(sch, 'c') === startOf(base, 'c') - 20);
  // 比抵達還早（到得早、走得早）→ 至少留 5 分，不會出現負的停留
  c.replanStops[1].expectedLeaveMin = startOf(base, 'b') - 30;
  sch = build();
  check('離開時間早於抵達時至少保留 5 分', endOf(sch, 'b') === startOf(sch, 'b') + 5);

  // AGENTS.md 的時間規則：後站手動時間已晚於前站就不動；早於或等於才順延
  c.replanStops = mk();
  const cOrig = startOf(base, 'c');
  c.replanStops[2].manualStartMin = cOrig + 60;           // 都蘭手動排在較晚
  c.replanStops[1].expectedLeaveMin = endOf(base, 'b') + 30;
  sch = build();
  check('後站手動時間仍晚於前站 → 不動', startOf(sch, 'c') === cOrig + 60);
  c.replanStops[1].expectedLeaveMin = endOf(base, 'b') + 60; // 前站抵達剛好等於手動時間
  sch = build();
  check('後站手動時間等於前站抵達 → 維持在該時間', startOf(sch, 'c') === cOrig + 60);
  c.replanStops[1].expectedLeaveMin = endOf(base, 'b') + 90; // 超過手動時間
  sch = build();
  check('後站手動時間早於前站抵達 → 順延', startOf(sch, 'c') === cOrig + 90);

  // 真實時鐘換算
  const clock = (h, m, day, anchor) => vm.runInContext('clockToScheduleMinutes', c)(new Date(2026, 8, 27, h, m), day, anchor);
  check('第 1 天 14:30 → 870', clock(14, 30, 1, 600) === 870);
  check('第 2 天 10:00 → 1440+600', clock(10, 0, 2, 1440 + 540) === 2040);
  check('過午夜仍在同一站 → 視為隔天', clock(0, 30, 1, 21 * 60) === 1440 + 30, '夜市 21:00 開始、00:30 還在');

  // 衝突判斷
  const assess = (...a) => vm.runInContext('assessLeaveImpact', c)(...a);
  const warn = (row) => (row.closeAt != null && row.start >= row.closeAt ? '⚠️ 可能在非營業時間' : '');
  const before = [
    { id: 'b', start: 600, end: 660, dayIndex: 1 },
    { id: 'c', name: '都蘭', start: 675, end: 720, closeAt: 700, dayIndex: 1 },
    { id: 'x', name: '原本就打烊的店', start: 735, end: 760, closeAt: 700, dayIndex: 1 },
    { id: 'e', type: 'end', start: 775, end: 775, dayIndex: 1 }
  ];
  const shifted = before.map((r, i) => (i === 0 ? { ...r, end: r.end + 30 } : { ...r, start: r.start + 30, end: r.end + 30 }));
  let r = assess(before, shifted, 0, { hoursWarning: warn, dayEnds: [790] });
  check('回報順延分鐘與受影響站數', r.shift === 30 && r.affected === 3, JSON.stringify(r));
  check('新出現的營業時間衝突要報', r.conflicts.some((x) => x.kind === 'hours' && x.stopId === 'c'));
  check('原本就有的衝突不算在這次頭上', !r.conflicts.some((x) => x.stopId === 'x'));
  check('當天結束時間超出要報', r.conflicts.some((x) => x.kind === 'day-end' && x.over === 15), JSON.stringify(r.conflicts));
  r = assess(before, before, 0, { hoursWarning: warn, dayEnds: [790] });
  check('沒變化就沒有衝突', r.conflicts.length === 0 && r.shift === 0);
  r = assess(before, shifted, 0, { hoursWarning: warn, dayEnds: null });
  check('沒設定行程時長就不判斷結束時間', !r.conflicts.some((x) => x.kind === 'day-end'));

  // 欄位同步（stops 多處同步的規矩）與縮短範圍
  check('expectedLeaveMin 在存檔／兩處載入／共編快照／簽章／欄位白名單都有',
    (P.match(/expectedLeaveMin: Number\.isFinite\((?:s|stop)\.expectedLeaveMin\)/g) || []).length === 4
    && /'plannerNote', 'expectedLeaveMin'/.test(P)
    && /s\.plannerNote \|\| '', Number\.isFinite\(s\.expectedLeaveMin\)/.test(P));
  check('縮短後面停留只動目前站之後、且不動已定預計離開的站',
    /const eligible = i >= minIndex && [\s\S]{0,160}?s\.expectedLeaveMin == null;/.test(P));
  check('重設進度時清掉預計離開時間', /s\.checkedInAt = null; s\.expectedLeaveMin = null;/.test(P));

  // 行程中點開景點：換成行程中面板，不再是整份鎖住的編輯表單
  const lc = {
    Math, Number, String, Date, currentStopIndex: 3, collabReadOnly: false, currentItineraryId: 'my_trip',
    getStayingStopIndex: () => 2, minutesToClock: (m) => `${String(Math.floor(m / 60)).padStart(2, '0')}:${String(m % 60).padStart(2, '0')}`,
    jsAttrStr: (v) => String(v).replace(/'/g, "\\'"), escapeHtml: (v) => String(v).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;'),
    hasMeaningfulSpotDescription: (d) => !!String(d || '').trim(), formatDayBusinessHours: () => '🕐 09:00–17:00',
    getStopServiceDate: () => '2026-09-28', buildSpotNotice: () => '注意落石', formatAccessNote: () => ''
  };
  vm.createContext(lc);
  ['formatClockOfEpoch', 'describeLiveStopStatus', 'renderLiveStopPanel'].forEach((n) => vm.runInContext(extractFunction(P, n), lc));
  const panel = (stop, i, row) => vm.runInContext('renderLiveStopPanel', lc)(stop, i, row);
  const status = (stop, i, row) => vm.runInContext('describeLiveStopStatus', lc)(stop, i, row);
  const spot = { id: 's', name: '三仙台', desc: '跨海步橋', businessHours: '全天', plannerNote: '集合在<南側>停車場' };
  const row = { start: 600, end: 660 };
  let html = panel(spot, 2, row);
  check('正在停留的站：預計離開＋調整', /預計 11:00 離開 · 調整/.test(html) && /openLeaveTimeAdjuster\('s'\)/.test(html));
  check('正在停留的站：可以拍照、不顯示導航', /📷 拍照/.test(html) && !/🧭 導航/.test(html));
  check('面板沒有唯讀表單欄位', !/<(input|select|textarea)\b/.test(html) && !/disabled/.test(html),
    '使用者回報：行程中點開景點只看到點不動的欄位和鎖頭，很怪');
  check('景點資訊直接顯示（介紹、營業時間、注意事項）', /跨海步橋/.test(html) && /09:00–17:00/.test(html) && /注意落石/.test(html));
  check('行程備註以文字顯示且有跳脫', /集合在&lt;南側&gt;停車場/.test(html));
  html = panel(spot, 3, row);
  check('下一站：到達打卡＋導航', /✅ 到達打卡/.test(html) && /checkInCurrentStop\('s', event\)/.test(html) && /🧭 導航/.test(html));
  lc.collabReadOnly = true;
  html = panel(spot, 3, row);
  check('唯讀成員：可導航但不能打卡', /🧭 導航/.test(html) && !/到達打卡/.test(html));
  html = panel(spot, 2, row);
  check('唯讀成員：只看預計離開，不能調整', /預計 11:00 離開/.test(html) && !/openLeaveTimeAdjuster/.test(html));
  lc.collabReadOnly = false;
  check('沒寫備註就不顯示備註區塊', !/行程備註/.test(panel({ ...spot, plannerNote: '' }, 5, row)));
  check('狀態文字', status(spot, 3, row) === '下一站 · 預計 10:00 抵達' && status(spot, 5, row) === '預計 10:00 抵達'
    && /^你在這裡/.test(status(spot, 2, row)) && /^✓ 已打卡/.test(status(spot, 1, row)));
  check('行程中改走面板、不再輸出鎖住的表單',
    /if \(currentTripStatus === 'ongoing'\) \{\s*if \(kicker\) kicker\.textContent = '行程中';[\s\S]{0,200}?body\.innerHTML = renderLiveStopPanel\(stop, stopIndex, schedule\);\s*return true;/.test(P));

  // 行程進行中不在背景自動壓縮
  // 註：不用 extractFunction——預設參數 `options = {}` 的大括號會讓它截錯函式本體
  check('行程開始後只有使用者主動要求才壓縮',
    /function fitScheduleToTimeLimit\(options = \{\}\) \{[\s\S]{0,600}?if \(currentTripStatus !== 'planning' && options\.userInitiated !== true\) \{\s*return \{ changed: false/.test(P),
    '實測：延長停留後其他成員一開頁，後面景點被背景壓到 5 分，兩人看到的結束時間不同');
  check('「縮短後面景點的停留」帶 userInitiated 且只縮到 35% 下限',
    /fitScheduleToTimeLimit\(\{ minIndex: index \+ 1, userInitiated: true, floorOnly: true \}\)/.test(P)
    && /guard = options\.floorOnly \? 4000 : 0;/.test(P),
    '實測：沒有下限時後面 4 個景點被壓到只剩 5 分');
}
section20();

// ══════════════════════════════════════════════════════════════
// 21. 地圖「階段」面板：窄地圖預設收合、縮放避開面板、一行一段、行程中展開目前這段
// ══════════════════════════════════════════════════════════════
function section21() {
  console.log('\n── 21. 階段面板 ──');
  const P = fs.readFileSync(path.join(APP, 'ai-travel-planner-v8.js'), 'utf8');
  const C = fs.readFileSync(path.join(APP, 'ai-travel-planner-v8.css'), 'utf8');
  const cls = () => { const s = new Set(); return { set: s, toggle: (n, on) => (on ? s.add(n) : s.delete(n)), contains: (n) => s.has(n) }; };
  const mkCard = (i) => ({ dataset: { index: String(i) }, classList: cls(), attrs: {}, setAttribute(k, v) { this.attrs[k] = v; }, offsetTop: i * 40 });
  const cards = [0, 1, 2, 3].map(mkCard);
  const scrolls = [];
  const panel = { classList: cls(), style: { display: 'block' }, offsetWidth: 240, parentElement: { clientWidth: 1000 },
    querySelectorAll: () => cards, scrollTo: (o) => scrolls.push(o.top) };
  const c = {
    Math, Number, String, currentTripStatus: 'ongoing', currentStopIndex: 2, activeRouteStage: null, lastScrolledLiveRouteStage: null,
    routeStageCache: [0, 1, 2, 3].map((i) => ({ index: i, sourceStopIndex: i, destinationStopIndex: i + 1 })),
    document: { getElementById: () => panel }, isMobileLayout: () => false, map: { getDiv: () => ({ clientWidth: 1000 }) },
    // 階段面板會跟著行程列表的「第 N 天」切換一起過濾
    currentTripPreferences: {}, itineraryDayFilter: 0,
    buildReplanSchedule: () => [{ dayIndex: 1 }, { dayIndex: 1 }, { dayIndex: 2 }, { dayIndex: 2 }]
  };
  vm.createContext(c);
  ['getLiveRouteStageIndex', 'syncDirectionsPanelStages', 'routeFitPadding', 'formatStageMinutes',
    'getActiveDayFilter', 'getRouteStageDayIndex', 'isMultiDayTrip', 'getPrefsDayCount', 'clampDayIndex'
  ].forEach((n) => vm.runInContext(extractFunction(P, n), c));
  const run = (code) => vm.runInContext(code, c);
  const has = (i, n) => cards[i].classList.contains(n);

  run('syncDirectionsPanelStages()');
  check('行程中展開「正在前往的段」（前往第 2 站＝第 2 段）', has(1, 'is-expanded') && has(1, 'is-live') && !has(2, 'is-expanded'));
  check('走過的段標成已完成', has(0, 'is-done') && !has(1, 'is-done') && !has(3, 'is-done'));
  check('換段時捲到目前這段（只捲一次）', scrolls.length === 1 && scrolls[0] === 32);
  run('syncDirectionsPanelStages()');
  check('同一段不重複捲動', scrolls.length === 1, '使用者自己捲過就不該被搶回去');
  c.activeRouteStage = 3;
  run('syncDirectionsPanelStages()');
  check('使用者點選的段優先展開', has(3, 'is-expanded') && has(3, 'is-active') && !has(1, 'is-expanded'));
  c.activeRouteStage = null; c.currentTripStatus = 'planning';
  run('syncDirectionsPanelStages()');
  check('規劃中沒有點選就全部一行', cards.every((_, i) => !has(i, 'is-expanded') && !has(i, 'is-done')));

  // 兩天一夜：行程列表切到「第 2 天」，階段面板只留第 2 天的段（段別依起點站所屬的天）
  c.currentTripPreferences = { days: '2天' };
  c.itineraryDayFilter = 2;
  run('syncDirectionsPanelStages()');
  check('切到第 2 天：第 1 天的段被隱藏', has(0, 'is-day-hidden') && has(1, 'is-day-hidden'));
  check('切到第 2 天：第 2 天的段留著', !has(2, 'is-day-hidden') && !has(3, 'is-day-hidden'));
  // 把手標題的段數要跟著分日走（使用者回報：卡片篩掉了，標題還寫「路線 · 19 段」）
  const handleEl = { innerHTML: '', attrs: {}, setAttribute(k, v) { this.attrs[k] = v; } };
  c.document = { getElementById: (id) => (id === 'directionsPanelHandle' ? handleEl : panel) };
  vm.runInContext(extractFunction(P, 'renderDirectionsPanelHandle'), c);
  c.itineraryDayFilter = 2;
  run('renderDirectionsPanelHandle(false)');
  check('把手標題顯示當天段數，不是整趟段數', /第 2 天 · 2 段/.test(handleEl.innerHTML), handleEl.innerHTML);
  c.itineraryDayFilter = 0;
  run('renderDirectionsPanelHandle(false)');
  check('切回「全部」：把手標題回到整趟段數', /路線 · 4 段/.test(handleEl.innerHTML), handleEl.innerHTML);
  c.document = { getElementById: () => panel };

  c.itineraryDayFilter = 0;
  run('syncDirectionsPanelStages()');
  check('切回「全部」：所有段都回來', cards.every((_, i) => !has(i, 'is-day-hidden')));
  c.currentTripPreferences = {};

  let pad = run('routeFitPadding(40)');
  check('面板展開：左側留白＝面板寬＋間距', pad.left === 40 + 240 + 16 && pad.right === 40, JSON.stringify(pad));
  panel.classList.toggle('collapsed', true);
  pad = run('routeFitPadding(40)');
  check('面板收合：四邊一樣', pad.left === 40);
  panel.classList.toggle('collapsed', false);
  c.map = { getDiv: () => ({ clientWidth: 380 }) };
  pad = run('routeFitPadding(40)');
  check('地圖太窄放不下就不讓（避免路線縮成一個點）', pad.left === 40);
  check('時間格式', run('formatStageMinutes(7)') === '7 分' && run('formatStageMinutes(75)') === '1 時 15 分' && run('formatStageMinutes(120)') === '2 時');

  check('窄地圖預設收合、使用者選過就尊重', /const DIRECTIONS_PANEL_AUTO_COLLAPSE_BELOW = 720;/.test(P)
    && /if \(directionsPanelUserChoice\) \{\s*setDirectionsPanelCollapsed\(directionsPanelUserChoice === 'closed'\);\s*return;/.test(P)
    && /directionsPanelUserChoice = collapsed \? 'closed' : 'open';/.test(P));
  check('所有路線縮放都避開面板', (P.match(/map\.fitBounds\([^;]*routeFitPadding\(/g) || []).length === 5,
    String((P.match(/map\.fitBounds\([^;]*routeFitPadding\(/g) || []).length));
  check('面板寬度跟著地圖欄（最多 320px、40%）', /#directionsPanel \{ width: min\(320px, calc\(40% - 16px\)\) !important;/.test(C));
  check('把手是橫向標題列／膠囊，不再是直排方塊', /#directionsPanelHandle \{[\s\S]{0,300}?writing-mode: horizontal-tb;/.test(C)
    && /#directionsPanelHandle\.collapsed \{[\s\S]{0,260}?border-radius: 999px;/.test(C), '使用者回報：直排「階段」方塊很突兀');
  check('替代路線收進「⋯ 路線選項」，與手機一致',
    /class="route-stage-more" onclick="openRouteStageActions\(\$\{i\}, event\)"/.test(P) && !/class="route-alt-btn"/.test(P),
    '使用者要求：桌機比照手機，把替代路線藏起來');
  check('桌機的「路線選項」是置中小視窗', /@media \(min-width: 1025px\) \{\s*body \.route-stage-actions-modal \{ align-items: center; justify-content: center; \}/.test(C));
  check('路線選項有明確標題與動作', /這段路線可以怎麼走？/.test(P)
    && /在地圖上查看/.test(P) && /比較其他路線/.test(P));
  check('沒有其他路線時顯示說明，不顯示灰色按鈕', /route-stage-actions-empty/.test(P)
    && !/data-stage-alt \$\{canCompare \? '' : 'disabled'\}/.test(P));
  check('選取高亮只用 class，不再逐張寫 inline style', !/child\.style\.(borderColor|backgroundColor|boxShadow)/.test(P),
    '實測：舊的 inline style 讓每一行都多出外框');
  check('一行一段、細節預設收起', /\.route-stage-detail \{ display: none;/.test(C) && /\.route-stage-card\.is-expanded \.route-stage-detail \{ display: block; \}/.test(C));
  check('手機路段起終點各自防拆詞，箭頭與目的地同組',
    /class="mobile-route-item-origin"/.test(P)
    && /class="mobile-route-item-destination-group"/.test(P)
    && /\.mobile-route-item-origin,\s*\.mobile-route-item-destination-group\s*\{[^}]*white-space:\s*nowrap;/.test(C)
    && /\.mobile-route-item-destination\s*\{[^}]*white-space:\s*nowrap;/.test(C));
}
section21();

// ══════════════════════════════════════════════════════════════
// 22. 跨端相容：App 建立的多日行程在網頁上不可被讀成單日
//     （App 端回報：App 的兩天一夜到網頁變單日、時間軸一路排到 22:48）
// ══════════════════════════════════════════════════════════════
function section22() {
  console.log('\n── 22. App 行程的天數相容 ──');
  const P = fs.readFileSync(path.join(APP, 'ai-travel-planner-v8.js'), 'utf8');
  const c = { Math, Number, String, Date, currentTripPreferences: {} };
  vm.createContext(c);
  ['derivePreferencesFromTrip', 'parseAppDaysWindow', 'getPrefsDayCount', 'isMultiDayTrip']
    .forEach((n) => vm.runInContext(extractFunction(P, n), c));
  const run = (code) => vm.runInContext(code, c);

  // App 寫入的樣子：有 appDays（跨兩日）、沒有 wizardData
  c.appTrip = { appDays: '2026/09/30 09:00 - 2026/10/01 15:00', transportMode: 'car' };
  let prefs = run('derivePreferencesFromTrip(appTrip)');
  check('App 行程（無 wizardData）→ 由 appDays 推出 2 天', prefs.days === '2天', JSON.stringify(prefs));
  check('App 行程 → isMultiDayTrip 為真', run('isMultiDayTrip(derivePreferencesFromTrip(appTrip).days)') === true);

  // 網頁改交通工具後曾補出的殘缺 wizardData（只有 transportMode，沒有 days）
  c.brokenTrip = { ...c.appTrip, wizardData: { transportMode: 'scooter' } };
  prefs = run('derivePreferencesFromTrip(brokenTrip)');
  check('殘缺 wizardData 不再遮住 appDays（原本會變成單日）', prefs.days === '2天', JSON.stringify(prefs));
  check('殘缺 wizardData 裡的車輛仍然保留', prefs.transportMode === 'scooter', String(prefs.transportMode));
  check('仍標記為 App 推導', prefs.__appDerived === true);

  // 網頁自己建立的完整 wizardData 要原封不動沿用（day2StartTime 等新欄位靠這條流通）
  c.webTrip = { wizardData: { days: '2天', startTime: '14:00', day2StartTime: '08:00' }, appDays: '2026/09/30 09:00 - 2026/10/01 15:00' };
  prefs = run('derivePreferencesFromTrip(webTrip)');
  check('完整 wizardData 整包沿用、不被 appDays 蓋掉',
    prefs.days === '2天' && prefs.startTime === '14:00' && prefs.day2StartTime === '08:00' && !prefs.__appDerived,
    JSON.stringify(prefs));

  // 寫入側：App 行程不可再被補上殘缺 wizardData（否則下次載入又壞）
  check('Firestore 寫入前先擋 __appDerived，不補 wizardData',
    /if \(!\(currentTripPreferences && currentTripPreferences\.__appDerived\)\) \{\s*patch\.wizardData = \{ \.\.\.\(patch\.wizardData \|\| \{\}\), transportMode: committedVehicle \};/.test(P));
  check('本機快取寫入同樣擋掉',
    /if \(hasVehiclePref && !\(currentTripPreferences && currentTripPreferences\.__appDerived\)\) \{/.test(P));

  // 分日切換：再點一次同一天回到「全部」
  check('再點一次目前那天 → 回到全部',
    /if \(next > 0 && itineraryDayFilter === next\) next = 0;/.test(P));

  // ── 停車資訊：不得把「查不到」講成「沒有」，並補上具名搜尋 ──
  check('Places 第三段用「景點名＋停車場」具名搜尋',
    /query: `\$\{namedQuery\} 停車場`/.test(P) && /listParkingFromPlaces\(center, stop && stop\.name\)/.test(P),
    '鄉鎮景點的停車場多半掛在景點名下，generic type:parking 搜不到');
  check('具名搜尋沿用同一個距離上限，不放寬',
    !/PARKING_NAMED_SEARCH_RADIUS|namedRadius/.test(P),
    '放寬半徑會把別鄉鎮的同名停車場配進來');
  check('畫面不再宣稱「無停車場／查不到停車場」',
    !/無停車場<\/span>/.test(P) && !/查不到停車場資料，請/.test(P),
    '查不到資料不等於現場沒有停車空間');
  check('改用「停車待確認」並說明不代表沒有',
    /停車待確認/.test(P) && /不代表沒有/.test(P));
  // Cost regression: opening or replanning a trip must not prefetch toilets for every stop.
  // The selected stop/stage still resolves its toilets through the existing lazy path.
  check('opening a trip does not batch-prefetch toilets',
    !P.includes('prefetchAllStopToiletData'));
  check('selected stop or route stage still resolves toilets lazily',
    /async function renderToiletMarkersForActiveRouteStage\(\)[\s\S]{0,2600}?resolveToiletCoordinatesNearStop/.test(P));
}
section22();

section19().catch((e) => check('第 19 節執行', false, e && e.stack)).then(() => {
  console.log('\n══════════════════════════════════════');
  console.log('通過 ' + pass + '，失敗 ' + fail);
  if (failures.length) {
    console.log('\n失敗項目：');
    failures.forEach((f, i) => console.log('  ' + (i + 1) + '. ' + f));
  }
  process.exit(fail ? 1 : 0);
});
