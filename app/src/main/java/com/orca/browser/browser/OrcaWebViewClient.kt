package com.orca.browser.browser

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.net.http.SslError
import android.os.Build
import android.webkit.CookieManager
import android.webkit.RenderProcessGoneDetail
import android.webkit.SslErrorHandler
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AlertDialog
import com.orca.browser.adblocker.AdBlocker
import com.orca.browser.data.model.BrowsingMode
import com.orca.browser.tor.TorWebViewConfig
import java.io.ByteArrayInputStream

class OrcaWebViewClient(
    private val tabId: String,
    private val listener: PageEventListener
) : WebViewClient() {

    var shouldInterceptRequests: ((String) -> WebResourceResponse?)? = null

    @SuppressLint("SetJavaScriptEnabled")
    override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
        super.onPageStarted(view, url, favicon)
        val safeUrl = url ?: return

        val orcaWebView = view as? OrcaWebView
        if (orcaWebView?.browsingMode == BrowsingMode.TOR) {
            TorWebViewConfig.injectWebRtcBlock(view)
        } else if (view != null && AuthDomainWhitelist.isAuthDomain(safeUrl)) {
            view.settings.javaScriptEnabled = true
            val cookieManager = CookieManager.getInstance()
            cookieManager.setAcceptCookie(true)
            cookieManager.setAcceptThirdPartyCookies(view, true)
        }

        injectAdBlockScripts(view, safeUrl)

        listener.onPageStarted(tabId, safeUrl)
        listener.onNavigationStateChanged(
            tabId = tabId,
            canGoBack = view?.canGoBack() == true,
            canGoForward = view?.canGoForward() == true
        )
    }

    override fun onPageFinished(view: WebView?, url: String?) {
        super.onPageFinished(view, url)
        val safeUrl = url ?: "about:blank"
        injectAdBlockScripts(view, safeUrl)

        val title = view?.title?.takeIf { it.isNotBlank() } ?: safeUrl
        listener.onPageFinished(tabId, safeUrl, title)
        listener.onNavigationStateChanged(
            tabId = tabId,
            canGoBack = view?.canGoBack() == true,
            canGoForward = view?.canGoForward() == true
        )
    }

    override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
        val uri = request?.url ?: return false
        val url = uri.toString()
        val scheme = uri.scheme?.lowercase().orEmpty()

        if (AuthDomainWhitelist.isAuthDomain(url)) {
            val orcaWebView = view as? OrcaWebView
            if (orcaWebView?.browsingMode != BrowsingMode.TOR && view != null) {
                val cookieManager = CookieManager.getInstance()
                cookieManager.setAcceptCookie(true)
                cookieManager.setAcceptThirdPartyCookies(view, true)
            }
            return false
        }

        return when (scheme) {
            "http", "https", "about" -> false
            "intent" -> handleIntentScheme(view, url)
            "market" -> handleMarketScheme(view, uri)
            "tel", "mailto", "sms", "geo" -> handleExternalScheme(view?.context, uri)
            else -> true
        }
    }

    override fun shouldInterceptRequest(
        view: WebView?,
        request: WebResourceRequest?
    ): WebResourceResponse? {
        val url = request?.url?.toString()
        if (url != null) {
            try {
                shouldInterceptRequests?.invoke(url)?.let { return it }
                val isMainFrame = request?.isForMainFrame == true
                if (AdBlocker.shouldBlockResource(url, isMainFrame)) {
                    AdBlocker.incrementBlockCount(tabId)
                    return WebResourceResponse("text/plain", "UTF-8", ByteArrayInputStream(ByteArray(0)))
                }
            } catch (_: Throwable) {
                // Keep resource interception safe on Chromium IO thread
            }
        }
        return super.shouldInterceptRequest(view, request)
    }

    @RequiresApi(Build.VERSION_CODES.O)
    override fun onRenderProcessGone(
        view: WebView?,
        detail: RenderProcessGoneDetail?
    ): Boolean {
        listener.onRenderProcessGone(tabId)
        return true
    }

    override fun onReceivedSslError(
        view: WebView?,
        handler: SslErrorHandler?,
        error: SslError?
    ) {
        if (handler == null) return
        val activity = unwrapActivity(view?.context)
        if (activity == null || activity.isFinishing || activity.isDestroyed) {
            handler.cancel()
            return
        }

        val errorDescription = when (error?.primaryError) {
            SslError.SSL_EXPIRED -> "The site's security certificate has expired."
            SslError.SSL_IDMISMATCH -> "The certificate hostname does not match this site."
            SslError.SSL_NOTYETVALID -> "The site's security certificate is not yet valid."
            SslError.SSL_UNTRUSTED -> "The certificate authority is not trusted."
            SslError.SSL_DATE_INVALID -> "The certificate date is invalid."
            else -> "An unknown SSL certificate error occurred."
        }
        val failingUrl = error?.url ?: "this website"

        var resolved = false
        val dialog = AlertDialog.Builder(activity)
            .setTitle("Security Warning")
            .setMessage(
                "Connection to $failingUrl is not secure.\n\n$errorDescription\n\n" +
                    "Attackers might be able to see or modify information you send."
            )
            .setCancelable(true)
            .setNegativeButton("Go Back") { _, _ ->
                if (!resolved) {
                    resolved = true
                    handler.cancel()
                }
            }
            .setPositiveButton("Proceed Anyway") { _, _ ->
                if (!resolved) {
                    resolved = true
                    handler.proceed()
                }
            }
            .setOnCancelListener {
                if (!resolved) {
                    resolved = true
                    handler.cancel()
                }
            }
            .create()

        dialog.show()
    }

    override fun onReceivedError(
        view: WebView?,
        request: WebResourceRequest?,
        error: WebResourceError?
    ) {
        super.onReceivedError(view, request, error)
        if (view == null || request?.isForMainFrame != true) return

        val failingUrl = request.url?.toString() ?: return
        if (failingUrl == "about:blank") return

        val errorCode = error?.errorCode ?: ERROR_UNKNOWN
        if (errorCode == ERROR_UNSUPPORTED_SCHEME) return

        val description = error?.description?.toString()?.takeIf { it.isNotBlank() }
            ?: "The webpage could not be loaded."
        val escapedUrl = escapeHtml(failingUrl)
        val escapedDesc = escapeHtml(description)

        val html = """
            <!DOCTYPE html>
            <html lang="en">
            <head>
                <meta charset="UTF-8" />
                <meta name="viewport" content="width=device-width, initial-scale=1.0" />
                <title>Page Load Error</title>
                <style>
                    * { box-sizing: border-box; margin: 0; padding: 0; }
                    body {
                        background-color: #000000;
                        color: #FFFFFF;
                        font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif;
                        display: flex;
                        align-items: center;
                        justify-content: center;
                        min-height: 100vh;
                        padding: 24px;
                    }
                    .card {
                        background-color: #1A1A1A;
                        border: 1px solid #2C2C2E;
                        border-radius: 16px;
                        padding: 24px;
                        max-width: 440px;
                        width: 100%;
                    }
                    .badge {
                        display: inline-block;
                        color: #FF3B30;
                        font-size: 13px;
                        font-weight: 600;
                        letter-spacing: 0.4px;
                        margin-bottom: 10px;
                    }
                    h1 {
                        font-size: 20px;
                        font-weight: 600;
                        margin-bottom: 10px;
                        color: #FFFFFF;
                    }
                    p {
                        font-size: 14px;
                        line-height: 1.5;
                        color: #8E8E93;
                        word-break: break-word;
                        margin-bottom: 12px;
                    }
                    .url {
                        font-family: monospace;
                        font-size: 12px;
                        color: #8E8E93;
                        background-color: #0A0A0A;
                        border: 1px solid #2C2C2E;
                        border-radius: 8px;
                        padding: 10px 12px;
                        margin-bottom: 20px;
                        word-break: break-all;
                    }
                    .btn {
                        display: inline-block;
                        background-color: #007AFF;
                        color: #FFFFFF;
                        text-decoration: none;
                        font-size: 15px;
                        font-weight: 600;
                        padding: 10px 20px;
                        border-radius: 20px;
                    }
                </style>
            </head>
            <body>
                <div class="card">
                    <div class="badge">CONNECTION ERROR ($errorCode)</div>
                    <h1>Unable to load page</h1>
                    <p>$escapedDesc</p>
                    <div class="url">$escapedUrl</div>
                    <a class="btn" href="$escapedUrl">Try Again</a>
                </div>
            </body>
            </html>
        """.trimIndent()

        view.loadDataWithBaseURL(failingUrl, html, "text/html", "UTF-8", failingUrl)
    }

    private fun handleIntentScheme(view: WebView?, url: String): Boolean {
        val context = view?.context ?: return true
        return try {
            val intent = Intent.parseUri(url, Intent.URI_INTENT_SCHEME).apply {
                addCategory(Intent.CATEGORY_BROWSABLE)
                component = null
                selector = null
            }
            if (intent.resolveActivity(context.packageManager) != null) {
                launchActivitySafely(context, intent)
            } else {
                val fallbackUrl = intent.getStringExtra("browser_fallback_url")
                if (!fallbackUrl.isNullOrBlank() &&
                    (fallbackUrl.startsWith("https://") || fallbackUrl.startsWith("http://"))
                ) {
                    view.loadUrl(fallbackUrl)
                }
            }
            true
        } catch (_: Exception) {
            true
        }
    }

    private fun handleMarketScheme(view: WebView?, uri: Uri): Boolean {
        val context = view?.context ?: return true
        val marketIntent = Intent(Intent.ACTION_VIEW, uri).apply {
            addCategory(Intent.CATEGORY_BROWSABLE)
        }
        if (marketIntent.resolveActivity(context.packageManager) != null) {
            launchActivitySafely(context, marketIntent)
            return true
        }

        val queryPart = uri.encodedQuery?.let { "?$it" } ?: ""
        val hostPart = uri.host ?: "details"
        val playStoreUrl = "https://play.google.com/store/apps/$hostPart$queryPart"
        view.loadUrl(playStoreUrl)
        return true
    }

    private fun handleExternalScheme(context: Context?, uri: Uri): Boolean {
        if (context == null) return true
        val intent = Intent(Intent.ACTION_VIEW, uri).apply {
            addCategory(Intent.CATEGORY_BROWSABLE)
        }
        launchActivitySafely(context, intent)
        return true
    }

    private fun launchActivitySafely(context: Context, intent: Intent) {
        try {
            if (context !is Activity) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            // No installed application can handle this scheme
        } catch (_: SecurityException) {
            // Target activity is not exported or requires missing permission
        }
    }

    private fun injectAdBlockScripts(view: WebView?, url: String) {
        if (view == null || url.isBlank() || url == "about:blank") return
        val lowerUrl = url.lowercase()
        if (!lowerUrl.startsWith("http://") && !lowerUrl.startsWith("https://")) return
        if (AuthDomainWhitelist.isAuthDomain(url)) return

        try {
            val context = view.context
            view.evaluateJavascript(AdBlocker.getAdBlockScript(context), null)
            if (lowerUrl.contains("youtube.com") || lowerUrl.contains("youtube-nocookie.com")) {
                view.evaluateJavascript(AdBlocker.getYouTubeAdBlockScript(context), null)
            }
        } catch (_: Throwable) {
            // WebView destroyed or JS evaluation unavailable
        }
    }

    private fun unwrapActivity(context: Context?): Activity? {
        var current = context
        while (current is ContextWrapper) {
            if (current is Activity) return current
            current = current.baseContext
        }
        return current as? Activity
    }

    private fun escapeHtml(text: String): String {
        return text
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&#39;")
    }
}
