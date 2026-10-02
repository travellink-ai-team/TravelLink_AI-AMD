'use strict';
/* ══════════════════════════════════════════════════
   usageMetadata 旁路解析器
   （實作計畫 docs/API成本統計-實作計畫.md 第 3 步）
   ──────────────────────────────────────────────────
   ★ 這個檔案存在的唯一理由是「不阻斷串流」。

   Vertex 代理是逐 chunk 直接轉發並設 X-Accel-Buffering: no，
   讓 SSE 不被緩衝——生成畫面「站名逐一浮現」完全靠它
   （先前的 P1-6 效能工作）。

   若為了讀 usageMetadata 而改成
       const text = await upstream.text();   // ← 絕對不可以
   會讓生成退回「轉圈到最後才一次出現」，把那些效能工作整個吃掉。

   因此本模組設計成「餵什麼看什麼」：代理照舊 res.write() 逐塊送出，
   同一塊 bytes 順便丟進 feed()。解析失敗一律靜默——
   記帳失敗絕不可以影響使用者拿到回應。
   ══════════════════════════════════════════════════ */

// 非串流回應要湊齊整包才能 JSON.parse，但不能無上限累積（避免被大回應撐爆記憶體）。
// 正常行程生成的 JSON 回應遠小於此。超過就放棄記帳，回應本身照常轉發。
const MAX_BUFFER_BYTES = 8 * 1024 * 1024;

function pickUsage(usageMetadata) {
  if (!usageMetadata || typeof usageMetadata !== 'object') return null;
  const prompt = Number(usageMetadata.promptTokenCount) || 0;
  const output = Number(usageMetadata.candidatesTokenCount) || 0;
  const thoughts = Number(usageMetadata.thoughtsTokenCount) || 0;
  if (prompt === 0 && output === 0 && thoughts === 0) return null;
  // 輸出與思考同價，但分開保存：拆得開才能事後解釋「錢花在 thinking 上」
  return { promptTokens: prompt, outputTokens: output, thoughtTokens: thoughts };
}

/**
 * @param {boolean} isSse 是否為 SSE（呼叫端依 alt=sse 或 content-type 判斷）
 */
function createUsageTap(isSse) {
  let lastUsage = null;      // 串流：以最後一個帶 usageMetadata 的事件為準
  let sseBuffer = '';        // 串流：跨 chunk 的未完成行
  let jsonChunks = [];       // 非串流：整包累積
  let jsonBytes = 0;
  let overflowed = false;

  function feedSse(text) {
    sseBuffer += text;
    // 只處理已經完整的行，最後一段殘行留到下一塊
    const lines = sseBuffer.split('\n');
    sseBuffer = lines.pop() || '';
    for (const rawLine of lines) {
      const line = rawLine.trim();
      if (!line.startsWith('data:')) continue;
      const payload = line.slice(5).trim();
      if (!payload || payload === '[DONE]') continue;
      try {
        const evt = JSON.parse(payload);
        const u = pickUsage(evt && evt.usageMetadata);
        if (u) lastUsage = u;
      } catch (_e) {
        // 半包或非 JSON 的事件行：忽略即可，不影響轉發
      }
    }
  }

  return {
    /** 餵入一塊剛剛（或即將）轉發出去的 bytes。必須永不拋例外。 */
    feed(chunk) {
      try {
        if (overflowed) return;
        if (isSse) {
          feedSse(Buffer.from(chunk).toString('utf8'));
        } else {
          jsonBytes += chunk.length;
          if (jsonBytes > MAX_BUFFER_BYTES) {
            overflowed = true;
            jsonChunks = [];
            return;
          }
          jsonChunks.push(Buffer.from(chunk));
        }
      } catch (_e) { /* 記帳不可影響轉發 */ }
    },

    /** 串流結束後呼叫，取出這次請求的用量；沒有可用資料回 null。 */
    end() {
      try {
        if (isSse) {
          if (sseBuffer.trim()) feedSse('\n'); // 沖出最後一行殘留
          return lastUsage;
        }
        if (overflowed || !jsonChunks.length) return null;
        const body = Buffer.concat(jsonChunks).toString('utf8');
        const data = JSON.parse(body);
        return pickUsage(data && data.usageMetadata);
      } catch (_e) {
        return null;
      }
    }
  };
}

/** 從 Vertex 上游路徑取出 model id，供 per-model 計價。 */
function modelIdFromPath(upstreamPath) {
  const m = String(upstreamPath || '').match(/\/models\/([^:/]+):/);
  return m ? m[1] : '';
}

module.exports = { createUsageTap, modelIdFromPath, MAX_BUFFER_BYTES };
