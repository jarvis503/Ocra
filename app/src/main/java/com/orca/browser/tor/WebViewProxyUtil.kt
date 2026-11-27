package com.orca.browser.tor

import android.os.Build
import android.webkit.WebView
import androidx.webkit.ProxyConfig
import androidx.webkit.ProxyController
import androidx.webkit.WebViewFeature
import java.util.concurrent.Executor

object WebViewProxyUtil {

    private val directExecutor = Executor { runnable -> runnable.run() }

    @JvmOverloads
    fun applyProxy(webView: WebView? = null, host: String, port: Int) {
        if (port <= 0) return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
            WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)
        ) {
            try {
                val proxyConfig = ProxyConfig.Builder()
                    .addProxyRule("socks5://$host:$port")
                    .build()
                ProxyController.getInstance().setProxyOverride(
                    proxyConfig,
                    directExecutor,
                    Runnable { }
                )
                return
            } catch (_: Throwable) {
                // Fallback to JVM SOCKS properties if ProxyController rejects override
            }
        }
        System.setProperty("socksProxyHost", host)
        System.setProperty("socksProxyPort", port.toString())
        System.setProperty("socksProxyVersion", "5")
    }

    fun applyProxy(host: String, port: Int) {
        applyProxy(null, host, port)
    }

    @JvmOverloads
    fun clearProxy(webView: WebView? = null) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
            WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)
        ) {
            try {
                ProxyController.getInstance().clearProxyOverride(
                    directExecutor,
                    Runnable { }
                )
            } catch (_: Exception) {
                // ProxyController unavailable
            }
        }
        System.clearProperty("socksProxyHost")
        System.clearProperty("socksProxyPort")
        System.clearProperty("socksProxyVersion")
    }
}
