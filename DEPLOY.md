# TravelLink AI — 自架網站部署指南（固定公網 IP 直連）

> **適用環境**：Windows 11 + Node.js + 靜態 HTML + 後端代理
> **架構**：Nginx（靜態網頁）＋ Node 代理（Vertex 金鑰）→ 直接對外開 Port 80/443
> **前置需求**：**固定公網 IP**（本機為 `210.240.160.150`，TANet 學術網路直連公網、無 NAT）

---

## 一、架構總覽

```
使用者（瀏覽器）
    │  http://210.240.160.150/
    ↓  Port 80/443（固定公網 IP，直接掛在本機，無 NAT）
┌─────────────────────────────────────────────┐
│  你的電腦（Windows 11）                       │
│                                               │
│  ┌────────────┐   /            ┌───────────┐  │
│  │   Nginx    │──────────────→ │  app/*    │  │  ← 靜態網頁（root=app）
│  │ Port 80/443│                └───────────┘  │
│  │  (加固)     │   /api/vertex/*                │
│  │            │──────┐                         │
│  └────────────┘      ↓                         │
│                ┌──────────────┐                │
│                │ Node 代理     │  VERTEX_API_KEY │
│                │ 127.0.0.1:3001│  (server/.env) │
│                └──────┬───────┘                │
└───────────────────────┼───────────────────────┘
                        ↓  金鑰在伺服器注入
                  Vertex AI (Gemini)

前端（瀏覽器）直接呼叫：Firebase / Google Maps（金鑰靠 console 網域限制，見 Step 4）
```

**目前狀態**：nginx（80）與 Node 代理（3001）都在跑、都設了登入自啟；防火牆預設擋 inbound，所以外部**尚未**連得進來。加一條防火牆允許規則（Step 6）後，`http://210.240.160.150/` 立即對公網開放。

### 固定公網 IP 的重點

| 項目 | 說明 |
|---|---|
| DDNS | ❌ 不需要（你有固定 IP，直接用 `210.240.160.150`） |
| 路由器 Port Forwarding | ❌ 不需要（公網 IP 直接掛在本機，沒有 NAT 要轉） |
| Windows 防火牆開 80/443 | ✅ 需要（這是唯一的對外開關，見 Step 6） |
| nginx 設定 | ✅ 不用為了 IP 改動，`listen 80` + `server_name _` 已接受 IP 直連 |
| 完全暴露在公網 | ⚠️ 無 NAT 緩衝，加固與金鑰限制務必做滿 |

> **⚠️ TANet 學術網路必讀**
> 1. **上游可能已擋 inbound 80/443**：就算開了 Windows 防火牆，學校網管的防火牆可能仍擋著。**務必用手機行動網路（關 Wi-Fi）從外部實測**；連不進來多半是這個原因，需向網管申請開放。
> 2. **檢查學校 IT 使用規範**：許多學術網路**禁止架公開伺服器**，違規可能被停用網路。上線前先確認被允許。
> 3. **更安全的替代（Tailscale）**：若只需給特定幾個人看，本機已裝 Tailscale（`100.86.166.39`），用 Tailscale 分享 / Funnel 不用開任何公網埠、自帶 HTTPS、只有授權裝置連得到。

---

## 二、Step 1 — 安裝 Nginx（Windows 版）

### 1.1 下載 Nginx

到 https://nginx.org/en/download.html 下載 **Stable 版** 的 Windows zip，解壓到 `C:\nginx`（路徑不要有中文或空格）。解壓後結構應為：

```
C:\nginx\nginx.exe
C:\nginx\conf\nginx.conf
C:\nginx\html\
```

### 1.2 測試能否啟動

```powershell
cd C:\nginx
.\nginx.exe                # 啟動
# 瀏覽器開 http://localhost 看到 "Welcome to nginx!" 即成功
.\nginx.exe -s stop        # 停止
```

---

## 三、Step 2 — 設定 Nginx（含安全防護）

> ⚠️ **關鍵：`root` 指向 `app/`，不是整個專案。**
> 專案根目錄含 `.claude/`（記憶檔/對話記錄）、`crawler/serviceAccount.json`（Firebase 管理員金鑰，可讀寫整個資料庫）、`.git/`、`*.md`、`firestore.rules`。
> **絕不可**把整個 `C:/Users/USER/Desktop/UIUX` 當網站根目錄——那樣即使加了 deny 規則，漏一條就外洩。
> `root` 指向 `app/` 後，所有敏感內容都在 `app/` 之外，天然不在網站根目錄底下，根本無從存取；網頁 HTML/CSS/JS/資料檔本來就都在 `app/`，功能不受影響。

把 `C:\nginx\conf\nginx.conf` 替換為以下內容（**本設定已於本機實測通過**）：

```nginx
worker_processes  1;

events {
    worker_connections  1024;
}

http {
    include       mime.types;
    default_type  application/octet-stream;
    sendfile      on;
    keepalive_timeout  65;

    server_tokens off;                 # 隱藏版本號
    client_max_body_size 10m;          # 限制 body 大小（防大檔攻擊）

    log_format  main  '$remote_addr - [$time_local] "$request" '
                      '$status $body_bytes_sent "$http_user_agent"';
    access_log  logs/access.log  main;
    error_log   logs/error.log;

    # 封鎖已知惡意 IP（見 Step 3）
    # 注意：include 相對路徑是相對於 conf/ 目錄，寫 blocklist.conf（不是 conf/blocklist.conf）
    include       blocklist.conf;

    # 限制請求速率（防掃描/DDoS）：每個 IP 每秒最多 10 個請求
    limit_req_zone $binary_remote_addr zone=general:10m rate=10r/s;

    server {
        listen       80;
        server_name  _;

        # 只曝光 app/ 目錄（見上方警告）
        root  "C:/Users/USER/Desktop/UIUX/app";
        index ai-travel-explore-final.html;

        # 防呆：封鎖任何點開頭的隱藏檔
        location ~ /\. {
            deny all;
            return 404;
        }

        # 安全標頭
        add_header X-Frame-Options "SAMEORIGIN" always;
        add_header X-Content-Type-Options "nosniff" always;
        add_header Referrer-Policy "strict-origin-when-cross-origin" always;
        add_header Permissions-Policy "camera=(), microphone=(), geolocation=(self)" always;

        # 後端代理：Vertex(Gemini) 走 Node，金鑰不進瀏覽器（見 Step 4 方案 B）
        location /api/ {
            proxy_pass http://127.0.0.1:3001;
            proxy_http_version 1.1;
            proxy_set_header Host $host;
            proxy_buffering off;          # Vertex 串流(SSE)需要
            proxy_read_timeout 300s;      # AI 生成較久
        }

        # 靜態資源快取（CSS/圖片/字型；不含 .js — 見下方說明）
        location ~* \.(css|png|jpg|jpeg|gif|ico|svg|woff|woff2)$ {
            expires 7d;
            add_header Cache-Control "public, immutable";
        }

        # 主要路由：靜態檔案服務
        location / {
            limit_req zone=general burst=20 nodelay;
            try_files $uri $uri/ =404;
        }
    }
}
```

> **為什麼不擋 `weather.env.js`？** 它是前端檔，網頁用 `<script src="weather.env.js">` 載入才能取得金鑰；擋掉會讓 app 壞掉。金鑰的正確防護見 Step 4——client-side 的金鑰一定會到瀏覽器，藏不住，靠 console 網域限制。
>
> **為什麼快取不含 `.js`？** 更新頻繁時把 `.js` 設 `immutable` 會讓瀏覽器抓到舊版；穩定後再自行加回。
>
> **實測小坑（Windows）：**
> - 用 PowerShell 寫 `nginx.conf` **不要用 `Set-Content -Encoding UTF8`**（會加 BOM，nginx 報 `unknown directive "﻿worker_processes"`）。改用 `[System.IO.File]::WriteAllText($path,$text,(New-Object System.Text.UTF8Encoding($false)))`。**也不要 `Get-Content` 讀回再改寫**（中文註解會亂碼、甚至吃掉換行把指令變成註解）。建議註解用英文，整份一次覆寫。
> - `include` 相對路徑是相對 `conf/`，寫 `conf/blocklist.conf` 會找不到；寫 `blocklist.conf` 即可。

改完設定：`C:\nginx\nginx.exe -t`（測語法）→ `C:\nginx\nginx.exe -s reload`（重載）。

---

## 四、Step 3 — 建立 IP 封鎖清單

建立 `C:\nginx\conf\blocklist.conf`：

```nginx
# IP 封鎖清單 — 發現惡意 IP 就加在這裡，改後 nginx -s reload 生效
deny 192.3.130.113;
deny 45.198.224.188;
# deny 185.220.101.0/24;   # 也可封整個網段
```

---

## 五、Step 4 — API 金鑰安全（**開公網前的核心**）

`app/weather.env.js` 原本含 Vertex / Maps / Firebase / TDX / CWA 金鑰。處理原則分兩類：

| 金鑰 | 在哪執行 | 防護方式 |
|---|---|---|
| **Vertex / Gemini** | 已移到後端 | ✅ 後端代理（方案 B，**已完成**）——金鑰不進瀏覽器 |
| **Google Maps** | 瀏覽器（Maps JS） | 🔒 藏不掉 → HTTP 參照網址限制（方案 C） |
| **Firebase Web Key** | 瀏覽器（SDK） | 🔒 公開設計 → 授權網域 + Firestore 規則（方案 C） |
| TDX / CWA | 瀏覽器 | 低風險（免費額度資料 API），可暫不處理或一併限制 |

> **先破除迷思**：這是 client-side app，凡是「瀏覽器要用」的金鑰都藏不住（F12 就看得到）。唯一有效防護是**在雲端 console 限制金鑰只能從你的來源使用**，讓別人複製了也不能用。

### ✅ 方案 B：後端代理（Vertex/Gemini + TDX，**已實作＋已加固**）

`VERTEX_API_KEY` 與 `TDX_APP_ID/KEY` 皆由後端持有，前端不再帶。詳見 [server/README.md](server/README.md)。

- `server/`（Node + Express）：密鑰放 `server/.env`（gitignore）。前端打同源 `/api/vertex/*`（AI）與 `/api/tdx/*`（景點/停車場），由代理注入密鑰轉發，**永不進瀏覽器**。
- **身分驗證（2026-07 資安測試後加固）**：`/api/vertex` 需 Firebase 登入——前端夾帶 `Authorization: Bearer <idToken>`（`vertexAuthHeaders()`），代理用 firebase-admin `verifyIdToken` 驗證（服務帳戶沿用 `crawler/serviceAccount.json`），未登入/假 token 一律 **401**。防止陌生人燒 Vertex 額度。
- **速率限制**：vertex 每 IP 10 分鐘 20 次、tdx 60 次，超量回 **429**（express-rate-limit；nginx 需帶 `X-Forwarded-For`，已設）。
- **路徑白名單**：vertex 只放行實際使用的兩個 model；tdx 只放行景點/停車場兩個唯讀端點。
- **TDX 景點端點更新**：舊 `api/basic/v2/Tourism/ScenicSpot` 已退役（404），改走新 odata `V2/Tourism/Attraction`，縣市過濾用 `PostalAddress/City eq '臺X縣'`（「臺」寫法；LocatedCities 多為空勿用）。
- `app/weather.env.js` 僅剩 Maps key + Firebase config（+ `API_PROXY_BASE: '/api'`）。**crawler 的 `enrich:fees --tdx` 改用環境變數** `$env:TDX_APP_ID / $env:TDX_APP_KEY`（worker.js 本就優先讀 env）。
- nginx `location /api/`（見 Step 2）；代理只監聽 `127.0.0.1`、登入自啟。
- 啟動：`cd server; npm install; npm start`（常駐見 Step 7）。
- 實測（2026-07-05）：未登入 401 ✓、假 token 401 ✓、登入後 200 ✓、連發第 21 次起 429 ✓、白名單外路徑 403 ✓。

### ✅ 方案 C：雲端 console 限制金鑰（**逐步，必做**）

你的專案 ID：`project-720a680b-3ad1-40d1-b07`。以下用固定 IP `210.240.160.150` 當來源；若之後加了 SSL（Step 5），把 `https://travel-link-ai.duckdns.org/*` 一併加入。

#### C-1. Google Maps 金鑰（前端用，最需要限制）

1. 前往 **Google Cloud Console → API 和服務 → 憑證**：https://console.cloud.google.com/apis/credentials
2. 上方確認專案是 `project-720a680b-3ad1-40d1-b07`。
3. 點開名為 Maps 用途的那把 API key（值以 `AIzaSyDJ47...` 開頭）。
4. **「應用程式限制」** 選 **「HTTP 參照網址（網站）」**，在「網站限制」逐條新增：
   - `http://210.240.160.150/*`
   - `http://210.240.160.150`（有些情況不帶路徑的也要）
   - `https://travel-link-ai.duckdns.org/*`（**若已做 Step 5 的 SSL**）
   - `http://localhost/*`（本機開發用）
5. **「API 限制」** 選 **「限制金鑰」**，只勾這個 app 真正用到的：
   - **Maps JavaScript API**
   - **Places API (New)**
   （其餘一律不勾，減少被盜用面。）
6. 按 **儲存**。生效約 1–5 分鐘。
7. 驗證：從**別的網域/直接貼金鑰**呼叫應被拒（`RefererNotAllowedMapError`）；你的站正常。

#### C-2. Vertex / Gemini 金鑰（已在後端，補強即可）

因為已改後端代理，這把金鑰**不在瀏覽器**、伺服器對 Google 的呼叫也**沒有 referrer**，所以 HTTP 參照網址限制不適用。改用：

1. Cloud Console → 憑證 → 點開 Vertex 那把 key（值以 `AQ.Ab8...` 開頭）。
2. **「API 限制」** 選「限制金鑰」，只勾 **Vertex AI API**。
3. （進階）**「應用程式限制」** 可選 **「IP 位址」**，填你的伺服器出口 IP `210.240.160.150`，這樣就算金鑰外洩，也只有從這台機器才能用。
   - ⚠️ **雙棧 IPv6 坑（2026-07 實際踩過）**：本機同時有 IPv6，Node 對 Google 的出站連線**預設優先走 IPv6**（且 Windows IPv6 是會輪換的臨時隱私位址），會被白名單擋下 `403 API_KEY_IP_ADDRESS_BLOCKED`。解法＝[server/server.js](server/server.js) 開頭的 `require('dns').setDefaultResultOrder('ipv4first')` 強制走固定 IPv4（**已加**）。別把 IPv6 加進白名單——它會一直變。
4. 金鑰只放 `server/.env`（已 gitignore），**永不進前端、不進版控**。
5. （建議）到 **Cloud Console → 帳單 → 預算與快訊** 設一個每月預算上限與 email 快訊，萬一被盜刷可及早發現。

#### C-3. Firebase（授權網域 + 資料規則）

Firebase Web API key（`FIREBASE_CONFIG.apiKey`，`AIzaSyDp9...`）本質是識別碼、Google 官方說可公開，真正的防護在下面兩項：

1. **授權網域**：Firebase Console → 你的專案 → **Authentication → Settings → 授權網域（Authorized domains）** → 新增 `210.240.160.150`。沒加的話登入會被擋。
   - 連結：https://console.firebase.google.com/project/project-720a680b-3ad1-40d1-b07/authentication/settings
   - ⚠️ **裸 IP 的坑**：授權網域主要管 **OAuth 彈窗登入（Google 登入）**。有些專案**不接受裸 IP**、或 Google 彈窗從 IP 開會失敗。若遇到，**Email/密碼登入不受影響**照常可用；要讓 Google 登入也能用，就得用網域——**這也是建議做 DuckDNS 的原因**：改加 `travel-link-ai.duckdns.org` 到授權網域（配合 Step 5 的 SSL）。
2. **Firestore 安全規則**：這是資料庫的真正門鎖。[firestore.rules](firestore.rules) 已限制「只有登入者/資料擁有者可讀寫」，**已於 2026-07 部署並實測通過**（匿名寫入、跨使用者讀取皆被擋；本人讀寫、訪客讀分享行程正常）。更新規則：Console → Firestore → 規則，或 `firebase deploy --only firestore:rules`。**別讓規則退回「任何人可讀寫」的預設。**
3. （選配）Cloud Console → 憑證 → 這把 Browser key → 「API 限制」只勾 Firebase 用到的（Identity Toolkit API、Token Service API、Cloud Firestore API）。

#### C-4. TDX / CWA（低風險，選配）

TDX（`TDX_APP_ID`/`TDX_APP_KEY`）與 CWA（`CWA_API_KEY`）是免費額度的開放資料 API，被盜用頂多耗你的免費配額、無帳單風險。要收斂的話，最徹底是比照 Vertex 也走後端代理；否則維持現狀即可。

> **金鑰輪替建議**：Vertex 金鑰先前曾放在前端檔（雖 gitignore、站台也還沒公開），保險起見可在 Cloud Console **重新產生一把**、舊的作廢，再填進 `server/.env`——反正前端已不需要它。

---

## 六、Step 5 — 加入 SSL（HTTPS）

直接對外**強烈建議加 HTTPS**，否則登入資料與 Firebase/Maps 往來都是明文。

> **純 IP 的難題**：**Let's Encrypt 不核發憑證給裸 IP**，`https://210.240.160.150` 這條免費路走不通。三個選項：

| 方案 | 結果 |
|---|---|
| HTTP only（不加） | 能動，但明文傳輸，不建議對公網 |
| **免費 DuckDNS 主機名指向你的固定 IP**（推薦） | 到 https://www.duckdns.org 用 GitHub/Google 登入，建一個 `travel-link-ai.duckdns.org`、IP 欄位填 `210.240.160.150`。**這樣就能對 `travel-link-ai.duckdns.org` 申請 Let's Encrypt 免費 SSL**（等於「無網域也有 HTTPS」）。這只是拿它當一個固定主機名，不是動態 DDNS。 |
| 自簽憑證 | 能加密，但瀏覽器每次跳「不安全」警告 |

### 用 win-acme 簽免費憑證（走 DuckDNS 主機名）

1. 先完成上面 DuckDNS：`travel-link-ai.duckdns.org` → `210.240.160.150`，並確認 `http://travel-link-ai.duckdns.org/` 能連到你的站（需先開 Step 6 的防火牆 80）。
2. 下載 win-acme：https://www.win-acme.com/ ，解壓後執行 `.\wacs.exe`。
3. 依互動選單：網域填 `travel-link-ai.duckdns.org`、驗證方式選 **HTTP-01**（需要 Port 80 開放）→ 自動產生憑證。
4. 更新 `nginx.conf` 加 SSL server 區塊（把憑證路徑換成 win-acme 產生的位置）：

```nginx
    server {
        listen       443 ssl;
        server_name  travel-link-ai.duckdns.org;

        ssl_certificate      "C:/nginx/ssl/fullchain.pem";
        ssl_certificate_key  "C:/nginx/ssl/privkey.pem";
        ssl_protocols        TLSv1.2 TLSv1.3;
        ssl_ciphers          HIGH:!aNULL:!MD5;

        root  "C:/Users/USER/Desktop/UIUX/app";
        index ai-travel-explore-final.html;
        # ...（其餘 location / add_header / limit_req 同 Port 80 那份）...
    }

    # HTTP 自動跳轉 HTTPS
    server {
        listen 80;
        server_name travel-link-ai.duckdns.org 210.240.160.150;
        return 301 https://travel-link-ai.duckdns.org$request_uri;
    }
```

5. `nginx -t` → `nginx -s reload`。之後把 Step 4 的金鑰限制與 Firebase 授權網域補上 `https://travel-link-ai.duckdns.org/*`。

> **✅ 已於 2026-07 完成**：憑證簽發成功（win-acme 2.2.9，PEM 匯出至 `C:\nginx\ssl`），nginx 443 已上線、HTTP 一律 301 轉 HTTPS，`/api/` 代理走 HTTPS 實測正常。
>
> **自動續期 + 自動 reload（已設定）**：win-acme 執行時**沒有系統管理員權限**，導致它自己的續期排程任務沒建成功（只留下 `C:\ProgramData\win-acme\...renewal.json` 續期設定）。改用自建方案：
> - [C:\nginx\renew-cert.bat](C:/nginx/renew-cert.bat)：跑 `wacs.exe --renew`（只在到期前 ~55 天才真的續）後 `nginx -s reload`（載入新憑證；每日優雅重載無停機）。
> - 由系統管理員註冊的排程任務 **`WanderAI Cert Renew`**（每日 03:00、以 SYSTEM 執行）呼叫上述 bat。
> - 註冊指令（系統管理員 PowerShell 跑一次）：
>   ```powershell
>   $action  = New-ScheduledTaskAction -Execute "C:\nginx\renew-cert.bat"
>   $trigger = New-ScheduledTaskTrigger -Daily -At 3:00AM
>   $settings = New-ScheduledTaskSettingsSet -StartWhenAvailable
>   Register-ScheduledTask -TaskName "WanderAI Cert Renew" -Action $action -Trigger $trigger -Settings $settings -User "SYSTEM" -RunLevel Highest -Force
>   ```
> - 手動測試：`Start-ScheduledTask -TaskName "WanderAI Cert Renew"`（應為 no-op 續期檢查 + reload，HTTPS 仍正常）。
>
> **驗證方式備忘**：HTTP-01 走「Save verification files on path」寫入 `app\.well-known\acme-challenge\`；nginx 對該路徑已設例外（放行於「擋點開頭檔」規則之前），port 80 的 server 保留此 location 供續期使用，其餘一律 301 轉 HTTPS。

---

## 七、Step 6 — 開 Windows 防火牆（對外開關）＋ 常駐

### 6.1 開防火牆（需系統管理員 PowerShell）

> ⚠️ 執行這步 = 站台正式對公網開放。**請先完成 Step 4 的金鑰限制**。

```powershell
New-NetFirewallRule -DisplayName "WanderAI HTTP"  -Direction Inbound -Protocol TCP -LocalPort 80  -Action Allow
New-NetFirewallRule -DisplayName "WanderAI HTTPS" -Direction Inbound -Protocol TCP -LocalPort 443 -Action Allow   # 有 SSL 才需要

# 確認沒有其他不必要的對外開放
Get-NetFirewallRule -Direction Inbound -Action Allow | Select-Object DisplayName, Enabled
```

> 只想給特定人看？加 `-RemoteAddress <對方IP或網段>` 限制來源，比全公網安全。

**開完立刻從外部實測**：用手機**關 Wi-Fi、走行動網路**開 `http://210.240.160.150/`（或 `https://travel-link-ai.duckdns.org/`）。連不進來先想 TANet 上游防火牆（見 Step 1）。

### 6.2 常駐（開機/登入自動啟動）

本專案有兩個要常駐的服務：**nginx** 與 **Node 代理**。

**目前已設定（不需系統管理員）**：登入時自動啟動，放在使用者啟動資料夾：
- `WanderAI-nginx-autostart.vbs` → 跑 `C:\nginx\autostart-nginx.bat`
- `WanderAI-proxy-autostart.vbs` → 跑 `C:\Users\USER\Desktop\UIUX\server\start-proxy.bat`
（兩支 bat 都會先檢查是否已在跑，未跑才啟動，避免重複。）

**若要「開機即啟」（登入前就跑）** → 用系統管理員把它們註冊成 Windows 服務（nssm）：

```powershell
choco install nssm -y                                   # 需系統管理員
# nginx
C:\nginx\nginx.exe -s stop
nssm install nginx C:\nginx\nginx.exe
nssm set nginx AppDirectory C:\nginx
nssm start nginx
# Node 代理
nssm install wanderai-proxy "C:\Program Files\nodejs\node.exe" "C:\Users\USER\Desktop\UIUX\server\server.js"
nssm set wanderai-proxy AppDirectory "C:\Users\USER\Desktop\UIUX\server"
nssm start wanderai-proxy
```

改用服務後，把兩支啟動資料夾的 VBS 刪掉，避免重複啟動：

```powershell
$s = [Environment]::GetFolderPath('Startup')
Remove-Item "$s\WanderAI-nginx-autostart.vbs","$s\WanderAI-proxy-autostart.vbs" -ErrorAction SilentlyContinue
```

---

## 八、安全防護總整理

### 已啟用（nginx.conf / 架構）

| # | 防護 | 作用 |
|---|------|------|
| 1 | `root=app/` | 專案敏感檔（.claude / crawler / .git / *.md）天然不在網站根目錄，一律 404 |
| 2 | IP 封鎖清單（`blocklist.conf`） | 拒絕已知惡意 IP |
| 3 | 隱藏檔封鎖（`location ~ /\.`） | 點開頭檔案回 404（防呆） |
| 4 | 速率限制（`limit_req`） | 每 IP 每秒最多 10 次，防掃描/DDoS |
| 5 | 隱藏版本號（`server_tokens off`） | 不洩漏 nginx 版本 |
| 6 | 安全標頭 | 防點擊劫持 / MIME 嗅探 |
| 7 | Body 大小限制（`client_max_body_size`） | 防大檔攻擊 |
| 8 | Vertex 後端代理 | 最貴的金鑰不進瀏覽器 |
| 9 | 金鑰網域/IP 限制（方案 C） | Maps/Firebase 金鑰被複製也不能用 |

### 額外建議

| # | 防護 | 做法 |
|---|------|------|
| 10 | Windows 防火牆 | 只開 80/443，其餘全擋（Step 6） |
| 11 | 定期看 log | 每週 `C:\nginx\logs\access.log`，新惡意 IP 加入封鎖清單 |
| 12 | 自動封鎖腳本 | 見 Step 9 |
| 13 | 帳單快訊 | Cloud Console 設每月預算上限 + email 快訊 |

---

## 九、（進階）自動封鎖惡意 IP 腳本

建立 `C:\nginx\scripts\auto-block.ps1`，定期掃 log 封鎖高頻 IP：

```powershell
# auto-block.ps1 — 封鎖每分鐘超過 60 次請求的 IP
$logFile = "C:\nginx\logs\access.log"
$blockFile = "C:\nginx\conf\blocklist.conf"
$threshold = 60

$cutoff = (Get-Date).AddMinutes(-1).ToString("dd/MMM/yyyy:HH:mm")
$lines = Get-Content $logFile -Tail 5000 | Where-Object { $_ -match $cutoff }

$ipCounts = @{}
foreach ($line in $lines) {
    if ($line -match "^([\d\.]+)") {
        $ip = $matches[1]
        if (-not $ipCounts.ContainsKey($ip)) { $ipCounts[$ip] = 0 }
        $ipCounts[$ip]++
    }
}

$existing = Get-Content $blockFile -ErrorAction SilentlyContinue
$added = 0
foreach ($ip in $ipCounts.Keys) {
    if ($ipCounts[$ip] -gt $threshold) {
        $denyLine = "deny $ip;"
        if ($existing -notcontains $denyLine) {
            Add-Content $blockFile "`n# $(Get-Date -Format 'yyyy-MM-dd HH:mm') auto-blocked ($($ipCounts[$ip]) req/min)"
            Add-Content $blockFile $denyLine
            $added++
        }
    }
}
if ($added -gt 0) { & C:\nginx\nginx.exe -s reload }
```

排程每 5 分鐘執行（系統管理員 PowerShell）：

```powershell
$action  = New-ScheduledTaskAction -Execute "powershell.exe" -Argument "-File C:\nginx\scripts\auto-block.ps1"
$trigger = New-ScheduledTaskTrigger -RepetitionInterval (New-TimeSpan -Minutes 5) -Once -At (Get-Date)
Register-ScheduledTask -TaskName "Nginx Auto Block" -Action $action -Trigger $trigger -RunLevel Highest
```

---

## 十、部署後驗證清單

```
□ 1. http://210.240.160.150/ 能正常顯示首頁
□ 2. https://travel-link-ai.duckdns.org/ 能正常顯示（如有設 SSL）
□ 3. /weather.env.js → 回 200，且內容「不含」VERTEX_API_KEY（已移到後端）
□ 4. /crawler/serviceAccount.json → 回 404（Firebase 管理員金鑰不外洩＝最關鍵）
□ 5. /../CLAUDE.md、/../firestore.rules → 回 404 / 400（專案檔不外洩）
□ 6. /api/health → 回 {"ok":true}（Node 代理正常）
□ 7. Google Maps 金鑰已設 HTTP 參照網址限制（方案 C-1）
□ 8. Vertex 金鑰已設 API/IP 限制、只在 server/.env（方案 C-2）
□ 9. Firebase 授權網域已加 IP、Firestore 規則已限制（方案 C-3）
□ 10. Google Maps 地圖正常顯示、Firebase 登入正常、AI 行程生成正常
□ 11. 手機（行動網路）可正常開啟
```

> 3~6 可本機快速驗證（nginx + 代理啟動後）：
> ```powershell
> function Probe($p){ try{(Invoke-WebRequest "http://localhost$p" -UseBasicParsing).StatusCode}catch{[int]$_.Exception.Response.StatusCode} }
> Probe '/'                              # 200
> Probe '/weather.env.js'               # 200（且內容不含金鑰值）
> Probe '/crawler/serviceAccount.json'  # 404
> Probe '/../CLAUDE.md'                  # 400/404
> Probe '/api/health'                   # 200
> ```

---

## 十一、快速指令參考

```powershell
# Nginx
cd C:\nginx
.\nginx.exe              # 啟動
.\nginx.exe -s stop      # 停止
.\nginx.exe -s reload    # 重載設定（改 conf 後）
.\nginx.exe -t           # 測試設定檔語法

# Node 代理
cd C:\Users\USER\Desktop\UIUX\server
npm start                # 啟動（127.0.0.1:3001）

# Log
Get-Content C:\nginx\logs\access.log -Tail 50
Get-Content C:\nginx\logs\error.log  -Tail 20

# 連線 / 埠
netstat -an | findstr ":80"
netstat -an | findstr ":3001"
```

---

## 附錄：第二個網站 travel-link-amd.duckdns.org（InnoServe AMD 版，2026-10-02）

同一台機器、同一個 IP，nginx 依網域分流；舊網站完全不動。

| | 舊網站 | 新網站（AMD） |
|---|---|---|
| 網域 | `travel-link-ai.duckdns.org` | `travel-link-amd.duckdns.org` |
| 前端 | `Desktop\UIUX\app` | `Desktop\TravelLink_AI-AMD\app` |
| 代理 | `127.0.0.1:3001`（`UIUX\server`） | `127.0.0.1:3011`（`TravelLink_AI-AMD\server`，`.env` 的 `PORT=3011`） |
| 文字 AI | Vertex / Gemini | `AI_PROVIDER=amd`：工研院 gpt-oss-120b（圖片仍走 Vertex） |
| 憑證 | `C:\nginx\ssl\travel-link-ai.duckdns.org-*.pem` | `C:\nginx\ssl\travel-link-amd.duckdns.org-*.pem` |

- **nginx**：`C:\nginx\conf\nginx.conf` 末尾新增兩個 server（80 保留 ACME 路徑後 301、443 正站），並加了 `server_names_hash_bucket_size 64;`（兩個長網域，預設 32 放不下）。改設定前的備份是 `nginx.conf.bak-20261002-202912`。
- **憑證**：win-acme 同一套參數（manual、HTTP-01 FileSystem、webroot 仍是 `UIUX\app`、PEM 匯出到 `C:\nginx\ssl`）。既有的 `WanderAI Cert Renew` 排程跑 `wacs --renew` 會續**所有**憑證，新網域不用另外設。
  - ⚠️ 兩個網域的續期驗證檔都寫到 `UIUX\app\.well-known\`，所以兩個 port 80 server 的 root 都要維持 `UIUX\app`。
- **代理常駐**：`TravelLink_AI-AMD\server\start-proxy.bat`（3011，路徑取自腳本位置）。開機自動啟動要另外註冊排程（系統管理員 PowerShell）：
  ```powershell
  $action  = New-ScheduledTaskAction -Execute "C:\Users\USER\Desktop\TravelLink_AI-AMD\server\start-proxy.bat"
  $trigger = New-ScheduledTaskTrigger -AtLogOn -User "$env:USERDOMAIN\$env:USERNAME"
  Register-ScheduledTask -TaskName "TravelLinkAI-AMD Proxy Autostart" -Action $action -Trigger $trigger -Force
  ```
- **控制台**：`TravelLink_AI-AMD\server\proxy-control.bat` 管 3011；舊網站的代理用 `UIUX\server` 那一份。
- **AMD 端點備援**：`server/.env` 設了 `AMD_LLM_FALLBACK_BASE_URL`／`AMD_LLM_FALLBACK_API_KEY`（組員 VM 的反向代理，網址與金鑰不進 repo）時，直連工研院連不上或回 401/403 會**自動**改走備援，5 分鐘後再試直連，切換與恢復都會寫進 `server/logs/proxy.out.log`／`proxy.err.log`。改 `.env` 後要重啟 3011 代理。測試：`node server/test/amd-failover.test.js`（本機假伺服器，不打真端點）。
- **外部設定（Console）**：Firebase Auth 授權網域、Google Maps 瀏覽器金鑰的 HTTP referrer 都要加上 `travel-link-amd.duckdns.org`。工研院端點看的是 IP，不用改。
