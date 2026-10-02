// 滾輪式（drum/wheel）日期/時間選擇器（純 vanilla，無套件，drop-in 共用元件）。
// 取代全站原生 <input type="date|time">，做出 iOS/Cupertino 風格的滾輪彈窗。
// 由 ai-travel-explore-final.html / ai-travel-planner-v8.html 以 <script src="wheel-picker.js"> 載入 → window.WAIPicker。
// API：
//   WAIPicker.openDate({ value:'YYYY-MM-DD'|'', min:'YYYY-MM-DD'|null, title, onSet(v), onClear? })
//   WAIPicker.openTime({ value:'HH:MM'|'', minuteStep:1, title, onSet(v), onClear? })
//   觸發欄位樣式 class：.wai-dt-field（樣式由本檔注入）。
(function () {
  'use strict';

  var ITEM_H = 40;     // 每列高度（px）
  var VISIBLE = 5;     // 顯示列數（中間 1 列為選取，上下各 2 列）
  var PAD = (VISIBLE - 1) / 2; // 頭尾墊片列數
  var YEAR_HORIZON = 6; // 年份上限＝max(今年, 起始年)+此值（相對，自動跟今年）
  var WHEEL_LOCK_MS = 90; // 滑鼠滾輪節流：每個刻度至少間隔此毫秒，確保一次只走一格

  // ── 一次性注入樣式 ──
  function ensureStyle() {
    if (document.getElementById('wai-picker-style')) return;
    var css = ''
      + '.wai-dt-field{display:flex;align-items:center;justify-content:space-between;gap:8px;width:100%;'
      + 'padding:10px 12px;border:1px solid #d8e2ef;border-radius:12px;font-size:15px;color:#1f3a52;'
      + 'background:#f7fbff;cursor:pointer;box-sizing:border-box;-webkit-tap-highlight-color:transparent;}'
      + '.wai-dt-field:hover{border-color:#7db8ee;}'
      + '.wai-dt-field .wai-dt-ph{color:#9bb0c4;}'
      + '.wai-dt-field .wai-dt-ic{font-size:15px;opacity:.7;}'
      + '.wai-pk-overlay{position:fixed;inset:0;z-index:99999;display:flex;align-items:center;justify-content:center;'
      + 'background:rgba(20,30,45,.45);padding:20px;box-sizing:border-box;animation:waiPkFade .18s ease;}'
      + '@keyframes waiPkFade{from{opacity:0}to{opacity:1}}'
      + '.wai-pk-card{width:100%;max-width:360px;background:#fff;border-radius:22px;overflow:hidden;'
      + 'box-shadow:0 24px 60px rgba(20,30,45,.28);font-family:inherit;animation:waiPkPop .2s cubic-bezier(.2,.9,.3,1);}'
      + '@keyframes waiPkPop{from{transform:translateY(12px) scale(.98);opacity:.6}to{transform:none;opacity:1}}'
      + '.wai-pk-title{font-size:19px;font-weight:700;color:#1f3a52;padding:22px 24px 10px;}'
      + '.wai-pk-wheels{position:relative;display:flex;justify-content:center;gap:6px;padding:8px 18px 14px;}'
      + '.wai-pk-wheels::before,.wai-pk-wheels::after{content:"";position:absolute;left:18px;right:18px;height:1px;'
      + 'background:#1f3a52;opacity:.85;pointer-events:none;z-index:2;}'
      + '.wai-pk-wheels::before{top:calc(8px + ' + (PAD * ITEM_H) + 'px);}'
      + '.wai-pk-wheels::after{top:calc(8px + ' + ((PAD + 1) * ITEM_H) + 'px);}'
      + '.wai-pk-colwrap{display:flex;align-items:center;gap:4px;}'
      + '.wai-wheel-col{height:' + (VISIBLE * ITEM_H) + 'px;overflow-y:scroll;scroll-snap-type:y mandatory;'
      + 'scrollbar-width:none;-ms-overflow-style:none;text-align:center;}'
      + '.wai-wheel-col::-webkit-scrollbar{display:none;width:0;height:0;}'
      + '.wai-wheel-item{height:' + ITEM_H + 'px;line-height:' + ITEM_H + 'px;scroll-snap-align:center;'
      + 'font-size:20px;color:#c2cad6;font-variant-numeric:tabular-nums;transition:color .12s,font-weight .12s;cursor:pointer;}'
      + '.wai-wheel-item.is-on{color:#1f3a52;font-weight:700;}'
      + '.wai-wheel-pad{height:' + (PAD * ITEM_H) + 'px;}'
      + '.wai-wheel-sep{font-size:20px;color:#1f3a52;align-self:center;padding:0 2px;}'
      + '.wai-wheel-unit{font-size:12px;color:#9bb0c4;width:0;overflow:visible;}'
      + '.wai-pk-actions{display:flex;align-items:center;justify-content:space-between;padding:8px 18px 18px;}'
      + '.wai-pk-btn{border:none;background:none;font-size:15px;font-weight:600;cursor:pointer;padding:10px 14px;'
      + 'border-radius:10px;font-family:inherit;}'
      + '.wai-pk-btn.clear{color:#a3506b;margin-right:auto;}'
      + '.wai-pk-btn.cancel{color:#5f6876;}'
      + '.wai-pk-btn.set{color:#2563a8;font-weight:800;}'
      + '.wai-pk-btn:hover{background:#f1f5fa;}';
    var el = document.createElement('style');
    el.id = 'wai-picker-style';
    el.textContent = css;
    document.head.appendChild(el);
  }

  function pad2(n) { return (n < 10 ? '0' : '') + n; }
  function daysInMonth(y, m) { return new Date(y, m, 0).getDate(); } // m: 1-12
  function clampIdx(i, len) { return Math.max(0, Math.min(len - 1, i)); }

  // ── 建立一個滾輪欄位；items: [{value,label}]；回傳 { el, getIndex, setIndex, rebuild } ──
  function makeColumn(items, selectedIdx, onSettle, label) {
    var col = document.createElement('div');
    col.className = 'wai-wheel-col';
    // 滾輪欄位原本是純 div，鍵盤按不到、讀屏也唸不出這是可選的清單。
    // 做成 listbox：欄位本身可聚焦，選項帶 role=option / aria-selected。
    col.setAttribute('role', 'listbox');
    col.setAttribute('tabindex', '0');
    if (label) col.setAttribute('aria-label', label);
    var current = selectedIdx;
    var initializing = false; // 初始化/重建時的程式化捲動，避免觸發 settle 與被 scroll-snap 蓋掉

    function render() {
      var html = '<div class="wai-wheel-pad"></div>';
      for (var i = 0; i < items.length; i++) {
        html += '<div class="wai-wheel-item' + (i === current ? ' is-on' : '') + '" data-i="' + i + '"'
          + ' role="option" aria-selected="' + (i === current ? 'true' : 'false') + '">' + items[i].label + '</div>';
      }
      html += '<div class="wai-wheel-pad"></div>';
      col.innerHTML = html;
      syncActiveDescendant();
    }
    render();

    function syncActiveDescendant() {
      var nodes = col.querySelectorAll('.wai-wheel-item');
      if (!nodes[current]) return;
      if (!nodes[current].id) {
        for (var i = 0; i < nodes.length; i++) nodes[i].id = 'waipk-' + (label || 'col') + '-' + i + '-' + Math.random().toString(36).slice(2, 6);
      }
      col.setAttribute('aria-activedescendant', nodes[current].id);
    }

    function highlight(idx) {
      var nodes = col.querySelectorAll('.wai-wheel-item');
      for (var i = 0; i < nodes.length; i++) {
        nodes[i].classList.toggle('is-on', i === idx);
        nodes[i].setAttribute('aria-selected', i === idx ? 'true' : 'false');
      }
      syncActiveDescendant();
    }

    // 鍵盤操作：上下鍵移動一格、Home/End 跳到頭尾、PageUp/PageDown 一次五格。
    col.addEventListener('keydown', function (e) {
      var delta = 0;
      if (e.key === 'ArrowDown') delta = 1;
      else if (e.key === 'ArrowUp') delta = -1;
      else if (e.key === 'PageDown') delta = 5;
      else if (e.key === 'PageUp') delta = -5;
      else if (e.key === 'Home') delta = -items.length;
      else if (e.key === 'End') delta = items.length;
      else return;
      e.preventDefault();
      var target = clampIdx(current + delta, items.length);
      if (target === current) return;
      current = target;
      highlight(current);
      scrollToIndex(current, true);
      if (onSettle) onSettle(current);
    });
    function scrollToIndex(idx, smooth) {
      col.scrollTo({ top: idx * ITEM_H, behavior: smooth ? 'smooth' : 'auto' });
    }
    // 直接設 scrollTop（暫時關閉 scroll-snap，避免首次版面計算時被 snap 回頂端）
    function setScrollDirect(idx) {
      initializing = true;
      var prev = col.style.scrollSnapType;
      col.style.scrollSnapType = 'none';
      col.scrollTop = idx * ITEM_H;
      requestAnimationFrame(function () {
        col.style.scrollSnapType = prev || '';
        setTimeout(function () { initializing = false; }, 0);
      });
    }

    var settleTimer = null;
    col.addEventListener('scroll', function () {
      if (initializing) return;
      var idx = clampIdx(Math.round(col.scrollTop / ITEM_H), items.length);
      if (idx !== current) { current = idx; highlight(idx); }
      if (settleTimer) clearTimeout(settleTimer);
      settleTimer = setTimeout(function () {
        scrollToIndex(current, true);
        if (onSettle) onSettle(current);
      }, 90);
    });
    // 點某列 → 捲到置中
    col.addEventListener('click', function (e) {
      var t = e.target.closest('.wai-wheel-item');
      if (!t) return;
      var idx = parseInt(t.getAttribute('data-i'), 10);
      if (Number.isFinite(idx)) { current = idx; highlight(idx); scrollToIndex(idx, true); if (onSettle) onSettle(idx); }
    });
    // 滑鼠滾輪：一個刻度只走一格（取代原生 scroll-snap 一次約跳三格）；onSettle 交由捲動結束的 settle 觸發
    var wheelLocked = false;
    col.addEventListener('wheel', function (e) {
      e.preventDefault();
      if (wheelLocked) return;
      var dir = e.deltaY > 0 ? 1 : (e.deltaY < 0 ? -1 : 0);
      if (!dir) return;
      var target = clampIdx(current + dir, items.length);
      if (target === current) return;
      current = target; highlight(current); scrollToIndex(current, true);
      wheelLocked = true;
      setTimeout(function () { wheelLocked = false; }, WHEEL_LOCK_MS);
    }, { passive: false });

    return {
      el: col,
      getIndex: function () { return current; },
      getValue: function () { return items[current] ? items[current].value : null; },
      setIndex: function (idx) { current = clampIdx(idx, items.length); highlight(current); setScrollDirect(current); },
      // 重建選項（月/日欄依上層變動時用），保持盡量相同的值
      rebuild: function (newItems) {
        var keepVal = items[current] ? items[current].value : null;
        items = newItems;
        var ni = 0;
        for (var i = 0; i < items.length; i++) { if (items[i].value === keepVal) { ni = i; break; } }
        current = clampIdx(ni, items.length);
        render();
        setScrollDirect(current);
      },
      initPosition: function () { setScrollDirect(current); }
    };
  }

  // ── 共用彈窗外殼 ──
  function buildShell(title, columnsWrap, opts, readValue) {
    ensureStyle();
    // 移除殘留遮罩，避免疊加
    var old = document.querySelector('.wai-pk-overlay');
    if (old) old.parentNode.removeChild(old);

    var returnFocus = document.activeElement;

    var overlay = document.createElement('div');
    overlay.className = 'wai-pk-overlay';
    var card = document.createElement('div');
    card.className = 'wai-pk-card';
    // 這是一個真正的強制回應對話框，之前完全沒有語意：讀屏不會宣告，焦點也不會被留住。
    card.setAttribute('role', 'dialog');
    card.setAttribute('aria-modal', 'true');

    var titleEl = document.createElement('div');
    titleEl.className = 'wai-pk-title';
    titleEl.textContent = title;
    titleEl.id = 'wai-pk-title-' + Math.random().toString(36).slice(2, 8);
    card.setAttribute('aria-labelledby', titleEl.id);

    var actions = document.createElement('div');
    actions.className = 'wai-pk-actions';
    function mkBtn(cls, text) { var b = document.createElement('button'); b.type = 'button'; b.className = 'wai-pk-btn ' + cls; b.textContent = text; return b; }
    function close() {
      if (overlay.parentNode) overlay.parentNode.removeChild(overlay);
      document.removeEventListener('keydown', onKey);
      // 焦點送回原本的觸發欄位，否則關閉後焦點掉到 body，鍵盤使用者得重新 Tab 一輪
      if (returnFocus && document.contains(returnFocus) && typeof returnFocus.focus === 'function') {
        returnFocus.focus();
      }
    }
    function focusables() {
      return Array.prototype.filter.call(
        card.querySelectorAll('button:not([disabled]), [tabindex]:not([tabindex="-1"])'),
        function (el) { return el.offsetParent !== null; }
      );
    }
    function onKey(e) {
      if (e.key === 'Escape') { e.preventDefault(); close(); return; }
      if (e.key !== 'Tab') return;
      var list = focusables();
      if (!list.length) return;
      var first = list[0], last = list[list.length - 1];
      if (!card.contains(document.activeElement)) { e.preventDefault(); (e.shiftKey ? last : first).focus(); }
      else if (e.shiftKey && document.activeElement === first) { e.preventDefault(); last.focus(); }
      else if (!e.shiftKey && document.activeElement === last) { e.preventDefault(); first.focus(); }
    }

    if (typeof opts.onClear === 'function') {
      var clearBtn = mkBtn('clear', '清除');
      clearBtn.addEventListener('click', function () { close(); opts.onClear(); });
      actions.appendChild(clearBtn);
    }
    var cancelBtn = mkBtn('cancel', '取消');
    cancelBtn.addEventListener('click', close);
    var setBtn = mkBtn('set', '設定');
    setBtn.addEventListener('click', function () { var v = readValue(); close(); if (typeof opts.onSet === 'function') opts.onSet(v); });
    actions.appendChild(cancelBtn);
    actions.appendChild(setBtn);

    card.appendChild(titleEl);
    card.appendChild(columnsWrap);
    card.appendChild(actions);
    overlay.appendChild(card);
    overlay.addEventListener('mousedown', function (e) { if (e.target === overlay) close(); });
    document.addEventListener('keydown', onKey);
    document.body.appendChild(overlay);
    // 開啟後把焦點帶進第一個滾輪欄位，讓上下鍵可以直接用
    var firstCol = card.querySelector('.wai-wheel-col');
    if (firstCol) firstCol.focus();
    return { overlay: overlay, close: close };
  }

  function openDate(opts) {
    opts = opts || {};
    var today = new Date();
    var minDate = null, minY = -Infinity, minM = 1, minD = 1;
    if (opts.min) {
      var mp = String(opts.min).split('-'); minDate = new Date(+mp[0], +mp[1] - 1, +mp[2]);
      minY = minDate.getFullYear(); minM = minDate.getMonth() + 1; minD = minDate.getDate();
    }

    var init = (opts.value && /^\d{4}-\d{2}-\d{2}$/.test(opts.value)) ? opts.value.split('-').map(Number)
      : [today.getFullYear(), today.getMonth() + 1, today.getDate()];
    var selY = init[0], selM = init[1], selD = init[2];

    var baseYear = minDate ? minY : today.getFullYear() - 1;
    var yearsEnd = Math.max(today.getFullYear(), baseYear) + YEAR_HORIZON;
    var years = []; for (var y = baseYear; y <= yearsEnd; y++) years.push({ value: y, label: String(y) });
    if (selY < baseYear) selY = baseYear;

    // 層級式 min：選到 min 當年 → 月份從 minM 起；選到 min 當年當月 → 日從 minD 起
    function monthItems(yy) {
      var start = (minDate && yy === minY) ? minM : 1, arr = [];
      for (var m = start; m <= 12; m++) arr.push({ value: m, label: String(m) });
      return arr;
    }
    function dayItems(yy, mm) {
      var start = (minDate && yy === minY && mm === minM) ? minD : 1, dim = daysInMonth(yy, mm), arr = [];
      for (var d = start; d <= dim; d++) arr.push({ value: d, label: String(d) });
      return arr;
    }
    if (minDate && selY === minY) { if (selM < minM) selM = minM; if (selY === minY && selM === minM && selD < minD) selD = minD; }

    var idxY = Math.max(0, years.findIndex(function (o) { return o.value === selY; }));
    var colY, colM, colD;
    function idxOfValue(arr, val) { for (var i = 0; i < arr.length; i++) if (arr[i].value === val) return i; return 0; }
    function onYearChange() {
      var yy = colY.getValue();
      colM.rebuild(monthItems(yy));
      colD.rebuild(dayItems(yy, colM.getValue()));
    }
    function onMonthChange() {
      colD.rebuild(dayItems(colY.getValue(), colM.getValue()));
    }
    var monthsInit = monthItems(selY);
    var daysInit = dayItems(selY, selM);
    colY = makeColumn(years, idxY, onYearChange, '年');
    colM = makeColumn(monthsInit, idxOfValue(monthsInit, selM), onMonthChange, '月');
    colD = makeColumn(daysInit, idxOfValue(daysInit, selD), null, '日');

    var wrap = document.createElement('div');
    wrap.className = 'wai-pk-wheels';
    [colY, colM, colD].forEach(function (c) { wrap.appendChild(c.el); });

    function readValue() {
      var yy = colY.getValue(), mm = colM.getValue(), dd = colD.getValue();
      if (minDate) { var pick = new Date(yy, mm - 1, dd); if (pick < minDate) { yy = minY; mm = minM; dd = minD; } } // 防呆
      return yy + '-' + pad2(mm) + '-' + pad2(dd);
    }
    buildShell(opts.title || '設定日期', wrap, opts, readValue);
    void wrap.offsetHeight; // 強制 reflow，元素已進 DOM 後再同步定位
    colY.initPosition(); colM.initPosition(); colD.initPosition();
  }

  function openTime(opts) {
    opts = opts || {};
    var step = opts.minuteStep && opts.minuteStep > 0 ? opts.minuteStep : 1;
    var minH = -1, minMin = 0;
    if (opts.min && /^\d{1,2}:\d{2}$/.test(opts.min)) { var mpt = opts.min.split(':').map(Number); minH = clampIdx(mpt[0], 24); minMin = mpt[1]; }

    var init = (opts.value && /^\d{1,2}:\d{2}$/.test(opts.value)) ? opts.value.split(':').map(Number) : [9, 0];
    var selH = clampIdx(init[0], 24), selMin = init[1];
    if (minH >= 0) { if (selH < minH) { selH = minH; selMin = minMin; } else if (selH === minH && selMin < minMin) selMin = minMin; }

    // 層級式 min：選到 min 當小時 → 分鐘從 ≥minMin 的最小 step 倍數起
    function hourItems() {
      var start = minH >= 0 ? minH : 0, arr = [];
      for (var h = start; h < 24; h++) arr.push({ value: h, label: pad2(h) });
      return arr;
    }
    function minItems(hh) {
      var floor = (minH >= 0 && hh === minH) ? minMin : 0, arr = [];
      for (var mm = 0; mm < 60; mm += step) { if (mm >= floor) arr.push({ value: mm, label: pad2(mm) }); }
      if (!arr.length) arr.push({ value: floor, label: pad2(floor) });
      return arr;
    }
    function idxOfValue(arr, val) { var best = 0; for (var i = 0; i < arr.length; i++) { if (arr[i].value <= val) best = i; } return best; }

    var hoursInit = hourItems();
    var colH, colMin;
    function onHourChange() { colMin.rebuild(minItems(colH.getValue())); }
    colH = makeColumn(hoursInit, idxOfValue(hoursInit, selH), onHourChange, '時');
    var minsInit = minItems(selH);
    colMin = makeColumn(minsInit, idxOfValue(minsInit, selMin), null, '分');

    var wrap = document.createElement('div');
    wrap.className = 'wai-pk-wheels';
    var cw = document.createElement('div'); cw.className = 'wai-pk-colwrap';
    var sep = document.createElement('div'); sep.className = 'wai-wheel-sep'; sep.textContent = ':';
    cw.appendChild(colH.el); cw.appendChild(sep); cw.appendChild(colMin.el);
    wrap.appendChild(cw);

    function readValue() { return pad2(colH.getValue()) + ':' + pad2(colMin.getValue()); }
    buildShell(opts.title || '設定時間', wrap, opts, readValue);
    void wrap.offsetHeight; // 強制 reflow，元素已進 DOM 後再同步定位
    colH.initPosition(); colMin.initPosition();
  }

  window.WAIPicker = { openDate: openDate, openTime: openTime };

  /* ── 觸發欄位的鍵盤可用性 ───────────────────────────────────────────
     .wai-dt-field 在各頁都是 `<div onclick="pickXxx()">`，沒有 role 也沒有 tabindex，
     鍵盤完全按不到。精靈的「出發日期」是必填，於是整條建立行程的流程對鍵盤使用者是斷的。

     這些欄位由各頁的樣板字串產生、且會一直重畫，所以不在樣板裡逐一補屬性（容易漏），
     改在這裡統一裝飾：欄位一出現就補上語意，再用委派處理 Enter／空白鍵。 */
  function decorateFields(root) {
    var scope = (root && root.querySelectorAll) ? root : document;
    var list = scope.querySelectorAll ? scope.querySelectorAll('.wai-dt-field') : [];
    for (var i = 0; i < list.length; i++) {
      var el = list[i];
      if (el.getAttribute('tabindex') === null) el.setAttribute('tabindex', '0');
      if (!el.getAttribute('role')) el.setAttribute('role', 'button');
    }
  }

  function startFieldWatcher() {
    decorateFields(document);
    if (typeof MutationObserver !== 'function' || !document.body) return;
    new MutationObserver(function (records) {
      for (var i = 0; i < records.length; i++) {
        var added = records[i].addedNodes;
        for (var j = 0; j < added.length; j++) {
          var node = added[j];
          if (!node || node.nodeType !== 1) continue;
          if (node.classList && node.classList.contains('wai-dt-field')) decorateFields(node.parentNode || document);
          else decorateFields(node);
        }
      }
    }).observe(document.body, { childList: true, subtree: true });
  }

  document.addEventListener('keydown', function (e) {
    if (e.key !== 'Enter' && e.key !== ' ') return;
    var field = e.target && e.target.closest ? e.target.closest('.wai-dt-field') : null;
    if (!field) return;
    e.preventDefault();
    field.click();
  });

  // 載入即注入欄位樣式，讓 .wai-dt-field 觸發欄位在開啟彈窗前就有正確外觀
  if (document.head) { ensureStyle(); }
  else { document.addEventListener('DOMContentLoaded', ensureStyle); }
  if (document.body) startFieldWatcher();
  else document.addEventListener('DOMContentLoaded', startFieldWatcher);
})();
