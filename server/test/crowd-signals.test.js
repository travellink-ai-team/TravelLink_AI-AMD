// crowd-signals.js 的離線測試：node test/crowd-signals.test.js
'use strict';
const c = require('../crowd-signals');

let pass = 0, fail = 0;
const ok = (name, cond, detail) => {
  if (cond) { pass++; console.log('PASS  ' + name); }
  else { fail++; console.log('FAIL  ' + name + (detail ? '\n      ' + JSON.stringify(detail) : '')); }
};

ok('時段：早上', c.slotOf('09:30') === 'am');
ok('時段：中午', c.slotOf('12:00') === 'noon');
ok('時段：下午', c.slotOf('次日 15:10') === 'pm');
ok('時段：晚上', c.slotOf('18:00') === 'night');
ok('時段：沒有時間', c.slotOf('') === null);
ok('欄位名：臺台視為同一個', c.stopKey('臺東森林公園') === c.stopKey('台東森林公園 (琵琶湖)'));
ok('欄位名：不留 Firestore 路徑字元', !/[.~*/[\]]/.test(c.stopKey('A.B/C*D[E]')), c.stopKey('A.B/C*D[E]'));
ok('日期：不存在的月份', c.isValidDate('2026-13-01') === false);
ok('日期：不存在的日子', c.isValidDate('2026-02-30') === false);
ok('統計：出發日格式壞掉不會丟錯', c.countsFromTrip([{ name: 'A', time: '10:00' }], '2026-13-01')[0].date === null);
ok('日期：第 2 天', c.dateForDay('2026-12-31', 2) === '2027-01-01');

const stops = [
  { type: 'start', name: '常德路', time: '09:00' },
  { name: '鯉魚山', time: '09:30', dayIndex: 1 },
  { type: 'lodging', name: '民宿', time: '17:00', dayIndex: 1 },
  { name: '小野柳', time: '09:00', dayIndex: 2 },
  { type: 'end', name: '常德路', time: '12:00' }
];
const counts = c.countsFromTrip(stops, '2026-11-20');
ok('統計：只算景點，不算起訖與住宿', counts.length === 2, counts);
ok('統計：第 2 天算到隔天', counts[1].date === '2026-11-21', counts);
ok('統計：沒有出發日只算總數', c.countsFromTrip(stops, '').every((x) => x.date === null));

ok('座標：還原 destKey', JSON.stringify(c.coordsFromDestKey('d22p7537_121p1497')) === JSON.stringify({ lat: 22.7537, lng: 121.1497 }));
ok('座標：格式錯回 null', c.coordsFromDestKey('abc') === null);
const full = c.fullSpotsFromReports([
  { type: 'full', destKey: 'd22p7537_121p1497', uid: 'secret', note: 'x' },
  { type: 'full', destKey: 'd22p7537_121p1497' },
  { type: 'found', destKey: 'd23p0000_121p0000' }
]);
ok('已滿：同地點只算一次、其他類型不算', full.length === 1, full);
ok('已滿：不回傳回報者', !JSON.stringify(full).includes('secret'));
const dayStart = c.startOfTaiwanDay(Date.UTC(2026, 10, 7, 20, 0));
ok('台灣時間：UTC 11/7 20:00 已是台灣 11/8', new Date(dayStart).toISOString() === '2026-11-07T16:00:00.000Z', new Date(dayStart).toISOString());

console.log(`\n${pass} passed, ${fail} failed`);
process.exit(fail ? 1 : 0);
