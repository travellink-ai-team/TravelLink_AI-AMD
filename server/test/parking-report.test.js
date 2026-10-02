// 停車回報端點的驗證邏輯測試（不啟動真正的 server，不碰 Firestore）
// 用 express + supertest 風格的手動注入：把 server.js 裡的純函式邏輯抄過來對照，
// 確認邊界條件的判斷與端點一致。重點在「哪些輸入該被拒絕」。
const assert = require('assert');
let pass = 0, fail = 0;
const ok = (name, cond) => { if (cond) { pass++; console.log('PASS  ' + name); } else { fail++; console.log('FAIL  ' + name); } };

// ── 與 server.js 相同的判斷式（若那邊改了，這裡要同步）──
const TYPES = new Set(['found', 'full', 'closed', 'none', 'wrong', 'missing']);
const KINDS = new Set(['lot', 'roadside', 'temp']);
const GEOFENCE = 800, MAX_ACC = 50;
const finiteNumber = (v) => (typeof v === 'number' && Number.isFinite(v) ? v : null);
function haversineMeters(a, b) {
  const R = 6371000, rad = (x) => (x * Math.PI) / 180;
  const dLat = rad(b.lat - a.lat), dLng = rad(b.lng - a.lng);
  const s = Math.sin(dLat / 2) ** 2 + Math.cos(rad(a.lat)) * Math.cos(rad(b.lat)) * Math.sin(dLng / 2) ** 2;
  return 2 * R * Math.asin(Math.sqrt(s));
}
function destKeyOf(lat, lng) {
  return `d${(Math.round(lat * 1e4) / 1e4).toFixed(4)}_${(Math.round(lng * 1e4) / 1e4).toFixed(4)}`
    .replace(/\./g, 'p').replace(/-/g, 'm');
}

// 模擬端點的驗證流程（目的地座標假設已由伺服器反查取得）
function validate(body, dest) {
  const type = String(body.type || '').trim();
  const kind = String(body.kind || '').trim();
  const lat = finiteNumber(body.lat), lng = finiteNumber(body.lng);
  const accuracy = finiteNumber(body.accuracy);
  if (!String(body.tripId || '').trim() || !String(body.stopId || '').trim()) return 'invalid trip or stop';
  if (!TYPES.has(type)) return 'invalid type';
  const hasCoords = lat !== null && lng !== null;
  // 範圍檢查在 type 分支之外：非 found 也可能帶座標
  if (hasCoords && (lat < -90 || lat > 90 || lng < -180 || lng > 180)) return 'coordinates out of range';
  if (type === 'found') {
    if (!hasCoords) return 'coordinates required';
    if (!KINDS.has(kind)) return 'invalid kind';
    if (accuracy === null || accuracy < 0 || accuracy > MAX_ACC) return 'accuracy too low';
  } else if (kind && !KINDS.has(kind)) {
    return 'invalid kind';
  }
  if (hasCoords && haversineMeters({ lat, lng }, dest) > GEOFENCE) return 'too far from destination';
  return null;   // 通過
}

const DEST = { lat: 22.7915, lng: 121.1200 };
const base = { tripId: 't1', stopId: 's1', type: 'found', kind: 'lot', accuracy: 20, lat: 22.7918, lng: 121.1203 };

console.log('── 正常路徑 ──');
ok('合法的 found 回報通過', validate(base, DEST) === null);
ok('無座標的 full 回報通過', validate({ tripId: 't1', stopId: 's1', type: 'full' }, DEST) === null);

console.log('\n── 欄位驗證 ──');
ok('缺 tripId 被拒', validate({ ...base, tripId: '' }, DEST) === 'invalid trip or stop');
ok('未知 type 被拒', validate({ ...base, type: 'hacked' }, DEST) === 'invalid type');
ok('found 缺座標被拒', validate({ ...base, lat: null, lng: null }, DEST) === 'coordinates required');
ok('found 缺 kind 被拒', validate({ ...base, kind: '' }, DEST) === 'invalid kind');
ok('未知 kind 被拒', validate({ ...base, kind: 'helicopter' }, DEST) === 'invalid kind');

console.log('\n── 數值邊界（Codex 特別點名）──');
ok('NaN 座標被拒', validate({ ...base, lat: NaN }, DEST) === 'coordinates required');
ok('Infinity 座標被拒', validate({ ...base, lat: Infinity }, DEST) === 'coordinates required');
ok('字串 "abc" 座標被拒', validate({ ...base, lat: 'abc' }, DEST) === 'coordinates required');
ok('緯度 91 超範圍被拒', validate({ ...base, lat: 91 }, DEST) === 'coordinates out of range');
ok('經度 -181 超範圍被拒', validate({ ...base, lng: -181 }, DEST) === 'coordinates out of range');
ok('accuracy 2000 被拒（>50）', validate({ ...base, accuracy: 2000 }, DEST) === 'accuracy too low');
ok('accuracy 恰 50 通過', validate({ ...base, accuracy: 50 }, DEST) === null);
ok('accuracy 負數被拒', validate({ ...base, accuracy: -1 }, DEST) === 'accuracy too low');
ok('accuracy 缺漏被拒', validate({ ...base, accuracy: null }, DEST) === 'accuracy too low');

console.log('\n── 地理圍籬 ──');
ok('45m 內通過', validate(base, DEST) === null);
ok('1.5km 外被拒', validate({ ...base, lat: 22.8050, lng: 121.1200 }, DEST) === 'too far from destination');
// ★ 關鍵：前端傳來的目的地座標必須無效——端點根本不讀 body 的 destLat/destLng
const spoof = { ...base, lat: 25.0330, lng: 121.5654, destLat: 25.0330, destLng: 121.5654 };
ok('★ 偽造 destLat/destLng 無法繞過圍籬（台北座標被擋）',
  validate(spoof, DEST) === 'too far from destination');

console.log('\n── 非 found 的旁路（Codex 審查抓到）──');
// 經度 +360 落在同一條子午線上，Haversine 距離為 0——圍籬擋不住，
// 只有明確的範圍檢查能擋，而原本的範圍檢查只在 found 分支裡。
const wrap = { tripId: 't1', stopId: 's1', type: 'full', lat: DEST.lat, lng: DEST.lng + 360 };
ok('★ 非 found 的經度 +360 被範圍檢查擋下（圍籬擋不到）',
  validate(wrap, DEST) === 'coordinates out of range');
ok('非 found 的緯度 91 也被擋',
  validate({ tripId: 't1', stopId: 's1', type: 'full', lat: 91, lng: DEST.lng }, DEST) === 'coordinates out of range');
ok('非 found 帶未知 kind 被拒',
  validate({ tripId: 't1', stopId: 's1', type: 'full', kind: 'helicopter' }, DEST) === 'invalid kind');
ok('非 found 不帶 kind 仍通過',
  validate({ tripId: 't1', stopId: 's1', type: 'full' }, DEST) === null);

console.log('\n── 地點鍵 ──');
ok('同一地點的兩次回報產生相同 destKey',
  destKeyOf(22.79151, 121.12003) === destKeyOf(22.79149, 121.11998));
ok('相隔 100m 的兩點 destKey 不同',
  destKeyOf(22.7915, 121.1200) !== destKeyOf(22.7924, 121.1200));
ok('destKey 不含 Firestore 禁用字元（/ . 等）',
  /^[a-zA-Z0-9_]+$/.test(destKeyOf(22.7915, 121.1200)));
ok('負座標也能產生合法 destKey',
  /^[a-zA-Z0-9_]+$/.test(destKeyOf(-33.8688, -151.2093)));

console.log(`\n總計 ${pass} 通過 / ${fail} 失敗`);
process.exit(fail ? 1 : 0);
