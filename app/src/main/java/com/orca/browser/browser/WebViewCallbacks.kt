package com.orca.browser.browser

import android.graphics.Bitmap

interface PageEventListener {
    fun onPageStarted(tabId: String, url: String)
    fun onPageFinished(tabId: String, url: String, title: String)
    fun onProgressChanged(tabId: String, progress: Int)
    fun onTitleChanged(tabId: String, title: String)
    fun onFaviconReceived(tabId: String, favicon: Bitmap?)
    fun onNewTabRequested(url: String, sourceTabId: String)
    fun onRedirectIntercepted(targetUrl: String, sourceDomain: String)
    fun onTabCloseRequested(tabId: String)
    fun onNavigationStateChanged(tabId: String, canGoBack: Boolean, canGoForward: Boolean)
    fun onRenderProcessGone(tabId: String)
}
