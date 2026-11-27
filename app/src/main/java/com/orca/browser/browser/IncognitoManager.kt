package com.orca.browser.browser

import android.os.Build
import android.webkit.CookieManager
import android.webkit.WebSettings
import android.webkit.WebStorage

object IncognitoManager {

    @Suppress("DEPRECATION")
    fun configureWebViewForIncognito(webView: OrcaWebView) {
        webView.settings.cacheMode = WebSettings.LOAD_NO_CACHE
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            webView.settings.saveFormData = false
        }
        webView.settings.savePassword = false

        val cookieManager = CookieManager.getInstance()
        cookieManager.setAcceptCookie(true)
        cookieManager.setAcceptThirdPartyCookies(webView, false)
    }

    fun clearIncognitoData() {
        try {
            val cookieManager = CookieManager.getInstance()
            cookieManager.removeAllCookies(null)
            cookieManager.flush()
        } catch (_: Exception) {
            // WebView CookieManager unavailable
        }

        try {
            WebStorage.getInstance().deleteAllData()
        } catch (_: Exception) {
            // WebStorage unavailable
        }
    }
}
