#!/usr/bin/env python3
"""
seed_poi_knowledge.py
自動填充 Firestore `poi_knowledge` 集合

流程：
  1. 讀取 places_to_seed.json（景點清單）
  2. 用 Google Places Text Search API 查詢每個景點的 placeId
  3. 用 Vertex AI（Gemini）生成 story / aiTip / tags 等知識內容
  4. 批次寫入 Firestore poi_knowledge（Document ID = placeId）

前置作業：
  pip install firebase-admin requests

  serviceAccount.json：
    Firebase Console → 專案設定 → 服務帳戶 → 產生新的私密金鑰
    存為 scripts/serviceAccount.json

用法：
  # 先 dry-run 預覽（不寫入 Firestore）
  python seed_poi_knowledge.py --dry-run

  # 正式執行
  python seed_poi_knowledge.py

  # 強制更新已存在的景點
  python seed_poi_knowledge.py --overwrite
"""

import json
import os
import time
import argparse
import requests
import firebase_admin
from firebase_admin import credentials, firestore

# ── 設定（從環境變數讀取，請勿將金鑰寫死於程式碼中）──────────────
MAPS_API_KEY     = os.environ["MAPS_API_KEY"]
VERTEX_API_KEY   = os.environ["VERTEX_API_KEY"]
VERTEX_PROJECT   = "project-720a680b-3ad1-40d1-b07"
VERTEX_LOCATION  = "us-central1"
VERTEX_MODEL     = "gemini-2.5-flash"

VERTEX_URL = (
    f"https://aiplatform.googleapis.com/v1/projects/{VERTEX_PROJECT}"
    f"/locations/{VERTEX_LOCATION}/publishers/google/models/{VERTEX_MODEL}:generateContent"
)

SERVICE_ACCOUNT  = os.path.join(os.path.dirname(__file__), "serviceAccount.json")
INPUT_FILE       = os.path.join(os.path.dirname(__file__), "places_to_seed.json")
COLLECTION       = "poi_knowledge"
DELAY_SECS       = 1.5   # 避免 API rate limit


# ── Firebase 初始化 ────────────────────────────────────────────────
def init_firebase():
    if not os.path.exists(SERVICE_ACCOUNT):
        raise FileNotFoundError(
            f"找不到 {SERVICE_ACCOUNT}\n"
            "請從 Firebase Console → 專案設定 → 服務帳戶 → 產生新的私密金鑰"
        )
    cred = credentials.Certificate(SERVICE_ACCOUNT)
    firebase_admin.initialize_app(cred)
    return firestore.client()


# ── Google Places API ──────────────────────────────────────────────
def lookup_place(name: str, region: str) -> dict | None:
    """用景點名稱 + 地區查詢 Google Places placeId 和基本資料"""
    resp = requests.get(
        "https://maps.googleapis.com/maps/api/place/textsearch/json",
        params={
            "query":    f"{name} {region} 台東縣 台灣",
            "language": "zh-TW",
            "key":      MAPS_API_KEY,
        },
        timeout=10
    )
    resp.raise_for_status()
    results = resp.json().get("results", [])
    if not results:
        return None
    r = results[0]
    return {
        "placeId":    r["place_id"],
        "name":       r["name"],
        "priceLevel": r.get("price_level", -1),
    }


# ── Vertex AI（Gemini）知識生成 ────────────────────────────────────
def generate_knowledge(name: str, region: str, category: str) -> dict:
    """
    呼叫 Vertex AI Gemini 生成景點的豐富描述。
    使用與 App 相同的 endpoint 和 x-goog-api-key header。
    """
    prompt = f"""你是台東旅遊達人，熟悉在地文化與原住民歷史。
請為「{name}」（位於台東{region}，類型：{category}）生成旅遊知識。
用繁體中文，直接回傳 JSON（不要 Markdown code block），所有欄位都必須填寫：

{{
  "story":        "景點故事或歷史背景（2–3句，50–100字）",
  "culturalNote": "原住民或在地文化連結（若無則填空字串）",
  "aiTip":        "給行程規劃 AI 的提示（最佳時段、注意事項、行程建議，30–60字）",
  "shortDesc":    "探索頁一句話描述（20字以內）",
  "highlights":   ["亮點特色1（10字內）", "亮點特色2", "亮點特色3"],
  "tags":         ["標籤1", "標籤2", "標籤3", "標籤4", "標籤5"],
  "travelStyles": ["旅遊風格1"],
  "bestTime":     "清晨"
}}

travelStyles 只能從以下選擇（可多選）：自然生態 / 文化探索 / 美食 / 冒險 / 親子 / 攝影 / 慢旅行
bestTime 只能填：清晨 / 上午 / 下午 / 黃昏 / 晚上 / 全天"""

    resp = requests.post(
        VERTEX_URL,
        headers={
            "x-goog-api-key": VERTEX_API_KEY,
            "Content-Type":   "application/json",
        },
        json={
            "contents": [{"role": "user", "parts": [{"text": prompt}]}],
            "generationConfig": {
                "temperature":      0.4,
                "responseMimeType": "application/json",
            },
        },
        timeout=30
    )
    resp.raise_for_status()

    raw = resp.json()["candidates"][0]["content"]["parts"][0]["text"]
    # 清除可能殘留的 Markdown fence
    text = raw.strip()
    if text.startswith("```"):
        lines = text.splitlines()
        text = "\n".join(lines[1:-1] if lines[-1].strip() == "```" else lines[1:])
    return json.loads(text.strip())


# ── 主流程 ────────────────────────────────────────────────────────
def main():
    parser = argparse.ArgumentParser(description="Seed Firestore poi_knowledge collection")
    parser.add_argument("--dry-run",   action="store_true",
                        help="只預覽生成內容，不實際寫入 Firestore")
    parser.add_argument("--overwrite", action="store_true",
                        help="強制覆蓋已有資料（預設跳過已存在的景點）")
    args = parser.parse_args()

    # 載入景點清單
    with open(INPUT_FILE, encoding="utf-8") as f:
        places = json.load(f)
    print(f"📋 共 {len(places)} 個景點待處理\n")

    # 初始化 Firebase（dry-run 跳過）
    db = None
    if not args.dry_run:
        print("🔥 初始化 Firebase...")
        db = init_firebase()

    success = skipped = failed = 0

    for idx, entry in enumerate(places, 1):
        name         = entry["name"]
        region       = entry.get("region", "台東")
        category     = entry.get("category", "景點")
        visit_mins   = entry.get("visitDurationMins", 60)
        manual_price = entry.get("priceLevel", -1)

        print(f"[{idx:02d}/{len(places)}] ── 「{name}」（{region} / {category}）")

        # 1. 查詢 placeId
        try:
            place_info = lookup_place(name, region)
        except Exception as e:
            print(f"  ❌ Places API 失敗：{e}")
            failed += 1
            continue

        if not place_info:
            print(f"  ⚠️  Google Places 找不到，跳過")
            skipped += 1
            continue

        place_id = place_info["placeId"]
        print(f"  🔍 placeId：{place_id}")

        # 2. 若已存在且不強制覆蓋 → 跳過
        if not args.overwrite and not args.dry_run:
            if db.collection(COLLECTION).document(place_id).get().exists:
                print(f"  ✅ 已存在，跳過（加上 --overwrite 可強制更新）")
                skipped += 1
                continue

        # 3. Vertex AI 生成知識內容
        try:
            time.sleep(DELAY_SECS)
            knowledge = generate_knowledge(name, region, category)
        except Exception as e:
            print(f"  ❌ Vertex AI 生成失敗：{e}")
            failed += 1
            continue

        # 4. 組合文件
        google_price = place_info.get("priceLevel", -1)
        doc = {
            "name":             place_info["name"],
            "placeId":          place_id,
            "region":           region,
            "story":            knowledge.get("story", ""),
            "culturalNote":     knowledge.get("culturalNote", ""),
            "aiTip":            knowledge.get("aiTip", ""),
            "visitDurationMins":visit_mins,
            "tags":             knowledge.get("tags", []),
            "travelStyles":     knowledge.get("travelStyles", []),
            "bestTime":         knowledge.get("bestTime", "全天"),
            "priceLevel":       google_price if google_price != -1 else manual_price,
            "coverImageUrl":    "",
            "shortDesc":        knowledge.get("shortDesc", ""),
            "highlights":       knowledge.get("highlights", []),
            "updatedBy":        "seed_script",
        }

        # 5. 預覽 / 寫入
        if args.dry_run:
            print(f"  [DRY-RUN]")
            print(f"    shortDesc  : {doc['shortDesc']}")
            print(f"    story      : {doc['story'][:60]}…")
            print(f"    aiTip      : {doc['aiTip'][:60]}…")
            print(f"    tags       : {doc['tags']}")
            print(f"    travelStyles: {doc['travelStyles']}")
        else:
            doc["updatedAt"] = firestore.SERVER_TIMESTAMP
            db.collection(COLLECTION).document(place_id).set(doc)
            print(f"  ✅ 寫入成功")
            print(f"    shortDesc : {doc['shortDesc']}")
            print(f"    story     : {doc['story'][:50]}…")

        success += 1
        time.sleep(DELAY_SECS)

    print(f"\n{'═' * 50}")
    mode = "[DRY-RUN] " if args.dry_run else ""
    print(f"{mode}完成  成功：{success}  跳過：{skipped}  失敗：{failed}")


if __name__ == "__main__":
    main()
