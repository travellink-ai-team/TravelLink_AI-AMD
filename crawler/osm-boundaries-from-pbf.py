"""大景點離線邊界：Geofabrik 台灣 OSM 檔（.osm.pbf）→ app/osm-boundary-data.js

與 `npm run export:boundaries`（worker.js，走 Overpass）產出同一個檔、同一種格式，差別只在資料來源：
公開 Overpass 2026-10 起常整批滿載（立刻 502 或一直沒回應），這支改讀 Geofabrik 每日更新的
台灣 OSM 資料檔，在本機組面、比對，不打任何 API，幾分鐘就跑完。

用法（在 crawler/ 下）：
  python -m pip install --user osmium          # pyosmium，只要裝一次
  # 下載 https://download.geofabrik.de/asia/taiwan-latest.osm.pbf（約 330 MB）到任意資料夾
  python osm-boundaries-from-pbf.py <taiwan-latest.osm.pbf 的路徑> [--dry-run]

比對規則跟 worker.js 的 exportBoundaries 一致：
  - 景點取 app/poi-data.js 裡 kind == 'scenic' 的項目
  - OSM 面（封閉 way 或 multipolygon）要有 name，名稱與某景點互相包含、質心在 3 km 內、
    環半徑 3 km 內；水體／海岸／行政區等標籤直接排除
  - 檔案只存「候選面」，最後挑哪一個由前端 pickOsmBoundary 決定（與即時查詢同一套規則）
資料授權：© OpenStreetMap contributors，ODbL 1.0。
"""
import json
import math
import os
import re
import sys
from datetime import datetime, timezone

import osmium

HERE = os.path.dirname(os.path.abspath(__file__))
APP = os.path.join(HERE, '..', 'app')
POI_FILE = os.path.join(APP, 'poi-data.js')
OUT_FILE = os.environ.get('BOUNDARY_DATA_PATH') or os.path.join(APP, 'osm-boundary-data.js')

AROUND_M = 3000
MAX_RING_R = 3000
MAX_PTS = 120
BAD_NATURAL = re.compile(r'^(water|bay|strait|coastline|wetland|reef|shoal|cape|peninsula)$', re.I)
# 台東縣本島＋綠島＋蘭嶼（與 worker.js 的 TAITUNG_OSM_BOXES 相同），外擴一點給跨界的面
BOXES = [
    (22.30, 120.74, 23.50, 121.70),
    (22.63, 121.44, 22.72, 121.53),
    (21.90, 121.48, 22.12, 121.65),
]
PAD = 0.05


def name_core(name):
    return re.sub(r'[（(][^）)]*[）)]', '', str(name or '')).strip()


def norm(name):
    # 對應 JS：core → 臺→台 → 小寫 → 去掉非字母數字
    return re.sub(r'[\W_]+', '', name_core(name).replace('臺', '台').lower())


def dist_m(a_lat, a_lng, b_lat, b_lng):
    r = 6371000
    d_lat = math.radians(b_lat - a_lat)
    d_lng = math.radians(b_lng - a_lng)
    h = math.sin(d_lat / 2) ** 2 + math.cos(math.radians(a_lat)) * math.cos(math.radians(b_lat)) * math.sin(d_lng / 2) ** 2
    return 2 * r * math.asin(math.sqrt(h))


def in_boxes(lat, lng):
    return any(s - PAD <= lat <= n + PAD and w - PAD <= lng <= e + PAD for s, w, n, e in BOXES)


def js_key(name, lat, lng):
    # 與 worker.js 的 `${boundaryNorm(name)}@${lat.toFixed(3)},${lng.toFixed(3)}` 相同，讓 Overpass 模式可接著續跑
    return f'{norm(name)}@{lat:.3f},{lng:.3f}'


def load_pois():
    text = open(POI_FILE, encoding='utf-8').read()
    m = re.search(r'window\.WAI_POI_DATA\s*=\s*(\{.*\})\s*;?\s*$', text, re.S)
    if not m:
        sys.exit(f'讀不到 {POI_FILE} 的 window.WAI_POI_DATA')
    data = json.loads(m.group(1))
    pois, seen = [], set()
    for bucket in data.values():
        if not isinstance(bucket, list):
            continue
        for p in bucket:
            if not isinstance(p, dict) or p.get('kind') != 'scenic':
                continue
            lat, lng = p.get('lat'), p.get('lng')
            if not isinstance(lat, (int, float)) or not isinstance(lng, (int, float)):
                continue
            n = norm(p.get('name'))
            key = js_key(p.get('name'), lat, lng)
            if len(name_core(p.get('name'))) < 2 or key in seen:
                continue
            seen.add(key)
            pois.append({'key': key, 'norm': n, 'lat': lat, 'lng': lng})
    return pois


def ring_area(ring):
    a = 0.0
    for i in range(len(ring)):
        p, q = ring[i], ring[(i + 1) % len(ring)]
        a += p[1] * q[0] - q[1] * p[0]
    return abs(a) / 2


def simplify(ring):
    if len(ring) <= MAX_PTS:
        return ring
    step = math.ceil(len(ring) / MAX_PTS)
    return ring[::step]


def main():
    try:
        sys.stdout.reconfigure(encoding='utf-8', errors='replace')  # Windows 主控台預設 cp950，中文會變亂碼
    except Exception:
        pass
    args = [a for a in sys.argv[1:] if not a.startswith('--')]
    dry = '--dry-run' in sys.argv
    if not args or not os.path.isfile(args[0]):
        sys.exit(__doc__)
    pois = load_pois()
    print(f'景點 {len(pois)} 個（poi-data.js，kind=scenic），讀取 {args[0]} …', flush=True)

    items = {}
    scanned = 0
    fp = (osmium.FileProcessor(args[0])
          .with_areas(osmium.filter.KeyFilter('name'))
          .with_filter(osmium.filter.EntityFilter(osmium.osm.AREA)))
    for area in fp:
        scanned += 1
        tags = area.tags
        name = tags.get('name')
        if not name or 'boundary' in tags or 'place' in tags or 'waterway' in tags or 'water' in tags:
            continue
        if BAD_NATURAL.match(tags.get('natural', '')):
            continue
        rings = []
        try:
            for outer in area.outer_rings():
                r = [(nr.lat, nr.lon) for nr in outer if nr.location.valid()]
                if len(r) >= 4 and r[0] == r[-1]:
                    r = r[:-1]
                if len(r) >= 3:
                    rings.append(r)
        except Exception:
            continue
        if not rings:
            continue
        ring = max(rings, key=ring_area)
        c_lat = sum(p[0] for p in ring) / len(ring)
        c_lng = sum(p[1] for p in ring) / len(ring)
        if not in_boxes(c_lat, c_lng):
            continue
        n = norm(name)
        if not n:
            continue
        # 名稱互相包含＋3 km 內；反方向（景點名包含 OSM 名）要求 OSM 名至少 3 字，避免「公園」這種泛稱灌進來
        hit = any(
            dist_m(c_lat, c_lng, p['lat'], p['lng']) <= AROUND_M
            and (p['norm'] in n or (len(n) >= 3 and n in p['norm']))
            for p in pois
        )
        if not hit:
            continue
        ring_r = max(dist_m(c_lat, c_lng, p[0], p[1]) for p in ring)
        if ring_r > MAX_RING_R:
            continue
        oid = f"{'way' if area.from_way() else 'relation'}/{area.orig_id()}"
        items[oid] = {
            'id': oid,
            'n': name,
            'r': [[round(lat, 5), round(lng, 5)] for lat, lng in simplify(ring)],
        }

    out = {
        '__generatedAt': datetime.now(timezone.utc).isoformat(timespec='milliseconds').replace('+00:00', 'Z'),
        '__attribution': '© OpenStreetMap contributors (ODbL)',
        '__source': 'Geofabrik ' + os.path.basename(args[0]),
        '__done': sorted(p['key'] for p in pois),
        'items': sorted(items.values(), key=lambda it: it['id']),
    }
    body = ('// Auto-generated by crawler (python osm-boundaries-from-pbf.py). Do not edit by hand.\n'
            '// 景點邊界 © OpenStreetMap contributors，ODbL 1.0 授權（https://www.openstreetmap.org/copyright）\n'
            'window.WAI_OSM_BOUNDARY_DATA = ' + json.dumps(out, ensure_ascii=False, separators=(',', ':')) + ';\n')
    print(f'掃過 {scanned} 個面，收 {len(items)} 個候選面，{round(len(body.encode("utf-8")) / 1024)} KB', flush=True)
    if dry:
        print('DRY：未寫檔')
        return
    with open(OUT_FILE, 'w', encoding='utf-8', newline='\n') as f:
        f.write(body)
    print(f'已寫入 {os.path.normpath(OUT_FILE)}')


if __name__ == '__main__':
    main()
