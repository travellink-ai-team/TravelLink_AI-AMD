'use strict';
/* POST /api/agent/precheck：主動式旅途管家的輕量預檢（不呼叫 LLM、不開 SSE）。
   請求格式與 /api/agent/replan 相同（{ trip, trigger, scenario? }），trigger.type 只認 weather／delay。
   回應：{ alert, kind, issues[], headline, simulated, weather? }。見 docs/amd-agent/proactive-concierge.md。
   獨立成一個檔案、由 server.js 一行掛上，避免跟其他 /api/agent/* 的改動互相衝突。 */
const { precheck } = require('./precheck');

function sanitizeScenario(raw) {
  const out = {};
  const rain = raw && raw.rain;
  if (rain && typeof rain === 'object') {
    out.rain = { date: String(rain.date || ''), from: String(rain.from || ''), to: String(rain.to || ''), pop: Number(rain.pop) || 80 };
  }
  const delay = raw && raw.delay && Number(raw.delay.minutes);
  if (Number.isFinite(delay)) out.delay = { minutes: Math.min(600, Math.max(0, Math.round(delay))) };
  return Object.keys(out).length ? out : null;
}

function mount(app, { requireFirebaseUser, rateLimit }) {
  // 背景輪詢用：比 replan 寬鬆（不呼叫 AI），但仍限流，避免壞掉的用戶端狂打 CWA
  const limiter = rateLimit({
    windowMs: 10 * 60 * 1000,
    max: 60,
    standardHeaders: true,
    legacyHeaders: false,
    message: { error: 'rate limited', message: '檢查太頻繁，請稍後再試。' }
  });
  app.post('/api/agent/precheck', limiter, requireFirebaseUser, async (req, res) => {
    const body = req.body || {};
    const trigger = body.trigger && typeof body.trigger === 'object' ? body.trigger : { type: 'weather' };
    if (!['weather', 'delay'].includes(trigger.type || 'weather')) return res.status(400).json({ error: 'invalid trigger', message: '預檢只支援 weather 與 delay' });
    if (!body.trip || !Array.isArray(body.trip.stops)) return res.status(400).json({ error: 'invalid trip' });
    try {
      const out = await precheck({ trip: body.trip, trigger, scenario: sanitizeScenario(body.scenario) });
      if (out.error && trigger.type === 'delay') return res.status(400).json({ error: 'invalid delay request', message: out.error });
      res.set('Cache-Control', 'no-store');
      return res.json(out);
    } catch (err) {
      console.error('[agent] 預檢失敗：', err && err.message);
      return res.status(500).json({ error: 'precheck failed', message: '暫時無法檢查，請稍後再試。' });
    }
  });
}

module.exports = { mount, sanitizeScenario };
