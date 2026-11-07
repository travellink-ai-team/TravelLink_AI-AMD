const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const path = require('node:path');

const source = fs.readFileSync(path.join(__dirname, '../app/ai-travel-explore-final.js'), 'utf8');
const deleteSource = source.slice(source.indexOf('async function deleteOwnTripFromCloud('), source.indexOf('// B2：', source.indexOf('async function deleteOwnTripFromCloud(')));
const syncSource = source.slice(source.indexOf('async function syncLocalTripToCloud('), source.indexOf('// 刪除自己的單人行程', source.indexOf('async function syncLocalTripToCloud(')));
const deleteUiSource = source.slice(source.indexOf('async function deleteMyTrip('), source.indexOf('// 將舊版或離線時只存於本機', source.indexOf('async function deleteMyTrip(')));

function deletionFixture(remoteDocs, existingChildren = []) {
  const actions = [];
  const child = (kind, id) => ({
    kind, id,
    get: async () => ({ exists: existingChildren.includes(`${kind}/${id}`), ref: { kind, id } })
  });
  const tripRef = {
    delete: async () => { actions.push('delete-parent'); },
    collection: (kind) => ({
      doc: (id) => child(kind, id),
      get: async () => ({ forEach: (fn) => {
        if (kind === 'photos' && existingChildren.includes('photos/p1')) fn({ ref: { kind, id: 'p1' } });
      } })
    })
  };
  const db = {
    collection: () => ({
      doc: () => tripRef,
      where: (field, op, email) => {
        actions.push(`query:${field}:${email}`);
        return { get: async () => ({ docs: remoteDocs.map((id) => ({ id })) }) };
      }
    }),
    batch: () => {
      const refs = [];
      return { delete: (ref) => refs.push(ref), commit: async () => { actions.push(refs.map((ref) => ref.kind || 'parent').join(',')); } };
    }
  };
  const ctx = { firebaseEnabled: true, firebaseDb: db,
    firebaseAuth: { currentUser: { uid: 'uid-1', email: 'me@example.invalid' } },
    currentUser: { email: 'me@example.invalid' }, firebase: {}, isLoggedIn: true };
  vm.runInNewContext(deleteSource, ctx);
  return { ctx, actions };
}

test('local-only trip is removed without trying to delete a missing Firestore document', async () => {
  const { ctx, actions } = deletionFixture([]);
  assert.equal(await ctx.deleteOwnTripFromCloud({ id: 'local-1' }), 'local-only');
  assert.deepEqual(actions, ['query:userEmail:me@example.invalid']);
});

test('cloud trip with no children deletes its parent', async () => {
  const { ctx, actions } = deletionFixture(['cloud-1']);
  assert.equal(await ctx.deleteOwnTripFromCloud({ id: 'cloud-1' }), 'deleted');
  assert.deepEqual(actions, ['query:userEmail:me@example.invalid', 'delete-parent']);
});

test('cloud trip deletes only existing children with its parent', async () => {
  const { ctx, actions } = deletionFixture(['cloud-1'], ['photos/p1', 'memories/uid-1']);
  assert.equal(await ctx.deleteOwnTripFromCloud({ id: 'cloud-1' }), 'deleted');
  assert.deepEqual(actions, ['query:userEmail:me@example.invalid', 'photos,memories,parent']);
});

test('signed-in trip is retained when auth is unavailable', async () => {
  const { ctx, actions } = deletionFixture([]);
  ctx.firebaseAuth.currentUser = null;
  assert.equal(await ctx.deleteOwnTripFromCloud({ id: 'local-1' }), 'offline');
  assert.deepEqual(actions, []);
});

test('manual sync never overwrites an existing cloud trip', async () => {
  const trip = { id: 'local-1' };
  const actions = [];
  const ctx = { myTrips: [trip], firebaseEnabled: true, isLoggedIn: true,
    currentUser: { email: 'me@example.invalid' },
    firebaseAuth: { currentUser: { email: 'me@example.invalid' } },
    firebaseDb: { collection: () => ({ where: () => ({ get: async () => ({ docs: [{ id: 'local-1' }] }) }) }) },
    saveMicroTripToFirebase: async () => { actions.push('save'); return true; },
    renderMyTrips() {}, renderSideMyTrips() {}, saveState() {},
    showToast: (message) => actions.push(message), console: { warn() {} } };
  vm.runInNewContext(syncSource, ctx);
  await ctx.syncLocalTripToCloud('local-1');
  assert.equal(trip.cloudSyncedFor, 'me@example.invalid');
  assert.equal(trip.__saving, false);
  assert.equal(actions.includes('save'), false);
});

test('manual sync uploads a missing trip and preserves it locally on failure', async () => {
  for (const success of [true, false]) {
    const trip = { id: 'local-1' };
    const events = [];
    const ctx = { myTrips: [trip], firebaseEnabled: true, isLoggedIn: true,
      currentUser: { email: 'me@example.invalid' },
      firebaseAuth: { currentUser: { email: 'me@example.invalid' } },
      firebaseDb: { collection: () => ({ where: () => ({ get: async () => ({ docs: [] }) }) }) },
      saveMicroTripToFirebase: async () => { events.push('save'); if (success) trip.cloudSyncedFor = 'me@example.invalid'; return success; },
      renderMyTrips() {}, renderSideMyTrips() {}, saveState() { events.push('persist'); },
      showToast() {}, console: { warn() {} } };
    vm.runInNewContext(syncSource, ctx);
    await ctx.syncLocalTripToCloud('local-1');
    assert.equal(events.includes('save'), true);
    assert.equal(events.includes('persist'), true);
    assert.equal(trip.cloudSyncedFor, success ? 'me@example.invalid' : undefined);
    assert.equal(trip.__saving, false);
    assert.equal(ctx.myTrips[0], trip);
  }
});

test('delete button removes local-only trip but keeps it on Firestore failure', async () => {
  for (const succeeds of [true, false]) {
    const trip = { id: 'local-1', title: '1005' };
    const events = [];
    const ctx = { myTrips: [trip], collabState: null,
      window: { confirm: () => true }, firebaseEnabled: true,
      deleteOwnTripFromCloud: async () => {
        if (!succeeds) throw Object.assign(new Error('permission-denied'), { code: 'permission-denied' });
        return 'local-only';
      },
      saveState() { events.push('persist'); }, renderMyTrips() {}, renderSideMyTrips() {},
      showToast() {}, console: { warn() {} } };
    vm.runInNewContext(deleteUiSource, ctx);
    await ctx.deleteMyTrip('local-1');
    assert.equal(ctx.myTrips.length, succeeds ? 0 : 1);
    assert.equal(events.includes('persist'), succeeds);
  }
});
