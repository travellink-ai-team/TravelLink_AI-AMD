/* 已走路線變灰：以模擬 GPS 驗證路徑投影與灰／彩色切段。 */
'use strict';

const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const source = fs.readFileSync(path.join(__dirname, '..', 'app', 'ai-travel-planner-v8.js'), 'utf8');
function extract(name) {
  const match = new RegExp(`\\bfunction ${name}\\(`).exec(source);
  if (!match) throw new Error(`找不到 ${name}`);
  const start = match.index;
  let i = source.indexOf('{', start);
  let depth = 0;
  for (; i < source.length; i++) {
    if (source[i] === '{') depth++;
    else if (source[i] === '}' && --depth === 0) return source.slice(start, i + 1);
  }
  throw new Error(`${name} 大括號未配對`);
}

const context = {
  measureDistanceMeters(a, b) {
    const lat = (b.lat - a.lat) * 111320;
    const lng = (b.lng - a.lng) * 111320 * Math.cos(a.lat * Math.PI / 180);
    return Math.hypot(lat, lng);
  }
};
vm.createContext(context);
['routePointLiteral', 'prepareStagePath', 'pointAlongStage', 'nearestStageProgress', 'splitStagePath']
  .forEach((name) => vm.runInContext(extract(name), context));

const stage = {};
context.prepareStagePath(stage, [
  { lat: 22.75, lng: 121.15 },
  { lat: 22.75, lng: 121.16 },
  { lat: 22.75, lng: 121.17 }
]);
assert.ok(stage.pathLengthMeters > 1000);

const start = context.splitStagePath(stage, 0);
assert.equal(start.completed.length, 0, '未出發不可顯示灰線');
assert.equal(start.remaining.length, 3);

const middleGps = { lat: 22.75, lng: 121.155 };
const progress = context.nearestStageProgress(stage, middleGps);
assert.ok(progress > 0.2 && progress < 0.3, 'GPS 應投影到第一段中點附近');
const middle = context.splitStagePath(stage, progress);
assert.ok(middle.completed.length >= 2 && middle.remaining.length >= 2, '灰線與彩色線須在 GPS 附近接合');
assert.deepEqual(middle.completed.at(-1), middle.remaining[0], '兩色路線不可留白縫');

assert.equal(context.nearestStageProgress(stage, { lat: 22.80, lng: 121.155 }), null, '遠離路線的 GPS 不應推進進度');

const end = context.splitStagePath(stage, 1);
assert.equal(end.completed.length, 3, '完成後全段應變灰');
assert.equal(end.remaining.length, 0, '完成後不應留下彩色線');
console.log('路線進度：起點／中途 GPS／偏離路線／終點測試通過');
