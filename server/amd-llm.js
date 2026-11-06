'use strict';
/* ══════════════════════════════════════════════════
   AMD LLM 轉接層（InnoServe AMD AI 代理人組）
   ──────────────────────────────────────────────────
   前端照舊送 Gemini 格式到 /api/vertex/*；AI_PROVIDER=amd 時，
   代理把文字生成改送到 AMD / 工研院提供的 gpt-oss-120b（vLLM，OpenAI 相容），
   再把回應轉回 Gemini 格式。前端與 usage-tap 都不用改。

   範圍：只有文字生成（generateContent / streamGenerateContent）。
   圖片生成（gemini-3-pro-image）沒有對應模型，維持走 Vertex。

   ⚠ 端點是 http——只能由後端呼叫（正式站是 https，瀏覽器會擋 mixed content）。
     工研院說會開通登記的 IP，但 2026-10-02 實測未登記的 IP 也連得上；日後收緊的話，
     把 AMD_LLM_BASE_URL 改指登記過 IP 的轉送機並重啟代理即可，
     網址放在 server/.env 的 AMD_LLM_BASE_URL，不可寫進 repo 或前端。
   ══════════════════════════════════════════════════ */

const AMD_LLM_BASE_URL = String(process.env.AMD_LLM_BASE_URL || '').trim().replace(/\/+$/, '');
const AMD_LLM_MODEL = String(process.env.AMD_LLM_MODEL || 'openai/gpt-oss-120b').trim();
// vLLM 端點不驗金鑰，但 OpenAI 相容介面仍要求帶 Authorization
const AMD_LLM_API_KEY = String(process.env.AMD_LLM_API_KEY || 'dummy-key').trim();
// gpt-oss 一定會推理，無法像 Gemini 設 thinkingBudget: 0；low 是最接近的設定
const AMD_LLM_REASONING_EFFORT = String(process.env.AMD_LLM_REASONING_EFFORT || 'low').trim();
// 推理文字也吃 max_tokens。前端的上限是照 Gemini（thinking 關閉）抓的，
// 直接沿用會讓推理把 JSON 擠到截斷，所以額外加一段餘裕。
const REASONING_HEADROOM = Number(process.env.AMD_LLM_REASONING_HEADROOM) || 1024;

// 計價／記帳用的模型名稱（pricing.js 的 GEMINI_RATES 以此為 key）
const USAGE_MODEL_ID = 'gpt-oss-120b';

// 只有這些前端模型改走 AMD；其餘（圖片模型）照舊送 Vertex
const TEXT_MODELS = new Set(['gemini-3-flash-preview']);

function isEnabled() {
  return String(process.env.AI_PROVIDER || '').trim().toLowerCase() === 'amd' && Boolean(AMD_LLM_BASE_URL);
}

function shouldRoute(modelId) {
  return isEnabled() && TEXT_MODELS.has(modelId);
}

function partsToText(parts) {
  if (!Array.isArray(parts)) return '';
  return parts.map((p) => {
    if (p && typeof p.text === 'string') return p.text;
    // 文字模型沒有理由收到圖片或檔案；靜默丟掉會讓輸出莫名變差，直接拒絕
    const err = new Error('AMD LLM 只支援文字內容');
    err.status = 400;
    throw err;
  }).join('');
}

/** Gemini generateContent body → OpenAI chat.completions body */
function toOpenAiRequest(body, { stream }) {
  const b = body || {};
  const cfg = b.generationConfig || {};
  const messages = [];

  const sys = b.systemInstruction || b.system_instruction;
  if (sys) {
    const text = typeof sys === 'string' ? sys : partsToText(sys.parts);
    if (text) messages.push({ role: 'system', content: text });
  }
  for (const c of (Array.isArray(b.contents) ? b.contents : [])) {
    const role = c && c.role === 'model' ? 'assistant' : 'user';
    messages.push({ role, content: partsToText(c && c.parts) });
  }
  if (!messages.some((m) => m.role === 'user')) {
    const err = new Error('缺少使用者訊息');
    err.status = 400;
    throw err;
  }

  const out = {
    model: AMD_LLM_MODEL,
    messages,
    max_tokens: (Number(cfg.maxOutputTokens) || 8192) + REASONING_HEADROOM,
    reasoning_effort: AMD_LLM_REASONING_EFFORT
  };
  if (typeof cfg.temperature === 'number') out.temperature = cfg.temperature;
  if (typeof cfg.topP === 'number') out.top_p = cfg.topP;
  if (/json/i.test(String(cfg.responseMimeType || ''))) out.response_format = { type: 'json_object' };
  if (stream) {
    out.stream = true;
    out.stream_options = { include_usage: true };   // 最後一個事件才帶 usage
  }
  return out;
}

const FINISH_REASON = { stop: 'STOP', length: 'MAX_TOKENS', content_filter: 'SAFETY' };

function toUsageMetadata(u) {
  if (!u) return null;
  const prompt = Number(u.prompt_tokens) || 0;
  const completion = Number(u.completion_tokens) || 0;
  const reasoning = Number(u.completion_tokens_details && u.completion_tokens_details.reasoning_tokens) || 0;
  return {
    promptTokenCount: prompt,
    // OpenAI 的 completion_tokens 含推理；Gemini 的 candidates 不含，拆開才不會重複計
    candidatesTokenCount: Math.max(0, completion - reasoning),
    thoughtsTokenCount: reasoning,
    totalTokenCount: Number(u.total_tokens) || prompt + completion
  };
}

/** OpenAI chat.completions 回應 → Gemini generateContent 回應 */
function fromOpenAiResponse(data) {
  const choice = (data && Array.isArray(data.choices) && data.choices[0]) || {};
  const text = String((choice.message && choice.message.content) || '');
  const out = {
    candidates: [{
      content: { role: 'model', parts: [{ text }] },
      finishReason: FINISH_REASON[choice.finish_reason] || 'STOP',
      index: 0
    }],
    // 端點實際回報的模型（openai/gpt-oss-120b）；沒回報才退回常數。是「真的有打到 AMD」的證據
    modelVersion: (data && data.model) || USAGE_MODEL_ID
  };
  const usage = toUsageMetadata(data && data.usage);
  if (usage) out.usageMetadata = usage;
  return out;
}

/**
 * OpenAI SSE → Gemini SSE（alt=sse 格式：每個事件一行 `data: {...}`）。
 * 逐塊轉換、立刻送出，不等整包——前端「站名逐一浮現」靠的是這個。
 * 推理內容（delta.reasoning / reasoning_content）不轉發，Gemini 端 thinking 本來就是關的。
 */
function createSseTranslator() {
  let buffer = '';
  let collected = '';
  let lastFinish = '';

  function translateLine(rawLine) {
    const line = rawLine.trim();
    if (!line.startsWith('data:')) return '';
    const payload = line.slice(5).trim();
    if (!payload || payload === '[DONE]') return '';
    let evt;
    try { evt = JSON.parse(payload); } catch (_e) { return ''; }

    const choice = Array.isArray(evt.choices) && evt.choices[0];
    const text = choice && choice.delta && typeof choice.delta.content === 'string' ? choice.delta.content : '';
    const finish = choice && choice.finish_reason ? (FINISH_REASON[choice.finish_reason] || 'STOP') : '';
    if (collected.length < 200000) collected += text;
    if (finish) lastFinish = finish;
    const usage = toUsageMetadata(evt.usage);
    if (!text && !finish && !usage) return '';

    const out = {};
    if (text || finish) {
      const cand = { content: { role: 'model', parts: [{ text }] }, index: 0 };
      if (finish) cand.finishReason = finish;
      out.candidates = [cand];
    }
    if (usage) out.usageMetadata = usage;
    out.modelVersion = evt.model || USAGE_MODEL_ID;
    return 'data: ' + JSON.stringify(out) + '\r\n\r\n';
  }

  return {
    /** 餵入上游的一塊文字，回傳可立即送出的 Gemini SSE 文字（可能是空字串） */
    push(text) {
      buffer += text;
      const lines = buffer.split('\n');
      buffer = lines.pop() || '';
      return lines.map(translateLine).join('');
    },
    /** 串流結束後取完整輸出（留記錄用） */
    output() { return { text: collected, finish: lastFinish }; },
    /** 上游結束時沖出殘行 */
    end() {
      const rest = buffer;
      buffer = '';
      return rest ? translateLine(rest) : '';
    }
  };
}

// 行程生成偶爾沒有景點，事後查不到模型回了什麼。要求 stops 的請求若回得不對，把開頭記下來（內容是行程，沒有個資或金鑰）。
function noteGenerationOutput(body, text, finish) {
  try {
    const prompt = (Array.isArray(body && body.contents) ? body.contents : []).map((c) => partsToText(c && c.parts)).join('');
    if (!prompt.includes('"stops"')) return;
    const out = String(text || '').trim();
    let problem = '';
    if (!out) problem = '回應是空的';
    else if (finish === 'MAX_TOKENS') problem = '輸出被截斷';
    else {
      try {
        const parsed = JSON.parse(out);
        const plan = Array.isArray(parsed) ? parsed[0] : parsed;
        if (!plan || !Array.isArray(plan.stops) || !plan.stops.length) problem = '沒有 stops';
      } catch (_e) { problem = '不是有效 JSON'; }
    }
    if (problem) console.warn('[amd-llm] 行程輸出異常：' + problem + '｜finish=' + finish + '｜長度=' + out.length + '｜開頭=' + out.slice(0, 300).replace(/\s+/g, ' '));
  } catch (_e) { /* 留記錄失敗不影響回應 */ }
}

function chatCompletionsUrl() {
  return AMD_LLM_BASE_URL + '/chat/completions';
}

function upstreamHeaders() {
  return { 'Content-Type': 'application/json', Authorization: 'Bearer ' + AMD_LLM_API_KEY };
}

/* ── 自動切換到備援代理 ──────────────────────────────────
   工研院的申請只登記了組員 GCP VM 的 IP；目前沒擋，但哪天開始擋，直連就會連不上。
   組員在 VM 上架了反向代理（網址與金鑰見組員私訊，只放 .env）。
   設了 AMD_LLM_FALLBACK_BASE_URL 之後：
   - 直連「連不上或被擋」才切：連線錯誤（逾時、拒絕、重設、無法到達），或 401／403
     （vLLM 本身不驗金鑰，回 401／403 代表中間有防火牆或代理在擋）
   - 5xx 不切：那是模型偶發錯誤，Agent 本來就會重試
   - 切過去後 5 分鐘內直接走備援（不用每次都等直連逾時），之後再試直連，恢復就切回來
   沒設備援時行為跟以前完全一樣。 */
const FALLBACK_BASE_URL = String(process.env.AMD_LLM_FALLBACK_BASE_URL || '').trim().replace(/\/+$/, '');
const FALLBACK_API_KEY = String(process.env.AMD_LLM_FALLBACK_API_KEY || '').trim();
const PRIMARY_COOLDOWN_MS = Number(process.env.AMD_LLM_FALLBACK_COOLDOWN_MS) || 5 * 60 * 1000;
const NETWORK_ERROR_CODES = new Set([
  'UND_ERR_CONNECT_TIMEOUT', 'UND_ERR_SOCKET', 'ECONNREFUSED', 'ECONNRESET',
  'ETIMEDOUT', 'EHOSTUNREACH', 'ENETUNREACH', 'EAI_AGAIN', 'ENOTFOUND'
]);
let primaryDownUntil = 0;

function hasFallback() {
  return Boolean(FALLBACK_BASE_URL);
}

function isUnreachable(err) {
  if (!err || err.name === 'AbortError' || err.name === 'TimeoutError') return false;   // 呼叫端自己取消／逾時，不是被擋
  const code = (err.cause && err.cause.code) || err.code;
  return NETWORK_ERROR_CODES.has(code) || (err.name === 'TypeError' && /fetch failed/i.test(err.message));
}

function upstreamFor(which) {
  return which === 'fallback'
    ? { url: FALLBACK_BASE_URL + '/chat/completions', headers: { 'Content-Type': 'application/json', Authorization: 'Bearer ' + FALLBACK_API_KEY } }
    : { url: chatCompletionsUrl(), headers: upstreamHeaders() };
}

/**
 * POST /chat/completions，必要時自動改走備援代理。
 * @returns {Promise<{ response: Response, upstream: 'primary'|'fallback' }>}
 */
async function postChatCompletions(bodyText, { signal } = {}) {
  const send = (which) => {
    const u = upstreamFor(which);
    return fetch(u.url, { method: 'POST', headers: u.headers, body: bodyText, signal });
  };
  if (!hasFallback()) return { response: await send('primary'), upstream: 'primary' };

  // 冷卻期內直連已知有問題：直接走備援；備援也連不上才回頭試直連
  if (Date.now() < primaryDownUntil) {
    try {
      return { response: await send('fallback'), upstream: 'fallback' };
    } catch (err) {
      if (!isUnreachable(err) || (signal && signal.aborted)) throw err;
      console.warn('[amd-llm] 備援代理也連不上，改回試直連：' + ((err.cause && err.cause.code) || err.message));
    }
  }

  let reason = '';
  try {
    const response = await send('primary');
    if (response.status !== 401 && response.status !== 403) {
      if (primaryDownUntil) {
        primaryDownUntil = 0;
        console.log('[amd-llm] 直連恢復，切回工研院端點');
      }
      return { response, upstream: 'primary' };
    }
    reason = 'HTTP ' + response.status;
    response.body && response.body.cancel().catch(() => {});
  } catch (err) {
    if (!isUnreachable(err) || (signal && signal.aborted)) throw err;
    reason = (err.cause && err.cause.code) || err.message;
  }
  primaryDownUntil = Date.now() + PRIMARY_COOLDOWN_MS;
  console.warn(`[amd-llm] 直連失敗（${reason}），改走備援代理，${Math.round(PRIMARY_COOLDOWN_MS / 60000)} 分鐘後再試直連`);
  return { response: await send('fallback'), upstream: 'fallback' };
}

/**
 * Agent 用的 tool calling 呼叫（OpenAI 格式直進直出，不轉 Gemini）。
 * 回傳 { message, finishReason, usage }；message 只留 role/content/tool_calls，
 * vLLM 附帶的 reasoning 欄位不往回送，避免下一輪 prompt 越滾越大。
 */
async function chat({ messages, tools, temperature = 0.3, maxTokens = 2048, signal }) {
  const body = {
    model: AMD_LLM_MODEL,
    messages,
    temperature,
    max_tokens: maxTokens,
    reasoning_effort: AMD_LLM_REASONING_EFFORT
  };
  if (Array.isArray(tools) && tools.length) {
    body.tools = tools;
    body.tool_choice = 'auto';
  }
  const { response: r, upstream } = await postChatCompletions(JSON.stringify(body), { signal });
  if (!r.ok) {
    const detail = await r.text().catch(() => '');
    const err = new Error('AMD LLM ' + r.status + '：' + detail.slice(0, 200));
    err.status = r.status;
    throw err;
  }
  const data = await r.json();
  const choice = (data.choices && data.choices[0]) || {};
  const m = choice.message || {};
  const message = { role: 'assistant', content: m.content || '' };
  if (Array.isArray(m.tool_calls) && m.tool_calls.length) message.tool_calls = m.tool_calls;
  return { message, finishReason: choice.finish_reason || '', usage: data.usage || null, model: data.model || '', upstream };
}

/* ── 結構化輸出（/api/ai/task 用，見 ai-tasks.js）──────────────────
   vLLM 的 OpenAI 相容介面支援 response_format: json_schema（guided decoding），
   輸出被文法約束，不再只靠 prompt 叫它「只回 JSON」。
   端點若不支援（回 400 且訊息提到 response_format／json_schema／guided），
   這個行程內改用 json_object 並記一次 log；之後的請求不再先試 json_schema。
   既有的 Gemini 轉接（toOpenAiRequest）與 chat() 行為完全不變。 */
let jsonSchemaUnsupported = false;

/**
 * @returns {Promise<{ text, finishReason, usage, model, upstream, structured: 'json_schema'|'json_object' }>}
 */
async function chatJson({ messages, schema, schemaName = 'output', temperature = 0.4, maxTokens = 1024, signal }) {
  const build = (mode) => {
    const body = {
      model: AMD_LLM_MODEL,
      messages,
      temperature,
      max_tokens: maxTokens + REASONING_HEADROOM,
      reasoning_effort: AMD_LLM_REASONING_EFFORT
    };
    body.response_format = mode === 'json_schema'
      ? { type: 'json_schema', json_schema: { name: schemaName, schema, strict: true } }
      : { type: 'json_object' };
    return body;
  };
  let mode = schema && !jsonSchemaUnsupported ? 'json_schema' : 'json_object';
  let { response: r, upstream } = await postChatCompletions(JSON.stringify(build(mode)), { signal });
  if (r.status === 400 && mode === 'json_schema') {
    const detail = await r.text().catch(() => '');
    if (!/response_format|json_schema|guided|structured/i.test(detail)) {
      const err = new Error('AMD LLM 400：' + detail.slice(0, 200));
      err.status = 400;
      throw err;
    }
    jsonSchemaUnsupported = true;
    console.warn('[amd-llm] 端點不支援 json_schema，改用 json_object：' + detail.slice(0, 160));
    mode = 'json_object';
    ({ response: r, upstream } = await postChatCompletions(JSON.stringify(build(mode)), { signal }));
  }
  if (!r.ok) {
    const detail = await r.text().catch(() => '');
    const err = new Error('AMD LLM ' + r.status + '：' + detail.slice(0, 200));
    err.status = r.status;
    throw err;
  }
  const data = await r.json();
  const choice = (data.choices && data.choices[0]) || {};
  return {
    text: String((choice.message && choice.message.content) || ''),
    finishReason: choice.finish_reason || '',
    usage: toUsageMetadata(data.usage),
    model: data.model || USAGE_MODEL_ID,
    upstream,
    structured: mode
  };
}

module.exports = {
  chat,
  chatJson,
  _resetJsonSchemaSupport: () => { jsonSchemaUnsupported = false; },
  postChatCompletions,
  hasFallback,
  USAGE_MODEL_ID,
  isEnabled,
  shouldRoute,
  toOpenAiRequest,
  fromOpenAiResponse,
  noteGenerationOutput,
  createSseTranslator,
  chatCompletionsUrl,
  upstreamHeaders
};
