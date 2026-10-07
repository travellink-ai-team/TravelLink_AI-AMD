import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt.android.plugin)
    id("com.google.gms.google-services")
}

val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) load(f.inputStream())
}
val mapsApiKey: String = localProps.getProperty("MAPS_API_KEY", "")
val directionsApiKey: String = localProps.getProperty("DIRECTIONS_API_KEY", "")
val tdxClientId: String = localProps.getProperty("TDX_CLIENT_ID", "")
val tdxClientSecret: String = localProps.getProperty("TDX_CLIENT_SECRET", "")
val cwaApiKey: String = localProps.getProperty("CWA_API_KEY", "")
// 回顧短片後端 base URL（待組員提供公開位址；空＝功能未啟用）
val recapApiBase: String = localProps.getProperty("RECAP_API_BASE", "")

android {
    namespace = "com.example.travellink_ai"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.example.travellink_ai"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // 原生程式庫（MapLibre 每種架構約 13 MB）只打包 64 位元手機＋模擬器，
        // 直接傳 APK 測試時不致暴增；32 位元舊手機（armeabi-v7a）因此無法安裝（使用者 2026-09-30 選定）
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }

        manifestPlaceholders["MAPS_API_KEY"] = mapsApiKey
        buildConfigField("String", "MAPS_API_KEY",       "\"$mapsApiKey\"")
        buildConfigField("String", "DIRECTIONS_API_KEY", "\"$directionsApiKey\"")
        buildConfigField("String", "TDX_CLIENT_ID",     "\"$tdxClientId\"")
        buildConfigField("String", "TDX_CLIENT_SECRET", "\"$tdxClientSecret\"")
        buildConfigField("String", "CWA_API_KEY",       "\"$cwaApiKey\"")
        buildConfigField("String", "RECAP_API_BASE",    "\"$recapApiBase\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    testOptions {
        installation {
            // 安裝時就授予 runtime 權限（-g）。API < 29 的外部儲存掛載是 per-process，
            // process 起來之後才 pm grant 不會生效，必須在安裝階段給，
            // 否則 MediaStoreSaver 的 legacy 相簿路徑無法被測到。
            installOptions.add("-g")
        }
    }
}

dependencies {
    // UI 與基礎庫
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)

    // Firebase
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.firestore)
    implementation(libs.firebase.analytics)
    implementation(libs.firebase.storage.ktx)
    implementation(libs.firebase.auth)
    implementation("com.google.firebase:firebase-functions")

    // Maps
    implementation("com.google.android.gms:play-services-maps:18.2.0")
    implementation("com.google.maps.android:maps-compose:4.3.3")
    // 離線底圖：MapLibre 讀本機 PMTiles（OSM 圖資）。11.x 的 kotlin-stdlib 2.0 與本專案 Kotlin 2.1 相容
    implementation("org.maplibre.gl:android-sdk:11.13.5")
    implementation("com.google.code.gson:gson:2.10.1")

    implementation("com.google.firebase:firebase-ai:16.0.0")

    // Ktor (網路請求)
    implementation("io.ktor:ktor-client-core:2.3.12")
    implementation("io.ktor:ktor-client-okhttp:2.3.12")
    implementation("io.ktor:ktor-client-content-negotiation:2.3.12")
    implementation("io.ktor:ktor-serialization-kotlinx-json:2.3.12")

    // QR Code 生成 + 掃描
    implementation("com.google.zxing:core:3.5.2")
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")

    // 影像與序列化
    implementation("io.coil-kt:coil-compose:2.6.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.play.services.location)
    debugImplementation(libs.androidx.compose.ui.tooling)

    // ✅ Room 本地資料庫 (建議改用 libs. 寫法，或是保持你這版的變數寫法)
    val room_version = "2.7.0"
    implementation("androidx.room:room-runtime:$room_version")
    implementation("androidx.room:room-ktx:$room_version")
    // 🌟 確保這一行使用的是 ksp，這會跟隨上面的插件版本
    ksp("androidx.room:room-compiler:$room_version")

    // Google Sign-In via Credential Manager (A1)
    implementation("androidx.credentials:credentials:1.3.0")
    implementation("androidx.credentials:credentials-play-services-auth:1.3.0")
    implementation("com.google.android.libraries.identity.googleid:googleid:1.1.1")

    // Hilt
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.hilt.navigation.compose)

    // 測試
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
    testImplementation("app.cash.turbine:turbine:1.1.0")
    testImplementation("io.mockk:mockk:1.13.12")
    testImplementation("androidx.arch.core:core-testing:2.2.0")
    // android.jar 裡的 org.json 是空 stub，單元測試呼叫會拋 "not mocked"。
    // 掛真的實作進 test classpath，讓解析 JSON 的邏輯能在 JVM 測（不影響 App 產物）
    testImplementation("org.json:json:20240303")

    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
    androidTestImplementation("androidx.room:room-testing:2.7.0")
    androidTestImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
    // Compose UI 測試（A7 回程班次卡在 Firebase Test Lab 上驗版面用）
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
