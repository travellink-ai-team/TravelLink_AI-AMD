/**
 * collab.js — TravelLinkAI 多人共同建立行程模組（window.WAI_COLLAB）
 *
 * 範圍（依產品決策）：
 *   - 同看一份 + 團體生成 + 成員清單（暫不做即時逐欄共編）。
 *   - 加入者預設 viewer；owner 可調整每人角色。
 *   - 彙整：節奏「多數決」、預算「平均」、興趣「聯集（票數排序）」、禁忌「聯集」。
 *   - 生成：owner 隨時可生成。
 *   - 每團上限 10 人；提供訪客（不登入）唯讀分享連結。
 *
 * 資料模型（沿用既有 micro_trips，加上協作欄位）：
 *   micro_trips/{tripId}
 *     collab:true, ownerUid, ownerEmail, ownerName,
   *     inviteCode, shareToken, maxMembers:10,
 *     memberEmails:[...]            // 供 array-contains 查「我加入的」
 *     members:{ <ekey>:{ email,name,role,ready,prefs{interests,pace,avoid,avoidTags,budget,desiredSpots},joinedAt } }
 *     userEmail: ownerEmail         // 保留既有欄位，owner 既有查詢仍找得到
 *   invites/{CODE} -> { tripId, active:true, createdBy, createdAt }
 *
 * 純函式（彙整 / 產碼）不碰 Firestore，可用 node 單元測試。
 * 需要 Firestore 的函式在呼叫時才讀全域 firebaseDb / firebaseAuth / firebase（CDN）。
 */
window.WAI_COLLAB = (function () {
  'use strict';

  var MAX_MEMBERS = 10;
  // base32，去掉易混字 0/O/1/I
  var CODE_ALPHABET = 'ABCDEFGHJKLMNPQRSTUVWXYZ23456789';

  // ── 產碼 / 正規化 ──
  function randFrom(alphabet, n) {
    if (!window.crypto || !window.crypto.getRandomValues) {
      throw new Error('目前瀏覽器不支援安全亂數，無法建立邀請連結。');
    }
    var s = '';
    var limit = 256 - (256 % alphabet.length);
    while (s.length < n) {
      var bytes = new Uint8Array(Math.max(8, (n - s.length) * 2));
      window.crypto.getRandomValues(bytes);
      for (var i = 0; i < bytes.length && s.length < n; i++) {
        if (bytes[i] < limit) s += alphabet[bytes[i] % alphabet.length];
      }
    }
    return s;
  }
  function generateInviteCode() {
    var raw = randFrom(CODE_ALPHABET, 8);
    return raw.slice(0, 4) + '-' + raw.slice(4); // 例：AB3D-7K9P
  }
  function generateShareToken() {
    return randFrom(CODE_ALPHABET, 20);
  }
  function normalizeCode(code) {
    return String(code || '').toUpperCase().replace(/[^A-Z0-9]/g, '');
  }
  // 顯示用：把正規化邀請碼補回連字號（AB3D7K9P → AB3D-7K9P），純美觀；輸入端一律再正規化。
  function formatInviteCode(code) {
    var n = normalizeCode(code);
    return n.length > 4 ? n.slice(0, 4) + '-' + n.slice(4) : n;
  }
  function emailKey(email) {
    return String(email || '').toLowerCase().replace(/[^a-z0-9]/g, '_');
  }
  function sameEmail(a, b) {
    return !!String(a || '').trim() && String(a).trim().toLowerCase() === String(b || '').trim().toLowerCase();
  }
  function memberForEmail(trip, email) {
    var member = trip && trip.members && trip.members[emailKey(email)];
    return member && sameEmail(member.email, email) ? member : null;
  }
  function assertMemberIdentity(trip, email) {
    var key = emailKey(email);
    var member = trip.members && trip.members[key];
    var emails = (trip.memberEmails || []).concat([trip.ownerEmail || trip.userEmail || '']);
    if (!email || (member && !sameEmail(member.email, email)) || emails.some(function (other) {
      return other && emailKey(other) === key && !sameEmail(other, email);
    })) throw new Error('此帳號與既有成員的識別資料衝突，暫時無法操作；請聯絡管理者。');
  }
  function memberEmailsWithOwner(trip) {
    var result = [];
    (trip.memberEmails || []).concat([trip.ownerEmail || trip.userEmail || '']).forEach(function (email) {
      if (email && !result.some(function (other) { return sameEmail(other, email); })) result.push(email);
    });
    return result;
  }
  function resolveRole(trip, email, uid) {
    if (!trip || !email) return 'viewer';
    if ((uid && trip.ownerUid === uid) || sameEmail(trip.ownerEmail, email) || sameEmail(trip.userEmail, email)) return 'owner';
    if ((trip.editorEmails || []).some(function (other) { return sameEmail(other, email); })) return 'editor';
    var member = memberForEmail(trip, email);
    return member && member.role === 'editor' ? 'editor' : 'viewer';
  }
  // 用於文件路徑／身分隔離的無碰撞 key。emailKey 需保留給既有共編 members schema；
  // identityKey 則以正規化 email 的 UTF-8 十六進位表示，避免 a.b 與 a_b 被壓成同一值。
  function identityKey(email) {
    var normalized = String(email || '').trim().toLowerCase();
    if (!normalized) return '';
    var bytes = new TextEncoder().encode(normalized);
    return Array.prototype.map.call(bytes, function (b) {
      return b.toString(16).padStart(2, '0');
    }).join('');
  }

  // ── 預算解析 / 格式化 ──
  function parseBudgetNumber(b) {
    if (typeof b === 'number') return b > 0 ? b : 0;
    var m = String(b || '').replace(/[,\s]/g, '').match(/(\d+)/);
    return m ? parseInt(m[1], 10) : 0;
  }
  function formatBudget(n) {
    return '$' + Math.round(n).toLocaleString('en-US');
  }

  // ── 節奏多數決（平手由發起人 owner 決定）──
  // 有唯一最高票 → 取多數；平手（≥2 種同為最高票，含 2 人不同調）→ 取 ownerPace；
  // owner 沒設節奏才退回「較放鬆者」（越前面越放鬆）。
  var PACE_ORDER = ['悠閒', '平衡', '輕快'];
  function majorityPace(paces, ownerPace) {
    var counts = {};
    (paces || []).filter(Boolean).forEach(function (p) { counts[p] = (counts[p] || 0) + 1; });
    var keys = Object.keys(counts);
    if (!keys.length) return ownerPace || '平衡';
    var maxN = Math.max.apply(null, keys.map(function (k) { return counts[k]; }));
    var top = keys.filter(function (k) { return counts[k] === maxN; });
    if (top.length === 1) return top[0]; // 唯一最高票 → 多數決
    // 平手 → 發起人說了算
    if (ownerPace) return ownerPace;
    // 退路：較放鬆者（PACE_ORDER 越前面越放鬆，自訂節奏排最後）
    top.sort(function (a, b) {
      var ia = PACE_ORDER.indexOf(a), ib = PACE_ORDER.indexOf(b);
      return (ia === -1 ? 99 : ia) - (ib === -1 ? 99 : ib);
    });
    return top[0];
  }

  // ── 團體 profile 彙整（純函式）──
  function aggregateGroupProfile(members) {
    var list = Array.isArray(members) ? members : Object.keys(members || {}).map(function (k) { return members[k]; });
    var ownerMember = list.find(function (m) { return m && m.role === 'owner'; });
    var ownerPace = ownerMember ? ((ownerMember.prefs || ownerMember.preferences || {}).pace || '') : '';
    var interestVotes = {};
    var paces = [];
    var budgets = [];
    var avoidMap = {};   // term -> [names]
    var desired = [];
    list.forEach(function (m) {
      if (!m) return;
      var pf = m.prefs || m.preferences || {};
      var who = m.name || m.email || '';
      (pf.interests || []).forEach(function (i) { if (i) interestVotes[i] = (interestVotes[i] || 0) + 1; });
      if (pf.pace) paces.push(pf.pace);
      var bn = parseBudgetNumber(pf.budget);
      if (bn > 0) budgets.push(bn);
      (pf.avoidTags || []).forEach(function (t) {
        if (!t) return;
        if (!avoidMap[t]) avoidMap[t] = [];
        if (who) avoidMap[t].push(who);
      });
      if (pf.avoid) {
        String(pf.avoid).split(/[、,，;；\n]+/).map(function (s) { return s.trim(); }).filter(Boolean).forEach(function (a) {
          if (!avoidMap[a]) avoidMap[a] = [];
          if (who) avoidMap[a].push(who);
        });
      }
      if (pf.desiredSpots && String(pf.desiredSpots).trim()) {
        desired.push({ by: who, text: String(pf.desiredSpots).trim() });
      }
    });
    var interests = Object.keys(interestVotes).sort(function (a, b) { return interestVotes[b] - interestVotes[a]; });
    var avoid = Object.keys(avoidMap).map(function (term) {
      var uniq = [];
      avoidMap[term].forEach(function (w) { if (uniq.indexOf(w) === -1) uniq.push(w); });
      return { term: term, by: uniq };
    });
    return {
      memberCount: list.length,
      interests: interests,
      interestVotes: interestVotes,
      pace: majorityPace(paces, ownerPace),
      budget: budgets.length ? formatBudget(budgets.reduce(function (a, b) { return a + b; }, 0) / budgets.length) : '',
      avoid: avoid,
      desired: desired
    };
  }

  // 把團體 profile 轉成 prompt 用的偏好行（取代單人 buildPreferenceLines）。
  function buildGroupPreferenceLines(profile) {
    var lines = [];
    lines.push('行程節奏：' + profile.pace + '（多數決；平手時以發起人為準）');
    lines.push('團體興趣方向：' + (profile.interests.join('、') || '多元體驗') +
      '（綜合 ' + profile.memberCount + ' 位成員，依被選票數排序，越前面越多人想去）');
    if (profile.budget) {
      lines.push('團體預算：每人約 ' + profile.budget + '（取全員平均，盡量不讓預算低的人超支）');
    }
    if (profile.avoid && profile.avoid.length) {
      var txt = profile.avoid.map(function (a) {
        var t = a.term.replace(/^#/, '');
        return a.by.length ? (t + '(' + a.by.join('、') + ')') : t;
      }).join('；');
      lines.push('⚠️ 個人禁忌／需避免（任一成員提出即為全團硬性限制，務必全程遵守，含餐廳與景點挑選）：' + txt);
    }
    if (profile.desired && profile.desired.length) {
      lines.push('成員希望景點：' + profile.desired.map(function (d) {
        return d.by ? (d.text + '(' + d.by + ')') : d.text;
      }).join('；') + '（盡量都安排到；若與上方禁忌衝突，一律以禁忌為最高優先，改以鄰近不衝突替代並於 reply 說明）');
    }
    return lines;
  }

  // ── 角色 / 共用工具（純）──
  function roleLabel(role) {
    return ({ owner: '擁有者', editor: '可編輯', viewer: '唯讀', guest: '訪客' })[role] || '唯讀';
  }
  function canEdit(role) { return role === 'owner' || role === 'editor'; }
  function isFull(memberEmails) { return (memberEmails || []).length >= MAX_MEMBERS; }

  // ════════════════════════════════════════════════════
  // 以下為需要 Firestore 的函式（呼叫時才讀全域 firebaseDb）
  // ════════════════════════════════════════════════════
  function db() {
    if (typeof firebaseDb === 'undefined' || !firebaseDb) {
      throw new Error('Firebase 尚未初始化，無法使用共編功能。');
    }
    return firebaseDb;
  }
  function serverTs() { return firebase.firestore.FieldValue.serverTimestamp(); }

  // 個人行程分享也必須使用不可猜的 token。只有 owner 能寫入自己的 micro_trip；
  // 若行程尚未同步到 Firestore，順便補上唯讀頁面所需的核心欄位。
  async function ensureTripShareToken(trip) {
    if (!trip || !trip.id) throw new Error('找不到要分享的行程。');
    var current = firebase.auth().currentUser;
    if (!current || !current.email) throw new Error('請先登入後再分享行程。');
    var token = String(trip.shareToken || '');
    if (token.length < 16) token = generateShareToken();

    var tripRef = db().collection('micro_trips').doc(trip.id);
    // 不可先 get()：文件不存在時 rules 沒有 resource.data 可驗證身分，讀取會直接
    // permission-denied，補建分支永遠到不了（與 friends.js sendInvite 是同一類錯誤）。
    // 改為先試 update——只碰 shareToken，不會覆蓋 Firestore 上較新的 stops／title。
    try {
      await tripRef.update({ shareToken: token, updatedAt: serverTs() });
      return token;
    } catch (updateError) {
      var code = updateError && updateError.code;
      // not-found＝文件不存在；permission-denied＝不存在（無 resource.data）或本人非 owner。
      // 兩者都往下走建檔，真的無權限時由建檔的錯誤處理給明確訊息。
      if (code !== 'not-found' && code !== 'permission-denied') throw updateError;
    }

    // 建檔必須帶齊 owner 身分欄位：read 規則要 ownerUid／ownerEmail／userEmail 其一，
    // 只寫 shareToken 會產生一份任何人都讀不到的孤兒文件。
    try {
      await tripRef.set({
        id: trip.id,
        title: trip.title || trip.aiTitle || '未命名行程',
        customTitle: !!trip.customTitle,
        emoji: trip.emoji || '🗺️',
        days: trip.days || '',
        region: trip.region || '',
        budget: trip.budget || '',
        people: trip.people || '',
        wizardData: trip.wizardData || {},
        stops: Array.isArray(trip.stops) ? trip.stops : [],
        status: trip.status || 'planning',
        currentStopIndex: Number.isInteger(trip.currentStopIndex) ? trip.currentStopIndex : -1,
        startedAt: trip.startedAt || null,
        userEmail: current.email,
        ownerUid: current.uid,
        ownerEmail: current.email,
        ownerName: current.displayName || current.email.split('@')[0],
        collab: false,
        shareToken: token,
        createdAt: serverTs(),
        updatedAt: serverTs()
      }, { merge: true });
    } catch (createError) {
      if (createError && createError.code === 'permission-denied') {
        throw new Error('只有行程擁有者可以建立分享連結。');
      }
      throw createError;
    }
    return token;
  }

  async function collabApi(path, options) {
    var current = firebase.auth().currentUser;
    var headers = Object.assign({ 'Content-Type': 'application/json' }, (options && options.headers) || {});
    if (current) headers.Authorization = 'Bearer ' + await current.getIdToken();
    var response = await fetch('/api/collab/' + path, Object.assign({}, options || {}, { headers: headers }));
    var body = {};
    try { body = await response.json(); } catch (_e) {}
    if (!response.ok) throw new Error(body.message || '共編服務暫時無法使用。');
    return body;
  }

  async function verifyInviteCode(code) {
    return collabApi('invites/verify', {
      method: 'POST',
      body: JSON.stringify({ code: normalizeCode(code) })
    });
  }

  // 產生不重複的邀請碼（最多重試數次）
  async function reserveUniqueCode() {
    for (var attempt = 0; attempt < 6; attempt++) {
      var code = generateInviteCode();
      var ref = db().collection('invites').doc(normalizeCode(code));
      var snap = await ref.get();
      if (!snap.exists) return { code: code, ref: ref };
    }
    throw new Error('邀請碼產生失敗，請再試一次。');
  }

  // owner 建立共用行程：寫 micro_trips 協作欄位 + invites/{code}。
  // trip：既有 newTrip 物件；owner：{ uid,email,name,prefs }
  async function createSharedTrip(trip, owner) {
    var reserved = await reserveUniqueCode();
    var code = reserved.code;
    var shareToken = generateShareToken();
    var ekey = emailKey(owner.email);
    var members = {};
    members[ekey] = {
      email: owner.email,
      name: owner.name || (owner.email || '擁有者'),
      role: 'owner',
      ready: true,
      prefs: normalizePrefs(owner.prefs),
      joinedAt: Date.now()
    };
    var tripRef = db().collection('micro_trips').doc(trip.id);
    await tripRef.set({
      id: trip.id,
      // lobby 顯示用的基本欄位（行程內容稍後由 owner 從精靈以 updateSharedTripParams 補上）
      title: trip.title || '未命名共編行程',
      emoji: trip.emoji || '👥',
      days: trip.days || '',
      region: trip.region || '',
      collab: true,
      ownerUid: owner.uid || null,
      ownerEmail: owner.email || null,
      ownerName: owner.name || null,
      // 存正規化（去連字號）邀請碼：安全規則的加入分支用 exists(invites/{inviteCode}) 檢查，
      // 而 invite 文件是以 normalizeCode(code) 為 docId，兩者必須一致，否則所有加入都會 permission-denied。
      inviteCode: normalizeCode(code),
      shareToken: shareToken,
      maxMembers: MAX_MEMBERS,
      memberEmails: [owner.email],
      editorEmails: [],
      members: members,
      userEmail: owner.email || 'unknown',
      collabCreatedAt: serverTs()
    }, { merge: true });
    await reserved.ref.set({
      tripId: trip.id,
      active: true,
      createdBy: owner.email || null,
      createdAt: serverTs()
    });
    return { code: code, shareToken: shareToken };
  }

  // 以邀請碼加入：後端驗證邀請碼並簽發短效 proof，再加 member（預設 viewer）。
  // user：{ uid,email,name,prefs }；回傳該共用行程 doc data（含 id）。
  async function joinByCode(code, user) {
    var norm = normalizeCode(code);
    if (!norm) throw new Error('請輸入邀請碼。');
    var verified = await verifyInviteCode(norm);
    var tripRef = db().collection('micro_trips').doc(verified.tripId);
    var already = false;
    await db().runTransaction(async function (tx) {
      var tripSnap = await tx.get(tripRef);
      if (!tripSnap.exists) throw new Error('行程不存在或已被刪除。');
      var data = tripSnap.data();
      assertMemberIdentity(data, user.email);
      var emails = memberEmailsWithOwner(data);
      var ekey = emailKey(user.email);
      already = emails.some(function (email) { return sameEmail(email, user.email); });
      var maxMembers = Math.min(Math.max(Number(data.maxMembers) || MAX_MEMBERS, 1), 50);
      if (!already && emails.length >= maxMembers) throw new Error('這個行程人數已滿（上限 ' + maxMembers + ' 人，含擁有者）。');
      if (!already) {
        // 注意：set(merge) 不支援「點號路徑 key」（會被當字面欄位名），必須用巢狀物件才能寫進 members map。
        var membersPatch = {};
        membersPatch[ekey] = {
          email: user.email,
          name: user.name || (user.email || '旅伴'),
          role: 'viewer', // 預設唯讀
          ready: false,
          prefs: normalizePrefs(user.prefs),
          joinedAt: Date.now()
        };
        // 只寫 memberEmails / members，對齊安全規則「加入」分支的 hasOnly(['memberEmails','members'])。
        // 不碰 editorEmails：剛加入者本來就不在 editor 名單；若在此對「無 editorEmails 欄位的舊行程」
        // 下 arrayRemove，會把欄位從無建成 []，讓 affectedKeys 多一個 editorEmails → 規則擋下加入。
        // 降級成員的 editor 移除，由 owner 的 setMemberRole 負責。
        tx.set(tripRef, {
          memberEmails: firebase.firestore.FieldValue.arrayUnion(user.email),
          members: membersPatch
        }, { merge: true });
      }
    });
    var fresh = await tripRef.get();
    // alreadyMember 為暫態旗標（不寫進 Firestore），供前端區分「重新加入」與「首次加入」
    return Object.assign({ id: verified.tripId, alreadyMember: already }, fresh.data());
  }

  // 加入前預覽：只讀取邀請碼對應的行程摘要，讓前端先請使用者確認。
  async function previewByCode(code) {
    var norm = normalizeCode(code);
    if (!norm) throw new Error('請輸入邀請碼。');
    var verified = await verifyInviteCode(norm);
    return Object.assign({}, verified.preview, { isMember: !!verified.alreadyMember });
  }

  // 更新共用行程的「行程參數」（title/region/days/budget/people/wizardData…）。
  // 只合併傳入欄位，絕不碰 members / memberEmails / inviteCode 等協作欄位。
  async function updateSharedTripParams(tripId, patch) {
    await db().collection('micro_trips').doc(tripId).set(patch || {}, { merge: true });
  }

  // owner 調整成員角色（巢狀物件 + merge：深合併，只改 role 不動其他欄位）
  async function setMemberRole(tripId, memberEmail, role) {
    var allowed = ['owner', 'editor', 'viewer'];
    if (allowed.indexOf(role) === -1) throw new Error('未知角色：' + role);
    var ref = db().collection('micro_trips').doc(tripId);
    await db().runTransaction(async function (tx) {
      var snap = await tx.get(ref);
      if (!snap.exists) throw new Error('行程不存在');
      assertMemberIdentity(snap.data(), memberEmail);
      if (!memberForEmail(snap.data(), memberEmail)) throw new Error('找不到此成員');
      var membersPatch = {};
      membersPatch[emailKey(memberEmail)] = { role: role };
      var rolePatch = { members: membersPatch };
      if (role === 'editor') rolePatch.editorEmails = firebase.firestore.FieldValue.arrayUnion(memberEmail);
      else rolePatch.editorEmails = firebase.firestore.FieldValue.arrayRemove(memberEmail);
      tx.set(ref, rolePatch, { merge: true });
    });
  }

  // 成員更新自己的偏好（興趣/節奏/預算/希望景點；avoid 來自帳號設定）
  async function setMemberPrefs(tripId, memberEmail, prefs) {
    var user = firebase.auth().currentUser;
    if (!user || user.email !== memberEmail) throw new Error('只能儲存自己的偏好');
    var tripRef = db().collection('micro_trips').doc(tripId);
    var prefRef = tripRef.collection('member_prefs').doc(emailKey(memberEmail));
    await db().runTransaction(async function (tx) {
      var snap = await tx.get(tripRef);
      if (!snap.exists) throw new Error('行程不存在');
      assertMemberIdentity(snap.data(), memberEmail);
      var pref = await tx.get(prefRef);
      if (pref.exists && !sameEmail(pref.data().email, memberEmail)) throw new Error('此偏好紀錄的帳號資料衝突，請聯絡管理者。');
      tx.set(prefRef, {
        email: memberEmail,
        prefs: normalizePrefs(prefs),
        ready: true,
        updatedAt: serverTs()
      }, { merge: true });
    });
  }

  // owner 撤銷整個行程的邀請碼（不再可加入新成員）
  async function revokeInvite(tripId, code) {
    if (code) await db().collection('invites').doc(normalizeCode(code)).set({ active: false }, { merge: true });
  }

  // 成員「離開」共用行程：把自己從 memberEmails 與 members map 移除。
  // 用於非 owner 成員在「我的微旅行」刪除 collab 行程時，真正退出（否則 memberEmails
  // 仍含其 email，下次登入 fetchMyCollabTrips 的 array-contains 又會把行程抓回來）。
  async function leaveSharedTrip(tripId, email) {
    if (!email) return;
    var ref = db().collection('micro_trips').doc(tripId);
    await db().runTransaction(async function (tx) {
      var snap = await tx.get(ref);
      if (!snap.exists) return;
      var patch = {};
      // 已存在碰撞的舊資料也能退出，但不能刪掉另一位成員的 map。
      if (memberForEmail(snap.data(), email)) patch[emailKey(email)] = firebase.firestore.FieldValue.delete();
      var changes = {
        memberEmails: firebase.firestore.FieldValue.arrayRemove(email),
        editorEmails: firebase.firestore.FieldValue.arrayRemove(email)
      };
      // Firestore merge 中的空 map 會清空既有 map，沒有自己的項目時必須省略。
      if (Object.keys(patch).length) changes.members = patch;
      tx.set(ref, changes, { merge: true });
    });
  }

  // owner 刪除整個共用行程：清 micro_trips doc（規則允許 owner 刪）+ 停用邀請碼。
  // 用於使用者在「我的微旅行」刪除 collab 行程時連遠端一起清，避免孤兒佔 Firestore。
  async function deleteSharedTrip(tripId, inviteCode) {
    if (inviteCode) {
      try { await db().collection('invites').doc(normalizeCode(inviteCode)).set({ active: false }, { merge: true }); }
      catch (e) { /* 規則禁刪 invites，停用失敗就略過 */ }
    }
    await db().collection('micro_trips').doc(tripId).delete();
  }

  // 訂閱共用行程（成員清單 / 內容即時更新；用於「同看一份」）
  function subscribeSharedTrip(tripId, onChange, onError) {
    return db().collection('micro_trips').doc(tripId).onSnapshot(function (snap) {
      if (!snap.exists) { if (onError) onError(new Error('行程已不存在')); return; }
      onChange(Object.assign({ id: tripId }, snap.data()));
    }, function (err) { if (onError) onError(err); });
  }

  function subscribeMemberPrefs(tripId, onChange, onError) {
    return db().collection('micro_trips').doc(tripId).collection('member_prefs').onSnapshot(function (snap) {
      var prefsByKey = {};
      snap.forEach(function (doc) { prefsByKey[doc.id] = doc.data(); });
      onChange(prefsByKey);
    }, function (err) { if (onError) onError(err); });
  }

  // 重生成鎖：避免 owner 與可編輯成員同時重生成互相覆蓋（最後寫入者覆蓋）。
  var REGEN_LOCK_TTL_MS = 5 * 60 * 1000; // 鎖逾時自動失效，避免當機留死鎖
  // 用 transaction 原子地檢查+設定：別人鎖住且未逾時 → { ok:false, holder }；否則上鎖 → { ok:true }
  async function acquireRegenLock(tripId, user) {
    var dbi = db();
    var ref = dbi.collection('micro_trips').doc(tripId);
    var myEmail = (user && user.email) || '';
    return dbi.runTransaction(function (tx) {
      return tx.get(ref).then(function (snap) {
        if (!snap.exists) throw new Error('行程不存在');
        var lock = snap.data().regenLock;
        var now = Date.now();
        var lockAt = lock && lock.at && typeof lock.at.toMillis === 'function'
          ? lock.at.toMillis()
          : Number(lock && lock.at);
        if (lock && Number.isFinite(lockAt) && (now - lockAt) < REGEN_LOCK_TTL_MS && lock.by !== myEmail) {
          return { ok: false, holder: lock.byName || lock.by || '其他成員' };
        }
        tx.set(ref, { regenLock: {
          by: myEmail,
          byName: (user && user.name) || myEmail || '成員',
          at: firebase.firestore.FieldValue.serverTimestamp()
        } }, { merge: true });
        return { ok: true };
      });
    });
  }
  async function releaseRegenLock(tripId) {
    try {
      await db().collection('micro_trips').doc(tripId).set(
        { regenLock: firebase.firestore.FieldValue.delete() }, { merge: true });
    } catch (e) { /* 清鎖失敗不致命，TTL 也會自動失效 */ }
  }

  // 單次讀取一份共用行程（含最新 members），供生成前彙整成員偏好用
  async function getSharedTrip(tripId) {
    var snap = await db().collection('micro_trips').doc(tripId).get();
    if (!snap.exists) return null;
    var trip = Object.assign({ id: tripId }, snap.data());
    var prefsSnap = await db().collection('micro_trips').doc(tripId).collection('member_prefs').get();
    var members = Object.assign({}, trip.members || {});
    prefsSnap.forEach(function (doc) {
      var pref = doc.data();
      if (members[doc.id] && sameEmail(members[doc.id].email, pref.email)) {
        members[doc.id] = Object.assign({}, members[doc.id], { prefs: pref.prefs, ready: pref.ready });
      }
    });
    trip.members = members;
    return trip;
  }

  // 載入「我以成員身分加入」的共用行程（array-contains 我的 email）
  async function fetchMyCollabTrips(email) {
    if (!email) return [];
    var snap = await db().collection('micro_trips').where('memberEmails', 'array-contains', email).get();
    return snap.docs.map(function (d) { return Object.assign({ id: d.id }, d.data()); });
  }

  // 「我的行程」即時來源：名稱、角色、狀態或內容變更後，不必重新整理列表。
  function subscribeMyCollabTrips(email, onChange, onError) {
    if (!email) return function () {};
    return db().collection('micro_trips').where('memberEmails', 'array-contains', email)
      .onSnapshot(function (snap) {
        onChange(snap.docs.map(function (d) { return Object.assign({ id: d.id }, d.data()); }));
      }, function (err) { if (onError) onError(err); });
  }

  // 訪客唯讀：由後端核對 shareToken 並只回傳去敏後的行程欄位。
  async function loadGuestTrip(tripId, token) {
    if (!tripId || !token) throw new Error('分享連結無效。');
    var query = '?tripId=' + encodeURIComponent(tripId) + '&token=' + encodeURIComponent(token);
    var response = await fetch('/api/collab/public-trip' + query, { headers: { Accept: 'application/json' } });
    var body = {};
    try { body = await response.json(); } catch (_e) {}
    if (!response.ok || !body.trip) throw new Error(body.message || '分享的行程不存在或已失效。');
    return body.trip;
  }

  async function requestTripJoin(tripId, token) {
    if (!tripId || !token) throw new Error('分享連結不完整。');
    return collabApi('join-requests', {
      method: 'POST',
      body: JSON.stringify({ tripId: tripId, token: token })
    });
  }

  async function getTripJoinRequestStatus(tripId, token) {
    if (!tripId || !token) return { status: 'none' };
    var query = 'join-requests/status?tripId=' + encodeURIComponent(tripId) + '&token=' + encodeURIComponent(token);
    return collabApi(query, { method: 'GET' });
  }

  async function resolveTripJoinRequest(tripId, requesterUid, decision) {
    if (!tripId || !requesterUid || ['accept', 'reject'].indexOf(decision) === -1) {
      throw new Error('加入申請資料不完整。');
    }
    return collabApi('join-requests/resolve', {
      method: 'POST',
      body: JSON.stringify({
        tripId: tripId,
        requesterUid: requesterUid,
        decision: decision
      })
    });
  }

  function buildShareLink(tripId, shareToken, baseHref) {
    var base = baseHref || 'ai-travel-planner-v8.html';
    var relative = base + '?sharedId=' + encodeURIComponent(tripId) + '&token=' + encodeURIComponent(shareToken || '') + '&guest=1';
    try {
      // QR 掃描發生在另一台裝置，內容必須是完整 HTTPS URL，不能依賴目前頁面的相對路徑。
      return new URL(relative, window.location.href).href;
    } catch (_e) {
      return relative;
    }
  }

  // 把任意偏好物件正規化成固定形狀（避免 undefined 寫進 Firestore）
  function normalizePrefs(p) {
    p = p || {};
    return {
      interests: Array.isArray(p.interests) ? p.interests.filter(Boolean) : [],
      pace: p.pace || '平衡',
      avoid: typeof p.avoid === 'string' ? p.avoid : '',
      avoidTags: Array.isArray(p.avoidTags) ? p.avoidTags.filter(Boolean) : [],
      budget: p.budget || '',
      desiredSpots: typeof p.desiredSpots === 'string' ? p.desiredSpots : ''
    };
  }

  return {
    MAX_MEMBERS: MAX_MEMBERS,
    // 純函式（可測）
    generateInviteCode: generateInviteCode,
    generateShareToken: generateShareToken,
    normalizeCode: normalizeCode,
    emailKey: emailKey,
    sameEmail: sameEmail,
    memberForEmail: memberForEmail,
    resolveRole: resolveRole,
    memberEmailsWithOwner: memberEmailsWithOwner,
    identityKey: identityKey,
    parseBudgetNumber: parseBudgetNumber,
    formatBudget: formatBudget,
    majorityPace: majorityPace,
    aggregateGroupProfile: aggregateGroupProfile,
    buildGroupPreferenceLines: buildGroupPreferenceLines,
    normalizePrefs: normalizePrefs,
    roleLabel: roleLabel,
    canEdit: canEdit,
    isFull: isFull,
    buildShareLink: buildShareLink,
    formatInviteCode: formatInviteCode,
    // Firestore
    createSharedTrip: createSharedTrip,
    updateSharedTripParams: updateSharedTripParams,
    joinByCode: joinByCode,
    previewByCode: previewByCode,
    setMemberRole: setMemberRole,
    deleteSharedTrip: deleteSharedTrip,
    leaveSharedTrip: leaveSharedTrip,
    setMemberPrefs: setMemberPrefs,
    revokeInvite: revokeInvite,
    subscribeSharedTrip: subscribeSharedTrip,
    subscribeMemberPrefs: subscribeMemberPrefs,
    getSharedTrip: getSharedTrip,
    acquireRegenLock: acquireRegenLock,
    releaseRegenLock: releaseRegenLock,
    fetchMyCollabTrips: fetchMyCollabTrips,
    subscribeMyCollabTrips: subscribeMyCollabTrips,
    ensureTripShareToken: ensureTripShareToken,
    loadGuestTrip: loadGuestTrip,
    requestTripJoin: requestTripJoin,
    getTripJoinRequestStatus: getTripJoinRequestStatus,
    resolveTripJoinRequest: resolveTripJoinRequest
  };
})();
