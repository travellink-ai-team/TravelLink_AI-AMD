package com.example.travellink_ai.ui.planning

import com.example.travellink_ai.data.model.Stop
import java.time.LocalDate
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * 會改變「幾點做什麼」的特別需求。
 *
 * 一般的特別需求（住宿、餐廳、想去的景點）只影響「選哪些點」，寫進 prompt 就夠；
 * 這幾種則要動到每日時間窗——日出要提早出發、夜景要延後收工，光靠 prompt 是排不到的
 * （排程器從固定的 09:00 起跑，AI 講再多也不會改變時間軸）。
 */
enum class TimeRequest { SUNRISE, SUNSET, NIGHT, STARS }

/**
 * 「特別需求」欄位上方的快速標籤。
 *
 * @property phrase 寫進 prompt 特殊需求的文字
 * @property request 會改動時間窗的標籤才有值；「室內為主」這類只影響選點的為 null
 */
data class QuickTag(
    val id: String,
    val emoji: String,
    val label: String,
    val phrase: String,
    val request: TimeRequest? = null
)

object SpecialRequests {

    val QUICK_TAGS = listOf(
        QuickTag("sunrise", "🌅", "看日出", "想看日出", TimeRequest.SUNRISE),
        QuickTag("sunset", "🌇", "看日落", "想看日落／夕陽", TimeRequest.SUNSET),
        QuickTag("night", "🌙", "夜景／夜市", "想逛夜市或看夜景", TimeRequest.NIGHT),
        QuickTag("stars", "⭐", "看星星", "想看星空", TimeRequest.STARS),
        QuickTag("indoor", "☔", "室內為主", "以室內或有遮蔽的景點為主（雨天備案）")
    )

    // ── 解析：標籤 + 自由文字 ─────────────────────────────────────

    private val KEYWORDS = mapOf(
        TimeRequest.SUNRISE to listOf("日出", "日昇", "曙光"),
        TimeRequest.SUNSET to listOf("日落", "夕陽", "黃昏"),
        TimeRequest.NIGHT to listOf("夜景", "夜市", "夜遊"),
        TimeRequest.STARS to listOf("星空", "星星", "銀河", "觀星")
    )

    /**
     * 否定用語。刻意用詞組而不是單字：「特別想看日出」含「別」、「免費夜市」含「免」、
     * 「不錯的日出」含「不」，單字比對全會誤判成否定。
     */
    private val NEGATIONS = listOf("不想", "不要", "不用", "不必", "不需", "不看", "不去", "不逛", "避開", "避免", "別去", "別看")

    private val CLAUSE_BREAKS = charArrayOf('，', ',', '。', '；', ';', '、', '\n')

    /** 關鍵字前面（同一子句內、最多 6 字）有沒有否定用語 */
    private fun isNegated(text: String, index: Int): Boolean {
        var prefix = text.substring(maxOf(0, index - 6), index)
        CLAUSE_BREAKS.forEach { prefix = prefix.substringAfterLast(it) }
        return NEGATIONS.any { prefix.contains(it) }
    }

    /** 從自由文字抓出時間型需求；「不想看日出」這類否定不算 */
    fun parse(text: String): Set<TimeRequest> {
        if (text.isBlank()) return emptySet()
        return KEYWORDS.mapNotNull { (request, words) ->
            val hit = words.any { w ->
                var from = 0
                var found = false
                while (true) {
                    val i = text.indexOf(w, from)
                    if (i < 0) break
                    if (!isNegated(text, i)) { found = true; break }
                    from = i + w.length
                }
                found
            }
            request.takeIf { hit }
        }.toSet()
    }

    /**
     * 已選標籤 + 「希望包含」自由文字 → 時間型需求。
     * 只看這兩處：住宿／餐廳欄位常有店名（「日出咖啡」「日昇飯店」），拿去比對會誤判。
     */
    fun resolve(tagIds: List<String>, freeText: String): Set<TimeRequest> =
        tagIds.mapNotNull { id -> QUICK_TAGS.firstOrNull { it.id == id }?.request }.toSet() + parse(freeText)

    // ── 說明文字（UI 提示、planningReason、prompt 共用）──────────────

    private fun dayLabel(windows: List<DayPlanner.DayWindow>, dayIndex: Int) =
        if (windows.size <= 1) "當天" else "第 $dayIndex 天"

    private fun hhmm(mins: Int) = DayPlanner.hhmmOf(mins)

    /** 日出所在的那一天：單日就是當天；多日是「住一晚後的隔天早上」 */
    internal fun sunriseDay(windows: List<DayPlanner.DayWindow>) = if (windows.size <= 1) 1 else 2

    /**
     * 給使用者看的一行行說明。[windows] 必須是已套用過 [DayPlanner.applyTimeRequests] 的時間窗。
     */
    fun describe(windows: List<DayPlanner.DayWindow>, requests: Set<TimeRequest>): List<String> = buildList {
        if (requests.isEmpty()) return@buildList
        if (TimeRequest.SUNRISE in requests) {
            windows.firstOrNull { it.dayIndex == sunriseDay(windows) }?.let { w ->
                add("🌅 ${dayLabel(windows, w.dayIndex)}日出約 ${hhmm(sunriseOf(w))}，該日從 ${hhmm(w.startMins)} 出發")
            }
        }
        val day1 = windows.firstOrNull { it.dayIndex == 1 } ?: return@buildList
        if (TimeRequest.SUNSET in requests)
            add("🌇 ${dayLabel(windows, 1)}日落約 ${hhmm(sunsetOf(day1))}，行程排到 ${hhmm(day1.endMins)}")
        if (TimeRequest.NIGHT in requests)
            add("🌙 ${dayLabel(windows, 1)}行程排到 ${hhmm(day1.endMins)}，安排夜市或夜間景點")
        if (TimeRequest.STARS in requests)
            add("⭐ ${dayLabel(windows, 1)}行程排到 ${hhmm(day1.endMins)}，天黑後安排觀星地點")
    }

    /**
     * 附在選點 prompt 特殊需求那行後面。每行都以換行開頭：沒有需求時是空字串，
     * prompt 與改動前逐字相同（不會多出空行）。
     */
    fun promptSection(windows: List<DayPlanner.DayWindow>, requests: Set<TimeRequest>): String = buildString {
        if (requests.isEmpty()) return@buildString
        if (TimeRequest.SUNRISE in requests) {
            windows.firstOrNull { it.dayIndex == sunriseDay(windows) }?.let { w ->
                append("\n- 【日出需求】${dayLabel(windows, w.dayIndex)}日出約 ${hhmm(sunriseOf(w))}，該日已提早到 ${hhmm(w.startMins)} 出發。" +
                    "該日第一站請安排適合看日出的戶外景點（視野開闊、東向海岸或高處，營業時間為「全天」者優先）；" +
                    "清晨多數店家尚未營業，嚴禁把餐廳或需購票的室內景點排在第一站。")
            }
        }
        val day1 = windows.firstOrNull { it.dayIndex == 1 } ?: return@buildString
        if (TimeRequest.SUNSET in requests)
            append("\n- 【日落需求】${dayLabel(windows, 1)}日落約 ${hhmm(sunsetOf(day1))}。" +
                "請讓該天在日落前後安排一站適合看夕陽的戶外景點（海岸、公園、觀景台），該時段不要排餐廳。")
        if (TimeRequest.NIGHT in requests)
            append("\n- 【夜間需求】${dayLabel(windows, 1)}行程延長到 ${hhmm(day1.endMins)}。" +
                "請把夜市或夜間仍營業的景點排在傍晚之後。")
        if (TimeRequest.STARS in requests)
            append("\n- 【觀星需求】${dayLabel(windows, 1)}行程延長到 ${hhmm(day1.endMins)}。" +
                "請在天黑後安排一站適合觀星的開闊戶外地點（遠離市區光害者優先）。")
    }

    /**
     * 生成完成後回頭檢查：日出那天的第一站有沒有真的趕在日出前後。
     * 排程器會依營業時間重排，AI 想把日出點放第一站不代表最後真的在那——不查證就會像
     * 「嘴上說日出、實際 09:03 才到」，使用者只能事後自己發現。
     */
    fun sunriseOutcome(windows: List<DayPlanner.DayWindow>, stops: List<Stop>): String? {
        val day = sunriseDay(windows)
        val w = windows.firstOrNull { it.dayIndex == day } ?: return null
        val rise = sunriseOf(w)
        // 用「最早抵達時間」找第一站，不能用 order：生成當下 order 還是 AI 原始順序，
        // 排程器重排後才會重新編號（實測把 05:34 的第一站誤報成 10:06 的海濱公園）
        val first = stops.filter { it.dayIndex == day && !it.isStation }
            .filter { DayPlanner.parseHhMm(it.time) != null }
            .minByOrNull { DayPlanner.parseHhMm(it.time)!! } ?: return null
        val t = DayPlanner.parseHhMm(first.time) ?: return null
        val label = dayLabel(windows, day)
        return if (t <= rise + ARRIVE_TOLERANCE_MINS)
            "$label 第一站「${first.name}」${first.time} 抵達（日出約 ${hhmm(rise)}）"
        else
            "⚠️ $label 第一站「${first.name}」排在 ${first.time}，晚於日出（約 ${hhmm(rise)}）——清晨多數景點未營業，這次沒能安排到日出時段"
    }

    /** 抵達時間比日出晚多少分鐘內仍算「趕上」 */
    private const val ARRIVE_TOLERANCE_MINS = 45

    internal fun sunriseOf(w: DayPlanner.DayWindow): Int = SunTimes.of(w.date)?.first ?: SunTimes.FALLBACK_SUNRISE
    internal fun sunsetOf(w: DayPlanner.DayWindow): Int = SunTimes.of(w.date)?.second ?: SunTimes.FALLBACK_SUNSET
}

/**
 * 日出／日落時刻（台東，UTC+8）。
 *
 * 用天文公式算，不用查表：查表要處理逐日、逐年，公式一個函式搞定，
 * 台東的誤差在幾分鐘內，對「日出前 30 分出發」綽綽有餘。
 * 公式出處：Sunrise equation（Wikipedia），大氣折射取 −0.833°。
 */
object SunTimes {
    private const val LAT = 22.75      // 台東市
    private const val LON = 121.15
    private const val TZ_MINS = 8 * 60

    /** 日期解析不出來時的保底值（台東全年日出約 05:10–06:40、日落約 17:10–18:40 的中間值） */
    const val FALLBACK_SUNRISE = 5 * 60 + 45
    const val FALLBACK_SUNSET = 18 * 60

    /** "yyyy/MM/dd" → (日出, 日落) 當地分鐘數；解析失敗回 null */
    fun of(date: String): Pair<Int, Int>? {
        val parts = date.trim().replace('-', '/').split("/")
        if (parts.size != 3) return null
        val y = parts[0].toIntOrNull() ?: return null
        val m = parts[1].toIntOrNull() ?: return null
        val d = parts[2].toIntOrNull() ?: return null
        val day = try { LocalDate.of(y, m, d) } catch (e: Exception) { return null }

        val n = (day.toEpochDay() - 10957L).toDouble()          // 距 J2000.0（2000-01-01 正午）的天數
        val jStar = n - LON / 360.0                              // 當地平太陽時（東經為正）
        val mAnom = Math.toRadians((357.5291 + 0.98560028 * jStar) % 360.0)
        val center = 1.9148 * sin(mAnom) + 0.0200 * sin(2 * mAnom) + 0.0003 * sin(3 * mAnom)
        val lambda = Math.toRadians((Math.toDegrees(mAnom) + center + 180.0 + 102.9372) % 360.0)
        val transit = jStar + 0.0053 * sin(mAnom) - 0.0069 * sin(2 * lambda)
        val sinDec = sin(lambda) * sin(Math.toRadians(23.4397))
        val cosDec = cos(asin(sinDec))
        val phi = Math.toRadians(LAT)
        val cosH = (sin(Math.toRadians(-0.833)) - sin(phi) * sinDec) / (cos(phi) * cosDec)
        if (cosH < -1.0 || cosH > 1.0) return null
        val halfDay = Math.toDegrees(acos(cosH)) / 360.0

        fun localMins(j: Double): Int {
            // j 的整數部分對應 UTC 正午，小數部分往後推
            val utcMins = (j - floor(j)) * 1440.0 + 720.0
            return (((utcMins + TZ_MINS).roundToInt() % 1440) + 1440) % 1440
        }
        return localMins(transit - halfDay) to localMins(transit + halfDay)
    }
}
