package com.example.travellink_ai.ui.recap

/**
 * 回顧短片分享到 IG（C5）用的網址與說明文案。
 *
 * 連結一律是網頁首頁，**不放 travellink://join/{docId}**：那是共編邀請，貼在公開貼文上任何人都能加入行程。
 * Reels 說明欄的連結點不了，所以網址寫成文字；限動則複製網址讓使用者貼到「連結」貼紙。
 */
object RecapShare {
    /** 提案定案：先用這個網址（片尾、連結貼紙同一個） */
    const val WEB_URL = "https://travel-link-ai.duckdns.org"
    private const val MAX_ROUTE_STOPS = 5

    /** 貼到 Reels 說明欄的文案：標題、地區天數、路線、網址、hashtag */
    fun caption(trip: RecapTrip): String {
        val days = trip.stops.maxOfOrNull { it.dayIndex } ?: 1
        val region = trip.region.trim()
        val names = trip.stops.map { it.name.trim() }.filter { it.isNotEmpty() }
        val route = names.take(MAX_ROUTE_STOPS).joinToString(" → ") +
            if (names.size > MAX_ROUTE_STOPS) "（共 ${names.size} 站）" else ""
        val tag = regionTag(region)
        val tags = listOfNotNull("#TravelLinkAI", "#旅遊回顧", tag?.let { "#${it}旅遊" }, tag?.let { "#${it}景點" })
        return listOfNotNull(
            trip.title.trim().ifEmpty { null },
            "📍 " + listOfNotNull(region.ifEmpty { null }, "$days 天 ${trip.stops.size} 個景點").joinToString("・"),
            route.ifEmpty { null },
            "行程用 TravelLink AI 規劃：${WEB_URL.removePrefix("https://")}",
            "",
            tags.joinToString(" ")
        ).joinToString("\n")
    }

    /** hashtag 用的地區：去掉空白與符號，「台東縣」→「台東」（只剩一個字就不去） */
    fun regionTag(region: String): String? {
        val clean = region.filter { it.isLetterOrDigit() }
        if (clean.isEmpty()) return null
        return if (clean.length > 2 && (clean.endsWith("縣") || clean.endsWith("市"))) clean.dropLast(1) else clean
    }
}
