package com.orca.browser.browser

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.os.Build
import android.util.AttributeSet
import android.view.View
import android.webkit.CookieManager
import android.webkit.WebSettings
import android.webkit.WebView
import androidx.webkit.ScriptHandler
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.orca.browser.data.model.BrowsingMode
import com.orca.browser.data.prefs.PrefsManager
import com.orca.browser.tor.TorWebViewConfig

class OrcaWebView(
    context: Context,
    attrs: AttributeSet? = null
) : WebView(context, attrs) {

    var browsingMode: BrowsingMode = BrowsingMode.NORMAL
        private set

    private var currentMode: BrowsingMode = BrowsingMode.NORMAL
    private var desktopModeEnabled: Boolean = false
    private var webRtcScriptHandler: ScriptHandler? = null

    init {
        val prefs = PrefsManager.getInstance(context)
        desktopModeEnabled = prefs.isDesktopMode

        applyBaseSettings(prefs)
        setBackgroundColor(Color.BLACK)
        scrollBarStyle = SCROLLBARS_INSIDE_OVERLAY
    }

    @SuppressLint("SetJavaScriptEnabled")
    @Suppress("DEPRECATION")
    private fun applyBaseSettings(prefs: PrefsManager) {
        settings.apply {
            javaScriptEnabled = prefs.isJavaScriptEnabled
            domStorageEnabled = true
            databaseEnabled = true
            setSupportMultipleWindows(true)
            loadWithOverviewMode = true
            useWideViewPort = true
            builtInZoomControls = true
            displayZoomControls = false
            mediaPlaybackRequiresUserGesture = true
            allowFileAccess = false
            allowContentAccess = false
            allowFileAccessFromFileURLs = false
            allowUniversalAccessFromFileURLs = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            cacheMode = WebSettings.LOAD_DEFAULT
            userAgentString = if (desktopModeEnabled) {
                DESKTOP_USER_AGENT
            } else {
                MOBILE_USER_AGENT
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                try {
                    safeBrowsingEnabled = true
                } catch (_: Throwable) {
                    // Safe Browsing provider unavailable
                }
            }
        }
    }

    @Suppress("DEPRECATION")
    fun configureForMode(mode: BrowsingMode) {
        currentMode = mode
        browsingMode = mode
        val cookieManager = CookieManager.getInstance()

        when (mode) {
            BrowsingMode.NORMAL -> {
                removeWebRtcBlocker()
                settings.cacheMode = WebSettings.LOAD_DEFAULT
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
                    settings.saveFormData = true
                }
                cookieManager.setAcceptCookie(true)
                cookieManager.setAcceptThirdPartyCookies(this, true)
                settings.userAgentString = if (desktopModeEnabled) {
                    DESKTOP_USER_AGENT
                } else {
                    MOBILE_USER_AGENT
                }
            }

            BrowsingMode.INCOGNITO -> {
                removeWebRtcBlocker()
                IncognitoManager.configureWebViewForIncognito(this)
                settings.userAgentString = if (desktopModeEnabled) {
                    DESKTOP_USER_AGENT
                } else {
                    MOBILE_USER_AGENT
                }
            }

            BrowsingMode.TOR -> {
                TorWebViewConfig.applyTorConfig(this)
                installWebRtcBlocker()
            }
        }
    }

    fun applyDesktopMode(enabled: Boolean) {
        desktopModeEnabled = enabled
        if (currentMode != BrowsingMode.TOR) {
            settings.userAgentString = if (enabled) {
                DESKTOP_USER_AGENT
            } else {
                MOBILE_USER_AGENT
            }
        }
        settings.useWideViewPort = enabled
        settings.loadWithOverviewMode = enabled
        reload()
    }

    fun updateJavaScript(enabled: Boolean) {
        settings.javaScriptEnabled = enabled
        reload()
    }

    fun hibernate() {
        onPause()
        loadUrl("about:blank")
        visibility = View.GONE
    }

    fun wakeUp(url: String) {
        visibility = View.VISIBLE
        onResume()
        resumeTimers()
        loadUrl(url)
    }

    fun resetForReuse(context: Context) {
        removeWebRtcBlocker()
        val prefs = PrefsManager.getInstance(context)
        currentMode = BrowsingMode.NORMAL
        browsingMode = BrowsingMode.NORMAL
        desktopModeEnabled = prefs.isDesktopMode
        applyBaseSettings(prefs)
        visibility = View.VISIBLE
        onResume()
        resumeTimers()
    }

    fun cleanup() {
        removeWebRtcBlocker()
        stopLoading()
        clearHistory()
        clearCache(true)
        removeAllViews()
        destroy()
    }

    private fun installWebRtcBlocker() {
        if (webRtcScriptHandler != null) return
        try {
            if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
                webRtcScriptHandler = WebViewCompat.addDocumentStartJavaScript(
                    this,
                    WEBRTC_BLOCK_SCRIPT,
                    setOf("*")
                )
            }
        } catch (_: Throwable) {
            // Document start script injection unsupported on this WebView version
        }
    }

    private fun removeWebRtcBlocker() {
        webRtcScriptHandler?.remove()
        webRtcScriptHandler = null
    }

    companion object {
        private val MOBILE_USER_AGENT =
            "Mozilla/5.0 (Linux; Android ${Build.VERSION.RELEASE}; K) " +
                "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"

        private const val DESKTOP_USER_AGENT =
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

        private val WEBRTC_BLOCK_SCRIPT = """
            (function() {
                const blockedError = function() {
                    return Promise.reject(new DOMException('WebRTC is disabled in Tor mode', 'NotAllowedError'));
                };
                try {
                    Object.defineProperty(window, 'RTCPeerConnection', { value: undefined, configurable: false });
                    Object.defineProperty(window, 'webkitRTCPeerConnection', { value: undefined, configurable: false });
                    Object.defineProperty(window, 'mozRTCPeerConnection', { value: undefined, configurable: false });
                    Object.defineProperty(window, 'RTCDataChannel', { value: undefined, configurable: false });
                    Object.defineProperty(window, 'RTCSessionDescription', { value: undefined, configurable: false });
                    Object.defineProperty(window, 'RTCIceCandidate', { value: undefined, configurable: false });
                } catch (_) {}
                if (navigator.mediaDevices) {
                    try {
                        Object.defineProperty(navigator.mediaDevices, 'getUserMedia', { value: blockedError, configurable: false });
                        Object.defineProperty(navigator.mediaDevices, 'enumerateDevices', { value: function() { return Promise.resolve([]); }, configurable: false });
                    } catch (_) {}
                }
                try {
                    Object.defineProperty(navigator, 'getUserMedia', { value: undefined, configurable: false });
                    Object.defineProperty(navigator, 'webkitGetUserMedia', { value: undefined, configurable: false });
                    Object.defineProperty(navigator, 'mozGetUserMedia', { value: undefined, configurable: false });
                } catch (_) {}
            })();
        """.trimIndent()
    }
}
