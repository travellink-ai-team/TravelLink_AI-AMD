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