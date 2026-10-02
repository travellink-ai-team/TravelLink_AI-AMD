#!/usr/bin/env node
/**
 * PreToolUse hook — 防止手動編輯自動生成檔案
 *
 * poi-data.js / restaurant-data.js 由 crawler 產生，不應手動修改。
 * 攔截 Write/Edit 並回傳 decision: block。
 */
'use strict';

const path = require('path');

const GENERATED = new Set(['poi-data.js', 'restaurant-data.js']);

let raw = '';
process.stdin.setEncoding('utf8');
process.stdin.on('data', c => { raw += c; });
process.stdin.on('end', () => {
  let data = {};
  try { data = JSON.parse(raw || '{}'); } catch (_) { process.exit(0); }

  const file = (data.tool_input || {}).file_path || '';
  if (!file) process.exit(0);

  const base = path.basename(file);
  if (!GENERATED.has(base)) process.exit(0);

  const cmd = base === 'poi-data.js'
    ? 'npm run export:local（在 crawler/ 目錄下）'
    : 'npm run crawl:food（在 crawler/ 目錄下）';

  process.stdout.write(JSON.stringify({
    decision: 'block',
    reason:
      `${base} 是 crawler 自動生成的檔案，禁止手動編輯。\n` +
      `如需更新，請執行：${cmd}`,
  }));
  process.exit(0);
});
