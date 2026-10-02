/*
 * 官方精選範本：從 app/poi-data.js 的真實景點即時組出行程（window.WAI_TEMPLATES）。
 *
 * 為什麼要有這個模組：
 *   探索首頁原本放 6 筆寫死的社群行程（含羅馬／京都／首爾、假作者、假讚數），
 *   但這個 app 沒有公開行程功能，而且那些目的地本地景點資料是 0，點進去生不出東西。
 *   改成由真實資料即時組範本——不編造社群訊號，也只展示做得到的地方。
 *
 * 組法：主軸 ＋ 沿途
 *   以該鄉鎮的高分景點當主軸，再從台東車站往返的路徑上挑順路景點補進來，
 *   避免一天全擠在同一個角落。景點少的鄉鎮（如金峰只有 8 個）靠沿途撐起來。
 *
 * ★ 沿途候選必須在同一條走廊上。
 *   台東的幹道從台東市呈 V 字分岔（台11 海線 / 台9 縱谷 / 南迴），
 *   只看直線繞路會把縱谷的景點判成「去長濱（海線北端）的順路點」——
 *   直線上確實幾乎不繞路，實際開車卻是完全相反的方向。
 *
 * 同時被 Node 測試載入（module.exports），故不可依賴任何瀏覽器 API。
 */
(function (root, factory) {
  var api = factory();
  if (typeof module === 'object' && module.exports) module.exports = api;
  else root.WAI_TEMPLATES = api;
}(typeof globalThis !== 'undefined' ? globalThis : this, function () {
  'use strict';

  // 地理分組：16 個鄉鎮攤成一列太長，也看不出彼此的關係
  var GROUPS = [
    { label: '海線', keys: ['長濱', '成功', '東河'] },
    { label: '市區', keys: ['台東', '卑南'] },
    { label: '縱谷', keys: ['池上', '關山', '鹿野', '延平', '海端'] },
    { label: '南迴', keys: ['太麻里', '金峰', '大武', '達仁'] },
    { label: '離島', keys: ['綠島', '蘭嶼'] }
  ];
  var ISLANDS = { '綠島': 1, '蘭嶼': 1 };
  // 台東車站：本島行程的出入口
  var GATE = { name: '台東車站', lat: 22.7931, lng: 121.1229, duration: 0, rating: 0 };

  var THEME = {
    '海線': { from: '#2a6b5e', to: '#4f9c86', line: '#ffd98a' },
    '市區': { from: '#b8531f', to: '#e8733a', line: '#ffe9c9' },
    '縱谷': { from: '#4a6b1f', to: '#7a9c3a', line: '#f2ffc9' },
    '南迴': { from: '#8a6d10', to: '#c9a227', line: '#fff6d6' },
    '離島': { from: '#1f5448', to: '#3a8f9c', line: '#ffe27a' }
  };
  var TITLE_SUFFIX = { '海線': '海岸線一日', '市區': '市區慢走', '縱谷': '縱谷一日', '南迴': '山海之間', '離島': '環島一日' };
  var GROUP_EMOJI = { '海線': '🌊', '市區': '🏙', '縱谷': '🏔', '南迴': '🌾', '離島': '🏝' };

  // 交通節點不是景點。poi-data 混了車站／機場，而且同一站有「臺東車站」
  // 「台東車站」「台東火車站」多種寫法，不濾掉會重複被挑進同一份行程。
  var TRANSIT_RE = /車站|火車站|機場|碼頭|轉運站|客運站/;
  // 住宿不該當成「景點」放進一日遊的主軸
  var LODGING_RE = /民宿|飯店|旅店|旅館|酒店|villa|hotel|hostel|inn\b/i;

  function zh(s) { return String(s == null ? '' : s).replace(/臺/g, '台'); }
  function isTransit(name) { return TRANSIT_RE.test(zh(name)); }
  function isLodging(name) { return LODGING_RE.test(zh(name)); }

  function groupOf(key) {
    for (var i = 0; i < GROUPS.length; i++) {
      if (GROUPS[i].keys.indexOf(key) >= 0) return GROUPS[i].label;
    }
    return '市區';
  }

  // haversine，公里。這是直線距離，不是車程。
  function km(a, b) {
    var R = 6371, rad = function (d) { return d * Math.PI / 180; };
    var dLat = rad(b.lat - a.lat), dLng = rad(b.lng - a.lng);
    var h = Math.sin(dLat / 2) * Math.sin(dLat / 2)
      + Math.cos(rad(a.lat)) * Math.cos(rad(b.lat)) * Math.sin(dLng / 2) * Math.sin(dLng / 2);
    return 2 * R * Math.asin(Math.sqrt(Math.min(1, h)));
  }

  function centroid(list) {
    var la = 0, ln = 0;
    for (var i = 0; i < list.length; i++) { la += list[i].lat; ln += list[i].lng; }
    return { lat: la / list.length, lng: ln / list.length };
  }

  /**
   * 把 poi-data 依 district 分桶。離島直接用它自己的目的地鍵。
   * @param {object} poiData window.WAI_POI_DATA
   * @returns {object} { 鄉鎮名: [poi, ...] }
   */
  function bucketByDistrict(poiData) {
    var out = {};
    if (!poiData) return out;
    Object.keys(poiData).forEach(function (destKey) {
      if (destKey === '__generatedAt' || !Array.isArray(poiData[destKey])) return;
      poiData[destKey].forEach(function (p) {
        if (!p || !p.name || !isFinite(p.lat) || !isFinite(p.lng)) return;
        // 分類由 export:local 寫進 kind（交叉比對過 restaurant-data.js）；
        // 舊資料沒有 kind 時退回名稱正則，維持相容。
        var kind = p.kind || (isTransit(p.name) ? 'transit' : (isLodging(p.name) ? 'lodging' : 'scenic'));
        if (kind !== 'scenic') return;
        // 離島：poi-data 的桶名就是目的地，不看 district
        var key = ISLANDS[destKey] ? destKey : p.district;
        if (!key) return;                 // 沒有行政區就不進鄉鎮桶（不用座標猜）
        if (!out[key]) out[key] = [];
        // 同名只留一筆（不同 bucket 可能各有一份）
        for (var i = 0; i < out[key].length; i++) {
          if (zh(out[key][i].name) === zh(p.name)) return;
        }
        out[key].push(p);
      });
    });
    Object.keys(out).forEach(function (k) {
      out[k].sort(function (a, b) { return (b.rating || 0) - (a.rating || 0); });
    });
    return out;
  }

  /** 依「離出發點由近到遠」串起來，避免來回跑 */
  function orderByChain(stops) {
    var rest = stops.slice(), out = [], cur = GATE;
    while (rest.length) {
      var bi = 0, bd = Infinity;
      for (var i = 0; i < rest.length; i++) {
        var d = km(cur, rest[i]);
        if (d < bd) { bd = d; bi = i; }
      }
      cur = rest[bi]; out.push(cur); rest.splice(bi, 1);
    }
    return out;
  }

  /**
   * 組一份範本。
   * @param {string} key 鄉鎮名（或離島名）
   * @param {object} buckets bucketByDistrict 的輸出
   * @returns {object|null} { key, group, stops, mainCount, viaCount, island, km, stayMin, rating }
   */
  function buildTemplate(key, buckets) {
    var own = (buckets[key] || []).slice();
    if (!own.length) return null;
    var group = groupOf(key);

    var stops;
    var mainCount, viaCount;
    if (ISLANDS[key]) {
      // 離島沒有陸路往返，就是島上一圈
      stops = orderByChain(own.slice(0, 5).map(function (s) { return copyStop(s, false); }));
      mainCount = stops.length; viaCount = 0;
    } else {
      var target = centroid(own);
      var baseline = km(GATE, target);
      var wantMain = own.length >= 6 ? 3 : (own.length >= 3 ? 2 : own.length);
      var main = own.slice(0, wantMain).map(function (s) { return copyStop(s, false); });

      // 沿途候選：同走廊 + 市區，且繞路成本夠小
      var pool = [];
      Object.keys(buckets).forEach(function (k) {
        if (k === key || ISLANDS[k]) return;
        var g = groupOf(k);
        if (g !== group && g !== '市區') return;
        buckets[k].forEach(function (s) {
          var detour = km(GATE, s) + km(s, target) - baseline;
          if (detour <= 9) {
            var c = copyStop(s, true);
            c.fromDistrict = k;
            c._detour = detour;
            c._outbound = km(GATE, s) < km(s, target);
            pool.push(c);
          }
        });
      });
      pool.sort(function (a, b) { return (a._detour - b._detour) || ((b.rating || 0) - (a.rating || 0)); });

      // 去程挑 1、回程挑 1，盡量來自不同鄉鎮
      var picked = [], usedName = {}, usedFrom = {};
      [true, false].forEach(function (outbound) {
        for (var i = 0; i < pool.length; i++) {
          var s = pool[i];
          if (s._outbound !== outbound || usedName[s.name] || usedFrom[s.fromDistrict]) continue;
          picked.push(s); usedName[s.name] = 1; usedFrom[s.fromDistrict] = 1;
          break;
        }
      });
      // 本身景點少的鄉鎮，多補一站把一天撐起來
      if (own.length < 6) {
        for (var j = 0; j < pool.length; j++) {
          if (!usedName[pool[j].name]) { picked.push(pool[j]); usedName[pool[j].name] = 1; break; }
        }
      }
      stops = orderByChain(main.concat(picked));
      mainCount = main.length; viaCount = picked.length;
    }

    if (!stops.length) return null;

    var nodes = ISLANDS[key] ? stops : [GATE].concat(stops).concat([GATE]);
    var dist = 0;
    for (var n = 1; n < nodes.length; n++) dist += km(nodes[n - 1], nodes[n]);
    var stayMin = stops.reduce(function (a, s) { return a + (s.duration || 45); }, 0);
    var rated = stops.filter(function (s) { return s.rating > 0; });

    return {
      key: key,
      group: group,
      emoji: GROUP_EMOJI[group] || '📍',
      title: key + '・' + (dist > 120 && !ISLANDS[key] ? '兩日慢行' : TITLE_SUFFIX[group]),
      stops: stops,
      mainCount: mainCount,
      viaCount: viaCount,
      island: !!ISLANDS[key],
      km: dist,
      farForOneDay: !ISLANDS[key] && dist > 120,   // 來回直線 >120km，當天往返太趕
      stayMin: stayMin,
      // 只平均「有評分」的站：poi-data 有一部分景點 rating=0（沒有 Places 評分），
      // 把 0 一起算會讓整趟變成 ★1.1 這種看起來像爛行程的數字
      rating: rated.length ? (rated.reduce(function (a, s) { return a + s.rating; }, 0) / rated.length) : null,
      ratedCount: rated.length
    };
  }

  function copyStop(s, via) {
    return {
      name: zh(s.name), lat: s.lat, lng: s.lng,
      duration: s.duration || 45, rating: s.rating || 0,
      desc: s.desc || '', businessHours: s.businessHours || '',
      fee: s.fee, feeNote: s.feeNote, district: s.district || '',
      via: !!via
    };
  }

  /** 產生封面用的路線 SVG（純字串，呼叫端自行 escape 無虞：內容全是數字與固定色碼） */
  function routeSvg(tpl, idSuffix) {
    var t = THEME[tpl.group] || THEME['市區'];
    var nodes = tpl.island ? tpl.stops : [GATE].concat(tpl.stops).concat([GATE]);
    var pts = nodes.map(function (s) {
      var rad = s.lat * Math.PI / 180;
      return { x: s.lng * Math.cos(rad), y: -s.lat };
    });
    var W = 300, H = 148, PAD = 32;
    var xs = pts.map(function (p) { return p.x; }), ys = pts.map(function (p) { return p.y; });
    var minX = Math.min.apply(null, xs), maxX = Math.max.apply(null, xs);
    var minY = Math.min.apply(null, ys), maxY = Math.max.apply(null, ys);
    var spanX = Math.max(maxX - minX, 1e-4), spanY = Math.max(maxY - minY, 1e-4);
    var scale = Math.min((W - PAD * 2) / spanX, (H - PAD * 2) / spanY);
    var cx = (minX + maxX) / 2, cy = (minY + maxY) / 2;
    var proj = pts.map(function (p) {
      return { x: W / 2 + (p.x - cx) * scale, y: H / 2 + (p.y - cy) * scale };
    });
    var d = proj.map(function (p, i) {
      return (i ? 'L' : 'M') + p.x.toFixed(1) + ' ' + p.y.toFixed(1);
    }).join(' ');
    var dots = proj.map(function (p, i) {
      var isGate = !tpl.island && (i === 0 || i === proj.length - 1);
      if (isGate && i === proj.length - 1) return '';          // 起訖同點，畫一次就好
      var s = tpl.island ? tpl.stops[i] : (i === 0 ? GATE : tpl.stops[i - 1]);
      var via = s && s.via;
      return '<circle cx="' + p.x.toFixed(1) + '" cy="' + p.y.toFixed(1) + '"'
        + ' r="' + (isGate ? 5.5 : (via ? 3 : 4.5)) + '"'
        + ' fill="' + (isGate ? t.line : (via ? 'rgba(255,255,255,.55)' : '#fff')) + '"'
        + ' stroke="' + t.from + '" stroke-width="' + (via ? 1.5 : 2) + '"/>';
    }).join('');
    // ★ 不能直接濾掉非 ASCII：鄉鎮名是中文，濾完每張卡的 id 都變成 'wt-'，
    //   所有 <rect> 就都指向第一個漸層，六張卡會長得一模一樣（實測全變橘色）。
    //   改成把字元碼接起來，保證每個 key 有唯一且合法的 id。
    var gid = 'wt-' + String(idSuffix || '0').split('').map(function (ch) {
      return ch.charCodeAt(0).toString(36);
    }).join('');
    var grid = '';
    for (var g = 0; g < 7; g++) grid += '<line x1="0" y1="' + (g * 22 + 8) + '" x2="' + W + '" y2="' + (g * 22 + 8) + '"/>';
    return '<svg viewBox="0 0 ' + W + ' ' + H + '" preserveAspectRatio="xMidYMid slice" aria-hidden="true" focusable="false">'
      + '<defs><linearGradient id="' + gid + '" x1="0" y1="0" x2="1" y2="1">'
      + '<stop offset="0%" stop-color="' + t.from + '"/><stop offset="100%" stop-color="' + t.to + '"/>'
      + '</linearGradient></defs>'
      + '<rect width="' + W + '" height="' + H + '" fill="url(#' + gid + ')"/>'
      + '<g opacity=".12" stroke="#fff" stroke-width="1">' + grid + '</g>'
      + '<path d="' + d + '" fill="none" stroke="rgba(0,0,0,.2)" stroke-width="5" stroke-linecap="round" stroke-linejoin="round" transform="translate(0,1.5)"/>'
      + '<path d="' + d + '" fill="none" stroke="' + t.line + '" stroke-width="2.6" stroke-linecap="round" stroke-linejoin="round"/>'
      + dots + '</svg>';
  }

  function fmtDuration(min) {
    var h = Math.floor(min / 60), m = min % 60;
    return h ? (m ? h + '小時' + m + '分' : h + '小時') : m + '分';
  }

  return {
    GROUPS: GROUPS,
    GATE: GATE,
    groupOf: groupOf,
    bucketByDistrict: bucketByDistrict,
    buildTemplate: buildTemplate,
    routeSvg: routeSvg,
    fmtDuration: fmtDuration,
    km: km,
    isTransit: isTransit,
    isLodging: isLodging
  };
}));
