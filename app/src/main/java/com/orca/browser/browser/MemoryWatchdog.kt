package com.orca.browser.browser

import android.content.ComponentCallbacks2
import android.content.res.Configuration
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

// Lenovo Tab M10 HD (TB-X306X) Memory Budget:
// Total RAM: 2GB (1.8GB usable)
// System baseline: ~1.5GB
// Available for Orca: ~300MB
//
// WebView instance: ~30-80MB each depending on page
// LRU cache limit: 3 WebViews = max ~240MB
// Room DB + app overhead: ~20MB
// Ad blocker HashSet (3K domains): ~1MB
// Safety margin: ~40MB
//
// Emergency actions at RUNNING_LOW: drop to 1 WebView
// Emergency actions at CRITICAL: drop to 0 (hibernate active tab too)
object MemoryWatchdog : ComponentCallbacks2 {

    private const val TAG = "MemoryWatchdog"
    private val watchdogScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onTrimMemory(level: Int) {
        val tabManager = TabManager.getInstance()
        when (level) {
            ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW,
            ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL -> {
                Log.w(TAG, "⚠️ Memory pressure detected, level=$level")
                tabManager.emergencyFreeMemory()
                tabManager.getActiveTab()?.let { activeTab ->
                    tabManager.getWebViewForTab(activeTab.id)?.clearCache(false)
                }
                if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL) {
                    WebViewPool.clear()
                }
                System.gc()
            }

            ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN -> {
                Log.i(TAG, "App UI hidden, hibernating all tabs and persisting session")
                tabManager.hibernateAllTabs()
                tabManager.persistSession(watchdogScope)
            }

            ComponentCallbacks2.TRIM_MEMORY_COMPLETE -> {
                Log.w(TAG, "⚠️ Maximum memory pressure (TRIM_MEMORY_COMPLETE)")
                tabManager.getActiveTab()?.let { activeTab ->
                    tabManager.getWebViewForTab(activeTab.id)?.clearCache(false)
                }
                tabManager.hibernateAllTabs()
                WebViewPool.clear()
                tabManager.persistSession(watchdogScope)
                System.gc()
            }

            ComponentCallbacks2.TRIM_MEMORY_BACKGROUND,
            ComponentCallbacks2.TRIM_MEMORY_MODERATE -> {
                tabManager.emergencyFreeMemory()
            }
        }
    }

    override fun onLowMemory() {
        onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        // No-op
    }
}
