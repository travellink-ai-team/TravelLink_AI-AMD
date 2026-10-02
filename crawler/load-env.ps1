# 把爬蟲需要的環境變數載進「目前這個 PowerShell session」。
#
# 為什麼需要這支：直接跑 `.\crawler-key.local.bat` 沒有用——
# PowerShell 執行 .bat 會另開一個 cmd.exe 子行程，裡面 `set` 的變數
# 只活在那個子行程，回不到 PowerShell。於是接著跑 npm 時環境變數是空的，
# 爬蟲就退回讀 app/weather.env.js 的瀏覽器金鑰（有 HTTP referrer 限制，
# 伺服器端一律 403 API_KEY_HTTP_REFERRER_BLOCKED）。
#
# 用法（注意開頭那個點和空格，這叫 dot-sourcing，少了就同樣沒效果）：
#
#     cd C:\Users\USER\Desktop\UIUX\crawler
#     . .\load-env.ps1
#     npm run crawl:food:dry
#
# 本檔不含任何金鑰，金鑰仍然只在 gitignore 的 crawler-key.local.bat 裡。

$ErrorActionPreference = 'Stop'
$here = Split-Path -Parent $MyInvocation.MyCommand.Path

# 1) 從 crawler-key.local.bat 解析 set "NAME=VALUE"
$batPath = Join-Path $here 'crawler-key.local.bat'
if (Test-Path $batPath) {
    $loaded = @()
    Get-Content $batPath | ForEach-Object {
        if ($_ -match '^\s*set\s+"?([A-Za-z_][A-Za-z0-9_]*)=([^"]*)"?\s*$') {
            [Environment]::SetEnvironmentVariable($Matches[1], $Matches[2], 'Process')
            $loaded += $Matches[1]
        }
    }
    if ($loaded.Count) {
        Write-Host ("[load-env] 已載入：" + ($loaded -join ', ')) -ForegroundColor Green
    } else {
        Write-Host "[load-env] crawler-key.local.bat 裡沒有解析到 set 指令" -ForegroundColor Yellow
    }
} else {
    Write-Host "[load-env] 找不到 crawler-key.local.bat（伺服器端金鑰）" -ForegroundColor Yellow
}

# 2) Firebase 服務帳戶：沒設的話 worker.js 會直接中止
$svc = Join-Path $here 'serviceAccount.json'
if (Test-Path $svc) {
    $env:FIREBASE_SERVICE_ACCOUNT_PATH = $svc
    Write-Host "[load-env] FIREBASE_SERVICE_ACCOUNT_PATH 已設定" -ForegroundColor Green
} else {
    Write-Host "[load-env] 找不到 serviceAccount.json" -ForegroundColor Yellow
}

# 3) 確認到底會用哪一支金鑰——這正是先前踩到的坑
if ($env:GOOGLE_MAPS_API_KEY) {
    $p = $env:GOOGLE_MAPS_API_KEY.Substring(0, [Math]::Min(8, $env:GOOGLE_MAPS_API_KEY.Length))
    Write-Host "[load-env] GOOGLE_MAPS_API_KEY = $p...（爬蟲會優先用這支，而不是 weather.env.js）" -ForegroundColor Cyan
} else {
    Write-Host "[load-env] 警告：GOOGLE_MAPS_API_KEY 未設定，爬蟲會退回 weather.env.js 的瀏覽器金鑰並被 403 擋下" -ForegroundColor Red
}
