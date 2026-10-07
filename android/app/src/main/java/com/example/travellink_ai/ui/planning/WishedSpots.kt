package com.example.travellink_ai.ui.planning

/**
 * 精靈「希望包含的景點或活動」→ 必排景點。
 *
 * 過去這一欄只是 prompt 裡的一行「特殊需求」，但 prompt 同時規定「只能從資料庫選」，
 * 而資料庫是 Google 周邊搜尋組的池子，使用者指定的點常常不在裡面（書店不在搜尋類型、
 * 名稱與 Google 不同、被評論門檻或「近期去過」刷掉）——實測從「台東・市區慢走」範本建立的
 * 行程，三個主軸景點一個都沒排進去。現在指定的點會直接放進候選池、在 prompt 標為必排、
 * AI 漏掉就由程式補回，排程減站時也最後才動它。
 */
object WishedSpots {

    /** 以「想去／想要」開頭、或以活動動詞開頭的是活動（「看日出」「泡溫泉」），不是地名，不查 Google */
    private val LEADING_WANTS = Regex("^(想去|想要|想|要|去)")
    private val ACTIVITY_VERBS = listOf("看", "逛", "吃", "喝", "泡", "玩", "拍", "體驗", "騎", "走", "爬", "買", "聽")

    /** 自由文字拆成一項一項：「萬富倉庫、晃晃二手書店，看日出」→ 三項 */
    fun parse(text: String): List<String> =
        text.split('、', '，', ',', '；', ';', '\n', '。')
            .map { it.trim() }
            .filter { it.length >= 2 }
            .distinct()

    fun isActivity(item: String): Boolean {
        val core = item.replace(LEADING_WANTS, "")
        return ACTIVITY_VERBS.any { core.startsWith(it) }
    }

    /**
     * 兩個名稱是不是指同一個地方（沒有座標時用）：正規化後相等，或較短的（≥3 字）被較長的包含；
     * 括號裡的別名也算（「台東鐵道藝術村 (鐵花新聚落)」對「鐵花新聚落」）。
     */
    fun sameName(a: String, b: String): Boolean {
        val aa = PlaceIdentity.aliases(a)
        val bb = PlaceIdentity.aliases(b)
        return aa.any { x ->
            bb.any { y ->
                if (x == y) true else {
                    val shorter = if (x.length <= y.length) x else y
                    val longer = if (x.length <= y.length) y else x
                    shorter.length >= 3 && longer.contains(shorter)
                }
            }
        }
    }

    /** prompt 的必排段落；沒有指定就回空字串（prompt 與原本逐字相同） */
    fun promptSection(names: List<String>, diningNames: List<String> = emptyList()): String =
        if (names.isEmpty()) "" else
            "\n- 【使用者指定必排】${names.joinToString("、")}——這些地點已在景點資料庫中，" +
                "名稱請照資料庫原樣寫。除非營業時間在行程期間完全排不進去，否則一定要全部排入；" +
                "其餘景點圍繞它們安排順路動線。" +
                (if (diningNames.isEmpty()) "" else
                    "其中${diningNames.joinToString("、")}是餐廳：它就是那個用餐時段的正餐，" +
                        "請排在午餐或晚餐時段，同一時段不要再另外安排別的正餐。")

    /** 行程裡沒有的指定景點（依名稱比對） */
    fun missing(wished: List<String>, stopNames: List<String>): List<String> =
        wished.filter { w -> stopNames.none { sameName(w, it) } }
}
