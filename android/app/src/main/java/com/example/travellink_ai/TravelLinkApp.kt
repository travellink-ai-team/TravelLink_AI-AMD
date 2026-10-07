package com.example.travellink_ai

import android.app.Application
import com.example.travellink_ai.data.ai.AiProvider
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class TravelLinkApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AiProvider.init(this)
        com.example.travellink_ai.data.ai.ShowcaseMode.init(this)
    }
}
