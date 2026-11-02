// ai-tasks.js（/api/ai/task）與 amd-llm.js chatJson（結構化輸出）的測試。
// 離線測試把上游全部 mock 掉，不需要網路；加上 --live 會用 server/.env 的
// AMD_LLM_BASE_URL 實際跑每個 task，確認 json_schema 結構化輸出可用。
//   node test/ai-tasks.test.js
//   node test/ai-tasks.test.js --live
'use strict';
const path = require('path');
const http = require('http');
require('dotenv').config({ path: path.join(__dirname, '..', '.env') });
const LIVE = process.argv.includes('--live');
process.env.AI_PROVIDER = 'amd';
if (!LIVE || !process.env.AMD_LLM_BASE_URL) process.env.AMD_LLM_BASE_URL = 'http://example.invalid/v1';
if (!LIVE) { delete process.env.AMD_LLM_FALLBACK_BASE_URL; }

const express = require('express');
const rateLimit = require('express-rate-limit');
const amd = require('../amd-llm');
const tasks = require('../ai-tasks');

let pass = 0, fail = 0;
const ok = (name, cond, extra) => {
  if (cond) { pass++; console.log('PASS  ' + name); }
  else { fail++; console.log('FAIL  ' + name + (extra ? '  ' + extra : '')); }
};

const recapInput = { kind: 'video', region: '台東', days: 2, dateLabel: '2026/10/12', transportMode: 'car', stops: ['鐵花村', '森林公園', '三仙台'] };
const memoryInput = {
  instruction: '卡片放大一點，標題移到下面',
  usedCards: 2,
  layout: { cards: [{ cx: 0.33, cy: 0.33, w: 0.3, angle: -6 }, { cx: 0.66, cy: 0.66, w: 0.3, angle: 8 }], titleBand: { cx: 0.5, cy: 0.5, w: 0.7, h: 0.1, visible: true, text: '台東' } }
};
const posterInput = { area: '台東', places: [{ n: 1, name: '台東火車站', type: '車站' }, { n: 2, name: '鐵花村', type: '' }] };

// 回傳一個依序吐出 responses 的假 callModel，並記下每次收到的 spec
function fakeModel(responses) {
  const calls = [];
  const fn = async (spec) => {
    calls.push(spec);
    const r = responses[Math.min(calls.length - 1, responses.length - 1)];
    if (r instanceof Error) throw r;
    return Object.assign({ model: 'gpt-oss-120b', structured: 'json_schema', usage: { promptTokens: 10, outputTokens: 5, thoughtTokens: 3 } }, r);
  };
  fn.calls = calls;
  return fn;
}

async function offline() {
  // ── schema 驗證器 ──
  const S = tasks.TASKS.recap_ideas.schema;
  ok('schema：合法輸出無錯誤', tasks.validateSchema(S, { titles: ['a'], hashtags: ['#b'] }).length === 0);
  ok('schema：缺必填欄位', tasks.validateSchema(S, { titles: ['a'] }).some((e) => e.includes('hashtags')));
  ok('schema：型別錯誤', tasks.validateSchema(S, { titles: 'a', hashtags: [] }).length > 0);
  ok('schema：多餘欄位被拒', tasks.validateSchema(S, { titles: ['a'], hashtags: ['#b'], x: 1 }).some((e) => e.includes('不允許')));
  ok('schema：integer 檢查', tasks.validateSchema({ type: 'integer' }, 1.5).length === 1);
  ok('parseJsonLoose 去掉 ```json 圍欄', tasks.parseJsonLoose('```json\n{"a":1}\n```').a === 1);
  ok('parseJsonLoose 取出前後多字中的物件', tasks.parseJsonLoose('好的：{"a":2} 完成').a === 2);
  ok('parseJsonLoose 壞 JSON 回 undefined', tasks.parseJsonLoose('{"a":') === undefined);

  // ── runTask：成功 ──
  let m = fakeModel([{ text: '{"titles":["秋遊台東","台東兩天小旅行","鐵花村的一天","第四個"],"hashtags":["台東","#台東","#Taitung", "#三仙台"]}' }]);
  let usage = [];
  let r = await tasks.runTask({ task: 'recap_ideas', input: recapInput, callModel: m, onUsage: (u) => usage.push(u) });
  ok('recap_ideas 一次成功', r.attempts === 1 && r.output.titles.length === 3);
  ok('recap_ideas hashtag 自動補 # 並去重', JSON.stringify(r.output.hashtags) === JSON.stringify(['#台東', '#Taitung', '#三仙台']));
  ok('送給模型的有 system（範圍限制）與 schema', m.calls[0].messages[0].role === 'system' && m.calls[0].schema === tasks.TASKS.recap_ideas.schema);
  ok('prompt 用的是後端淨化過的 input', m.calls[0].messages[1].content.includes('鐵花村、森林公園、三仙台'));
  ok('onUsage 收到用量', usage.length === 1 && usage[0].promptTokens === 10);

  // ── runTask：壞 JSON → 重試一次成功，並附上錯誤原因 ──
  m = fakeModel([{ text: '{"titles":["x"' , finishReason: 'length' }, { text: '{"titles":["好標題"],"hashtags":["#台東"]}' }]);
  r = await tasks.runTask({ task: 'recap_ideas', input: recapInput, callModel: m });
  ok('壞 JSON 重試一次後成功', r.attempts === 2 && r.output.titles[0] === '好標題');
  ok('重試時告訴模型上次錯在哪', m.calls[1].messages.length === 3 && m.calls[1].messages[2].content.includes('截斷'));

  // ── runTask：schema 兩次都不符 → 回錯 ──
  m = fakeModel([{ text: '{"titles":"不是陣列","hashtags":[]}' }]);
  let err = null;
  try { await tasks.runTask({ task: 'recap_ideas', input: recapInput, callModel: m }); } catch (e) { err = e; }
  ok('schema 兩次不符就回錯（只重試一次）', err && err.code === 'schema' && m.calls.length === 2);

  // ── runTask：上游 5xx 重試，4xx 不重試 ──
  m = fakeModel([Object.assign(new Error('AMD LLM 503'), { status: 503 }), { text: '{"titles":["a"],"hashtags":["#b"]}' }]);
  r = await tasks.runTask({ task: 'recap_ideas', input: recapInput, callModel: m });
  ok('上游 5xx 重試一次', r.attempts === 2);
  m = fakeModel([Object.assign(new Error('AMD LLM 429'), { status: 429 })]);
  err = null;
  try { await tasks.runTask({ task: 'recap_ideas', input: recapInput, callModel: m }); } catch (e) { err = e; }
  ok('上游 4xx 不重試', err && err.status === 429 && m.calls.length === 1);

  // ── 輸入驗證 ──
  err = null;
  try { await tasks.runTask({ task: 'nope', input: {}, callModel: fakeModel([]) }); } catch (e) { err = e; }
  ok('未知 task 回 400', err && err.status === 400);
  err = null;
  try { await tasks.runTask({ task: 'memory_layout_edit', input: { layout: memoryInput.layout }, callModel: fakeModel([]) }); } catch (e) { err = e; }
  ok('memory_layout_edit 缺說明回 400', err && err.code === 'input');

  // ── memory_layout_edit：夾限與張數 ──
  m = fakeModel([{ text: JSON.stringify({ cards: [{ cx: 1.5, cy: 0.5, w: 0.9, angle: 45 }, { cx: 0.2, cy: 0.8, w: 0.2, angle: -3 }, { cx: 0.5, cy: 0.5, w: 0.3, angle: 0 }], titleBand: { cx: 0.5, cy: 0.85, w: 0.8, h: 0.5, visible: false } }) }]);
  r = await tasks.runTask({ task: 'memory_layout_edit', input: memoryInput, callModel: m });
  const c0 = r.output.cards[0];
  ok('版面：張數裁成 usedCards', r.output.cards.length === 2);
  ok('版面：超出範圍的數字被夾限', c0.cx === 0.94 && c0.w === 0.6 && c0.angle === 20 && r.output.titleBand.h === 0.2);
  ok('版面：visible 照模型回傳', r.output.titleBand.visible === false);
  ok('版面：prompt 不帶標題文字（只送版面數字）', !m.calls[0].messages[1].content.includes('"text"'));

  // ── poster_place_descriptions：只留清單內的編號 ──
  m = fakeModel([{ text: '{"descriptions":[{"n":1,"desc":"有屋簷與月台的鐵路車站"},{"n":9,"desc":"不在清單"},{"n":2,"desc":"綠地上的燈飾與舞台"}]}' }]);
  r = await tasks.runTask({ task: 'poster_place_descriptions', input: posterInput, callModel: m });
  ok('插圖描述：只留清單內的編號', r.output.descriptions.length === 2 && r.output.descriptions.every((d) => d.n === 1 || d.n === 2));

  // ── amd-llm chatJson：送 json_schema；端點不支援時退回 json_object ──
  const realFetch = global.fetch;
  const sent = [];
  let mode = 'ok';
  global.fetch = async (url, opts) => {
    const body = JSON.parse(opts.body);
    sent.push(body);
    if (mode === 'unsupported' && body.response_format.type === 'json_schema') {
      return new Response('{"error":"response_format json_schema is not supported"}', { status: 400 });
    }
    if (mode === 'other400') return new Response('{"error":"bad messages"}', { status: 400 });
    return new Response(JSON.stringify({
      model: 'openai/gpt-oss-120b',
      choices: [{ message: { content: '{"titles":["a"],"hashtags":["#b"]}', reasoning_content: '想一下' }, finish_reason: 'stop' }],
      usage: { prompt_tokens: 100, completion_tokens: 40, total_tokens: 140, completion_tokens_details: { reasoning_tokens: 15 } }
    }), { status: 200, headers: { 'Content-Type': 'application/json' } });
  };
  try {
    amd._resetJsonSchemaSupport();
    const schema = tasks.TASKS.recap_ideas.schema;
    let out = await amd.chatJson({ messages: [{ role: 'user', content: 'hi' }], schema, schemaName: 'recap_ideas', maxTokens: 512 });
    const rf = sent[0].response_format;
    ok('chatJson 送 response_format json_schema', rf.type === 'json_schema' && rf.json_schema.name === 'recap_ideas'
      && JSON.stringify(rf.json_schema.schema) === JSON.stringify(schema));
    ok('chatJson max_tokens 加推理餘裕', sent[0].max_tokens > 512 && sent[0].reasoning_effort);
    ok('chatJson 回傳文字、structured、usage 拆推理', out.text.includes('titles') && out.structured === 'json_schema' && out.usage.thoughtsTokenCount === 15 && out.usage.candidatesTokenCount === 25);
    ok('chatJson 不回傳推理內容', !JSON.stringify(out).includes('想一下'));

    sent.length = 0; mode = 'unsupported';
    out = await amd.chatJson({ messages: [{ role: 'user', content: 'hi' }], schema, maxTokens: 256 });
    ok('端點不支援 json_schema → 自動改 json_object', sent.length === 2 && sent[1].response_format.type === 'json_object' && out.structured === 'json_object');
    sent.length = 0;
    await amd.chatJson({ messages: [{ role: 'user', content: 'hi' }], schema, maxTokens: 256 });
    ok('之後直接用 json_object，不再先試 json_schema', sent.length === 1 && sent[0].response_format.type === 'json_object');

    amd._resetJsonSchemaSupport();
    sent.length = 0; mode = 'other400';
    let e400 = null;
    try { await amd.chatJson({ messages: [{ role: 'user', content: 'hi' }], schema }); } catch (e) { e400 = e; }
    ok('與 schema 無關的 400 不降級、直接丟錯', e400 && e400.status === 400 && sent.length === 1);
    amd._resetJsonSchemaSupport();
    mode = 'ok';

    // 既有轉接行為不變
    const oa = amd.toOpenAiRequest({ contents: [{ role: 'user', parts: [{ text: 'x' }] }], generationConfig: { responseMimeType: 'application/json' } }, { stream: false });
    ok('既有 toOpenAiRequest 仍是 json_object', oa.response_format.type === 'json_object');
  } finally {
    global.fetch = realFetch;
  }

  // ── HTTP 端點：AMD 與 Vertex 兩條路、標頭、記帳 ──
  const usageLog = [];
  const fakeRun = { id: 'run1' };
  const fakeGenRuns = {
    getOwnedRun: (id, uid) => (id === 'run1' && uid === 'u1' ? fakeRun : null),
    retain: () => {}, release: async () => {},
    addGeminiUsage: (run, model, u) => usageLog.push({ model, u }),
    markUpstreamError: () => usageLog.push({ error: true })
  };
  let amdEnabled = true;
  let amdText = '{"titles":["秋遊台東"],"hashtags":["#台東"]}';
  const fakeAmd = {
    USAGE_MODEL_ID: 'gpt-oss-120b',
    isEnabled: () => amdEnabled,
    chatJson: async () => ({ text: amdText, finishReason: 'stop', usage: { promptTokenCount: 50, candidatesTokenCount: 20, thoughtsTokenCount: 9 }, model: 'openai/gpt-oss-120b', structured: 'json_schema' })
  };
  const vertexCalls = [];
  const fakeFetch = async (url, opts) => {
    vertexCalls.push({ url: String(url), body: JSON.parse(opts.body) });
    return new Response(JSON.stringify({
      candidates: [{ content: { parts: [{ text: '{"titles":["台東慢旅"],"hashtags":["#台東"]}' }] }, finishReason: 'STOP' }],
      usageMetadata: { promptTokenCount: 30, candidatesTokenCount: 12 }
    }), { status: 200 });
  };
  const app = express();
  app.use(express.json());
  const requireFirebaseUser = (req, res, next) => {
    if (req.headers.authorization !== 'Bearer good') return res.status(401).json({ error: 'login required' });
    req.user = { uid: 'u1' };
    next();
  };
  tasks.mountAiTasks(app, { requireFirebaseUser, rateLimit, vertexUpstream: 'https://vertex.test', vertexApiKey: 'k', amdLlm: fakeAmd, genRuns: fakeGenRuns, fetchImpl: fakeFetch });
  const server = await new Promise((resolve) => { const s = app.listen(0, '127.0.0.1', () => resolve(s)); });
  const port = server.address().port;
  const post = (body, headers = {}) => new Promise((resolve, reject) => {
    const data = JSON.stringify(body);
    const req = http.request({ host: '127.0.0.1', port, path: '/api/ai/task', method: 'POST', headers: Object.assign({ 'Content-Type': 'application/json', 'Content-Length': Buffer.byteLength(data) }, headers) }, (res) => {
      let buf = '';
      res.on('data', (c) => { buf += c; });
      res.on('end', () => resolve({ status: res.statusCode, headers: res.headers, json: JSON.parse(buf || '{}') }));
    });
    req.on('error', reject);
    req.end(data);
  });
  try {
    let resp = await post({ task: 'recap_ideas', input: recapInput });
    ok('未登入回 401', resp.status === 401);
    resp = await post({ task: 'recap_ideas', input: recapInput }, { Authorization: 'Bearer good', 'X-Run-Id': 'run1' });
    ok('AMD：200 且 x-ai-provider=amd', resp.status === 200 && resp.headers['x-ai-provider'] === 'amd' && resp.headers['x-ai-model'] === 'gpt-oss-120b');
    ok('AMD：JSON 帶 provider/model/label/structured', resp.json.provider === 'amd' && resp.json.model === 'gpt-oss-120b' && resp.json.label === 'AMD gpt-oss-120b' && resp.json.structured === 'json_schema');
    ok('AMD：output 已正規化', resp.json.output.titles[0] === '秋遊台東');
    ok('AMD：用量記在 gpt-oss-120b 名下', usageLog.length === 1 && usageLog[0].model === 'gpt-oss-120b' && usageLog[0].u.thoughtTokens === 9);

    resp = await post({ task: 'unknown', input: {} }, { Authorization: 'Bearer good' });
    ok('未知 task 回 400', resp.status === 400 && resp.json.error === 'unknown task');
    resp = await post({ task: 'poster_place_descriptions', input: { places: [] } }, { Authorization: 'Bearer good' });
    ok('不合法 input 回 400', resp.status === 400 && resp.json.error === 'invalid input');

    amdText = '我不會輸出 JSON';
    resp = await post({ task: 'recap_ideas', input: recapInput }, { Authorization: 'Bearer good', 'X-Run-Id': 'run1' });
    ok('AMD 兩次都壞 → 502 invalid output，仍標 provider', resp.status === 502 && resp.json.error === 'invalid output' && resp.json.provider === 'amd');
    ok('失敗時標記 run 上游錯誤', usageLog.some((x) => x.error));

    amdEnabled = false;
    resp = await post({ task: 'recap_ideas', input: recapInput }, { Authorization: 'Bearer good' });
    ok('Vertex：200 且 x-ai-provider=vertex', resp.status === 200 && resp.headers['x-ai-provider'] === 'vertex' && resp.json.model === 'gemini-3-flash-preview');
    const vb = vertexCalls[0] && vertexCalls[0].body;
    ok('Vertex：送 JSON mime、thinkingBudget 0、systemInstruction', vb && vb.generationConfig.responseMimeType === 'application/json'
      && vb.generationConfig.thinkingConfig.thinkingBudget === 0 && vb.systemInstruction.parts[0].text.includes('旅遊'));
    ok('Vertex：金鑰由伺服器帶、模型固定', vertexCalls[0].url.startsWith('https://vertex.test/v1/publishers/google/models/gemini-3-flash-preview:generateContent?key=k'));
  } finally {
    server.close();
  }
}

async function live() {
  if (!/^https?:\/\//.test(process.env.AMD_LLM_BASE_URL) || process.env.AMD_LLM_BASE_URL.includes('example.invalid')) {
    console.log('SKIP  live：server/.env 沒有 AMD_LLM_BASE_URL');
    return;
  }
  amd._resetJsonSchemaSupport();
  const caller = tasks.makeAmdCaller(amd);
  const inputs = { recap_ideas: recapInput, memory_layout_edit: memoryInput, poster_place_descriptions: posterInput };
  const modes = new Set();
  for (const task of Object.keys(inputs)) {
    const t0 = Date.now();
    let structured = '';
    try {
      const r = await tasks.runTask({
        task, input: inputs[task],
        callModel: async (spec) => { const x = await caller({ ...spec, signal: AbortSignal.timeout(90000) }); structured = x.structured; return x; }
      });
      modes.add(structured);
      ok(`live ${task}：符合 schema（${structured}，第 ${r.attempts} 次）`, true);
      if (task === 'memory_layout_edit') {
        // 「卡片放大一點，標題移到下面」：真的有照做，而不是抄回原值（範圍限制誤判時會這樣）
        ok('live memory_layout_edit：照要求調整（卡片變大、標題下移）', r.output.cards.every((c) => c.w > 0.3) && r.output.titleBand.cy > 0.5);
      }
      console.log(`      ${Date.now() - t0}ms：${JSON.stringify(r.output).slice(0, 220)}`);
    } catch (e) {
      ok(`live ${task}`, false, e.message);
    }
  }
  ok('live 端點接受 json_schema（未降級成 json_object）', modes.size === 1 && modes.has('json_schema'));
}

offline()
  .then(() => (LIVE ? live() : null))
  .catch((e) => { fail++; console.log('FAIL  例外：' + (e && e.stack || e)); })
  .then(() => {
    console.log(`\n${pass} passed, ${fail} failed`);
    process.exit(fail ? 1 : 0);
  });
