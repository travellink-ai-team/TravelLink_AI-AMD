import sys

path = r'c:\Users\Tim lin\Desktop\TEST\ai-travel-explore-final.html'
with open(path, 'r', encoding='utf-8', newline='') as f:
    content = f.read()

NL = '\r\n'

# ── 1. CSS ──────────────────────────────────────────────────────────────
css_marker = '.wizard-badge{background:rgba(255,255,255,.76);border:1px solid rgba(255,255,255,.7);box-shadow:0 8px 22px rgba(25,32,45,.08);border-radius:999px;padding:8px 14px;font-weight:700;color:#1d5f5b;font-size:14px;backdrop-filter:blur(8px);white-space:nowrap;}'
css_lines = [
    '#genOverlay{position:fixed;inset:0;background:rgba(18,24,32,.97);z-index:9999;display:flex;align-items:center;justify-content:center;padding:24px;}',
    '.gen-container{width:100%;max-width:620px;display:flex;flex-direction:column;gap:20px;}',
    '.gen-header{text-align:center;}',
    '.gen-title{font-size:22px;font-weight:800;color:#e8f4f1;margin-bottom:6px;}',
    '.gen-phase{font-size:13px;color:#7db4ab;}',
    '.gen-terminal{background:#0d1117;border-radius:16px;overflow:hidden;box-shadow:0 24px 64px rgba(0,0,0,.55);display:flex;flex-direction:column;}',
    '.gen-terminal-bar{background:#1c2128;padding:10px 16px;display:flex;align-items:center;gap:6px;flex-shrink:0;}',
    '.gen-dot{width:11px;height:11px;border-radius:50%;flex-shrink:0;}',
    '.gen-dot-r{background:#ff5f57;}.gen-dot-y{background:#ffbd2e;}.gen-dot-g{background:#28ca41;}',
    '.gen-terminal-label{font-size:12px;color:#8b949e;margin-left:8px;font-family:monospace;}',
    '.gen-output{padding:16px 20px;overflow-y:auto;max-height:42vh;min-height:120px;display:flex;flex-direction:column;gap:2px;scroll-behavior:smooth;}',
    '.gen-cursor-row{padding:2px 20px 14px;color:#58a6ff;font-size:14px;font-family:monospace;animation:genBlink 1s infinite;}',
    '@keyframes genBlink{0%,49%{opacity:1}50%,100%{opacity:0}}',
    '.gen-line{font-size:13px;font-family:"Courier New",monospace;padding:2px 0;display:flex;align-items:center;gap:8px;animation:genTypeIn .18s ease;}',
    '.gen-line-info{color:#8b949e;}.gen-line-spot{color:#7ee787;}.gen-line-warn{color:#f0883e;}.gen-line-done{color:#58a6ff;}.gen-line-sys{color:#e6edf3;}',
    '@keyframes genTypeIn{from{opacity:0;transform:translateY(5px)}to{opacity:1;transform:translateY(0)}}',
    '.gen-footer{display:flex;flex-direction:column;align-items:center;gap:10px;min-height:48px;}',
    '.gen-edit-btn{background:linear-gradient(135deg,#0f7c6b 0%,#1f8efa 100%);color:#fff;border:none;border-radius:16px;padding:14px 44px;font-size:16px;font-weight:700;cursor:pointer;animation:riseIn .4s ease;box-shadow:0 8px 28px rgba(31,142,250,.35);letter-spacing:.3px;}',
    '.gen-edit-btn:hover{transform:translateY(-2px);box-shadow:0 12px 36px rgba(31,142,250,.45);}',
    '.gen-skip-link{background:none;border:none;color:#7db4ab;font-size:13px;cursor:pointer;text-decoration:underline;}',
]
css_insert = NL + NL.join(css_lines) + NL

if css_marker in content:
    content = content.replace(css_marker, css_marker + css_insert, 1)
    print('CSS inserted OK')
else:
    print('CSS marker NOT FOUND', file=sys.stderr)
    sys.exit(1)

# ── 2. HTML overlay (before </body>) ──────────────────────────────────
html_marker = '</body>'
html_lines = [
    '<div id="genOverlay" style="display:none">',
    '  <div class="gen-container">',
    '    <div class="gen-header">',
    '      <div class="gen-title" id="genTitle">\U0001f916 AI 正在規劃你的行程</div>',
    '      <div class="gen-phase" id="genPhase">連接中…</div>',
    '    </div>',
    '    <div class="gen-terminal">',
    '      <div class="gen-terminal-bar">',
    '        <span class="gen-dot gen-dot-r"></span>',
    '        <span class="gen-dot gen-dot-y"></span>',
    '        <span class="gen-dot gen-dot-g"></span>',
    '        <span class="gen-terminal-label">TravelLinkAI \xb7 行程生成</span>',
    '      </div>',
    '      <div class="gen-output" id="genOutput"></div>',
    '      <div class="gen-cursor-row" id="genCursor">◌</div>',
    '    </div>',
    '    <div class="gen-footer" id="genFooter"></div>',
    '  </div>',
    '</div>',
]
html_insert = NL.join(html_lines) + NL

if html_marker in content:
    content = content.replace(html_marker, html_insert + html_marker, 1)
    print('HTML overlay inserted OK')
else:
    print('HTML marker NOT FOUND', file=sys.stderr)
    sys.exit(1)

# ── 3. Replace finishWizard ────────────────────────────────────────────
old_start = 'async function finishWizard() {'
old_end   = '// ══════════════════════════════════════════════════' + NL + '// INVITE CODE'

idx_start = content.find(old_start)
idx_end   = content.find(old_end)
if idx_start < 0 or idx_end < 0:
    print('finishWizard markers NOT FOUND', idx_start, idx_end, file=sys.stderr)
    sys.exit(1)

def L(s):
    return s + NL

new_code = (
    L('// === Gen Panel helpers ===') +
    L("function showGenPanel(title) {") +
    L("  const ov = document.getElementById('genOverlay');") +
    L("  if (!ov) return;") +
    L("  document.getElementById('genTitle').textContent = '\U0001f916 ' + title + ' \xb7 生成中';") +
    L("  document.getElementById('genPhase').textContent = 'AI 正在思考你的行程…';") +
    L("  document.getElementById('genOutput').innerHTML = '';") +
    L("  document.getElementById('genFooter').innerHTML = '';") +
    L("  const cur = document.getElementById('genCursor');") +
    L("  if (cur) cur.style.display = '';") +
    L("  ov.style.display = 'flex';") +
    L("  addGenLine('$ TravelLinkAI --generate --dest ' + title, 'info');") +
    L("}") +
    L("function hideGenPanel() {") +
    L("  const ov = document.getElementById('genOverlay');") +
    L("  if (ov) ov.style.display = 'none';") +
    L("}") +
    L("function addGenLine(text, type) {") +
    L("  const out = document.getElementById('genOutput');") +
    L("  if (!out) return;") +
    L("  const d = document.createElement('div');") +
    L("  d.className = 'gen-line gen-line-' + (type || 'sys');") +
    L("  d.textContent = text;") +
    L("  out.appendChild(d);") +
    L("  out.scrollTop = out.scrollHeight;") +
    L("}") +
    L("function setGenPhase(text) {") +
    L("  const el = document.getElementById('genPhase');") +
    L("  if (el) el.textContent = text;") +
    L("}") +
    L("function extractNamesFromStream(text) {") +
    L("  const names = [];") +
    L('  const re = /"name"\\s*:\\s*"([^"\\\\\\n]{1,60})"/g;') +
    L("  let m;") +
    L("  while ((m = re.exec(text)) !== null) {") +
    L("    const n = m[1].trim();") +
    L("    if (n && n.length > 1 && !names.includes(n)) names.push(n);") +
    L("  }") +
    L("  return names;") +
    L("}") +
    L("function showGenDoneButton(tripId) {") +
    L("  const cur = document.getElementById('genCursor');") +
    L("  if (cur) cur.style.display = 'none';") +
    L("  const footer = document.getElementById('genFooter');") +
    L("  if (!footer) return;") +
    L("  const btn = document.createElement('button');") +
    L("  btn.className = 'gen-edit-btn';") +
    L("  btn.textContent = '✏️ 開始編輯行程';") +
    L("  btn.onclick = function() { hideGenPanel(); window.location = 'ai-travel-planner-v8.html?id=' + tripId; };") +
    L("  footer.appendChild(btn);") +
    L("  const skip = document.createElement('button');") +
    L("  skip.className = 'gen-skip-link';") +
    L("  skip.textContent = '前往我的行程列表';") +
    L("  skip.onclick = function() { hideGenPanel(); showMainView('mytrips'); };") +
    L("  footer.appendChild(skip);") +
    L("}") +
    NL +
    L("async function finishWizard() {") +
    L("  if (isGeneratingTrip) return;") +
    L("  isGeneratingTrip = true;") +
    NL +
    L("  const dest = wizData.dest || wizData.destCustom || '台東';") +
    L("  const days = wizData.days || '1天';") +
    L("  const budgetMap = {'0.5小時':'$200','1小時':'$300','2小時':'$500','半天':'$800','1天':'$1,500','2天':'$3,000'};") +
    L("  const tripMode = wizData.tripMode || 'solo';") +
    L("  const inviteCode = tripMode === 'collab'") +
    L("    ? 'WNDR-' + Math.random().toString(36).substring(2, 6).toUpperCase()") +
    L("    : null;") +
    L("  const emojiMap = {'台東':'\U0001f30a','花蓮':'\U0001f3d4','台北':'\U0001f3d9','日本':'⛩️','韓國':'\U0001f338','歐洲':'\U0001f3db'};") +
    L("  const newTrip = {") +
    L("    id: 'my_' + Date.now(), title: `${dest} ${days}微旅行`,") +
    L("    emoji: emojiMap[dest] || '✈️',") +
    L("    cc: ['c0','c1','c2','c3'][Math.floor(Math.random()*4)],") +
    L("    days, region: dest, budget: wizData.budget || budgetMap[days] || '$1,500',") +
    L("    people: wizData.people || (wizData.tripMode === 'solo' ? '1人' : '2人'), status: 'planning',") +
    L("    createdAt: new Date().toLocaleDateString('zh-TW'),") +
    L("    tripMode,") +
    L("    ...(inviteCode ? { inviteCode } : {}),") +
    L("    wizardData: {") +
    L("      dest, days,") +
    L("      people: wizData.people || (wizData.tripMode === 'solo' ? '1人' : '2人'),") +
    L("      pace: wizData.pace || '平衡',") +
    L("      interests: wizData.interests || [], theme: wizData.theme || '經典旅人',") +
    L("      startTime: wizData.startTime || '09:00',") +
    L("      startLocation: wizData.startLocation || '', endLocation: wizData.endLocation || '',") +
    L("      departureDate: wizData.departureDate || '',") +
    L("      returnDate: wizData.returnDate || '',") +
    L("      budget: wizData.budget || '',") +
    L("      accommodation: isLongTrip(days) ? (wizData.accommodation || '飯店') : '',") +
    L("      desiredSpots: (wizData.desiredSpots || '').trim()") +
    L("    },") +
    L("    stops: []") +
    L("  };") +
    NL +
    L("  closeWizard();") +
    L("  if (inviteCode) {") +
    L("    document.getElementById('collabCodeDisplay').textContent = inviteCode;") +
    L("    document.getElementById('collabCodeOverlay').classList.add('open');") +
    L("  }") +
    L("  showGenPanel(newTrip.title);") +
    NL +
    L("  try {") +
    L("    let lastNames = [];") +
    L("    const finalPlan = await requestGeminiMicroTravelPlan(wizData, {") +
    L("      mode: 'final',") +
    L("      includeFirebase: true,") +
    L("      useStreaming: true,") +
    L("      onChunk: (_chunk, fullText) => {") +
    L("        const names = extractNamesFromStream(fullText);") +
    L("        if (names.length > lastNames.length) {") +
    L("          for (let i = lastNames.length; i < names.length; i++) {") +
    L("            addGenLine('  ❆ ' + names[i], 'spot');") +
    L("          }") +
    L("          lastNames = names;") +
    L("          setGenPhase('已識別 ' + names.length + ' 個景點，持續接收中…');") +
    L("        }") +
    L("      }") +
    L("    });") +
    NL +
    L("    addGenLine('> AI 生成完畢，正在驗證景點與地圖資訊…', 'info');") +
    L("    setGenPhase('驗證地圖資訊中…');") +
    NL +
    L("    if (finalPlan && finalPlan.stops) {") +
    L("      newTrip.stops = await optimizeGeneratedTripStops(finalPlan.stops, wizData);") +
    L("      newTrip.aiTitle = finalPlan.title || newTrip.title;") +
    L("      newTrip.aiReply = finalPlan.reply || '';") +
    L("    }") +
    NL +
    L("    myTrips.unshift(newTrip);") +
    L("    saveState(); renderSideMyTrips(); renderMyTrips();") +
    NL +
    L("    addGenLine('> 已儲存到本機裝置 ✓', 'done');") +
    L("    setGenPhase('行程就緒！');") +
    L("    showGenDoneButton(newTrip.id);") +
    NL +
    L("    // Firebase 背景同步，不阻擋編輯按鈕") +
    L("    if (firebaseEnabled) {") +
    L("      persistMicroTripInBackground(newTrip);") +
    L("    }") +
    NL +
    L("  } catch (error) {") +
    L("    isGeneratingTrip = false;") +
    L("    addGenLine('> 錯誤：' + error.message, 'warn');") +
    L("    setGenPhase('生成失敗，請嘗試重新發起');") +
    L("    setTimeout(() => { hideGenPanel(); showToast('生成行程失敗：' + error.message, 'red'); }, 1500);") +
    L("    console.error('finishWizard error:', error);") +
    L("    myTrips.unshift(newTrip);") +
    L("    saveState(); renderSideMyTrips(); renderMyTrips();") +
    L("    return;") +
    L("  }") +
    L("  isGeneratingTrip = false;") +
    L("}") +
    NL
)

old_block = content[idx_start:idx_end]
content = content[:idx_start] + new_code + content[idx_end:]
print(f'finishWizard replaced OK (was {len(old_block)} chars, new {len(new_code)} chars)')

with open(path, 'w', encoding='utf-8', newline='') as f:
    f.write(content)
print('File written OK')
