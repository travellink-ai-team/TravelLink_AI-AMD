// qr-encode.js — 本地 QR Code 產生器（純 vanilla，無套件、無外部服務）
//
// 為什麼要自己做：原本三處 QR 都是把網址丟給 api.qrserver.com 產圖。
// 那個網址裡有共編行程的 sharedId 與 token——等於把私密邀請權杖當 query string
// 送給第三方服務。外部服務慢或掛掉時 QR 也跟著不見（實測過載入不出來）。
//
// 範圍刻意收斂到「這個專案實際需要的」：位元組模式（UTF-8）、容錯等級 M、版本 1–10
// （最多 216 bytes，遠超過分享連結的 ~121 字元）。超出範圍回傳 null，由呼叫端決定退路，
// 不做半調子的猜測。
//
// API：
//   WAIQR.render(text, { size, margin, dark, light }) → HTMLCanvasElement | null
//   WAIQR.toDataURL(text, opts) → string | null
//   WAIQR.encode(text) → { size, modules: Uint8Array }（1 = 黑）| null   ※供測試驗證用
(function (root, factory) {
  if (typeof module === 'object' && module.exports) module.exports = factory();
  else root.WAIQR = factory();
}(typeof self !== 'undefined' ? self : this, function () {
  'use strict';

  // ── GF(256)，本原多項式 0x11D ──
  var EXP = new Uint8Array(512);
  var LOG = new Uint8Array(256);
  (function () {
    var x = 1;
    for (var i = 0; i < 255; i++) {
      EXP[i] = x;
      LOG[x] = i;
      x <<= 1;
      if (x & 0x100) x ^= 0x11D;
    }
    for (var j = 255; j < 512; j++) EXP[j] = EXP[j - 255];
  }());

  function gfMul(a, b) {
    if (a === 0 || b === 0) return 0;
    return EXP[LOG[a] + LOG[b]];
  }

  // 產生 degree 次的 Reed-Solomon 生成多項式
  function rsGenerator(degree) {
    var poly = [1];
    for (var d = 0; d < degree; d++) {
      var next = new Array(poly.length + 1).fill(0);
      for (var i = 0; i < poly.length; i++) {
        next[i] ^= poly[i];
        next[i + 1] ^= gfMul(poly[i], EXP[d]);
      }
      poly = next;
    }
    return poly;
  }

  function rsEncode(data, eccLen) {
    var gen = rsGenerator(eccLen);
    var res = new Uint8Array(eccLen);
    for (var i = 0; i < data.length; i++) {
      var factor = data[i] ^ res[0];
      res.copyWithin(0, 1);
      res[eccLen - 1] = 0;
      if (factor !== 0) {
        for (var j = 0; j < eccLen; j++) res[j] ^= gfMul(gen[j + 1], factor);
      }
    }
    return res;
  }

  // ── 版本表（容錯等級 M）──
  // [eccPerBlock, 群組1區塊數, 群組1每塊資料碼字, 群組2區塊數, 群組2每塊資料碼字]
  var BLOCKS_M = {
    1: [10, 1, 16, 0, 0],
    2: [16, 1, 28, 0, 0],
    3: [26, 1, 44, 0, 0],
    4: [18, 2, 32, 0, 0],
    5: [24, 2, 43, 0, 0],
    6: [16, 4, 27, 0, 0],
    7: [18, 4, 31, 0, 0],
    8: [22, 2, 38, 2, 39],
    9: [22, 3, 36, 2, 37],
    10: [26, 4, 43, 1, 44]
  };

  var ALIGN = {
    1: [], 2: [6, 18], 3: [6, 22], 4: [6, 26], 5: [6, 30],
    6: [6, 34], 7: [6, 22, 38], 8: [6, 24, 42], 9: [6, 26, 46], 10: [6, 28, 50]
  };

  function dataCodewords(version) {
    var b = BLOCKS_M[version];
    return b[1] * b[2] + b[3] * b[4];
  }

  function countBits(version) { return version <= 9 ? 8 : 16; }

  function pickVersion(byteLen) {
    for (var v = 1; v <= 10; v++) {
      var needBits = 4 + countBits(v) + byteLen * 8;
      if (needBits <= dataCodewords(v) * 8) return v;
    }
    return 0;
  }

  // ── BCH（格式資訊與版本資訊共用）──
  function bch(value, generator, genBits) {
    var v = value << (genBits - 1);
    var genTop = 1 << (genBits - 1);
    while (v >>> 0 >= genTop) {
      var shift = 0;
      var t = v;
      while (t >>> 1) { t >>>= 1; shift++; }
      v ^= generator << (shift - (genBits - 1));
    }
    return v;
  }

  function formatBits(mask) {
    // 容錯等級 M 的 2 bit 是 00
    var data = (0 << 3) | mask;
    var rem = bch(data, 0x537, 11);
    return ((data << 10) | rem) ^ 0x5412;
  }

  function versionBits(version) {
    var rem = bch(version, 0x1F25, 13);
    return (version << 12) | rem;
  }

  // ── 位元串流 ──
  function BitBuffer() { this.bits = []; }
  BitBuffer.prototype.put = function (value, length) {
    for (var i = length - 1; i >= 0; i--) this.bits.push((value >>> i) & 1);
  };

  function utf8Bytes(text) {
    if (typeof TextEncoder === 'function') return new TextEncoder().encode(text);
    var out = [];
    var s = unescape(encodeURIComponent(String(text)));
    for (var i = 0; i < s.length; i++) out.push(s.charCodeAt(i) & 0xFF);
    return new Uint8Array(out);
  }

  // ── 把資料編成最終的碼字序列（含分塊、RS、交錯）──
  function buildCodewords(bytes, version) {
    var b = BLOCKS_M[version];
    var eccLen = b[0];
    var total = dataCodewords(version);

    var buf = new BitBuffer();
    buf.put(4, 4);                       // 位元組模式
    buf.put(bytes.length, countBits(version));
    for (var i = 0; i < bytes.length; i++) buf.put(bytes[i], 8);
    // 終止符最多 4 bit，且不可超出容量
    var terminator = Math.min(4, total * 8 - buf.bits.length);
    buf.put(0, terminator);
    while (buf.bits.length % 8 !== 0) buf.bits.push(0);

    var data = [];
    for (var k = 0; k < buf.bits.length; k += 8) {
      var byte = 0;
      for (var j = 0; j < 8; j++) byte = (byte << 1) | buf.bits[k + j];
      data.push(byte);
    }
    var pad = [0xEC, 0x11];
    var p = 0;
    while (data.length < total) data.push(pad[p++ % 2]);

    // 分塊
    var blocks = [];
    var offset = 0;
    function take(count, size) {
      for (var n = 0; n < count; n++) {
        var slice = Uint8Array.from(data.slice(offset, offset + size));
        offset += size;
        blocks.push({ data: slice, ecc: rsEncode(slice, eccLen) });
      }
    }
    take(b[1], b[2]);
    take(b[3], b[4]);

    // 交錯
    var out = [];
    var maxData = Math.max(b[2], b[4]);
    for (var c = 0; c < maxData; c++) {
      for (var bi = 0; bi < blocks.length; bi++) {
        if (c < blocks[bi].data.length) out.push(blocks[bi].data[c]);
      }
    }
    for (var e = 0; e < eccLen; e++) {
      for (var bj = 0; bj < blocks.length; bj++) out.push(blocks[bj].ecc[e]);
    }
    return out;
  }

  // ── 矩陣 ──
  function makeMatrix(version) {
    var size = version * 4 + 17;
    var modules = new Int8Array(size * size).fill(-1);   // -1 = 尚未填，0/1 = 已定
    var reserved = new Uint8Array(size * size);
    var at = function (r, c) { return r * size + c; };

    function setFinder(r0, c0) {
      for (var r = -1; r <= 7; r++) {
        for (var c = -1; c <= 7; c++) {
          var rr = r0 + r, cc = c0 + c;
          if (rr < 0 || cc < 0 || rr >= size || cc >= size) continue;
          var inRing = (r >= 0 && r <= 6 && (c === 0 || c === 6))
            || (c >= 0 && c <= 6 && (r === 0 || r === 6))
            || (r >= 2 && r <= 4 && c >= 2 && c <= 4);
          modules[at(rr, cc)] = inRing ? 1 : 0;
          reserved[at(rr, cc)] = 1;
        }
      }
    }
    setFinder(0, 0); setFinder(0, size - 7); setFinder(size - 7, 0);

    // 時序圖樣
    for (var i = 8; i < size - 8; i++) {
      var v = i % 2 === 0 ? 1 : 0;
      modules[at(6, i)] = v; reserved[at(6, i)] = 1;
      modules[at(i, 6)] = v; reserved[at(i, 6)] = 1;
    }

    // 校正圖樣（不可蓋到定位圖樣）
    var centers = ALIGN[version];
    for (var a = 0; a < centers.length; a++) {
      for (var b2 = 0; b2 < centers.length; b2++) {
        var cr = centers[a], cc2 = centers[b2];
        if ((cr === 6 && cc2 === 6) || (cr === 6 && cc2 === size - 7)
          || (cr === size - 7 && cc2 === 6)) continue;
        for (var dr = -2; dr <= 2; dr++) {
          for (var dc = -2; dc <= 2; dc++) {
            var on = Math.max(Math.abs(dr), Math.abs(dc)) !== 1;
            modules[at(cr + dr, cc2 + dc)] = on ? 1 : 0;
            reserved[at(cr + dr, cc2 + dc)] = 1;
          }
        }
      }
    }

    // 固定的黑點
    modules[at(size - 8, 8)] = 1; reserved[at(size - 8, 8)] = 1;

    // 保留格式資訊區
    for (var f = 0; f < 9; f++) {
      if (modules[at(8, f)] === -1) { modules[at(8, f)] = 0; }
      reserved[at(8, f)] = 1;
      if (modules[at(f, 8)] === -1) { modules[at(f, 8)] = 0; }
      reserved[at(f, 8)] = 1;
    }
    for (var g = 0; g < 8; g++) {
      reserved[at(8, size - 1 - g)] = 1;
      if (modules[at(8, size - 1 - g)] === -1) modules[at(8, size - 1 - g)] = 0;
      reserved[at(size - 1 - g, 8)] = 1;
      if (modules[at(size - 1 - g, 8)] === -1) modules[at(size - 1 - g, 8)] = 0;
    }

    // 版本資訊（v7 以上）
    if (version >= 7) {
      var vb = versionBits(version);
      for (var k = 0; k < 18; k++) {
        var bit = (vb >>> k) & 1;
        var r1 = Math.floor(k / 3), c1 = size - 11 + (k % 3);
        modules[at(r1, c1)] = bit; reserved[at(r1, c1)] = 1;
        modules[at(c1, r1)] = bit; reserved[at(c1, r1)] = 1;
      }
    }

    return { size: size, modules: modules, reserved: reserved, at: at };
  }

  function placeData(m, codewords) {
    var size = m.size, modules = m.modules, reserved = m.reserved, at = m.at;
    var bitIndex = 0;
    var total = codewords.length * 8;
    var upward = true;
    for (var right = size - 1; right >= 1; right -= 2) {
      if (right === 6) right = 5;   // 跳過垂直時序線
      for (var step = 0; step < size; step++) {
        var row = upward ? size - 1 - step : step;
        for (var col = right; col >= right - 1; col--) {
          if (reserved[at(row, col)]) continue;
          var bit = 0;
          if (bitIndex < total) {
            bit = (codewords[bitIndex >> 3] >>> (7 - (bitIndex & 7))) & 1;
            bitIndex++;
          }
          modules[at(row, col)] = bit;
        }
      }
      upward = !upward;
    }
  }

  function maskFn(mask, r, c) {
    switch (mask) {
      case 0: return (r + c) % 2 === 0;
      case 1: return r % 2 === 0;
      case 2: return c % 3 === 0;
      case 3: return (r + c) % 3 === 0;
      case 4: return (Math.floor(r / 2) + Math.floor(c / 3)) % 2 === 0;
      case 5: return ((r * c) % 2) + ((r * c) % 3) === 0;
      case 6: return (((r * c) % 2) + ((r * c) % 3)) % 2 === 0;
      default: return (((r + c) % 2) + ((r * c) % 3)) % 2 === 0;
    }
  }

  function penalty(grid, size) {
    var score = 0, r, c, i;
    // 規則 1：同色連續 5 格以上
    for (r = 0; r < size; r++) {
      for (var dir = 0; dir < 2; dir++) {
        var run = 1, prev = -1;
        for (c = 0; c < size; c++) {
          var val = dir === 0 ? grid[r * size + c] : grid[c * size + r];
          if (val === prev) { run++; } else { if (run >= 5) score += run - 2; run = 1; prev = val; }
        }
        if (run >= 5) score += run - 2;
      }
    }
    // 規則 2：2×2 同色
    for (r = 0; r < size - 1; r++) {
      for (c = 0; c < size - 1; c++) {
        var v0 = grid[r * size + c];
        if (v0 === grid[r * size + c + 1] && v0 === grid[(r + 1) * size + c] && v0 === grid[(r + 1) * size + c + 1]) score += 3;
      }
    }
    // 規則 3：1:1:3:1:1 圖樣（含四周留白）
    var pat1 = [1, 0, 1, 1, 1, 0, 1, 0, 0, 0, 0];
    var pat2 = [0, 0, 0, 0, 1, 0, 1, 1, 1, 0, 1];
    function matches(get, start, pat) {
      for (var k = 0; k < 11; k++) if (get(start + k) !== pat[k]) return false;
      return true;
    }
    for (r = 0; r < size; r++) {
      for (c = 0; c + 11 <= size; c++) {
        var getRow = function (x) { return grid[r * size + x]; };
        if (matches(getRow, c, pat1) || matches(getRow, c, pat2)) score += 40;
        var getCol = function (x) { return grid[x * size + r]; };
        if (matches(getCol, c, pat1) || matches(getCol, c, pat2)) score += 40;
      }
    }
    // 規則 4：黑白比例偏離 50%
    var dark = 0;
    for (i = 0; i < grid.length; i++) if (grid[i]) dark++;
    var ratio = Math.abs((dark * 100) / grid.length - 50);
    score += Math.floor(ratio / 5) * 10;
    return score;
  }

  function applyFormat(grid, size, mask) {
    var fb = formatBits(mask);
    for (var k = 0; k < 15; k++) {
      var bit = (fb >>> k) & 1;
      // 左上
      if (k < 6) grid[k * size + 8] = bit;
      else if (k === 6) grid[7 * size + 8] = bit;
      else if (k === 7) grid[8 * size + 8] = bit;
      else if (k === 8) grid[8 * size + 7] = bit;
      else grid[8 * size + (14 - k)] = bit;
      // 右上／左下
      if (k < 8) grid[8 * size + (size - 1 - k)] = bit;
      else grid[(size - 15 + k) * size + 8] = bit;
    }
  }

  function encode(text) {
    var bytes = utf8Bytes(text);
    var version = pickVersion(bytes.length);
    if (!version) return null;    // 超過 v10-M 的容量，交給呼叫端決定退路
    var codewords = buildCodewords(bytes, version);
    var m = makeMatrix(version);
    placeData(m, codewords);

    var best = null;
    for (var mask = 0; mask < 8; mask++) {
      var grid = new Uint8Array(m.size * m.size);
      for (var r = 0; r < m.size; r++) {
        for (var c = 0; c < m.size; c++) {
          var idx = r * m.size + c;
          var v = m.modules[idx] === 1 ? 1 : 0;
          if (!m.reserved[idx] && maskFn(mask, r, c)) v ^= 1;
          grid[idx] = v;
        }
      }
      applyFormat(grid, m.size, mask);
      var score = penalty(grid, m.size);
      if (!best || score < best.score) best = { score: score, grid: grid, mask: mask };
    }
    return { size: m.size, version: version, mask: best.mask, modules: best.grid };
  }

  function render(text, opts) {
    opts = opts || {};
    var qr = encode(text);
    if (!qr || typeof document === 'undefined') return null;
    var margin = opts.margin == null ? 4 : opts.margin;
    var target = opts.size || 144;
    var dim = qr.size + margin * 2;
    // 用 ceil 而不是 floor：floor 會讓 144px 的目標只畫成 106px，再被放大顯示就糊掉
    // （45 模組 + 8 留白 = 53，floor(144/53)=2）。寧可畫大一點再讓 CSS 縮，掃描也更穩。
    var scale = Math.max(1, Math.ceil(target / dim));
    var px = dim * scale;
    var canvas = document.createElement('canvas');
    canvas.width = px; canvas.height = px;
    canvas.style.width = target + 'px';
    canvas.style.height = target + 'px';
    canvas.style.imageRendering = 'pixelated';   // 縮放時保持模組邊緣銳利
    var ctx = canvas.getContext('2d');
    ctx.fillStyle = opts.light || '#ffffff';
    ctx.fillRect(0, 0, px, px);
    ctx.fillStyle = opts.dark || '#000000';
    for (var r = 0; r < qr.size; r++) {
      for (var c = 0; c < qr.size; c++) {
        if (qr.modules[r * qr.size + c]) {
          ctx.fillRect((c + margin) * scale, (r + margin) * scale, scale, scale);
        }
      }
    }
    return canvas;
  }

  function toDataURL(text, opts) {
    var canvas = render(text, opts);
    return canvas ? canvas.toDataURL('image/png') : null;
  }

  return { encode: encode, render: render, toDataURL: toDataURL, MAX_VERSION: 10 };
}));
