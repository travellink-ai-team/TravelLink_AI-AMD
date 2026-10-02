'use strict';
/* ══════════════════════════════════════════════════
   AMD LLM 轉接層（InnoServe AMD AI 代理人組）
   ──────────────────────────────────────────────────
   前端照舊送 Gemini 格式到 /api/vertex/*；AI_PROVIDER=amd 時，
   代理把文字生成改送到 AMD / 工研院提供的 gpt-oss-120b（vLLM，OpenAI 相容），
   再把回應轉回 Gemini 格式。前端與 usage-tap 都不用改。

   範圍：只有文字生成（generateContent / streamGenerateContent）。
   圖片生成（gemini-3.1-flash-image）沒有對應模型，維持走 Vertex。

   ⚠ 端點只開放登記過的 IP，而且是 http——只能由後端呼叫，
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
    modelVersion: USAGE_MODEL_ID
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
    const usage = toUsageMetadata(evt.usage);
    if (!text && !finish && !usage) return '';

    const out = {};
    if (text || finish) {
      const cand = { content: { role: 'model', parts: [{ text }] }, index: 0 };
      if (finish) cand.finishReason = finish;
      out.candidates = [cand];
    }
    if (usage) out.usageMetadata = usage;
    out.modelVersion = USAGE_MODEL_ID;
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
    /** 上游結束時沖出殘行 */
    end() {
      const rest = buffer;
      buffer = '';
      return rest ? translateLine(rest) : '';
    }
  };
}

function chatCompletionsUrl() {
  return AMD_LLM_BASE_URL + '/chat/completions';
}

function upstreamHeaders() {
  return { 'Content-Type': 'application/json', Authorization: 'Bearer ' + AMD_LLM_API_KEY };
}

module.exports = {
  USAGE_MODEL_ID,
  isEnabled,
  shouldRoute,
  toOpenAiRequest,
  fromOpenAiResponse,
  createSseTranslator,
  chatCompletionsUrl,
  upstreamHeaders
};
