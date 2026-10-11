#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
merge_tourism_data.py

把交通部觀光署「觀光資訊資料庫 V2.1」的臺東縣景點，正規化成
scripts/data/tourism-attractions.json，供 build_local_places.py 當第四個資料源。

為什麼用離線批次而非 App 端串 API：
  • 觀光署景點簡介屬於月更新等級的靜態資料，不需要執行期即時查
  • App 端零改動、零網路失敗風險，且與 E1 離線地圖快取相合
  • 資料在這裡就檢查完，跑壞了不 commit 即可

資料源（免驗證公開檔，2026-08 實測可用）：
  https://media.taiwan.net.tw/XMLReleaseAll_public/v2.0/Zh_tw/Attraction-json.zip
  註：V1.0 舊檔（scenic_spot_C_f.json）已於 2026/6/30 下架，勿再使用。

合併規則（與 build_local_places.py 的 norm_key 完全一致）：
  • 已存在的點 → 只補 officialDesc / officialPhotoUrl，其餘欄位一律不覆蓋
    （本地的 duration / rating / businessHours 比觀光署完整，不可被蓋掉）
  • 不存在的點 → 整筆新增，typeName 一律「景點」
  • 無座標者略過（build_local_places.py 會丟掉沒有 lat/lng 的記錄）

名稱變體去重（光靠 norm_key 會漏）：
  觀光署與本地對同一地點常有不同寫法（「利吉惡地及小黃山」vs「利吉惡地」、
  「水往上流奇觀」vs「水往上流」）。若兩者座標在 200m 內 **且** 一方的 norm_key
  是另一方的子字串，視為同一點，改成補欄位而非新增，並在輸出裡帶 mergeAs 欄位
  告訴 build_local_places.py 要掛到哪個既有名稱下。
  只靠距離會誤判（成功漁港與三仙台豆花相距 147m 但顯然不同），故兩條件缺一不可。

用法：
    python scripts/merge_tourism_data.py --dry-run   # 只預覽，不寫檔
    python scripts/merge_tourism_data.py             # 寫入 tourism-attractions.json

資料使用需標示來源：交通部觀光署觀光資訊資料庫（政府資料開放授權條款）。
"""
import argparse
import io
import json
import math
import os
import re
import sys
import urllib.request
import zipfile
from datetime import datetime, timezone

# Windows 主控台預設 cp950 無法輸出 emoji，統一改用 UTF-8。
try:
    sys.stdout.reconfigure(encoding="utf-8")
except (AttributeError, ValueError):
    pass

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
LOCAL_PLACES_PATH = os.path.join(ROOT, "app", "src", "main", "assets", "local_places.json")
OUT_PATH = os.path.join(ROOT, "scripts", "data", "tourism-attractions.json")

ZIP_URL = "https://media.taiwan.net.tw/XMLReleaseAll_public/v2.0/Zh_tw/Attraction-json.zip"

# 簡介截斷長度。臺東 287 筆平均 161 字、最長 991 字；截 300 字可涵蓋絕大多數
# 完整敘述，又不會讓 assets 體積失控。
DESC_MAX = 300

# 名稱變體判定的距離上限。實測 11–148m 都出現過真變體（利吉惡地 11m、紫坪 148m），
# 200m 內再加上「名稱包含關係」這個條件已足以排除鄰近但不同的地點。
VARIANT_MAX_M = 200

# 人工確認過的同一地點對照（觀光署名稱 → local_places 既有名稱）。
# 座標近但名稱不相含的情況自動判定風險太高，一律列進報告由人決定後寫在這裡。
MANUAL_MERGE = {
    # 成功漁港的正式名稱即新港漁港，與本地既有記錄為同一個港（2026-08 人工確認）
    "成功漁港": "成功新港漁港",
}


def norm_key(name):
    """比對用正規化鍵。必須與 build_local_places.py / CostReference.kt 完全一致。"""
    if not name:
        return ""
    cut = re.split(r"[（(]", name, maxsplit=1)[0]
    return re.sub(r"\s+", "", cut).lower()


def haversine_m(lat1, lng1, lat2, lng2):
    """兩點直線距離（公尺）。"""
    r = 6371000.0
    p1, p2 = math.radians(lat1), math.radians(lat2)
    dp = math.radians(lat2 - lat1)
    dl = math.radians(lng2 - lng1)
    a = math.sin(dp / 2) ** 2 + math.cos(p1) * math.cos(p2) * math.sin(dl / 2) ** 2
    return 2 * r * math.asin(math.sqrt(a))


def find_variant_match(record, local_pois):
    """
    找出同一地點的名稱變體。需同時滿足：座標 200m 內、且 norm_key 有包含關係。
    回傳既有點的原始名稱，找不到回 None。
    """
    key = norm_key(record["name"])
    best, best_dist = None, VARIANT_MAX_M
    for p in local_pois:
        if p.get("lat") is None or p.get("lng") is None:
            continue
        pkey = norm_key(p["name"])
        if not (pkey in key or key in pkey):
            continue
        d = haversine_m(record["lat"], record["lng"], p["lat"], p["lng"])
        if d < best_dist:
            best, best_dist = p["name"], d
    return best


def fetch_attractions():
    """下載並解出全台景點清單（不落地 zip，避免污染 repo）。"""
    print(f"⬇️  下載 {ZIP_URL}")
    with urllib.request.urlopen(ZIP_URL, timeout=180) as resp:
        blob = resp.read()
    print(f"   取得 {len(blob) // 1024} KB")
    z = zipfile.ZipFile(io.BytesIO(blob))
    raw = z.read("AttractionList.json").decode("utf-8-sig")
    return json.loads(raw)["Attractions"]


def to_record(a):
    """觀光署一筆 → 本專案格式；缺座標回 None。"""
    lat, lng = a.get("PositionLat"), a.get("PositionLon")
    if lat is None or lng is None:
        return None

    addr = a.get("PostalAddress") or {}
    images = a.get("Images") or []
    photo = ""
    for img in images:
        if img.get("URL"):
            photo = img["URL"]
            break

    desc = (a.get("Description") or "").strip()
    # 觀光署簡介常有連續空白與換行，壓成單行以免 assets 塞一堆空字元
    desc = re.sub(r"\s+", " ", desc)[:DESC_MAX]

    return {
        "name": a.get("AttractionName", "").strip(),
        "lat": lat,
        "lng": lng,
        "typeName": "景點",
        "address": "".join([
            addr.get("City", ""), addr.get("Town", ""), addr.get("StreetAddress", "")
        ]),
        "town": addr.get("Town", ""),
        "officialDesc": desc,
        "officialPhotoUrl": photo,
    }


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--dry-run", action="store_true", help="只預覽合併結果，不寫任何檔案")
    args = ap.parse_args()

    attractions = fetch_attractions()
    print(f"   全台景點 {len(attractions)} 筆")

    # ── 篩臺東縣 ─────────────────────────────────────────────────────
    taitung = [
        a for a in attractions
        if str((a.get("PostalAddress") or {}).get("City", "")).startswith("臺東")
    ]
    records = [r for r in (to_record(a) for a in taitung) if r and r["name"]]
    skipped_no_coord = len(taitung) - len(records)
    print(f"   臺東縣 {len(taitung)} 筆 → 可用 {len(records)} 筆"
          f"（略過無座標 {skipped_no_coord} 筆）")

    # ── 比對現有 local_places.json ───────────────────────────────────
    with open(LOCAL_PLACES_PATH, encoding="utf-8") as f:
        local = json.load(f)
    existing = {norm_key(p["name"]): p for p in local.get("pois", [])}

    local_pois = local.get("pois", [])

    overlap, brand_new, variants = [], [], []
    for r in records:
        if norm_key(r["name"]) in existing:
            overlap.append(r)
            continue
        # norm_key 對不上時，先看人工對照表，再試名稱變體（座標近 + 名稱包含）
        match = MANUAL_MERGE.get(r["name"]) or find_variant_match(r, local_pois)
        if match:
            r["mergeAs"] = match
            variants.append(r)
        else:
            brand_new.append(r)

    # 已存在的點裡，有多少會真的補到東西（原本沒有簡介／照片的）
    fills_desc = sum(1 for r in overlap if r["officialDesc"])
    fills_photo = sum(1 for r in overlap if r["officialPhotoUrl"])

    print()
    print("── 合併預覽 ─────────────────────────────────────────────")
    print(f"現有 local_places.json：{len(local_pois)} 筆")
    print(f"與觀光署重疊：{len(overlap)} 筆 → 補官方簡介 {fills_desc}、官方照片 {fills_photo}")
    print(f"名稱變體（併入既有點，不新增）：{len(variants)} 筆")
    for r in variants:
        print(f"     「{r['name']}」→ 併入「{r['mergeAs']}」")
    print(f"觀光署獨有（會新增）：{len(brand_new)} 筆")

    islands = [r for r in records if r["town"] in ("綠島鄉", "蘭嶼鄉")]
    islands_new = [r for r in brand_new if r["town"] in ("綠島鄉", "蘭嶼鄉")]
    print(f"其中離島（綠島／蘭嶼）：共 {len(islands)} 筆，新增 {len(islands_new)} 筆 → 可餵 A5")

    added_bytes = len(json.dumps(
        [{"officialDesc": r["officialDesc"], "officialPhotoUrl": r["officialPhotoUrl"]}
         for r in records], ensure_ascii=False).encode("utf-8"))
    cur_kb = os.path.getsize(LOCAL_PLACES_PATH) // 1024
    print(f"預估 local_places.json：{cur_kb} KB → 約 {cur_kb + added_bytes // 1024} KB")

    # 新增的點裡，座標很近但名稱無包含關係者 → 可能是同一地點的不同稱呼
    # （例：觀光署「成功漁港」vs 本地「成功新港漁港」）。自動判定風險太高，
    # 改列出來人工確認；確認為同一點者手動加進 MANUAL_MERGE 即可。
    suspects = []
    for r in brand_new:
        for p in local_pois:
            if p.get("lat") is None or p.get("lng") is None:
                continue
            d = haversine_m(r["lat"], r["lng"], p["lat"], p["lng"])
            if d < VARIANT_MAX_M:
                suspects.append((r["name"], p["name"], int(d)))
                break
    if suspects:
        print()
        print(f"⚠️  待人工確認 {len(suspects)} 筆（座標 {VARIANT_MAX_M}m 內但名稱不相含）：")
        for a, b, d in sorted(suspects, key=lambda x: x[2]):
            print(f"     「{a}」 vs 既有「{b}」 相距 {d}m")
        print("     確認為同一地點者，把觀光署名稱加進 MANUAL_MERGE 後重跑")

    print()
    print("新增景點範例（前 12 筆）：")
    for r in brand_new[:12]:
        print(f"  + {r['name']}（{r['town']}）")

    print()
    print("補簡介範例（前 3 筆）：")
    for r in overlap[:3]:
        if r["officialDesc"]:
            print(f"  ~ {r['name']}：{r['officialDesc'][:60]}…")

    # 沒有簡介或照片的比例，用來判斷這批資料值不值得
    no_desc = sum(1 for r in records if not r["officialDesc"])
    no_photo = sum(1 for r in records if not r["officialPhotoUrl"])
    print()
    print(f"品質檢查：無簡介 {no_desc} 筆、無照片 {no_photo} 筆（共 {len(records)} 筆）")

    if args.dry_run:
        print()
        print("🔍 dry-run，未寫入任何檔案")
        return

    out = {
        "generatedAt": datetime.now(timezone.utc).isoformat(),
        "source": "交通部觀光署 觀光資訊資料庫 V2.1（Attraction-json.zip）",
        "license": "政府資料開放授權條款第 1 版，加值利用須標示來源",
        "count": len(records),
        "attractions": records,
    }
    os.makedirs(os.path.dirname(OUT_PATH), exist_ok=True)
    with open(OUT_PATH, "w", encoding="utf-8") as f:
        json.dump(out, f, ensure_ascii=False, indent=2)
    print()
    print(f"✅ 已輸出 {OUT_PATH}（{len(records)} 筆）")
    print("   接著執行 python scripts/build_local_places.py 才會併進 assets")


if __name__ == "__main__":
    main()
