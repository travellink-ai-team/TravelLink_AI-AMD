package com.example.travellink_ai.data.ai

import android.content.Context
import com.example.travellink_ai.BuildConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 文字 AI 走哪個引擎。
 *
 * 同一份 App 參加兩組比賽：AI 工具運用組用 Gemini（預設），AMD AI 代理人組要展示 AMD 算力。
 * 切到 AMD 時，文字生成改送 AMD 版網站的代理（[AmdTextClient]），由它轉給工研院的 gpt-oss-120b；
 * 圖片生成沒有對應模型，一律照舊走 Gemini。
 *
 * 只有開發版能切到 AMD（切換放在抽屜，正式版看不到），正式版恆為 Gemini，AI 工具運用組的行為不受影響。
 */
object AiProvider {
    enum class Kind { GEMINI, AMD }

    private const val PREFS = "ai_provider"
    private const val KEY = "kind"

    private val _kind = MutableStateFlow(Kind.GEMINI)
    val kind: StateFlow<Kind> = _kind.asStateFlow()

    /** 文字生成是否走 AMD */
    val isAmd: Boolean get() = BuildConfig.DEBUG && _kind.value == Kind.AMD

    fun init(context: Context) {
        if (!BuildConfig.DEBUG) return
        val saved = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)
        _kind.value = Kind.entries.firstOrNull { it.name == saved } ?: Kind.GEMINI
    }

    fun set(context: Context, kind: Kind) {
        if (!BuildConfig.DEBUG) return
        _kind.value = kind
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, kind.name).apply()
    }
}

/**
 * 🎬 展示模式（開發版限定，決賽 demo 用）：旅程應變助理只留「情境模擬」三個按鈕，
 * mock／來源這類開發選項藏起來；「模擬情境」「錄製重播」等標示照樣顯示，對評審要誠實。
 * mock 來源要用的話，先關掉展示模式選好再打開（選擇會保留）。
 */
object ShowcaseMode {
    private const val PREFS = "ai_provider"
    private const val KEY = "showcase"

    private val _on = MutableStateFlow(false)
    val on: StateFlow<Boolean> = _on.asStateFlow()

    fun init(context: Context) {
        if (!BuildConfig.DEBUG) return
        _on.value = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY, false)
    }

    fun set(context: Context, v: Boolean) {
        if (!BuildConfig.DEBUG) return
        _on.value = v
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY, v).apply()
    }
}
