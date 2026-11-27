package com.orca.browser.browser

import java.util.concurrent.ConcurrentHashMap

data class RedirectInfo(
    val sourceTabId: String,
    val sourceDomain: String,
    val timestamp: Long
)

object RedirectDetector {

    private val redirectMap = ConcurrentHashMap<String, RedirectInfo>()

    fun markAsRedirect(newTabId: String, sourceTabId: String, sourceDomain: String) {
        redirectMap[newTabId] = RedirectInfo(
            sourceTabId = sourceTabId,
            sourceDomain = sourceDomain,
            timestamp = System.currentTimeMillis()
        )
    }

    fun isRedirectTab(tabId: String): Boolean {
        return redirectMap.containsKey(tabId)
    }

    fun getSourceInfo(tabId: String): RedirectInfo? {
        return redirectMap[tabId]
    }

    fun removeTab(tabId: String) {
        redirectMap.remove(tabId)
    }

    fun getRecentRedirectCount(sourceTabId: String, withinMs: Long = 5000L): Int {
        val cutoff = System.currentTimeMillis() - withinMs
        var count = 0
        for (info in redirectMap.values) {
            if (info.sourceTabId == sourceTabId && info.timestamp >= cutoff) {
                count++
            }
        }
        return count
    }
}
