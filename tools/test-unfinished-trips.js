const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const source = fs.readFileSync(require('node:path').join(__dirname, '../app/ai-travel-explore-final.js'), 'utf8');
const helpers = source.slice(source.indexOf('function isUnfinishedPersonalTrip('), source.indexOf('function renderMyTrips('));
function setup(trip, running = false) {
  const calls = [], saved = [];
  const ctx = { myTrips: [trip], isGeneratingTrip: running, wizData: { dest: 'unrelated' },
    localStorage: { setItem: (key, value) => saved.push([key, JSON.parse(value)]) },
    renderWizard() {}, reopenWizGen() {}, showWizGenProgress() {}, showToast() {},
    _doGeneration: (t, prefs) => calls.push({ t, prefs }) };
  vm.runInNewContext(helpers, ctx);
  return { ctx, calls, saved };
}
test('resume uses the same draft ID and saved preferences, including custom title', () => {
  const trip = { id: 'draft', customTitle: true, title: 'My title', stops: [], wizardData: { dest: '台東', startTime: '10:00' } };
  const { ctx, calls, saved } = setup(trip);
  ctx.resumePersonalTripGeneration('draft');
  assert.equal(calls.length, 1);
  assert.equal(calls[0].t, trip);
  assert.equal(calls[0].prefs.dest, '台東');
  assert.equal(calls[0].prefs.startTime, '10:00');
  assert.equal(saved[0][1].tripId, 'draft');
  assert.equal(trip.title, 'My title');
});
test('completed itinerary and collab lobby cannot be regenerated through draft action', () => {
  for (const trip of [{ id: 't', wizardData: {}, stops: [{ name: 'keep' }] },
    { id: 't', wizardData: {}, stops: [], collab: true }]) {
    const { ctx, calls, saved } = setup(trip);
    ctx.resumePersonalTripGeneration('t');
    assert.equal(calls.length, 0);
    assert.equal(saved.length, 0);
  }
});
test('concurrent generation preserves existing wizard state and pending job', () => {
  const { ctx, calls, saved } = setup({ id: 't', wizardData: {}, stops: [] }, true);
  ctx.resumePersonalTripGeneration('t');
  assert.equal(calls.length, 0);
  assert.equal(saved.length, 0);
  assert.equal(ctx.wizData.dest, 'unrelated');
});
