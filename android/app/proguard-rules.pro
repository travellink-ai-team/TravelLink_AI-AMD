# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# Uncomment this to preserve the line number information for
# debugging stack traces.
#-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile

# Gson 透過反射序列化/反序列化資料類別，避免欄位被改名或移除
-keepattributes Signature,*Annotation*
-keep class com.example.travellink_ai.data.** { *; }
-keep class com.example.travellink_ai.ui.**.model.** { *; }
-keepclassmembers,allowobfuscation class * {
    @com.google.gson.annotations.SerializedName <fields>;
}
-dontwarn com.google.gson.**

# Firestore 以反射讀寫 POJO，保留無參數建構子與欄位
-keepclassmembers class com.example.travellink_ai.** {
    public <init>();
}

# 回顧短片後端的請求／回應（ui/recap/RecapModels.kt）：Gson 以欄位名稱對應 JSON，
# 不在上面 data.** 的範圍內，混淆後欄位改名，送出的 JSON 與解析結果都會錯
-keep class com.example.travellink_ai.ui.recap.RecapStop { *; }
-keep class com.example.travellink_ai.ui.recap.RecapTrip { *; }
-keep class com.example.travellink_ai.ui.recap.RecapPhoto { *; }
-keep class com.example.travellink_ai.ui.recap.RecapRenderRequest { *; }
-keep class com.example.travellink_ai.ui.recap.RecapJobCreated { *; }
-keep class com.example.travellink_ai.ui.recap.RecapJobStatus { *; }
-keep class com.example.travellink_ai.ui.recap.CloudRecap { *; }
