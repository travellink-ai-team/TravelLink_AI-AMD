package com.example.travellink_ai.ui.planning

/**
 * 適合看日出的景點：判斷「這個點能不能當日出那天的第一站」。
 *
 * 過去只把「看日出」寫進 prompt 要 AI 自己安排，AI 也確實把海濱公園排成日出首站，
 * 但排程器為了車程最短把它重排到第 6 站（10:06）。要讓日出真的發生，得在排程前
 * 先決定「哪一站是日出點」，再把它釘在當天第一個。這裡負責前半：挑出日出點。
 *
 * 不是所有 24 小時開放的戶外景點都能看日出（一個水利公園、一條山路步道未必有視野），
 * 所以要求有「日出相關的訊號」：名稱或描述提到日出、已知的東岸日出名點、海岸類關鍵字，
 * 或知識庫標了清晨最佳。再加上抵達時必須真的開著（清晨多數景點沒開）。
 */
data class SpotCandidate(
    val name: String,
    val stopType: String = "",
    val desc: String = "",
    val bestTime: String = "",
    /** 已依日期取單日的營業時間；未提供＝不知道 */
    val hours: String = "未提供",
    val isDining: Boolean = false,
    /** 距出發點的公里數；AI 已排在當天的站不用給（本來就在行程裡） */
    val distanceKm: Double? = null
)

object SunriseSpots {

    /** 名稱或描述明講日出的字眼 */
    private val STRONG_WORDS = listOf("日出", "日昇", "曙光", "朝日", "朝陽")

    /** 台東已知適合看日出的東海岸點（名稱包含即算） */
    private val KNOWN_NAMES = listOf(
        "三仙台", "加路蘭", "海濱公園", "濱海公園", "杉原", "小野柳", "東海岸", "曙光"
    )

    /** 海岸類：面向太平洋，視野開闊 */
    private val COAST_WORDS = listOf("海濱", "濱海", "海岸", "海灘", "海邊", "看海", "海景", "海堤", "海岬")

    /** 清晨不會開、或本質上不是看日出的地方 */
    private val EXCLUDED_TYPES = setOf(
        "餐廳", "咖啡廳", "博物館/文化館", "藝廊/展覽館", "教堂", "廟宇/宗教場所", "學校/部落教育"
    )

    /** 抵達後停留多久要算「開著」 */
    private const val VISIT_MINS = 30

    /** 低於此分數不算適合（避免硬湊一個不像的景點） */
    const val MIN_SCORE = 40

    /** 補入日出點時，從景點庫往外找的半徑 */
    const val SEARCH_RADIUS_M = 25_000

    /**
     * 0 = 不適合。分數越高越適合：日出訊號（名稱／描述／知識庫）決定基本分，
     * 抵達時確定開著再加分，距離出發點越遠越扣分。
     */
    fun score(c: SpotCandidate, arriveMins: Int): Int {
        if (c.isDining || c.stopType in EXCLUDED_TYPES) return 0
        val open = BusinessHours.isOpenAt(c.hours, arriveMins, VISIT_MINS)
        if (open == false) return 0

        val text = c.name + c.desc
        var signal = 0
        if (STRONG_WORDS.any { text.contains(it) }) signal += 40
        // 已知的東岸日出名點：名稱本身就夠格（營業時間不明只是分數較低，不是不能選）
        if (KNOWN_NAMES.any { c.name.contains(it) }) signal += MIN_SCORE
        if (COAST_WORDS.any { text.contains(it) }) signal += 20
        if (c.bestTime.contains("清晨") || c.bestTime.contains("日出")) signal += 25
        if (signal == 0) return 0

        var s = signal
        if (open == true) s += 20
        c.distanceKm?.let { s -= minOf(it * 1.5, 30.0).toInt() }
        return s.coerceAtLeast(0)
    }

    /**
     * 名稱／描述本身有沒有日出訊號（不看營業時間與距離）。
     * 去重用：重複的地點只有在它本來就是日出點時，才該優先留在日出那一天；
     * 台東森林公園這種沒有訊號的重複，留哪一天都一樣，不該因為排在日出那天就被偏袒。
     */
    fun isSunriseCandidate(name: String, desc: String = ""): Boolean =
        score(SpotCandidate(name = name, desc = desc), arriveMins = 0) > 0

    /**
     * 從候選裡挑最適合的日出點；沒有達 [MIN_SCORE] 的就回 null（寧可不釘，
     * 也不要為了「有日出」硬把一個看不到日出的地方排成第一站）。
     * 分數相同時取排在前面的（呼叫端把 AI 原本的第一站放最前面）。
     */
    fun pick(candidates: List<SpotCandidate>, arriveMins: Int): SpotCandidate? =
        candidates
            .map { it to score(it, arriveMins) }
            .filter { it.second >= MIN_SCORE }
            .maxByOrNull { it.second }   // maxByOrNull 遇到同分保留先出現者
            ?.first
}
