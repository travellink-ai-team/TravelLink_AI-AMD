const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const source = fs.readFileSync(require('node:path').join(__dirname, '../app/ai-travel-explore-final.js'), 'utf8');
const functions = source.slice(source.indexOf('function sanitizeTripForFirestore('), source.indexOf('function sanitizeFirebaseDocId('));
test('save removes nested undefined without dropping stops, falsy values, or SDK types', async () => {
  let saved;
  const ctx = vm.createContext({ firebaseEnabled: true,
    firebaseDb: { collection: () => ({ doc: () => ({ set: async data => { saved = data; } }) }) },
    currentUser: { email: 'test@example.invalid' },
    serializeTripForStorage: t => t,
    firebase: { firestore: { FieldValue: { serverTimestamp: () => 'SERVER_TIME' } } },
    console: { log() {}, warn() {} } });
  vm.runInContext(functions + `
    class Sentinel {}
    const marker = new Sentinel();
    const trip = {id:'test', title:'saved', role:'owner', optional:undefined,
      stops:[{name:'stop', hours:undefined, stayMin:0, verified:false,
        nested:{empty:'', missing:undefined}, values:[undefined, null, 0]}], marker};
    this.trip = trip; this.marker = marker;
  `, ctx);
  assert.equal(await vm.runInContext('saveMicroTripToFirebase(trip)', ctx), true);
  assert.equal(Object.hasOwn(saved, 'optional'), false);
  assert.equal(Object.hasOwn(saved, 'role'), false);
  assert.equal(Object.hasOwn(saved.stops[0], 'hours'), false);
  assert.equal(saved.stops[0].stayMin, 0);
  assert.equal(saved.stops[0].verified, false);
  assert.equal(saved.stops[0].nested.empty, '');
  assert.equal(saved.stops[0].values[0], null);
  assert.equal(saved.marker, ctx.marker);
  assert.equal(Object.hasOwn(ctx.trip.stops[0], 'hours'), true, 'source is not mutated');
});

test('background persistence reports both successful and exhausted saves', async () => {
  const fn = source.slice(source.indexOf('function persistMicroTripInBackground('), source.indexOf('// === Gen Panel helpers'));
  for (const success of [true, false]) {
    let attempts = 0;
    const ctx = { firebaseEnabled: true, saveMicroTripToFirebase: async () => { attempts++; return success; },
      saveState() {}, renderSideMyTrips() {}, renderMyTrips() {}, showToast() {},
      setTimeout: cb => cb(), console: { warn() {} } };
    vm.runInNewContext(fn, ctx);
    assert.equal(await ctx.persistMicroTripInBackground({}), success);
    assert.equal(attempts, success ? 1 : 3);
  }
});
