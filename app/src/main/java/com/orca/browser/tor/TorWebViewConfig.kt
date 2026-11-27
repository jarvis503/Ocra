package com.orca.browser.tor

import android.os.Build
import android.webkit.CookieManager
import android.webkit.WebSettings
import android.webkit.WebStorage
import android.webkit.WebView
import com.orca.browser.browser.OrcaWebView

object TorWebViewConfig {

    const val TOR_USER_AGENT =
        "Mozilla/5.0 (Android 10; Mobile) Gecko/109.0 Firefox/109.0"

    private const val WEBRTC_DISABLE_JS = """
        (function() {
            try { navigator.mediaDevices = undefined; } catch (_) {}
            try { window.RTCPeerConnection = undefined; } catch (_) {}
            try { window.mozRTCPeerConnection = undefined; } catch (_) {}
            try { window.webkitRTCPeerConnection = undefined; } catch (_) {}
        })();
    """

    @Suppress("DEPRECATION")
    fun applyTorConfig(webView: OrcaWebView) {
        webView.settings.apply {
            cacheMode = WebSettings.LOAD_NO_CACHE
            userAgentString = TOR_USER_AGENT
            setGeolocationEnabled(false)
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
                saveFormData = false
            }
            savePassword = false
        }

        val cookieManager = CookieManager.getInstance()
        cookieManager.setAcceptCookie(false)
        cookieManager.setAcceptThirdPartyCookies(webView, false)
    }

    fun injectWebRtcBlock(webView: WebView?) {
        webView?.evaluateJavascript(WEBRTC_DISABLE_JS, null)
    }

    fun clearTorData() {
        try {
            val cookieManager = CookieManager.getInstance()
            cookieManager.removeAllCookies(null)
            cookieManager.flush()
        } catch (_: Exception) {
            // CookieManager unavailable
        }

        try {
            WebStorage.getInstance().deleteAllData()
        } catch (_: Exception) {
            // WebStorage unavailable
        }

        WebViewProxyUtil.clearProxy(null)
    }
}
