// 人工確認過的「同一個景點」資料（手動維護，爬蟲不會改寫）。explore 生成與 planner 加站共用。
//
// 合併規則：只有「名稱明確相關」或「列在這份清單」才算同一處。距離很近只提醒，不合併——
// 300 公尺內就合併會把台東天后宮和寶町藝文中心、成功漁港和成功萬善廟併成一站。
// OSM 輪廓（osm-boundary-data.js）只用在 parents 列出的景點，其他輪廓不信任：
// 阿美族民俗中心框住都歷遊客中心、東河橋遊憩區框住資料座標不準的泰源幽谷。
//
// 新增前請先查證兩者的關係（官方網站、地圖、旅遊資料）。
// - parents：大景點。name＝站名（對得到 poi-data），outline＝OSM 輪廓名，aliases＝在裡面的小景點。
// - sameNames：同一個地方的不同名稱。
window.WAI_BIG_ATTRACTIONS = (function () {
  const parents = [
    { name: '臺東森林公園', outline: '臺東森林公園', aliases: ['鷺鷥湖', '琵琶湖', '活水湖'] },
    { name: '鯉魚山', outline: '鯉魚山公園', aliases: ['龍鳳佛堂'] },
    { name: '台東海濱公園', outline: '海濱公園(馬卡巴嗨公園)', aliases: ['國際地標', '馬卡巴嗨', '海洋生態意象廣場', '海洋意象廣場'] },
    { name: '小野柳', outline: '小野柳遊憩區', aliases: ['富岡地質公園'] },
    { name: '臺東鐵道藝術村 (鐵花新聚落)', outline: '台東鐵道藝術村', aliases: ['鐵花新聚落', '舊鐵道'] },
    { name: '台東糖廠文創園區', outline: '台東糖廠', aliases: [] },
    { name: '東河橋遊憩區', outline: '', aliases: ['東河舊橋'] },
    { name: '八仙洞', outline: '八仙洞', aliases: [] },
    { name: '初鹿牧場', outline: '初鹿牧場', aliases: [] },
    { name: '關山親水公園', outline: '關山親水公園', aliases: [] },
    { name: '綠洲山莊', outline: '綠洲山莊(綠島感訓監獄)', aliases: ['八卦樓'] }
  ];
  const sameNames = [
    ['台東車站', '台東火車站'],
    ['多良車站', '多良火車站'],
    ['霧鹿砲台', '霧鹿古砲台'],
    ['火把岩', '玉女岩'],
    ['白色恐怖綠島紀念園區', '綠島人權文化園區', '綠島人權園區', '國家人權博物館-白色恐怖綠島紀念園區'],
    ['臺東北町建築群', '臺東市北町日式建築宿舍群'],
    ['TTstyle原創館-波浪屋', '台東波浪屋-原民文創產業聚落'],
    ['野銀舊部落', '野銀部落地下屋']
  ];
  // 就算在範圍內也是獨立一站；萬富倉庫是糖廠裡的甜點店，鐵花村音樂聚落是藝術村旁的另一個場地
  const SKIP_RE = /火車站|車站|捷運|高鐵|轉運站|客運站|機場|航空站|停車場|公廁|廁所|萬富倉庫|鐵花村/;

  function norm(name) {
    const unified = String(name || '').replace(/臺/g, '台');
    const clean = (s) => s.toLowerCase().replace(/[^\p{L}\p{N}]+/gu, '');
    return clean(unified.replace(/[（(][^）)]*[）)]/g, '')) || clean(unified);
  }
  function ringOf(parent) {
    if (parent.ring !== undefined) return parent.ring;
    const items = (window.WAI_OSM_BOUNDARY_DATA && window.WAI_OSM_BOUNDARY_DATA.items) || [];
    const hit = parent.outline ? items.find((it) => it && it.n === parent.outline && Array.isArray(it.r) && it.r.length >= 3) : null;
    parent.ring = hit ? hit.r : null;
    return parent.ring;
  }
  function pointInRing(ring, lat, lng) {
    let inside = false;
    for (let i = 0, j = ring.length - 1; i < ring.length; j = i++) {
      const a = ring[i], b = ring[j];
      if ((a[0] > lat) !== (b[0] > lat) && lng < ((b[1] - a[1]) * (lat - a[0])) / (b[0] - a[0]) + a[1]) inside = !inside;
    }
    return inside;
  }
  // 這個地點屬於哪個大景點：{ parent, isSelf }（isSelf＝它自己就是大景點）；不屬於回 null。
  // 先比對名稱，座標可能不準。餐廳、住宿由呼叫端先排除。
  function classify(rawName, coords) {
    const name = norm(rawName);
    if (!name || SKIP_RE.test(String(rawName || ''))) return null;
    for (const parent of parents) {
      const own = norm(parent.name);
      if (name === own || name.includes(own) || (parent.outline && name === norm(parent.outline))) return { parent, isSelf: true };
    }
    for (const parent of parents) {
      if (parent.aliases.some((a) => name.includes(norm(a)))) return { parent, isSelf: false };
    }
    if (coords && Number.isFinite(coords.lat) && Number.isFinite(coords.lng)) {
      for (const parent of parents) {
        const ring = ringOf(parent);
        if (ring && pointInRing(ring, coords.lat, coords.lng)) return { parent, isSelf: false };
      }
    }
    return null;
  }
  function isSameName(a, b) {
    const na = norm(a), nb = norm(b);
    if (!na || !nb) return false;
    if (na === nb) return true;
    const inGroup = (group, n) => group.some((x) => { const g = norm(x); return n === g || n.includes(g); });
    return sameNames.some((group) => inGroup(group, na) && inGroup(group, nb));
  }
  // 清單判定是否同一處：同名，或屬於同一個大景點
  function isSamePlace(aName, aCoords, bName, bCoords) {
    if (isSameName(aName, bName)) return true;
    const ca = classify(aName, aCoords), cb = classify(bName, bCoords);
    return !!(ca && cb && ca.parent === cb.parent);
  }
  return { parents, sameNames, normalize: norm, ringOf, classify, isSameName, isSamePlace };
})();
