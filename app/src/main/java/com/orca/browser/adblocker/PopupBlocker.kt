package com.orca.browser.adblocker

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast

data class PopupRecord(
    val sourceTabId: String,
    val timestamp: Long
)

object PopupBlocker {

    private const val SPAM_WINDOW_MS = 5000L
    private const val CLEANUP_AGE_MS = 10000L
    private const val MAX_POPUPS_IN_WINDOW = 3

    private val recentPopups = mutableListOf<PopupRecord>()
    private val mainHandler = Handler(Looper.getMainLooper())

    fun shouldAllowPopup(
        sourceTabId: String,
        targetUrl: String,
        context: Context? = null
    ): Boolean {
        if (AdBlocker.shouldBlockResource(targetUrl)) {
            return false
        }

        val now = System.currentTimeMillis()
        val recentCount: Int
        synchronized(recentPopups) {
            pruneOldRecordsLocked(now)
            recentCount = recentPopups.count {
                it.sourceTabId == sourceTabId && (now - it.timestamp) <= SPAM_WINDOW_MS
            }
        }

        if (recentCount >= MAX_POPUPS_IN_WINDOW) {
            showSpamToast(context)
            return false
        }

        return true
    }

    fun recordPopup(sourceTabId: String) {
        val now = System.currentTimeMillis()
        synchronized(recentPopups) {
            recentPopups.add(PopupRecord(sourceTabId = sourceTabId, timestamp = now))
            pruneOldRecordsLocked(now)
        }
    }

    private fun pruneOldRecordsLocked(now: Long) {
        val iterator = recentPopups.iterator()
        while (iterator.hasNext()) {
            if (now - iterator.next().timestamp > CLEANUP_AGE_MS) {
                iterator.remove()
            }
        }
    }

    private fun showSpamToast(context: Context?) {
        val appContext = context?.applicationContext ?: return
        if (Looper.myLooper() == Looper.getMainLooper()) {
            Toast.makeText(appContext, "Popup spam blocked", Toast.LENGTH_SHORT).show()
        } else {
            mainHandler.post {
                Toast.makeText(appContext, "Popup spam blocked", Toast.LENGTH_SHORT).show()
            }
        }
    }
}
