/*
 * recap-renderer.js — 後端「路線 clip」渲染器
 * 用 puppeteer-core 開系統 Chrome 無頭，載入 app/recap-headless.html（同一支前端渲染器）。
 *
 * 速度：優先用「headless 內 MediaRecorder 即時錄 canvas → webm」再 ffmpeg 轉 mp4——
 *       錄製時間≈影片長度（~14s），遠快於「每幀 CDP 截圖」（舊法 ~140s）。
 *       MediaRecorder 不可用時退回逐幀截圖（改 JPEG 降低傳輸成本）。
 */
'use strict';
const path = require('path');
const fs = require('fs');
const { spawn } = require('child_process');
const puppeteer = require('puppeteer-core');
const ffmpegPath = require('@ffmpeg-installer/ffmpeg').path;

const APP_DIR = path.resolve(__dirname, '..', 'app');
const HEADLESS_HTML = path.join(APP_DIR, 'recap-headless.html');

function findChrome() {
  var candidates = [
    'C:/Program Files/Google/Chrome/Application/chrome.exe',
    'C:/Program Files (x86)/Google/Chrome/Application/chrome.exe',
    process.env.CHROME_PATH
  ].filter(Boolean);
  for (var i = 0; i < candidates.length; i++) if (fs.existsSync(candidates[i])) return candidates[i];
  throw new Error('找不到 Chrome，請設 CHROME_PATH 環境變數');
}

function fileUrl(p) { return 'file:///' + p.replace(/\\/g, '/'); }

function runFfmpeg(args) {
  return new Promise((resolve, reject) => {
    const ff = spawn(ffmpegPath, args, { stdio: ['ignore', 'ignore', 'pipe'] });
    let err = '';
    ff.stderr.on('data', d => { err += d.toString(); if (err.length > 8000) err = err.slice(-8000); });
    ff.on('close', code => code === 0 ? resolve() : reject(new Error('ffmpeg exit ' + code + '\n' + err)));
    ff.on('error', reject);
  });
}

// webm → 直式 H.264 mp4
function transcodeWebmToMp4(webmPath, outPath, fps) {
  return runFfmpeg([
    '-y', '-i', webmPath,
    '-c:v', 'libx264', '-preset', 'veryfast', '-pix_fmt', 'yuv420p',
    '-r', String(fps), '-movflags', '+faststart', outPath
  ]);
}

// 逐幀截圖（fallback）：JPEG 串流 → mp4
async function capturePerFrame(page, canvasEl, totalMs, fps, outPath, onProgress) {
  const ff = spawn(ffmpegPath, [
    '-y', '-f', 'image2pipe', '-framerate', String(fps), '-i', '-',
    '-c:v', 'libx264', '-preset', 'veryfast', '-pix_fmt', 'yuv420p',
    '-r', String(fps), '-movflags', '+faststart', outPath
  ], { stdio: ['pipe', 'ignore', 'pipe'] });
  let ffErr = '';
  ff.stderr.on('data', d => { ffErr += d.toString(); if (ffErr.length > 8000) ffErr = ffErr.slice(-8000); });
  const ffDone = new Promise((res, rej) => {
    ff.on('close', code => code === 0 ? res() : rej(new Error('ffmpeg exit ' + code + '\n' + ffErr)));
    ff.on('error', rej);
  });
  const frameCount = Math.max(1, Math.round(totalMs / 1000 * fps));
  for (let i = 0; i < frameCount; i++) {
    const tMs = Math.min(totalMs, i * (1000 / fps));
    await page.evaluate(t => window.__recap.frame(t), tMs);
    const jpg = await canvasEl.screenshot({ type: 'jpeg', quality: 90 });
    if (!ff.stdin.write(jpg)) await new Promise(r => ff.stdin.once('drain', r));
    if (onProgress && (i % 15 === 0 || i === frameCount - 1)) onProgress((i + 1) / frameCount);
  }
  ff.stdin.end();
  await ffDone;
  return frameCount;
}

/**
 * 把 manifest 渲染成一支路線 mp4。
 * @param {object} manifest  由 recap-manifest.js 產生
 * @param {object} opts  { outPath, fps=30, width=1080, height=1920, chromePath?, onProgress? }
 */
async function renderRecapVideo(manifest, opts) {
  opts = opts || {};
  const fps = opts.fps || 30;
  const width = opts.width || 1080;
  const height = opts.height || 1920;
  const outPath = opts.outPath || path.join(require('os').tmpdir(), 'recap-' + Date.now() + '.mp4');
  const chromePath = opts.chromePath || findChrome();
  if (!fs.existsSync(HEADLESS_HTML)) throw new Error('缺少 ' + HEADLESS_HTML);

  const browser = await puppeteer.launch({
    executablePath: chromePath,
    headless: 'new',
    args: [
      '--no-sandbox', '--disable-setuid-sandbox', '--force-device-scale-factor=1', '--hide-scrollbars',
      // 防止無頭分頁被節流，確保即時錄製的 rAF 維持 fps
      '--disable-background-timer-throttling', '--disable-renderer-backgrounding',
      '--disable-backgrounding-occluded-windows', '--autoplay-policy=no-user-gesture-required'
    ]
  });
  try {
    const page = await browser.newPage();
    await page.setViewport({ width: width, height: height, deviceScaleFactor: 1 });
    await page.goto(fileUrl(HEADLESS_HTML), { waitUntil: 'load' });
    const totalMs = await page.evaluate((m, w, h) => window.__recap.init(m, w, h), manifest, width, height);

    // 主路徑：MediaRecorder 即時錄製
    let usedFallback = false;
    try {
      if (opts.onProgress) opts.onProgress(0.05);
      // MediaRecorder 即時錄製是黑箱、中間不回報，而其耗時 ≈ 影片總長（即時擷取）。
      // 用計時器按經過時間把進度從 5% 緩緩推到 ~80%，避免卡在 5% 後突然跳到 85%。
      let tick = null;
      if (opts.onProgress) {
        const startedAt = Date.now();
        tick = setInterval(() => {
          const frac = Math.min(0.8, 0.05 + ((Date.now() - startedAt) / Math.max(1, totalMs)) * 0.75);
          opts.onProgress(frac);
        }, 500);
      }
      let rec;
      try { rec = await page.evaluate((t, f) => window.__recap.record(t, f), totalMs, fps); }
      finally { if (tick) clearInterval(tick); }
      if (opts.onProgress) opts.onProgress(0.85);
      const b64 = String(rec.dataUrl).split(',')[1] || '';
      if (!b64) throw new Error('empty recording');
      const webmPath = outPath.replace(/\.mp4$/i, '') + '.webm';
      fs.writeFileSync(webmPath, Buffer.from(b64, 'base64'));
      await transcodeWebmToMp4(webmPath, outPath, fps);
      fs.promises.unlink(webmPath).catch(() => {});
      if (opts.onProgress) opts.onProgress(1);
    } catch (recErr) {
      console.warn('[recap] MediaRecorder 失敗，改逐幀截圖：', recErr && recErr.message);
      usedFallback = true;
      const canvas = await page.$('#c');
      await capturePerFrame(page, canvas, totalMs, fps, outPath, opts.onProgress);
    }
    return { outPath: outPath, totalMs: totalMs, width: width, height: height, fps: fps, mode: usedFallback ? 'per-frame' : 'mediarecorder' };
  } finally {
    try { await browser.close(); } catch (e) {}
  }
}

module.exports = { renderRecapVideo: renderRecapVideo, findChrome: findChrome };
