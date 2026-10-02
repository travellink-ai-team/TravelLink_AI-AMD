const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const path = require('node:path');
const source = fs.readFileSync(path.join(__dirname, '../app/ai-travel-planner-v8.js'), 'utf8');
const fn = source.slice(source.indexOf('  async function logTripEvent('), source.indexOf('  function ensureGeminiApiKey('));

async function run(data, options = {}) {
  const writes = [], warnings = [];
  const user = { uid: 'user-1', email: 'member@test.invalid' };
  const context = {
    firebaseEnabled: true, firebaseAuth: { currentUser: options.guest ? null : user },
    currentItineraryId: 'trip-1', currentTripTitle: 'Test', tripSessionId: 'session-1',
    firebase: { firestore: { FieldValue: { serverTimestamp: () => 'timestamp' } } },
    console: { warn: (...args) => warnings.push(args) },
    firebaseDb: { collection(name) {
      if (name === 'micro_trips') return { doc: () => ({ get: async () => {
        if (options.denied) throw { code: 'permission-denied' };
        if (options.switchAccount) context.firebaseAuth.currentUser = { uid: 'user-2' };
        return { exists: !!data, data: () => data };
      } }) };
      return { doc: () => ({ collection: () => ({ add: async event => writes.push(event) }) }) };
    } }
  };
  vm.runInNewContext(fn + ';this.runEvent = logTripEvent;', context);
  await context.runEvent('session_started', { itinerary: [] });
  return { writes, warnings };
}

test('owner UID, legacy owner email, and member can log events', async () => {
  for (const data of [{ ownerUid: 'user-1' }, { userEmail: 'member@test.invalid' },
    { ownerEmail: 'member@test.invalid' }, { memberEmails: ['member@test.invalid'] }]) {
    const result = await run(data);
    assert.equal(result.writes.length, 1);
    assert.equal(result.writes[0].tripId, 'trip-1');
    assert.equal(result.warnings.length, 0);
  }
});
test('missing cloud trip, nonmember, denied read, and guest never write', async () => {
  for (const [data, options] of [[null, {}], [{ ownerUid: 'someone-else' }, {}],
    [null, { denied: true }], [{ ownerUid: 'user-1' }, { guest: true }]]) {
    const result = await run(data, options);
    assert.equal(result.writes.length, 0);
    assert.equal(result.warnings.length, 0);
  }
});
test('account switch during cloud check cannot write under the new identity', async () => {
  const result = await run({ ownerUid: 'user-1' }, { switchAccount: true });
  assert.equal(result.writes.length, 0);
});
