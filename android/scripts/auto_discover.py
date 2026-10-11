#!/usr/bin/env python3
"""
auto_discover.py
從 Firestore test_trips 集合自動找出還沒有 poi_knowledge 的景點，
呼叫 Vertex AI 生成知識內容後寫入 poi_knowledge。

用法：
  # 預覽哪些景點缺少知識庫資料
  python auto_discover.py --dry-run

  # 正式執行
  python auto_discover.py
"""

import sys
import json
import os
import time
import argparse
import requests
import firebase_admin
from firebase_admin import credentials, firestore

# Windows cp950 終端不支援 emoji，強制 UTF-8 輸出
if sys.stdout.encoding and sys.stdout.encoding.lower() != "utf-8":
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    sys.stderr.reconfigure(encoding="utf-8", errors="replace")

# ── 設定（從環境變數讀取，請勿將金鑰寫死於程式碼中）──────────────
MAPS_API_KEY   = os.environ["MAPS_API_KEY"]
VERTEX_API_KEY = os.environ["VERTEX_API_KEY"]
VERTEX_PROJECT = "project-720a680b-3ad1-40d1-b07"
VERTEX_LOCATION= "us-central1"
VERTEX_MODEL   = "gemini-2.5-flash"
VERTEX_URL     = (
    f"https://aiplatform.googleapis.com/v1/projects/{VERTEX_PROJECT}"
    f"/locations/{VERTEX_LOCATION}/publishers/google/models/{VERTEX_MODEL}:generateContent"
)
SERVICE_ACCOUNT= os.path.join(os.path.dirname(__file__), "serviceAccount.json")
TRIPS_COL      = "test_trips"
KNOWLEDGE_COL  = "poi_knowledge"
DELAY_SECS     = 1.5

# 跳過的景點類型（車站、廁所等不需要知識庫）
SKIP_TYPES = {"車站", "公車站", "捷運站", "火車站"}
SKIP_NAMES_KEYWORDS = ["車站", "捷運", "公車", "廁所", "停車場", "加油站"]


# ── Firebase 初始化 ────────────────────────────────────────────────
def init_firebase():
    cred = credentials.Certificate(SERVICE_ACCOUNT)
    firebase_admin.initialize_app(cred)
    return firestore.client()


# ── 從 test_trips 收集所有景點 ──────────────────────────────────────
def collect_stops_from_trips(db) -> list[dict]:
    """
    讀取所有行程，收集有 placeId 的景點（去重），
    回傳 list of {name, placeId, region, isStation}
    """
    print("📖 讀取 test_trips 集合...")
    trips = db.collection(TRIPS_COL).stream()

    seen_place_ids = set()
    stops = []

    for trip in trips:
        data = trip.to_dict()
        region = data.get("region", "台東")
        for stop in data.get("stops", []):
            place_id = stop.get("placeId", "").strip()
            name     = stop.get("name", "").strip()
            is_station = stop.get("isStation", False)

            # 跳過：無 placeId、已收集過、車站、關鍵字過濾
            if not place_id or place_id in seen_place_ids:
                continue
            if is_station:
                continue
            if any(kw in name for kw in SKIP_NAMES_KEYWORDS):
                continue

            seen_place_ids.add(place_id)
            stops.append({
                "name":    name,
                "placeId": place_id,
                "region":  region,
            })

    print(f"  → 共收集到 {len(stops)} 個不重複景點（含 placeId）")
    return stops


# ── 找出缺少知識庫的景點 ───────────────────────────────────────────
def find_missing(db, stops: list[dict]) -> list[dict]:
    """
    批次查詢 poi_knowledge，回傳還沒有文件的景點清單
    """
    if not stops:
        return []

    print("🔍 比對 poi_knowledge 現有資料...")
    existing_ids = set()
    place_ids = [s["placeId"] for s in stops]

    # Firestore whereIn 每批上限 30
    for i in range(0, len(place_ids), 30):
        batch = place_ids[i:i+30]
        snaps = db.collection(KNOWLEDGE_COL) \
                  .where("__name__", "in", batch) \
                  .stream()
        for doc in snaps:
            existing_ids.add(doc.id)

    missing = [s for s in stops if s["placeId"] not in existing_ids]
    print(f"  → 缺少知識庫資料：{len(missing)} 個景點")
    return missing


# ── 推斷景點類型 ────────────────────────────────────────────────────
def infer_category(name: str) -> str:
    if any(kw in name for kw in ["海灘", "沙灘", "海岸", "灣"]):
        return "自然景觀"
    if any(kw in name for kw in ["部落", "文物館", "文化館", "博物館", "美術館", "糖廠"]):
        return "文化體驗"
    if any(kw in name for kw in ["公園", "步道", "森林", "遊憩區", "休息區"]):
        return "公園步道"
    if any(kw in name for kw in ["廟", "宮", "天后", "教堂"]):
        return "宗教場所"
    if any(kw in name for kw in ["餐廳", "小吃", "食堂", "海產", "麵", "咖啡", "飲食"]):
        return "餐廳"
    if any(kw in name for kw in ["湖", "橋", "陸連島", "岩", "礁", "台", "嗡嗡"]):
        return "自然景觀"
    return "景點"


# ── Vertex AI 生成知識內容 ─────────────────────────────────────────
def generate_knowledge(name: str, region: str, category: str) -> dict:
    prompt = f"""你是台東旅遊達人，熟悉在地文化與原住民歷史。
請為「{name}」（位於台東{region}，類型：{category}）生成旅遊知識。
用繁體中文，直接回傳 JSON（不要 Markdown code block）：

{{
  "story":        "景點故事或歷史背景（2–3句，50–100字）",
  "culturalNote": "原住民或在地文化連結（若無則填空字串）",
  "aiTip":        "給行程規劃 AI 的提示（最佳時段、注意事項，30–60字）",
  "shortDesc":    "探索頁一句話描述（20字以內）",
  "highlights":   ["亮點1（10字內）", "亮點2", "亮點3"],
  "tags":         ["標籤1", "標籤2", "標籤3", "標籤4", "標籤5"],
  "travelStyles": ["旅遊風格1"],
  "bestTime":     "全天"
}}

travelStyles 只能從：自然生態 / 文化探索 / 美食 / 冒險 / 親子 / 攝影 / 慢旅行
bestTime 只能填：清晨 / 上午 / 下午 / 黃昏 / 晚上 / 全天"""

    resp = requests.post(
        VERTEX_URL,
        headers={"x-goog-api-key": VERTEX_API_KEY, "Content-Type": "application/json"},
        json={
            "contents": [{"role": "user", "parts": [{"text": prompt}]}],
            "generationConfig": {"temperature": 0.4, "responseMimeType": "application/json"},
        },
        timeout=30
    )
    resp.raise_for_status()

    raw = resp.json()["candidates"][0]["content"]["parts"][0]["text"].strip()
    if raw.startswith("```"):
        lines = raw.splitlines()
        raw = "\n".join(lines[1:-1] if lines[-1].strip() == "```" else lines[1:])
    return json.loads(raw.strip())


# ── 主流程 ────────────────────────────────────────────────────────
def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--dry-run", action="store_true",
                        help="只顯示缺少的景點，不寫入 Firestore")
    args = parser.parse_args()

    print("🔥 初始化 Firebase...")
    db = init_firebase()

    # 1. 收集所有行程景點
    all_stops = collect_stops_from_trips(db)
    if not all_stops:
        print("❌ test_trips 裡沒有景點資料")
        return

    # 2. 找出缺少知識庫的景點
    missing = find_missing(db, all_stops)
    if not missing:
        print("\n✅ 所有景點都已有知識庫資料，無需更新")
        return

    print(f"\n待補齊景點：")
    for s in missing:
        print(f"  • {s['name']} ({s['region']}) — {s['placeId']}")

    if args.dry_run:
        print(f"\n[DRY-RUN] 共 {len(missing)} 個景點待補齊，加上 --dry-run 以外的參數執行來寫入")
        return

    # 3. 逐一生成並寫入
    print(f"\n🤖 開始為 {len(missing)} 個景點生成知識內容...\n")
    success = failed = 0

    for idx, stop in enumerate(missing, 1):
        name     = stop["name"]
        region   = stop["region"]
        place_id = stop["placeId"]
        category = infer_category(name)

        print(f"[{idx:02d}/{len(missing)}] 「{name}」（{region} / {category}）")

        try:
            time.sleep(DELAY_SECS)
            knowledge = generate_knowledge(name, region, category)
        except Exception as e:
            print(f"  ❌ 生成失敗：{e}")
            failed += 1
            continue

        doc = {
            "name":             name,
            "placeId":          place_id,
            "region":           region,
            "story":            knowledge.get("story", ""),
            "culturalNote":     knowledge.get("culturalNote", ""),
            "aiTip":            knowledge.get("aiTip", ""),
            "visitDurationMins":60,
            "tags":             knowledge.get("tags", []),
            "travelStyles":     knowledge.get("travelStyles", []),
            "bestTime":         knowledge.get("bestTime", "全天"),
            "priceLevel":       -1,
            "coverImageUrl":    "",
            "shortDesc":        knowledge.get("shortDesc", ""),
            "highlights":       knowledge.get("highlights", []),
            "updatedAt":        firestore.SERVER_TIMESTAMP,
            "updatedBy":        "auto_discover",
        }
        db.collection(KNOWLEDGE_COL).document(place_id).set(doc)
        print(f"  ✅ 已寫入｜{doc['shortDesc']}")
        success += 1
        time.sleep(DELAY_SECS)

    print(f"\n{'═'*50}")
    print(f"完成  成功：{success}  失敗：{failed}")


if __name__ == "__main__":
    main()
