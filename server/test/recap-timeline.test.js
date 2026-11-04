// 回顧短片：節奏樣板（app/recap-templates.js）、manifest 依樣板產時間軸（app/recap-manifest.js）、
// 推薦版時間軸（app/recap-timeline.js）與後端驗證（recap-jobs.js）的離線測試。
//   node test/recap-timeline.test.js
'use strict';
const path = require('path');
const TEMPLATES = require(path.join(__dirname, '..', '..', 'app', 'recap-templates.js'));
const MANIFEST = require(path.join(__dirname, '..', '..', 'app', 'recap-manifest.js'));
const TIMELINE = require(path.join(__dirname, '..', '..', 'app', 'recap-timeline.js'));
const JOBS = require(path.join(__dirname, '..', 'recap-jobs.js'))._internals;

let pass = 0, fail = 0;
const ok = (name, cond, detail) => {
  if (cond) { pass++; console.log('PASS  ' + name); }
  else { fail++; console.log('FAIL  ' + name + (detail ? '\n      ' + detail : '')); }
};
const near = (a, b, tol = 2) => Math.abs(a - b) <= tol;

// 台東兩天：第 1 段只有 20 公尺（短程），第 3 段跨天
const trip = {
  title: '台東兩天', transportMode: 'car', distanceKm: 40,
  stops: [
    { stopId: 'a', name: '台東車站', lat: 22.7500, lng: 121.1400, dayIndex: 1 },
    { stopId: 'b', name: '鐵花村', lat: 22.7502, lng: 121.1401, dayIndex: 1 },
    { stopId: 'c', name: '森林公園', lat: 22.7700, lng: 121.1600, dayIndex: 1 },
    { stopId: 'd', name: '三仙台', lat: 23.1200, lng: 121.4000, dayIndex: 2 },
    { stopId: 'e', name: '都蘭', lat: 22.8800, lng: 121.2300, dayIndex: 2 }
  ]
};
const photos = [{ stopIndex: 1, url: 'https://x/1.jpg' }, { stopIndex: 3, url: 'https://x/3.jpg' }];
const mediaStopIndices = photos.map((p) => p.stopIndex);

// ── 樣板 ──
ok('四種樣板都在', ['standard', 'brisk', 'leisurely', 'cinematic'].every((id) => TEMPLATES.hasTemplate(id)));
ok('未知樣板退回標準', TEMPLATES.getTemplate('nope').id === 'standard');
ok('getTemplate 回傳副本（改了不影響下次）', (() => { const t = TEMPLATES.getTemplate('brisk'); t.coverMs = 1; return TEMPLATES.getTemplate('brisk').coverMs !== 1; })());

// ── manifest 依樣板 ──
const lengths = {};
['standard', 'brisk', 'leisurely', 'cinematic'].forEach((id) => {
  const m = MANIFEST.buildRecapManifest(trip, { template: id, mediaCount: 2, mediaStopIndices });
  const tpl = TEMPLATES.getTemplate(id);
  const driving = m.segments.filter((s) => !s.dayBreak).reduce((a, s) => a + s.travelMs, 0);
  const breaks = m.segments.filter((s) => s.dayBreak).reduce((a, s) => a + s.travelMs, 0);
  lengths[id] = m.timeline.totalMs;
  ok(`${id}：路線時間等於樣板目標（開車＋換日）`, near(driving + breaks, m.timeline.routeMs), `${driving}+${breaks} vs ${m.timeline.routeMs}`);
  ok(`${id}：每段開車至少 ${tpl.segment.minMs}ms`, m.segments.filter((s) => !s.dayBreak).every((s) => s.travelMs >= tpl.segment.minMs - 1));
  ok(`${id}：manifest 帶樣板 id`, m.template.id === id);
});
ok('快節奏 < 標準 < 慢遊', lengths.brisk < lengths.standard && lengths.standard < lengths.leisurely, JSON.stringify(lengths));

const cine = MANIFEST.buildRecapManifest(trip, { template: 'cinematic', mediaCount: 2, mediaStopIndices });
ok('電影感開場：有照片 → photoCut，用第一張有照片的站', cine.cover.style === 'photoCut' && cine.cover.photoStopIndex === 1);
ok('電影感開場：開場長度＝照片＋停半拍', cine.timeline.coverMs === cine.cover.photoMs + cine.cover.beatMs);
ok('電影感開場：路線等速（linear）', cine.template.segmentEase === 'linear');
const cineNoPhoto = MANIFEST.buildRecapManifest(trip, { template: 'cinematic', mediaCount: 0, mediaStopIndices: [] });
ok('電影感開場：沒有照片 → 退回標題卡開場', cineNoPhoto.cover.style === 'title');

// 站很多、路線時間不夠每段都給下限時，仍要依距離分配（不能每段一樣長）
const manyKm = [2.6, 0.8, 1, 0.6, 6.1, 6, 6.9, 0.9, 7.7, 2.9];
let lat = 22.04;
const manyStops = [{ stopId: 'm0', name: 'm0', lat, lng: 121.55 }];
manyKm.forEach((km, i) => { lat += km / 111; manyStops.push({ stopId: 'm' + (i + 1), name: 'm' + (i + 1), lat, lng: 121.55 }); });
const many = MANIFEST.buildRecapManifest({ title: 'many', stops: manyStops }, { template: 'standard' });
const longest = many.segments[8].travelMs, shortest = many.segments[3].travelMs;
ok('10 段：7.7 km 明顯比 0.6 km 長', longest > shortest * 2, `${longest} vs ${shortest}`);
ok('10 段：路線總時間仍等於目標', near(many.segments.reduce((a, s) => a + s.travelMs, 0), many.timeline.routeMs));

const legacy = MANIFEST.buildRecapManifest(trip, { coverMs: 1234, mediaCount: 0 });
ok('明確給的時間值優先於樣板', legacy.timeline.coverMs === 1234);

// ── 推薦版時間軸 ──
const tl = TIMELINE.buildRecommendedTimeline(trip, { templateId: 'standard', photos });
const types = tl.clips.map((c) => c.type).join(',');
ok('片段順序：開場 → 路線 → 照片 → … → 換日 → … → 結尾', types === 'cover,route,photo,route,timeline,photo,route,outro', types);
ok('片段 order 連號', tl.clips.every((c, i) => c.order === i));
ok('照片片段指到對的站', tl.clips.filter((c) => c.type === 'photo').map((c) => c.assetRef.stopId).join() === 'b,d');
ok('路線片段的起訖是相鄰兩站', tl.clips.filter((c) => c.type === 'route').every((c) => {
  const i = trip.stops.findIndex((s) => s.stopId === c.fromStopId);
  return trip.stops[i + 1] && trip.stops[i + 1].stopId === c.toStopId;
}));
const compiled = MANIFEST.buildRecapManifest(trip, TIMELINE.compileTimeline(trip, tl, photos));
ok('推薦版 compile 回 manifest，總長一致', near(compiled.timeline.totalMs, tl.totalMs), `${compiled.timeline.totalMs} vs ${tl.totalMs}`);
const sum = TIMELINE.summarizeTimeline(tl);
ok('清單總秒數＝時間軸總長', near(sum.totalSeconds * 1000, tl.totalMs, 60));

// 編輯過的時間軸：拉長一段路線、刪掉一張照片
const edited = JSON.parse(JSON.stringify(tl));
edited.clips.find((c) => c.type === 'route' && c.toStopId === 'c').durationMs = 4000;
edited.clips = edited.clips.filter((c) => !(c.type === 'photo' && c.assetRef.stopId === 'd'));
const eo = TIMELINE.compileTimeline(trip, edited, photos);
ok('編輯：指定的路線時長被採用', eo.segmentMs[1] === 4000);
ok('編輯：刪掉的照片不再出現', eo.mediaStopIndices.join() === '1');

// 不可信的輸入
const bad = TIMELINE.compileTimeline(trip, {
  templateId: 'nope',
  clips: [
    { type: 'route', fromStopId: 'a', toStopId: 'c', durationMs: 3000 },   // 跳站
    { type: 'route', fromStopId: 'a', toStopId: 'b', durationMs: 999999 }, // 超長
    { type: 'photo', assetRef: { stopId: 'c' }, durationMs: 3000 },         // 這站沒有照片
    { type: 'photo', assetRef: { stopId: 'b' }, durationMs: 1 },            // 太短
    { type: 'cover', durationMs: -5 }
  ]
}, photos);
ok('跳站的路線片段被忽略', bad.segmentMs[0] === TIMELINE.LIMITS.route[1] && bad.segmentMs.length === 1);
ok('沒有照片的站不能放照片片段', bad.mediaStopIndices.join() === '1');
ok('時長被夾在範圍內', bad.holdMsByStop[1] === TIMELINE.LIMITS.photo[0] && bad.coverMs === TIMELINE.LIMITS.cover[0]);

// ── 字幕 ──
ok('字幕：去換行與控制字元、最多 14 字', TIMELINE.sanitizeCaption(' 抵達\n三仙台' + String.fromCharCode(7) + '看海超級超級超級長的字幕 ') === '抵達 三仙台 看海超級超級超');
let captioned = TIMELINE.setClipCaption(tl, 'cover', '台東慢慢走');
captioned = TIMELINE.setClipCaption(captioned, 'route-0', '出發囉');
captioned = TIMELINE.setClipCaption(captioned, 'photo-1', '鐵花村的晚上');
captioned = TIMELINE.setClipCaption(captioned, 'outro', '下次再來');
ok('設定字幕會標記為已編輯、原時間軸不變', captioned.edited === true && !tl.clips[0].caption);
const capManifest = MANIFEST.buildRecapManifest(trip, TIMELINE.compileTimeline(trip, captioned, photos));
capManifest.media = [{ stopIndex: 1 }, { stopIndex: 3 }];
TIMELINE.attachCaptions(capManifest, captioned);
const caps = capManifest.captions;
ok('字幕軌：4 則、依時間排序', caps.length === 4 && caps.every((c, i) => i === 0 || c.startMs >= caps[i - 1].startMs), JSON.stringify(caps));
ok('字幕軌：開場字幕從 0 到開場結束', caps[0].startMs === 0 && caps[0].endMs === capManifest.timeline.coverMs);
const photoCap = caps.find((c) => c.text === '鐵花村的晚上');
ok('字幕軌：照片字幕對到該站的停留時間', photoCap && photoCap.startMs === Math.round(capManifest.timeline.arriveMs[1]) && photoCap.endMs - photoCap.startMs === Math.round(capManifest.timeline.holdMs[1]));
ok('字幕軌：結尾字幕到影片結束', caps[3].endMs === Math.round(capManifest.timeline.totalMs));
const forged = MANIFEST.buildRecapManifest(trip, { template: 'standard', mediaCount: 0 });
TIMELINE.attachCaptions(forged, { clips: [{ type: 'photo', assetRef: { stopId: 'b' }, caption: '沒有照片的站' }, { type: 'route', fromStopId: 'a', toStopId: 'c', caption: '跳站' }, { type: 'timeline', caption: '不能加字幕的類型' }] });
ok('字幕軌：對不回行程或不能加字幕的片段被丟掉', forged.captions.length === 0);

// ── 編輯器操作 ──
const longer = TIMELINE.setClipDuration(tl, 'route-0', 999999);
ok('調秒數：夾在上限', longer.clips.find((c) => c.clipId === 'route-0').durationMs === TIMELINE.LIMITS.route[1]);
const removed = TIMELINE.removePhotoClip(tl, 'photo-1');
ok('移除照片片段：order 重新連號', removed.clips.every((c, i) => c.order === i) && !removed.clips.some((c) => c.clipId === 'photo-1'));
ok('不能移除路線片段', TIMELINE.removePhotoClip(tl, 'route-0').clips.length === tl.clips.length);
ok('移除照片後總長變短', TIMELINE.timelineTotalMs(trip, removed, photos) < TIMELINE.timelineTotalMs(trip, tl, photos));

// ── AI 劇本建議 ──
const scripted = TIMELINE.applyScriptSuggestion(tl, {
  cover: '台東兩天慢慢走', outro: '下次見',
  clips: {
    'photo-1': { caption: '鐵花村聽音樂', emphasis: 'long' },
    'photo-3': { caption: '三仙台看日出', emphasis: 'short' },
    'route-0': { caption: '出發往市區', emphasis: 'long' },
    ghost: { caption: '不存在的片段' }
  }
});
ok('AI：字幕填進對應片段', scripted.clips.find((c) => c.clipId === 'photo-1').caption === '鐵花村聽音樂' && scripted.clips[0].caption === '台東兩天慢慢走');
ok('AI：不新增片段、不改順序', scripted.clips.map((c) => c.clipId).join() === tl.clips.map((c) => c.clipId).join());
ok('AI：long／short 只調照片片段', scripted.clips.find((c) => c.clipId === 'photo-1').durationMs > tl.clips.find((c) => c.clipId === 'photo-1').durationMs
  && scripted.clips.find((c) => c.clipId === 'photo-3').durationMs < tl.clips.find((c) => c.clipId === 'photo-3').durationMs
  && scripted.clips.find((c) => c.clipId === 'route-0').durationMs === tl.clips.find((c) => c.clipId === 'route-0').durationMs);
const kept = TIMELINE.applyScriptSuggestion(TIMELINE.setClipCaption(tl, 'cover', '我自己寫的'), { cover: 'AI 寫的開場', clips: { 'photo-1': { caption: 'AI 照片字幕' } } }, { keepExisting: true });
ok('AI：keepExisting 不覆蓋使用者打好的字幕、仍填空白的', kept.clips[0].caption === '我自己寫的' && kept.clips.find((c) => c.clipId === 'photo-1').caption === 'AI 照片字幕');
ok('AI：亂回的格式不會出錯', TIMELINE.applyScriptSuggestion(tl, 'garbage').clips.length === tl.clips.length);

// ── 後端 ──
const sTrip = JOBS.sanitizeTrip(trip);
const sPhotos = JOBS.sanitizePhotos(photos, sTrip.stops.length);
const viaTimeline = JOBS.buildManifest(sTrip, JOBS.resolveManifestOptions(sTrip, sPhotos, 'brisk', TIMELINE.buildRecommendedTimeline(sTrip, { templateId: 'brisk', photos: sPhotos })));
const direct = JOBS.buildManifest(sTrip, JOBS.resolveManifestOptions(sTrip, sPhotos, 'brisk', null));
ok('後端：送時間軸與只送樣板，結果相同', near(viaTimeline.timeline.totalMs, direct.timeline.totalMs));
ok('後端：舊版請求（沒有樣板欄位）用標準樣板', JOBS.buildManifest(sTrip, JOBS.resolveManifestOptions(sTrip, sPhotos)).template.id === 'standard');
ok('後端：不認得的樣板 id 改用標準', JOBS.resolveManifestOptions(sTrip, sPhotos, '<script>', null).template === 'standard');
let rejected = false;
try { JOBS.resolveManifestOptions(sTrip, sPhotos, 'standard', { clips: 'x' }); } catch (e) { rejected = e.status === 400; }
ok('後端：格式錯誤的時間軸回 400', rejected);

// ════ 第 2 版：Reels（IG 分享優化提案 A2、A7～A11、A19）════
// 參考時間表：台東一日遊、8 站、9 張照片（森林公園 3 張＝主角），總長 20.5 秒
const demoStops = ['糖廠', '豬血湯', '鐵花村', 'TTstyle', '地瓜酥', '森林公園', '美術館', '馬蘭車站']
  .map((name, i) => ({ stopId: 'd' + i, name, lat: 22.75 + i * 0.004, lng: 121.14 + (i % 3) * 0.003, dayIndex: 1, stayMin: 40 }));
const demoTrip = { title: '台東一日遊', distanceKm: 18, stops: demoStops };
const demoCounts = [1, 1, 0, 1, 1, 3, 1, 1];
const demoPhotos = [];
demoCounts.forEach((c, i) => { for (let k = 0; k < c; k++) demoPhotos.push({ stopIndex: i, photoId: `p${i}_${k}`, url: `data:image/png;base64,${i}${k}` }); });

ok('v2：Reels 樣板存在、是前端推薦預設；請求沒帶樣板仍是標準', TEMPLATES.hasTemplate('reels') && TEMPLATES.RECOMMENDED_ID === 'reels' && TEMPLATES.DEFAULT_ID === 'standard');
ok('v2：其他節奏的換日卡縮到 0.5～0.8 秒', ['standard', 'brisk', 'leisurely', 'cinematic'].every((id) => { const ms = TEMPLATES.getTemplate(id).dayTransitionMs; return ms >= 500 && ms <= 800; }));

const demoM = MANIFEST.buildRecapManifest(demoTrip, { template: 'reels', photoCounts: demoCounts, heroStopIndex: 5 });
const demoPhaseStr = demoM.timeline.phases.map((p) => `${p.type}@${p.startMs}`).join(' ');
ok('v2：參考時間表總長 20.5 秒', demoM.timeline.totalMs === 20500, String(demoM.timeline.totalMs));
ok('v2：參考時間表逐段秒數一致', demoPhaseStr === 'cover@0 reveal@1200 photo@2200 photo@3600 hop@5000 photo@5700 photo@7100 hop@8500 photo@9200 hop@12200 photo@12900 photo@14300 summary@15700 outro@17200', demoPhaseStr);
ok('v2：主角景點 3 張各 1 秒', demoM.media.filter((mm) => mm.stopIndex === 5).every((mm) => mm.durationMs === 1000 && mm.hero));
ok('v2：中間沒照片的站只在過場經過', demoM.timeline.phases.some((p) => p.type === 'hop' && p.fromIndex === 1 && p.toIndex === 3 && p.viaIndices.join() === '2'));
ok('v2：三種中段結構依照片數切換', MANIFEST.structureFor(0) === 'routeStory' && MANIFEST.structureFor(2) === 'mapFeatured' && MANIFEST.structureFor(3) === 'photoStory');
ok('v2：沒指定主角時挑照片最多的站', MANIFEST.buildRecapManifest(demoTrip, { template: 'reels', photoCounts: demoCounts }).heroStopIndex === 5);
ok('v2：0 張照片不設主角', MANIFEST.buildRecapManifest(demoTrip, { template: 'reels' }).heroStopIndex === -1);

const f15 = MANIFEST.buildRecapManifest(demoTrip, { template: 'reels', photoCounts: demoCounts.map((c, i) => (i === 5 ? 5 : c)), heroStopIndex: 5, lengthMode: '15' });
const perStop15 = {};
f15.media.forEach((mm) => { perStop15[mm.stopIndex] = (perStop15[mm.stopIndex] || 0) + 1; });
ok('v2：固定 15 秒總長剛好 15 秒', f15.timeline.totalMs === 15000, String(f15.timeline.totalMs));
ok('v2：固定 15 秒主角最多 3 張、其他站 1 張', perStop15[5] === 3 && Object.keys(perStop15).every((k) => k === '5' || perStop15[k] === 1), JSON.stringify(perStop15));
const many2 = MANIFEST.buildRecapManifest(demoTrip, { template: 'reels', photoCounts: demoCounts.map(() => 8) });
ok('v2：放進全部照片有上限（30 秒）', many2.timeline.totalMs <= 30000, String(many2.timeline.totalMs));

// 多日：不出換日卡，在當天第一張照片標「第 N 天」
const twoDay = { title: '兩天', stops: demoStops.map((s, i) => Object.assign({}, s, { dayIndex: i < 4 ? 1 : 2 })) };
const twoDayM = MANIFEST.buildRecapManifest(twoDay, { template: 'reels', photoCounts: demoCounts });
ok('v2：多日行程沒有換日卡', twoDayM.timeline.dayBreaks.length === 0 && !twoDayM.timeline.phases.some((p) => p.type === 'timeline'));
ok('v2：第 1、2 天的第一張照片帶天數標籤', twoDayM.timeline.dayLabels.map((d) => d.stopIndex + ':' + d.day).join() === '0:1,4:2', JSON.stringify(twoDayM.timeline.dayLabels));

// 推薦時間軸第 2 版
const v2 = TIMELINE.buildRecommendedTimeline(demoTrip, { templateId: 'reels', photos: demoPhotos, highlightStopId: 'd5' });
ok('v2：schemaVersion 2、帶 lengthMode 與結構', v2.schemaVersion === 2 && v2.lengthMode === 'all' && v2.structure === 'photoStory');
ok('v2：片段順序', v2.clips.map((c) => c.type).join() === 'cover,route,photo,photo,route,photo,photo,route,photo,route,photo,photo,summary,outro', v2.clips.map((c) => c.type).join());
const heroClip = v2.clips.find((c) => c.hero);
ok('v2：主角景點一張卡片、3 個 assetRefs', heroClip && heroClip.assetRefs.length === 3 && heroClip.assetRefs[2].photoId === 'p5_2');
ok('v2：跨站路線帶 viaStopIds', v2.clips.some((c) => c.clipId === 'route-1-3' && c.viaStopIds.join() === 'd2'));
ok('v2：封面預設行程插圖', v2.clips[0].source === 'illustration');
ok('v2：compile 回 manifest 總長一致', TIMELINE.timelineTotalMs(demoTrip, v2, demoPhotos) === v2.totalMs);
ok('v2：清單秒數加總＝總長', near(v2.clips.reduce((a, c) => a + c.durationMs, 0), v2.totalMs));
ok('v1 樣板仍產第 1 版', TIMELINE.buildRecommendedTimeline(demoTrip, { templateId: 'standard', photos: demoPhotos }).schemaVersion === 1);

// 驗證：via 不對的路線片段不採用（改用預設過場秒數），亂序的照片片段丟掉
const badV2 = JSON.parse(JSON.stringify(v2));
badV2.clips.find((c) => c.clipId === 'route-1-3').viaStopIds = ['d4'];
badV2.clips.find((c) => c.clipId === 'route-1-3').durationMs = 5000;
const badOpts = TIMELINE.compileTimeline(demoTrip, badV2, demoPhotos);
ok('v2 驗證：viaStopIds 不是中間的站 → 不採用', badOpts.anchorPlan.find((p) => p.stopIndex === 3).hopMs === undefined);
const outOfOrder = JSON.parse(JSON.stringify(v2));
const p7 = outOfOrder.clips.find((c) => c.clipId === 'photo-7');
outOfOrder.clips.splice(outOfOrder.clips.indexOf(p7), 1);
outOfOrder.clips.splice(2, 0, p7);
ok('v2 驗證：照片片段不能倒著排', TIMELINE.compileTimeline(demoTrip, outOfOrder, demoPhotos).mediaStopIndices.join() === '7');
const forgedRef = JSON.parse(JSON.stringify(v2));
forgedRef.clips.find((c) => c.clipId === 'photo-2' || c.clipId === 'photo-1').assetRefs = [{ kind: 'memory_photo', stopId: 'd2' }];
ok('v2 驗證：沒有照片的站不能放照片片段', TIMELINE.compileTimeline(demoTrip, forgedRef, demoPhotos).photoCounts[2] === 0);

// 編輯器：移除照片後路線自動重接，片段秒數加總仍＝實際總長
[['photo-0'], ['photo-3'], ['photo-1'], ['photo-7'], ['photo-5', 'photo-6'], ['photo-0', 'photo-3']].forEach((ids) => {
  let t = v2;
  ids.forEach((id) => { t = TIMELINE.removePhotoClip(t, id); });
  const sumMs = t.clips.reduce((a, c) => a + c.durationMs, 0);
  ok(`v2 編輯：移除 ${ids.join('+')} 後路線重接、總長一致`, near(sumMs, TIMELINE.timelineTotalMs(demoTrip, t, demoPhotos)) && !t.clips.some((c) => c.marker), `${sumMs} vs ${TIMELINE.timelineTotalMs(demoTrip, t, demoPhotos)}`);
});
const lessHero = TIMELINE.removeClipPhoto(v2, heroClip.clipId, 1);
ok('v2 編輯：主角卡片個別移除照片', TIMELINE.compileTimeline(demoTrip, lessHero, demoPhotos).photoSelection[5].join() === '0,2'
  && lessHero.clips.find((c) => c.hero).durationMs === 2000);

// 字幕軌：時間取自 phases
let capV2 = TIMELINE.setClipCaption(v2, 'route-1-3', '往市區');
capV2 = TIMELINE.setClipCaption(capV2, 'summary', '一天走完');
capV2 = TIMELINE.setClipCaption(capV2, heroClip.clipId, '森林公園');
const capM = MANIFEST.buildRecapManifest(demoTrip, TIMELINE.compileTimeline(demoTrip, capV2, demoPhotos));
TIMELINE.attachCaptions(capM, capV2);
ok('v2 字幕：跨站路線、主角、收束各一則，時間對到段落', JSON.stringify(capM.captions) === JSON.stringify([
  { startMs: 5000, endMs: 5700, text: '往市區' }, { startMs: 9200, endMs: 12200, text: '森林公園' }, { startMs: 15700, endMs: 17200, text: '一天走完' }
]), JSON.stringify(capM.captions));

// 後端：新欄位
const sDemo = JOBS.sanitizeTrip(Object.assign({}, demoTrip, {
  costPerPerson: 1350.4, highlightStopId: 'd5',
  stops: demoStops.map((s, i) => Object.assign({}, s, { arriveTime: i === 0 ? '09:30' : (i === 1 ? '25:00' : '') }))
}));
ok('後端：每人花費、最推薦的一站、抵達時間', sDemo.costPerPerson === 1350 && sDemo.highlightStopId === 'd5' && sDemo.stops[0].arriveTime === '09:30' && sDemo.stops[1].arriveTime === '');
ok('後端：不是這趟行程的最推薦站被丟掉', JOBS.sanitizeTrip(Object.assign({}, demoTrip, { highlightStopId: 'nope' })).highlightStopId === '');
const sDemoPhotos = JOBS.sanitizePhotos(demoPhotos.concat([demoPhotos[0]]), sDemo.stops.length);
ok('後端：同站多張照片照收、重複網址去掉', sDemoPhotos.length === 9 && sDemoPhotos.filter((p) => p.stopIndex === 5).length === 3);
ok('後端：照片總數上限 24', JOBS.sanitizePhotos(Array.from({ length: 40 }, (_, k) => ({ stopIndex: k % 8, url: 'data:image/png;base64,' + k })), 8).length === 24);
const reelsM = JOBS.buildManifest(sDemo, JOBS.resolveManifestOptions(sDemo, sDemoPhotos, 'reels', null, { lengthMode: 'all' }));
ok('後端：只送樣板也照參考時間表排、主角取 highlightStopId', reelsM.timeline.totalMs === 20500 && reelsM.heroStopIndex === 5);
ok('後端：送第 2 版時間軸與只送樣板結果相同', JOBS.buildManifest(sDemo, JOBS.resolveManifestOptions(sDemo, sDemoPhotos, 'reels', v2, {})).timeline.totalMs === reelsM.timeline.totalMs);
ok('後端：lengthMode 亂填 → all', JOBS.resolveManifestOptions(sDemo, sDemoPhotos, 'reels', null, { lengthMode: '999' }).lengthMode === 'all');
ok('後端：畫面風格白名單（未完成的先用基本）', JOBS.normalizeLook('film').requested === 'film' && JOBS.normalizeLook('film').look === 'basic' && JOBS.normalizeLook('<x>').requested === 'basic');
const deco = JOBS.decorateManifest(reelsM, sDemo, sDemoPhotos, { coverStopIndex: 3, look: 'basic' });
ok('後端：自選封面照片、片尾網址來自伺服器設定', reelsM.cover.source === 'photo' && reelsM.cover.photoStopIndex === 3 && deco.coverPhotoUrl === sDemoPhotos.find((p) => p.stopIndex === 3).url && /duckdns/.test(reelsM.brand.webUrl));
ok('後端：攻略卡最推薦的一站名稱', reelsM.trip.highlightName === '森林公園' && reelsM.trip.costPerPerson === 1350);

console.log(`\n${pass} passed, ${fail} failed`);
process.exit(fail ? 1 : 0);
