package com.orca.browser.browser

import android.content.Context
import android.content.MutableContextWrapper
import android.view.View
import android.view.ViewGroup

object WebViewPool {

    private var pooledWebView: OrcaWebView? = null

    fun obtain(context: Context): OrcaWebView {
        val recycled = pooledWebView
        if (recycled != null) {
            pooledWebView = null
            (recycled.parent as? ViewGroup)?.removeView(recycled)
            (recycled.context as? MutableContextWrapper)?.baseContext = context
            recycled.resetForReuse(context)
            return recycled
        }
        return OrcaWebView(MutableContextWrapper(context))
    }

    fun recycle(webView: OrcaWebView) {
        (webView.parent as? ViewGroup)?.removeView(webView)
        if (pooledWebView != null) {
            webView.cleanup()
            return
        }

        webView.apply {
            stopLoading()
            clearHistory()
            clearCache(false)
            setFindListener(null)
            loadUrl("about:blank")
            onPause()
            visibility = View.GONE
        }
        (webView.context as? MutableContextWrapper)?.baseContext =
            webView.context.applicationContext
        pooledWebView = webView
    }

    fun clear() {
        pooledWebView?.cleanup()
        pooledWebView = null
    }
}
