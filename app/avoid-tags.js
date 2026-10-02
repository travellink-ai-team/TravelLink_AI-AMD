/**
 * 飲食禁忌 / 想避免事物的「固定標籤」字典。
 *
 * 設定個人喜好精靈會把這些渲染成可點選的 chip；使用者選取後，送進 AI prompt
 * 時會以 hashtag（tag 欄位，如 "#素食"）輸出，讓 AI 更穩定辨識硬性禁忌。
 * 自由輸入（textarea）仍保留，用來補充清單以外的個別需求。
 *
 * 維護：要增刪固定標籤，直接改下面的清單即可（label = chip 顯示文字，tag = 送 AI 的 hashtag）。
 */
window.WAI_AVOID_TAGS = [
  {
    group: '飲食',
    items: [
      { tag: '#素食', label: '素食' },
      { tag: '#不吃辣', label: '不吃辣' },
      { tag: '#不吃牛', label: '不吃牛' },
      { tag: '#不吃豬', label: '不吃豬' },
      { tag: '#海鮮過敏', label: '海鮮過敏' },
      { tag: '#堅果過敏', label: '堅果過敏' }
    ]
  },
  {
    group: '行程',
    items: [
      { tag: '#避開人潮', label: '避開人潮' },
      { tag: '#避開高消費', label: '避開高消費' },
      { tag: '#避免大量步行', label: '避免大量步行' },
      { tag: '#避免水上活動', label: '避免水上活動' }
    ]
  }
];
