// Run with: node tools/test-two-day-wizard-time.js
// Exercises the real wizard time functions without Firebase, Maps, or a browser.
'use strict';

const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const source = fs.readFileSync(path.join(__dirname, '..', 'app', 'ai-travel-explore-final.js'), 'utf8');
function sourceOf(name) {
  const start = source.indexOf(`function ${name}(`);
  if (start < 0) throw new Error(`Missing ${name}`);
  const next = source.indexOf('\nfunction ', start + 9);
  return source.slice(start, next < 0 ? source.length : next);
}

const names = [
  'parseDurationMinutes', 'isLongTrip', 'getWizardDayCount', 'calcTripEndTime',
  'getLastDayStartTime', 'getDay2StartTime', 'reconcileDay2StartTime', 'getTripDayWindows', 'getTripActiveMinutes', 'hasValidMultiDayWindow', 'timeStringToMinutes',
  'minutesToTimeString', 'normalizeClockInput', 'buildAutoDelayedPreviewTimes',
  'buildPreviewBaseTimes', 'reconcilePreviewCustomTimes', 'applyTripPlanningRules', 'updatePreviewNodeTime',
  'updateWizardDays', 'setTripDurationMode', 'setDay1Hours', 'setDay2StartTime', 'setDay2EndTime', 'pickWizStartTime',
  'pickPreviewNodeTime', 'getWizardPlanCacheKey', 'setWizardPreviewBanner', 'getWizardPreviewStatusText', 'scheduleWizardPreviewRequest', 'wizNext'
];
const context = vm.createContext({
  getDefaultSlotMinutes: () => 45,
  getMaxSlotMinutes: () => 45,
  getDefaultTransitHub: () => '台東車站',
  clampPreviewStopsByDuration: () => 4,
  buildInterestDrivenStops: () => [
    {title:'出發',desc:''}, {title:'景點 A',desc:''},
    {title:'景點 B',desc:''}, {title:'返回',desc:''}
  ],
  updateFlowParkingNote: () => {},
  getCachedWizardPreviewPlan: () => null,
  escapeHtml: (value) => String(value).replace(/&/g, '&amp;').replace(/</g, '&lt;'),
  scheduleWizardPreviewRequest: () => {},
  autoUpdateReturnDate: () => {},
  getSafeSlotMinutes: () => 45,
  normalizeGeneratedStop: (stop, index) => ({ ...stop, order: index + 1, duration: stop.duration || 30 }),
  extractDayHoursWindow: (hours) => hours || null,
  stopServiceDate: () => ''
});
vm.runInContext(names.map(sourceOf).join('\n'), context);
const run = (expression) => vm.runInContext(expression, context);

assert.equal(run("isLongTrip('兩天一夜')"), true);
assert.equal(run("timeStringToMinutes(minutesToTimeString(3000))"), 3000);
assert.deepEqual(Array.from(run("buildPreviewBaseTimes(4, {days:'2天',startTime:'09:00',day1Hours:8,day2EndTime:'12:00'})")), [
  '09:00', '17:00', '次日 09:00', '次日 12:00'
]);
assert.deepEqual(Array.from(run("buildPreviewBaseTimes(2, {days:'2天',startTime:'09:00',day1Hours:8,day2EndTime:'12:00'})")), [
  '09:00', '次日 12:00'
]);
assert.deepEqual(Array.from(run("buildPreviewBaseTimes(4, {days:'8小時',startTime:'09:00'})")), [
  '09:00', '11:40', '14:20', '17:00'
]);
assert.deepEqual(Array.from(run("buildAutoDelayedPreviewTimes(['09:00','17:00','次日 09:00','次日 12:00'], {'2':'10:00'}, 45)")), [
  '09:00', '17:00', '次日 10:00', '次日 12:00'
]);
assert.deepEqual(Array.from(run("buildAutoDelayedPreviewTimes(['09:00','17:00','次日 09:00','次日 12:00'], {'1':'09:00','2':'08:00','3':'08:00'}, 45)")), [
  '09:00', '09:45', '次日 08:00', '次日 08:45'
]);
assert.deepEqual(Array.from(run("getTripDayWindows({days:'2天',startTime:'14:00',day1Hours:8,day2EndTime:'12:00'}).dayWindows")).map(({day,start,end}) => ({day,start,end})), [
  {day:1,start:'14:00',end:'22:00'},
  {day:2,start:'09:00',end:'12:00'}
]);
assert.equal(run("getTripActiveMinutes({days:'2天',startTime:'09:00',day1Hours:8,day2EndTime:'12:00'})"), 660);
assert.equal(run("getTripActiveMinutes({days:'2天',startTime:'14:00',day1Hours:8,day2EndTime:'11:00'})"), 600);
assert.equal(run("getTripActiveMinutes({days:'2天',startTime:'09:00',day1Hours:8,day2StartTime:'10:00',day2EndTime:'12:00'})"), 600);
assert.equal(run("getTripDayWindows({days:'2天',startTime:'09:00',day1Hours:8,day2StartTime:'10:00',day2EndTime:'12:00'}).day2Start"), '10:00');
assert.equal(run("hasValidMultiDayWindow({days:'2天',startTime:'09:00',day1Hours:8,day2StartTime:'12:00',day2EndTime:'12:00'})"), false);
assert.equal(run("hasValidMultiDayWindow({days:'2天',startTime:'23:00',day1Hours:12,day2StartTime:'09:00',day2EndTime:'12:00'})"), false);
vm.runInContext(sourceOf('renderWizardDurationField'), context);
context.wizData = {days:'2天',startTime:'09:00',day1Hours:8,day2StartTime:'10:00',day2EndTime:'12:00'};
assert.match(run('renderWizardDurationField()'), /第二天出發時間[\s\S]*10:00/);
assert.match(run('renderWizardDurationField()'), /整體旅行時間/);
assert.match(sourceOf('renderWizard'), /wizStep===1[\s\S]*日期 & 遊玩時間/);
assert.match(sourceOf('renderWizard'), /wizStep===2[\s\S]*交通 & 旅遊偏好/);
assert.equal(run("hasValidMultiDayWindow({days:'2天',startTime:'23:00',day1Hours:12,day2EndTime:'12:00'})"), true);
assert.equal(run("getTripDayWindows({days:'2天',startTime:'23:00',day1Hours:12,day2EndTime:'12:00'}).day2Start"), '11:00');
assert.equal(run("hasValidMultiDayWindow({days:'2天',startTime:'23:00',day1Hours:12,day2EndTime:'10:00'})"), false);
assert.equal(run("hasValidMultiDayWindow({days:'2天',startTime:'09:00',day1Hours:8,day2EndTime:'00:00'})"), false);
assert.notEqual(
  run("getWizardPlanCacheKey({dest:'台東',days:'2天',startTime:'09:00',day1Hours:8,day2EndTime:'12:00'}, 'preview')"),
  run("getWizardPlanCacheKey({dest:'台東',days:'2天',startTime:'10:00',day1Hours:8,day2EndTime:'12:00'}, 'preview')")
);
assert.notEqual(
  run("getWizardPlanCacheKey({dest:'台東',days:'2天',startTime:'09:00',day1Hours:8,day2EndTime:'12:00'}, 'preview')"),
  run("getWizardPlanCacheKey({dest:'台東',days:'2天',startTime:'09:00',day1Hours:8,day2EndTime:'13:00'}, 'preview')")
);
assert.notEqual(
  run("getWizardPlanCacheKey({dest:'台東',days:'2天',day2StartTime:'09:00'}, 'preview')"),
  run("getWizardPlanCacheKey({dest:'台東',days:'2天',day2StartTime:'10:00'}, 'preview')")
);
assert.notEqual(
  run("getWizardPlanCacheKey({dest:'台東',transportMode:'car'}, 'preview')"),
  run("getWizardPlanCacheKey({dest:'台東',transportMode:'taxi'}, 'preview')")
);
assert.equal(run("getWizardPreviewStatusText('step2-days')"), 'AI 正在依照「旅行天數」更新景點預覽…');
assert.doesNotMatch(run("getWizardPreviewStatusText('step2-days', true)"), /step\d|已接收|字/);
context.wizData = {dest:'台東',days:'2天',startTime:'09:00'};
context.wizardPreviewDebounceTimer = null;
context.wizardPreviewRequestKey = '';
context.setTimeout = () => 1;
context.clearTimeout = () => {};
context.requestWizardPreviewInBackground = () => {};
run("scheduleWizardPreviewRequest('test')");
const oldPreviewKey = context.wizardPreviewRequestKey;
context.wizData.startTime = '10:00';
run("scheduleWizardPreviewRequest('test')");
assert.notEqual(context.wizardPreviewRequestKey, oldPreviewKey);
assert.deepEqual(Array.from(run("applyTripPlanningRules([{dayIndex:1,duration:60},{dayIndex:1,duration:60},{dayIndex:2,duration:30},{dayIndex:2,duration:30}], {days:'2天',startTime:'09:00',day1Hours:8,day2EndTime:'12:00'})")).map((s) => s.time), [
  '09:00', '10:00', '次日 09:00', '次日 09:30'
]);
assert.deepEqual(Array.from(run("applyTripPlanningRules([{dayIndex:1,duration:30},{dayIndex:2,duration:30,businessHours:{open:570,close:1020}}], {days:'2天',startTime:'09:00',day1Hours:8,day2EndTime:'12:00'})")).map((s) => s.time), [
  '09:00', '次日 09:30'
]);

const elements = Object.fromEntries(['themeNote','flowTitle','flowNodes','flowSummary','wizardPreviewStatus'].map((id) => [id, {textContent:'',innerHTML:'',dataset:{},hidden:false}]));
let toast = '', finished = false;
context.showToast = (message) => { toast = message; };
context.document = { getElementById: (id) => elements[id] };
context.wizData = {dest:'台東',days:'2天',startTime:'09:00',day1Hours:8,day2EndTime:'12:00',people:'2人',pace:'平衡'};
vm.runInContext(sourceOf('renderFlowPreview'), context);
context.renderWizard = () => run('renderFlowPreview()');
run('renderFlowPreview()');
assert.deepEqual(Array.from(context.wizData.previewTimes), ['09:00','17:00','次日 09:00','次日 12:00']);
assert.match(elements.flowNodes.innerHTML, /次日 09:00/);
assert.match(elements.flowNodes.innerHTML, /過夜<\/span><strong>第 2 天/);
assert.doesNotMatch(elements.flowNodes.innerHTML, /次日 01:00/);
assert.match(elements.flowSummary.textContent, /第二天約 09:00–12:00/);
run("updatePreviewNodeTime(1, '23:00')");
assert.match(toast, /超過當日/);
assert.equal(context.wizData.customNodeTimes['1'], undefined);
run("updatePreviewNodeTime(1, '16:00')");
run('setDay1Hours(4)');
assert.equal(context.wizData.customNodeTimes['1'], undefined);
assert.equal(context.wizData.previewTimes[1], '13:00');
run('setDay1Hours(8)');
run("updatePreviewNodeTime(2, '10:00')");
assert.deepEqual(Array.from(context.wizData.previewTimes), ['09:00','17:00','次日 10:00','次日 12:00']);
assert.equal(context.wizData.day2StartTime, '10:00');
run("setDay2StartTime('08:00')");
assert.equal(context.wizData.previewTimes[2], '次日 08:00');
run("setDay2StartTime('10:00')");
assert.equal(context.wizData.previewTimes[2], '次日 10:00');
run("setDay2StartTime('14:00')");
assert.equal(context.wizData.day2StartTime, '10:00');
assert.match(toast, /第二天出發時間/);
run("updatePreviewNodeTime(3, '13:00')");
assert.equal(context.wizData.day2EndTime, '13:00');
assert.equal(context.wizData.previewTimes[3], '次日 13:00');
run("setDay2EndTime('12:00')");
assert.equal(context.wizData.previewTimes[3], '次日 12:00');
assert.equal(context.wizData.previewTimes[2], '次日 10:00');
run("setDay2EndTime('09:30')");
assert.equal(context.wizData.day2StartTime, '');
assert.equal(context.wizData.customNodeTimes['2'], undefined);
assert.equal(context.wizData.previewTimes[3], '次日 09:30');
run("setDay2EndTime('12:00')");
run("updatePreviewNodeTime(2, '10:00')");
run("updatePreviewNodeTime(3, '10:00')");
assert.equal(context.wizData.day2EndTime, '10:45');
assert.equal(context.wizData.previewTimes[3], '次日 10:45');
run("setDay2EndTime('12:00')");
let pickerValue = '';
context.WAIPicker = {openTime: ({value}) => { pickerValue = value; }};
context.window = {WAIPicker: context.WAIPicker};
run("pickPreviewNodeTime(2, '次日 10:00')");
assert.equal(pickerValue, '10:00');
context.WAIPicker = {openTime: ({onSet}) => onSet('11:00')};
context.window = {WAIPicker: context.WAIPicker};
run('pickWizStartTime()');
assert.equal(context.wizData.previewTimes[0], '11:00');
assert.equal(context.wizData.previewTimes[1], '19:00');
assert.equal(context.wizData.previewTimes[2], '次日 10:00');
run("setTripDurationMode('single')");
assert.equal(context.wizData.customNodeTimes['2'], undefined);
assert.equal(context.wizData.previewTimes[0], '11:00');

context.finishWizard = () => { finished = true; };
context.isGeneratingTrip = false;
context.WIZ_TOTAL = 4;
context.wizData = {dest:'台東',days:'2天',startTime:'09:00',day1Hours:8,day2EndTime:'12:00',people:'2人'};
context.wizStep = 0;
run('wizNext()');
assert.equal(context.wizStep, 1);
run('wizNext()');
assert.equal(context.wizStep, 1);
assert.match(toast, /出發日期/);
context.wizData.departureDate = '2026-10-01';
run('wizNext()');
assert.equal(context.wizStep, 2);
run('wizNext()');
assert.equal(context.wizStep, 3);
run('wizNext()');
assert.equal(finished, true);
context.wizStep = 0;
context.wizData = {dest:'台東',days:'2天',startTime:'23:00',day1Hours:12,day2EndTime:'10:00'};
run('wizNext()');
assert.equal(context.wizStep, 0);
assert.match(toast, /重疊/);

context.wizData = {dest:'台東',days:'2天'};
vm.runInContext(sourceOf('renderAiSkeletonPreview'), context);
run("renderAiSkeletonPreview({stops:[{name:'出發',time:'09:00',dayIndex:1},{name:'景點 A',time:'17:00',dayIndex:1},{name:'景點 B',time:'09:00',dayIndex:2},{name:'返回',time:'12:00',dayIndex:2}]})");
assert.match(elements.flowNodes.innerHTML, /第 2 天/);
assert.match(elements.flowNodes.innerHTML, /次日 09:00/);
assert.equal(elements.wizardPreviewStatus.hidden, false);
assert.match(elements.wizardPreviewStatus.textContent, /預覽已更新/);
assert.equal(elements.wizardPreviewStatus.dataset.state, 'done');
run("renderAiSkeletonPreview({stops:[{name:'出發',time:'09:00'},{name:'景點 A',time:'17:00'},{name:'景點 B',time:'09:00'},{name:'返回',time:'12:00'}]})");
assert.match(elements.flowNodes.innerHTML, /次日 09:00/);
run("renderAiSkeletonPreview({stops:[{name:'出發',time:'09:00'},{name:'景點 A',time:'17:00'},{name:'景點 B',time:'次日 09:00'},{name:'返回',time:'次日 12:00'}]})");
assert.match(elements.flowNodes.innerHTML, /第 2 天/);

// Production uses the Vertex proxy with no browser Gemini key. It must still request live names.
vm.runInContext(sourceOf('requestWizardPreviewInBackground'), context);
let previewRequests = 0;
context.getWizardDestination = () => '台東';
context.getVertexConfig = () => ({ready:true});
context.getGeminiApiKey = () => '';
context.setWizardStreamingHint = () => {};
context.requestGeminiMicroTravelPlan = () => { previewRequests++; return Promise.resolve({stops:[{name:'真實景點',time:'09:00'}]}); };
context.wizData = {dest:'台東',days:'2天',startTime:'09:00',day1Hours:8,day2StartTime:'10:00',day2EndTime:'12:00'};
context.wizardPreviewCacheKey = '';
context.wizardPreviewPlan = null;
run("requestWizardPreviewInBackground('test')");
assert.equal(previewRequests, 1);
context.getVertexConfig = () => ({ready:false});
run("requestWizardPreviewInBackground('test')");
assert.equal(previewRequests, 1);

console.log('Two-day wizard time checks passed');
