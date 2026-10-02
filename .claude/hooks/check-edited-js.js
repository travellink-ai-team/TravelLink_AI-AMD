#!/usr/bin/env node
/**
 * PostToolUse hook — 自動語法檢查（node --check）
 *
 * 每次 Write/Edit 後執行：若被編輯的是本專案的 .js 檔，就用 `node --check`
 * 驗證語法。通過則安靜結束；失敗則回報給 Claude（decision: block）並提示使用者。
 *
 * 會略過：非 JS 檔、node_modules / dist / build、以及 *.min.js（第三方/壓縮檔）。
 *
 * 此 hook 接收 stdin 的 JSON（{ tool_input, tool_response, ... }）。
 */
'use strict';

const { spawnSync } = require('child_process');

let raw = '';
process.stdin.setEncoding('utf8');
process.stdin.on('data', (chunk) => { raw += chunk; });
process.stdin.on('end', () => {
  let data = {};
  try { data = JSON.parse(raw || '{}'); } catch (_) { process.exit(0); }

  const input = data.tool_input || {};
  const resp = data.tool_response || {};
  const file = input.file_path || resp.filePath || resp.file_path || '';
  if (!file) process.exit(0);

  // 只檢查本專案自己的 JS 原始碼
  if (!/\.(c|m)?js$/i.test(file)) process.exit(0);
  if (/[\\/](node_modules|dist|build)[\\/]/i.test(file)) process.exit(0);
  if (/\.min\.js$/i.test(file)) process.exit(0);

  const res = spawnSync(process.execPath, ['--check', file], { encoding: 'utf8' });

  // status 為 null 代表沒跑起來（例如檔案已被刪）；視為通過、不打擾
  if (res.status === 0 || res.status === null) process.exit(0);

  const detail = String(res.stderr || res.stdout || 'unknown syntax error').trim();
  const out = {
    decision: 'block',
    reason:
      `語法檢查失敗（node --check）：\n${file}\n\n${detail}\n\n` +
      `請修正上述語法錯誤後再繼續。`,
    systemMessage: `✗ 語法檢查失敗：${file}`,
  };
  process.stdout.write(JSON.stringify(out));
  process.exit(0);
});
