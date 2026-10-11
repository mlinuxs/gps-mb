# 保留 JS Bridge 类（WebView 通过 JavascriptInterface 反射调用）
-keep class com.ridelog.app.JsBridge { *; }
-keepclassmembers class com.ridelog.app.JsBridge {
    @android.webkit.JavascriptInterface <methods>;
}
