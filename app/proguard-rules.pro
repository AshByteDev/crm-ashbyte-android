# kotlinx.serialization keeps its generated serializers through its own rules; WebView bridges need their methods.
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

# Names of exceptions stay readable: the app shows them when something unexpected happens (AppModel.setPortal).
-keepnames class * extends java.lang.Throwable
