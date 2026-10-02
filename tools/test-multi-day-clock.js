// 驗證 planner v8 新增的多日時間欄位轉換（實作與 app/ai-travel-planner-v8.js 一致）
function minutesToClock(totalMinutes) {
  const safe = Math.max(0, totalMinutes);
  const dayOffset = Math.floor(safe / (24 * 60));
  const within = safe % (24 * 60);
  const hhmm = `${String(Math.floor(within / 60)).padStart(2, '0')}:${String(within % 60).padStart(2, '0')}`;
  if (dayOffset === 1) return `次日 ${hhmm}`;
  if (dayOffset >= 2) return `第 ${dayOffset + 1} 天 ${hhmm}`;
  return hhmm;
}

function clockToMinutes(clockText) {
  if (!clockText || typeof clockText !== 'string') return null;
  let text = clockText.trim();
  let offset = 0;
  const dayPrefix = /^第\s*(\d+)\s*天\s*/.exec(text);
  if (text.startsWith('次日 ')) { offset = 24 * 60; text = text.slice(3); }
  else if (dayPrefix) { offset = Math.max(0, Number(dayPrefix[1]) - 1) * 24 * 60; text = text.slice(dayPrefix[0].length); }
  if (!text.includes(':')) return null;
  const [hText, mText] = text.split(':');
  const h = parseInt(hText, 10);
  const m = parseInt(mText, 10);
  if (!Number.isFinite(h) || !Number.isFinite(m)) return null;
  if (h < 0 || h > 23 || m < 0 || m > 59) return null;
  return offset + h * 60 + m;
}

function toClockFieldValue(absoluteMinutes) {
  const n = Number(absoluteMinutes);
  if (!Number.isFinite(n)) return '';
  const within = ((n % 1440) + 1440) % 1440;
  return `${String(Math.floor(within / 60)).padStart(2, '0')}:${String(within % 60).padStart(2, '0')}`;
}

function fromClockFieldValue(clockText, dayIndex, anchorMin) {
  const min = clockToMinutes(clockText);
  if (!Number.isFinite(min)) return null;
  const within = ((min % 1440) + 1440) % 1440;
  const day = Math.max(1, Math.round(Number(dayIndex)) || 1);
  let base = (day - 1) * 1440;
  const anchor = Number(anchorMin);
  if (Number.isFinite(anchor)) {
    const anchorDay = Math.floor(anchor / 1440);
    if (anchorDay > day - 1) base = anchorDay * 1440;
  }
  return base + within;
}

const HHMM = /^([01]\d|2[0-3]):[0-5]\d$/;
let pass = 0, fail = 0;
function check(name, actual, expected) {
  const ok = actual === expected;
  if (ok) pass++; else fail++;
  console.log(`${ok ? 'PASS' : 'FAIL'}  ${name}  →  ${JSON.stringify(actual)}${ok ? '' : `  (expected ${JSON.stringify(expected)})`}`);
}

console.log('── BUG 4：minutesToClock / clockToMinutes 必須互逆 ──');
[540, 1980, 2100, 3000, 4500].forEach((m) => {
  check(`round-trip ${m} (${minutesToClock(m)})`, clockToMinutes(minutesToClock(m)), m);
});

console.log('\n── BUG 1：<input type="time"> 只吃 HH:MM ──');
// 1980 分 = 1440 + 540 → 第 2 天 09:00
check('第1天 09:00 欄位值', toClockFieldValue(540), '09:00');
check('第2天 09:00 欄位值', toClockFieldValue(1980), '09:00');
check('第2天欄位值通過 HH:MM 格式', HHMM.test(toClockFieldValue(1980)), true);
check('舊行為（次日 09:00）會被 input 丟棄', HHMM.test(minutesToClock(1980)), false);
check('第2天欄位原值存回不變', fromClockFieldValue('09:00', 2, 1980), 1980);
check('第2天改成 11:15 存回', fromClockFieldValue('11:15', 2, 1980), 2115);
check('第1天欄位存回不受影響', fromClockFieldValue('09:00', 1, 540), 540);

console.log('\n── BUG 2：修改站點視窗，起訖同基準比較 ──');
// 第2天某站原本 09:00–11:00（絕對分鐘 1980–2100）
const startField = toClockFieldValue(1980);  // '09:00'
const endField = toClockFieldValue(2100);    // '11:00'
// 情境 A：只改結束時間 → 11:30
const s1 = fromClockFieldValue(startField, 2, 1980);
const e1 = fromClockFieldValue('11:30', 2, 2100);
check('只改結束時間：結束晚於開始（舊版會跳「請設定有效的起訖時間」）', e1 > s1, true);
check('只改結束時間：stayMin 合理', e1 - s1, 150);
// 情境 B：只改開始時間 → 08:30（舊版 stayMin 會變成 2100-510=1590 分 ≈ 26.5 小時）
const s2 = fromClockFieldValue('08:30', 2, 1980);
const e2 = fromClockFieldValue(endField, 2, 2100);
check('只改開始時間：stayMin 不再暴增成一整天', e2 - s2, 150);
check('只改開始時間：舊版的錯誤值', 2100 - clockToMinutes('08:30'), 1590);

console.log('\n── 單日行程玩過午夜（錨點兜底） ──');
check('單日 01:00 顯示', toClockFieldValue(1500), '01:00');
check('單日 01:00 存回仍在隔天', fromClockFieldValue('01:00', 1, 1500), 1500);
check('單日一般時段不被推到隔天', fromClockFieldValue('14:00', 1, 840), 840);

// ── 多日時間窗口（planner v8 getMultiDayWindow 的純邏輯部分）──
function getLaterDayStartClock(dayEndClockMin) {
  const DEFAULT_START = 9 * 60;
  if (!Number.isFinite(dayEndClockMin) || dayEndClockMin >= 10 * 60) return DEFAULT_START;
  return Math.max(0, dayEndClockMin - 60);
}

function buildDayWindows(startMin, day1Hours, lastEndMin, dayCount, day2StartTime) {
  const days = [];
  let prevEnd = -Infinity;
  for (let i = 1; i <= dayCount; i++) {
    const dayBase = (i - 1) * 24 * 60;
    let dayStart, dayEnd;
    if (i === 1) {
      dayStart = dayBase + startMin;
      dayEnd = dayStart + day1Hours * 60;
    } else {
      const isLastDay = (i === dayCount);
      const explicitStartClock = clockToMinutes(day2StartTime);
      const hasExplicitStart = Number.isFinite(explicitStartClock)
        && (!isLastDay || explicitStartClock < lastEndMin);
      const startClock = hasExplicitStart
        ? explicitStartClock
        : (isLastDay ? getLaterDayStartClock(lastEndMin) : getLaterDayStartClock(null));
      dayStart = dayBase + startClock;
      if (dayStart < prevEnd) dayStart = prevEnd;
      dayEnd = isLastDay ? Math.max(dayStart + 5, dayBase + lastEndMin) : dayStart + 8 * 60;
    }
    days.push({ startMin: dayStart, endMin: dayEnd });
    prevEnd = dayEnd;
  }
  return days;
}

// getMultiDayWindow 的出發時刻解析：`|| 540` 會把合法的 00:00 改寫成 09:00
function resolveStartMin(clockText) {
  const parsed = clockToMinutes(clockText);
  return Number.isFinite(parsed) ? parsed : (9 * 60);
}

const win = (s, h, e, n = 2, d2s) => buildDayWindows(s, h, e, n, d2s)
  .map((d) => `${minutesToClock(d.startMin)}–${minutesToClock(d.endMin)}`).join(' / ');

console.log('\n── 第二天時間窗口（與建立端同規則）──');
check('早上出發、次日中午結束', win(540, 8, 720), '09:00–17:00 / 次日 09:00–次日 12:00');
check('下午 14:00 出發（舊版會算成第2天 14:00–15:00）', win(840, 6, 720), '14:00–20:00 / 次日 09:00–次日 12:00');
check('次日很早就返程（09:30）→ 開始改為結束前一小時', win(540, 8, 570), '09:00–17:00 / 次日 08:30–次日 09:30');
check('次日 10:00 結束 → 維持 09:00 開始', win(540, 8, 600), '09:00–17:00 / 次日 09:00–次日 10:00');
check('三天：中間日 09:00 起約 8 小時', win(540, 8, 720, 3),
  '09:00–17:00 / 次日 09:00–次日 17:00 / 第 3 天 09:00–第 3 天 12:00');
check('使用者設定的結束時刻被保留', buildDayWindows(840, 6, 720, 2)[1].endMin % 1440, 720);

console.log('\n── 邊界：首日跨午夜 / 合法 00:00 ──');
// 首日 23:00 出發玩 12 小時 → 次日 11:00 才結束；第二天不能還從 09:00 開始
check('首日跨午夜 → 第二天以首日結束為下限',
  win(23 * 60, 12, 720), '23:00–次日 11:00 / 次日 11:00–次日 12:00');
check('首日跨午夜且回程更早 → 至少留 5 分鐘、不變成負區間',
  buildDayWindows(23 * 60, 12, 600, 2)[1].endMin - buildDayWindows(23 * 60, 12, 600, 2)[1].startMin, 5);
check('出發 00:00 不被改寫成 09:00', resolveStartMin('00:00'), 0);
check('出發空字串才退回 09:00', resolveStartMin(''), 540);
check('出發 00:00 的第一天窗口', win(resolveStartMin('00:00'), 8, 720), '00:00–08:00 / 次日 09:00–次日 12:00');

// activeMinutes＝各天實際窗口總和，必須是 660（第一天 8 小時 + 第二天 09:00–12:00），
// 不是 parseDurationMinutes('2天') 那個固定的 960。與建立端口徑一致。
const activeMinutes = (s, h, e, n = 2) =>
  buildDayWindows(s, h, e, n).reduce((sum, d) => sum + (d.endMin - d.startMin), 0);
check('預設兩天一夜的實際活動分鐘＝660（非 960）', activeMinutes(540, 8, 720), 660);

console.log('\n── 建立端新增的「第二天出發時間」（prefs.day2StartTime）──');
check('使用者設 08:00 → 第二天照他設的開始',
  win(540, 8, 720, 2, '08:00'), '09:00–17:00 / 次日 08:00–次日 12:00');
check('沒設 → 仍走 09:00 預設規則',
  win(540, 8, 720, 2, ''), '09:00–17:00 / 次日 09:00–次日 12:00');
check('設得比結束還晚（14:00 vs 12:00）→ 不採用、也不覆寫結束時刻',
  win(540, 8, 720, 2, '14:00'), '09:00–17:00 / 次日 09:00–次日 12:00');
check('合法的 00:00 出發時間不被當成沒設',
  win(540, 8, 720, 2, '00:00'), '09:00–17:00 / 次日 00:00–次日 12:00');
check('首日跨午夜仍優先：第二天不早於首日結束',
  win(23 * 60, 12, 720, 2, '08:00'), '23:00–次日 11:00 / 次日 11:00–次日 12:00');

console.log(`\n${fail === 0 ? 'ALL PASS' : 'HAS FAILURES'}  pass=${pass} fail=${fail}`);
process.exit(fail === 0 ? 0 : 1);
