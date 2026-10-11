package com.example.travellink_ai.ui.poster

import android.content.Context
import android.graphics.Bitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.travellink_ai.data.local.LocalItinerary
import android.net.Uri
import com.example.travellink_ai.util.MediaStoreSaver
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject
import kotlin.math.abs

/** 製作流程步驟。 */
enum class GridStep { EDIT, PREVIEW }

/** 目前選中的編輯對象。 */
sealed interface GridSelection {
    /** 底圖：拖曳＝平移照片露出的位置（調焦點）、雙指＝縮放照片。 */
    data object Background : GridSelection
    /** 小卡：拖曳＝移動、雙指＝縮放。 */
    data class Card(val index: Int) : GridSelection
    /** 標題：拖曳＝移動、雙指＝縮放。 */
    data object Title : GridSelection
}

/**
 * 九宮格製作的狀態擁有者。
 *
 * ★ [UiState.layout] 是渲染的 source of truth。選片/換範本會重建它；
 *   編輯（移動/縮放/焦點）直接改它。預覽與匯出都讀它。
 */
@HiltViewModel
class MemoryGridViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: MemoryGridRepository
) : ViewModel() {

    data class UiState(
        val loading: Boolean = true,
        val tripTitle: String = "",
        val subtitle: String = "",
        val photos: List<PhotoRef> = emptyList(),
        val selected: List<PhotoRef> = emptyList(),
        val templateKey: String = "collage5",
        /** 目前版面（含編輯後的位置/大小）。渲染與匯出的依據。 */
        val layout: PhotoLayout? = null,
        /** 目前選中的編輯對象，null＝未選。 */
        val selection: GridSelection? = null,
        /** 照片長寬比（url → 高/寬），供框依實際照片比例畫。 */
        val photoRatios: Map<String, Float> = emptyMap(),
        /** 是否手動調過位置/大小；換範本會清掉這些調整，故需提醒。 */
        val edited: Boolean = false,
        val preview: Bitmap? = null,
        val rendering: Boolean = false,
        /** 編輯頁是否顯示九宮格切線。 */
        val showGrid: Boolean = true,
        /** 目前步驟。 */
        val step: GridStep = GridStep.EDIT,
        /** 預覽頁用的較大整張圖（供 3×3 顯示與單張切片）。 */
        val previewLarge: Bitmap? = null,
        /** 單張檢視目前看第幾張（發布次序 1~9）。 */
        val previewTileOrder: Int = 1,
        val exporting: Boolean = false,
        val exportedCount: Int = 0,
        val exportDone: Boolean = false,
        /** 已存到相簿的 9 張圖 Uri（依發布次序），供「分享到社群」使用。 */
        val exportedUris: List<Uri> = emptyList(),
        val message: String? = null
    ) {
        val capacity: Int get() = 1 + TestLayouts.byKey(templateKey).cards.size
    }

    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    private var region: String = "旅程"
    private val cache = PhotoBitmapCache(context)
    // 合流渲染：同時只跑一個 renderWhole，跑完取最新請求再跑一次。
    // 快速拖曳時只更新請求、不排隊，自適應裝置渲染速度，不累積卡頓。
    private var renderReq: Pair<PhotoLayout, Boolean>? = null
    private var rendering = false

    // 已載入照片快取：拖曳時 selected 不變，不重新 loadAll
    private var loadedPhotos: Map<String, Bitmap> = emptyMap()
    private var loadedKey: List<String> = emptyList()

    fun load(item: LocalItinerary) {
        val cloud = item.firestoreDocId?.takeIf { it.isNotBlank() }.orEmpty()
        val roomId = cloud.ifBlank { "local_${item.id}" }
        region = item.region.ifBlank { "旅程" }
        val title = item.title.ifBlank { item.aiTitle }.ifBlank { "我的旅程" }

        _ui.value = UiState(loading = true, tripTitle = title, subtitle = "$region・${item.days}")

        viewModelScope.launch {
            val photos = repository.loadPhotos(cloud, roomId)
            val selected = photos.take(_ui.value.capacity)
            _ui.value = _ui.value.copy(
                loading = false, photos = photos, selected = selected,
                layout = buildLayout(_ui.value.templateKey, selected),
                message = if (photos.isEmpty()) "這趟還沒有照片，先去旅程回憶加幾張" else null
            )
            repaint()
        }
    }

    fun selectTemplate(key: String) {
        if (key == _ui.value.templateKey) return
        val cap = 1 + TestLayouts.byKey(key).cards.size
        val cur = _ui.value
        val selected = when {
            cur.selected.size > cap -> cur.selected.take(cap)
            cur.selected.size < cap -> (cur.selected + cur.photos.filter { it !in cur.selected }).take(cap)
            else -> cur.selected
        }
        // 換範本會重置編輯過的位置（回到範本預設）
        _ui.value = cur.copy(
            templateKey = key, selected = selected,
            layout = buildLayout(key, selected), selection = null, edited = false
        )
        repaint()
    }

    fun togglePhoto(ref: PhotoRef) {
        val cur = _ui.value
        val selected = if (cur.selected.any { it.photoId == ref.photoId }) {
            cur.selected.filterNot { it.photoId == ref.photoId }
        } else {
            if (cur.selected.size >= cur.capacity) {
                _ui.value = cur.copy(message = "這個版面最多 ${cur.capacity} 張，先移除一張再換")
                return
            }
            cur.selected + ref
        }
        _ui.value = cur.copy(
            selected = selected, layout = buildLayout(cur.templateKey, selected),
            selection = null, message = null, edited = false
        )
        repaint()
    }

    /** 把某張設為底圖（移到 selected 第一位）。 */
    fun setAsBackground(ref: PhotoRef) {
        val cur = _ui.value
        if (cur.selected.firstOrNull()?.photoId == ref.photoId) return
        val reordered = listOf(ref) + cur.selected.filterNot { it.photoId == ref.photoId }
        val selected = if (cur.selected.none { it.photoId == ref.photoId })
            reordered.take(cur.capacity) else reordered
        _ui.value = cur.copy(
            selected = selected, layout = buildLayout(cur.templateKey, selected),
            selection = null, message = "已設為底圖", edited = false
        )
        repaint()
    }

    // ── 編輯（版面座標 0~1）──────────────────────────────────────

    /**
     * 命中測試：點 ([px],[py])。優先小卡（上層）→ 標題 → 都沒中則選底圖。
     * 底圖總是可選（點空白處），因此拖動空白＝調底圖露出的位置。
     */
    fun hitTest(px: Float, py: Float) {
        val layout = _ui.value.layout ?: return
        val cardIdx = layout.cards.indices.reversed().firstOrNull { i ->
            val c = layout.cards[i]
            abs(px - c.cx) <= c.w / 2 && abs(py - c.cy) <= cardRelHeight(c) / 2
        }
        val title = layout.titleCard
        val sel: GridSelection = when {
            cardIdx != null -> GridSelection.Card(cardIdx)
            title != null && title.visible &&
                abs(px - title.cx) <= title.w / 2 && abs(py - title.cy) <= title.h / 2 ->
                GridSelection.Title
            else -> GridSelection.Background
        }
        if (sel != _ui.value.selection) _ui.value = _ui.value.copy(selection = sel)
    }

    /** 拖曳：小卡/標題＝移動位置；底圖＝平移照片露出的位置（反向，照片跟手）。 */
    fun moveSelected(dx: Float, dy: Float) {
        val cur = _ui.value
        val layout = cur.layout ?: return
        val newLayout = when (val s = cur.selection) {
            is GridSelection.Card -> layout.updateCard(s.index) {
                it.copy(cx = (it.cx + dx).coerceIn(0.05f, 0.95f), cy = (it.cy + dy).coerceIn(0.05f, 0.95f))
            }
            GridSelection.Title -> layout.titleCard?.let { t ->
                layout.copy(titleCard = t.copy(
                    cx = (t.cx + dx).coerceIn(0.1f, 0.9f), cy = (t.cy + dy).coerceIn(0.06f, 0.94f)))
            }
            GridSelection.Background -> layout.background?.let { bg ->
                // 拖右＝想看左邊，focus 反向移動
                layout.copy(background = bg.copy(
                    focusX = (bg.focusX - dx).coerceIn(0f, 1f), focusY = (bg.focusY - dy).coerceIn(0f, 1f)))
            }
            null -> null
        } ?: return
        _ui.value = cur.copy(layout = newLayout, edited = true)
        repaint(large = false)   // 拖曳中低清、節流重繪，照片跟著框一起動
    }

    /** 雙指縮放：小卡/標題＝改大小；底圖＝放大照片（zoom）。 */
    fun scaleSelected(factor: Float) {
        val cur = _ui.value
        val layout = cur.layout ?: return
        val newLayout = when (val s = cur.selection) {
            is GridSelection.Card -> layout.updateCard(s.index) {
                it.copy(w = (it.w * factor).coerceIn(PhotoSlot.MIN_W, PhotoSlot.MAX_W))
            }
            GridSelection.Title -> layout.titleCard?.let { t ->
                layout.copy(titleCard = t.copy(w = (t.w * factor).coerceIn(0.3f, 0.9f)))
            }
            GridSelection.Background -> layout.background?.let { bg ->
                layout.copy(background = bg.copy(zoom = (bg.zoom * factor).coerceIn(1f, 3f)))
            }
            null -> null
        } ?: return
        _ui.value = cur.copy(layout = newLayout, edited = true)
        repaint(large = false)
    }

    /** 一次手勢（拖曳/縮放）結束後，重繪高清照片畫到最新。 */
    fun commitEdit() = repaint(large = true)

    /** 重設版面：回到目前範本＋選片的預設位置（拖曳編輯前的樣子）。 */
    fun resetLayout() {
        val cur = _ui.value
        _ui.value = cur.copy(
            layout = buildLayout(cur.templateKey, cur.selected),
            selection = null, message = "版面已重設", edited = false
        )
        repaint()
    }

    private inline fun PhotoLayout.updateCard(i: Int, transform: (PhotoSlot) -> PhotoSlot): PhotoLayout {
        if (i !in cards.indices) return this
        val list = cards.toMutableList()
        list[i] = transform(list[i])
        return copy(cards = list)
    }

    private fun buildLayout(templateKey: String, selected: List<PhotoRef>): PhotoLayout =
        TestLayouts.byKey(templateKey).withPhotos(selected)

    /**
     * 重繪預覽。拖曳時高頻呼叫，用節流限制實際重繪頻率，並保證停手後畫到最新。
     * 選中框由 UI 直接讀 layout 即時跟手，不靠這裡，所以拖曳體感是「框即時、照片緊跟」。
     */
    /**
     * @param large true＝高清（540×720，靜止/放手）；false＝低清（270×360，拖曳中）。
     *   拖曳中登記最新請求即返回；由 [pumpRender] 一次只跑一個，跑完取最新，
     *   快速拖曳也不累積 —— 框即時、照片以裝置能力盡快跟上。
     */
    private fun repaint(large: Boolean = true) {
        val layout = _ui.value.layout
        if (layout == null || _ui.value.selected.isEmpty()) {
            _ui.value = _ui.value.copy(preview = null); return
        }
        renderReq = layout to large
        if (!rendering) pumpRender()
    }

    private fun pumpRender() {
        val (layout, large) = renderReq ?: return
        renderReq = null
        rendering = true
        val cur = _ui.value
        val div = if (large) 2 else 4
        viewModelScope.launch {
            val bmp = withContext(Dispatchers.Default) {
                val photos = ensurePhotos(cur.selected)
                val renderer = PhotoGridRenderer(layout, photos, cur.tripTitle, cur.subtitle)
                GridTiler.renderWhole(renderer, TILE_W / div, TILE_H / div)
            }
            _ui.value.preview?.recycle()
            _ui.value = _ui.value.copy(preview = bmp)
            rendering = false
            if (renderReq != null) pumpRender()   // 期間有新請求 → 直接畫最新
        }
    }

    /** 拿已載入的照片；selected 沒變就用快取，不重新解碼。順便算長寬比供框。 */
    private suspend fun ensurePhotos(selected: List<PhotoRef>): Map<String, Bitmap> {
        val key = selected.map { it.url }
        if (key == loadedKey && loadedPhotos.isNotEmpty()) return loadedPhotos
        loadedPhotos = cache.loadAll(key)
        loadedKey = key
        val ratios = loadedPhotos.mapValues { (_, b) ->
            if (b.width > 0) b.height.toFloat() / b.width else 1f
        }
        _ui.value = _ui.value.copy(photoRatios = ratios)
        return loadedPhotos
    }

    /** 小卡在版面座標的相對高度（畫布 3:4，故乘 0.75）。供框與命中測試。 */
    private fun cardRelHeight(c: PhotoSlot): Float {
        val ratio = c.aspect ?: c.photoRef?.url?.let { _ui.value.photoRatios[it] } ?: 1f
        return c.w * ratio * 0.75f
    }

    fun exportNine() {
        val cur = _ui.value
        val layout = cur.layout ?: return
        if (cur.selected.isEmpty() || cur.exporting) return
        viewModelScope.launch {
            _ui.value = cur.copy(exporting = true, exportedCount = 0, exportDone = false, exportedUris = emptyList())
            val uris = ArrayList<Uri>(9)
            var appOnly = 0
            val ok = withContext(Dispatchers.Default) {
                val photos = ensurePhotos(cur.selected)
                val renderer = PhotoGridRenderer(layout, photos, cur.tripTitle, cur.subtitle)
                renderer.warmUp()
                val pool = TilePool()
                var saved = 0
                try {
                    for (order in 1..9) {
                        val (row, col) = GridTiler.publishOrderToCell(order)
                        val tile = pool.render(renderer, row, col)
                        val name = GridTiler.galleryFileName(region, order)
                        when (val r = MediaStoreSaver.saveJpeg(context, tile, name, quality = 92)) {
                            is MediaStoreSaver.SaveResult.Gallery -> { uris.add(r.uri); saved++ }
                            is MediaStoreSaver.SaveResult.AppDirOnly -> { uris.add(Uri.fromFile(r.file)); saved++; appOnly++ }
                            is MediaStoreSaver.SaveResult.Failed -> {}
                        }
                        _ui.value = _ui.value.copy(exportedCount = order)
                    }
                } finally {
                    pool.release()
                }
                saved
            }
            _ui.value = _ui.value.copy(
                exporting = false, exportDone = true, exportedUris = uris,
                message = when {
                    ok < 9 -> "有 ${9 - ok} 張存檔失敗，可重試"
                    appOnly > 0 -> "沒有儲存權限，九張只暫存在 App 內，相簿裡看不到"
                    else -> "九張已存到相簿"
                }
            )
        }
    }

    fun toggleGrid() { _ui.value = _ui.value.copy(showGrid = !_ui.value.showGrid) }

    /** 進預覽頁：渲染一張較大的整張圖，供 3×3 與單張切片檢視。 */
    fun goToPreview() {
        val cur = _ui.value
        val layout = cur.layout ?: return
        if (cur.selected.isEmpty()) return
        _ui.value = cur.copy(step = GridStep.PREVIEW, previewTileOrder = 1, exportDone = false)
        viewModelScope.launch {
            val bmp = withContext(Dispatchers.Default) {
                val photos = ensurePhotos(cur.selected)
                val renderer = PhotoGridRenderer(layout, photos, cur.tripTitle, cur.subtitle)
                GridTiler.renderWhole(renderer, TILE_W, TILE_H)   // 大圖，crop 單張才清楚
            }
            _ui.value.previewLarge?.recycle()
            _ui.value = _ui.value.copy(previewLarge = bmp)
        }
    }

    fun backToEdit() {
        _ui.value.previewLarge?.recycle()
        _ui.value = _ui.value.copy(
            step = GridStep.EDIT, previewLarge = null, exportDone = false
        )
    }

    fun setPreviewTile(order: Int) {
        _ui.value = _ui.value.copy(previewTileOrder = order.coerceIn(1, 9))
    }

    fun clearMessage() { _ui.value = _ui.value.copy(message = null) }

    override fun onCleared() {
        cache.release()
        _ui.value.preview?.recycle()
        _ui.value.previewLarge?.recycle()
        super.onCleared()
    }
}
