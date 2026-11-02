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
# 2026-10-11 起 App 改用 gemini-3-flash-preview（文字，對齊網頁）與 gemini-3-pro-image（插圖）；
# 2.5 / 3.1 先留著：已安裝的 v1.1 試用版還在用，全部更新後再拿掉。
ALLOWED_MODELS = {"gemini-2.5-flash", "gemini-3.1-flash-image", "gemini-3-flash-preview", "gemini-3-pro-image"}
# Gemini 3 只開在 global（實測 us-central1 回 404「Publisher model … was not found」）。
# 舊版 vertexai SDK 處理不了 global 的端點，這些模型一律走 REST。
GLOBAL_MODELS = {"gemini-3-flash-preview", "gemini-3-pro-image"}
MAX_PROMPT_LENGTH = 20000
# 圖片生成可附的參考圖（互動插圖的構圖草稿）：一張、base64 後最多約 3MB（草稿是幾十 KB 的 PNG）
MAX_REFERENCE_IMAGES = 1
MAX_REFERENCE_IMAGE_B64 = 3_000_000
# 文字呼叫可附的照片（回顧短片配樂建議：每站一張代表照、長邊 512px 的 JPEG，一張約 50～80KB）
MAX_TEXT_IMAGES = 6
MAX_TEXT_IMAGE_B64 = 400_000

# 生成耗時量測：分出「冷啟動（新實例＋首次 import vertexai）」與「模型本身」各花多少時間
_INSTANCE_STARTED_AT = time.time()
_calls_on_instance = 0


@https_fn.on_call(region="us-central1", timeout_sec=300)
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
        # 新版 App（文字走 REST 的 gemini-3）帶 rest=true：只叫醒實例、取好權杖，約 1 秒。
        # 不能再載入 vertexai：要 22 秒，而一個實例同時只處理一個請求，使用者在這期間按 AI
        # 會再開一個冷實例（實測換景點來回 27.4 秒）。舊版 v1.1 沒帶旗標，照舊預載 vertexai。
        if data.get("rest"):
            _vertex_creds()
            import_ms = int((time.perf_counter() - t_import) * 1000)
            print(f"[timing] warmup(rest) importMs={import_ms} coldStart={_calls_on_instance == 1}")
            return {"warm": True, "timing": {"importMs": import_ms, "coldStart": _calls_on_instance == 1}}
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

    # 每日免費額度：先記一次再呼叫模型，模型失敗就退回（見 _reserve_quota）
    is_image = "IMAGE" in (data.get("responseModalities") or [])
    kinds = _quota_kinds(is_image, data.get("quotaKind"))
    used = _reserve_quota(req.auth.uid, kinds)
    try:
        result = _run_generation(model_name, prompt, data, t_start, is_image)
    except Exception:
        if used is not None:
            _refund_quota(req.auth.uid, kinds)
        raise
    if used is not None:
        result["quota"] = used
    return result


def _run_generation(model_name: str, prompt: str, data: dict, t_start: float, is_image: bool) -> dict:
    # 圖片生成改走 REST v1：舊版 vertexai SDK（requirements 釘 >=1.70.0）呼叫
    # gemini-3.1-flash-image 會回 404「model not found」，但 REST v1 端點正常出圖。
    # 文字生成維持走 SDK（已驗證正常）。
    if is_image:
        return _generate_image_via_rest(model_name, prompt, data)
    if model_name in GLOBAL_MODELS:
        return _generate_text_via_rest(model_name, prompt, data, t_start)
    if data.get("images"):
        raise https_fn.HttpsError("invalid-argument", f"{model_name} 不支援附照片")

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


# ── 每日免費額度 ─────────────────────────────────────────────
# 依登入帳號、台灣日期計次：usage_daily/{uid}_{YYYY-MM-DD} = {uid, date, trip, image, chat, swap, text, updatedAt}。
# 網頁代理也寫同一份文件（同一套欄位），兩邊的次數合在一起算。只有後端（Admin SDK）能寫，規則不開放用戶端。
#   trip／swap／chat：用戶端在 quotaKind 標明是哪個動作（行程生成第一次呼叫、換景點建議、隨行管家一則）
#   image：每畫一張（伺服器看 responseModalities 判斷，用戶端標不掉）
#   text：所有文字呼叫的總數。行程的備援呼叫、插圖的畫面描述不另外標 quotaKind，靠這個總數兜底，
#         防止用戶端不標 quotaKind 就無限呼叫
# 上限與豁免帳號放在 config/ai_quota（{limits: {trip: 10, ...}, exemptUids: [...]}），Console 改了一分鐘內生效。
DEFAULT_QUOTA_LIMITS = {"trip": 10, "image": 3, "chat": 30, "swap": 20, "text": 150}
CLIENT_QUOTA_KINDS = {"trip", "swap", "chat"}
QUOTA_MESSAGES = {
    "trip": "今天的免費行程生成（{n} 次）用完了，明天再試",
    "image": "今天的免費插圖（{n} 張）用完了，明天再畫",
    "chat": "今天的隨行管家免費次數（{n} 則）用完了，明天再問我",
    "swap": "今天的換景點建議（{n} 次）用完了，明天再試",
    "text": "今天的 AI 使用次數到上限了，明天再試",
}
_TAIPEI = None
_quota_cfg = {"at": 0.0, "limits": dict(DEFAULT_QUOTA_LIMITS), "exempt": set()}


def _quota_kinds(is_image: bool, quota_kind) -> list:
    if is_image:
        return ["image"]
    return ["text", quota_kind] if quota_kind in CLIENT_QUOTA_KINDS else ["text"]


def _quota_config() -> dict:
    """config/ai_quota 快取一分鐘；讀不到時沿用上次（第一次就讀不到則用預設值）"""
    if time.time() - _quota_cfg["at"] < 60:
        return _quota_cfg
    from firebase_admin import firestore
    try:
        d = firestore.client().collection("config").document("ai_quota").get().to_dict() or {}
        limits = dict(DEFAULT_QUOTA_LIMITS)
        for k, v in (d.get("limits") or {}).items():
            if k in limits and isinstance(v, (int, float)):
                limits[k] = int(v)
        _quota_cfg.update(limits=limits, exempt=set(d.get("exemptUids") or []))
    except Exception as e:
        print(f"[quota] 讀不到 config/ai_quota，沿用目前設定：{e}")
    _quota_cfg["at"] = time.time()
    return _quota_cfg


def _quota_ref(uid: str):
    from datetime import datetime, timedelta, timezone
    from firebase_admin import firestore
    global _TAIPEI
    _TAIPEI = _TAIPEI or timezone(timedelta(hours=8))   # 台灣沒有日光節約時間
    date = datetime.now(_TAIPEI).strftime("%Y-%m-%d")
    return firestore.client().collection("usage_daily").document(f"{uid}_{date}"), date


def _reserve_quota(uid: str, kinds: list):
    """額度內就先記上一次，回傳記完後的 {kind: {used, limit}}；超過丟 resource-exhausted。豁免帳號回 None。"""
    from firebase_admin import firestore
    cfg = _quota_config()
    if uid in cfg["exempt"]:
        return None
    limits = cfg["limits"]
    ref, date = _quota_ref(uid)

    @firestore.transactional
    def _take(tx):
        snap = ref.get(transaction=tx)
        cur = (snap.to_dict() or {}) if snap.exists else {}
        for k in kinds:
            if int(cur.get(k, 0)) >= limits[k]:
                raise https_fn.HttpsError("resource-exhausted", QUOTA_MESSAGES[k].format(n=limits[k]),
                                          {"kind": k, "limit": limits[k]})
        after = {k: int(cur.get(k, 0)) + 1 for k in kinds}
        tx.set(ref, {"uid": uid, "date": date, **after, "updatedAt": firestore.SERVER_TIMESTAMP}, merge=True)
        return {k: {"used": after[k], "limit": limits[k]} for k in kinds}

    return _take(firestore.client().transaction())


def _refund_quota(uid: str, kinds: list) -> None:
    """模型失敗時退回剛記的那一次；退不回只記 log，不影響回給用戶端的錯誤"""
    from firebase_admin import firestore
    try:
        ref, _ = _quota_ref(uid)
        ref.set({k: firestore.Increment(-1) for k in kinds}, merge=True)
    except Exception as e:
        print(f"[quota] 退回失敗 uid={uid} kinds={kinds}: {e}")


@https_fn.on_call(region="us-central1", timeout_sec=30)
def get_ai_quota(req: https_fn.CallableRequest) -> dict:
    """查今天的免費額度：{date, exempt, limits, used}（App 畫插圖前用來看還能畫幾張）"""
    if req.auth is None:
        raise https_fn.HttpsError("unauthenticated", "需要登入")
    cfg = _quota_config()
    ref, date = _quota_ref(req.auth.uid)
    cur = ref.get().to_dict() or {}
    return {"date": date, "exempt": req.auth.uid in cfg["exempt"], "limits": cfg["limits"],
            "used": {k: int(cur.get(k, 0)) for k in DEFAULT_QUOTA_LIMITS}}


# 服務帳戶憑證：同一個實例沿用，權杖過期（約 1 小時）才重取，不必每次呼叫都多一趟 refresh
_cached_creds = None


def _vertex_creds():
    global _cached_creds
    import google.auth
    from google.auth.transport.requests import Request as GAuthRequest
    if _cached_creds is None:
        _cached_creds, _ = google.auth.default(scopes=["https://www.googleapis.com/auth/cloud-platform"])
    if not _cached_creds.valid:
        _cached_creds.refresh(GAuthRequest())
    return _cached_creds


def _vertex_rest(model_name: str, body: dict, timeout: int) -> dict:
    """以服務帳戶呼叫 Vertex REST v1 generateContent，依模型選 global 或 us-central1。"""
    import json
    import urllib.request

    creds = _vertex_creds()
    if model_name in GLOBAL_MODELS:
        host, location = "aiplatform.googleapis.com", "global"
    else:
        host, location = "aiplatform.googleapis.com", LOCATION
    url = (f"https://{host}/v1/projects/{PROJECT_ID}"
           f"/locations/{location}/publishers/google/models/{model_name}:generateContent")
    request = urllib.request.Request(
        url,
        data=json.dumps(body).encode("utf-8"),
        headers={"Authorization": f"Bearer {creds.token}", "Content-Type": "application/json"},
        method="POST",
    )
    with urllib.request.urlopen(request, timeout=timeout) as resp:
        return json.loads(resp.read().decode("utf-8"))


def _usage_of(payload: dict) -> dict:
    um = payload.get("usageMetadata", {}) or {}
    return {
        "promptTokens": int(um.get("promptTokenCount", 0) or 0),
        "outputTokens": int(um.get("candidatesTokenCount", 0) or 0),
        "thoughtsTokens": int(um.get("thoughtsTokenCount", 0) or 0),
    }


def _generate_text_via_rest(model_name: str, prompt: str, data: dict, t_start: float) -> dict:
    """Gemini 3 文字生成（global 端點）。參數與 SDK 路徑相同：temperature、responseMimeType、
    thinkingBudget（網頁對 gemini-3-flash-preview 也用 thinkingBudget，0＝不思考）、maxOutputTokens。"""
    import urllib.error

    gen_config = {"temperature": data.get("temperature", 0.4)}
    if data.get("responseMimeType"):
        gen_config["responseMimeType"] = data["responseMimeType"]
    if data.get("thinkingBudget") is not None:
        gen_config["thinkingConfig"] = {"thinkingBudget": int(data["thinkingBudget"])}
    if data.get("maxOutputTokens") is not None:
        gen_config["maxOutputTokens"] = int(data["maxOutputTokens"])
    # 照片（選填）放在文字前面，與圖片生成的參考圖相同
    parts = _image_parts(data, MAX_TEXT_IMAGES, MAX_TEXT_IMAGE_B64, "照片")
    parts.append({"text": prompt})
    body = {"contents": [{"role": "user", "parts": parts}], "generationConfig": gen_config}

    t_model = time.perf_counter()
    try:
        payload = _vertex_rest(model_name, body, timeout=150)
    except urllib.error.HTTPError as e:
        detail = e.read().decode("utf-8", "ignore")[:400]
        raise https_fn.HttpsError("internal", f"AI 生成失敗 HTTP {e.code}: {detail}")
    except Exception as e:
        raise https_fn.HttpsError("internal", f"AI 生成失敗: {e}")
    timing = {
        "serverMs": int((time.perf_counter() - t_start) * 1000),
        "importMs": 0,
        "modelMs": int((time.perf_counter() - t_model) * 1000),
        "coldStart": _calls_on_instance == 1,
        "instanceAgeS": int(time.time() - _INSTANCE_STARTED_AT),
    }
    print(f"[timing] generate_content {model_name} {timing}")

    candidates = payload.get("candidates", [])
    if not candidates:
        raise https_fn.HttpsError("internal", "AI 回應為空，可能被安全過濾器攔截")
    # thinking 開著時 parts 會有 thought=true 的思考摘要，不能混進結果（JSON 會壞）
    text = "".join(p.get("text", "") for p in candidates[0].get("content", {}).get("parts", [])
                   if not p.get("thought"))
    if not text:
        raise https_fn.HttpsError("internal", f"AI 回應沒有文字（finishReason={candidates[0].get('finishReason')}）")
    return {"result": text, "usage": _usage_of(payload), "timing": timing}


def _image_parts(data: dict, max_n: int, max_b64: int, label: str) -> list:
    """data["images"]（[{mimeType, data(base64)}]）轉成 Gemini 的 inlineData parts；超過 max_n 張的直接略過"""
    images = data.get("images") or []
    if not isinstance(images, list):
        raise https_fn.HttpsError("invalid-argument", f"{label}格式不正確")
    parts = []
    for img in images[:max_n]:
        mime = img.get("mimeType") if isinstance(img, dict) else None
        b64 = img.get("data") if isinstance(img, dict) else None
        if mime not in ("image/png", "image/jpeg") or not isinstance(b64, str) or not b64:
            raise https_fn.HttpsError("invalid-argument", f"{label}格式不正確")
        if len(b64) > max_b64:
            raise https_fn.HttpsError("invalid-argument", f"{label}太大")
        parts.append({"inlineData": {"mimeType": mime, "data": b64}})
    return parts


def _generate_image_via_rest(model_name: str, prompt: str, data: dict) -> dict:
    """以 REST v1 呼叫圖片模型（繞開舊版 vertexai SDK 的 404）。回 {imageBase64, usage}。"""
    import urllib.error

    # responseModalities 必須含 TEXT，否則模型會回 NO_IMAGE 不出圖（實測）。
    modalities = data.get("responseModalities") or ["TEXT", "IMAGE"]
    gen_config = {"responseModalities": modalities}
    image_config = data.get("imageConfig")
    if image_config:
        gen_config["imageConfig"] = {"aspectRatio": image_config.get("aspectRatio", "1:1")}

    # 參考圖（選填）：互動插圖的「構圖草稿」（紅色編號圓點＋虛線路線），放在文字前面，與網頁 posterSketchPart 相同
    parts = _image_parts(data, MAX_REFERENCE_IMAGES, MAX_REFERENCE_IMAGE_B64, "參考圖")
    parts.append({"text": prompt})
    body = {
        "contents": [{"role": "user", "parts": parts}],
        "generationConfig": gen_config,
    }
    # gemini-3-pro-image 實測小圖就要 ~30 秒，整張行程插圖更久；上限放到函式逾時（300s）之內
    try:
        payload = _vertex_rest(model_name, body, timeout=280)
    except urllib.error.HTTPError as e:
        detail = e.read().decode("utf-8", "ignore")[:400]
        raise https_fn.HttpsError("internal", f"圖片生成失敗 HTTP {e.code}: {detail}")
    except Exception as e:
        raise https_fn.HttpsError("internal", f"圖片生成失敗: {e}")

    candidates = payload.get("candidates", [])
    if not candidates:
        raise https_fn.HttpsError("internal", "圖片回應為空，可能被安全過濾器攔截")

    usage = _usage_of(payload)

    # REST 回應的 inlineData.data 已是 base64 字串，直接回傳給 App 解碼。
    for part in candidates[0].get("content", {}).get("parts", []):
        inline = part.get("inlineData") or {}
        if inline.get("data"):
            return {"imageBase64": inline["data"], "usage": usage}

    raise https_fn.HttpsError("internal", "圖片回應中沒有影像資料（finishReason 可能為 NO_IMAGE）")


# ── 互動插圖：找出每一站在行程插圖上的位置 ──
# 插圖是 AI 畫的，不對應真實地理，站在哪裡只能請模型看圖找。
# 結果寫回 micro_trips/{tripId}.imageStopBoxes，App 與網頁共用：
#   {imageUrl, model, at, boxes: [{stopId, name, box: [ymin, xmin, ymax, xmax], point: [y, x]}]}（0～1000 正規化）
# box＝點擊範圍（編號標記、名稱、小插圖）；point＝該站在路線上的標記中心（選填），進行中的小點沿這些點走。
# imageUrl 記的是「這組框是看哪張圖找的」；插圖重畫後網址會變，用戶端比對不同就再呼叫一次。
STOP_BOX_MODEL = "gemini-3-flash-preview"
MAX_BOX_STOPS = 40


def _is_trip_member(doc: dict, uid: str, email: str) -> bool:
    if uid and uid == doc.get("ownerUid"):
        return True
    if not email:
        return False
    email = email.lower()
    singles = [doc.get("ownerEmail"), doc.get("userEmail")]
    lists = (doc.get("memberEmails") or []) + (doc.get("editorEmails") or [])
    return any(isinstance(e, str) and e.lower() == email for e in singles + lists)


def _storage_path_of(url: str, bucket_name: str) -> str | None:
    """Firebase Storage 下載網址 → 檔案路徑；不是我們自己 bucket 的網址一律不收（避免被拿去抓任意網址）。"""
    from urllib.parse import urlparse, unquote
    u = urlparse(url)
    if u.scheme != "https" or u.netloc != "firebasestorage.googleapis.com":
        return None
    parts = u.path.split("/")   # ['', 'v0', 'b', bucket, 'o', encodedPath]
    if len(parts) != 6 or parts[1] != "v0" or parts[2] != "b" or parts[4] != "o" or parts[3] != bucket_name:
        return None
    path = unquote(parts[5])
    return path if path and ".." not in path else None


def _stop_box_prompt(stops: list) -> str:
    # 每一站用它在行程裡的序號（1 起算）找，不用站名：起點＝終點時兩站同名，用名字對會把同一個框
    # 給兩站、另一處標記反而沒框（2026-10-19 泓廷回報開元漁港 1／11）。網頁疊字插圖的同一地點
    # 標成「1／11」，這時兩個編號各回一筆、同一個位置。
    stop_lines = "\n".join(f"{i + 1}. {s['name']}" for i, s in enumerate(stops))
    return (
        "這是一張旅遊行程插畫，圖上的景點可能標有編號 1、2、3…並以路線相連。請依編號找出下列每一站在圖中的位置，"
        "回傳 JSON 陣列，每筆 {\"number\": 站的編號, \"box_2d\": [ymin, xmin, ymax, xmax], \"point\": [y, x]}，"
        "座標以 0～1000 正規化。box_2d 框住該站的編號標記、名稱文字與旁邊的小插圖；"
        "point 是該站在路線上的標記（例如編號圓點）的中心，沒有標記就給該站插圖的中心。"
        "圖上有編號時以編號為準：同一個地點的標記可能寫成「1／11」這類合併編號，這時編號 1 和 11 各回一筆、用同一個位置；"
        "同一個地點若畫了兩處、各有自己的編號，就各自回傳那一處。圖上沒有編號時才依名稱找。"
        "每個編號只回一筆；圖中找不到的站就不要回。\n各站：\n" + stop_lines
    )


# 用 schema 鎖住回傳格式：只給 responseMimeType 時，模型偶爾會寫出壞掉的 JSON
#（2026-10-18 實測某張圖固定在第 5 筆漏掉 "number"，temperature 0 每次都一樣壞）
_STOP_BOX_SCHEMA = {
    "type": "ARRAY",
    "items": {
        "type": "OBJECT",
        "properties": {
            "number": {"type": "INTEGER"},
            "box_2d": {"type": "ARRAY", "items": {"type": "INTEGER"}, "minItems": 4, "maxItems": 4},
            "point": {"type": "ARRAY", "items": {"type": "INTEGER"}, "minItems": 2, "maxItems": 2},
        },
        "required": ["number", "box_2d", "point"],
        "propertyOrdering": ["number", "box_2d", "point"],
    },
}


def _stop_box_request(stops: list, mime: str, image_bytes: bytes) -> dict:
    return {
        "contents": [{"role": "user", "parts": [
            {"inlineData": {"mimeType": mime, "data": base64.b64encode(image_bytes).decode("ascii")}},
            {"text": _stop_box_prompt(stops)}]}],
        "generationConfig": {"responseMimeType": "application/json", "responseSchema": _STOP_BOX_SCHEMA,
                             "temperature": 0, "thinkingConfig": {"thinkingBudget": 0}},
    }


def _parse_stop_boxes(raw, stops: list) -> list:
    """模型回應 → [{stopId, name, box, point?}]，依 stops 順序；編號＝站在行程裡的序號（1 起算），壞資料略過。
    模型漏回的站，若有同名的站找到了就沿用它的位置（舊版插圖同一地點只畫一次、沒有合併編號）。"""
    def coords(v, n):
        if isinstance(v, list) and len(v) == n and all(isinstance(x, (int, float)) for x in v):
            return [max(0, min(1000, int(round(x)))) for x in v]
        return None

    found = {}
    for item in raw if isinstance(raw, list) else []:
        if not isinstance(item, dict) or not isinstance(item.get("number"), int):
            continue
        idx = item["number"] - 1
        if not 0 <= idx < len(stops) or idx in found:
            continue
        box = coords(item.get("box_2d"), 4)
        point = coords(item.get("point"), 2)
        if box is None or box[2] <= box[0] or box[3] <= box[1]:
            # 框壞掉（實測回過 3 個數字）但圓點位置還在：以圓點為中心補一個可以點的範圍
            if point is None:
                continue
            box = [max(0, point[0] - 50), max(0, point[1] - 80), min(1000, point[0] + 50), min(1000, point[1] + 80)]
        found[idx] = (box, point)

    by_name = {}
    for idx in sorted(found):
        by_name.setdefault(stops[idx]["name"].strip(), found[idx])
    boxes = []
    for i, s in enumerate(stops):
        hit = found.get(i) or by_name.get(s["name"].strip())
        if hit is None:
            continue
        entry = {"stopId": s["stopId"], "name": s["name"], "box": hit[0]}
        if hit[1] is not None:
            entry["point"] = hit[1]
        boxes.append(entry)
    return boxes


@https_fn.on_call(region="us-central1", timeout_sec=120)
def detect_stop_boxes(req: https_fn.CallableRequest) -> dict:
    """請 gemini-3-flash-preview 看行程插圖，回每站的框並存回行程文件。
    參數：tripId、stops=[{stopId, name}]、force（選填，同一張圖也重找）。"""
    import json
    import urllib.error
    from firebase_admin import firestore, storage

    if req.auth is None:
        raise https_fn.HttpsError("unauthenticated", "需要登入")
    data = req.data or {}
    trip_id = data.get("tripId") or ""
    if not isinstance(trip_id, str) or not trip_id or "/" in trip_id:
        raise https_fn.HttpsError("invalid-argument", "tripId 不正確")
    stops = []
    for s in (data.get("stops") or [])[:MAX_BOX_STOPS]:
        if isinstance(s, dict) and isinstance(s.get("stopId"), str) and isinstance(s.get("name"), str):
            name = s["name"].strip()[:60]
            if s["stopId"] and name:
                stops.append({"stopId": s["stopId"][:80], "name": name})
    if not stops:
        raise https_fn.HttpsError("invalid-argument", "沒有景點")

    db = firestore.client()
    ref = db.collection("micro_trips").document(trip_id)
    snap = ref.get()
    if not snap.exists:
        raise https_fn.HttpsError("not-found", "行程不存在")
    doc = snap.to_dict() or {}
    if not _is_trip_member(doc, req.auth.uid, (req.auth.token or {}).get("email", "")):
        raise https_fn.HttpsError("permission-denied", "不是這趟行程的成員")

    # 圖一律用行程文件上的 imageUrl，不收用戶端給的網址
    image_url = doc.get("imageUrl") or ""
    existing = doc.get("imageStopBoxes") or {}
    # 同一張圖已有站框就回快取。疊字時算好的框（model＝web-layout，網頁與 App 都會寫）是精確值，
    # 就算帶 force 也不重找，免得被模型猜的框蓋掉
    same_image = existing.get("imageUrl") == image_url and existing.get("boxes") is not None
    if same_image and (not data.get("force") or existing.get("model") == "web-layout"):
        return {"imageUrl": image_url, "boxes": existing.get("boxes"), "cached": True}

    bucket = storage.bucket()
    path = _storage_path_of(image_url, bucket.name)
    if path is None:
        raise https_fn.HttpsError("failed-precondition", "行程還沒有雲端插圖")
    blob = bucket.blob(path)
    try:
        image_bytes = blob.download_as_bytes()
    except Exception as e:
        raise https_fn.HttpsError("not-found", f"讀不到插圖：{e}")
    mime = blob.content_type or "image/jpeg"

    body = _stop_box_request(stops, mime, image_bytes)
    t_model = time.perf_counter()
    try:
        payload = _vertex_rest(STOP_BOX_MODEL, body, timeout=90)
    except urllib.error.HTTPError as e:
        detail = e.read().decode("utf-8", "ignore")[:400]
        raise https_fn.HttpsError("internal", f"找站點失敗 HTTP {e.code}: {detail}")
    except Exception as e:
        raise https_fn.HttpsError("internal", f"找站點失敗: {e}")

    candidates = payload.get("candidates", [])
    text = "".join(p.get("text", "") for p in (candidates[0].get("content", {}).get("parts", []) if candidates else [])
                   if not p.get("thought"))
    try:
        raw = json.loads(text)
    except Exception:
        raise https_fn.HttpsError("internal", "模型回傳的格式看不懂")

    boxes = _parse_stop_boxes(raw, stops)

    model_ms = int((time.perf_counter() - t_model) * 1000)
    print(f"[timing] detect_stop_boxes trip={trip_id} found={len(boxes)}/{len(stops)} modelMs={model_ms} "
          f"usage={_usage_of(payload)}")
    # 只動 imageStopBoxes 這一個欄位；圖在呼叫期間被重畫就不寫，免得新圖配舊框
    @firestore.transactional
    def _save(tx):
        cur = ref.get(transaction=tx).to_dict() or {}
        if (cur.get("imageUrl") or "") != image_url:
            return False
        tx.update(ref, {"imageStopBoxes": {"imageUrl": image_url, "model": STOP_BOX_MODEL,
                                           "at": firestore.SERVER_TIMESTAMP, "boxes": boxes}})
        return True
    saved = _save(db.transaction())
    return {"imageUrl": image_url, "boxes": boxes, "cached": False, "saved": saved,
            "usage": _usage_of(payload)}


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
