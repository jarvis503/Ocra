package com.orca.browser.browser

import android.content.Context
import android.net.Uri
import android.util.LruCache
import android.view.View
import android.view.ViewGroup
import com.orca.browser.OrcaApp
import com.orca.browser.data.db.OrcaDatabase
import com.orca.browser.data.db.TabDao
import com.orca.browser.data.model.BrowsingMode
import com.orca.browser.data.model.Tab
import com.orca.browser.tor.EmbeddedTorManager
import com.orca.browser.tor.TorWebViewConfig
import com.orca.browser.tor.WebViewProxyUtil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.lang.ref.WeakReference
import java.util.UUID

interface TabManagerListener {
    fun onTabAdded(tab: Tab)
    fun onTabRemoved(tabId: String)
    fun onTabUpdated(tab: Tab)
    fun onActiveTabChanged(tab: Tab?)
    fun onTabListChanged(tabs: List<Tab>)
}

class TabManager private constructor() {

    private val tabs = mutableListOf<Tab>()
    private var activeTabId: String? = null
    private var listener: TabManagerListener? = null
    private var pageEventListener: PageEventListener? = null
    private var webViewConfigurator: ((String, OrcaWebView) -> Unit)? = null

    private var appContext: Context? = null
    private var uiContextRef: WeakReference<Context>? = null
    private var tabDao: TabDao? = null
    private var dbScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var hibernationJob: Job? = null
    private var lastRenderCrashTabId: String? = null
    private var lastRenderCrashTimeMs: Long = 0L

    private val webViewCache = object : LruCache<String, OrcaWebView>(MAX_LIVE_WEBVIEWS) {
        override fun entryRemoved(
            evicted: Boolean,
            key: String,
            oldValue: OrcaWebView,
            newValue: OrcaWebView?
        ) {
            if (evicted) {
                oldValue.hibernate()
                detachFromParent(oldValue)
                oldValue.post { WebViewPool.recycle(oldValue) }
                markTabHibernated(key)
            }
        }
    }

    fun initialize(context: Context, scope: CoroutineScope) {
        captureContext(context)
        dbScope = scope
        ensureTabDao(context)
        restoreSession(scope)
    }

    fun createTab(
        url: String,
        mode: BrowsingMode,
        context: Context,
        isRedirect: Boolean = false,
        sourceTabId: String? = null
    ): Tab {
        captureContext(context)
        ensureTabDao(context)

        val now = System.currentTimeMillis()
        val normalizedUrl = url.trim().ifEmpty { BLANK_URL }
        val initialTitle = if (normalizedUrl == BLANK_URL) DEFAULT_TAB_TITLE else normalizedUrl
        val nextPosition = (tabs.filter { it.browsingMode == mode }.maxOfOrNull { it.position } ?: -1) + 1

        deactivateCurrentActiveInMemory(persistChanges = mode == BrowsingMode.NORMAL)

        val newTab = Tab(
            id = UUID.randomUUID().toString(),
            url = normalizedUrl,
            title = initialTitle,
            favicon = null,
            position = nextPosition,
            isActive = true,
            browsingMode = mode,
            lastAccessedTime = now,
            isHibernated = false,
            isRedirect = isRedirect,
            sourceTabId = sourceTabId,
            createdAt = now
        )

        tabs.add(newTab)
        activeTabId = newTab.id

        if (isRedirect && sourceTabId != null) {
            val sourceUrl = tabs.firstOrNull { it.id == sourceTabId }?.url.orEmpty()
            val sourceDomain = try {
                Uri.parse(sourceUrl).host.orEmpty()
            } catch (_: Exception) {
                ""
            }
            RedirectDetector.markAsRedirect(newTab.id, sourceTabId, sourceDomain)
        }

        val webView = createConfiguredWebView(newTab, context)
        webViewCache.put(newTab.id, webView)
        ensureActiveTimersResumed(webView)
        if (mode != BrowsingMode.TOR || EmbeddedTorManager.isConnected() || normalizedUrl == BLANK_URL) {
            webView.loadUrl(normalizedUrl)
        }

        if (mode == BrowsingMode.NORMAL) {
            persistTabAsActive(newTab)
        }

        listener?.onTabAdded(newTab)
        listener?.onActiveTabChanged(newTab)
        listener?.onTabListChanged(tabs.toList())

        return newTab
    }

    fun switchToTab(tabId: String, context: Context) {
        captureContext(context)
        ensureTabDao(context)

        val targetIndex = tabs.indexOfFirst { it.id == tabId }
        if (targetIndex == -1) return

        val previousActiveId = activeTabId
        if (previousActiveId != null && previousActiveId != tabId) {
            webViewCache.snapshot()[previousActiveId]?.onPause()
        }

        val now = System.currentTimeMillis()
        val oldActive = deactivateCurrentActiveInMemory(persistChanges = false)

        val targetTab = tabs[targetIndex].copy(
            isActive = true,
            isHibernated = false,
            lastAccessedTime = now
        )
        tabs[targetIndex] = targetTab
        activeTabId = tabId

        var webView = webViewCache.get(tabId)
        if (webView == null) {
            webView = createConfiguredWebView(targetTab, context)
            webViewCache.put(tabId, webView)
            ensureActiveTimersResumed(webView)
            webView.loadUrl(targetTab.url)
        } else {
            webView.visibility = View.VISIBLE
            webView.onResume()
            webView.resumeTimers()
        }

        if (targetTab.browsingMode == BrowsingMode.NORMAL || oldActive?.browsingMode == BrowsingMode.NORMAL) {
            val dao = tabDao
            if (dao != null) {
                dbScope.launch(Dispatchers.IO) {
                    dao.deactivateAllTabs()
                    if (targetTab.browsingMode == BrowsingMode.NORMAL) {
                        dao.insertTab(targetTab)
                    }
                }
            }
        }

        if (oldActive != null && oldActive.id != targetTab.id) {
            listener?.onTabUpdated(oldActive)
        }
        listener?.onTabUpdated(targetTab)
        listener?.onActiveTabChanged(targetTab)
        listener?.onTabListChanged(tabs.toList())
    }

    fun closeTab(tabId: String) {
        val index = tabs.indexOfFirst { it.id == tabId }
        if (index == -1) return

        val removedTab = tabs.removeAt(index)
        val wasActive = activeTabId == tabId || removedTab.isActive
        RedirectDetector.removeTab(tabId)

        val cachedWebView = webViewCache.remove(tabId)
        if (cachedWebView != null) {
            detachFromParent(cachedWebView)
            cachedWebView.cleanup()
        }

        if (removedTab.browsingMode == BrowsingMode.NORMAL) {
            val dao = tabDao
            if (dao != null) {
                dbScope.launch(Dispatchers.IO) {
                    dao.deleteTabById(tabId)
                }
            }
        }

        listener?.onTabRemoved(tabId)

        val remainingInMode = tabs.filter { it.browsingMode == removedTab.browsingMode }
        if (remainingInMode.isEmpty()) {
            if (wasActive) {
                activeTabId = null
            }
            val context = getAvailableContext()
            if (context != null) {
                createTab(BLANK_URL, removedTab.browsingMode, context)
            } else {
                if (wasActive) {
                    listener?.onActiveTabChanged(null)
                }
                listener?.onTabListChanged(tabs.toList())
            }
            return
        }

        if (wasActive) {
            val nearestTab = findNearestTab(removedTab, remainingInMode)
            val context = getAvailableContext()
            if (context != null) {
                switchToTab(nearestTab.id, context)
            } else {
                val nearestIndex = tabs.indexOfFirst { it.id == nearestTab.id }
                if (nearestIndex != -1) {
                    val activated = tabs[nearestIndex].copy(
                        isActive = true,
                        lastAccessedTime = System.currentTimeMillis()
                    )
                    tabs[nearestIndex] = activated
                    activeTabId = activated.id
                    listener?.onActiveTabChanged(activated)
                }
                listener?.onTabListChanged(tabs.toList())
            }
        } else {
            listener?.onTabListChanged(tabs.toList())
        }
    }

    fun closeAllTabs(mode: BrowsingMode) {
        val tabsToClose = tabs.filter { it.browsingMode == mode }
        if (tabsToClose.isEmpty()) return

        var closedActive = false
        for (tab in tabsToClose) {
            if (tab.id == activeTabId || tab.isActive) {
                closedActive = true
            }
            RedirectDetector.removeTab(tab.id)
            val cachedWebView = webViewCache.remove(tab.id)
            if (cachedWebView != null) {
                detachFromParent(cachedWebView)
                cachedWebView.cleanup()
            }
            tabs.remove(tab)
            listener?.onTabRemoved(tab.id)
        }

        if (mode == BrowsingMode.NORMAL) {
            val dao = tabDao
            if (dao != null) {
                dbScope.launch(Dispatchers.IO) {
                    dao.deleteTabsByMode(BrowsingMode.NORMAL)
                }
            }
        } else if (mode == BrowsingMode.INCOGNITO) {
            IncognitoManager.clearIncognitoData()
        } else if (mode == BrowsingMode.TOR) {
            TorWebViewConfig.clearTorData()
            EmbeddedTorManager.stop()
        }

        if (closedActive) {
            activeTabId = null
            val context = getAvailableContext()
            if (context != null) {
                createTab(BLANK_URL, mode, context)
            } else {
                listener?.onActiveTabChanged(null)
                listener?.onTabListChanged(tabs.toList())
            }
        } else {
            listener?.onTabListChanged(tabs.toList())
        }
    }

    fun clearTorSession() {
        val torTabs = tabs.filter { it.browsingMode == BrowsingMode.TOR }
        var closedActive = false

        for (tab in torTabs) {
            if (tab.id == activeTabId || tab.isActive) {
                closedActive = true
            }
            RedirectDetector.removeTab(tab.id)
            val cachedWebView = webViewCache.remove(tab.id)
            if (cachedWebView != null) {
                detachFromParent(cachedWebView)
                cachedWebView.cleanup()
            }
            tabs.remove(tab)
            listener?.onTabRemoved(tab.id)
        }

        TorWebViewConfig.clearTorData()
        EmbeddedTorManager.stop()

        if (closedActive) {
            val fallbackTab = tabs.firstOrNull { it.browsingMode == BrowsingMode.NORMAL }
                ?: tabs.firstOrNull()
            activeTabId = fallbackTab?.id
            listener?.onActiveTabChanged(fallbackTab)
        }
        listener?.onTabListChanged(tabs.toList())
    }

    fun clearIncognitoSession() {
        val incognitoTabs = tabs.filter { it.browsingMode == BrowsingMode.INCOGNITO }
        var closedActive = false

        for (tab in incognitoTabs) {
            if (tab.id == activeTabId || tab.isActive) {
                closedActive = true
            }
            val cachedWebView = webViewCache.remove(tab.id)
            if (cachedWebView != null) {
                detachFromParent(cachedWebView)
                cachedWebView.cleanup()
            }
            tabs.remove(tab)
            listener?.onTabRemoved(tab.id)
        }

        IncognitoManager.clearIncognitoData()

        if (closedActive) {
            val fallbackTab = tabs.firstOrNull { it.browsingMode == BrowsingMode.NORMAL }
                ?: tabs.firstOrNull()
            activeTabId = fallbackTab?.id
            listener?.onActiveTabChanged(fallbackTab)
        }
        listener?.onTabListChanged(tabs.toList())
    }

    fun getTabsByMode(mode: BrowsingMode): List<Tab> {
        return tabs.filter { it.browsingMode == mode }
    }

    fun getActiveTab(): Tab? {
        val id = activeTabId ?: return tabs.firstOrNull { it.isActive }
        return tabs.firstOrNull { it.id == id }
    }

    fun getWebViewForTab(tabId: String): OrcaWebView? {
        return webViewCache.get(tabId)
    }

    fun handleRenderProcessGone(tabId: String, context: Context) {
        captureContext(context)
        val deadWebView = webViewCache.remove(tabId)
        if (deadWebView != null) {
            try {
                detachFromParent(deadWebView)
                deadWebView.destroy()
            } catch (_: Throwable) {
                // Dead renderer already torn down
            }
        }

        val index = tabs.indexOfFirst { it.id == tabId }
        if (index == -1) return

        val now = System.currentTimeMillis()
        val repeatedCrash = (lastRenderCrashTabId == tabId) &&
            (now - lastRenderCrashTimeMs < RENDER_CRASH_COOLDOWN_MS)
        lastRenderCrashTabId = tabId
        lastRenderCrashTimeMs = now

        val existing = tabs[index]
        val updated = existing.copy(
            url = if (repeatedCrash) BLANK_URL else existing.url,
            isHibernated = true
        )
        tabs[index] = updated
        listener?.onTabUpdated(updated)

        if (tabId == activeTabId) {
            switchToTab(tabId, context)
        }
    }

    fun updateTabInfo(tabId: String, url: String?, title: String?, favicon: ByteArray?) {
        val index = tabs.indexOfFirst { it.id == tabId }
        if (index == -1) return

        val existing = tabs[index]
        val updated = existing.copy(
            url = url ?: existing.url,
            title = title?.takeIf { it.isNotBlank() } ?: existing.title,
            favicon = favicon ?: existing.favicon,
            lastAccessedTime = System.currentTimeMillis()
        )
        tabs[index] = updated

        if (updated.browsingMode == BrowsingMode.NORMAL) {
            val dao = tabDao
            if (dao != null) {
                dbScope.launch(Dispatchers.IO) {
                    dao.updateTab(updated)
                }
            }
        }

        listener?.onTabUpdated(updated)
    }

    fun persistSession(scope: CoroutineScope) {
        val dao = tabDao ?: getAvailableContext()?.let {
            OrcaDatabase.getInstance(it.applicationContext).tabDao()
        } ?: return

        val normalTabs = tabs.filter { it.browsingMode == BrowsingMode.NORMAL }
        scope.launch(Dispatchers.IO) {
            for (tab in normalTabs) {
                dao.insertTab(tab)
            }
        }
    }

    fun restoreSession(scope: CoroutineScope) {
        val dao = tabDao ?: getAvailableContext()?.let {
            OrcaDatabase.getInstance(it.applicationContext).tabDao()
        } ?: return

        scope.launch {
            val storedNormalTabs = withContext(Dispatchers.IO) {
                dao.getTabsByMode(BrowsingMode.NORMAL).first()
            }

            withContext(Dispatchers.Main) {
                tabs.removeAll { it.browsingMode == BrowsingMode.NORMAL }

                val preserveExistingActive = activeTabId != null && tabs.any { it.id == activeTabId }
                val activeNormalId = if (preserveExistingActive) {
                    null
                } else {
                    storedNormalTabs.firstOrNull { it.isActive }?.id
                        ?: storedNormalTabs.firstOrNull()?.id
                }

                val restoredTabs = storedNormalTabs.map { tab ->
                    tab.copy(
                        isHibernated = true,
                        isActive = tab.id == activeNormalId
                    )
                }

                tabs.addAll(restoredTabs)
                if (!preserveExistingActive) {
                    activeTabId = activeNormalId
                }

                listener?.onTabListChanged(tabs.toList())
                listener?.onActiveTabChanged(getActiveTab())
            }
        }
    }

    fun hibernateInactiveTabs() {
        val now = System.currentTimeMillis()
        val liveSnapshot = webViewCache.snapshot()
        var anyHibernated = false

        for ((tabId, webView) in liveSnapshot) {
            val index = tabs.indexOfFirst { it.id == tabId }
            if (index == -1) continue

            val tab = tabs[index]
            if (tab.id == activeTabId || tab.isActive) continue
            if (now - tab.lastAccessedTime <= HIBERNATION_TIMEOUT_MS) continue

            hibernateAndRemoveWebView(tabId, webView)

            val updatedTab = tab.copy(isHibernated = true)
            tabs[index] = updatedTab
            anyHibernated = true

            if (updatedTab.browsingMode == BrowsingMode.NORMAL) {
                val dao = tabDao
                if (dao != null) {
                    dbScope.launch(Dispatchers.IO) {
                        dao.updateHibernation(tabId, true)
                    }
                }
            }

            listener?.onTabUpdated(updatedTab)
        }

        if (anyHibernated) {
            activeTabId?.let { activeId ->
                webViewCache.snapshot()[activeId]?.let { activeView ->
                    ensureActiveTimersResumed(activeView)
                }
            }
            listener?.onTabListChanged(tabs.toList())
        }
    }

    fun startHibernationMonitor(scope: CoroutineScope) {
        hibernationJob?.cancel()
        hibernationJob = scope.launch(Dispatchers.Main) {
            while (isActive) {
                delay(HIBERNATION_CHECK_INTERVAL_MS)
                hibernateInactiveTabs()
            }
        }
    }

    fun emergencyFreeMemory() {
        val liveSnapshot = webViewCache.snapshot()
        var anyFreed = false

        for ((tabId, webView) in liveSnapshot) {
            if (tabId == activeTabId) continue

            hibernateAndRemoveWebView(tabId, webView)

            val index = tabs.indexOfFirst { it.id == tabId }
            if (index != -1) {
                val updatedTab = tabs[index].copy(isHibernated = true)
                tabs[index] = updatedTab
                anyFreed = true

                if (updatedTab.browsingMode == BrowsingMode.NORMAL) {
                    val dao = tabDao
                    if (dao != null) {
                        dbScope.launch(Dispatchers.IO) {
                            dao.updateHibernation(tabId, true)
                        }
                    }
                }

                listener?.onTabUpdated(updatedTab)
            }
        }

        WebViewPool.clear()

        if (anyFreed) {
            activeTabId?.let { activeId ->
                webViewCache.snapshot()[activeId]?.let { activeView ->
                    ensureActiveTimersResumed(activeView)
                }
            }
            listener?.onTabListChanged(tabs.toList())
        }
    }

    fun hibernateAllTabs() {
        val liveSnapshot = webViewCache.snapshot()
        var anyHibernated = false

        for ((tabId, webView) in liveSnapshot) {
            hibernateAndRemoveWebView(tabId, webView)

            val index = tabs.indexOfFirst { it.id == tabId }
            if (index != -1) {
                val updatedTab = tabs[index].copy(isHibernated = true)
                tabs[index] = updatedTab
                anyHibernated = true

                if (updatedTab.browsingMode == BrowsingMode.NORMAL) {
                    val dao = tabDao
                    if (dao != null) {
                        dbScope.launch(Dispatchers.IO) {
                            dao.updateHibernation(tabId, true)
                        }
                    }
                }

                listener?.onTabUpdated(updatedTab)
            }
        }

        if (anyHibernated) {
            listener?.onTabListChanged(tabs.toList())
        }
    }

    fun setListener(listener: TabManagerListener?) {
        this.listener = listener
    }

    fun setPageEventListener(listener: PageEventListener?) {
        this.pageEventListener = listener
    }

    fun setWebViewConfigurator(configurator: ((String, OrcaWebView) -> Unit)?) {
        this.webViewConfigurator = configurator
    }

    fun getTabCount(mode: BrowsingMode): Int {
        return tabs.count { it.browsingMode == mode }
    }

    fun getAllTabs(): List<Tab> {
        return tabs.toList()
    }

    private fun createConfiguredWebView(tab: Tab, context: Context): OrcaWebView {
        return WebViewPool.obtain(context).apply {
            configureForMode(tab.browsingMode)
            when (tab.browsingMode) {
                BrowsingMode.INCOGNITO -> IncognitoManager.configureWebViewForIncognito(this)
                BrowsingMode.TOR -> {
                    TorWebViewConfig.applyTorConfig(this)
                    val socksPort = EmbeddedTorManager.getSocksPort()
                    if (socksPort > 0) {
                        WebViewProxyUtil.applyProxy(this, "127.0.0.1", socksPort)
                    }
                }
                BrowsingMode.NORMAL -> WebViewProxyUtil.clearProxy(this)
            }
            pageEventListener?.let { events ->
                webViewClient = OrcaWebViewClient(tab.id, events)
                webChromeClient = OrcaWebChromeClient(tab.id, events)
            }
            webViewConfigurator?.invoke(tab.id, this)
        }
    }

    private fun hibernateAndRemoveWebView(tabId: String, webView: OrcaWebView) {
        webView.hibernate()
        detachFromParent(webView)
        webViewCache.remove(tabId)
        WebViewPool.recycle(webView)
    }

    private fun markTabHibernated(tabId: String) {
        val index = tabs.indexOfFirst { it.id == tabId }
        if (index == -1) return

        val existing = tabs[index]
        if (!existing.isHibernated) {
            val updated = existing.copy(isHibernated = true)
            tabs[index] = updated
            if (updated.browsingMode == BrowsingMode.NORMAL) {
                val dao = tabDao
                if (dao != null) {
                    dbScope.launch(Dispatchers.IO) {
                        dao.updateHibernation(tabId, true)
                    }
                }
            }
            listener?.onTabUpdated(updated)
        }
    }

    private fun deactivateCurrentActiveInMemory(persistChanges: Boolean): Tab? {
        val currentId = activeTabId
        val index = if (currentId != null) {
            tabs.indexOfFirst { it.id == currentId }
        } else {
            tabs.indexOfFirst { it.isActive }
        }
        if (index == -1) return null

        val existing = tabs[index]
        if (!existing.isActive) return existing

        val deactivated = existing.copy(isActive = false)
        tabs[index] = deactivated

        if (persistChanges && deactivated.browsingMode == BrowsingMode.NORMAL) {
            val dao = tabDao
            if (dao != null) {
                dbScope.launch(Dispatchers.IO) {
                    dao.updateActiveState(deactivated.id, false)
                }
            }
        }
        return deactivated
    }

    private fun persistTabAsActive(tab: Tab) {
        val dao = tabDao ?: return
        dbScope.launch(Dispatchers.IO) {
            dao.deactivateAllTabs()
            dao.insertTab(tab)
        }
    }

    private fun findNearestTab(removedTab: Tab, remainingInMode: List<Tab>): Tab {
        return remainingInMode.firstOrNull { it.position >= removedTab.position }
            ?: remainingInMode.last()
    }

    private fun ensureActiveTimersResumed(webView: OrcaWebView) {
        webView.visibility = View.VISIBLE
        webView.onResume()
        webView.resumeTimers()
    }

    private fun detachFromParent(webView: OrcaWebView) {
        val parent = webView.parent as? ViewGroup ?: return
        parent.removeView(webView)
    }

    private fun captureContext(context: Context) {
        appContext = context.applicationContext
        uiContextRef = WeakReference(context)
    }

    private fun getAvailableContext(): Context? {
        return uiContextRef?.get() ?: appContext ?: try {
            OrcaApp.instance
        } catch (_: UninitializedPropertyAccessException) {
            null
        }
    }

    private fun ensureTabDao(context: Context) {
        if (tabDao == null) {
            tabDao = OrcaDatabase.getInstance(context.applicationContext).tabDao()
        }
    }

    companion object {
        private const val MAX_LIVE_WEBVIEWS = 3
        private const val HIBERNATION_TIMEOUT_MS = 3L * 60L * 1000L
        private const val HIBERNATION_CHECK_INTERVAL_MS = 60L * 1000L
        private const val BLANK_URL = "about:blank"
        private const val DEFAULT_TAB_TITLE = "New Tab"
        private const val RENDER_CRASH_COOLDOWN_MS = 5000L

        @Volatile
        private var INSTANCE: TabManager? = null

        fun getInstance(): TabManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: TabManager().also { INSTANCE = it }
            }
        }
    }
}
