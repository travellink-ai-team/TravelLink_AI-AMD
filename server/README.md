# WanderAI 後端代理（Vertex AI / Gemini + TDX）

把 `VERTEX_API_KEY` 與 `TDX_APP_ID/KEY` 留在伺服器端，前端只呼叫同源的 `/api/vertex/*` 與 `/api/tdx/*`，密鑰不進瀏覽器。

**安全（2026-07 資安測試後加固）**：
- `/api/vertex` 需 Firebase 登入（`Authorization: Bearer <idToken>`，firebase-admin 驗證，服務帳戶沿用 `../crawler/serviceAccount.json`），未登入 401。
- 速率限制：vertex 20 次/10 分/IP、tdx 60 次/10 分/IP（超量 429；依賴 nginx 傳 `X-Forwarded-For`）。
- 路徑白名單：vertex 僅兩個實際使用的 model；tdx 僅景點（新 odata `V2/Tourism/Attraction`）與停車場端點。

## 為什麼需要它

WanderAI 是純前端應用，原本直接從瀏覽器打 Vertex AI（`...:generateContent?key=VERTEX_API_KEY`），
金鑰藏不住（DevTools 可見）。此代理讓瀏覽器改打 `/api/vertex/...`（不帶 key），由伺服器注入真實金鑰再轉發，
金鑰因此只存在伺服器。

> Maps / Firebase 金鑰**無法**這樣代理（Maps JS 與 Firebase SDK 一定在瀏覽器執行），
> 那兩把改用 HTTP 參照網址 / 授權網域限制（見 [../DEPLOY.md](../DEPLOY.md) Step 4 方案 C）。

## 安裝與啟動

```powershell
cd C:\Users\USER\Desktop\UIUX\server
npm install
Copy-Item .env.example .env    # 首次；再把 VERTEX_API_KEY 填成真實值
npm start                       # http://127.0.0.1:3001（僅本機）
```

- 只監聽 `127.0.0.1`：外部一律經 nginx `location /api/` 反向代理進來，此埠不直接對外。
- 路徑白名單只放 Vertex 產生內容端點，避免變成 open proxy。
- 健康檢查：`GET /api/health` → `{ ok: true }`。

## 前端如何接上

`app/weather.env.js` 的 `TRAVEL_APP_CONFIG.API_PROXY_BASE` 設為 `'/api'` 即啟用代理模式：
前端會走 `/api/vertex/...` 且不再需要 `VERTEX_API_KEY`。留空則回退為直接呼叫 Google（需前端自帶金鑰，僅本機開發用）。

## nginx 設定

`nginx.conf` 需把 `/api/` 反向代理到本服務（SSE 串流要關 buffering）：

```nginx
location /api/ {
    proxy_pass http://127.0.0.1:3001;
    proxy_http_version 1.1;
    proxy_set_header Host $host;
    proxy_buffering off;          # Vertex 串流（SSE）需要
    proxy_read_timeout 300s;      # AI 生成較久
}
```

## 開機常駐

比照 nginx，可用 nssm 把 `node server.js` 註冊成 Windows 服務，或用 PM2：
`pm2 start server.js --name wanderai-proxy && pm2 save`。
