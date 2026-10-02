/* 照片亂序、缺 EXIF、跨裝置格式的純邏輯回歸測試。 */
'use strict';

const assert = require('node:assert/strict');
global.window = {};
require('../app/trip-photo-manager.js');
const manager = global.window.TripPhotoManager;

const at = (time) => Date.parse(`2026-09-28T${time}:00+08:00`);
const stops = [
  { id: 'park', name: '森林公園', dayKey: '2026-09-28', startAt: at('09:00'), endAt: at('10:00'), coords: { lat: 22.765, lng: 121.157 } },
  { id: 'market', name: '鐵花村', dayKey: '2026-09-28', startAt: at('11:00'), endAt: at('12:00'), coords: { lat: 22.752, lng: 121.149 } }
];

async function run() {
  const shuffled = [
    { id: 'late', capturedAt: at('11:20'), metadata: { capturedAt: at('11:20'), timeSource: 'exif', coords: { lat: 22.752, lng: 121.149 } } },
    { id: 'early', capturedAt: at('09:20'), metadata: { capturedAt: at('09:20'), timeSource: 'exif', coords: { lat: 22.765, lng: 121.157 } } }
  ];
  const sorted = manager.classifyPhotos(shuffled, { tripId: 'test-trip', stops, timeZoneOffsetMinutes: 480 });
  assert.deepEqual(sorted.map((p) => p.id), ['early', 'late'], '上傳順序不應決定相簿順序');
  assert.deepEqual(sorted.map((p) => p.stopId), ['park', 'market'], '時間和 GPS 應分到對應景點');

  const manual = manager.stableSort([
    { id: 'unassigned', capturedAt: at('08:00'), manualOrder: null },
    { id: 'chosen', capturedAt: at('12:00'), manualOrder: 1 }
  ]);
  assert.deepEqual(manual.map((p) => p.id), ['chosen', 'unassigned'], '沒有手動順位不可被視為 0');

  const missing = manager.classifyPhoto({ id: 'no-meta', metadata: { capturedAt: null, coords: null } }, { tripId: 'test-trip', stops });
  assert.equal(missing.group, 'unassigned', '完全缺時間與位置時應進待整理');
  assert.equal(missing.dayKey, null, '缺時間不可誤判為 1970-01-01');

  const weak = manager.classifyPhoto({ id: 'heic-fallback', metadata: { capturedAt: at('09:20'), timeSource: 'file-last-modified', coords: null } }, { tripId: 'test-trip', stops, timeZoneOffsetMinutes: 480 });
  assert.equal(weak.stopId, 'park', '沒有 JPEG EXIF 的格式可用檔案時間暫分組');
  assert.notEqual(weak.confidence, 'high', '檔案修改時間不可當作高信心拍攝時間');

  const fakeHeic = { name: 'IMG_1234.HEIC', type: 'image/heic', lastModified: at('09:20') };
  const heicMetadata = await manager.extractMetadata(fakeHeic);
  assert.equal(heicMetadata.timeSource, 'file-last-modified');
  assert.equal(heicMetadata.fallbackUsed, true);
  assert.ok(heicMetadata.warnings.length, '缺 EXIF 應提示使用者確認');

  console.log('照片排序／分類：6 組情境通過');
}

run().catch((error) => { console.error(error); process.exitCode = 1; });
