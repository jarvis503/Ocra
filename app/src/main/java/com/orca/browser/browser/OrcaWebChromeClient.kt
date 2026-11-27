package com.orca.browser.browser

import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Message
import android.view.View
import android.webkit.GeolocationPermissions
import android.webkit.PermissionRequest
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import java.io.ByteArrayOutputStream
import kotlin.math.min

class OrcaWebChromeClient(
    private val tabId: String,
    private val listener: PageEventListener
) : WebChromeClient() {

    private var pendingFilePathCallback: ValueCallback<Array<Uri>>? = null

    var fileChooserHandler: ((ValueCallback<Array<Uri>>, FileChooserParams?) -> Boolean)? = null
    var onFaviconBytesReceived: ((String, ByteArray) -> Unit)? = null

    override fun onProgressChanged(view: WebView?, newProgress: Int) {
        super.onProgressChanged(view, newProgress)
        listener.onProgressChanged(tabId, newProgress)
    }

    override fun onReceivedTitle(view: WebView?, title: String?) {
        super.onReceivedTitle(view, title)
        if (!title.isNullOrBlank()) {
            listener.onTitleChanged(tabId, title)
        }
    }

    override fun onReceivedIcon(view: WebView?, icon: Bitmap?) {
        super.onReceivedIcon(view, icon)
        if (icon == null || icon.isRecycled) {
            listener.onFaviconReceived(tabId, null)
            return
        }

        try {
            val scaledBitmap = scaleBitmapToMaxSize(icon, MAX_FAVICON_SIZE_PX)
            val compressedBytes = compressBitmapToWebp(scaledBitmap)
            onFaviconBytesReceived?.invoke(tabId, compressedBytes)
            listener.onFaviconReceived(tabId, scaledBitmap)
        } catch (_: Throwable) {
            listener.onFaviconReceived(tabId, null)
        }
    }

    override fun onCreateWindow(
        view: WebView?,
        isDialog: Boolean,
        isUserGesture: Boolean,
        resultMsg: Message?
    ): Boolean {
        if (view == null || resultMsg == null) return false
        val transport = resultMsg.obj as? WebView.WebViewTransport ?: return false

        val sourceDomain = try {
            Uri.parse(view.url.orEmpty()).host?.takeIf { it.isNotBlank() } ?: "unknown"
        } catch (_: Exception) {
            "unknown"
        }

        val tempWebView = WebView(view.context).apply {
            visibility = View.GONE
            settings.javaScriptEnabled = false
        }

        var handled = false
        tempWebView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                tempView: WebView?,
                request: WebResourceRequest?
            ): Boolean {
                if (!handled) {
                    handled = true
                    handleCapturedWindowUrl(view, request?.url?.toString(), sourceDomain)
                    disposeTempWebView(tempView)
                }
                return true
            }

            override fun onPageStarted(tempView: WebView?, url: String?, favicon: Bitmap?) {
                if (!handled && !url.isNullOrBlank() && url != "about:blank") {
                    handled = true
                    handleCapturedWindowUrl(view, url, sourceDomain)
                    disposeTempWebView(tempView)
                }
            }
        }

        transport.webView = tempWebView
        resultMsg.sendToTarget()
        return true
    }

    override fun onCloseWindow(window: WebView?) {
        super.onCloseWindow(window)
        if (RedirectDetector.isRedirectTab(tabId)) {
            listener.onTabCloseRequested(tabId)
        }
    }

    override fun onShowFileChooser(
        webView: WebView?,
        filePathCallback: ValueCallback<Array<Uri>>?,
        fileChooserParams: FileChooserParams?
    ): Boolean {
        cancelPendingFileChooser()
        if (filePathCallback == null) return false

        pendingFilePathCallback = filePathCallback
        val handler = fileChooserHandler
        if (handler != null) {
            val accepted = handler.invoke(filePathCallback, fileChooserParams)
            if (!accepted) {
                cancelPendingFileChooser()
            }
        } else {
            cancelPendingFileChooser()
        }
        return true
    }

    override fun onGeolocationPermissionsShowPrompt(
        origin: String?,
        callback: GeolocationPermissions.Callback?
    ) {
        callback?.invoke(origin, false, false)
    }

    override fun onPermissionRequest(request: PermissionRequest?) {
        request?.deny()
    }

    fun onFileChooserResult(uris: Array<Uri>?) {
        pendingFilePathCallback?.onReceiveValue(uris)
        pendingFilePathCallback = null
    }

    fun cancelPendingFileChooser() {
        pendingFilePathCallback?.onReceiveValue(null)
        pendingFilePathCallback = null
    }

    private fun handleCapturedWindowUrl(
        parentWebView: WebView,
        targetUrl: String?,
        sourceDomain: String
    ) {
        if (targetUrl.isNullOrBlank() || targetUrl == "about:blank") return

        // EXCEPTION: Auth domains — allow silently in the same tab, no popup
        if (AuthDomainWhitelist.isAuthDomain(targetUrl)) {
            parentWebView.post {
                parentWebView.loadUrl(targetUrl)
            }
            return
        }

        listener.onRedirectIntercepted(targetUrl, sourceDomain)
    }

    private fun disposeTempWebView(tempView: WebView?) {
        if (tempView == null) return
        tempView.stopLoading()
        tempView.post {
            tempView.removeAllViews()
            tempView.destroy()
        }
    }

    companion object {
        private const val MAX_FAVICON_SIZE_PX = 64
        private const val FAVICON_WEBP_QUALITY = 50

        fun scaleBitmapToMaxSize(source: Bitmap, maxDimension: Int): Bitmap {
            val width = source.width
            val height = source.height
            if (width <= 0 || height <= 0) return source
            if (width <= maxDimension && height <= maxDimension) {
                return source.copy(Bitmap.Config.ARGB_8888, false) ?: source
            }
            val scale = min(
                maxDimension.toFloat() / width.toFloat(),
                maxDimension.toFloat() / height.toFloat()
            )
            val targetWidth = maxOf(1, (width * scale).toInt())
            val targetHeight = maxOf(1, (height * scale).toInt())
            return Bitmap.createScaledBitmap(source, targetWidth, targetHeight, true)
        }

        fun compressBitmapToWebp(bitmap: Bitmap): ByteArray {
            val format = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                Bitmap.CompressFormat.WEBP_LOSSY
            } else {
                @Suppress("DEPRECATION")
                Bitmap.CompressFormat.WEBP
            }
            val output = ByteArrayOutputStream()
            bitmap.compress(format, FAVICON_WEBP_QUALITY, output)
            return output.toByteArray()
        }
    }
}
