/**
 * friends.js — TravelLinkAI 好友模組（window.WAI_FRIENDS）
 *
 * 範圍（Week4 D1；群組功能已於 UIUX 調整時移除）：
 *   - 好友：邀請(pending)→接受(accepted)；單方刪除=拒絕/取消/解除三合一。
 *   - 公開檔案 user_profiles：好友 email 搜尋用的最小鏡像（users/{uid} 僅本人可讀）。
 *
 * 資料模型（★schema 需與 Android 對齊，週會確認）：
 *   user_profiles/{uid}    -> 本人檔案（不可列舉）
 *   user_profiles_by_email/{identityKey(email)} -> 好友精確搜尋用最小鏡像（只允許 get）
 *   friendships/{docId}    -> { emails:[a,b], fromEmail, toEmail, names:{<ekey>:name},
 *                               status:'pending'|'accepted', createdAt, acceptedAt }
 *                              docId = 兩個 emailKey 排序後以 '__' 相接（防重複邀請）
 *
 * 慣例比照 collab.js：IIFE、呼叫時才讀全域 firebaseDb；emailKey 直接複用 WAI_COLLAB.emailKey
 * （index.html 的 <script> 順序保證 collab.js 先載入）。
 */
window.WAI_FRIENDS = (function () {
  'use strict';

  function db() {
    if (typeof firebaseDb === 'undefined' || !firebaseDb) {
      throw new Error('Firebase 尚未初始化，無法使用好友功能。');
    }
    return firebaseDb;
  }
  function serverTs() { return firebase.firestore.FieldValue.serverTimestamp(); }
  function ekey(email) { return window.WAI_COLLAB.identityKey(email); }
  function legacyEkey(email) { return window.WAI_COLLAB.emailKey(email); }
  function wait(ms) { return new Promise(function (resolve) { setTimeout(resolve, ms); }); }

  async function writeSearchIndex(me) {
    var lastError = null;
    // Rules 剛部署或網路短暫抖動時，第一次寫入可能失敗；merge 寫入可安全重試。
    for (var attempt = 0; attempt < 3; attempt++) {
      try {
        await db().collection('user_profiles_by_email').doc(ekey(me.email)).set({
          email: me.email,
          name: me.name || me.email,
          emoji: me.emoji || '🌟',
          updatedAt: serverTs()
        }, { merge: true });
        return;
      } catch (error) {
        lastError = error;
        if (attempt < 2) await wait(300 * (attempt + 1));
      }
    }
    throw lastError;
  }

  // ── 純函式 ──
  // 好友關係文件 id：兩端 emailKey 排序後相接，A→B 與 B→A 天生撞同一 doc（防重複邀請）
  function friendshipId(emailA, emailB) {
    return [ekey(emailA), ekey(emailB)].sort().join('__');
  }

  // ── user_profiles：最小公開檔案 ──
  // 登入/註冊時同步自己的公開檔案（好友搜尋靠它）。安全規則驗 email==token，只能寫自己的。
  async function syncMyProfile(me) {
    if (!me || !me.uid || !me.email) return;
    var profile = {
      uid: me.uid,
      email: me.email,
      emailLower: String(me.email).trim().toLowerCase(), // 搜尋鍵：等式查詢免大小寫問題
      name: me.name || me.email,
      emoji: me.emoji || '🌟',
      updatedAt: serverTs()
    };
    // 兩份資料獨立同步：其中一份失敗時，另一份仍會嘗試寫入。
    // 索引失敗不可靜默吞掉，讓登入流程留下可診斷的 warning，下一次登入仍會補寫。
    var results = await Promise.allSettled([
      db().collection('user_profiles').doc(me.uid).set(profile, { merge: true }),
      writeSearchIndex(me)
    ]);
    if (results[1].status === 'rejected') throw results[1].reason;
    if (results[0].status === 'rejected') throw results[0].reason;
    return { profileSynced: true, searchIndexSynced: true };
  }

  // 以 email 精確查公開檔案（找不到回 null——對方需登入過 TravelLinkAI 才有檔案）
  async function findProfileByEmail(email) {
    var q = String(email || '').trim().toLowerCase();
    if (!q) return null;
    try {
      var snap = await db().collection('user_profiles_by_email').doc(ekey(q)).get();
      if (snap.exists) return snap.data();
    } catch (_indexError) {
      // 舊 Rules 過渡期才會走到這裡；新 Rules 禁止 user_profiles list/query。
      var legacySnap = await db().collection('user_profiles').where('emailLower', '==', q).limit(1).get();
      return legacySnap.empty ? null : legacySnap.docs[0].data();
    }
    return null;
  }

  // ── friendships：邀請 → 接受 ──
  // 送出邀請。直接嘗試建立；若文件已存在，再讀回判斷 pending／accepted。
  // 不可先 ref.get() 查重：新文件尚不存在時，Rules 無 resource.data 可驗證當事人，
  // 讀取會先被 permission-denied 擋住，導致第一封邀請永遠無法建立。
  // me/target = { email, name }；createdAt 必須 serverTimestamp（規則驗 ==request.time）。
  async function sendInvite(me, target) {
    if (!me || !me.email) throw new Error('請先登入。');
    if (!target || !target.email) throw new Error('找不到對方資料。');
    if (ekey(me.email) === ekey(target.email)) throw new Error('不能加自己為好友。');
    // 先以當事人查詢找既有關係，兼容舊版 emailKey 文件 id，也避免對不存在文件 get 被 Rules 擋下。
    var related = await db().collection('friendships').where('emails', 'array-contains', me.email).get();
    var existing = null;
    related.forEach(function (doc) {
      if (existing) return;
      var data = doc.data() || {};
      var hasTarget = (data.emails || []).some(function (email) {
        return String(email || '').toLowerCase() === String(target.email).toLowerCase();
      });
      if (hasTarget) existing = { id: doc.id, data: data };
    });
    if (existing) {
      if (existing.data.status === 'accepted') throw new Error('你們已經是好友了。');
      throw new Error(existing.data.fromEmail === me.email
        ? '已送出過邀請，等待對方確認中。'
        : '對方已邀請過你，請到「待確認邀請」接受。');
    }
    var id = friendshipId(me.email, target.email);
    var ref = db().collection('friendships').doc(id);
    var legacyId = [legacyEkey(me.email), legacyEkey(target.email)].sort().join('__');
    var cycleId = Date.now().toString(36) + '_' + Math.random().toString(36).slice(2, 10);
    var names = {};
    // 同時寫安全 key 與舊 key：新版不受碰撞影響，Android／舊 Web 仍能顯示名稱。
    names[ekey(me.email)] = me.name || me.email;
    names[ekey(target.email)] = target.name || target.email;
    names[legacyEkey(me.email)] = me.name || me.email;
    names[legacyEkey(target.email)] = target.name || target.email;
    try {
      await ref.set({
        emails: [me.email, target.email],
        fromEmail: me.email,
        toEmail: target.email,
        names: names,
        status: 'pending',
        cycleId: cycleId,
        createdAt: serverTs()
      });
    } catch (writeError) {
      // 前端先上線、Rules 尚未部署的短暫期間，舊規則只接受 legacy docId。
      // 新規則部署後安全 id 會直接成功，不會進入此分支。
      if (writeError && writeError.code === 'permission-denied' && legacyId !== id) {
        try {
          var legacyRef = db().collection('friendships').doc(legacyId);
          await legacyRef.set({
            emails: [me.email, target.email], fromEmail: me.email, toEmail: target.email,
            names: names, status: 'pending', createdAt: serverTs()
          });
          return { id: legacyId, cycleId: cycleId };
        } catch (_legacyWriteError) { /* 繼續回報原始安全寫入錯誤 */ }
      }
      // 同一對帳號共用固定 docId。既有文件上的 set 會被 update 規則拒絕，
      // 此時文件已存在且當事人可讀，才能安全地取回狀態並顯示精確訊息。
      try {
        var existing = await ref.get();
        if (existing.exists) {
          var st = existing.data().status;
          if (st === 'accepted') throw new Error('你們已經是好友了。');
          var from = existing.data().fromEmail;
          throw new Error(from === me.email
            ? '已送出過邀請，等待對方確認中。'
            : '對方已邀請過你，請到「待確認邀請」接受。');
        }
      } catch (readError) {
        if (readError && !readError.code) throw readError;
      }
      throw writeError;
    }
    return { id: id, cycleId: cycleId };
  }

  // 接受邀請（只有 toEmail 本人可呼叫；規則限 pending→accepted 且只動 status/acceptedAt）
  async function acceptInvite(friendshipDocId) {
    await db().collection('friendships').doc(friendshipDocId).update({
      status: 'accepted',
      acceptedAt: serverTs()
    });
  }

  // 刪除好友關係：拒絕邀請／取消已送邀請／解除好友（規則允許任一當事人）
  async function removeFriendship(friendshipDocId) {
    await db().collection('friendships').doc(friendshipDocId).delete();
  }

  // 訂閱與我有關的所有好友關係（array-contains 我的 email → 規則查詢可證明）。
  // cb 收 [{id, ...data}]，呼叫端自行分三堆：accepted／收到的 pending／送出的 pending。
  function subscribeFriendships(myEmail, onChange, onError) {
    if (!myEmail) return function () {};
    return db().collection('friendships').where('emails', 'array-contains', myEmail)
      .onSnapshot(function (snap) {
        onChange(snap.docs.map(function (d) { return Object.assign({ id: d.id }, d.data()); }));
      }, function (err) { if (onError) onError(err); });
  }

  return {
    friendshipId: friendshipId,
    syncMyProfile: syncMyProfile,
    findProfileByEmail: findProfileByEmail,
    sendInvite: sendInvite,
    acceptInvite: acceptInvite,
    removeFriendship: removeFriendship,
    subscribeFriendships: subscribeFriendships
  };
})();
