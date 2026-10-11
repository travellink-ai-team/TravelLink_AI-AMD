#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
build_local_places.py

把四份本地資料合併成單一 assets/local_places.json，供 App 端使用：
  1. app/src/main/assets/seeded_pois.json  ── 現行種子清單（含 typeName）
  2. scripts/data/poi-data.js              ── 組員新版 POI 匯出（含 fee / feeNote / 廁所）
  3. scripts/data/restaurant-data.js       ── 組員餐廳匯出（含 priceLevel / costPerPerson / costNote）
  4. scripts/data/tourism-attractions.json ── 觀光署官方景點（含 officialDesc / officialPhotoUrl）
                                              由 merge_tourism_data.py 產出，須先執行它

用途（一份檔案吃兩個好處）：
  • 更省 API：座標／營業時間齊全的地點會被 loadSeededPOIs() 命中，
    直接跳過 Geocoding／Nearby Search／Place Details。餐廳座標也一併納入。
  • 更準費用：CostReference 依名稱／座標比對到真實 fee 與 costPerPerson，
    取代 CostConfig 的硬編碼猜測值。

輸出 schema 沿用現行 seeded_pois.json 的 { generatedAt, count, pois:[...] }，
loadSeededPOIs() 的解析邏輯無需大改，只是每筆多了選填的成本欄位。

執行：
    python scripts/build_local_places.py
"""
import json
import re
import os
import sys
from datetime import datetime, timezone

# Windows 主控台預設 cp950 無法輸出 emoji，統一改用 UTF-8。
try:
    sys.stdout.reconfigure(encoding="utf-8")
except (AttributeError, ValueError):
    pass

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SEEDED_PATH = os.path.join(ROOT, "app", "src", "main", "assets", "seeded_pois.json")
POI_JS_PATH = os.path.join(ROOT, "scripts", "data", "poi-data.js")
REST_JS_PATH = os.path.join(ROOT, "scripts", "data", "restaurant-data.js")
TOURISM_PATH = os.path.join(ROOT, "scripts", "data", "tourism-attractions.json")
OUT_PATH = os.path.join(ROOT, "app", "src", "main", "assets", "local_places.json")

# fee 合理上限：超過視為活動／潛水等非「單點入場費」，估算時排除但保留 feeNote 說明。
FEE_SANITY_MAX = 800

# 離島 bbox：與 Kotlin 端 IslandRegistry（data/island/IslandProfile.kt）同一組範圍。
# 用途是抓「地址寫本島鄉鎮、座標卻落在離島」的錯抄——富岡漁港曾被抄成綠島南寮漁港的
# 座標（22.65791, 121.47449），本島行程的地圖與車程因此全錯，且會被離島守門員誤判。
ISLAND_BBOXES = {
    "綠島": (22.60, 22.75, 121.40, 121.55),
    "蘭嶼": (21.90, 22.15, 121.40, 121.65),
}
# 本島鄉鎮關鍵字：出現在 address 就代表這筆不該落在離島 bbox 裡。
MAINLAND_TOWNS = ("臺東市", "台東市", "卑南鄉", "太麻里", "東河鄉", "成功鎮", "長濱",
                  "池上", "關山", "鹿野", "延平", "海端", "大武", "達仁", "金峰")

# ── 餐飲型別判定 ──────────────────────────────────────────────────────
# 三份來源都沒有 typeName，舊版是「看檔案給型別」：seeded/poi-data 一律「景點」、
# restaurant-data 一律「餐廳」。於是
#   • 只收在 poi-data.js 的餐廳（阿拉慕廚房、硓宅食堂、SP夏帕義大利麵…）永遠是「景點」；
#   • 兩邊都有的餐廳由先跑的來源標成「景點」後，setdefault 就成了 no-op。
# 錯標的下場在 App 端：餐廳繞過每日餐飲上限，還會被當成景點補進行程空檔。
#
# 關鍵字刻意與 ItineraryViewModel.DINING_NAME_KEYWORDS 對齊——App 端那套名稱判斷
# 是資料出錯時的備援，兩邊分歧就會出現「資料說景點、程式說餐廳」。
CAFE_NAME_KEYWORDS = ("咖啡", "cafe", "café", "茶飲", "手搖", "茶館")
DINING_NAME_KEYWORDS = (
    "餐廳", "食堂", "小吃", "早午餐", "餐酒", "廚房", "料理", "小館", "飯館",
    # 「吃」「食」看似寬鬆，實測反例（台東客來吃樂、東粄香客家米食）都靠它們攔下，
    # 而景點名稱幾乎不會出現這兩字（夜市不列入，本專案把它算景點）
    "吃", "食",
    "麵", "米粉", "水餃", "便當", "火鍋", "燒肉", "燒烤", "鵝肉", "鴨肉", "豬排",
    "牛排", "速食", "炸雞", "披薩", "壽司", "拉麵", "剉冰", "冰品", "甜點", "蛋糕",
)

# 泛用型別：只有掛著它的記錄才會被名稱判定改標。已細分過的型別（來源標好的
# 「餐廳」、觀光署的「自然景觀」等）一律不動——否則「Moon island 沐嶋｜餐酒館｜
# 餐廳｜咖啡廳｜…」這種塞滿關鍵字的店名會被降格成咖啡廳。
GENERIC_TYPE = "景點"

# 佔位／範例資料：名稱只是個類型（「咖啡廳」）或寫成舉例（「咖啡館 (例如：…)」），
# 不是真的店家，座標也是隨手填的——「咖啡廳」那筆落在 25.2065/121.525（新北汐止），
# 離台東 200 公里。整筆刪掉，不掛回鄰近的真地點：那等於假設佔位字串的座標屬於那家店。
PLACEHOLDER_GENERIC_NAMES = {"咖啡廳", "咖啡館", "餐廳", "小吃", "景點", "早餐店", "台東"}
PLACEHOLDER_MARKERS = ("例如", "例：", "在地午餐")




def island_of(lat, lng):
    """回傳座標所在的離島名稱；不在任何離島 bbox 內則回傳 None。"""
    for name, (min_lat, max_lat, min_lng, max_lng) in ISLAND_BBOXES.items():
        if min_lat <= lat <= max_lat and min_lng <= lng <= max_lng:
            return name
    return None


def check_island_mismatch(pois):
    """找出地址寫本島、座標卻在離島 bbox 的地點。只警告不擋，避免擋掉正常出檔。"""
    bad = []
    for p in pois:
        island = island_of(p["lat"], p["lng"])
        if not island:
            continue
        address = p.get("address") or ""
        if any(town in address for town in MAINLAND_TOWNS):
            bad.append((p["name"], island, p["lat"], p["lng"], address))
    return bad


def load_window_js(path):
    """讀取 `window.XXX = { ... };` 形式的檔案，回傳 dict（已去掉 __generatedAt 等 meta）。"""
    with open(path, encoding="utf-8") as f:
        text = f.read()
    # 檔頭可能有 `// 註解` 與 `window.XXX =`，結尾可能有 `;`。
    # 直接擷取第一個 `{` 到最後一個 `}` 之間的 JSON 主體。
    start = text.index("{")
    end = text.rindex("}")
    return json.loads(text[start:end + 1])


def norm_key(name):
    """比對用正規化鍵：取第一個括號前、去空白、轉小寫。須與 Kotlin 端 CostReference 完全一致。"""
    if not name:
        return ""
    # 取第一個 ( 或 （ 之前
    cut = re.split(r"[（(]", name, maxsplit=1)[0]
    return re.sub(r"\s+", "", cut).lower()


_NAME_ALIASES_RAW = {
    # ── 全形／異體字與前綴差異 ──────────────────────────────────
    "台東美術館": "臺東美術館",
    "台東車站": "台東火車站",
    "多良車站": "多良火車站",
    "南寮漁港": "綠島南寮漁港",
    "綠島紫坪": "紫坪",
    "蘭嶼無餓不坐": "無餓不坐",
    "霧鹿古砲台": "霧鹿砲台",
    "藍蜻蜓速食店": "藍蜻蜓速食專賣店",
    "台糖池上牧野渡假村": "池上牧野渡假村",
    "台東糖廠": "台東糖廠文創園區",  # 正式名取較完整的寫法：visitDurationCap 看「園區」給 100 分鐘，
                                     # 只叫「台東糖廠」會退到一般景點的 45 分。CostReference 的門票
                                     # 覆蓋表已同步補上這個鍵（overrideKey 是精確比對）
    # ── 中英並列／重複贅字 ────────────────────────────────────
    "寶町藝文中心 Boting Art Center": "寶町藝文中心",
    "台東波浪屋-原民文創產業聚落 TTstyle Arts & Crafts-Wavy Roof Crafts": "TTstyle原創館-波浪屋",
    "火把岩 Jimavonot": "玉女岩(火把岩)",  # 玉女岩的達悟語名，同一塊礁岩
    "燈塔 燈塔 綠島燈塔": "綠島燈塔",
    "燈塔 綠島燈塔": "綠島燈塔",
    # ── 全名／簡稱 ──────────────────────────────────────────
    "國家人權博物館-白色恐怖綠島紀念園區": "白色恐怖綠島紀念園區",
    "綠島人權文化園區": "白色恐怖綠島紀念園區",
    "小野柳": "富岡地質公園 (小野柳)",
    "臺東鐵道藝術村 (鐵花新聚落)": "鐵花新聚落",
    "阿鋐炸雞": "阿鋐炸雞-正氣店",
    "只有海-午餐/輕食/晚餐": "只有海-午餐/晚餐/選物",
    # 四筆都指海濱公園裡的那座「向陽樹」地標，座標散在 22.7529–22.7531 之間。
    # 正式名刻意不選帶 address 的「台東海濱公園 (國際地標)」——那筆在 seeded 被誤標成
    # 餐廳，當正式名會讓地標整個變成用餐點（address 仍會由它補進來）。
    "台東海濱公園 (國際地標)": "台東國際地標",
    "台東海濱公園國際地標": "台東國際地標",
    "臺東海濱公園 (國際地標)": "台東國際地標",
    # ── 局部景物／園區本體 ────────────────────────────────────
    # 都是「園區與園區裡最有名的那個東西」，分開排會在同一個點停兩次。
    "東河舊橋": "東河橋遊憩區",          # 舊橋是遊憩區的主角，觀光署「東河橋」也已 mergeAs 到此
    "野銀部落地下屋": "野銀舊部落",       # 地下屋就是舊部落的主體
    "都歷遊客中心": "都歷園區",          # 同一個東管處處本部，地址都是新村路 25 號。
                                        # 正式名取園區：遊客中心是設施，園區才是目的地，
                                        # 而且 App 的遊客中心規則會依名稱判斷（Step 1c）
    "紅頭岩洞口": "紅頭岩",
    "紅頭岩(像水渠一樣)": "紅頭岩",      # 觀光署原始名帶了看不懂的括號註解，順手正名
    "小長城步道 & 睡美人與哈巴狗": "小長城",  # 睡美人與哈巴狗是從小長城觀景台看出去的礁岩
    "小長城觀景台": "小長城",
    # 「綠島小」名稱被截斷、描述抄的是小長城，但座標落在南寮（差 4 公里）。
    # 掛成別名而非留著：別名的欄位寫入走 setdefault，那組錯座標不會蓋掉小長城。
    "綠島小": "小長城",
    "牛頭山、樓門岩": "牛頭山",
    "山里車站與山里教堂": "山里火車站",   # 教堂就在車站旁，同一個地址
}
NAME_ALIASES = {norm_key(k): v for k, v in _NAME_ALIASES_RAW.items()}

# 正式名不可再是別名，否則要靠對應次數才知道最後落在哪一筆。
# 「紅頭岩(像水渠一樣)→紅頭岩」這種同 norm_key 的純改名不算連鎖。
for _alias_key, _canonical in NAME_ALIASES.items():
    _next = NAME_ALIASES.get(norm_key(_canonical))
    if _next is not None and norm_key(_next) != norm_key(_canonical):
        raise ValueError(f"NAME_ALIASES 出現連鎖對應：{_alias_key} → {_canonical} → {_next}")

# 已人工複核過「座標相同但確實是不同地點」的組合，不列入重複座標警告。
REVIEWED_SAME_COORD = {
    frozenset({"天龍古道", "天龍吊橋"}),
    frozenset({"崑慈堂", "鹿野崑慈堂及鹿野神社"}),
    # 蘭嶼真的有新舊兩座燈塔（觀光署「蘭嶼燈塔」簡介明講），不是名稱變體。
    # 但本地來源把新燈塔也抄成舊燈塔的座標，正確值應為 22.08142, 121.50409，
    # 要修得回頭改 seeded_pois.json / poi-data.js，不在合併腳本的職責內。
    frozenset({"舊蘭嶼燈塔", "蘭嶼燈塔"}),
}


def dining_type_of(name):
    """依名稱判斷餐飲型別，回傳「咖啡廳」／「餐廳」；看不出是餐飲則回傳 None。"""
    if not name:
        return None
    if any(k in name.lower() for k in CAFE_NAME_KEYWORDS):
        return "咖啡廳"
    if any(k in name for k in DINING_NAME_KEYWORDS):
        return "餐廳"
    return None


def retype_if_generic(rec, name):
    """型別還停在泛用「景點」且名稱看得出是餐飲時，改標成餐飲型別。"""
    if rec.get("typeName") in (None, "", GENERIC_TYPE):
        dining = dining_type_of(name)
        if dining:
            rec["typeName"] = dining


def is_placeholder(name):
    """這筆是不是佔位／範例資料（不是真的店家）。"""
    if any(m in name for m in PLACEHOLDER_MARKERS):
        return True
    return norm_key(name) in PLACEHOLDER_GENERIC_NAMES


def canonical_name(name):
    """名稱變體 → 正式名；不在對照表裡就是它自己。"""
    return NAME_ALIASES.get(norm_key(name), name)


def has_coords(rec):
    """有沒有可用座標。0,0 在幾內亞灣，等同沒有，且會被 CostReference 的最近鄰比對誤中。"""
    lat, lng = rec.get("lat"), rec.get("lng")
    return lat is not None and lng is not None and not (lat == 0 and lng == 0)


def check_duplicate_coords(pois):
    """找出座標完全相同、卻沒被 NAME_ALIASES 併起來的地點組（已複核者除外）。"""
    groups = {}
    for p in pois:
        groups.setdefault((round(p["lat"], 6), round(p["lng"], 6)), []).append(p["name"])
    return [(coord, names) for coord, names in groups.items()
            if len(names) > 1 and frozenset(names) not in REVIEWED_SAME_COORD]


def add_place(merged, name):
    """
    取得（或建立）合併後的地點記錄，回傳 (記錄, 是否為別名)。

    以「正式名的 norm_key」去重：名稱變體會併進同一筆，記錄一律採用正式名。
    是否為別名決定欄位寫入方式——見 put()——正式名那筆永遠壓過變體，
    合併結果才不會隨來源檔的排列順序改變。
    """
    canonical = canonical_name(name)
    key = norm_key(canonical)
    if key not in merged:
        merged[key] = {"name": canonical}
    return merged[key], name != canonical


def put(rec, field, value, is_alias):
    """寫入欄位：正式名那筆直接覆蓋，別名只補空缺，避免變體蓋掉正式資料。"""
    if is_alias:
        rec.setdefault(field, value)
    else:
        rec[field] = value


def main():
    merged = {}  # norm_key -> place dict

    # ── 1. 種子清單（提供 typeName / desc 基底）────────────────────────
    with open(SEEDED_PATH, encoding="utf-8") as f:
        seeded = json.load(f)
    for p in seeded.get("pois", []):
        rec, alias = add_place(merged, p["name"])
        put(rec, "lat", p["lat"], alias)
        put(rec, "lng", p["lng"], alias)
        put(rec, "typeName", p.get("typeName", GENERIC_TYPE), alias)
        put(rec, "businessHours", p.get("businessHours", ""), alias)
        # 種子清單是人工整理的，但餐飲一路都標成「景點」，名稱看得出來就改標
        retype_if_generic(rec, p["name"])
        for opt in ("desc", "address", "duration", "rating"):
            if p.get(opt) not in (None, ""):
                put(rec, opt, p[opt], alias)
    seeded_count = len(merged)

    # ── 2. 新版 POI（補 fee / feeNote / 廁所；缺的點也補進來）──────────
    poi_data = load_window_js(POI_JS_PATH)
    fee_dropped = []
    for region, arr in poi_data.items():
        if region.startswith("__"):
            continue
        for p in arr:
            rec, alias = add_place(merged, p["name"])
            rec.setdefault("typeName", GENERIC_TYPE)
            retype_if_generic(rec, p["name"])
            if p.get("lat") is not None:
                put(rec, "lat", p["lat"], alias)
            if p.get("lng") is not None:
                put(rec, "lng", p["lng"], alias)
            for opt in ("desc", "address", "businessHours", "rating", "duration",
                        "feeNote", "nearbyToiletLocations"):
                if p.get(opt) not in (None, ""):
                    put(rec, opt, p[opt], alias)
            if "fee" in p and p["fee"] is not None:
                if p["fee"] <= FEE_SANITY_MAX:
                    put(rec, "fee", p["fee"], alias)
                else:
                    fee_dropped.append((p["name"], p["fee"]))

    # ── 3. 餐廳（補 priceLevel / costPerPerson / costNote；缺的點也補進來）──
    rest_data = load_window_js(REST_JS_PATH)
    for region, arr in rest_data.items():
        if region.startswith("__"):
            continue
        for r in arr:
            rec, alias = add_place(merged, r["name"])
            # 收在餐廳清單裡就是餐飲，泛用「景點」一律讓位——舊版用 setdefault，
            # 前面的來源標過之後就永遠蓋不掉了。已細分過的（咖啡廳…）不動。
            if rec.get("typeName") in (None, "", GENERIC_TYPE):
                rec["typeName"] = dining_type_of(r["name"]) or "餐廳"
            if r.get("lat") is not None:
                put(rec, "lat", r["lat"], alias)
            if r.get("lng") is not None:
                put(rec, "lng", r["lng"], alias)
            for opt in ("address", "businessHours", "rating"):
                if r.get(opt) not in (None, ""):
                    put(rec, opt, r[opt], alias)
            for opt in ("priceLevel", "costMin", "costMax", "costPerPerson", "costNote"):
                if r.get(opt) is not None:
                    put(rec, opt, r[opt], alias)

    # ── 4. 觀光署景點（補官方簡介／官方照片；獨有的點也補進來）────────
    # 由 scripts/merge_tourism_data.py 產出。刻意放在最後一步且一律 setdefault，
    # 確保前三個來源既有的 duration / rating / businessHours 不會被覆蓋
    # （本地那份比觀光署完整，觀光署的營業時間全臺只有 77 筆有值）。
    tourism_new = 0
    tourism_filled = 0
    if os.path.exists(TOURISM_PATH):
        with open(TOURISM_PATH, encoding="utf-8") as f:
            tourism = json.load(f)
        for a in tourism.get("attractions", []):
            # mergeAs＝人工／自動判定為既有點的名稱變體，掛回既有記錄而非另開一筆
            target = a.get("mergeAs") or a["name"]
            is_new = norm_key(canonical_name(target)) not in merged
            rec, _alias = add_place(merged, target)
            if is_new:
                tourism_new += 1
                rec["lat"] = a["lat"]
                rec["lng"] = a["lng"]
                rec.setdefault("typeName", a.get("typeName", "景點"))
            if a.get("officialDesc"):
                rec.setdefault("officialDesc", a["officialDesc"])
                tourism_filled += 1
            if a.get("officialPhotoUrl"):
                rec.setdefault("officialPhotoUrl", a["officialPhotoUrl"])
            if a.get("address"):
                rec.setdefault("address", a["address"])
    else:
        print(f"ℹ️  找不到 {TOURISM_PATH}，略過觀光署資料"
              f"（需先執行 python scripts/merge_tourism_data.py）")

    # ── 輸出 ──────────────────────────────────────────────────────────
    pois = [v for v in merged.values() if has_coords(v)]
    no_coords = sorted(v["name"] for v in merged.values() if not has_coords(v))
    # 佔位／範例資料最後統一濾掉，不管它是從哪個來源進來的
    placeholders = [p for p in pois if is_placeholder(p["name"])]
    pois = [p for p in pois if not is_placeholder(p["name"])]
    pois.sort(key=lambda x: x["name"])

    out = {
        "generatedAt": datetime.now(timezone.utc).isoformat(),
        "count": len(pois),
        "source": ("merged: seeded_pois.json + poi-data.js + restaurant-data.js"
                   " + tourism-attractions.json（交通部觀光署 觀光資訊資料庫 V2.1）"),
        "pois": pois,
    }
    with open(OUT_PATH, "w", encoding="utf-8") as f:
        json.dump(out, f, ensure_ascii=False, indent=2)

    with_fee = sum(1 for p in pois if "fee" in p)
    with_meal = sum(1 for p in pois if "costPerPerson" in p)
    with_price = sum(1 for p in pois if "priceLevel" in p)
    type_counts = {}
    for p in pois:
        t = p.get("typeName", GENERIC_TYPE)
        type_counts[t] = type_counts.get(t, 0) + 1
    print(f"✅ 已輸出 {OUT_PATH}")
    print(f"   總點數：{len(pois)}（種子 {seeded_count} → 合併後 {len(merged)}）")
    print(f"   名稱變體合併：NAME_ALIASES {len(NAME_ALIASES)} 條")
    print("   型別分佈：" + "、".join(f"{t} {n}" for t, n in
                                   sorted(type_counts.items(), key=lambda kv: -kv[1])))
    if placeholders:
        print(f"   ⚠️ 已濾掉 {len(placeholders)} 筆佔位／範例資料：")
        for p in placeholders:
            print(f"      - {p['name']}（{p.get('lat')}, {p.get('lng')}）")
    if no_coords:
        print(f"   ⚠️ 已排除 {len(no_coords)} 筆沒有可用座標（缺值或 0,0）的地點：")
        for name in no_coords:
            print(f"      - {name}")
    print(f"   帶真實入場費 fee：{with_fee} 筆")
    print(f"   帶餐費 costPerPerson：{with_meal} 筆")
    print(f"   帶 priceLevel：{with_price} 筆")
    with_odesc = sum(1 for p in pois if "officialDesc" in p)
    print(f"   帶官方簡介 officialDesc：{with_odesc} 筆"
          f"（觀光署新增 {tourism_new} 個景點、補 {tourism_filled} 筆簡介）")
    if fee_dropped:
        print(f"   ⚠️ 已排除 {len(fee_dropped)} 筆逾 {FEE_SANITY_MAX} 元的 fee（疑似活動費，保留 feeNote）：")
        for name, fee in fee_dropped:
            print(f"      - {name}: {fee}")

    dup_coords = check_duplicate_coords(pois)
    if dup_coords:
        print(f"   ⚠️ {len(dup_coords)} 組地點座標完全相同，請確認是不是同一個地方："
              f"是就加進 NAME_ALIASES，不是就加進 REVIEWED_SAME_COORD：")
        for (lat, lng), names in dup_coords:
            print(f"      - ({lat}, {lng}) {names}")

    mismatched = check_island_mismatch(pois)
    if mismatched:
        print(f"   ⚠️ {len(mismatched)} 筆地址在本島、座標卻落在離島 bbox，請回頭修來源資料：")
        for name, island, lat, lng, address in mismatched:
            print(f"      - {name}: {lat}, {lng} 落在{island}，但地址是「{address}」")
    else:
        print("   座標／地址離島一致性檢查：通過")


if __name__ == "__main__":
    main()
