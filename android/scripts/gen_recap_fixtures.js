/*
 * 產生回顧短片時間軸的對照資料（App 單元測試 RecapReelsParityTest 用）。
 * 用網頁實際部署的 recap-templates.js／recap-manifest.js／recap-timeline.js 跑一遍，
 * Kotlin 版（RecapTimelines／RecapReels）要算出一模一樣的時間軸與總長。
 *
 * 用法：
 *   curl -O https://travel-link-ai.duckdns.org/recap-templates.js（manifest、timeline 同）到某個資料夾
 *   node scripts/gen_recap_fixtures.js <那個資料夾> > app/src/test/resources/recap_fixtures.json
 * 網頁的算法改了就重跑一次、再跑 App 的單元測試。
 */
'use strict';
const path = require('path');
const dir = path.resolve(process.argv[2] || '.');
const T = require(path.join(dir, 'recap-timeline.js'));

function stop(id, name, lat, lng, day, stayMin) {
  return { stopId: id, name, lat, lng, mode: 'car', stayMin: stayMin || 0, dayIndex: day || 1 };
}

function trip(title, stops, distanceKm) {
  return { title, region: '台東縣', dateLabel: '2026.10.31', people: '2人', distanceKm: distanceKm || 30, transportMode: 'car', stops };
}

// 每站 n 張照片：photoId ＝ stopId-k
function photos(stops, counts, withIds) {
  const out = [];
  counts.forEach((n, i) => {
    for (let k = 0; k < n; k += 1) {
      const p = { stopIndex: i, url: 'https://x/' + stops[i].stopId + '-' + k + '.jpg' };
      if (withIds !== false) p.photoId = stops[i].stopId + '-' + k;
      out.push(p);
    }
  });
  return out;
}

// 台東一日遊 8 站（提案「參考時間表」：照片 [1,1,0,1,1,3,1,1]、主角第 6 站）
const oneDay = [
  stop('s0', '台東糖廠文創園區', 22.7637, 121.1361, 1, 60),
  stop('s1', '卑南豬血湯', 22.7553, 121.1478, 1, 30),
  stop('s2', '鐵花村', 22.7546, 121.1501, 1, 45),
  stop('s3', 'TTstyle', 22.7569, 121.1519, 1, 40),
  stop('s4', '海濱公園', 22.7524, 121.1569, 1, 30),
  stop('s5', '台東森林公園', 22.7660, 121.1612, 1, 90),
  stop('s6', '小野柳', 22.7924, 121.1939, 1, 40),
  stop('s7', '富岡漁港', 22.7915, 121.1925, 1, 30)
];
// 兩天：第 1 天 4 站、第 2 天 4 站
const twoDay = [
  stop('d0', '台東車站', 22.7937, 121.1229, 1, 10),
  stop('d1', '鹿野高台', 22.9151, 121.1350, 1, 90),
  stop('d2', '關山親水公園', 23.0418, 121.1603, 1, 60),
  stop('d3', '池上伯朗大道', 23.1037, 121.2195, 1, 60),
  stop('d4', '池上民宿', 23.0975, 121.2110, 2, 30),
  stop('d5', '三仙台', 23.1240, 121.4100, 2, 90),
  stop('d6', '都蘭', 22.8835, 121.2350, 2, 60),
  stop('d7', '台東車站', 22.7937, 121.1229, 2, 10)
];
// 十二站（全部照片放不下 30 秒，要後端挑）
const many = [];
for (let i = 0; i < 12; i += 1) many.push(stop('m' + i, '景點' + i, 22.70 + i * 0.012, 121.10 + (i % 3) * 0.01, 1, 20 + (i * 7) % 50));

const cases = [];
function add(name, tr, ph, opts) {
  const o = Object.assign({ templateId: 'reels', photos: ph }, opts || {});
  const tl = T.buildRecommendedTimeline(tr, o);
  const c = { name, trip: tr, photos: ph, options: Object.assign({}, opts || {}, { templateId: o.templateId }), timeline: tl, totalMs: T.timelineTotalMs(tr, tl, ph), edits: [] };
  const photoClips = tl.clips.filter((x) => x.type === 'photo');
  // 移除每一個照片片段（一次一個），以及連續移除兩個
  photoClips.forEach((pc) => {
    const next = T.removePhotoClip(tl, pc.clipId);
    c.edits.push({ op: 'remove', clipId: pc.clipId, timeline: next, totalMs: T.timelineTotalMs(tr, next, ph) });
  });
  if (photoClips.length >= 3) {
    const a = T.removePhotoClip(tl, photoClips[1].clipId);
    const b = T.removePhotoClip(a, photoClips[2].clipId);
    c.edits.push({ op: 'remove2', clipIds: [photoClips[1].clipId, photoClips[2].clipId], timeline: b, totalMs: T.timelineTotalMs(tr, b, ph) });
  }
  // 主角卡片移除單張照片
  photoClips.filter((pc) => (pc.assetRefs || []).length > 1).forEach((pc) => {
    const next = T.removeClipPhoto(tl, pc.clipId, 1);
    c.edits.push({ op: 'removePhoto', clipId: pc.clipId, index: 1, timeline: next, totalMs: T.timelineTotalMs(tr, next, ph) });
  });
  // 每段 +3 秒（夾範圍）
  tl.clips.forEach((cl) => {
    const next = T.setClipDuration(tl, cl.clipId, cl.durationMs + 3000);
    c.edits.push({ op: 'duration', clipId: cl.clipId, durationMs: cl.durationMs + 3000, timeline: next, totalMs: T.timelineTotalMs(tr, next, ph) });
  });
  cases.push(c);
}

add('reference', trip('台東一日遊', oneDay, 18.4), photos(oneDay, [1, 1, 0, 1, 1, 3, 1, 1]));
add('reference15', trip('台東一日遊', oneDay, 18.4), photos(oneDay, [1, 1, 0, 1, 1, 3, 1, 1]), { lengthMode: '15' });
add('referenceHighlight', trip('台東一日遊', oneDay, 18.4), photos(oneDay, [1, 1, 0, 1, 1, 3, 1, 1]), { highlightStopId: 's3', coverStopIndex: 5 });
add('referenceNoIds', trip('台東一日遊', oneDay, 18.4), photos(oneDay, [1, 1, 0, 1, 1, 3, 1, 1], false));
add('singleEach', trip('台東一日遊', oneDay, 18.4), photos(oneDay, [0, 1, 1, 0, 1, 0, 1, 0]));
add('mapFeatured', trip('台東一日遊', oneDay, 18.4), photos(oneDay, [0, 0, 1, 0, 0, 1, 0, 0]));
add('mapFeatured15', trip('台東一日遊', oneDay, 18.4), photos(oneDay, [0, 0, 1, 0, 0, 1, 0, 0]), { lengthMode: '15' });
add('routeStory', trip('台東一日遊', oneDay, 18.4), photos(oneDay, [0, 0, 0, 0, 0, 0, 0, 0]));
add('routeStory15', trip('台東一日遊', oneDay, 18.4), [], { lengthMode: '15' });
add('twoDay', trip('池上兩天', twoDay, 120), photos(twoDay, [1, 2, 0, 1, 1, 4, 0, 1]));
add('twoDay15', trip('池上兩天', twoDay, 120), photos(twoDay, [1, 2, 0, 1, 1, 4, 0, 1]), { lengthMode: '15' });
add('twoDayFeatured', trip('池上兩天', twoDay, 120), photos(twoDay, [0, 0, 0, 1, 0, 0, 0, 0]));
add('manyAll', trip('十二站', many, 25), photos(many, [2, 3, 1, 4, 1, 1, 2, 1, 5, 1, 2, 3]));
add('many15', trip('十二站', many, 25), photos(many, [2, 3, 1, 4, 1, 1, 2, 1, 5, 1, 2, 3]), { lengthMode: '15' });
add('manyOnes', trip('十二站', many, 25), photos(many, [1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1]));
add('heroFirst', trip('台東一日遊', oneDay, 18.4), photos(oneDay, [3, 1, 1, 0, 0, 0, 0, 1]));
add('heroLast', trip('台東一日遊', oneDay, 18.4), photos(oneDay, [1, 0, 1, 0, 1, 0, 1, 2]));
// 第 1 版（標準等）：換日卡秒數改了（A10）
['standard', 'brisk', 'leisurely', 'cinematic'].forEach((id) => {
  add('v1-' + id, trip('池上兩天', twoDay, 120), photos(twoDay, [1, 1, 0, 0, 1, 1, 0, 0]), { templateId: id });
});

process.stdout.write(JSON.stringify({ generatedFrom: 'travel-link-ai.duckdns.org ' + new Date().toISOString().slice(0, 10), cases }));
