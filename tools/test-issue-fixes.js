// Run real browser functions / Express handlers in a VM with in-memory I/O.
// No credentials, live writes, AI calls, or copied validation implementations.
const fs = require('node:fs');
const vm = require('node:vm');
const assert = require('node:assert/strict');
const { test } = require('node:test');
const crypto = require('node:crypto');
const read = p => fs.readFileSync(require('node:path').join(__dirname, '..', p), 'utf8');
const server = read('server/server.js');
const planner = read('app/ai-travel-planner-v8.js');
const clone = x => JSON.parse(JSON.stringify(x));
const memberKey = e => e.toLowerCase().replace(/[^a-z0-9]/g, '_');
const trip = (extra = {}) => ({ ownerEmail: 'owner@test.invalid', ownerUid: 'owner',
  memberEmails: [], members: {}, maxMembers: 3, shareToken: 'abcdefghijklmnopqrst', inviteCode: 'ABCD1234', ...extra });

function fakeDb(initial) {
  const records = new Map(Object.entries(clone(initial)));
  const writes = [];
  function apply(path, data) {
    const merge = (target, patch) => {
      for (const [key, value] of Object.entries(patch)) {
        if (value && value.op === 'delete') delete target[key];
        else if (value && value.op === 'union') target[key] = [...new Set([...(target[key] || []), ...value.values])];
        else if (value && value.op === 'remove') target[key] = (target[key] || []).filter(x => !value.values.includes(x));
        else if (value && typeof value === 'object' && !Array.isArray(value)) target[key] = Object.keys(value).length ? merge(target[key] || {}, value) : {};
        else target[key] = clone(value);
      }
      return target;
    };
    records.set(path, merge(records.get(path) || {}, data));
    writes.push({ path, data: clone(data) });
  }
  function ref(path) {
    return { path, collection: n => ref(path + '/' + n), doc: n => ref(path + '/' + n),
      get: async () => ({ exists: records.has(path), data: () => clone(records.get(path)), get: k => records.get(path)?.[k] }),
      set: async data => apply(path, data) };
  }
  return { records, writes, collection: ref, runTransaction: async fn => {
    const pending = [];
    const result = await fn({ get: r => r.get(), set: (r, data) => pending.push([r.path, data]) });
    pending.forEach(([path, data]) => apply(path, data));
    return result;
  } };
}

function serverRoute(path, data, user, extra = {}) {
  const db = fakeDb({ 'micro_trips/trip-1': data, 'invites/ABCD1234': { tripId: 'trip-1', active: true }, ...extra });
  let handler;
  const ctx = vm.createContext({ Buffer, crypto, console: { error() {} }, adminDb: db, adminReady: true,
    Timestamp: { now: () => 1, fromMillis: x => x }, collabLimiter: null, requireFirebaseUser: null,
    parkingReportIpLimiter: null, parkingReportUidLimiter: null, rateLimit: () => null,
    app: { post: (...args) => { handler = args.at(-1); } } });
  vm.runInContext(server.slice(server.indexOf('function normalizeInviteCode'), server.indexOf('function publicTripData')), ctx);
  const at = server.indexOf("app.post('" + path + "'");
  assert.ok(at >= 0, path);
  if (path === '/api/parking-report') {
    const begin = server.indexOf('const PARKING_REPORT_COLLECTION');
    // Includes only the real parking constants and pure helpers before the route.
    vm.runInContext(server.slice(begin, at), ctx);
  }
  vm.runInContext(server.slice(at, server.indexOf('\n});', at) + 4), ctx);
  return { db, call: async body => {
    const result = { status: 200, body: null };
    const res = { status: n => { result.status = n; return res; }, json: b => { result.body = b; return res; } };
    await handler({ body, user }, res);
    return result;
  } };
}
function browserCollab(data, email = 'member@test.invalid') {
  const db = fakeDb({ 'micro_trips/trip-1': data });
  const user = { uid: 'member', email, getIdToken: async () => 'test' };
  const ctx = vm.createContext({ window: {}, TextEncoder, Uint8Array, URL, firebaseDb: db,
    firebase: { auth: () => ({ currentUser: user }), firestore: { FieldValue: {
      serverTimestamp: () => 1, arrayUnion: (...values) => ({ op: 'union', values }),
      arrayRemove: (...values) => ({ op: 'remove', values }), delete: () => ({ op: 'delete' })
    } } }, fetch: async () => ({ ok: true, json: async () => ({ tripId: 'trip-1' }) }) });
  vm.runInContext(read('app/collab.js'), ctx);
  return { api: ctx.window.WAI_COLLAB, db, user };
}

test('legacy collision cannot borrow editor role; explicit editor and owner remain valid', () => {
  const { api } = browserCollab(trip());
  const data = trip({ members: { [memberKey('a.b@test.invalid')]: { email: 'a.b@test.invalid', role: 'editor' } } });
  assert.equal(api.resolveRole(data, 'a_b@test.invalid'), 'viewer');
  assert.equal(api.resolveRole(data, 'a.b@test.invalid'), 'editor');
  assert.equal(api.resolveRole(data, 'owner@test.invalid', 'owner'), 'owner');
  assert.equal(api.resolveRole({ ...data, editorEmails: ['a_b@test.invalid'] }, 'a_b@test.invalid'), 'editor');
});
test('normal invite join, repeat, role change, leave and rejoin preserve member data', async () => {
  const { api, db, user } = browserCollab(trip());
  await api.joinByCode('ABCD1234', user);
  assert.equal(db.records.get('micro_trips/trip-1').members[memberKey(user.email)].role, 'viewer');
  assert.equal((await api.joinByCode('ABCD1234', user)).alreadyMember, true);
  await api.setMemberRole('trip-1', user.email, 'editor');
  assert.equal(db.records.get('micro_trips/trip-1').members[memberKey(user.email)].email, user.email);
  await api.leaveSharedTrip('trip-1', user.email);
  assert.equal(db.records.get('micro_trips/trip-1').members[memberKey(user.email)], undefined);
  assert.equal((await api.joinByCode('ABCD1234', user)).alreadyMember, false);
});
test('collision blocks browser join and role write; leave never deletes the other member', async () => {
  const existing = 'a.b@test.invalid', colliding = 'a_b@test.invalid';
  const data = trip({ memberEmails: [existing, colliding], members: { [memberKey(existing)]: { email: existing, role: 'editor' } } });
  const { api, db, user } = browserCollab(data, colliding);
  await assert.rejects(api.joinByCode('ABCD1234', user), /識別資料衝突/);
  await assert.rejects(api.setMemberRole('trip-1', colliding, 'viewer'), /識別資料衝突/);
  assert.equal(db.writes.length, 0);
  await api.leaveSharedTrip('trip-1', colliding);
  assert.equal(db.records.get('micro_trips/trip-1').members[memberKey(existing)].email, existing);
});
test('browser join counts missing owner and deduplicates emails', async () => {
  const full = browserCollab(trip({ memberEmails: ['one@test.invalid', 'two@test.invalid'] }));
  await assert.rejects(full.api.joinByCode('ABCD1234', full.user), /人數已滿/);
  assert.equal(full.db.writes.length, 0);
  const room = browserCollab(trip({ memberEmails: ['one@test.invalid', 'one@test.invalid'] }));
  await room.api.joinByCode('ABCD1234', room.user);
  assert.equal(room.db.writes.length, 1);
});
test('member preferences accept own identity and reject stale colliding preference records', async () => {
  const { api, db, user } = browserCollab(trip());
  await api.joinByCode('ABCD1234', user);
  await api.setMemberPrefs('trip-1', user.email, { pace: '平衡' });
  const path = 'micro_trips/trip-1/member_prefs/' + memberKey(user.email);
  assert.equal(db.records.get(path).email, user.email);
  db.records.set(path, { email: 'someone-else@test.invalid', prefs: { pace: '悠閒' } });
  await assert.rejects(api.setMemberPrefs('trip-1', user.email, {}), /帳號資料衝突/);
  assert.equal(db.records.get(path).email, 'someone-else@test.invalid');
});
test('planner role resolution does not trust colliding member or cached role', () => {
  const ctx = vm.createContext({ currentUserIdentity: () => ({ email: 'a_b@test.invalid', uid: 'member' }) });
  vm.runInContext(planner.slice(planner.indexOf('  function memberKeyOf('), planner.indexOf('  function currentUserIdentity(')), ctx);
  vm.runInContext(planner.slice(planner.indexOf('  function resolveCollabRole('), planner.indexOf('  /* ── App 端行程')), ctx);
  ctx.trip = trip({ role: 'owner', members: { [memberKey('a.b@test.invalid')]: { email: 'a.b@test.invalid', role: 'editor' } } });
  assert.equal(vm.runInContext('resolveCollabRole(trip)', ctx), 'viewer');
  ctx.trip.editorEmails = ['a_b@test.invalid'];
  assert.equal(vm.runInContext('resolveCollabRole(trip)', ctx), 'editor');
});
test('server invite and share request both reject full trips with owner omitted', async () => {
  const data = trip({ memberEmails: ['one@test.invalid', 'two@test.invalid'] });
  const user = { uid: 'member', email: 'member@test.invalid' };
  for (const path of ['/api/collab/invites/verify', '/api/collab/join-requests']) {
    const route = serverRoute(path, data, user);
    const res = await route.call({ code: 'ABCD1234', tripId: 'trip-1', token: data.shareToken });
    assert.equal(res.status, 409);
    assert.equal(route.db.writes.length, 0);
  }
});
test('server request then acceptance use same count; duplicate request is idempotent', async () => {
  const data = trip({ memberEmails: ['one@test.invalid', 'one@test.invalid'] });
  const request = serverRoute('/api/collab/join-requests', data, { uid: 'member', email: 'member@test.invalid' });
  assert.equal((await request.call({ tripId: 'trip-1', token: data.shareToken })).body.status, 'pending');
  const count = request.db.writes.length;
  assert.equal((await request.call({ tripId: 'trip-1', token: data.shareToken })).body.status, 'pending');
  assert.equal(request.db.writes.length, count);
  const extra = { 'micro_trips/trip-1/join_requests/member': request.db.records.get('micro_trips/trip-1/join_requests/member') };
  const accept = serverRoute('/api/collab/join-requests/resolve', data, { uid: 'owner', email: data.ownerEmail }, extra);
  assert.equal((await accept.call({ tripId: 'trip-1', requesterUid: 'member', decision: 'accept' })).body.status, 'accepted');
  assert.equal(accept.db.records.get('micro_trips/trip-1').memberEmails.length, 3);
});
test('acceptance rechecks capacity and collision inside transaction, with zero partial writes', async () => {
  for (const data of [trip({ maxMembers: 1 }), trip({ ownerEmail: 'a.b@test.invalid' })]) {
    const email = data.maxMembers === 1 ? 'member@test.invalid' : 'a_b@test.invalid';
    const route = serverRoute('/api/collab/join-requests/resolve', data, { uid: 'owner', email: data.ownerEmail }, {
      'micro_trips/trip-1/join_requests/member': { status: 'pending', requesterEmail: email }
    });
    assert.equal((await route.call({ tripId: 'trip-1', requesterUid: 'member', decision: 'accept' })).status, 409);
    assert.equal(route.db.writes.length, 0);
  }
});
test('both server join entry points reject member identity collision', async () => {
  const data = trip({ ownerEmail: 'a.b@test.invalid' });
  for (const path of ['/api/collab/invites/verify', '/api/collab/join-requests']) {
    const route = serverRoute(path, data, { uid: 'member', email: 'a_b@test.invalid' });
    assert.equal((await route.call({ code: 'ABCD1234', tripId: 'trip-1', token: data.shareToken })).status, 409);
    assert.equal(route.db.writes.length, 0);
  }
});

test('parking endpoint accepts lot/roadside/unknown and legacy temp, rejects arbitrary values', async () => {
  const data = trip({ stops: [{ lat: 22.79, lng: 121.12 }] });
  for (const kind of ['lot', 'roadside', 'unknown', 'temp', 'invalid']) {
    const route = serverRoute('/api/parking-report', data, { uid: 'owner', email: data.ownerEmail });
    const result = await route.call({ tripId: 'trip-1', stopId: 'stop1', type: 'found', kind,
      stopLat: 22.79, stopLng: 121.12, lat: 22.79, lng: 121.12, accuracy: 10 });
    assert.equal(result.status, kind === 'invalid' ? 400 : 200);
    if (kind !== 'invalid') assert.equal(route.db.writes.at(-1).data.kind, kind);
  }
});

test('parking selection survives marker step, persistence and backend payload; non-found has no kind', async () => {
  const nodes = Object.fromEntries(['parkingReportType','parkingReportKind','parkingReportKindField','parkingReportNote','parkingReportOverlay']
    .map(id => [id, { value: '', hidden: false, classList: { remove() {} } }]));
  const requests = [];
  const ctx = vm.createContext({ window: {}, document: { getElementById: id => nodes[id] },
    feedbackToast() {}, localStorage: { getItem: () => '{}' }, renderItineraryDisplay() {}, persistParkingRecords: async () => {},
    VERTEX_PROXY_BASE: '/api', firebaseAuth: { currentUser: { uid: 'u', getIdToken: async () => 'token' } },
    fetch: async (url, options) => { requests.push(JSON.parse(options.body)); return { ok: true }; } });
  vm.runInContext(`let replanStops=[{id:'s',name:'Stop',lat:22.79,lng:121.12}], parkingReportStopId='s', pendingParkingReport=null;
    let parkingReports=[], currentItineraryId='trip-1', collabReadOnly=false, myParkingPromise=null, myParkingUid=null;
    const PARKING_REPORT_TYPES=new Set(['found','full','closed','none','wrong','missing']);
    const PARKING_REPORT_KINDS=new Set(['lot','roadside','unknown','temp']);`, ctx);
  function section(begin, end) { vm.runInContext(planner.slice(planner.indexOf(begin), planner.indexOf(end, planner.indexOf(begin))), ctx); }
  section('  function normalizeParkingReports(', '  function getParkingRecord(');
  section('  window.closeParkingReportSheet =', '  window.openActiveParkingDetails =');
  ctx.window.openParkingRecordSheet = () => {};
  nodes.parkingReportType.value = 'found';
  nodes.parkingReportKind.value = 'roadside';
  ctx.window.updateParkingReportKindVisibility();
  assert.equal(nodes.parkingReportKindField.hidden, false);
  await ctx.window.submitParkingReport();
  assert.equal(vm.runInContext('pendingParkingReport.kind', ctx), 'roadside');
  await vm.runInContext("commitParkingReport(replanStops[0], pendingParkingReport.type, pendingParkingReport.note, {lat:22.79,lng:121.12,accuracy:10}, pendingParkingReport.kind)", ctx);
  assert.equal(requests[0].kind, 'roadside');
  assert.equal(vm.runInContext('parkingReports[0].kind', ctx), 'roadside');
  assert.equal(vm.runInContext("normalizeParkingReports([{stopId:'old',type:'found'}])[0].kind", ctx), undefined);
  nodes.parkingReportType.value = 'full';
  ctx.window.updateParkingReportKindVisibility();
  assert.equal(nodes.parkingReportKindField.hidden, true);
  vm.runInContext("parkingReportStopId='s'", ctx);
  await ctx.window.submitParkingReport();
  assert.equal(requests[1].kind, undefined);
});

test('feedback local drafts are isolated by authenticated UID; legacy shared cache not read', () => {
  const store = new Map([['wai_trip_feedback', '{"trip-1":{"comment":"legacy private text"}}']]);
  const ctx = vm.createContext({ firebaseAuth: { currentUser: { uid: 'A' } }, localStorage: { getItem: k => store.get(k) } });
  vm.runInContext("const TRIP_FEEDBACK_KEY='wai_trip_feedback';" + planner.slice(planner.indexOf('  function getLocalTripFeedbackMap('), planner.indexOf('  // 計算本趟到訪數')), ctx);
  assert.equal(vm.runInContext("getLocalTripFeedback('trip-1')", ctx), null);
  store.set('wai_trip_feedback:A', '{"trip-1":{"comment":"A only"}}');
  assert.equal(vm.runInContext("getLocalTripFeedback('trip-1').comment", ctx), 'A only');
  ctx.firebaseAuth.currentUser = { uid: 'B' };
  assert.equal(vm.runInContext("getLocalTripFeedback('trip-1')", ctx), null);
});
