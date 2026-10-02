// 「真的有打到 AMD gpt-oss-120b」的證據：全部印端點自己回傳的欄位，不用程式裡寫死的常數。
//   node test/amd-evidence.js
// 端點網址只印主機遮罩，不外流（見 docs/amd-agent/ferry-sea-data-research.md 之外的 .env 說明）。
'use strict';
const path = require('path');
require('dotenv').config({ path: path.join(__dirname, '..', '.env') });
const amd = require('../amd-llm');
const { runAgent } = require('../agent/loop');

const base = String(process.env.AMD_LLM_BASE_URL || '').replace(/\/+$/, '');
const masked = base.replace(/\/\/([^/:]+)(:\d+)?/, (_m, host, port) => '//' + host.replace(/\d+\.\d+$/, 'x.x') + (port ? ':****' : ''));
const line = (k, v) => console.log(('  ' + k).padEnd(26) + v);

(async () => {
  console.log(`時間：${new Date().toLocaleString('zh-TW', { timeZone: 'Asia/Taipei' })}`);
  console.log(`AI_PROVIDER=${process.env.AI_PROVIDER || '(未設定)'}　端點：${masked}`);
  try {
    const ip = await fetch('https://api.ipify.org', { signal: AbortSignal.timeout(5000) }).then((r) => r.text());
    console.log(`本機對外 IP：${ip}`);
  } catch (_e) { /* 查不到不影響 */ }

  console.log('\n① GET /v1/models（端點上載入的模型）');
  const models = await fetch(base + '/models', { signal: AbortSignal.timeout(15000) }).then((r) => r.json());
  for (const m of models.data || []) line('id / owned_by', `${m.id} / ${m.owned_by}　max_model_len=${m.max_model_len}`);

  console.log('\n② POST /v1/chat/completions 原始回應欄位');
  const r = await fetch(amd.chatCompletionsUrl(), {
    method: 'POST', headers: amd.upstreamHeaders(), signal: AbortSignal.timeout(60000),
    body: JSON.stringify({ model: process.env.AMD_LLM_MODEL || 'openai/gpt-oss-120b', messages: [{ role: 'user', content: '用一句繁體中文介紹台東森林公園' }], max_tokens: 300, reasoning_effort: 'low' })
  });
  const j = await r.json();
  line('HTTP', r.status);
  line('id', j.id);
  line('model', j.model);
  line('system_fingerprint', j.system_fingerprint || '(無)');
  line('usage', JSON.stringify(j.usage));
  line('回答', String(((j.choices || [])[0] || {}).message ? j.choices[0].message.content : '').replace(/\s+/g, ' ').slice(0, 60));

  console.log('\n③ 旅程應變 Agent 實際執行（模擬午後下雨），記錄每次 LLM 呼叫回報的 model');
  const tomorrow = new Date(Date.now() + 32 * 3600e3).toISOString().slice(0, 10);
  const res = await runAgent({
    trip: { region: '台東', startDate: tomorrow, stops: [
      { id: 's1', day: 1, time: '09:00', stayMin: 120, name: '國立臺灣史前文化博物館' },
      { id: 's3', day: 1, time: '13:30', stayMin: 90, name: '臺東森林公園' },
      { id: 's4', day: 1, time: '15:30', stayMin: 60, name: '加路蘭' }
    ] },
    trigger: { type: 'weather' }, scenario: { rain: { date: tomorrow, from: '13:00', to: '17:00', pop: 80 } }, onEvent: () => {}
  });
  line('結果', `${res.type}　${res.summary || res.message || ''}`);
  line('upstreamModels', JSON.stringify(res.upstreamModels));
  line('LLM 呼叫 / tokens', `${res.usage.llmCalls} 次 / prompt ${res.usage.promptTokens} + completion ${res.usage.completionTokens}`);
  line('耗時', `${(res.ms / 1000).toFixed(1)} 秒`);
})().catch((e) => { console.error('失敗：', e.message); process.exit(1); });
