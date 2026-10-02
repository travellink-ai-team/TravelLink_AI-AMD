const fs = require('node:fs');
const vm = require('node:vm');
const assert = require('node:assert/strict');
const { test } = require('node:test');
const src = fs.readFileSync(require('node:path').join(__dirname, '../app/ai-travel-planner-v8.js'), 'utf8');
test('endpoint aliases are removed, return endpoint and unrelated station remain', () => {
  const ctx = { readStopCoordinates: s => s.coord || null,
    approxDistanceMeters: (a,b,c,d) => Math.hypot(a-c,b-d)*111000 };
  vm.runInNewContext(src.slice(src.indexOf('  function normalizeText('), src.indexOf('  function normalizeInviteCode('))
    + src.slice(src.indexOf('  function isReplanEndpointDuplicate('), src.indexOf('  function deduplicateAdjacentTripStops(')), ctx);
  const start = {name:'台東車站',type:'start'}, end = {name:'台東車站',type:'end'};
  const park = {name:'台東森林公園'}, other = {name:'台東舊車站'};
  const result = ctx.removeReplanEndpointDuplicates([start,{name:'臺東火車站'},park,other,{name:'台東車站'},end],start,end);
  assert.deepEqual(result, [start,park,other,end]);
  assert.equal(ctx.isReplanEndpointDuplicate({name:'別名',coord:{lat:22,lng:121}}, {name:'起點',coord:{lat:22,lng:121}}),true);
});
test('cloud binding requires existing accessible owned/member trip; local draft stays unbound', async () => {
  for (const [data, expected] of [[null,null],[{userEmail:'ME@test.invalid'},'trip'],[{memberEmails:['me@test.invalid']},'trip'],[{ownerUid:'other'},null]]) {
    const ctx = { currentItineraryId:'trip',firebaseEnabled:true,
      firebaseAuth:{currentUser:{uid:'me',email:'me@test.invalid'}},
      firebaseDb:{collection:()=>({doc:()=>({get:async()=>({exists:!!data,data:()=>data})})})} };
    vm.runInNewContext(src.slice(src.indexOf('  async function getCloudTripIdForCost('),src.indexOf('  function vertexHttpError(')),ctx);
    assert.equal(await ctx.getCloudTripIdForCost(),expected);
  }
});
