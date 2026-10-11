package com.example.travellink_ai.debug

import com.example.travellink_ai.BuildConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Debug-only：demo 用的時鐘＝手機時間＋快轉量。
 *
 * 行程進行中的時間判斷（打卡時間→落後／超前、預計離開、離開提醒、經過時間）都看這裡，
 * demo 時按「+15 分」就能讓行程「過了 15 分鐘」，不用改手機設定（改手機時間會讓 Firebase 出錯）。
 * 只有開發版且開著 demo 模式時快轉量才不為 0；關掉 demo 模式就歸零，正式版恆等於手機時間。
 *
 * Android 鬧鐘照真實時間響，排鬧鐘時要用 [toRealTime] 換回真實時間。
 */
object DemoClock {
    private val _offsetMs = MutableStateFlow(0L)
    val offsetMs: StateFlow<Long> = _offsetMs.asStateFlow()

    /** 行程用的「現在」 */
    fun now(): Long = System.currentTimeMillis() + offset()

    /** demo 時間 → 真實時間（排 AlarmManager 用） */
    fun toRealTime(demoMs: Long): Long = demoMs - offset()

    fun advance(ms: Long) { if (BuildConfig.DEBUG) _offsetMs.value += ms }

    /** 快轉到指定的 demo 時間（不往回撥：落後／打卡紀錄不能倒流） */
    fun jumpTo(demoMs: Long) {
        if (!BuildConfig.DEBUG) return
        val delta = demoMs - now()
        if (delta > 0) _offsetMs.value += delta
    }

    /** 直接設成某個 demo 時間，可以往回撥（例如設成行程開始時間，而那時間已經過了） */
    fun setTo(demoMs: Long) {
        if (BuildConfig.DEBUG) _offsetMs.value = demoMs - System.currentTimeMillis()
    }

    fun reset() { _offsetMs.value = 0L }

    /** 目前是否有快轉（demo 中） */
    fun isShifted(): Boolean = offset() != 0L

    private fun offset(): Long = if (BuildConfig.DEBUG) _offsetMs.value else 0L
}
