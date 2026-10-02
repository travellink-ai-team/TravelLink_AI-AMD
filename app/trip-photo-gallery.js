/**
 * trip-photo-gallery.js — TravelLink AI 多人旅程相簿 UI
 *
 * 這個模組不直接依賴 Firebase。主頁可以注入 adapter，也可以直接沿用
 * window.TripPhotoManager。adapter 最小介面：
 *   listPhotos({ tripId }) -> Photo[] | { photos, visited, memories }
 * 可選介面：subscribe、updateClassification、retry、remove、can。
 */
(function (root, factory) {
  'use strict';
  var api = factory();
  if (typeof module === 'object' && module.exports) module.exports = api;
  if (root) root.TripPhotoGallery = api;
})(typeof window !== 'undefined' ? window : (typeof globalThis !== 'undefined' ? globalThis : this), function () {
  'use strict';

  var STATUS_LABELS = Object.freeze({
    queued: '等待上傳',
    uploading: '上傳中',
    publishing: '同步中',
    synced: '已同步',
    failed: '同步失敗',
    'local-only': '僅存在此裝置'
  });

  var state = null;

  function text(value) { return String(value == null ? '' : value); }
  function finite(value) { var number = Number(value); return Number.isFinite(number) ? number : null; }
  function escapeHtml(value) {
    return text(value).replace(/[&<>"']/g, function (character) {
      return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[character];
    });
  }
  function slug(value) {
    var source = text(value);
    var hash = 2166136261;
    for (var i = 0; i < source.length; i++) {
      hash ^= source.charCodeAt(i);
      hash = Math.imul(hash, 16777619);
    }
    return (hash >>> 0).toString(36);
  }
  function ownerOf(photo) {
    var owner = photo && photo.owner;
    var uid = text(photo && (photo.ownerUid || (owner && owner.uid) || photo.uid));
    var name = text((typeof owner === 'string' ? owner : owner && (owner.name || owner.displayName))
      || photo && (photo.ownerName || photo.authorName || photo.uploaderName));
    return { uid: uid, name: name || '旅伴' };
  }
  function urlOf(photo) {
    return text(photo && (photo.url || photo.downloadURL || photo.thumbnailUrl
      || (photo.remote && (photo.remote.url || photo.remote.downloadURL))));
  }
  function capturedAtOf(photo) {
    return finite(photo && (photo.capturedAt || photo.ts || photo.createdAt || photo.uploadedAt)) || 0;
  }
  function dayKeyOf(photo) {
    if (photo && photo.dayKey) return text(photo.dayKey);
    var epoch = capturedAtOf(photo);
    if (!epoch) return 'pending-day';
    var date = new Date(epoch);
    if (!Number.isFinite(date.getTime())) return 'pending-day';
    return [date.getFullYear(), String(date.getMonth() + 1).padStart(2, '0'), String(date.getDate()).padStart(2, '0')].join('-');
  }
  function stablePhotoSort(photos) {
    return (Array.isArray(photos) ? photos : []).map(function (photo, index) {
      return { photo: photo, index: index };
    }).sort(function (a, b) {
      var ao = finite(a.photo.manualOrder);
      var bo = finite(b.photo.manualOrder);
      if (ao != null || bo != null) {
        if (ao == null) return 1;
        if (bo == null) return -1;
        if (ao !== bo) return ao - bo;
      }
      var at = capturedAtOf(a.photo) || Infinity;
      var bt = capturedAtOf(b.photo) || Infinity;
      return at - bt || text(a.photo.id).localeCompare(text(b.photo.id)) || a.index - b.index;
    }).map(function (entry) { return entry.photo; });
  }
  function normalizePhoto(photo, defaults) {
    defaults = defaults || {};
    photo = photo || {};
    var owner = ownerOf(Object.assign({}, defaults, photo));
    var url = urlOf(photo);
    var id = text(photo.id || photo.photoId || photo.path || (url ? 'legacy-' + slug(url) : 'photo-' + slug(JSON.stringify(photo))));
    var stopId = photo.stopId == null || photo.stopId === '' ? null : text(photo.stopId);
    var classification = photo.classification || {};
    return Object.assign({}, photo, {
      id: id,
      tripId: text(photo.tripId || defaults.tripId),
      dayKey: text(photo.dayKey || defaults.dayKey || dayKeyOf(photo)),
      stopId: stopId || (defaults.stopId == null ? null : text(defaults.stopId)),
      stopName: text(photo.stopName || defaults.stopName),
      owner: owner,
      ownerUid: owner.uid,
      ownerName: owner.name,
      url: url,
      capturedAt: capturedAtOf(photo),
      status: text(photo.status || defaults.status || 'synced'),
      source: text(photo.source || defaults.source || 'adapter'),
      readOnly: Boolean(photo.readOnly || defaults.readOnly),
      pending: !stopId && defaults.stopId == null
        || classification.group === 'unassigned'
        || classification.confidence === 'low'
    });
  }
  function stopLookup(stops) {
    var result = {};
    (Array.isArray(stops) ? stops : []).forEach(function (stop, index) {
      if (!stop) return;
      var id = text(stop.collabStopId || stop.stopId || stop.id || ('stop-' + index));
      result[id] = text(stop.name || stop.title || ('景點 ' + (index + 1)));
    });
    return result;
  }
  function flattenSources(input, context) {
    context = context || {};
    var result = [];
    var payload = Array.isArray(input) ? { photos: input } : (input || {});
    (payload.photos || []).forEach(function (photo) {
      result.push(normalizePhoto(photo, { tripId: context.tripId }));
    });
    (payload.visited || payload.visitedSpots || []).forEach(function (place) {
      (place && Array.isArray(place.photos) ? place.photos : []).forEach(function (photo) {
        result.push(normalizePhoto(photo, {
          tripId: place.tripId || context.tripId,
          dayKey: place.visitDate,
          stopId: place.stopId,
          stopName: place.name,
          source: 'visited',
          readOnly: Boolean(photo && photo.foreign)
        }));
      });
    });
    (payload.memories || []).forEach(function (memory) {
      var memoryOwner = { uid: text(memory.uid || memory.ownerUid), name: text(memory.ownerName || '旅伴') };
      var spots = memory && memory.spots || {};
      Object.keys(spots).forEach(function (stopId) {
        var spot = spots[stopId] || {};
        (Array.isArray(spot.photos) ? spot.photos : []).forEach(function (photo, index) {
          var value = typeof photo === 'string' ? { url: photo } : photo;
          result.push(normalizePhoto(value, {
            tripId: memory.tripId || context.tripId,
            dayKey: spot.dayKey,
            stopId: stopId,
            stopName: spot.spotName,
            owner: memoryOwner,
            ownerUid: memoryOwner.uid,
            ownerName: memoryOwner.name,
            source: 'memory',
            readOnly: true,
            status: 'synced',
            id: 'memory-' + slug(stopId + ':' + index + ':' + urlOf(value))
          }));
        });
      });
    });
    var seen = new Set();
    return stablePhotoSort(result.filter(function (photo) {
      if (context.tripId && photo.tripId && text(photo.tripId) !== text(context.tripId)) return false;
      var key = photo.id || photo.url;
      if (!key || seen.has(key)) return false;
      seen.add(key);
      return true;
    }));
  }
  function isPending(photo) {
    return Boolean(photo && (photo.pending || !photo.stopId
      || photo.classification && (photo.classification.group === 'unassigned' || photo.classification.confidence === 'low')));
  }
  function filterPhotos(photos, filter, actorUid) {
    filter = text(filter || 'all');
    actorUid = text(actorUid);
    return (photos || []).filter(function (photo) {
      var uid = ownerOf(photo).uid;
      if (filter === 'pending') return isPending(photo);
      if (filter === 'mine') return Boolean(actorUid) && uid === actorUid;
      if (filter.indexOf('member:') === 0) return uid === filter.slice(7);
      return true;
    });
  }
  function groupPhotos(photos, stops) {
    var names = stopLookup(stops);
    var groups = {};
    stablePhotoSort(photos).forEach(function (photo) {
      var day = text(photo.dayKey || 'pending-day');
      var stop = text(photo.stopId || 'unassigned');
      var key = day + '\u0000' + stop;
      if (!groups[key]) groups[key] = {
        key: key,
        dayKey: day,
        stopId: stop,
        stopName: stop === 'unassigned' ? '待整理' : text(photo.stopName || names[stop] || '未命名景點'),
        photos: []
      };
      groups[key].photos.push(photo);
    });
    return Object.keys(groups).map(function (key) { return groups[key]; }).sort(function (a, b) {
      if (a.dayKey === 'pending-day') return 1;
      if (b.dayKey === 'pending-day') return -1;
      return a.dayKey.localeCompare(b.dayKey) || (a.stopId === 'unassigned' ? 1 : b.stopId === 'unassigned' ? -1 : 0);
    });
  }
  /* 上傳者標籤。身分一直都是 uid，name 只是給人看的字串——所以兩個不同帳號取同樣的
     暱稱時，畫面上會出現兩個一模一樣的「小明」，看不出哪張是誰的。
     （這不是換欄位能解決的：改用 email 前綴一樣會撞，tim@gmail.com 與 tim@nttu.edu.tw
     都會顯示 tim。）
     做法是只在真的撞到時才加後綴，沒撞的人維持乾淨的名字。後綴取 uid 尾四碼——
     uid 本來就是這份資料的身分，不必為了消歧多存 email 進照片文件。 */
  function buildOwnerLabels(photos) {
    var byName = {};
    var names = {};
    // 名稱以正式照片為準：舊版 memories（readOnly）的 ownerName 可能是另一套來源，
    // 先看到它的話，同一個人會被標成另一個名字。
    var ordered = (photos || []).filter(function (p) { return p && !p.readOnly; })
      .concat((photos || []).filter(function (p) { return p && p.readOnly; }));
    ordered.forEach(function (photo) {
      var owner = ownerOf(photo);
      if (!owner.uid || names[owner.uid]) return;
      names[owner.uid] = owner.name;
    });
    Object.keys(names).forEach(function (uid) {
      var name = names[uid];
      if (!byName[name]) byName[name] = [];
      byName[name].push(uid);
    });
    var labels = {};
    Object.keys(names).forEach(function (uid) {
      var name = names[uid];
      labels[uid] = byName[name].length > 1
        ? name + ' #' + String(uid).slice(-4)
        : name;
    });
    return labels;
  }

  function ownerLabel(photo) {
    var owner = ownerOf(photo);
    var labels = state && state.ownerLabels;
    return (labels && owner.uid && labels[owner.uid]) || owner.name;
  }

  function memberOptions(photos) {
    var labels = buildOwnerLabels(photos);
    return Object.keys(labels).sort(function (a, b) { return labels[a].localeCompare(labels[b], 'zh-Hant'); })
      .map(function (uid) { return { uid: uid, name: labels[uid] }; });
  }
  function normalizeRole(role) {
    role = text(role || 'viewer').toLowerCase();
    return ['owner', 'editor', 'member', 'viewer'].indexOf(role) >= 0 ? role : 'viewer';
  }
  function defaultCan(action, photo, actor) {
    actor = actor || {};
    var role = normalizeRole(actor.role);
    var owns = Boolean(actor.uid) && text(actor.uid) === ownerOf(photo).uid;
    if (photo && photo.readOnly && action !== 'view') return false;
    if (role === 'owner') return true;
    if (action === 'view') return true;
    if (action === 'classify') return !photo.readOnly && (role === 'editor' || owns);
    if (action === 'retry') return owns && (role === 'editor' || role === 'member');
    // 方案 B：owner/editor 可協助管理（與 TripPhotoManager.can 及 Firestore Rules 一致）。
    // readOnly 仍然擋——那是舊版 memories 併進來的唯讀資料，本來就不該從相簿刪。
    if (action === 'delete') return !photo.readOnly && (role === 'editor' || (owns && role === 'member'));
    return false;
  }
  function formatDay(dayKey) {
    if (!dayKey || dayKey === 'pending-day') return '日期待確認';
    var parts = text(dayKey).split('-');
    return parts.length === 3 ? Number(parts[1]) + ' 月 ' + Number(parts[2]) + ' 日' : text(dayKey);
  }
  function formatTime(epoch) {
    if (!epoch) return '拍攝時間待確認';
    var date = new Date(epoch);
    if (!Number.isFinite(date.getTime())) return '拍攝時間待確認';
    return String(date.getHours()).padStart(2, '0') + ':' + String(date.getMinutes()).padStart(2, '0');
  }

  function managerFor(options) { return options.manager || (typeof window !== 'undefined' && window.TripPhotoManager) || null; }
  function adapterFor(options) {
    var adapter = options.adapter || {};
    var manager = managerFor(options);
    return {
      listPhotos: adapter.listPhotos || (manager && function (query) { return manager.list(query); }),
      subscribe: adapter.subscribe,
      updateClassification: adapter.updateClassification || (manager && manager.updateClassification && function (id, patch, actor) { return manager.updateClassification(id, patch, actor); }),
      retry: adapter.retry || (manager && manager.retryPending && function (query) { return manager.retryPending(query); }),
      remove: adapter.remove || (manager && manager.remove && function (id, actor) { return manager.remove(id, actor); }),
      can: adapter.can || (manager && manager.can)
    };
  }
  function can(action, photo) {
    if (!state) return false;
    if (photo && photo.readOnly && action !== 'view') return false;
    var adapter = state.adapter;
    if (typeof adapter.can === 'function') {
      try { return Boolean(adapter.can(action, photo, state.options.actor || {})); } catch (_error) { return false; }
    }
    return defaultCan(action, photo, state.options.actor || {});
  }
  function actionAvailable(action) {
    return state && typeof state.adapter[action === 'classify' ? 'updateClassification' : action] === 'function';
  }
  function announce(message, kind) {
    if (!state) return;
    state.message = { text: text(message), kind: kind || 'info' };
    var node = state.container.querySelector('.tpg-message');
    if (node) {
      node.textContent = state.message.text;
      node.dataset.kind = state.message.kind;
      node.hidden = !state.message.text;
    }
    if (typeof state.options.onMessage === 'function') state.options.onMessage(state.message);
  }
  function statusBadge(photo) {
    var status = text(photo.status || 'synced');
    return '<span class="tpg-status tpg-status--' + escapeHtml(status) + '">' + escapeHtml(STATUS_LABELS[status] || status) + '</span>';
  }
  function photoCard(photo) {
    var owner = ownerOf(photo);
    var preview = urlOf(photo);
    var selectable = can('classify', photo) && actionAvailable('classify');
    var selected = state.selected.has(photo.id);
    var retry = photo.status === 'failed' && can('retry', photo) && actionAvailable('retry');
    var remove = can('delete', photo) && actionAvailable('remove');
    return '<article class="tpg-photo' + (selected ? ' is-selected' : '') + '" data-photo-id="' + escapeHtml(photo.id) + '">' +
      '<div class="tpg-photo__media">' +
        (preview ? '<img src="' + escapeHtml(preview) + '" alt="' + escapeHtml(photo.stopName || '旅程照片') + '" loading="lazy">' : '<div class="tpg-photo__placeholder" aria-label="照片預覽尚未可用">📷</div>') +
        (selectable ? '<label class="tpg-select"><input type="checkbox" data-action="select" ' + (selected ? 'checked' : '') + '><span>選取</span></label>' : '') +
      '</div>' +
      '<div class="tpg-photo__body">' +
        '<div class="tpg-photo__meta"><span class="tpg-author" title="上傳者">' + escapeHtml(ownerLabel(photo)) + '</span>' + statusBadge(photo) + '</div>' +
        '<span class="tpg-time nowrap">' + escapeHtml(formatTime(photo.capturedAt)) + '</span>' +
        ((retry || remove) ? '<div class="tpg-photo__actions">' +
          (retry ? '<button type="button" data-action="retry">重新同步</button>' : '') +
          (remove ? '<button type="button" data-action="remove">移除</button>' : '') +
        '</div>' : '') +
      '</div>' +
    '</article>';
  }
  function groupMarkup(group) {
    return '<section class="tpg-group" data-group-key="' + escapeHtml(group.key) + '">' +
      '<header class="tpg-group__header"><div><span class="tpg-day nowrap">' + escapeHtml(formatDay(group.dayKey)) + '</span>' +
      '<h3>' + escapeHtml(group.stopName) + '</h3></div><span class="tpg-count nowrap">' + group.photos.length + ' 張</span></header>' +
      '<div class="tpg-grid">' + group.photos.map(photoCard).join('') + '</div></section>';
  }
  function stopOptions() {
    var lookup = stopLookup(state.options.stops || []);
    return Object.keys(lookup).map(function (id) {
      return '<option value="' + escapeHtml(id) + '">' + escapeHtml(lookup[id]) + '</option>';
    }).join('');
  }
  function render() {
    if (!state || !state.container) return;
    var actor = state.options.actor || {};
    var filtered = filterPhotos(state.photos, state.filter, actor.uid);
    var groups = groupPhotos(filtered, state.options.stops || []);
    state.ownerLabels = buildOwnerLabels(state.photos);
    var members = memberOptions(state.photos);
    var selectedCount = state.selected.size;
    var title = text(state.options.title || '共同行程相簿');
    state.container.innerHTML = '<section class="tpg" aria-label="' + escapeHtml(title) + '">' +
      '<header class="tpg-header"><div class="tpg-heading"><h2>' + escapeHtml(title) + '</h2><p>依日期與景點整理所有旅伴的照片。</p></div>' +
      '<button type="button" class="tpg-refresh" data-action="refresh">重新整理</button></header>' +
      '<div class="tpg-filters" role="group" aria-label="照片篩選">' +
        '<button type="button" data-filter="all" aria-pressed="' + (state.filter === 'all') + '">所有成員</button>' +
        '<button type="button" data-filter="mine" aria-pressed="' + (state.filter === 'mine') + '">我的照片</button>' +
        '<button type="button" data-filter="pending" aria-pressed="' + (state.filter === 'pending') + '">待整理</button>' +
        '<label class="tpg-member-select"><span>指定成員</span><select data-action="member-filter"><option value="">請選擇</option>' +
          members.map(function (member) { return '<option value="' + escapeHtml(member.uid) + '" ' + (state.filter === 'member:' + member.uid ? 'selected' : '') + '>' + escapeHtml(member.name) + '</option>'; }).join('') +
        '</select></label>' +
      '</div>' +
      '<div class="tpg-message" role="status" aria-live="polite" data-kind="' + escapeHtml(state.message.kind || 'info') + '" ' + (!state.message.text ? 'hidden' : '') + '>' + escapeHtml(state.message.text) + '</div>' +
      (selectedCount ? '<div class="tpg-batch"><strong class="nowrap">已選 ' + selectedCount + ' 張</strong><label><span>移到景點</span><select data-action="batch-stop"><option value="">請選擇景點</option>' + stopOptions() + '</select></label><button type="button" data-action="batch-classify">套用分類</button><button type="button" data-action="clear-selection">取消選取</button></div>' : '') +
      '<div class="tpg-content" aria-busy="' + state.loading + '">' +
        (state.loading ? '<div class="tpg-empty">正在載入共同行程照片…</div>' : groups.length ? groups.map(groupMarkup).join('') : '<div class="tpg-empty">目前沒有符合條件的照片。</div>') +
      '</div></section>';
  }
  function findPhoto(id) { return state && state.photos.find(function (photo) { return photo.id === id; }); }
  async function classifySelected() {
    if (!state || !state.selected.size) return;
    var select = state.container.querySelector('[data-action="batch-stop"]');
    var stopId = select && select.value;
    if (!stopId) { announce('請先選擇要移入的景點。', 'warning'); return; }
    var stopName = stopLookup(state.options.stops || [])[stopId] || '';
    var ids = Array.from(state.selected);
    var failed = [];
    for (var i = 0; i < ids.length; i++) {
      var photo = findPhoto(ids[i]);
      if (!photo || !can('classify', photo)) continue;
      try {
        await state.adapter.updateClassification(photo.id, { stopId: stopId, stopName: stopName, dayKey: photo.dayKey }, state.options.actor || {});
      } catch (error) { failed.push({ id: photo.id, error: error }); }
    }
    state.selected.clear();
    await refresh();
    announce(failed.length ? '部分照片無法更新，請稍後再試。' : '照片分類已更新。', failed.length ? 'error' : 'success');
  }
  async function onClick(event) {
    if (!state) return;
    var filterButton = event.target.closest('[data-filter]');
    if (filterButton) {
      state.filter = filterButton.dataset.filter;
      state.selected.clear();
      render();
      return;
    }
    var target = event.target.closest('[data-action]');
    if (!target) return;
    var action = target.dataset.action;
    var card = target.closest('[data-photo-id]');
    var photo = card && findPhoto(card.dataset.photoId);
    if (action === 'refresh') await refresh();
    else if (action === 'clear-selection') { state.selected.clear(); render(); }
    else if (action === 'batch-classify') await classifySelected();
    else if (action === 'retry' && photo) {
      await state.adapter.retry({ tripId: state.options.tripId, force: true, photoId: photo.id });
      await refresh();
    } else if (action === 'remove' && photo) {
      var allow = typeof state.options.confirmRemove === 'function'
        ? await state.options.confirmRemove(photo)
        : (typeof window === 'undefined' || window.confirm('確定要移除這張照片嗎？'));
      if (allow) { await state.adapter.remove(photo.id, state.options.actor || {}); await refresh(); }
    }
  }
  function onChange(event) {
    if (!state) return;
    var action = event.target && event.target.dataset.action;
    if (action === 'member-filter') {
      state.filter = event.target.value ? 'member:' + event.target.value : 'all';
      state.selected.clear();
      render();
    } else if (action === 'select') {
      var card = event.target.closest('[data-photo-id]');
      if (!card) return;
      if (event.target.checked) state.selected.add(card.dataset.photoId);
      else state.selected.delete(card.dataset.photoId);
      render();
    }
  }
  async function loadPhotos() {
    if (!state || typeof state.adapter.listPhotos !== 'function') return [];
    var payload = await state.adapter.listPhotos({ tripId: state.options.tripId });
    return flattenSources(payload, { tripId: state.options.tripId, stops: state.options.stops });
  }
  async function refresh() {
    if (!state) return [];
    var current = state;
    current.loading = true;
    render();
    try {
      var photos = await loadPhotos();
      if (state !== current) return [];
      current.photos = photos;
      current.selected.forEach(function (id) { if (!findPhoto(id)) current.selected.delete(id); });
      current.loading = false;
      render();
      return photos.slice();
    } catch (error) {
      if (state === current) {
        current.loading = false;
        render();
        announce('照片載入失敗，請檢查網路後重新整理。', 'error');
      }
      if (typeof current.options.onError === 'function') current.options.onError(error);
      return [];
    }
  }
  function subscribeUpdates() {
    if (!state) return;
    if (typeof state.adapter.subscribe === 'function') {
      var unsubscribe = state.adapter.subscribe(state.options.tripId, function (payload) {
        if (!state) return;
        if (payload) {
          state.photos = flattenSources(payload, { tripId: state.options.tripId, stops: state.options.stops });
          render();
        } else refresh();
      }, function (error) {
        announce('共同行程同步暫時中斷，將保留目前畫面。', 'warning');
        if (state && typeof state.options.onError === 'function') state.options.onError(error);
      });
      if (typeof unsubscribe === 'function') state.cleanups.push(unsubscribe);
    }
    var manager = managerFor(state.options);
    if (manager && typeof manager.subscribe === 'function') {
      state.cleanups.push(manager.subscribe(function (event) {
        if (!state || !event) return;
        var tripId = event.detail && (event.detail.tripId || event.detail.photo && event.detail.photo.tripId);
        if (!tripId || text(tripId) === text(state.options.tripId)) refresh();
      }));
    }
  }
  async function mount(container, options) {
    if (typeof document === 'undefined') throw new Error('TripPhotoGallery.mount 只能在瀏覽器中使用。');
    var element = typeof container === 'string' ? document.querySelector(container) : container;
    if (!element) throw new Error('找不到相簿掛載容器。');
    destroy();
    options = options || {};
    state = {
      container: element,
      options: options,
      adapter: adapterFor(options),
      photos: [],
      filter: text(options.initialFilter || 'all'),
      selected: new Set(),
      loading: true,
      message: { text: '', kind: 'info' },
      cleanups: []
    };
    if (typeof state.adapter.listPhotos !== 'function') throw new Error('請提供 adapter.listPhotos，或先載入 TripPhotoManager。');
    var click = function (event) { onClick(event).catch(function (error) { announce(error.message || '操作失敗。', 'error'); }); };
    var change = function (event) { onChange(event); };
    element.addEventListener('click', click);
    element.addEventListener('change', change);
    state.cleanups.push(function () { element.removeEventListener('click', click); element.removeEventListener('change', change); });
    render();
    subscribeUpdates();
    await refresh();
    return api;
  }
  function destroy() {
    if (!state) return;
    state.cleanups.splice(0).forEach(function (cleanup) { try { cleanup(); } catch (_error) {} });
    if (state.options.clearOnDestroy !== false && state.container) state.container.innerHTML = '';
    state = null;
  }

  var api = {
    VERSION: '1.0.0',
    STATUS_LABELS: STATUS_LABELS,
    mount: mount,
    refresh: refresh,
    destroy: destroy,
    utils: Object.freeze({
      normalizePhoto: normalizePhoto,
      flattenSources: flattenSources,
      stablePhotoSort: stablePhotoSort,
      filterPhotos: filterPhotos,
      groupPhotos: groupPhotos,
      memberOptions: memberOptions,
      defaultCan: defaultCan,
      isPending: isPending
    })
  };
  return api;
});
