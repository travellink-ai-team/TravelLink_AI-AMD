/**
 * TravelLink AI — Google 表單自動建立腳本 (Google Apps Script)
 * 
 * 使用方法：
 * 1. 開啟 https://script.google.com/ 建立新專案
 * 2. 將此程式碼完整貼上取代 Code.gs
 * 3. 點擊「執行 (Run)」，授權後即可在您的 Google 雲端硬碟自動生成一份完整的 Google 表單！
 * 4. 執行完成後，請至 Apps Script 執行紀錄 (Execution Log) 查看表單編輯連結與填寫連結。
 */

function createTravelLinkUXForm() {
  var form = FormApp.create('TravelLink AI 台東智慧旅遊 App — 使用者體驗（UX）調查問卷');
  
  form.setDescription(
    '親愛的測試者您好：\n' +
    '感謝您參與【TravelLink AI 台東智慧旅遊 App】的使用者體驗測試！\n\n' +
    '本問卷旨在收集您在使用 AI 行程生成、隨行管家、地圖導航、多人共編、在地交通接駁及旅遊回憶等核心功能時的真實感受與寶貴建議。\n' +
    '問卷填寫約需 3 - 5 分鐘，所有資料僅供專題開發與 UX 介面優化分析使用，請安心填寫！\n\n' +
    '團隊再次感謝您的寶貴時間與協助！'
  );
  
  form.setCollectEmail(false);
  form.setAllowResponseEdits(true);
  
  // ==========================================
  // 第一區塊：基本資料與旅遊習慣
  // ==========================================
  form.addHeaderItem().setTitle('一、 基本資料與旅遊習慣');
  
  var q1 = form.addMultipleChoiceItem();
  q1.setTitle('1. 您的年齡層')
    .setChoiceValues(['18 歲以下', '18 - 24 歲', '25 - 34 歲', '35 - 44 歲', '45 - 54 歲', '55 歲以上'])
    .setRequired(true);

  var q2 = form.addMultipleChoiceItem();
  q2.setTitle('2. 您平均多久進行一次國內自由行旅遊？')
    .setChoiceValues(['每個月 1 次以上', '每半年 1-2 次', '每年 1-2 次', '偶爾（一年不到 1 次）'])
    .setRequired(true);

  var q3 = form.addCheckboxItem();
  q3.setTitle('3. 規劃旅遊行程時，您過往最常使用的工具？（可多選）')
    .setChoiceValues([
      'Excel / Google Sheet 手動排表',
      'Notion / 手機筆記 App',
      '小紅書 / Instagram / 旅遊部落格搜尋社群貼文',
      '既有行程規劃 App（如 Funliday、TripIt 等）',
      'ChatGPT / Claude 等泛用型 AI 對話工具',
      '委託旅行社 / 隨興無規劃直接出發'
    ])
    .setRequired(true);

  var q4 = form.addMultipleChoiceItem();
  q4.setTitle('4. 您本次測試使用的 Android 裝置與系統版本')
    .setChoiceValues([
      'Android 14 及以上（最新旗艦/次旗艦機）',
      'Android 11 - 13（主流手機）',
      'Android 10 及以下（舊款手機）',
      '不確定 / 使用電腦 Android 模擬器測試'
    ])
    .setRequired(false);

  // ==========================================
  // 第二區塊：AI 行程生成與預覽體驗
  // ==========================================
  form.addPageBreakItem().setTitle('二、 AI 行程生成與預覽體驗');
  
  var q5 = form.addScaleItem();
  q5.setTitle('5. 【行程規劃精靈】輸入條件（日期、人數、風格、節奏、預算）的介面操作直覺度')
    .setBounds(1, 5)
    .setLabels('非常困難/複雜', '非常直覺順暢')
    .setRequired(true);

  var q6 = form.addScaleItem();
  q6.setTitle('6. 【AI 行程合理性】Gemini 生成的行程內容合理度（景點順路度、停留時間、台東在地特色符合度）')
    .setBounds(1, 5)
    .setLabels('非常不合理', '非常滿意/符合需求')
    .setRequired(true);

  var q7 = form.addScaleItem();
  q7.setTitle('7. 【Live Preview 即時預覽】AI 生成過程中「分階段逐步成形」的動態呈現體驗')
    .setBounds(1, 5)
    .setLabels('等待過久/感到焦慮', '富有科技感/體驗良好')
    .setRequired(true);

  var q8 = form.addScaleItem();
  q8.setTitle('8. 【AI 旅遊插圖】Imagen 3.0 自動生成的 16:9 手繪風格封面圖對行程質感的提升程度')
    .setBounds(1, 5)
    .setLabels('無感/不吸引人', '大幅提升質感與期待感')
    .setRequired(true);

  // ==========================================
  // 第三區塊：AI 隨行管家（Assistant Overlay）體驗
  // ==========================================
  form.addPageBreakItem().setTitle('三、 AI 隨行管家（Assistant Overlay）體驗');

  var q9 = form.addScaleItem();
  q9.setTitle('9. 【懸浮頭像】畫面上可任意拖曳的 AI 隨行管家頭像，操作便利性與畫面遮擋感')
    .setBounds(1, 5)
    .setLabels('經常誤觸/嚴重遮擋', '位置靈活/極為便利')
    .setRequired(true);

  var q10 = form.addScaleItem();
  q10.setTitle('10. 【智能推薦】隨行管家提供的景點臨時推薦、替換建議與對話答覆的實用性')
    .setBounds(1, 5)
    .setLabels('毫無幫助/答非所問', '非常實用且精準')
    .setRequired(true);

  // ==========================================
  // 第四區塊：地圖動線、景點打卡與離線體驗
  // ==========================================
  form.addPageBreakItem().setTitle('四、 地圖動線、景點打卡與離線體驗');

  var q11 = form.addScaleItem();
  q11.setTitle('11. 【地圖動線視覺化】Google Maps 地圖景點標記與路線 Polyline 折線呈現的清晰度')
    .setBounds(1, 5)
    .setLabels('路線混亂難以識別', '清晰易懂路線一目瞭然')
    .setRequired(true);

  var q12 = form.addScaleItem();
  q12.setTitle('12. 【行程進行中模式】GPS 即時定位、景點打卡（已去標記）與導航跳轉按鈕順暢度')
    .setBounds(1, 5)
    .setLabels('卡頓/操作不順', '反應迅速流暢')
    .setRequired(true);

  var q13 = form.addScaleItem();
  q13.setTitle('13. 【離線模式】在離線或網路訊號不佳時，瀏覽歷史行程與離線地圖快取的穩定度')
    .setBounds(1, 5)
    .setLabels('容易出錯/離線無法用', '離線支援完整穩定')
    .setRequired(true);

  // ==========================================
  // 第五區塊：多人即時協作與好友群組
  // ==========================================
  form.addPageBreakItem().setTitle('五、 多人即時協作與好友群組');

  var q14 = form.addScaleItem();
  q14.setTitle('14. 【Deep Link 邀請】透過分享 Deep Link 連結（travellink://join/...）邀請好友加入行程的方便程度')
    .setBounds(1, 5)
    .setLabels('經常失敗/難以使用', '一鍵加入非常方便')
    .setRequired(true);

  var q15 = form.addScaleItem();
  q15.setTitle('15. 【Firestore 即時同步】多人同時編輯行程時，異動同步速度與編輯鎖防衝突體驗')
    .setBounds(1, 5)
    .setLabels('延遲明顯/資料衝突', '即時同步/體驗極佳')
    .setRequired(true);

  var q16 = form.addScaleItem();
  q16.setTitle('16. 【偏好多數決】群組成員旅遊風格與節奏的多數決聚合機制滿意度')
    .setBounds(1, 5)
    .setLabels('不符團隊期待', '相當公平實用')
    .setRequired(true);

  // ==========================================
  // 第六區塊：在地交通、天氣與費用估算
  // ==========================================
  form.addPageBreakItem().setTitle('六、 在地交通、天氣與費用估算');

  var q17 = form.addScaleItem();
  q17.setTitle('17. 【在地接駁資訊】整合台鐵時刻與綠島/蘭嶼渡輪時刻表對行前規劃的幫助程度')
    .setBounds(1, 5)
    .setLabels('毫無幫助', '幫助極大省去搜尋時間')
    .setRequired(true);

  var q18 = form.addScaleItem();
  q18.setTitle('18. 【費用預估】App 自動計算的交通與門票費用預估細節透明度與參考價值')
    .setBounds(1, 5)
    .setLabels('缺乏參考價值', '清楚透明極具參考性')
    .setRequired(true);

  // ==========================================
  // 第七區塊：旅遊回憶與社群分享
  // ==========================================
  form.addPageBreakItem().setTitle('七、 旅遊回憶與社群分享');

  var q19 = form.addScaleItem();
  q19.setTitle('19. 【旅遊相簿】景點照片上傳與「九宮格回憶牆」排版視覺呈現滿意度')
    .setBounds(1, 5)
    .setLabels('效果平淡/不吸引人', '質感極佳令人想保存')
    .setRequired(true);

  var q20 = form.addScaleItem();
  q20.setTitle('20. 【Recap 回顧短片】AI 自動生成旅程亮點短片與一鍵分享至 IG / 社群平台的流暢度')
    .setBounds(1, 5)
    .setLabels('生成失敗/分享困難', '非常驚豔且分享流暢')
    .setRequired(true);

  // ==========================================
  // 第八區塊：總體評分與推薦意願 (SUS & NPS)
  // ==========================================
  form.addPageBreakItem().setTitle('八、 總體評價與推薦意願 (SUS & NPS)');

  var q21 = form.addScaleItem();
  q21.setTitle('21. 【系統易用性 (SUS)】總體而言，我覺得 TravelLink AI 介面簡潔且容易上手')
    .setBounds(1, 5)
    .setLabels('非常不同意', '非常同意')
    .setRequired(true);

  var q22 = form.addScaleItem();
  q22.setTitle('22. 【NPS 淨推薦值】您有多大的可能性會向準備前往台東旅遊的朋友推薦 TravelLink AI？')
    .setBounds(0, 10)
    .setLabels('完全不可能 (0 分)', '極有可能 (10 分)')
    .setRequired(true);

  var q23 = form.addCheckboxItem();
  q23.setTitle('23. 您個人最喜歡 TravelLink AI 的哪前 3 個亮點功能？（最多選擇 3 項）')
    .setChoiceValues([
      'Gemini AI 客製化行程秒級生成',
      'Imagen 3.0 手繪風格動態旅遊插圖',
      'AI 隨行管家（隨時替換景點、位置感知與對話）',
      'Google Maps 路線視覺化與一鍵開啟導航',
      '多人即時共編行程與偏好投票機制',
      '離線地圖快取與 Room 本地離線存取',
      '台鐵／離島渡輪時刻整合與費用預估',
      '旅遊回憶九宮格與 Recap 亮點短片生成'
    ])
    .setRequired(true);

  // ==========================================
  // 第九區塊：寶貴建議與 Bug 問題回報
  // ==========================================
  form.addPageBreakItem().setTitle('九、 寶貴建議與 Bug 問題回報');

  var q24 = form.addParagraphTextItem();
  q24.setTitle('24. 【Bug / 異常回報】您在測試過程中是否有遇到任何錯誤、閃退或體驗卡頓的地方？（請描述頁面與狀況）');

  var q25 = form.addParagraphTextItem();
  q25.setTitle('25. 【功能許願】您認為目前最需要優先優化或未來最期待加入的功能是什麼？');

  var q26 = form.addParagraphTextItem();
  q26.setTitle('26. 【團隊鼓勵】其他想對開發團隊說的話或對於專題成果的評語：');

  // 輸出完成資訊
  var editUrl = form.getEditUrl();
  var publishedUrl = form.getPublishedUrl();
  
  Logger.log('\n==================================================');
  Logger.log('🎉 TravelLink AI UX 問卷表單建立完成！');
  Logger.log('📝 管理／編輯網址：' + editUrl);
  Logger.log('🔗 填寫／發布網址：' + publishedUrl);
  Logger.log('==================================================\n');
}
