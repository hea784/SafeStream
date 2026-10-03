# Media3 / OkHttp / WebView 默认规则已足够，本项目无反射入口。
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
-dontwarn org.slf4j.**
