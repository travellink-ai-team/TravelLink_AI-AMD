package com.example.travellink_ai.data.agent

/**
 * Server-Sent Events 的最小解析器：把收到的文字片段切成一個個事件的 data 內容。
 *
 * 後端每個事件寫成 `data: {json}` 加一個空行；網路片段可能在任何地方斷開（一行切成兩半、
 * 一次收到好幾個事件），行尾也可能是 `\r\n`。這裡只處理 `data:` 欄位，其他欄位（event／id／註解）略過。
 */
class SseParser {
    private val pending = StringBuilder()
    private val dataLines = mutableListOf<String>()

    /** 餵入一段文字，回傳這段之後已完整的事件 data（可能是空清單） */
    fun feed(chunk: String): List<String> {
        pending.append(chunk)
        val out = mutableListOf<String>()
        while (true) {
            val nl = pending.indexOf("\n")
            if (nl < 0) break
            val line = pending.substring(0, nl).removeSuffix("\r")
            pending.delete(0, nl + 1)
            if (line.isEmpty()) {
                if (dataLines.isNotEmpty()) {
                    out += dataLines.joinToString("\n")
                    dataLines.clear()
                }
            } else if (line.startsWith("data:")) {
                dataLines += line.removePrefix("data:").removePrefix(" ")
            }
        }
        return out
    }

    /** 串流結束：沒有以空行收尾的最後一個事件也送出 */
    fun finish(): List<String> {
        val tail = pending.toString().removeSuffix("\r")
        pending.clear()
        if (tail.startsWith("data:")) dataLines += tail.removePrefix("data:").removePrefix(" ")
        return if (dataLines.isEmpty()) emptyList() else listOf(dataLines.joinToString("\n")).also { dataLines.clear() }
    }
}
