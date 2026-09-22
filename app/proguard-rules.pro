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

-keep class kotlin.Metadata { *; }
-keepattributes RuntimeVisibleAnnotations
-dontwarn kotlin.**
-keepclassmembers class **$WhenMappings {
    <fields>;
}
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}
-dontwarn kotlinx.coroutines.**
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-keepclassmembernames class kotlinx.** {
    volatile <fields>;
}
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt

# Gson 反射所需:保留签名/注解,以及通过 Gson 反序列化的 model 类字段名
-keepattributes Signature
-keepattributes EnclosingMethod
-keep class com.google.gson.reflect.TypeToken { *; }
-keep class * extends com.google.gson.reflect.TypeToken
-keep class com.ikunshare.sound.ui.theme.CustomThemeColors { *; }
-keep class com.ikunshare.sound.common.CustomThemeEntry { *; }

# 以下 model 通过 Gson 反射写入到本地 SQLite / SharedPrefs，
# 一旦字段名被混淆，release 写入的 JSON 在 debug（或下次 schema 变更）就读不出来。
# 保字段名（@SerializedName 缺失时尤为重要）。
-keepclassmembers class com.ikunshare.sound.platform.base.PlayListInfoResult { <fields>; }
-keepclassmembers class com.ikunshare.sound.platform.base.PlayListInfoResult$* { <fields>; }
-keepclassmembers class com.ikunshare.sound.platform.base.MediaInfoResult { <fields>; }
-keepclassmembers class com.ikunshare.sound.platform.base.MediaInfoResult$* { <fields>; }
-keepclassmembers class com.ikunshare.sound.platform.base.MusicListResult { <fields>; }
-keepclassmembers class com.ikunshare.sound.model.UserInfo { <fields>; }
-keepclassmembers class com.ikunshare.sound.model.** { <fields>; }
-keepclassmembers class com.ikunshare.sound.platform.qq.QQCredentials { <fields>; }
-keepclassmembers class com.ikunshare.sound.platform.wy.WyCredentials { <fields>; }
-keepclassmembers class com.ikunshare.sound.platform.kg.KgCredentials { <fields>; }
-keepclassmembers class com.ikunshare.sound.platform.kw.KwCredentials { <fields>; }
-keepclassmembers class com.ikunshare.sound.manager.DownloadManager$PersistedTask { <fields>; }