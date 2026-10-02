/* ══════════════════════════════════════════════════
   TravelLinkAI 共用圖示（UIUX 建議 #4：統一圖示系統）
   ──────────────────────────────────────────────────
   為什麼是這個做法：
   - 本專案無建置步驟，不能用 npm 的 lucide 套件，也不想為了圖示引 CDN
     （多一個外部相依，且 file:// 下會失效）。
   - 兩頁各貼一份 SVG sprite 會立刻不同步，所以抽成這支共用檔，
     以 <script src> 載入後注入一份隱藏 sprite，兩頁用 <use> 引用。
   圖示取自 Lucide（ISC 授權）的路徑資料，統一 24×24 網格、stroke-width 2、
   圓端點——這是「同一層級只用一種圖示風格」的關鍵。

   用法：
     HTML： <svg class="wai-icon" aria-hidden="true"><use href="#wai-bell"></use></svg>
     JS：   waiIcon('bell')  → 回傳上面那段字串
   icon-only 按鈕務必自行補 aria-label；本檔一律把 svg 標成 aria-hidden，
   因為圖示是裝飾，語意應該由按鈕的 aria-label 提供。
   ══════════════════════════════════════════════════ */
(function () {
  'use strict';

  // key → 路徑內容（只放 <path>/<circle> 等內容，外層 <symbol> 由下面統一組裝）
  var ICONS = {
    // 通知
    bell: '<path d="M10.268 21a2 2 0 0 0 3.464 0"/><path d="M3.262 15.326A1 1 0 0 0 4 17h16a1 1 0 0 0 .74-1.673C19.41 13.956 18 12.499 18 8A6 6 0 0 0 6 8c0 4.499-1.411 5.956-2.738 7.326"/>',
    // 返回
    'arrow-left': '<path d="m12 19-7-7 7-7"/><path d="M19 12H5"/>',
    // 旅伴／成員
    users: '<path d="M16 21v-2a4 4 0 0 0-4-4H6a4 4 0 0 0-4 4v2"/><circle cx="9" cy="7" r="4"/><path d="M22 21v-2a4 4 0 0 0-3-3.87"/><path d="M16 3.13a4 4 0 0 1 0 7.75"/>',
    // 邀請加入
    'user-plus': '<path d="M16 21v-2a4 4 0 0 0-4-4H6a4 4 0 0 0-4 4v2"/><circle cx="9" cy="7" r="4"/><path d="M19 8v6"/><path d="M22 11h-6"/>',
    // 地圖
    map: '<path d="M14.106 5.553a2 2 0 0 0 1.788 0l3.659-1.83A1 1 0 0 1 21 4.619v12.764a1 1 0 0 1-.553.894l-4.553 2.277a2 2 0 0 1-1.788 0l-4.212-2.106a2 2 0 0 0-1.788 0l-3.659 1.83A1 1 0 0 1 3 19.381V6.618a1 1 0 0 1 .553-.894l4.553-2.277a2 2 0 0 1 1.788 0z"/><path d="M15 5.764v15"/><path d="M9 3.236v15"/>',
    // 地點
    'map-pin': '<path d="M20 10c0 4.993-5.539 10.193-7.399 11.799a1 1 0 0 1-1.202 0C9.539 20.193 4 14.993 4 10a8 8 0 0 1 16 0"/><circle cx="12" cy="10" r="3"/>',
    // 探索
    compass: '<circle cx="12" cy="12" r="10"/><path d="m16.24 7.76-1.804 5.411a2 2 0 0 1-1.265 1.265L7.76 16.24l1.804-5.411a2 2 0 0 1 1.265-1.265z"/>',
    // 我的行程／清單
    'clipboard-list': '<rect width="8" height="4" x="8" y="2" rx="1" ry="1"/><path d="M16 4h2a2 2 0 0 1 2 2v14a2 2 0 0 1-2 2H6a2 2 0 0 1-2-2V6a2 2 0 0 1 2-2h2"/><path d="M12 11h4"/><path d="M12 16h4"/><path d="M8 11h.01"/><path d="M8 16h.01"/>',
    // 日曆
    calendar: '<path d="M8 2v4"/><path d="M16 2v4"/><rect width="18" height="18" x="3" y="4" rx="2"/><path d="M3 10h18"/>',
    // 汽車
    car: '<path d="M19 17h2c.6 0 1-.4 1-1v-3c0-.9-.7-1.7-1.5-1.9C18.7 10.6 16 10 16 10s-1.3-1.4-2.2-2.3c-.5-.4-1.1-.7-1.8-.7H5c-.6 0-1.1.4-1.4.9l-1.4 2.9A3.7 3.7 0 0 0 2 12v4c0 .6.4 1 1 1h2"/><circle cx="7" cy="17" r="2"/><path d="M9 17h6"/><circle cx="17" cy="17" r="2"/>',
    // 步行
    footprints: '<path d="M4 16v-2.38C4 11.5 2.97 10.5 3 8c.03-2.72 1.49-6 4.5-6C9.37 2 10 3.8 10 5.5c0 3.11-2 5.66-2 8.68V16a2 2 0 1 1-4 0Z"/><path d="M20 20v-2.38c0-2.12 1.03-3.12 1-5.62-.03-2.72-1.49-6-4.5-6C14.63 6 14 7.8 14 9.5c0 3.11 2 5.66 2 8.68V20a2 2 0 1 0 4 0Z"/><path d="M16 17h4"/><path d="M4 13h4"/>',
    // AI／亮點
    // 原始 Lucide sparkles 還有兩個裝飾小十字（M20 3v4 / M22 5h-4、M4 17v2 / M5 18H3）。
    // 這裡刻意移除：本專案的圖示實際只渲染到 17px，那兩個十字各自縮到約 3px，
    // 小到看不出是十字，變成兩條飄在星星旁的短線；又因為 17px 不是整數倍縮放，
    // 它們有時被抗鋸齒糊掉、有時清晰可見，看起來像忽隱忽現的雜訊。
    // 只保留主星，在小尺寸下才乾淨。
    sparkles: '<path d="M9.937 15.5A2 2 0 0 0 8.5 14.063l-6.135-1.582a.5.5 0 0 1 0-.962L8.5 9.936A2 2 0 0 0 9.937 8.5l1.582-6.135a.5.5 0 0 1 .963 0L14.063 8.5A2 2 0 0 0 15.5 9.937l6.135 1.581a.5.5 0 0 1 0 .964L15.5 14.063a2 2 0 0 0-1.437 1.437l-1.582 6.135a.5.5 0 0 1-.963 0z"/>',
    // 更多
    'more-horizontal': '<circle cx="12" cy="12" r="1"/><circle cx="19" cy="12" r="1"/><circle cx="5" cy="12" r="1"/>',
    // 行程編輯／出發
    plane: '<path d="M17.8 19.2 16 11l3.5-3.5C21 6 21.5 4 21 3c-1-.5-3 0-4.5 1.5L13 8 4.8 6.2c-.5-.1-.9.1-1.1.5l-.3.5c-.2.5-.1 1 .3 1.3L9 12l-2 3H4l-1 1 3 2 2 3 1-1v-3l3-2 3.5 5.3c.3.4.8.5 1.3.3l.5-.2c.4-.3.6-.7.5-1.2z"/>',
    // 掃碼加入：刻意不提供 QR 圖示。
    // Lucide 的 qr-code 有 12 條路徑、其中 7 條是零長度的點，
    // 本專案圖示實際只渲染到 17px，縮到那個尺寸只剩一團無法辨識的糊影。
    // 「邀請碼快速加入」改用 user-plus——語意同樣是加入，且小尺寸下清晰。
    // 若日後真的需要 QR 圖示，請確認渲染尺寸至少 32px 再加回來。
  };

  // 一次組出 sprite。fill:none/stroke:currentColor 放在 .wai-icon（CSS），
  // 這裡只放幾何路徑，顏色才能跟著按鈕文字色走。
  function buildSprite() {
    var parts = [];
    for (var key in ICONS) {
      if (!Object.prototype.hasOwnProperty.call(ICONS, key)) continue;
      parts.push('<symbol id="wai-' + key + '" viewBox="0 0 24 24">' + ICONS[key] + '</symbol>');
    }
    return '<svg xmlns="http://www.w3.org/2000/svg" aria-hidden="true" focusable="false"'
      + ' style="position:absolute;width:0;height:0;overflow:hidden">'
      + parts.join('') + '</svg>';
  }

  function injectSprite() {
    if (document.getElementById('wai-icon-sprite')) return;
    var host = document.createElement('div');
    host.id = 'wai-icon-sprite';
    host.setAttribute('aria-hidden', 'true');
    host.innerHTML = buildSprite();
    // 插在 body 最前面：<use> 會即時解析，靜態標記與稍後由 JS 產生的都吃得到
    document.body.insertBefore(host, document.body.firstChild);
  }

  // 產生一顆圖示的標記。extraClass 供需要額外尺寸/位移的場合使用。
  function waiIcon(name, extraClass) {
    if (!Object.prototype.hasOwnProperty.call(ICONS, name)) {
      // 名稱打錯時回空字串而不是壞掉的 <use>，避免畫面出現破圖方框
      if (window.console) console.warn('[icons] 未知的圖示名稱：' + name);
      return '';
    }
    return '<svg class="wai-icon' + (extraClass ? ' ' + extraClass : '') + '" aria-hidden="true">'
      + '<use href="#wai-' + name + '"></use></svg>';
  }

  window.waiIcon = waiIcon;
  window.WAI_ICON_NAMES = Object.keys(ICONS);

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', injectSprite);
  } else {
    injectSprite();
  }
})();
