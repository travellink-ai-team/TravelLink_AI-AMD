# 後端代理控制台（WinForms GUI）
# 用途：不用打指令就能查看／啟動／停止／重啟 server.js（Vertex 代理 + 停車回報端點）。
# 執行：雙擊 proxy-control.bat，或 powershell -ExecutionPolicy Bypass -File proxy-control.ps1

# Invoke-WebRequest 會在主控台畫進度列（「讀取 Web 回應…」）。健康檢查每 3 秒跑一次，
# 那行字就會不停閃。這裡把進度串流關掉——它是狀態列，不是錯誤訊息，關掉不影響結果。
$ProgressPreference = 'SilentlyContinue'

Add-Type -AssemblyName System.Windows.Forms
Add-Type -AssemblyName System.Drawing
[System.Windows.Forms.Application]::EnableVisualStyles()

# 藏起 powershell 的黑底主控台視窗（真正要看的是下面那個 GUI）。
# 刻意等到 ShowDialog 之前才藏（見檔尾）：若腳本在那之前就掛了，
# 錯誤訊息還留在主控台上看得到，不會變成「雙擊沒反應」。
Add-Type -Namespace WinConsole -Name Native -MemberDefinition @'
[DllImport("kernel32.dll")] public static extern IntPtr GetConsoleWindow();
[DllImport("user32.dll")] public static extern bool ShowWindow(IntPtr hWnd, int nCmdShow);
'@

$ServerDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$LogDir    = Join-Path $ServerDir 'logs'
$OutLog    = Join-Path $LogDir 'proxy.out.log'
$ErrLog    = Join-Path $LogDir 'proxy.err.log'
$UiLog     = Join-Path $LogDir 'control-ui.log'   # 控制台自己的操作／錯誤記錄
$Port      = 3001
$HealthUrl = "http://127.0.0.1:$Port/api/health"

if (-not (Test-Path $LogDir)) { New-Item -ItemType Directory -Path $LogDir | Out-Null }

# ── 狀態查詢 ────────────────────────────────────────────────
# 監聽 3001 的 PID。server.js 綁 127.0.0.1，所以只看本機回環位址。
function Get-ProxyPid {
  try {
    $conn = Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction Stop |
            Select-Object -First 1
    if ($conn) { return [int]$conn.OwningProcess }
  } catch {
    # 舊系統沒有 Get-NetTCPConnection 時退回 netstat
    $line = netstat -ano | Select-String ":$Port\s" | Select-String 'LISTENING' | Select-Object -First 1
    if ($line) { return [int]($line.ToString().Trim() -split '\s+')[-1] }
  }
  return 0
}

# 健康檢查。回 $true 只代表 /api/health 回 ok，不代表 Firestore 憑證正常。
function Test-ProxyHealth {
  try {
    $r = Invoke-WebRequest -Uri $HealthUrl -TimeoutSec 2 -UseBasicParsing -ErrorAction Stop
    return ($r.StatusCode -eq 200)
  } catch { return $false }
}

# ── 動作 ────────────────────────────────────────────────────
function Start-Proxy {
  if ((Get-ProxyPid) -ne 0) { Write-Log '已經在執行中，略過啟動。'; return }
  Write-Log '啟動中…'
  <#  改為呼叫 start-proxy.bat，不要在這裡另寫一份啟動邏輯：
      - 開機自動啟動的工作排程跑的就是那支，兩邊共用同一份才不會各自壞掉
      - 它用 >> 附加寫記錄；原本的 Start-Process -RedirectStandard* 每次啟動都會
        「截斷」記錄檔，等於把上一次當掉的證據清乾淨——記錄檔正是為了那個而存在的
      - 它本來就有「已在監聽就跳過」的判斷，重複觸發是安全的  #>
  try {
    Start-Process -FilePath (Join-Path $ServerDir 'start-proxy.bat') `
      -WorkingDirectory $ServerDir -WindowStyle Hidden -ErrorAction Stop | Out-Null
  } catch {
    Write-Log "啟動失敗：$($_.Exception.Message)"
    return
  }
  # 等健康檢查通過（最多 10 秒）；node 起來到能接請求之間有空窗。
  for ($i = 0; $i -lt 20; $i++) {
    Start-Sleep -Milliseconds 500
    if (Test-ProxyHealth) { Write-Log "已啟動（PID $(Get-ProxyPid)）。"; return }
  }
  Write-Log '啟動逾時：process 可能已退出，請看下方錯誤記錄。'
}

function Stop-Proxy {
  $procId = Get-ProxyPid
  if ($procId -eq 0) { Write-Log '目前沒有在執行。'; return }
  Write-Log "停止中（PID $procId）…"
  try {
    Stop-Process -Id $procId -Force -ErrorAction Stop
    Start-Sleep -Milliseconds 600
    Write-Log '已停止。'
  } catch {
    Write-Log "停止失敗：$($_.Exception.Message)"
  }
}

function Restart-Proxy {
  Write-Log '── 重啟 ──'
  Stop-Proxy
  Start-Proxy
}

# ── UI ──────────────────────────────────────────────────────
$form = New-Object System.Windows.Forms.Form
$form.Text = 'TravelLinkAI 後端代理控制台'
$form.Size = New-Object System.Drawing.Size(560, 460)
$form.StartPosition = 'CenterScreen'
$form.BackColor = [System.Drawing.Color]::FromArgb(248, 249, 251)
$form.Font = New-Object System.Drawing.Font('Microsoft JhengHei UI', 9)

$card = New-Object System.Windows.Forms.Panel
$card.Location = New-Object System.Drawing.Point(16, 14)
$card.Size = New-Object System.Drawing.Size(510, 92)
$card.BackColor = [System.Drawing.Color]::White
$card.BorderStyle = 'FixedSingle'
$form.Controls.Add($card)

$dot = New-Object System.Windows.Forms.Label
$dot.Location = New-Object System.Drawing.Point(18, 22)
$dot.Size = New-Object System.Drawing.Size(22, 22)
$dot.Text = '●'
$dot.Font = New-Object System.Drawing.Font('Segoe UI', 16)
$dot.ForeColor = [System.Drawing.Color]::Gray
$card.Controls.Add($dot)

$lblState = New-Object System.Windows.Forms.Label
$lblState.Location = New-Object System.Drawing.Point(46, 18)
$lblState.Size = New-Object System.Drawing.Size(440, 26)
$lblState.Text = '偵測中…'
$lblState.Font = New-Object System.Drawing.Font('Microsoft JhengHei UI', 13, [System.Drawing.FontStyle]::Bold)
$card.Controls.Add($lblState)

$lblMeta = New-Object System.Windows.Forms.Label
$lblMeta.Location = New-Object System.Drawing.Point(48, 50)
$lblMeta.Size = New-Object System.Drawing.Size(440, 34)
$lblMeta.ForeColor = [System.Drawing.Color]::FromArgb(90, 96, 106)
$lblMeta.Text = ''
$card.Controls.Add($lblMeta)

function New-Btn($text, $x, $w, $accent) {
  $b = New-Object System.Windows.Forms.Button
  $b.Text = $text
  $b.Location = New-Object System.Drawing.Point($x, 118)
  $b.Size = New-Object System.Drawing.Size($w, 40)
  $b.FlatStyle = 'Flat'
  $b.FlatAppearance.BorderSize = 0
  $b.Font = New-Object System.Drawing.Font('Microsoft JhengHei UI', 10, [System.Drawing.FontStyle]::Bold)
  if ($accent) {
    $b.BackColor = [System.Drawing.Color]::FromArgb(37, 99, 235)
    $b.ForeColor = [System.Drawing.Color]::White
  } else {
    $b.BackColor = [System.Drawing.Color]::FromArgb(233, 236, 241)
    $b.ForeColor = [System.Drawing.Color]::FromArgb(30, 35, 45)
  }
  $form.Controls.Add($b)
  return $b
}

# 按鈕文字不放彩色 emoji：WinForms 的預設字型畫不出來，會變成一個個豆腐方框。
# ▶ ■ 這類幾何符號（U+25B6 / U+25A0）在 Microsoft JhengHei UI 裡有字，可以用。
$btnRestart = New-Btn '重新啟動' 16 150 $true
$btnStart   = New-Btn '▶ 啟動' 176 118 $false
$btnStop    = New-Btn '■ 停止' 302 118 $false
$btnLog     = New-Btn '檢視記錄' 428 98 $false

$logBox = New-Object System.Windows.Forms.TextBox
$logBox.Location = New-Object System.Drawing.Point(16, 172)
$logBox.Size = New-Object System.Drawing.Size(510, 234)
$logBox.Multiline = $true
$logBox.ScrollBars = 'Vertical'
$logBox.ReadOnly = $true
$logBox.BackColor = [System.Drawing.Color]::FromArgb(24, 27, 33)
$logBox.ForeColor = [System.Drawing.Color]::FromArgb(214, 220, 230)
$logBox.Font = New-Object System.Drawing.Font('Consolas', 9)
$logBox.BorderStyle = 'FixedSingle'
# 開場訊息直接設在控制項上，不靠 Shown 事件——事件裡呼叫 AppendText
# 曾經整段沒出現（視窗還沒建好 handle），改成建構期指定就一定看得到。
$logBox.Text = "工作目錄：$ServerDir`r`n連接埠：$Port（僅 127.0.0.1）`r`n關閉本視窗不會停掉代理。`r`n"
$form.Controls.Add($logBox)

function Write-Log($msg) {
  $line = "[{0}] {1}" -f (Get-Date -Format 'HH:mm:ss'), $msg
  $logBox.AppendText($line + "`r`n")
  # 同時落地。畫面上的記錄關掉視窗就沒了，出事時要回頭查得到。
  try { Add-Content -Path $UiLog -Value $line -Encoding UTF8 -ErrorAction Stop } catch {}
  [System.Windows.Forms.Application]::DoEvents()
}

<#  按鈕動作期間鎖住 UI，避免重複點擊造成兩個 node 搶同一個 port。
    ⚠ try/catch 是必要的，不是保險：PowerShell 的 WinForms 事件處理常式若丟例外，
      例外會寫到錯誤串流然後被吃掉——而主控台已經藏起來，於是按鈕按下去毫無反應、
      記錄也沒有半行，完全查不到原因（這個控制台第一版就是這樣壞掉的）。
      這裡把例外抓下來寫進畫面與檔案，讓失敗看得見。 #>
function Invoke-Guarded($action, $label) {
  $btnRestart.Enabled = $false; $btnStart.Enabled = $false; $btnStop.Enabled = $false
  try {
    & $action
  } catch {
    Write-Log ("『$label』發生例外：" + $_.Exception.Message)
    Write-Log ("  位置：" + $_.InvocationInfo.PositionMessage.Trim())
  } finally {
    $btnRestart.Enabled = $true; $btnStart.Enabled = $true; $btnStop.Enabled = $true
    try { Update-Status } catch { Write-Log ('狀態更新失敗：' + $_.Exception.Message) }
  }
}

# 動作直接寫在處理常式裡，不再包一層 scriptblock 參數——少一層間接就少一處
# 可能出錯又看不見的地方。
$btnRestart.Add_Click({ Invoke-Guarded { Restart-Proxy } '重新啟動' })
$btnStart.Add_Click({   Invoke-Guarded { Start-Proxy } '啟動' })
$btnStop.Add_Click({    Invoke-Guarded { Stop-Proxy } '停止' })
$btnLog.Add_Click({
  # 錯誤記錄優先——會來看記錄多半是出事了
  if ((Test-Path $ErrLog) -and (Get-Item $ErrLog).Length -gt 0) { Start-Process notepad.exe $ErrLog }
  elseif (Test-Path $OutLog) { Start-Process notepad.exe $OutLog }
  else { Write-Log '尚無記錄檔（代理還沒從這個控制台啟動過）。' }
})

function Update-Status {
  $procId = Get-ProxyPid
  if ($procId -eq 0) {
    $dot.ForeColor = [System.Drawing.Color]::FromArgb(200, 60, 60)
    $lblState.Text = '未執行'
    $lblMeta.Text  = "連接埠 $Port 沒有程序在監聽。前端的 /api/* 呼叫會全部失敗。"
  } elseif (Test-ProxyHealth) {
    $dot.ForeColor = [System.Drawing.Color]::FromArgb(34, 160, 90)
    $lblState.Text = '執行中'
    $lblMeta.Text  = "PID $procId ・ 127.0.0.1:$Port ・ /api/health 正常回應"
  } else {
    $dot.ForeColor = [System.Drawing.Color]::FromArgb(220, 150, 30)
    $lblState.Text = '有監聽但無回應'
    $lblMeta.Text  = "PID $procId 佔用連接埠 $Port，但 /api/health 沒回應——可能卡住或不是這支程式。"
  }
}

$timer = New-Object System.Windows.Forms.Timer
$timer.Interval = 3000
$timer.Add_Tick({ Update-Status })
$timer.Start()

$form.Add_Shown({
  Update-Status
  $form.Activate()
})
# 關掉視窗不會停掉代理——這是刻意的，代理要在背景繼續服務網站。
$form.Add_FormClosing({ $timer.Stop() })

# 走到這裡代表 GUI 建好了，主控台已無用處——藏起來（0 = SW_HIDE）
try { [void][WinConsole.Native]::ShowWindow([WinConsole.Native]::GetConsoleWindow(), 0) } catch {}

[void]$form.ShowDialog()
