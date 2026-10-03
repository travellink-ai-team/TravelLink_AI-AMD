// 直連工研院失敗時自動改走備援代理（amd-llm.js postChatCompletions）。
// 全部用本機假伺服器模擬，不打真的端點：node test/amd-failover.test.js
'use strict';
const http = require('http');
const path = require('path');

let pass = 0, fail = 0;
const ok = (name, cond, extra) => { if (cond) { pass++; console.log('PASS  ' + name); } else { fail++; console.log('FAIL  ' + name + (extra ? '\n      ' + extra : '')); } };

// 假的 OpenAI 相容端點：記錄被打了幾次、帶了什麼金鑰，回傳指定狀態碼
function fakeServer(status) {
  return new Promise((resolve) => {
    const s = { hits: 0, auth: [], status };
    s.server = http.createServer((req, res) => {
      let body = '';
      req.on('data', (d) => { body += d; });
      req.on('end', () => {
        s.hits++;
        s.auth.push(req.headers.authorization);
        res.writeHead(s.status, { 'Content-Type': 'application/json' });
        res.end(JSON.stringify({ model: 'openai/gpt-oss-120b', choices: [{ message: { role: 'assistant', content: 'ok' }, finish_reason: 'stop' }], usage: { prompt_tokens: 1, completion_tokens: 1, total_tokens: 2 } }));
      });
    }).listen(0, '127.0.0.1', () => { s.url = `http://127.0.0.1:${s.server.address().port}/v1`; resolve(s); });
  });
}
// 一個保證沒人在聽的埠：開了馬上關
const deadUrl = () => new Promise((resolve) => {
  const tmp = http.createServer().listen(0, '127.0.0.1', () => {
    const port = tmp.address().port;
    tmp.close(() => resolve(`http://127.0.0.1:${port}/v1`));
  });
});

// 設定是在 require 時讀的，所以每個情境都用新的環境變數重新載入模組
function load(env) {
  for (const k of Object.keys(process.env)) if (k.startsWith('AMD_LLM_')) delete process.env[k];
  Object.assign(process.env, { AI_PROVIDER: 'amd' }, env);
  delete require.cache[require.resolve(path.join(__dirname, '..', 'amd-llm.js'))];
  return require('../amd-llm');
}

const body = JSON.stringify({ model: 'openai/gpt-oss-120b', messages: [{ role: 'user', content: 'hi' }] });
const silence = () => { const w = console.warn, l = console.log; console.warn = () => {}; console.log = () => {}; return () => { console.warn = w; console.log = l; }; };

(async () => {
  const fallback = await fakeServer(200);
  const primary = await fakeServer(200);
  const dead = await deadUrl();
  const FB = { AMD_LLM_FALLBACK_BASE_URL: fallback.url, AMD_LLM_FALLBACK_API_KEY: 'test-fallback-key' };

  // 1. 直連連不上 → 改走備援，帶的是備援的金鑰
  let amd = load({ AMD_LLM_BASE_URL: dead, ...FB });
  let restore = silence();
  let r = await amd.postChatCompletions(body);
  restore();
  ok('直連連不上 → 改走備援', r.upstream === 'fallback' && r.response.status === 200);
  ok('備援請求帶的是備援金鑰', fallback.auth[fallback.auth.length - 1] === 'Bearer test-fallback-key');

  // 2. 冷卻期內直接走備援，不再等直連逾時
  const before = fallback.hits;
  r = await amd.postChatCompletions(body);
  ok('冷卻期內直接走備援', r.upstream === 'fallback' && fallback.hits === before + 1);

  // 3. 直連回 403（被防火牆擋）→ 改走備援；5xx 不切
  primary.status = 403;
  amd = load({ AMD_LLM_BASE_URL: primary.url, ...FB });
  restore = silence();
  r = await amd.postChatCompletions(body);
  restore();
  ok('直連回 403 → 改走備援', r.upstream === 'fallback');
  primary.status = 500;
  amd = load({ AMD_LLM_BASE_URL: primary.url, ...FB });
  r = await amd.postChatCompletions(body);
  ok('直連回 500 不切換（交給原本的重試）', r.upstream === 'primary' && r.response.status === 500);

  // 4. 冷卻期過了、直連恢復 → 切回直連
  primary.status = 200;
  amd = load({ AMD_LLM_BASE_URL: dead, ...FB, AMD_LLM_FALLBACK_COOLDOWN_MS: '150' });
  restore = silence();
  await amd.postChatCompletions(body);          // 觸發切換
  restore();
  await new Promise((res) => setTimeout(res, 200));
  // 模擬「工研院恢復」：同一個模組實例改不了網址，所以用另一個實例驗證冷卻後會先試直連
  amd = load({ AMD_LLM_BASE_URL: primary.url, ...FB, AMD_LLM_FALLBACK_COOLDOWN_MS: '150' });
  const pHits = primary.hits;
  r = await amd.postChatCompletions(body);
  ok('沒在冷卻期 → 先試直連，正常就用直連', r.upstream === 'primary' && primary.hits === pHits + 1);

  // 5. 同一個實例：冷卻期過後會再試直連（直連還是死的就再切一次）
  amd = load({ AMD_LLM_BASE_URL: dead, ...FB, AMD_LLM_FALLBACK_COOLDOWN_MS: '150' });
  restore = silence();
  const logs = [];
  console.warn = (m) => logs.push(m);
  await amd.postChatCompletions(body);
  await new Promise((res) => setTimeout(res, 200));
  await amd.postChatCompletions(body);
  restore();
  ok('冷卻期過後重試直連（失敗再記一次切換）', logs.filter((m) => /改走備援代理/.test(m)).length === 2, JSON.stringify(logs));

  // 6. 呼叫端自己取消：不切換
  amd = load({ AMD_LLM_BASE_URL: dead, ...FB });
  const fbHits = fallback.hits;
  let aborted = null;
  try { await amd.postChatCompletions(body, { signal: AbortSignal.abort() }); } catch (e) { aborted = e; }
  ok('呼叫端取消 → 不切換、照樣丟錯', aborted && fallback.hits === fbHits);

  // 7. 沒設備援 → 跟以前一樣直接丟錯
  amd = load({ AMD_LLM_BASE_URL: dead });
  let noFb = null;
  try { await amd.postChatCompletions(body); } catch (e) { noFb = e; }
  ok('沒設備援 → 直接丟錯（行為不變）', noFb && !amd.hasFallback());

  // 8. Agent 用的 chat() 也會切換，並回報走了哪一條
  amd = load({ AMD_LLM_BASE_URL: dead, ...FB });
  restore = silence();
  const c = await amd.chat({ messages: [{ role: 'user', content: 'hi' }] });
  restore();
  ok('Agent 的 chat() 也會切換', c.upstream === 'fallback' && c.model === 'openai/gpt-oss-120b');

  fallback.server.close(); primary.server.close();
  console.log(`\n${pass} passed, ${fail} failed`);
  process.exit(fail ? 1 : 0);
})().catch((e) => { console.error(e); process.exit(1); });
