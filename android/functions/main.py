import base64
import time
from firebase_functions import https_fn, firestore_fn
from firebase_functions.options import set_global_options, MemoryOption
from firebase_admin import initialize_app

# 注意：vertexai / aiplatform（~270MB）冷 import 約 9.8s，若放在模組頂層，
# Firebase 部署的「探索後端規格」步驟（10s 逾時）會 timeout 導致部署失敗。
# 因此改為在各 function 內部 lazy import，讓模組載入瞬間完成、探索不逾時；
# 執行時僅第一次呼叫該 function 時才載入（每個實例只發生一次）。

initialize_app()
# google-cloud-aiplatform 載入時記憶體用量約 270MB+，預設 256MiB 不夠，會被 OOM kill
set_global_options(max_instances=10, memory=MemoryOption.MB_512)

PROJECT_ID = "project-720a680b-3ad1-40d1-b07"
LOCATION = "us-central1"  # gemini-2.5-flash 與 gemini-3.1-flash-image 皆在此 region 可用

# generate_content 代理僅允許這些模型，避免被當成任意 LLM 代理濫用
# 註：gemini-3.1-flash-image 為正式版；曾用的 gemini-3.1-flash-image-preview 已失效（404）。
ALLOWED_MODELS = {"gemini-2.5-flash", "gemini-3.1-flash-image"}
MAX_PROMPT_LENGTH = 20000

# 生成耗時量測：分出「冷啟動（新實例＋首次 import vertexai）」與「模型本身」各花多少時間
_INSTANCE_STARTED_AT = time.time()
_calls_on_instance = 0


@https_fn.on_call(region="us-central1", timeout_sec=180)
def generate_content(req: https_fn.CallableRequest) -> dict:
    """Android App 端所有 Gemini 呼叫的代理入口，避免在用戶端攜帶 Vertex AI 金鑰。"""
    global _calls_on_instance
    _calls_on_instance += 1
    t_start = time.perf_counter()
    if req.auth is None:
        raise https_fn.HttpsError("unauthenticated", "需要登入才能使用 AI 功能")

    data = req.data

    # 預熱：App 打開建立精靈時先呼叫一次，只載入 vertexai、不呼叫模型。
    # 實測冷啟動光 import 就 14.2s（模型本身約 7s）；使用者填精靈的時間足夠讓實例熱好。
    if data.get("warmup"):
        t_import = time.perf_counter()
        import vertexai
        from vertexai.generative_models import GenerativeModel  # noqa: F401（重的是這個子模組）
        vertexai.init(project=PROJECT_ID, location=LOCATION)
        import_ms = int((time.perf_counter() - t_import) * 1000)
        print(f"[timing] warmup importMs={import_ms} coldStart={_calls_on_instance == 1}")
        return {"warm": True, "timing": {"importMs": import_ms, "coldStart": _calls_on_instance == 1}}

    prompt = data.get("prompt", "")
    model_name = data.get("model", "gemini-2.5-flash")
    if model_name not in ALLOWED_MODELS:
        raise https_fn.HttpsError("invalid-argument", f"不支援的模型: {model_name}")
    if not prompt or len(prompt) > MAX_PROMPT_LENGTH:
        raise https_fn.HttpsError("invalid-argument", "prompt 為空或過長")

    # 圖片生成改走 REST v1：舊版 vertexai SDK（requirements 釘 >=1.70.0）呼叫
    # gemini-3.1-flash-image 會回 404「model not found」，但 REST v1 端點正常出圖。
    # 文字生成維持走 SDK（已驗證正常）。
    if "IMAGE" in (data.get("responseModalities") or []):
        return _generate_image_via_rest(model_name, prompt, data)

    t_import = time.perf_counter()
    import vertexai
    from vertexai.generative_models import GenerativeModel, GenerationConfig

    vertexai.init(project=PROJECT_ID, location=LOCATION)
    model = GenerativeModel(model_name)
    import_ms = int((time.perf_counter() - t_import) * 1000)

    response_modalities = data.get("responseModalities")
    response_mime_type = data.get("responseMimeType")
    # thinkingBudget：0＝關閉思考（Gemini 2.5 Flash 允許），None＝維持模型預設動態思考。
    # 舊版 vertexai GenerationConfig wrapper 不支援 thinking_config，改用 dict 形式
    # 讓 model.generate_content 直接展開成 raw gapic proto（其 GenerationConfig 有 thinking_config 欄位）。
    # imageConfig（如 {"aspectRatio": "9:16"}）：圖片生成用，透傳到 raw proto 的 image_config。
    image_config = data.get("imageConfig")
    thinking_budget = data.get("thinkingBudget")
    if thinking_budget is not None or image_config is not None:
        gen_config = {"temperature": data.get("temperature", 0.4)}
        if response_mime_type:
            gen_config["response_mime_type"] = response_mime_type
        if response_modalities:
            gen_config["response_modalities"] = response_modalities
        if thinking_budget is not None:
            gen_config["thinking_config"] = {"thinking_budget": int(thinking_budget)}
        if image_config is not None:
            gen_config["image_config"] = {"aspect_ratio": image_config.get("aspectRatio", "1:1")}
        max_output_tokens = data.get("maxOutputTokens")
        if max_output_tokens is not None:
            gen_config["max_output_tokens"] = int(max_output_tokens)
    else:
        gen_config = GenerationConfig(
            temperature=data.get("temperature", 0.4),
            response_mime_type=response_mime_type if response_mime_type else None,
            response_modalities=response_modalities if response_modalities else None,
        )

    t_model = time.perf_counter()
    try:
        response = model.generate_content(prompt, generation_config=gen_config)
    except Exception as e:
        raise https_fn.HttpsError("internal", f"AI 生成失敗: {e}")
    model_ms = int((time.perf_counter() - t_model) * 1000)
    timing = {
        "serverMs": int((time.perf_counter() - t_start) * 1000),
        "importMs": import_ms,          # 首次呼叫含 vertexai 冷 import（約 10s），之後接近 0
        "modelMs": model_ms,            # Vertex AI 模型本身
        "coldStart": _calls_on_instance == 1,
        "instanceAgeS": int(time.time() - _INSTANCE_STARTED_AT),
    }
    print(f"[timing] generate_content {model_name} {timing}")

    if not response.candidates:
        raise https_fn.HttpsError("internal", "AI 回應為空，可能被安全過濾器攔截")

    # 用量資料（供開發階段估算每次生成成本；release 端不一定使用，回傳無害）
    um = getattr(response, "usage_metadata", None)
    usage = {
        "promptTokens": int(getattr(um, "prompt_token_count", 0) or 0),
        "outputTokens": int(getattr(um, "candidates_token_count", 0) or 0),
        "thoughtsTokens": int(getattr(um, "thoughts_token_count", 0) or 0),
    } if um is not None else None

    for part in response.candidates[0].content.parts:
        inline_data = getattr(part, "_raw_part", None)
        if inline_data is not None and inline_data.inline_data.data:
            return {
                "imageBase64": base64.b64encode(inline_data.inline_data.data).decode("utf-8"),
                "usage": usage,
                "timing": timing,
            }

    return {"result": response.text, "usage": usage, "timing": timing}


def _generate_image_via_rest(model_name: str, prompt: str, data: dict) -> dict:
    """以 REST v1 呼叫圖片模型（繞開舊版 vertexai SDK 的 404）。回 {imageBase64, usage}。"""
    import json
    import urllib.request
    import urllib.error
    import google.auth
    from google.auth.transport.requests import Request as GAuthRequest

    creds, _ = google.auth.default(scopes=["https://www.googleapis.com/auth/cloud-platform"])
    creds.refresh(GAuthRequest())

    # responseModalities 必須含 TEXT，否則模型會回 NO_IMAGE 不出圖（實測）。
    modalities = data.get("responseModalities") or ["TEXT", "IMAGE"]
    gen_config = {"responseModalities": modalities}
    image_config = data.get("imageConfig")
    if image_config:
        gen_config["imageConfig"] = {"aspectRatio": image_config.get("aspectRatio", "1:1")}

    body = {
        "contents": [{"role": "user", "parts": [{"text": prompt}]}],
        "generationConfig": gen_config,
    }
    url = (f"https://aiplatform.googleapis.com/v1/projects/{PROJECT_ID}"
           f"/locations/{LOCATION}/publishers/google/models/{model_name}:generateContent")
    request = urllib.request.Request(
        url,
        data=json.dumps(body).encode("utf-8"),
        headers={"Authorization": f"Bearer {creds.token}", "Content-Type": "application/json"},
        method="POST",
    )
    try:
        with urllib.request.urlopen(request, timeout=150) as resp:
            payload = json.loads(resp.read().decode("utf-8"))
    except urllib.error.HTTPError as e:
        detail = e.read().decode("utf-8", "ignore")[:400]
        raise https_fn.HttpsError("internal", f"圖片生成失敗 HTTP {e.code}: {detail}")
    except Exception as e:
        raise https_fn.HttpsError("internal", f"圖片生成失敗: {e}")

    candidates = payload.get("candidates", [])
    if not candidates:
        raise https_fn.HttpsError("internal", "圖片回應為空，可能被安全過濾器攔截")

    um = payload.get("usageMetadata", {}) or {}
    usage = {
        "promptTokens": int(um.get("promptTokenCount", 0) or 0),
        "outputTokens": int(um.get("candidatesTokenCount", 0) or 0),
        "thoughtsTokens": int(um.get("thoughtsTokenCount", 0) or 0),
    }

    # REST 回應的 inlineData.data 已是 base64 字串，直接回傳給 App 解碼。
    for part in candidates[0].get("content", {}).get("parts", []):
        inline = part.get("inlineData") or {}
        if inline.get("data"):
            return {"imageBase64": inline["data"], "usage": usage}

    raise https_fn.HttpsError("internal", "圖片回應中沒有影像資料（finishReason 可能為 NO_IMAGE）")


@https_fn.on_call(region="us-central1", timeout_sec=120)
def generate_itinerary(req: https_fn.CallableRequest) -> dict:
    data = req.data
    start = data.get("startDateTime", "")
    end = data.get("endDateTime", "")
    people = data.get("people", "")
    pace = data.get("pace", "")
    allowed_spots = data.get("allowedSpots", [])

    import vertexai
    from vertexai.generative_models import GenerativeModel, GenerationConfig

    vertexai.init(project=PROJECT_ID, location=LOCATION)
    model = GenerativeModel("gemini-2.5-flash")

    prompt = _build_itinerary_prompt(start, end, people, pace, allowed_spots)

    response = model.generate_content(
        prompt,
        generation_config=GenerationConfig(
            temperature=0.3,
            response_mime_type="application/json"
        )
    )
    return {"result": response.text}


@https_fn.on_call(region="us-central1", timeout_sec=300)
def generate_image(req: https_fn.CallableRequest) -> dict:
    data = req.data
    ai_title = data.get("aiTitle", "")
    region = data.get("region", "台東")
    stop_details = data.get("stopDetails", "")

    import vertexai
    from vertexai.preview.vision_models import ImageGenerationModel

    vertexai.init(project=PROJECT_ID, location=LOCATION)
    model = ImageGenerationModel.from_pretrained("imagen-3.0-generate-002")

    prompt = f"""A 16:9 horizontal travel guide illustration in cute hand-drawn style.
Title: prominently display "{ai_title}" at the top in Traditional Chinese calligraphy style.
Layout: card-style layout with each attraction as its own illustrated card:
{stop_details}
Each card shows: attraction name in Traditional Chinese, visit time, and a realistic detailed illustration (architecture / local food / street scene / nature).
Style: realistic yet warm hand-drawn texture. Color tone adapts to content — coastal scenes use blue palette, mountain scenes use green palette, night market food uses warm orange palette.
Background: include local {region} elements (map outline, regional specialty icons).
Composition: 16:9 horizontal, cards arranged neatly without overlap, lively and informative like a travel guidebook."""

    images = model.generate_images(
        prompt=prompt,
        number_of_images=1,
        aspect_ratio="16:9"
    )

    img_base64 = base64.b64encode(images[0]._image_bytes).decode("utf-8")
    return {"imageBase64": img_base64}


def _build_itinerary_prompt(start: str, end: str, people: str, pace: str, allowed_spots: list) -> str:
    is_long_trip = start.split(" ")[0] != end.split(" ")[0]
    rules = [
        f"1. 針對 {start} 到 {end} 量身打造。嚴禁超出此區間，嚴禁在單日行程中使用『三天兩夜』字眼。",
        "2. 描述 (desc) 必須精簡在 25 字內。總景點數 3-5 個。",
        "3. 停留時間 (duration) 必須設定在 90 到 150 分鐘之間。",
        "4. 直接回傳純 JSON 字串，不要 Markdown 代碼塊。"
    ]
    if is_long_trip:
        rules.append("5. 需安排 12:00-13:30 的午餐停留。")

    spot_section = ""
    if allowed_spots:
        spot_section = (
            "\n【重要限制】可選景點清單如下，name 欄位只能從這份清單中選擇，嚴禁使用清單以外的地點：\n"
            + "、".join(allowed_spots)
        )

    return (
        "你是台東旅遊 AI。請生成 JSON：{title, aiTitle, aiReply, region, stops:[{order,emoji,name,time,desc,duration,businessHours}]}\n"
        f"行程規則：\n" + "\n".join(rules) + "\n"
        + spot_section
        + f"\n需求：台東, {start} 到 {end}, {people}, {pace}。"
    )


# ── 共同相簿：照片文件被刪時清掉 Storage 檔案與舊版 memories 引用 ─────────────
# 方案 B：行程 owner/editor 可刪別人的照片，但 storage.rules 只准上傳者本人刪檔，
# 所以別人刪掉的照片只會少了 Firestore 文件、Storage 檔案還在（有 token 的網址照樣打得開）。
# 這裡用 Admin SDK 補刪檔案；同時把舊版 memories/{ownerUid} 裡同一張的網址移掉，
# 否則兩端「新版 photos ＋ 舊版 memories」雙讀時照片會再出現。
@firestore_fn.on_document_deleted(
    document="micro_trips/{tripId}/photos/{photoId}",
    memory=MemoryOption.MB_256,
)
def cleanup_deleted_trip_photo(event: firestore_fn.Event) -> None:
    # 只用到 Admin SDK 的 firestore / storage，lazy import 避免拖慢部署時的探索
    from firebase_admin import firestore, storage
    from google.api_core.exceptions import NotFound

    data = event.data.to_dict() if event.data is not None else None
    if not data:
        return
    trip_id = event.params["tripId"]
    owner_uid = data.get("ownerUid") or ""
    path = data.get("storagePath") or ""
    url = data.get("url") or ""
    db = firestore.client()

    # storagePath 是用戶端寫的，只刪「擁有者自己資料夾、這趟行程底下」的檔案，
    # 避免被竄改的路徑指去刪別人的東西
    prefix = f"trip-photos/{owner_uid}/{trip_id}/"
    if owner_uid and path.startswith(prefix) and ".." not in path:
        # 同一個檔案還有別的照片文件在用（例如搬移時重複）就不刪
        still_used = any(
            doc.id != event.params["photoId"]
            for doc in db.collection("micro_trips").document(trip_id)
            .collection("photos").where("storagePath", "==", path).limit(2).stream()
        )
        if not still_used:
            try:
                storage.bucket().blob(path).delete()
                print(f"🧹 已刪除照片檔案 {path}")
            except NotFound:
                pass  # 上傳者本人刪除時 App／網頁已先刪掉檔案

    if owner_uid and (url or path):
        _drop_from_legacy_memory(db, trip_id, owner_uid, url, path)


def _drop_from_legacy_memory(db, trip_id: str, owner_uid: str, url: str, path: str) -> None:
    from urllib.parse import unquote

    def same_photo(item) -> bool:
        item_url = item if isinstance(item, str) else (item or {}).get("url", "")
        item_path = "" if isinstance(item, str) else (item or {}).get("storagePath", "")
        if not item_path and "/o/" in item_url:
            item_path = unquote(item_url.split("/o/", 1)[1].split("?", 1)[0])
        return (url and item_url == url) or (path and item_path == path)

    ref = db.collection("micro_trips").document(trip_id).collection("memories").document(owner_uid)
    snap = ref.get()
    if not snap.exists:
        return
    memory = snap.to_dict() or {}
    spots = memory.get("spots") or {}
    updates = {}
    remaining_urls = []
    for key, spot in spots.items():
        photos = (spot or {}).get("photos")
        if not isinstance(photos, list):
            continue
        kept = [p for p in photos if not same_photo(p)]
        if len(kept) != len(photos):
            updates[f"spots.`{key}`.photos"] = kept
        remaining_urls += [p if isinstance(p, str) else (p or {}).get("url", "") for p in kept]
    # 封面若正是被刪的那張，換成剩下的第一張（沒有就清空），否則封面還會指向已刪的檔案
    if same_photo(memory.get("coverUrl") or ""):
        updates["coverUrl"] = next((u for u in remaining_urls if u), "")
    if updates:
        ref.update(updates)
        print(f"🧹 已從 memories/{owner_uid} 移除舊照片引用：{sorted(updates)}（{trip_id}）")
