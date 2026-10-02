/**
 * notifications.js — TravelLinkAI 通知中心資料層（window.WAI_NOTIFY）
 *
 * 資料模型（F1）：
 *   user_notifications/{identityKey}/items/{nid}
 *     -> { type, fromEmail, toEmail, fromName, tripId, tripTitle, message, read:false, createdAt }
 *   identityKey 使用 email 的無碰撞 UTF-8 hex；nid 為「確定性 id」（同事件重送會撞同一 doc，
 *   安全規則只允許 create，第二次寫入 permission-denied → 靜默吞掉＝天生防打擾）。
 *
 * type 白名單（與 firestore.rules 對齊）：
 *   collab_invite / friend_invite / friend_accept / trip_renamed / trip_regenerated / friend_trip_completed
 *
 * 慣例比照 friends.js：IIFE、呼叫時才讀全域 firebaseDb / firebase；
 * 所有寫入自帶 try/catch 靜默（rules 未部署前不噴紅字，僅 console.warn）。
 */
window.WAI_NOTIFY = (function () {
  'use strict';

  // session 內去重：同一 (收件人, nid) 只嘗試寫一次，避免重複打 Firestore
  var _sessionSent = {};
  var _sessionPending = {};

  function db() {
    if (typeof firebaseDb === 'undefined' || !firebaseDb) return null;
    return firebaseDb;
  }
  function ekey(email) {
    if (window.WAI_COLLAB && typeof WAI_COLLAB.identityKey === 'function') return WAI_COLLAB.identityKey(email);
    var normalized = String(email || '').trim().toLowerCase();
    if (!normalized) return '';
    var bytes = new TextEncoder().encode(normalized);
    return Array.prototype.map.call(bytes, function (b) { return b.toString(16).padStart(2, '0'); }).join('');
  }
  function me() {
    try {
      var u = (typeof firebase !== 'undefined' && firebase.auth) ? firebase.auth().currentUser : null;
      return u ? { email: u.email || '', name: u.displayName || '' } : null;
    } catch (_e) { return null; }
  }

  // 確定性 nid：parts 逐段淨化後以 '__' 相接（如 ['collab_invite', tripId, fromKey]）
  function nid(parts) {
    return (parts || []).map(function (p) {
      return String(p == null ? '' : p).replace(/[^A-Za-z0-9_-]/g, '_');
    }).filter(Boolean).join('__').slice(0, 300);
  }

  // 寫一則通知給 toEmail。失敗（rules 未部署 / 已存在 / 未登入）一律靜默。
  async function push(toEmail, id, payload) {
    try {
      var dbi = db();
      var sender = me();
      if (!dbi || !sender || !sender.email || !toEmail || !id) return false;
      if (ekey(toEmail) === ekey(sender.email)) return false; // 不通知自己
      var dedupeKey = ekey(toEmail) + '/' + id;
      if (_sessionSent[dedupeKey] || _sessionPending[dedupeKey]) return false;
      _sessionPending[dedupeKey] = true;
      var p = payload || {};
      await dbi.collection('user_notifications').doc(ekey(toEmail)).collection('items').doc(id).set({
        type: String(p.type || ''),
        fromEmail: sender.email,
        toEmail: String(toEmail).trim(),
        fromName: String(p.fromName || sender.name || sender.email).slice(0, 100),
        tripId: String(p.tripId || '').slice(0, 150),
        tripTitle: String(p.tripTitle || '').slice(0, 200),
        message: String(p.message || '').slice(0, 200),
        read: false,
        createdAt: firebase.firestore.FieldValue.serverTimestamp()
      });
      _sessionSent[dedupeKey] = true;
      return true;
    } catch (e) {
      // 確定性 docId 已存在（重送）或 rules 未部署 → permission-denied，一律靜默
      console.warn('[notify] push skipped:', e && e.code || e && e.message || e);
      return false;
    } finally {
      if (typeof dedupeKey !== 'undefined') delete _sessionPending[dedupeKey];
    }
  }

  // 群發（排除自己）。id 可為字串或 function(email)→字串。
  async function pushToMany(emails, idOrFn, payload) {
    var sender = me();
    var myKey = sender ? ekey(sender.email) : '';
    var list = (emails || []).filter(function (e) { return e && ekey(e) !== myKey; });
    await Promise.all(list.map(function (e) {
      var id = (typeof idOrFn === 'function') ? idOrFn(e) : idOrFn;
      return push(e, id, payload);
    }));
  }

  // 訂閱自己的通知（新到舊，最多 50 則）。回傳 unsubscribe。
  function subscribe(myEmail, onChange, onError) {
    var dbi = db();
    if (!dbi || !myEmail) return function () {};
    return dbi.collection('user_notifications').doc(ekey(myEmail)).collection('items')
      .orderBy('createdAt', 'desc').limit(50)
      .onSnapshot(function (snap) {
        onChange(snap.docs.map(function (d) { return Object.assign({ id: d.id }, d.data()); }));
      }, function (err) {
        console.warn('[notify] subscribe failed:', err && err.code || err);
        if (onError) onError(err);
      });
  }

  async function markRead(myEmail, itemId) {
    try {
      var dbi = db();
      if (!dbi || !myEmail || !itemId) return;
      await dbi.collection('user_notifications').doc(ekey(myEmail)).collection('items').doc(itemId).update({ read: true });
    } catch (e) { console.warn('[notify] markRead failed:', e && e.code || e); }
  }

  async function markAllRead(myEmail, items) {
    try {
      var dbi = db();
      if (!dbi || !myEmail) return;
      var unread = (items || []).filter(function (n) { return n && !n.read && n.id; });
      if (!unread.length) return;
      var batch = dbi.batch();
      var col = dbi.collection('user_notifications').doc(ekey(myEmail)).collection('items');
      unread.slice(0, 50).forEach(function (n) { batch.update(col.doc(n.id), { read: true }); });
      await batch.commit();
    } catch (e) { console.warn('[notify] markAllRead failed:', e && e.code || e); }
  }

  // 取「已成立好友」的 email 清單（planner 完成行程通知好友用）。
  // 查詢與 friends.js subscribeFriendships 同形（array-contains），status 過濾在 client 端做，
  // 避免 array-contains + == 需要複合索引。失敗回 []。
  async function fetchAcceptedFriendEmails(myEmail) {
    try {
      var dbi = db();
      if (!dbi || !myEmail) return [];
      var snap = await dbi.collection('friendships').where('emails', 'array-contains', myEmail).get();
      var out = [];
      snap.forEach(function (doc) {
        var f = doc.data() || {};
        if (f.status !== 'accepted') return;
        (f.emails || []).forEach(function (e) {
          if (e && e !== myEmail && out.indexOf(e) === -1) out.push(e);
        });
      });
      return out;
    } catch (e) {
      console.warn('[notify] fetchAcceptedFriendEmails failed:', e && e.code || e);
      return [];
    }
  }

  return {
    nid: nid,
    push: push,
    pushToMany: pushToMany,
    subscribe: subscribe,
    markRead: markRead,
    markAllRead: markAllRead,
    fetchAcceptedFriendEmails: fetchAcceptedFriendEmails
  };
})();
