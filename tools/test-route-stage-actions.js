/* 路段選項：驗證手機／桌機共用選單的文案與有無替代路線兩種狀態。 */
'use strict';

const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const source = fs.readFileSync(path.join(__dirname, '..', 'app', 'ai-travel-planner-v8.js'), 'utf8');
const start = source.indexOf('  function openRouteStageActions(');
const end = source.indexOf('  function openRouteAlternatives(', start);
assert.ok(start >= 0 && end > start, '找不到路段選項實作');
const implementation = source.slice(start, end);

function render(alts, altEligible) {
  const listeners = {};
  let overlay = null;
  class HTMLElement {}
  const trigger = new HTMLElement();
  const stage = {
    index: 1,
    origin: { name: '東南大圳水利公園' },
    destination: { name: '利吉惡地' },
    alts,
    altEligible
  };
  const context = {
    HTMLElement,
    routeStageCache: [stage],
    escapeHtml: (value) => String(value).replaceAll('&', '&amp;').replaceAll('<', '&lt;').replaceAll('>', '&gt;'),
    closeRouteStageActions() {},
    document: {
      createElement() {
        overlay = {
          innerHTML: '',
          addEventListener(name, handler) { listeners[name] = handler; },
          querySelector(selector) {
            return { addEventListener(name, handler) { listeners[`${selector}:${name}`] = handler; }, focus() {} };
          }
        };
        return overlay;
      },
      body: { appendChild() {} }
    }
  };
  vm.createContext(context);
  vm.runInContext(implementation, context);
  context.openRouteStageActions(1, { stopPropagation() {}, currentTarget: trigger });
  return { html: overlay.innerHTML, listeners };
}

const onlyOne = render([{ index: 0 }], true);
assert.match(onlyOne.html, /這段路線可以怎麼走？/);
assert.match(onlyOne.html, /第 2 段/);
assert.match(onlyOne.html, /東南大圳水利公園/);
assert.match(onlyOne.html, /利吉惡地/);
assert.match(onlyOne.html, /在地圖上查看/);
assert.match(onlyOne.html, /目前沒有可比較的其他路線/);
assert.doesNotMatch(onlyOne.html, /data-stage-alt/);

const multiple = render([{ index: 0 }, { index: 1 }, { index: 2 }], true);
assert.match(multiple.html, /比較其他路線/);
assert.match(multiple.html, /另有 2 條走法/);
assert.ok(multiple.listeners['[data-stage-alt]:click'], '可比較時須綁定按鈕動作');
assert.ok(multiple.listeners.keydown, '選單須支援 Escape 關閉');

console.log('路段選項：單一路線／多條路線／鍵盤關閉測試通過');
