/* ══════════════════════════════════════════════════
   /api/ai/task 前端 client（方案 E）
   ──────────────────────────────────────────────────
   零散的文字 AI 小任務（回顧標題、九宮格版面、插圖畫面描述…）改由後端定義 prompt 與輸出 schema，
   前端只送 { task, input }。後端在回應標頭 x-ai-provider／x-ai-model 標示實際用的模型，
   provider 是 amd 時，畫面顯示「AMD gpt-oss-120b」小標示；Vertex 時不顯示任何標示。

   只在代理模式（TRAVEL_APP_CONFIG.API_PROXY_BASE）可用；未設代理時 enabled() 為 false，
   呼叫端沿用原本的直連路徑。後端說明見 server/ai-tasks.js、docs/amd-agent/ai-task-inventory.md。
   ══════════════════════════════════════════════════ */
(function () {
  'use strict';

  function proxyBase() {
    return ((window.TRAVEL_APP_CONFIG && window.TRAVEL_APP_CONFIG.API_PROXY_BASE) || '').replace(/\/$/, '');
  }

  /**
   * @param {string} task  後端 TASKS 的 key
   * @param {object} input 任務輸入（後端會再淨化）
   * @param {{ headers?: object, timeoutMs?: number }} [opts] headers 通常傳 vertexAuthHeaders() 的結果（登入 token＋X-Run-Id）
   * @returns {Promise<{ output: object, provider: string, model: string, attempts: number }>}
   */
  async function request(task, input, opts) {
    var base = proxyBase();
    if (!base) {
      var noProxy = new Error('未設定後端代理');
      noProxy.code = 'no-proxy';
      throw noProxy;
    }
    var o = opts || {};
    var headers = Object.assign({ 'Content-Type': 'application/json' }, o.headers || {});
    var ctrl = new AbortController();
    // 後端每次嘗試 30 秒、最多兩次；前端多留一點
    var timer = setTimeout(function () { ctrl.abort(); }, o.timeoutMs || 70000);
    try {
      var res = await fetch(base + '/ai/task', {
        method: 'POST', headers: headers, signal: ctrl.signal,
        body: JSON.stringify({ task: task, input: input })
      });
      var data = null;
      try { data = await res.json(); } catch (_e) { /* 非 JSON（例如 nginx 錯誤頁） */ }
      var provider = res.headers.get('x-ai-provider') || (data && data.provider) || '';
      var model = res.headers.get('x-ai-model') || (data && data.model) || '';
      if (!res.ok || !data || !data.ok) {
        var msg = res.status === 401 ? '請先登入，登入後才能使用 AI 功能。'
          : res.status === 429 ? 'AI 請求過於頻繁，請休息一下再試。'
          : ((data && data.message) || ('AI 任務失敗（' + res.status + '）'));
        var err = new Error(msg);
        err.status = res.status;
        err.provider = provider;
        throw err;
      }
      return { output: data.output, provider: provider, model: model, attempts: data.attempts || 1 };
    } finally {
      clearTimeout(timer);
    }
  }

  function isAmd(meta) {
    return !!meta && meta.provider === 'amd';
  }

  // 小標示：只在 AMD 時顯示；class 已設 nowrap，不會裂成兩行
  function badgeHtml(meta) {
    return isAmd(meta)
      ? '<span class="ai-provider-badge" title="這段內容由 AMD gpt-oss-120b 生成">AMD gpt-oss-120b</span>'
      : '';
  }

  window.WAI_AI_TASK = {
    request: request,
    isAmd: isAmd,
    badgeHtml: badgeHtml,
    enabled: function () { return !!proxyBase(); }
  };
})();
