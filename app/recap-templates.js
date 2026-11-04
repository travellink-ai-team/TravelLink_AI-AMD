/*
 * TravelLink AI recap templates — 回顧短片的「節奏樣板」（純資料）。
 * 把原本寫死在 recap-manifest.js 的時間常數、片長目標與開場方式抽成樣板，
 * manifest 依樣板產時間軸、渲染器依樣板決定開場畫面。
 * 沒有任何相依；瀏覽器 <script> 與 Node require 都可載入。
 *
 * 時間單位一律 ms。targetMs 依真實素材數分四級（計劃 6.1：素材不足就縮短影片，不用假畫面補長度）。
 * segment：站間路線的時間分配——依距離比例，但每段至少 minMs、最多佔全部路線時間的 maxShare，
 *          避免 200 公尺的短程一閃而過、一段長途吃掉整支影片。
 * cover.style：'title' ＝台灣全景俯衝＋標題卡（原本的開場）；
 *              'photoCut' ＝先放一張實景照片（photoMs），硬切到地圖、停半拍（beatMs）再開始畫路線
 *              （參考影片的結構，見 docs/travel-story/RECAP_VIDEO_TEMPLATE_HANDOFF.md）。沒有照片時退回 'title'。
 */
(function (root, factory) {
  var api = factory();

  if (typeof module === 'object' && module.exports) {
    module.exports = api;
  } else {
    root.WAI_RECAP_TEMPLATES = api;
  }
}(typeof globalThis !== 'undefined' ? globalThis : this, function () {
  'use strict';

  // DEFAULT_ID：請求沒帶樣板時（舊版網頁、目前的 App）用的樣板，維持「標準」以免舊請求的影片變樣。
  // RECOMMENDED_ID：前端選單的預設值（IG 分享優化提案 A2）。
  var DEFAULT_ID = 'standard';
  var RECOMMENDED_ID = 'reels';

  // 長度模式（A19）：all ＝放進全部照片、依秒數自動延長（上限 maxMs）；15 ＝固定 15 秒，放不下的照片站略過
  var LENGTH_MODES = ['all', '15'];

  var TEMPLATES = [
    {
      // structure 'anchors'：只在有照片的站之間播路線（A7），中段依照片數切換三種結構（A9），
      // 換日不出獨立的轉場卡、改在隔天第一張照片標「第 N 天」（A10）。秒數規則見提案「秒數規則」。
      id: 'reels',
      label: 'Reels',
      desc: '照片當主角、節奏明快，適合 IG Reels 與限時動態。',
      structure: 'anchors',
      coverMs: 1200,
      revealMs: 1000,
      photoMs: 1400,
      heroMs: 1000,
      heroSingleMs: 2000,
      hopMs: 700,
      summaryMs: 1500,
      statsMs: 3300,
      routeOnlyMs: 6000,       // 0 張照片（路線故事）：整條路線開完的時間
      featuredRouteMs: 5000,   // 1～2 張照片（地圖＋精選照片）：整條路線開完的時間（不含照片）
      focusMs: 600,            // 路線故事：聚焦的站各停多久
      focusMax: 4,
      maxMs: 30000,            // 放進全部照片時的上限（待討論，提案建議約 30 秒）
      fixed15: { totalMs: 15000, heroSingleMs: 2400, heroMs: 1000, heroManyMs: 800, heroManyCount: 3 },
      dwellMs: 0,
      dayTransitionMs: 0,
      targetMs: { '0': 15000, '1-2': 15000, '3-5': 20000, '6+': 30000 },
      minRouteMs: 3000,
      segment: { minMs: 300, maxShare: 0.45, ease: 'smooth' },
      cover: { style: 'title' }
    },
    {
      id: 'standard',
      label: '標準',
      desc: '從台灣全景俯衝到路線，到站停一下看照片。',
      coverMs: 3400,
      statsMs: 3000,
      dwellMs: 200,
      photoMs: 2600,
      dayTransitionMs: 800,
      targetMs: { '0': 14500, '1-2': 18000, '3-5': 23000, '6+': 32000 },
      minRouteMs: 6000,
      segment: { minMs: 900, maxShare: 0.45, ease: 'smooth' },
      cover: { style: 'title' }
    },
    {
      id: 'brisk',
      label: '快節奏',
      desc: '更短、更緊湊，適合 Reels 與限時動態。',
      coverMs: 2200,
      statsMs: 2200,
      dwellMs: 120,
      photoMs: 1800,
      dayTransitionMs: 600,
      targetMs: { '0': 11000, '1-2': 13500, '3-5': 17000, '6+': 24000 },
      minRouteMs: 4500,
      segment: { minMs: 700, maxShare: 0.4, ease: 'smooth' },
      cover: { style: 'title' }
    },
    {
      id: 'leisurely',
      label: '慢遊',
      desc: '節奏放慢，照片停留更久，適合多日長途。',
      coverMs: 4200,
      statsMs: 3600,
      dwellMs: 400,
      photoMs: 3600,
      dayTransitionMs: 800,
      targetMs: { '0': 18000, '1-2': 22000, '3-5': 29000, '6+': 40000 },
      minRouteMs: 8000,
      segment: { minMs: 1200, maxShare: 0.45, ease: 'smooth' },
      cover: { style: 'title' }
    },
    {
      id: 'cinematic',
      label: '電影感開場',
      desc: '先放一張實景照片，硬切到地圖、停半拍再開始畫路線。',
      coverMs: 2600,
      statsMs: 2800,
      dwellMs: 200,
      photoMs: 2400,
      dayTransitionMs: 700,
      targetMs: { '0': 14000, '1-2': 17500, '3-5': 22500, '6+': 31000 },
      minRouteMs: 6000,
      segment: { minMs: 900, maxShare: 0.45, ease: 'linear' },
      cover: { style: 'photoCut', photoMs: 1430, beatMs: 320 }
    }
  ];

  function clone(obj) {
    return JSON.parse(JSON.stringify(obj));
  }

  // 找不到的 id 一律退回標準樣板（前端送來的值不可信）
  function getTemplate(id) {
    var key = String(id || DEFAULT_ID);
    var i;
    for (i = 0; i < TEMPLATES.length; i += 1) {
      if (TEMPLATES[i].id === key) return clone(TEMPLATES[i]);
    }
    return getTemplate(DEFAULT_ID);
  }

  function listTemplates() {
    return TEMPLATES.map(function (t) { return { id: t.id, label: t.label, desc: t.desc }; });
  }

  function hasTemplate(id) {
    return TEMPLATES.some(function (t) { return t.id === String(id || ''); });
  }

  function normalizeLengthMode(value) {
    var v = String(value == null ? '' : value);
    return LENGTH_MODES.indexOf(v) >= 0 ? v : 'all';
  }

  return {
    DEFAULT_ID: DEFAULT_ID,
    RECOMMENDED_ID: RECOMMENDED_ID,
    LENGTH_MODES: LENGTH_MODES.slice(),
    normalizeLengthMode: normalizeLengthMode,
    getTemplate: getTemplate,
    listTemplates: listTemplates,
    hasTemplate: hasTemplate
  };
}));
