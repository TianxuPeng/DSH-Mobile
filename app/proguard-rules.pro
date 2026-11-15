# dshMobile release rules.
# CameraX publishes its own consumer ProGuard rules. ZXing is referenced
# statically, so no reflection-specific keep rules are required here.
-dontwarn com.google.zxing.**

# Methods exposed to the authenticated DSH page through
# webView.addJavascriptInterface(..., "DshMobileBalance").
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
