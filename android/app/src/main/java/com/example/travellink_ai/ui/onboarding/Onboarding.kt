package com.example.travellink_ai.ui.onboarding

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 首次使用引導的「看過了」紀錄：歡迎輪播一次、各頁提示（[CoachTour]）各一次。
 *
 * 記在這支手機（SharedPreferences），不進 Firestore：換手機或重裝會再看一次，對新使用者反而是好事。
 * 側欄「使用教學」呼叫 [replay] 清掉紀錄、重播歡迎輪播，各頁提示也會在下次進到該頁時再出現。
 */
object Onboarding {
    private const val PREFS = "onboarding"
    private const val KEY_WELCOME = "welcome_seen"
    private const val KEY_TOURS = "tours_seen"

    private var prefs: SharedPreferences? = null

    private val _showWelcome = MutableStateFlow(false)
    /** 目前要不要顯示歡迎輪播（第一次打開 App，或按了「使用教學」） */
    val showWelcome: StateFlow<Boolean> = _showWelcome.asStateFlow()

    private val _seenTours = MutableStateFlow<Set<String>>(emptySet())
    val seenTours: StateFlow<Set<String>> = _seenTours.asStateFlow()

    fun init(context: Context) {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs = p
        _showWelcome.value = !p.getBoolean(KEY_WELCOME, false)
        _seenTours.value = p.getStringSet(KEY_TOURS, emptySet())!!.toSet()
    }

    fun finishWelcome() {
        _showWelcome.value = false
        prefs?.edit()?.putBoolean(KEY_WELCOME, true)?.apply()
    }

    fun markTourSeen(id: String) {
        val next = _seenTours.value + id
        _seenTours.value = next
        prefs?.edit()?.putStringSet(KEY_TOURS, next)?.apply()
    }

    /** 側欄「使用教學」：重播歡迎輪播，各頁提示全部重新出現（中途關掉 App，下次打開還會看到輪播） */
    fun replay() {
        _seenTours.value = emptySet()
        prefs?.edit()?.remove(KEY_TOURS)?.putBoolean(KEY_WELCOME, false)?.apply()
        _showWelcome.value = true
    }
}

/**
 * 各頁提示的內容。targetId 對應畫面上掛了 [coachTarget] 的元件；null＝沒有對應元件，提示置中顯示。
 * 改文案只要改這裡。
 */
object Tours {
    // ── 提示指向的元件 ──
    const val T_HOME_CREATE = "home_create"
    const val T_HOME_JOIN = "home_join"
    const val T_HOME_QUICK_PICK = "home_quick_pick"
    const val T_HOME_MENU = "home_menu"
    const val T_NAV = "nav_bar"
    const val T_DRAWER_OFFLINE = "drawer_offline_map"
    const val T_DRAWER_TUTORIAL = "drawer_tutorial"
    const val T_PREVIEW_STOP = "preview_stop"
    const val T_PREVIEW_ROUTE = "preview_route"
    const val T_PREVIEW_START = "preview_start"
    const val T_PREVIEW_ILLUSTRATION = "preview_illustration"
    const val T_ASSISTANT = "assistant_avatar"
    const val T_ONGOING_CHECKIN = "ongoing_checkin"
    const val T_ONGOING_MAP = "ongoing_map"
    const val T_ONGOING_PARKING = "ongoing_parking"
    const val T_ONGOING_LEAVE = "ongoing_leave"
    const val T_RECAP_AUTO = "recap_auto"

    val HOME = TourSpec("home", listOf(
        CoachStep(T_HOME_CREATE, "建立微旅行",
            "從這裡開始：選日期、人數和喜好，AI 會從台東在地景點與餐廳排出一日或兩天一夜的行程。"),
        CoachStep(T_HOME_JOIN, "加入旅伴的行程",
            "朋友傳來邀請碼？在這裡輸入，就能一起編輯同一趟行程，大家的修改會即時同步。"),
        CoachStep(T_HOME_QUICK_PICK, "現在去哪？",
            "不想排整趟行程？點這裡，依你的位置和天氣，從附近挑一個現在有開的景點或餐廳，不喜歡就換一個。"),
        CoachStep(T_NAV, "四個分頁",
            "探索有官方精選範本可以直接套用；「我的微旅行」放你所有的行程；好友頁可以加好友、邀請一起出遊。"),
        CoachStep(T_HOME_MENU, "側欄選單",
            "個人喜好、台東離線地圖、通知設定都在這裡，也可以隨時重看使用教學。")
    ))

    val DRAWER = TourSpec("drawer", listOf(
        CoachStep(T_DRAWER_OFFLINE, "出發前下載離線地圖",
            "山區和海邊常沒訊號。先在有網路時下載台東離線地圖（約 19 MB），沒網路也看得到地圖和路線。"),
        CoachStep(T_DRAWER_TUTORIAL, "使用教學",
            "想再看一次這些說明，點這裡就會重新播放。")
    ))

    val PREVIEW = TourSpec("preview", listOf(
        CoachStep(T_PREVIEW_STOP, "調整景點",
            "點景點卡可以改停留時間、換一個景點；按住卡片拖曳可以調整順序，時間會自動重算。"),
        CoachStep(T_PREVIEW_ROUTE, "查看路線",
            "在地圖上看整趟路線、每段車程，開車的話還有附近停車場和走到景點的時間。"),
        CoachStep(T_ASSISTANT, "AI 隨行管家",
            "有問題直接問它，例如「附近有什麼好吃的」「這站可以換成室內的嗎」。頭像可以拖到畫面旁邊。"),
        CoachStep(T_PREVIEW_START, "出發當天按這裡",
            "開始行程後會進入逐站打卡模式，依序導航、到站打卡。"),
        CoachStep(null, "別忘了離線地圖",
            "出發前到側欄下載「台東離線地圖」，沒訊號的地方也看得到路線。")
    ))

    /**
     * 插圖：第一次有插圖時跳，重播教學時接在 [PREVIEW] 後面。
     * 不併進 PREVIEW：剛建好行程時多半還沒畫圖，那一步會被略過，之後畫好就再也看不到
     */
    val ILLUSTRATION = TourSpec("illustration", listOf(
        CoachStep(T_PREVIEW_ILLUSTRATION, "手繪插圖可以點",
            "點圖上的景點可以看介紹、營業時間和導航；點右下角「看大圖」可以放大，多日行程能切換第幾天。出發後還會標出你走到哪。")
    ))

    val ONGOING = TourSpec("ongoing", listOf(
        CoachStep(T_ONGOING_CHECKIN, "到站打卡",
            "到了景點按「到達打卡」，就會記下時間，也會提醒你拍照。按導航可以直接開 Google 地圖。"),
        CoachStep(T_ONGOING_MAP, "行程地圖",
            "看你目前的位置、停車點和附近廁所。"),
        // 只有自己開車／騎車的行程才有這顆按鈕，其他行程這步會略過
        CoachStep(T_ONGOING_PARKING, "記停車點",
            "停好車按一下，會記下現在的位置，可以在地圖上拖曳微調、寫備註（例如 B2 紅區）；回程時按導航就能找回車子。"),
        CoachStep(null, "旅程結束自動做短片",
            "到側欄打開「回顧短片」的自動產生，按「完成行程」後就會在背景做好回顧短片，好了會通知你。")
    ))

    val ONGOING_HERE = TourSpec("ongoing_here", listOf(
        CoachStep(T_ONGOING_LEAVE, "想多待一下？",
            "點這裡調整預計離開時間，後面的行程會自動順延；排不下時會提醒你，由你決定要不要重排。")
    ))

    val RECAP = TourSpec("recap", listOf(
        CoachStep(T_RECAP_AUTO, "旅程結束自動做",
            "打開這個開關，之後每趟按「完成行程」就會自動做好回顧短片並通知你；做好後還是可以在這裡修改、重新產生。")
    ))
}

data class TourSpec(val id: String, val steps: List<CoachStep>)

data class CoachStep(val targetId: String?, val title: String, val text: String)
