/* ══════════════════════════════════════════════════
   TravelLinkAI 共用載入／回饋工具（UIUX 建議 #11）
   ──────────────────────────────────────────────────
   建議書要求：操作超過 300ms 要有清楚回饋——按鈕進 loading 態、
   禁止重複提交、成功與錯誤訊息靠近操作位置。
   本專案原本只有「把按鈕 disabled 變灰」，使用者看不出是在處理還是壞了。

   兩頁共用，以 <script src> 載入（無建置步驟，非 module）。

   用法：
     await waiRunBusy(btn, '處理中…', async () => { ...非同步工作... });
       → 自動接管 disabled／文字／還原，例外會往外拋（呼叫端照樣 catch）
     const restore = waiSetBusy(btn, '儲存中…');  // 需要手動控制時
     restore();
   ══════════════════════════════════════════════════ */
(function () {
  'use strict';

  var SPINNER = '<span class="wai-spinner" aria-hidden="true"></span>';

  /**
   * 讓按鈕進入忙碌狀態，回傳「還原」函式（可重複呼叫，只生效一次）。
   * 刻意保存 innerHTML 而非 textContent：按鈕裡常含 SVG 圖示，
   * 用 textContent 還原會把圖示吃掉。
   */
  function waiSetBusy(el, busyText) {
    if (!el) return function () {};
    if (el.dataset.waiBusy === '1') return function () {}; // 已在忙碌中，不重複接管
    var prevHtml = el.innerHTML;
    var prevDisabled = el.disabled;
    var prevWidth = el.offsetWidth;

    el.dataset.waiBusy = '1';
    // 鎖住原本寬度，避免文字換成「處理中…」時按鈕忽大忽小造成版面位移
    // （建議書 P0-1：點擊狀態不會造成版面位移）
    if (prevWidth > 0) el.style.minWidth = prevWidth + 'px';
    el.disabled = true;
    el.setAttribute('aria-busy', 'true');
    el.classList.add('is-busy');
    el.innerHTML = SPINNER + (busyText ? '<span class="wai-busy-text">' + busyText + '</span>' : '');

    var done = false;
    return function restore() {
      if (done) return;
      done = true;
      delete el.dataset.waiBusy;
      el.innerHTML = prevHtml;
      el.disabled = prevDisabled;
      el.removeAttribute('aria-busy');
      el.classList.remove('is-busy');
      el.style.minWidth = '';
    };
  }

  /**
   * 包一段非同步工作：期間按鈕忙碌，結束（成功或失敗）一定還原。
   * 例外原樣往外拋，呼叫端既有的 try/catch 與錯誤訊息邏輯不受影響。
   */
  /**
   * 給「成功後預期由外部重繪把節點換掉」的情境用的安全網。
   * 正常情況重繪會把按鈕連同忙碌態一起移除，restore 不會被觸發；
   * 但若重繪遲遲沒來（推播延遲、listener 斷線、離線），
   * 逾時後把按鈕還原成可操作，避免永久卡在「處理中…」。
   * 回傳 cancel 函式，呼叫端若確定不需要安全網可主動取消。
   */
  function waiBusyUntilRerender(restore, el, ms) {
    var timer = setTimeout(function () {
      // 節點已被重繪移除 → 什麼都不用做（還原一個孤兒節點沒有意義）
      if (!el || !el.isConnected) return;
      restore();
    }, ms || 8000);
    return function cancel() { clearTimeout(timer); };
  }

  async function waiRunBusy(el, busyText, fn) {
    var restore = waiSetBusy(el, busyText);
    try {
      return await fn();
    } finally {
      restore();
    }
  }

  /** 產生 n 條骨架列，給「還在載入的清單」用，避免空白畫面。 */
  function waiSkeletonRows(n, className) {
    var out = [];
    for (var i = 0; i < (n || 3); i++) {
      out.push('<div class="wai-skeleton ' + (className || '') + '"></div>');
    }
    return '<div class="wai-skeleton-wrap" aria-hidden="true">' + out.join('') + '</div>';
  }

  window.waiSetBusy = waiSetBusy;
  window.waiRunBusy = waiRunBusy;
  window.waiBusyUntilRerender = waiBusyUntilRerender;
  window.waiSkeletonRows = waiSkeletonRows;
})();
