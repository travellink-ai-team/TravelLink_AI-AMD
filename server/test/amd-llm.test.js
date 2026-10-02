// amd-llm.js 的格式轉換測試（Gemini ⇄ OpenAI）。
// 離線測試不需要網路；加上 --live 會實際呼叫 server/.env 裡的 AMD_LLM_BASE_URL，
// 並走過 usage-tap，確認代理記帳拿得到用量。
//   node test/amd-llm.test.js
//   node test/amd-llm.test.js --live
'use strict';
const path = require('path');
require('dotenv').config({ path: path.join(__dirname, '..', '.env') });
process.env.AI_PROVIDER = 'amd';
if (!process.env.AMD_LLM_BASE_URL) process.env.AMD_LLM_BASE_URL = 'http://example.invalid/v1';
const amd = require('../amd-llm');
const { createUsageTap } = require('../usage-tap');

let pass = 0, fail = 0;
const ok = (name, cond) => { if (cond) { pass++; console.log('PASS  ' + name); } else { fail++; console.log('FAIL  ' + name); } };

// ── 路由：只有文字模型改走 AMD ──
ok('文字模型改走 AMD', amd.shouldRoute('gemini-3-flash-preview'));
ok('圖片模型仍走 Vertex', !amd.shouldRoute('gemini-3.1-flash-image'));

// ── 請求轉換 ──
const geminiReq = {
  systemInstruction: { parts: [{ text: '只輸出 JSON' }] },
  contents: [
    { role: 'user', parts: [{ text: '第一句' }, { text: '第二句' }] },
    { role: 'model', parts: [{ text: '{"a":1}' }] },
    { role: 'user', parts: [{ text: '再一次' }] }
  ],
  generationConfig: { responseMimeType: 'application/json', temperature: 0.6, maxOutputTokens: 2048, thinkingConfig: { thinkingBudget: 0 } }
};
const oa = amd.toOpenAiRequest(geminiReq, { stream: false });
ok('systemInstruction → system', oa.messages[0].role === 'system' && oa.messages[0].content === '只輸出 JSON');
ok('多個 parts 合併成一段', oa.messages[1].content === '第一句第二句');
ok('model → assistant', oa.messages[2].role === 'assistant');
ok('JSON 模式', oa.response_format && oa.response_format.type === 'json_object');
ok('temperature 保留', oa.temperature === 0.6);
ok('max_tokens 加上推理餘裕', oa.max_tokens > 2048);
ok('不帶 Gemini 專用欄位', !('thinkingConfig' in oa) && !('generationConfig' in oa));
ok('非串流不帶 stream', !oa.stream);
const oaStream = amd.toOpenAiRequest(geminiReq, { stream: true });
ok('串流要求最後附 usage', oaStream.stream === true && oaStream.stream_options.include_usage === true);

let rejected = null;
try { amd.toOpenAiRequest({ contents: [{ role: 'user', parts: [{ inlineData: { mimeType: 'image/png', data: 'x' } }] }] }, { stream: false }); }
catch (e) { rejected = e; }
ok('圖片內容回 400 而不是靜默丟掉', rejected && rejected.status === 400);

// ── 非串流回應轉換 ──
const gem = amd.fromOpenAiResponse({
  choices: [{ message: { content: '{"stops":[]}', reasoning_content: '想一下' }, finish_reason: 'length' }],
  usage: { prompt_tokens: 100, completion_tokens: 50, total_tokens: 150, completion_tokens_details: { reasoning_tokens: 20 } }
});
ok('文字放在 candidates[0].content.parts[0].text', gem.candidates[0].content.parts[0].text === '{"stops":[]}');
ok('推理內容不外流', !JSON.stringify(gem).includes('想一下'));
ok('length → MAX_TOKENS', gem.candidates[0].finishReason === 'MAX_TOKENS');
ok('usage 拆出推理 tokens', gem.usageMetadata.candidatesTokenCount === 30 && gem.usageMetadata.thoughtsTokenCount === 20);

// ── 串流轉換：故意把事件切在奇怪的位置 ──
const upstreamSse = [
  'data: {"choices":[{"delta":{"role":"assistant","content":""}}]}\n\n',
  'data: {"choices":[{"delta":{"reasoning":"User wants"}}]}\n\n',
  'data: {"choices":[{"delta":{"content":"{\\"st"}}]}\n\ndata: {"choi',
  'ces":[{"delta":{"content":"ops\\":[]}"},"finish_reason":"stop"}]}\n\n',
  'data: {"choices":[],"usage":{"prompt_tokens":7,"completion_tokens":5,"total_tokens":12}}\n\n',
  'data: [DONE]\n\n'
].join('');
const tr = amd.createSseTranslator();
let translated = '';
for (let i = 0; i < upstreamSse.length; i += 13) translated += tr.push(upstreamSse.slice(i, i + 13));
translated += tr.end();
let text = '', finishReason = '', usage = null;
translated.split('\n').forEach((line) => {
  if (!line.trim().startsWith('data:')) return;
  const evt = JSON.parse(line.trim().slice(5));
  const c = evt.candidates && evt.candidates[0];
  if (c) { text += c.content.parts[0].text || ''; if (c.finishReason) finishReason = c.finishReason; }
  if (evt.usageMetadata) usage = evt.usageMetadata;
});
ok('串流文字完整拼回', text === '{"stops":[]}');
ok('串流不含推理內容', !translated.includes('User wants'));
ok('串流 finishReason', finishReason === 'STOP');
ok('串流最後帶 usageMetadata', usage && usage.promptTokenCount === 7 && usage.candidatesTokenCount === 5);
const tap = createUsageTap(true);
tap.feed(Buffer.from(translated));
const tapped = tap.end();
ok('usage-tap 讀得到轉換後的用量', tapped && tapped.promptTokens === 7 && tapped.outputTokens === 5);

async function live() {
  if (!/^https?:\/\//.test(process.env.AMD_LLM_BASE_URL) || process.env.AMD_LLM_BASE_URL.includes('example.invalid')) {
    console.log('SKIP  live：server/.env 沒有 AMD_LLM_BASE_URL');
    return;
  }
  const req = {
    contents: [{ role: 'user', parts: [{ text: '用繁體中文排台東市區半日遊 3 站，只輸出 JSON：{"stops":[{"name":"","time":"HH:MM"}]}' }] }],
    generationConfig: { responseMimeType: 'application/json', temperature: 0.6, maxOutputTokens: 1024, thinkingConfig: { thinkingBudget: 0 } }
  };
  for (const stream of [false, true]) {
    const t0 = Date.now();
    const r = await fetch(amd.chatCompletionsUrl(), {
      method: 'POST', headers: amd.upstreamHeaders(),
      body: JSON.stringify(amd.toOpenAiRequest(req, { stream })),
      signal: AbortSignal.timeout(90000)
    });
    ok(`live ${stream ? '串流' : '非串流'} HTTP 200`, r.ok);
    if (!r.ok) { console.log(await r.text()); continue; }
    let out = '', events = 0;
    const tapLive = createUsageTap(stream);
    if (stream) {
      const t = amd.createSseTranslator();
      const dec = new TextDecoder();
      const reader = r.body.getReader();
      for (;;) {
        const { value, done } = await reader.read();
        if (done) break;
        const chunk = t.push(dec.decode(value, { stream: true }));
        if (chunk) { events++; tapLive.feed(Buffer.from(chunk)); }
        chunk.split('\n').forEach((line) => {
          if (!line.startsWith('data:')) return;
          const c = JSON.parse(line.slice(5)).candidates;
          if (c) out += c[0].content.parts[0].text;
        });
      }
      tapLive.feed(Buffer.from(t.end()));
    } else {
      const g = amd.fromOpenAiResponse(await r.json());
      out = g.candidates[0].content.parts[0].text;
      tapLive.feed(Buffer.from(JSON.stringify(g)));
    }
    let parsed = null;
    try { parsed = JSON.parse(out); } catch (_e) {}
    ok(`live ${stream ? '串流' : '非串流'} 回傳可解析的 JSON`, parsed && Array.isArray(parsed.stops) && parsed.stops.length > 0);
    const u = tapLive.end();
    ok(`live ${stream ? '串流' : '非串流'} 記帳拿得到用量`, u && u.promptTokens > 0 && u.outputTokens > 0);
    console.log(`      ${Date.now() - t0}ms${stream ? `，${events} 塊` : ''}：${parsed ? parsed.stops.map((s) => s.time + ' ' + s.name).join('、') : out.slice(0, 120)}`);
  }
}

(process.argv.includes('--live') ? live() : Promise.resolve())
  .catch((e) => { fail++; console.log('FAIL  live 例外：' + e.message); })
  .then(() => {
    console.log(`\n${pass} passed, ${fail} failed`);
    process.exit(fail ? 1 : 0);
  });
