package com.example.travellink_ai.ui.planning

import java.time.DayOfWeek
import java.time.LocalDate

/**
 * 營業時間字串的「依日期取用」。
 *
 * 景點的營業時間有兩種形態：
 *  - 全週：`星期一: 08:30 – 12:00, 13:30 – 17:00\n星期二: 休息\n…`（本地資料庫、Google 整週資料）
 *  - 單日：`08:30 - 12:00, 13:30 - 17:00`、`24 小時營業`、`休息`
 *
 * 過去的問題是「查的不是行程那一天」：多日行程一律用第 1 天的星期、加站補位的景點沒查、
 * 全週字串解析時退回用「今天」（生成當天）的星期。結果是週一公休的夜市被排在週一，
 * 週二公休的博物館被排在週二。這裡集中處理，讓每個站都能取到它實際所在那一天的時段。
 */
object BusinessHours {

    /** 公休的標記。單日字串只有這類字、沒有任何時間，才算公休 */
    private val CLOSED_MARKERS = listOf("休息", "公休", "休館")

    private val TIME = Regex("""\d{1,2}:\d{2}""")
    private val ALL_DAY = Regex("""24\s*小時|全天|全日""")

    private fun parseDate(date: String): LocalDate? {
        val p = date.trim().substringBefore(" ").replace('-', '/').split("/")
        if (p.size != 3) return null
        val y = p[0].toIntOrNull() ?: return null
        val m = p[1].toIntOrNull() ?: return null
        val d = p[2].toIntOrNull() ?: return null
        return try { LocalDate.of(y, m, d) } catch (e: Exception) { null }
    }

    private fun weekdayLabel(date: LocalDate): String = when (date.dayOfWeek) {
        DayOfWeek.MONDAY -> "星期一"
        DayOfWeek.TUESDAY -> "星期二"
        DayOfWeek.WEDNESDAY -> "星期三"
        DayOfWeek.THURSDAY -> "星期四"
        DayOfWeek.FRIDAY -> "星期五"
        DayOfWeek.SATURDAY -> "星期六"
        DayOfWeek.SUNDAY -> "星期日"
    }

    /**
     * 全週字串 → [date]（`yyyy/MM/dd`，可帶時間）那天的單日時段。
     * 已是單日格式、日期解析不出來、或找不到該星期那一行時，原樣回傳。
     */
    fun resolve(raw: String, date: String): String {
        if (!raw.contains("星期")) return raw
        val day = parseDate(date) ?: return raw
        val label = weekdayLabel(day)
        val line = raw.lines().firstOrNull { it.trimStart().startsWith(label) } ?: return raw
        // 去掉「星期X」與其後的冒號（半形或全形）。不能用 substringAfter(":")：
        // 全形冒號的格式「星期一：08:30-12:00」會被切在時間裡的冒號上。
        return line.trimStart().removePrefix(label).trimStart(':', '：', ' ', '　')
            .trim().replace("–", "-")
    }

    /** 單日字串是否為公休（只有「休息」這類字、沒有任何時間） */
    fun isClosed(resolved: String): Boolean {
        val s = resolved.trim()
        if (s.isEmpty() || TIME.containsMatchIn(s)) return false
        return CLOSED_MARKERS.any { s.contains(it) }
    }

    /** [raw]（全週或單日）在 [date] 當天是否公休 */
    fun closedOn(raw: String, date: String): Boolean = isClosed(resolve(raw, date))

    private val RANGE = Regex("""(\d{1,2}):(\d{2})\s*[-–~～]\s*(\d{1,2}):(\d{2})""")

    /**
     * 單日營業時間 [resolved] 在 [startMins] 抵達、待 [visitMins] 分鐘，是不是全程都開著。
     * 回傳 true／false；沒有資料或解析不出時段回 null（不知道，別當成開或不開）。
     *
     * 給「清晨要去看日出」這類對時刻敏感的判斷用：只知道當天有開不夠，
     * 05:30 到的時候得真的開著才行。
     */
    fun isOpenAt(resolved: String, startMins: Int, visitMins: Int): Boolean? {
        val s = resolved.trim()
        if (s.isEmpty() || s == "未提供" || s == "未知") return null
        if (isClosed(s)) return false
        if (ALL_DAY.containsMatchIn(s)) return true
        val ranges = RANGE.findAll(s).map { m ->
            val open = m.groupValues[1].toInt() * 60 + m.groupValues[2].toInt()
            var close = m.groupValues[3].toInt() * 60 + m.groupValues[4].toInt()
            if (close <= open) close += 24 * 60   // 跨夜（如 16:00–00:00）
            open to close
        }.toList()
        if (ranges.isEmpty()) return null
        return ranges.any { (open, close) -> startMins >= open && startMins + visitMins <= close }
    }

    /**
     * 單日營業時間 [resolved] 的各時段（開門, 關門）分鐘數；跨夜的關門時間加 24 小時。
     * 全天營業、公休、沒資料或解析不出時段都回 null——呼叫端各自判斷那幾種情況。
     */
    fun openRanges(resolved: String): List<Pair<Int, Int>>? {
        val s = resolved.trim()
        if (s.isEmpty() || s == "未提供" || s == "未知" || isClosed(s) || ALL_DAY.containsMatchIn(s)) return null
        return RANGE.findAll(s).map { m ->
            val open = m.groupValues[1].toInt() * 60 + m.groupValues[2].toInt()
            var close = m.groupValues[3].toInt() * 60 + m.groupValues[4].toInt()
            if (close <= open) close += 24 * 60
            open to close
        }.toList().ifEmpty { null }
    }

    /**
     * 給 prompt 用的營業時間一行。
     *
     * 過去直接把全週原文（七行）塞進去，AI 看不出哪天休；而且只要字串裡有「24」就被標成
     * 「全天」（例如 12:24 也算）。改成依行程日期取當天：
     *  - 各天相同 → 一個值；多日且不同 → 「第 1 天 …／第 2 天 公休」，AI 才知道不能排在哪天
     */
    fun promptLabel(raw: String, dates: List<String>): String {
        if (raw.isBlank() || raw == "未提供") return "時間未知"
        val usable = dates.filter { it.isNotBlank() }
        fun label(resolved: String): String = when {
            isClosed(resolved) -> "公休"
            ALL_DAY.containsMatchIn(resolved) -> "全天"
            else -> resolved.lines().joinToString(" ") { it.trim() }.trim()
        }
        if (usable.isEmpty()) {
            // 沒有行程日期可對：全週字串不能亂挑一天。七天都一樣才給值，否則老實說依星期而異
            if (!raw.contains("星期")) return label(raw)
            val perDay = raw.lines().filter { it.contains("星期") }
                .map { label(it.substringAfter("星期").drop(1).trimStart(':', '：', ' ', '　')) }
            return if (perDay.distinct().size == 1) perDay[0] else "時間依星期而異"
        }
        val labels = usable.map { label(resolve(raw, it)) }
        if (labels.distinct().size == 1) return labels[0]
        return labels.mapIndexed { i, l -> "第 ${i + 1} 天 $l" }.joinToString("／")
    }
}
