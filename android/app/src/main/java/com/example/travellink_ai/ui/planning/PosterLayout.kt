package com.example.travellink_ai.ui.planning

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import com.example.travellink_ai.data.model.Stop

/**
 * 行程插圖（對齊網頁 2026-10-19 的做法）：程式先決定每一站在圖上的位置 → 畫一張「構圖草稿」
 * （紅色編號圓點＋灰色虛線）連同指令給 AI → AI 只畫**沒有文字**的底圖 → 程式疊上編號、站名與標題。
 *
 * 以前請 AI 連站名一起畫、再請 detect_stop_boxes 找框：站名會寫錯或漏畫、同名站搞混（2026-10-18 實測
 * 回程車站被畫成第二個馬蘭車站）。現在站名一定正確、每一站一定點得到，站框是現成的（model＝web-layout）。
 *
 * 和網頁不同：網頁 16:9 排成一圈；App 9:16 直式，1 號在標題下方、依序由上往下左右交錯。
 * 同名的站（起點＝終點、隔天的住宿）只放一個標記，編號寫成「1／9」。
 */
internal object PosterLayout {
    /** 預設版面座標系（9:16，Gemini）；疊字時依底圖實際大小縮放。OpenAI 只有 2:3，用 [OPENAI_W]×[OPENAI_H] */
    const val W = 900f
    const val H = 1600f
    const val OPENAI_W = 1024f
    const val OPENAI_H = 1536f
    private const val TOP = 0.20f      // 第一站的高度（上面留給標題）
    private const val BOTTOM = 0.90f   // 最後一站的高度（下面留給站名標籤）
    private const val SKETCH_DOT = 26f
    private const val DOT = 32f        // 疊上去的圓點要比草稿的大：AI 常把草稿紅點照抄進圖裡，要蓋得住

    class Place(
        val name: String,
        val emoji: String,
        /** 景點類型（餐廳、自然景觀…）：沒有畫面描述時用來寫通用描述 */
        val stopType: String,
        val numbers: List<Int>,
        val stopIds: List<String>,
        val x: Float,
        val y: Float
    ) {
        val label: String get() = numbers.joinToString("／")
    }

    /** [order]＝每一站對應的 places 索引（依行程順序）；[w]×[h]＝版面座標系，草稿也畫這個大小 */
    class Layout(val places: List<Place>, val order: List<Int>, val w: Float = W, val h: Float = H) {
        /** 給 AI 的比例描述 */
        val ratioText: String get() = if (w * 3 == h * 2) "2:3 直式" else "9:16 直式"
    }

    fun nameKey(name: String) = name.replace("臺", "台").replace(Regex("\\s+"), "").lowercase()

    fun build(stops: List<Stop>, w: Float = W, h: Float = H): Layout {
        val keys = mutableListOf<String>()
        val first = mutableListOf<Stop>()
        val numbers = mutableListOf<MutableList<Int>>()
        val ids = mutableListOf<MutableList<String>>()
        val order = stops.mapIndexed { i, s ->
            val k = nameKey(s.name)
            var idx = keys.indexOf(k)
            if (idx < 0) { keys += k; first += s; numbers += mutableListOf<Int>(); ids += mutableListOf<String>(); idx = keys.lastIndex }
            numbers[idx] += i + 1
            ids[idx] += s.stopId
            idx
        }
        val n = keys.size
        val places = (0 until n).map { k ->
            val y = h * if (n == 1) 0.5f else TOP + (BOTTOM - TOP) * k / (n - 1)
            val x = w * when { n == 1 -> 0.5f; k % 2 == 0 -> 0.28f; else -> 0.72f }
            Place(first[k].name.trim().ifBlank { "景點" }, first[k].emoji, first[k].stopType, numbers[k], ids[k], x, y)
        }
        return Layout(places, order, w, h)
    }

    /**
     * 草稿的路線：只畫「第一次到某地」的段落。回到已經到過的地點（回程車站）那段不畫，
     * 否則直式版面會有一條從最下面拉回最上面、橫穿整張圖的線。
     */
    fun sketchSegments(layout: Layout): List<Pair<Int, Int>> {
        val seen = mutableSetOf(layout.order.firstOrNull() ?: return emptyList())
        return layout.order.zipWithNext().filter { (a, b) -> a != b && seen.add(b) }
    }

    fun sketch(layout: Layout): Bitmap {
        val bmp = Bitmap.createBitmap(layout.w.toInt(), layout.h.toInt(), Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(0xFFF4F1EA.toInt())
        val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF8A857C.toInt(); strokeWidth = 6f; style = Paint.Style.STROKE
            pathEffect = DashPathEffect(floatArrayOf(18f, 14f), 0f)
        }
        sketchSegments(layout).forEach { (a, b) ->
            val pa = layout.places[a]; val pb = layout.places[b]
            c.drawLine(pa.x, pa.y, pb.x, pb.y, line)
        }
        val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFE0452B.toInt() }
        val num = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFFFFFFF.toInt(); textSize = 30f; textAlign = Paint.Align.CENTER; typeface = Typeface.DEFAULT_BOLD
        }
        layout.places.forEach { p ->
            c.drawCircle(p.x, p.y, SKETCH_DOT, dot)
            c.drawText(p.numbers.first().toString(), p.x, p.y - (num.ascent() + num.descent()) / 2, num)
        }
        return bmp
    }

    /**
     * 請文字模型把每一站轉成「畫面描述」的指令（回 JSON：{"編號": "描述"}）。
     *
     * 畫底圖的指令裡只要出現站名，圖片模型就常把名字寫進圖裡（還有錯字）：2026-10-19「兩天測試」第 1 天
     * 寫滿了「鷺鷥湖」「活水湖」…。改成只給描述後實測整張圖沒有任何字。描述不准有專有名詞；
     * 不確定外觀就寫通用描述（實測台東車站被猜成「白色流線型的現代車站」，畫成高鐵）。
     */
    fun describePrompt(layout: Layout, area: String): String {
        val lines = layout.places.joinToString("\n") { p ->
            "${p.numbers.first()}. ${p.name}" + (p.stopType.takeIf { it.isNotBlank() }?.let { "（$it）" } ?: "")
        }
        return "以下是一趟「$area」旅遊行程的地點。請為每個地點寫一句 20 字以內的「畫面描述」，描述它的外觀、景觀或代表性食物，" +
            "讓插畫家不看名字也畫得出來。描述裡不能出現任何專有名詞（地名、店名、景點名）、文字、數字或招牌。" +
            "如果不確定這個地點實際長什麼樣子，就依類型寫通用的描述，不要猜測特定細節。" +
            "回傳 JSON 物件，鍵是編號字串、值是描述，例如 {\"1\": \"有屋簷與月台的鐵路車站\"}。\n" + lines
    }

    /** 解析畫面描述（模型回傳的 JSON）；壞掉的就略過，該站改用通用描述 */
    fun parseDescriptions(json: String?, layout: Layout): Map<Int, String> = try {
        val o = org.json.JSONObject(json ?: "")
        layout.places.mapNotNull { p ->
            val n = p.numbers.first()
            o.optString(n.toString()).trim().takeIf { it.isNotBlank() }?.let { n to it.take(40) }
        }.toMap()
    } catch (e: Exception) { emptyMap() }

    /** 沒有畫面描述時的通用描述（只用類型，不帶站名） */
    private fun genericDescription(p: Place) = when {
        p.stopType.contains("餐") || p.stopType.contains("咖啡") || p.stopType.contains("小吃") -> "一盤當地的特色美食"
        p.stopType.contains("車站") || p.name.endsWith("車站") -> "有屋簷與月台的鐵路車站"
        p.stopType.contains("博物館") || p.stopType.contains("文化") -> "一座有特色的文化展館建築"
        p.stopType.contains("公園") || p.stopType.contains("步道") -> "綠樹成蔭的公園步道"
        p.stopType.contains("自然") -> "當地的自然風景"
        p.stopType.contains("住宿") || p.stopType.contains("民宿") -> "溫馨的民宿小屋"
        else -> "一個有當地特色的景點"
    }

    /**
     * 給 AI 的指令：照草稿位置畫底圖，整張圖不能有任何文字（與網頁 buildPosterBasePrompt 同一套，比例改直式）。
     * 每一站只給 [descriptions]（畫面描述），**不給站名**：給了名字模型就會寫出來。
     */
    fun basePrompt(layout: Layout, area: String, character: String, descriptions: Map<Int, String> = emptyMap()): String {
        val who = character.trim().ifBlank { "可愛的小旅人" }
        val list = layout.places.joinToString("\n") { p ->
            val n = p.numbers.first()
            "圓點 $n：${p.emoji}${descriptions[n] ?: genericDescription(p)}"
        }
        return listOf(
            "請以附圖為「構圖草稿」，繪製一張旅遊地圖插畫（底圖）。",
            "附圖說明：紅色編號圓點是各站的位置，灰色虛線是行程路線。",
            "",
            "【位置】每一站的小插圖，中心必須正好畫在草稿上對應圓點的位置：不要往外移、不要移到畫面中央、也不要和其他站交換位置。路線用一條連續的手繪虛線，沿著草稿虛線依序連接各站，可以有自然的彎曲。畫面最上方約八分之一留給標題，只畫天空或背景、不要放站點插圖。",
            "【內容】背景是俯瞰的手繪示意地圖，依「$area」一帶的特色畫出地形（例如海岸線與海、山脈、綠地、街道或道路；地名只是讓你知道地形，不要寫出來）。每一站畫一個符合下列描述的小插圖。主角是可愛的卡通「$who」，在其中兩三站旁邊當嚮導或旅伴。日式雜誌插畫風格，可愛、清新，比例 ${layout.ratioText}。",
            "【絕對禁止】整張圖不能有任何文字：不要任何中文（繁體或簡體）、英文、數字、編號、標題或標籤；建築物的招牌、匾額、包裝畫成空白或圖案。也不要畫空白的標籤框、文字底板、圖例或紅色圓點。編號與站名之後會由程式另外疊上，圖上只要有插畫。",
            "",
            "各圓點要畫的內容：",
            list
        ).joinToString("\n")
    }

    /** 疊好字的插圖，以及每一站的點擊範圍（Firestore imageStopBoxes.boxes 格式，0～1000） */
    class Composed(val bitmap: Bitmap, val boxes: List<Map<String, Any>>)

    fun compose(base: Bitmap, layout: Layout, title: String, range: String): Composed {
        val out = base.copy(Bitmap.Config.ARGB_8888, true)
        val c = Canvas(out)
        val w = out.width.toFloat(); val h = out.height.toFloat()
        val sx = w / layout.w; val sy = h / layout.h; val s = minOf(sx, sy)
        val crowded = layout.places.size > 10
        val bold = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        val fill = Paint(Paint.ANTI_ALIAS_FLAG)
        val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; typeface = bold }
        fun Paint.centerY(y: Float) = y - (ascent() + descent()) / 2
        fun norm(v: Float, total: Float) = (v / total * 1000).toInt().coerceIn(0, 1000)
        val boxes = mutableListOf<Map<String, Any>>()

        layout.places.forEach { p ->
            val x = p.x * sx; val y = p.y * sy
            val wide = p.label.length > 2
            val r = DOT * s * (if (wide) 1.25f else 1f)
            fill.color = 0x40000000; c.drawCircle(x, y + 2 * s, r, fill)
            fill.color = 0xFFF07B3F.toInt(); c.drawCircle(x, y, r, fill)
            stroke.color = 0xFFFFFFFF.toInt(); stroke.strokeWidth = 4 * s; c.drawCircle(x, y, r, stroke)
            text.color = 0xFFFFFFFF.toInt(); text.textSize = (if (wide) 22f else 30f) * s
            c.drawText(p.label, x, text.centerY(y), text)

            // 站名標籤：圓點下方；站多時名字太長就截斷，免得和隔壁標籤疊在一起
            val maxChars = if (crowded) 9 else 14
            val label = if (p.name.length > maxChars) p.name.take(maxChars - 1) + "…" else p.name
            text.color = 0xFF22201D.toInt(); text.textSize = 28f * s
            val ph = 42f * s; val pw = text.measureText(label) + 28f * s
            val lx = (x - pw / 2).coerceIn(6 * s, w - pw - 6 * s)
            val ly = minOf(h - ph - 6 * s, y + r + 10 * s)
            val pill = RectF(lx, ly, lx + pw, ly + ph)
            fill.color = 0xEDFFFFFF.toInt(); c.drawRoundRect(pill, ph / 2, ph / 2, fill)
            stroke.color = 0xFF2E7D6D.toInt(); stroke.strokeWidth = 3 * s; c.drawRoundRect(pill, ph / 2, ph / 2, stroke)
            c.drawText(label, pill.centerX(), text.centerY(pill.centerY()), text)

            // 點擊範圍＝圓點＋標籤，外加一點邊距方便手指點；point＝圓點中心（進行中的小點沿這些點走）
            val pad = 12 * s
            val box = listOf(
                norm(y - r - pad, h), norm(minOf(x - r, lx) - pad, w),
                norm(pill.bottom + pad, h), norm(maxOf(x + r, pill.right) + pad, w)
            )
            val point = listOf(norm(y, h), norm(x, w))
            p.stopIds.forEach { id -> boxes += mapOf("stopId" to id, "name" to p.name, "box" to box, "point" to point) }
        }

        // 標題（左上角）：行程名稱與時間
        val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = bold; textSize = 40f * s; color = 0xFF22201D.toInt() }
        val rangePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = bold; textSize = 24f * s; color = 0xFF2E7D6D.toInt() }
        val tw = maxOf(titlePaint.measureText(title), if (range.isBlank()) 0f else rangePaint.measureText(range))
        val bh = (if (range.isBlank()) 66f else 102f) * s
        val card = RectF(18 * s, 18 * s, 18 * s + tw + 44 * s, 18 * s + bh)
        fill.color = 0xE6FFFFFF.toInt(); c.drawRoundRect(card, 20 * s, 20 * s, fill)
        stroke.color = 0xFF2E7D6D.toInt(); stroke.strokeWidth = 3 * s; c.drawRoundRect(card, 20 * s, 20 * s, stroke)
        c.drawText(title, card.left + 22 * s, titlePaint.let { it.centerY(card.top + (if (range.isBlank()) bh / 2 else 38f * s)) }, titlePaint)
        if (range.isNotBlank()) c.drawText(range, card.left + 22 * s, rangePaint.centerY(card.top + 78f * s), rangePaint)
        return Composed(out, boxes)
    }

    /** 多日插圖每一天標題下的時間：「2026/10/17 09:00–18:30」（日期＋當天第一站到最後一站離開） */
    fun dayRange(date: String?, stops: List<Stop>): String {
        val start = stops.firstNotNullOfOrNull { com.example.travellink_ai.ui.trip.parseClockToMinutes(it.time) }
        val end = stops.lastOrNull()?.let { s -> com.example.travellink_ai.ui.trip.parseClockToMinutes(s.time)?.plus(s.duration.toInt()) }
        val time = if (start != null && end != null)
            "${com.example.travellink_ai.ui.trip.fmtClock(start)}–${com.example.travellink_ai.ui.trip.fmtClock(end)}" else ""
        return listOfNotNull(date?.takeIf { it.isNotBlank() }, time.takeIf { it.isNotBlank() }).joinToString(" ")
    }

    /**
     * 標題下的行程時間：同一天的「2026/10/17 11:00 - 2026/10/17 17:00」縮成「2026/10/17 11:00–17:00」；
     * 跨日或其他格式（網頁的「N小時」）原樣保留。
     */
    fun dateLabel(days: String): String {
        val m = Regex("""^(\d{4}/\d{2}/\d{2}) (\d{1,2}:\d{2}) - (\d{4}/\d{2}/\d{2}) (\d{1,2}:\d{2})$""").find(days.trim())
            ?: return days
        val (d1, t1, d2, t2) = m.destructured
        return if (d1 == d2) "$d1 $t1–$t2" else days
    }
}
