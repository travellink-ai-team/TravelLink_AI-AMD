const scenes = [
  { title: '開場 · 海風', label: '台東 · DAY 01', caption: '從海風開始的<br>三天小旅行', location: '都蘭海岸線', time: '00:00', image: 'https://images.unsplash.com/photo-1507525428034-b723cf961d3e?auto=format&fit=crop&w=900&q=85', motion: 'pan-right' },
  { title: '午後 · 巷弄', label: '台東 · DAY 01', caption: '在不趕時間的<br>午後繞一點路', location: '台東市區 · 巷口咖啡', time: '00:07', image: 'https://images.unsplash.com/photo-1501339847302-ac426a4a7cbb?auto=format&fit=crop&w=900&q=85', motion: 'zoom' },
  { title: '傍晚 · 山線', label: '台東 · DAY 02', caption: '山的另一邊，<br>夕陽剛好落下', location: '池上 · 伯朗大道', time: '00:14', image: 'https://images.unsplash.com/photo-1500534623283-312aade485b7?auto=format&fit=crop&w=900&q=85', motion: 'pan-left' },
  { title: '收尾 · 記住', label: '台東 · DAY 03', caption: '有些地方，<br>適合慢慢記住', location: '回程前的最後一站', time: '00:21', image: 'https://images.unsplash.com/photo-1516483638261-f4dbaf036963?auto=format&fit=crop&w=900&q=85', motion: 'still' }
];

const reelScreen = document.getElementById('reelScreen');
const reelImage = document.getElementById('reelImage');
const reelCaption = document.getElementById('reelCaption');
const reelLocation = document.getElementById('reelLocation');
const reelCounter = document.getElementById('reelCounter');
const reelProgress = document.getElementById('reelProgress');
const sceneName = document.getElementById('sceneName');
const sceneList = document.getElementById('sceneList');
const playButton = document.getElementById('playButton');
const playIcon = document.getElementById('playIcon');
const captionToggle = document.getElementById('captionToggle');
const captionState = document.getElementById('captionState');
const exportButton = document.getElementById('exportButton');
const exportHint = document.getElementById('exportHint');

let activeIndex = 0;
let isPlaying = true;
let showCaption = true;
let sceneStartedAt = performance.now();
const sceneDuration = 7000;

function renderScene(index) {
  const scene = scenes[index];
  activeIndex = index;
  sceneStartedAt = performance.now();
  reelImage.style.backgroundImage = `url("${scene.image}")`;
  reelScreen.dataset.motion = scene.motion;
  reelCaption.querySelector('.caption-kicker').textContent = scene.label;
  reelCaption.querySelector('strong').innerHTML = scene.caption;
  reelLocation.textContent = scene.location;
  reelCounter.textContent = `${String(index + 1).padStart(2, '0')} / ${String(scenes.length).padStart(2, '0')}`;
  sceneName.textContent = scene.title;
  reelCaption.classList.toggle('is-hidden', !showCaption);
  reelLocation.classList.toggle('is-hidden', !showCaption);
  document.querySelectorAll('.scene-item').forEach((item, itemIndex) => item.classList.toggle('active', itemIndex === index));
}

function renderSceneList() {
  sceneList.innerHTML = scenes.map((scene, index) => `
    <button class="scene-item ${index === activeIndex ? 'active' : ''}" type="button" data-index="${index}">
      <span class="scene-num">0${index + 1}</span>
      <span class="scene-title">${scene.title}</span>
      <span class="scene-time">${scene.time}</span>
    </button>
  `).join('');
  sceneList.querySelectorAll('.scene-item').forEach((item) => item.addEventListener('click', () => {
    renderScene(Number(item.dataset.index));
    isPlaying = true;
    updatePlayButton();
  }));
}

function updatePlayButton() {
  playIcon.textContent = isPlaying ? 'Ⅱ' : '▶';
  playButton.setAttribute('aria-label', isPlaying ? '暫停' : '播放');
}

function animate(now) {
  if (isPlaying) {
    const elapsed = now - sceneStartedAt;
    const sceneProgress = Math.min(1, elapsed / sceneDuration);
    reelProgress.style.width = `${((activeIndex + sceneProgress) / scenes.length) * 100}%`;
    if (sceneProgress >= 1) renderScene((activeIndex + 1) % scenes.length);
  }
  requestAnimationFrame(animate);
}

document.querySelectorAll('.mood-card').forEach((card) => card.addEventListener('click', () => {
  document.querySelectorAll('.mood-card').forEach((item) => item.classList.remove('active'));
  card.classList.add('active');
  exportHint.textContent = card.dataset.mood === 'rhythm' ? '節奏剪輯預覽 · 會增加切換頻率與動態文字' : card.dataset.mood === 'journal' ? '旅程紀錄預覽 · 會加入日期與路線節點' : 'Demo 預覽 · 實際版本會輸出 1080 × 1920 MP4';
}));

playButton.addEventListener('click', () => {
  isPlaying = !isPlaying;
  if (isPlaying) sceneStartedAt = performance.now();
  updatePlayButton();
});

captionToggle.addEventListener('click', () => {
  showCaption = !showCaption;
  captionToggle.classList.toggle('active', showCaption);
  captionState.textContent = showCaption ? '顯示地點與短句' : '純畫面，保留情緒';
  reelCaption.classList.toggle('is-hidden', !showCaption);
  reelLocation.classList.toggle('is-hidden', !showCaption);
});

exportButton.addEventListener('click', () => {
  exportHint.textContent = '這裡是概念 Demo；正式版會開始產生 MP4，完成後可下載或分享。';
  exportButton.innerHTML = '<span>✓</span> 已加入匯出佇列';
  window.setTimeout(() => {
    exportButton.innerHTML = '<span>↓</span> 匯出這支旅程短片';
    exportHint.textContent = 'Demo 預覽 · 實際版本會輸出 1080 × 1920 MP4';
  }, 2400);
});

renderSceneList();
renderScene(activeIndex);
updatePlayButton();
requestAnimationFrame(animate);
