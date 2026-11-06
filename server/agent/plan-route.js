'use strict';
/* ══════════════════════════════════════════════════
   POST /api/agent/plan（SSE）：行程生成 Agent（方案 C，一日遊）
   ──────────────────────────────────────────────────
   比照 /api/agent/replan：需登入、限流、SSE 逐步回報、usage 記到 generation_runs。
   每一步（查天氣、搜尋景點、排程驗證…）即時推給前端的生成畫面；
   最後一個事件是 itinerary 或 error。失敗時前端自動退回原本的一次生成流程。
   獨立成模組掛在 server.js 上，避免與其他功能的路由改動衝突。
   ══════════════════════════════════════════════════ */
const { runPlanAgent } = require('./plan-agent');

const MAX_DAY_MINUTES = 14 * 60;
const PING_MS = 5000;

/** 驗證 body.request；回傳 { error } 或 { request, scenario } */
function parsePlanBody(body) {
  const b = body || {};
  const r = b.request && typeof b.request === 'object' && !Array.isArray(b.request) ? b.request : null;
  if (!r) return { error: 'invalid request' };
  if (!String(r.dest || '').trim()) return { error: 'dest required' };
  // 前端用 Places 查好的起訖點座標：只收台灣範圍內的數字，其餘一律當作沒給
  const coordOf = (c) => {
    const lat = Number(c && c.lat), lng = Number(c && c.lng);
    return Number.isFinite(lat) && Number.isFinite(lng) && lat > 21.5 && lat < 26.5 && lng > 119 && lng < 123 ? { lat, lng } : null;
  };
  const dur = Number(r.durationMin);
  if (r.durationMin !== undefined && (!Number.isFinite(dur) || dur < 60)) return { error: 'invalid durationMin' };
  // 一日遊才走 Agent；多日由前端維持原本的一次生成
  if (Number(r.days) > 1 || dur > MAX_DAY_MINUTES) return { error: 'single day only', message: '目前只有一日遊支援 AI 規劃代理人' };
  const request = {
    dest: String(r.dest).slice(0, 20),
    date: String(r.date || '').slice(0, 10),
    startTime: String(r.startTime || '').slice(0, 5),
    durationMin: Number.isFinite(dur) ? dur : 480,
    people: r.people,
    pace: String(r.pace || '').slice(0, 10),
    theme: String(r.theme || '').slice(0, 20),
    interests: Array.isArray(r.interests) ? r.interests.slice(0, 8).map((x) => String(x).slice(0, 12)) : [],
    budgetPerPerson: Number(r.budgetPerPerson) || null,
    desiredSpots: String(r.desiredSpots || '').slice(0, 200),
    startLocation: String(r.startLocation || '').slice(0, 60),
    endLocation: String(r.endLocation || '').slice(0, 60),
    startCoord: coordOf(r.startCoord),
    endCoord: coordOf(r.endCoord),
    avoidNames: Array.isArray(r.avoidNames) ? r.avoidNames.slice(0, 20).map((x) => String(x).slice(0, 40)).filter(Boolean) : [],
    transportMode: String(r.transportMode || '').slice(0, 12),
    title: String(r.title || '').slice(0, 40)
  };
  // demo 情境注入：只改工具回傳的天氣，Agent 流程照常真實執行
  const rain = b.scenario && b.scenario.rain;
  const scenario = rain && typeof rain === 'object'
    ? { rain: { date: String(rain.date || ''), from: String(rain.from || ''), to: String(rain.to || ''), pop: Number(rain.pop) || 80 } }
    : null;
  return { request, scenario };
}

/**
 * @param {import('express').Express} app
 * @param {{ requireFirebaseUser: Function, rateLimit: Function, genRuns: object, amdLlm: object, ipKeyGenerator?: Function, loadCrowdSignals?: Function, run?: Function, pingMs?: number }} deps
 */
function mountAgentPlan(app, deps) {
  const { requireFirebaseUser, rateLimit, genRuns, amdLlm, ipKeyGenerator } = deps;
  const run = deps.run || runPlanAgent;
  const planLimiter = rateLimit({
    windowMs: 10 * 60 * 1000,
    // 一次規劃最多 18 次 LLM 呼叫；這裡限的是「規劃次數」。
    // 放在驗證之後、用帳號計算：展場多人共用同一個對外 IP（場館 Wi-Fi NAT），用 IP 算會互相吃額度
    max: 60,
    standardHeaders: true,
    legacyHeaders: false,
    keyGenerator: (req, res) => (req.user && req.user.uid) || (ipKeyGenerator ? ipKeyGenerator(req, res) : req.ip),
    message: { error: 'rate limited', message: 'AI 規劃代理人使用太頻繁，請稍後再試。' }
  });

  app.post('/api/agent/plan', requireFirebaseUser, planLimiter, async (req, res) => {
    const parsed = parsePlanBody(req.body);
    if (parsed.error) return res.status(400).json({ error: parsed.error, ...(parsed.message ? { message: parsed.message } : {}) });

    const genRun = genRuns.getOwnedRun(req.headers['x-run-id'], req.user.uid);
    if (genRun) genRuns.retain(genRun);

    res.status(200);
    res.set('Content-Type', 'text/event-stream; charset=utf-8');
    res.set('Cache-Control', 'no-cache');
    res.set('X-Accel-Buffering', 'no');
    res.flushHeaders();
    let closed = false;
    // 前端放棄（逾時退回一般生成、關頁）就停止 Agent，別在 AMD 上繼續跑一份沒人要的
    const controller = new AbortController();
    res.on('close', () => { closed = true; if (!res.writableEnded) controller.abort(); });
    const send = (e) => { if (!closed) res.write('data: ' + JSON.stringify(e) + '\n\n'); };
    // 心跳：等 AI 回應時（含 5xx 重試、改走備援代理）沒有事件，前端 20 秒沒收到東西就會放棄。
    // SSE 註解行前端不當事件處理，但會重置它的等待計時
    const ping = setInterval(() => { if (!closed) res.write(': ping\n\n'); }, deps.pingMs || PING_MS);

    try {
      let crowdSignals = null;
      if (deps.loadCrowdSignals) {
        try {
          crowdSignals = await Promise.race([
            deps.loadCrowdSignals(parsed.request.dest, parsed.request.date),
            new Promise((resolve) => setTimeout(() => resolve(null), 2500))
          ]);
        } catch (_e) { /* 訊號不可用時仍正常規劃 */ }
      }
      const last = await run({
        request: parsed.request,
        crowdSignals,
        scenario: parsed.scenario,
        signal: controller.signal,
        onEvent: send,
        onUsage: (u) => {
          if (!genRun) return;
          genRuns.addGeminiUsage(genRun, amdLlm.USAGE_MODEL_ID, {
            promptTokens: Number(u.prompt_tokens) || 0,
            outputTokens: Number(u.completion_tokens) || 0,
            thoughtTokens: 0
          });
        }
      });
      if (genRun && last && last.type === 'error' && last.code === 'upstream') genRuns.markUpstreamError(genRun);
    } catch (err) {
      console.error('[plan-agent] 執行失敗：', err && err.message);
      if (genRun) genRuns.markUpstreamError(genRun);
      send({ type: 'error', code: 'internal', message: 'AI 規劃代理人發生錯誤，改用一般生成。' });
    }
    clearInterval(ping);
    if (genRun) genRuns.release(genRun).catch(() => {});
    res.end();
  });
}

module.exports = { mountAgentPlan, parsePlanBody };
