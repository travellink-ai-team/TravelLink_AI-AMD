// Explicit live integration probe. Only synthetic, namespaced fixtures are written.
// Credentials remain in process memory; output is assertions, never tokens or keys.
// Run after server restart / Rules publish: node tools/test-live-issues.js --run-live
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const { createRequire } = require('node:module');
const assert = require('node:assert/strict');
if (!process.argv.includes('--run-live')) throw new Error('Requires --run-live');
const root = path.resolve(__dirname, '..');
const req = createRequire(path.join(root, 'server/server.js'));
const { initializeApp, cert, deleteApp } = req('firebase-admin/app');
const { getAuth } = req('firebase-admin/auth');
const { getFirestore } = req('firebase-admin/firestore');
const scope = { window: {} };
vm.runInNewContext(fs.readFileSync(path.join(root, 'app/weather.env.js'), 'utf8'), scope);
const config = scope.window.TRAVEL_APP_CONFIG.FIREBASE_CONFIG;
const sa = JSON.parse(fs.readFileSync(path.join(root, 'crawler/serviceAccount.json'), 'utf8'));
assert.equal(config.projectId, sa.project_id, 'Configured project must match service account');
const app = initializeApp({ credential: cert(sa) }, 'issue-live-test');
const auth = getAuth(app), db = getFirestore(app);
const origin = 'https://travel-link-ai.duckdns.org';
const prefix = 'e2e_issues_' + Date.now();
const testEmail = 'codex.e2e.editor.20260901.2348@travel-link.test';
const fixtures = new Set();
const observations = [];
let token;
const stamp = () => new Date().toISOString();
function report(name, data = {}) { const entry = { name, ...data }; observations.push(entry); console.log(JSON.stringify(entry)); }
async function jsonFetch(url, options = {}) {
  const r = await fetch(url, { ...options, signal: AbortSignal.timeout(20000) });
  const text = await r.text();
  let body; try { body = JSON.parse(text); } catch { body = {}; }
  return { status: r.status, body };
}
async function api(route, body, method = 'POST') {
  return jsonFetch(origin + route, { method, headers: { 'Content-Type': 'application/json', Authorization: 'Bearer ' + token },
    ...(body ? { body: JSON.stringify(body) } : {}) });
}
function fields(data) {
  const value = x => {
    if (x === null) return { nullValue: null };
    if (Array.isArray(x)) return { arrayValue: { values: x.map(value) } };
    if (typeof x === 'object') return { mapValue: { fields: fields(x) } };
    if (typeof x === 'boolean') return { booleanValue: x };
    if (typeof x === 'number') return Number.isInteger(x) ? { integerValue: String(x) } : { doubleValue: x };
    return { stringValue: x };
  };
  return Object.fromEntries(Object.entries(data).map(([k, v]) => [k, value(v)]));
}
async function firestore(doc, data, mask) {
  const url = 'https://firestore.googleapis.com/v1/projects/' + config.projectId + '/databases/(default)/documents/' + doc;
  return jsonFetch(url + (mask ? '?updateMask.fieldPaths=' + encodeURIComponent(mask) : ''), {
    method: data ? 'PATCH' : 'GET', headers: { Authorization: 'Bearer ' + token, 'Content-Type': 'application/json' },
    ...(data ? { body: JSON.stringify({ fields: fields(data) }) } : {})
  });
}
async function fixture(doc, data) {
  assert.ok(doc.includes(prefix), 'All fixture paths must be namespaced');
  fixtures.add(doc);
  await db.doc(doc).create(data);
}
const identityKey = e => Buffer.from(e.toLowerCase()).toString('hex');
const legacyKey = e => e.toLowerCase().replace(/[^a-z0-9]/g, '_');

(async () => {
  const health = await jsonFetch(origin + '/api/health');
  assert.equal(health.status, 200); assert.equal(health.body.ok, true);
  report('production health', { pass: true, time: stamp() });
  const user = await auth.getUserByEmail(testEmail);
  const customToken = await auth.createCustomToken(user.uid);
  const session = await jsonFetch('https://identitytoolkit.googleapis.com/v1/accounts:signInWithCustomToken?key=' + encodeURIComponent(config.apiKey), {
    method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ token: customToken, returnSecureToken: true })
  });
  assert.equal(session.status, 200, 'Test account authentication');
  token = session.body.idToken;
  const tripId = prefix + '_owner';
  const tripPath = 'micro_trips/' + tripId;
  const lat = 22.7567, lng = 121.1256;
  await fixture(tripPath, { title: '自動化驗收暫存，不是真實旅遊', ownerUid: user.uid, ownerEmail: user.email,
    userEmail: user.email, memberEmails: [user.email], stops: [{ name: '人工座標驗收點', lat, lng }], maxMembers: 3 });
  // Submit via authenticated client REST, confirm via Admin read, then prove client cannot read.
  const feedbackPath = tripPath + '/feedback/' + prefix;
  fixtures.add(feedbackPath);
  const entry = { email: user.email, name: '測試資料', tripRating: 4, aiAccuracy: 4,
    comment: prefix + ' 隱私驗收，測完清除', visitedCount: 0, totalStops: 1,
    submittedAt: Date.now(), appPlatform: 'web', stopRatings: {} };
  assert.equal((await firestore(feedbackPath, entry)).status, 200, 'Owner can submit feedback');
  assert.equal((await db.doc(feedbackPath).get()).data().comment, entry.comment);
  assert.equal((await firestore(feedbackPath)).status, 403, 'Owner cannot read feedback');
  assert.equal((await firestore(feedbackPath, { ...entry, tripRating: 5 })).status, 200, 'Owner can update own feedback without read');
  report('feedback submit / server persistence / denied read / resubmit', { pass: true });
  const topFeedback = 'feedback/' + prefix;
  fixtures.add(topFeedback);
  assert.equal((await firestore(topFeedback, entry)).status, 200);
  assert.equal((await firestore(topFeedback)).status, 403);
  report('top-level feedback read denied', { pass: true });
  const memberTrip = 'micro_trips/' + prefix + '_viewer';
  await fixture(memberTrip, { ownerUid: prefix + '_other', ownerEmail: prefix + '@example.invalid',
    memberEmails: [user.email], members: { [legacyKey(user.email)]: { email: user.email, role: 'viewer' } } });
  const memberFeedback = memberTrip + '/feedback/' + prefix;
  fixtures.add(memberFeedback);
  assert.equal((await firestore(memberFeedback, entry)).status, 200);
  assert.equal((await firestore(memberFeedback)).status, 403);
  report('viewer can submit but cannot read feedback', { pass: true });
  const collision = user.email.replace('.', '_');
  assert.notEqual(collision, user.email); assert.equal(legacyKey(collision), legacyKey(user.email));
  const collisionTrip = 'micro_trips/' + prefix + '_collision';
  await fixture(collisionTrip, { title: 'collision fixture', ownerUid: prefix + '_other', ownerEmail: prefix + '@example.invalid',
    memberEmails: [user.email, collision], members: { [legacyKey(user.email)]: { email: collision, role: 'editor' } } });
  assert.equal((await firestore(collisionTrip, { title: 'must not write' }, 'title')).status, 403);
  await db.doc(collisionTrip).update({ ['members.' + legacyKey(user.email) + '.email']: user.email });
  assert.equal((await firestore(collisionTrip, { title: 'valid editor write' }, 'title')).status, 200);
  report('Rules deny collision editor and allow matching editor', { pass: true });

  const shareToken = 'abcdefghijklmnopqrst';
  const fullId = prefix + '_full';
  await fixture('micro_trips/' + fullId, { ownerEmail: prefix + '@example.invalid', ownerUid: prefix + '_owner',
    memberEmails: ['one@example.invalid', 'two@example.invalid'], maxMembers: 3, shareToken });
  const full = await api('/api/collab/join-requests', { tripId: fullId, token: shareToken });
  assert.equal(full.status, 409); assert.match(full.body.message, /上限/);
  assert.equal((await db.collection('micro_trips/' + fullId + '/join_requests').get()).size, 0);
  report('server capacity includes omitted owner; no pending request created', { pass: true });
  const collidingId = prefix + '_join_collision';
  await fixture('micro_trips/' + collidingId, { ownerEmail: collision, ownerUid: prefix + '_other', memberEmails: [], maxMembers: 10, shareToken });
  const blocked = await api('/api/collab/join-requests', { tripId: collidingId, token: shareToken });
  assert.equal(blocked.status, 409); assert.match(blocked.body.message, /識別資料衝突/);
  report('server collision join rejected', { pass: true });

  // Check beforehand to avoid replacing any earlier report by this same account.
  const destKey = ('d' + lat.toFixed(4) + '_' + lng.toFixed(4)).replace(/\./g, 'p').replace(/-/g, 'm');
  const parkingPath = 'parking_reports/' + destKey + '__' + user.uid;
  assert.equal((await db.doc(parkingPath).get()).exists, false, 'Never overwrite existing parking report');
  fixtures.add(parkingPath);
  for (const kind of ['unknown', 'roadside', 'lot']) {
    const res = await api('/api/parking-report', { tripId, stopId: prefix + '_stop', stopLat: lat, stopLng: lng,
      type: 'found', kind, note: prefix + ' 人工驗收資料，測完清除', lat, lng, accuracy: 10, adjusted: true });
    assert.equal(res.status, 200, 'Parking kind ' + kind);
    assert.equal((await db.doc(parkingPath).get()).data().kind, kind);
    const mine = await api('/api/parking-reports/mine', null, 'GET');
    assert.equal(mine.status, 200);
    assert.ok(mine.body.items.some(x => x.kind === kind && Math.abs(x.lat - lat) < 0.00001));
    report('parking kind write / mine read ' + kind, { pass: true });
  }
  const bad = await api('/api/parking-report', { tripId, stopId: prefix, stopLat: lat, stopLng: lng,
    type: 'found', kind: 'invalid-kind', lat, lng, accuracy: 10 });
  assert.equal(bad.status, 400);
  assert.equal((await db.doc(parkingPath).get()).data().kind, 'lot');
  report('invalid parking kind denied without corrupting prior report', { pass: true });
})().catch(e => {
  report('failure', { pass: false, message: String(e.message).replace(/key=[^\s]+/g, 'key=[redacted]') });
  process.exitCode = 1;
}).finally(async () => {
  // Exact paths only. No broad query/delete of user data, no recursive delete.
  const failedCleanup = [];
  for (const doc of [...fixtures].reverse()) {
    try { await db.doc(doc).delete(); } catch { failedCleanup.push(doc); }
  }
  report('cleanup', { pass: !failedCleanup.length, fixtureCount: fixtures.size, failedCleanup });
  if (failedCleanup.length) process.exitCode = 1;
  const output = path.join(root, 'test-artifacts/issue-live-2026-09-21.json');
  fs.writeFileSync(output, JSON.stringify({ date: stamp(), fixturePrefix: prefix, observations }, null, 2));
  await deleteApp(app);
});
