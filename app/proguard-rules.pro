# Room
-keep class com.orca.browser.data.model.** { *; }
-keep class com.orca.browser.data.db.** { *; }
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class *
-dontwarn androidx.room.paging.**

# ViewBinding
-keepclassmembers class * implements androidx.viewbinding.ViewBinding {
    public static ** bind(android.view.View);
    public static ** inflate(android.view.LayoutInflater);
}

# WebView & AndroidX WebKit
-keepclassmembers class * extends android.webkit.WebViewClient {
    public void *(android.webkit.WebView, java.lang.String);
    public boolean *(android.webkit.WebView, java.lang.String);
    public boolean *(android.webkit.WebView, android.webkit.WebResourceRequest);
}
-keepclassmembers class * extends android.webkit.WebChromeClient {
    public void *(android.webkit.WebView, int);
    public boolean *(android.webkit.WebView, java.lang.String);
    public boolean *(android.webkit.WebView, boolean, boolean, android.os.Message);
}
-keep class androidx.webkit.** { *; }

# Keep enums
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# Coroutines
-dontwarn kotlinx.coroutines.**

# Embedded Tor (tor-android, jtorctl, IPtProxy)
-keep class org.torproject.jni.** { *; }
-keep class net.freehaven.tor.control.** { *; }
-keep class IPtProxy.** { *; }
-keep class go.** { *; }
-keep class com.orca.browser.tor.** { *; }

# General R8 options
-dontusemixedcaseclassnames
