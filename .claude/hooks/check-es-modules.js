#!/usr/bin/env node
/**
 * PostToolUse hook — 防止 app/*.js 出現 ES module 語法
 *
 * app/ 底下的 .js 以 <script src> 載入（非 type="module"），
 * 在 file:// 下若出現 import/export 會觸發 CORS/module 錯誤。
 *
 * 略過：crawler/（Node CJS 環境）、node_modules、*.min.js。
 */
'use strict';

const fs = require('fs');
const path = require('path');

const EXPORT_RE = /^export\s+(default|const|let|var|function|class|\{)/;
const IMPORT_RE = /^import\s+.+\s+from\s+['"]/;

let raw = '';
process.stdin.setEncoding('utf8');
process.stdin.on('data', c => { raw += c; });
process.stdin.on('end', () => {
  let data = {};
  try { data = JSON.parse(raw || '{}'); } catch (_) { process.exit(0); }

  const file = (data.tool_input || {}).file_path || '';
  if (!file) process.exit(0);

  // 只檢查 app/ 底下的 .js
  if (!/[/\\]app[/\\]/.test(file)) process.exit(0);
  if (!/\.(c|m)?js$/i.test(file)) process.exit(0);
  if (/\.min\.js$/i.test(file)) process.exit(0);
  if (/[/\\](node_modules|dist|build)[/\\]/i.test(file)) process.exit(0);

  let content = '';
  try { content = fs.readFileSync(file, 'utf8'); } catch (_) { process.exit(0); }

  const lines = content.split('\n');
  for (let i = 0; i < lines.length; i++) {
    const trimmed = lines[i].trimStart();
    if (EXPORT_RE.test(trimmed) || IMPORT_RE.test(trimmed)) {
      process.stdout.write(JSON.stringify({
        decision: 'block',
        reason:
          `${path.basename(file)} 第 ${i + 1} 行含有 ES module 語法：\n` +
          `  ${lines[i].trim()}\n\n` +
          `app/ 的 .js 以 <script src> 載入（無 type="module"），\n` +
          `file:// 下不支援 ES module，請改用一般函式宣告或 IIFE。`,
      }));
      process.exit(0);
    }
  }
  process.exit(0);
});
