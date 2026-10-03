// 離島停航功能的開關（AGENT_SEA_RISK，預設關閉）。離線測試，不呼叫 AI、不打 CWA。
//   node test/sea-flag.test.js
'use strict';
const path = require('path');
require('dotenv').config({ path: path.join(__dirname, '..', '.env') });
process.env.AI_PROVIDER = 'off';
process.env.CWA_API_KEY = '';

const T = require('../agent/tools');
const { runAgent } = require('../agent/loop');

let pass = 0, fail = 0;
const ok = (name, cond, extra) => { if (cond) { pass++; console.log('PASS  ' + name); } else { fail++; console.log('FAIL  ' + name + (extra ? '\n      ' + extra : '')); } };

const tomorrow = new Date(Date.now() + 8 * 3600 * 1000 + 86400 * 1000).toISOString().slice(0, 10);
const dayAfter = new Date(Date.now() + 8 * 3600 * 1000 + 2 * 86400 * 1000).toISOString().slice(0, 10);
// 綠島兩天一夜，第 2 天回程；模擬第 2 天浪高 3.5 公尺（開著時會判停航風險高）
const GREEN = {
  title: '綠島兩天一夜', region: '綠島', startDate: tomorrow, endTime: '21:00', people: 2,
  stops: [
    { id: 'g1', day: 1, time: '08:00', stayMin: 30, name: '富岡漁港' },
    { id: 'g2', day: 1, time: '09:30', stayMin: 20, name: '南寮漁港' },
    { id: 'g3', day: 1, time: '11:00', stayMin: 60, name: '綠島大街' },
    { id: 'g4', day: 2, time: '09:00', stayMin: 35, name: '大白沙' },
    { id: 'g5', day: 2, time: '14:30', stayMin: 60, name: '南寮漁港' },
    { id: 'g6', day: 2, time: '16:30', stayMin: 20, name: '富岡漁港' }
  ]
};
const STORM = { sea: { date: dayAfter, waveMaxM: 3.5 } };
const names = (defs) => defs.map((d) => d.function.name);
const patchTypes = (defs) => defs.find((d) => d.function.name === 'propose_patch').function.parameters.properties.ops.items.properties.type.enum;
const run = (trip, trigger, scenario) => {
  const events = [];
  return runAgent({ trip, trigger, scenario, onEvent: (e) => events.push(e) }).then((r) => ({ r, events }));
};

(async () => {
  // ── 關閉（預設）──
  delete process.env.AGENT_SEA_RISK;
  const offDefs = T.definitions();
  ok('關閉：AI 看不到海象、船班工具', !names(offDefs).includes('get_sea_conditions') && !names(offDefs).includes('get_ferry_status'));
  ok('關閉：propose_patch 沒有 move_ferry 選項', !patchTypes(offDefs).includes('move_ferry'));
  ok('關閉：原始定義沒被改到', patchTypes(T.DEFINITIONS).includes('move_ferry'));
  const ctx = { trip: null };
  ok('關閉：硬叫海象工具會被拒', !!(await T.runTool(ctx, 'get_sea_conditions', {})).error);
  ok('關閉：硬送 move_ferry 會被拒', /船班不在/.test((await T.runTool({ trip: null }, 'propose_patch', { ops: [{ type: 'move_ferry', direction: 'return', day: 1, time: '15:00' }] })).error || ''));

  const off = await run(GREEN, { type: 'weather' }, STORM);
  const offCheck = off.events.find((e) => e.type === 'check');
  ok('關閉：天氣檢查不提海象、不判停航', off.r.type === 'no_change' && !/海象|停航/.test((offCheck && offCheck.label) || '') && !/停航/.test(off.r.reason || ''), JSON.stringify({ r: off.r.type, reason: off.r.reason, label: offCheck && offCheck.label }));

  // ── 打開 ──
  process.env.AGENT_SEA_RISK = 'on';
  const onDefs = T.definitions();
  ok('打開：海象、船班工具都在', names(onDefs).includes('get_sea_conditions') && names(onDefs).includes('get_ferry_status') && patchTypes(onDefs).includes('move_ferry'));
  const on = await run(GREEN, { type: 'weather' }, STORM);
  const onResult = on.events.find((e) => e.type === 'check_result');
  ok('打開：同一份行程會判出停航風險', !!onResult && onResult.issues.some((m) => /停航/.test(m)), JSON.stringify(onResult || on.r).slice(0, 300));

  console.log(`\n${pass} passed, ${fail} failed`);
  process.exit(fail ? 1 : 0);
})().catch((e) => { console.error(e); process.exit(1); });
