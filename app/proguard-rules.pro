# Add project specific ProGuard rules here.
-keep class com.lunyx.naurokhelper.** { *; }
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
