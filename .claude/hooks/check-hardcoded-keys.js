#!/usr/bin/env node
/**
 * PostToolUse hook — 禁止在 app/ 原始碼 hardcode API 金鑰
 *
 * 規則：金鑰只能放在 weather.env.js，透過 window.TRAVEL_APP_CONFIG 存取。
 * 偵測 Google/Firebase API key 格式（AIzaSy…）出現在其他 app/ 檔案時 block。
 *
 * 略過：weather.env.js（合法放金鑰之處）、serviceAccount.json、app/ 以外的檔案。
 */
'use strict';

const fs = require('fs');
const path = require('path');

const KEY_RE = /AIzaSy[A-Za-z0-9_-]{33}/;

let raw = '';
process.stdin.setEncoding('utf8');
process.stdin.on('data', c => { raw += c; });
process.stdin.on('end', () => {
  let data = {};
  try { data = JSON.parse(raw || '{}'); } catch (_) { process.exit(0); }

  const file = (data.tool_input || {}).file_path || '';
  if (!file) process.exit(0);

  const base = path.basename(file);

  // 合法放金鑰的位置
  if (base === 'weather.env.js') process.exit(0);
  if (base === 'serviceAccount.json') process.exit(0);

  // 只掃 app/ 底下的 HTML / JS / CSS
  if (!/[/\\]app[/\\]/.test(file)) process.exit(0);
  if (!/\.(html?|[cm]?js|css)$/i.test(file)) process.exit(0);

  let content = '';
  try { content = fs.readFileSync(file, 'utf8'); } catch (_) { process.exit(0); }

  const match = content.match(KEY_RE);
  if (!match) process.exit(0);

  process.stdout.write(JSON.stringify({
    decision: 'block',
    reason:
      `偵測到 hardcode API 金鑰（${match[0].slice(0, 12)}…）於：\n${file}\n\n` +
      `金鑰請統一放在 weather.env.js，並透過 window.TRAVEL_APP_CONFIG 讀取，\n` +
      `不要寫死在原始碼中。`,
  }));
  process.exit(0);
});
