// Top-level build file where you can add configuration options common to all sub-projects/modules.

// 指定較新的 R8：AGP 8.7.2 內建的 R8 在超大的 coroutine（ItineraryViewModel.generateItineraryWithAI）
// 配置暫存器時，會用 move（非 object）搬參照，Android 9（API 28）驗證器拒絕整個 class，一按產生行程就閃退
buildscript {
    repositories { google() }
    dependencies { classpath("com.android.tools:r8:8.13.25") }
}

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.hilt.android.plugin) apply false
    // Add the dependency for the Google services Gradle plugin
    id("com.google.gms.google-services") version "4.4.4" apply false
    // 當機回報：release 建置時自動上傳 R8 mapping，Console 的 stack trace 才看得懂
    id("com.google.firebase.crashlytics") version "3.0.4" apply false

}