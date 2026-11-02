package com.example.travellink_ai

import android.app.Application
import com.example.travellink_ai.data.ai.AiProvider
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.crashlytics.FirebaseCrashlytics
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class TravelLinkApp : Application() {
    override fun onCreate() {
        super.onCreate()
        setupCrashlytics()
        AiProvider.init(this)
        com.example.travellink_ai.data.ai.IllustrationByok.init(this)
        com.example.travellink_ai.data.ai.ShowcaseMode.init(this)
        com.example.travellink_ai.ui.onboarding.Onboarding.init(this)
        warmUpMaps()
    }

    /**
     * Google 地圖第一次建立時會在主執行緒載入地圖模組（log 的 ClientParamsBlocking … MapView.onCreate），
     * 第一次進地圖頁明顯卡一下。App 開好、首頁畫完之後先在背景初始化，進地圖時就不用等。
     */
    private fun warmUpMaps() {
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            runCatching {
                com.google.android.gms.maps.MapsInitializer.initialize(
                    this, com.google.android.gms.maps.MapsInitializer.Renderer.LATEST
                ) {
                    // 只初始化不夠：實測第一次 MapView.onCreate 還是在主執行緒卡 1.7 秒（ClientParamsBlocking）。
                    // 在背景先建一個不顯示的 MapView 跑過 onCreate，同一個 process 之後再建就不用等
                    runCatching {
                        com.google.android.gms.maps.MapView(this).apply { onCreate(null); onDestroy() }
                    }
                }
            }
        }, 2_000L)
    }

    /**
     * 當機回報只收正式版（試用者手機）：開發時 debug 版的閃退不混進 Console。
     * 用 Firebase uid 標記是誰（不是 email），試用者回報問題時可以對到那一筆。
     */
    private fun setupCrashlytics() {
        val crashlytics = FirebaseCrashlytics.getInstance()
        crashlytics.isCrashlyticsCollectionEnabled = !BuildConfig.DEBUG
        FirebaseAuth.getInstance().addAuthStateListener { auth ->
            crashlytics.setUserId(auth.currentUser?.uid.orEmpty())
        }
    }
}
