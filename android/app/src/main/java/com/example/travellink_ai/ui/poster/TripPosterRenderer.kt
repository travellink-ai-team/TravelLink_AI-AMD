package com.example.travellink_ai.ui.poster

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect

/**
 * IG 個人檔案九宮格的渲染基礎設施。
 *
 * 設計依據與實測數據見 `docs/規格_Android渲染方案.md`。
 * 主視覺 3240×4320 若一次建立 Bitmap 需約 53 MB，低階機必然 OOM，
 * 因此一律逐格渲染，一次只做一張 1080×1440。
 */

const val MASTER_W = 3240f
const val MASTER_H = 4320f
const val TILE_W = 1080
const val TILE_H = 1440
const val GRID = 3

/**
 * 主視覺繪圖器。
 *
 * ★ 核心約束：一律以 [masterW] × [masterH] 的邏輯座標繪製，
 *   實作**不可以**知道自己被畫到多大的畫布上。
 *   由呼叫端用 canvas 的 scale / translate 決定輸出哪一塊、多大。
 *   這是能做逐格渲染的前提，事後補改代價很高。
 */
interface TripPosterRenderer {
    fun draw(canvas: Canvas, masterW: Float, masterH: Float)

    /**
     * 預熱：字型載入、Skia 初始化與 class init 會讓「第一次繪製」特別慢
     * （實測 1927ms，其後僅 9～23ms）。在使用者還在編輯畫面時先空跑一次，
     * 把這段成本吸收掉，按下匯出後的可見耗時才會落在 1 秒內。
     */
    fun warmUp() {
        // ⚠️ 不能用 1×1 畫布：文字會被 clip 掉，字型根本不會載入，
        //    暖機只花 24ms 卻沒暖到真正貴的東西（實測）。
        //    用一張小但完整比例的畫布，強迫走完排版與字形光柵化。
        val w = 108
        val h = (w * MASTER_H / MASTER_W).toInt()
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        try {
            val c = Canvas(bmp)
            c.scale(w / MASTER_W, h / MASTER_H)
            draw(c, MASTER_W, MASTER_H)
        } finally {
            bmp.recycle()
        }
    }
}

/**
 * 逐格渲染器。
 *
 * ★ 九格**共用同一組 Bitmap**，格間只 eraseColor 重畫，全程零配置。
 *   這是硬性約束不是最佳化：Spike 第一版每格重新配置，峰值飆到 52.7 MB
 *   ≈ 整張主視覺，逐格渲染完全失去意義。修正後峰值 13.5 MB。
 *
 * overscan：在格子四周多畫一圈再取中間，讓格外的內容也參與抗鋸齒與文字 hinting。
 * 實測把切線區差異密度比從 2.79× 壓到 1.71×、最大通道差 40 → 27。
 */
class TilePool(
    private val tileW: Int = TILE_W,
    private val tileH: Int = TILE_H,
    private val overscan: Int = DEFAULT_OVERSCAN
) {
    companion object {
        /** overscan 像素數（輸出座標）。0 代表直接畫進 output，省下 scratch。 */
        const val DEFAULT_OVERSCAN = 32
    }

    private val scratch: Bitmap? =
        if (overscan > 0)
            Bitmap.createBitmap(tileW + overscan * 2, tileH + overscan * 2, Bitmap.Config.ARGB_8888)
        else null

    private val output: Bitmap =
        Bitmap.createBitmap(tileW, tileH, Bitmap.Config.ARGB_8888)

    private val scratchCanvas = scratch?.let { Canvas(it) }
    private val outputCanvas = Canvas(output)
    private val srcRect = Rect(overscan, overscan, overscan + tileW, overscan + tileH)
    private val dstRect = Rect(0, 0, tileW, tileH)

    /** 常駐位元組數，供量測報告用。 */
    val residentBytes: Int
        get() = (scratch?.allocationByteCount ?: 0) + output.allocationByteCount

    /**
     * 渲染第 [row] 列第 [col] 欄的切片。
     *
     * ⚠️ 回傳的永遠是**同一個** Bitmap 實例，呼叫端必須在下一次 render 前用完
     * （例如壓成 JPEG），不可長期持有。
     */
    fun render(renderer: TripPosterRenderer, row: Int, col: Int): Bitmap {
        val scale = tileW * GRID / MASTER_W

        val canvas = scratchCanvas ?: outputCanvas
        val bmp = scratch ?: output
        bmp.eraseColor(Color.TRANSPARENT)

        val save = canvas.save()
        // 變換順序：master 座標 --scale--> 輸出像素 --平移到本格--> 再讓出 overscan 邊
        canvas.translate(overscan.toFloat(), overscan.toFloat())
        canvas.translate(-col * tileW.toFloat(), -row * tileH.toFloat())
        canvas.scale(scale, scale)
        renderer.draw(canvas, MASTER_W, MASTER_H)
        canvas.restoreToCount(save)

        if (scratch != null) {
            // 取中間那塊到 output，不用 createBitmap（那會每格配置一次）
            outputCanvas.drawBitmap(scratch, srcRect, dstRect, null)
        }
        return output
    }

    fun release() {
        scratch?.recycle()
        output.recycle()
    }
}

/**
 * 九宮格切線的安全區計算。
 *
 * 計畫 §5.2 硬性要求：「重要臉孔、景點主體與**文字**不可跨越九宮格切線」。
 * 這是規則不是美感 —— 使用者的九張是分開發布的，被切開的文字在單張裡讀不出意思。
 *
 * 座標一律是主視覺邏輯座標（[MASTER_W] × [MASTER_H]）。
 */
object CutLines {

    /** 垂直切線的 x 位置。 */
    val verticalX: List<Float> = (1 until GRID).map { MASTER_W * it / GRID }

    /** 水平切線的 y 位置。 */
    val horizontalY: List<Float> = (1 until GRID).map { MASTER_H * it / GRID }

    /** 一格的寬與高。 */
    const val CELL_W = MASTER_W / GRID
    const val CELL_H = MASTER_H / GRID

    /**
     * 第 [row] 列第 [col] 欄格子的可用文字區域（已內縮 [inset]）。
     * 在這個範圍內畫的東西保證不被任何切線切到。
     */
    fun safeTextBox(row: Int, col: Int, inset: Float = MASTER_W * 0.035f): FloatArray =
        floatArrayOf(
            col * CELL_W + inset,
            row * CELL_H + inset,
            (col + 1) * CELL_W - inset,
            (row + 1) * CELL_H - inset
        )

    /** 一段從 [left] 到 [right] 的水平範圍是否會被垂直切線切到。 */
    fun crossesVertical(left: Float, right: Float): Boolean =
        verticalX.any { it > left && it < right }

    /** 一段從 [top] 到 [bottom] 的垂直範圍是否會被水平切線切到。 */
    fun crossesHorizontal(top: Float, bottom: Float): Boolean =
        horizontalY.any { it > top && it < bottom }
}

object GridTiler {

    /** 一次畫完整張主視覺（僅供小尺度比對用；全尺寸會 OOM）。 */
    fun renderWhole(renderer: TripPosterRenderer, outW: Int, outH: Int): Bitmap {
        val bmp = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.scale(outW / MASTER_W, outH / MASTER_H)
        renderer.draw(canvas, MASTER_W, MASTER_H)
        return bmp
    }

    /** 發布次序 → 最終格線位置。次序 1 是右下角（IG 最新貼文排左上，故反向發布）。 */
    fun publishOrderToCell(order: Int): Pair<Int, Int> {
        require(order in 1..9)
        val idx = 9 - order          // 0 = r1c1(左上) … 8 = r3c3(右下)
        return idx / GRID to idx % GRID
    }

    /** 相簿檔名（使用者看得懂的形式）。`r{row}c{col}` 只存在 manifest。 */
    fun galleryFileName(region: String, order: Int): String =
        "TravelLink_${region.ifBlank { "旅程" }}_${order}of9"
}
