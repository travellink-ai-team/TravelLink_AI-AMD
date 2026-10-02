#!/usr/bin/env node
/**
 * Stop hook — 若 app/ 有未 commit 的變更，提醒跑 User Flow Test
 *
 * 依 AGENTS.md 規定：每次修改後應從流程起點測試，而不只看編輯的那行。
 * 此 hook 在 Claude 結束回應時執行，透過 git status 偵測未 commit 的 app/ 變更。
 */
'use strict';

const { spawnSync } = require('child_process');

let raw = '';
process.stdin.setEncoding('utf8');
process.stdin.on('data', c => { raw += c; });
process.stdin.on('end', () => {
  const res = spawnSync('git', ['status', '--porcelain'], { encoding: 'utf8' });
  if (res.status !== 0 || !res.stdout) process.exit(0);

  const appFiles = res.stdout
    .split('\n')
    .filter(l => l.trim() && l.includes('app/'));

  if (appFiles.length === 0) process.exit(0);

  const list = appFiles.map(l => `  • ${l.trim()}`).join('\n');
  process.stdout.write(
    `\n⚠ app/ 有未 commit 的變更，請依 AGENTS.md 跑 User Flow Test：\n${list}\n`
  );
  process.exit(0);
});
