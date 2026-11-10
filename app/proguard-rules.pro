# kotlinx.serialization keeps its generated serializers through its own rules; WebView bridges need their methods.
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
