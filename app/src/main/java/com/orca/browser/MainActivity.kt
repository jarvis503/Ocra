package com.orca.browser

import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.orca.browser.adblocker.AdBlocker
import com.orca.browser.adblocker.PopupBlocker
import com.orca.browser.browser.OrcaWebChromeClient
import com.orca.browser.browser.OrcaWebView
import com.orca.browser.browser.PageEventListener
import com.orca.browser.browser.StartupOptimizer
import com.orca.browser.browser.TabManager
import com.orca.browser.browser.TabManagerListener
import com.orca.browser.data.db.HistoryDao
import com.orca.browser.data.model.BrowsingMode
import com.orca.browser.data.model.HistoryEntry
import com.orca.browser.data.model.SearchEngine
import com.orca.browser.data.model.Tab
import com.orca.browser.data.prefs.PrefsManager
import com.orca.browser.databinding.ActivityMainBinding
import com.orca.browser.tor.EmbeddedTorManager
import com.orca.browser.tor.TorConnectDialog
import com.orca.browser.tor.TorState
import com.orca.browser.tor.WebViewProxyUtil
import com.orca.browser.ui.find.FindInPageController
import com.orca.browser.ui.home.HomePageController
import com.orca.browser.ui.menu.BrowserMenuPopup
import com.orca.browser.ui.overview.TabOverviewFragment
import com.orca.browser.ui.redirect.RedirectPromptDialog
import com.orca.browser.ui.settings.SettingsActivity
import com.orca.browser.ui.tabstrip.TabStripAdapter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

// Orientation Test Matrix:
// Portrait -> browsing -> rotate to landscape: WebView re-renders, toolbar adapts
// Landscape -> open tab overview -> 3-column grid shown
// Portrait -> open tab overview -> 2-column grid shown
// Rotate while tab overview open -> grid columns update
// Rotate while find-in-page active -> find bar stays, results preserved
// Rotate while typing in address bar -> text preserved (configChanges prevents recreation)
// All modes (Normal/Incognito/Tor) survive rotation
class MainActivity : AppCompatActivity(), TabManagerListener, PageEventListener {

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: PrefsManager
    private lateinit var tabManager: TabManager
    private lateinit var historyDao: HistoryDao
    private lateinit var tabStripAdapter: TabStripAdapter
    private lateinit var homePageController: HomePageController
    private lateinit var findController: FindInPageController
    private lateinit var fileChooserLauncher: ActivityResultLauncher<Intent>

    private var currentMode: BrowsingMode = BrowsingMode.NORMAL
    private var currentUrl: String = BLANK_URL
    private var isCurrentPageLoading: Boolean = false
    private var hasCompletedInitialRestore: Boolean = false
    private var isApplyingActiveTabChange: Boolean = false
    private var pendingChromeClientForFile: OrcaWebChromeClient? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(R.style.AppTheme)
        super.onCreate(savedInstanceState)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val app = application as OrcaApp
        prefs = app.prefs
        historyDao = app.database.historyDao()
        tabManager = TabManager.getInstance()

        registerFileChooserLauncher()
        setupHomePageController()
        setupTabStrip()
        setupToolbarButtons()
        setupAddressBar()
        findController = FindInPageController(binding.findBar.root) { activeWebView() }
        setupBackPressHandling()

        applyModeUi(currentMode)
        updateNavigationButtons(canGoBack = false, canGoForward = false)

        EmbeddedTorManager.onStateChanged = { state ->
            handleTorStateChanged(state)
        }

        tabManager.setListener(this)
        tabManager.setPageEventListener(this)
        tabManager.setWebViewConfigurator { tabId, webView ->
            configureWebViewCallbacks(tabId, webView)
        }
        tabManager.initialize(this, lifecycleScope)
        tabManager.startHibernationMonitor(lifecycleScope)

        StartupOptimizer.deferredInit(this) {
            Log.d(TAG, "Deferred startup initialization complete")
        }
    }

    override fun onResume() {
        super.onResume()
        val activeTab = tabManager.getActiveTab()
        if (activeTab != null &&
            activeTab.isHibernated &&
            activeTab.url != BLANK_URL &&
            (activeTab.browsingMode != BrowsingMode.TOR || EmbeddedTorManager.isConnected())
        ) {
            tabManager.switchToTab(activeTab.id, this)
        }
    }

    override fun onPause() {
        super.onPause()
        tabManager.persistSession(lifecycleScope)
    }

    override fun onStop() {
        super.onStop()
        tabManager.persistSession(lifecycleScope)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        val isLandscape = newConfig.orientation == Configuration.ORIENTATION_LANDSCAPE
        Log.d(TAG, "Orientation changed: isLandscape=$isLandscape")
        binding.rvTabStrip.requestLayout()
        val overview = supportFragmentManager.findFragmentByTag(TAG_TAB_OVERVIEW) as? TabOverviewFragment
        overview?.updateGridSpanCount(newConfig.orientation)
    }

    override fun onDestroy() {
        findController.release()
        pendingChromeClientForFile?.cancelPendingFileChooser()
        pendingChromeClientForFile = null
        EmbeddedTorManager.onStateChanged = null
        tabManager.setListener(null)
        tabManager.setPageEventListener(null)
        tabManager.setWebViewConfigurator(null)
        if (tabManager.getTabCount(BrowsingMode.INCOGNITO) > 0) {
            tabManager.clearIncognitoSession()
        }
        if (tabManager.getTabCount(BrowsingMode.TOR) > 0) {
            tabManager.clearTorSession()
        }
        EmbeddedTorManager.stop()
        super.onDestroy()
    }

    // --- TabManagerListener ---

    override fun onTabAdded(tab: Tab) {
        refreshTabStrip()
        updateTabCountBadge()
        refreshTabOverviewIfOpen()
    }

    override fun onTabRemoved(tabId: String) {
        refreshTabStrip()
        updateTabCountBadge()
        refreshTabOverviewIfOpen()
    }

    override fun onTabUpdated(tab: Tab) {
        if (tab.browsingMode == currentMode) {
            refreshTabStrip()
        }
        if (tab.id == tabManager.getActiveTab()?.id) {
            currentUrl = tab.url
            if (!binding.etAddress.hasFocus()) {
                setAddressBarDisplayMode(tab.url)
            }
        }
        refreshTabOverviewIfOpen()
    }

    override fun onActiveTabChanged(tab: Tab?) {
        binding.webviewContainer.removeAllViews()
        tabStripAdapter.activeTabId = tab?.id.orEmpty()
        updateTabCountBadge()

        if (tab == null) {
            currentUrl = BLANK_URL
            setAddressBarDisplayMode(BLANK_URL)
            showHomePage()
            setLoadingUi(false)
            updateNavigationButtons(canGoBack = false, canGoForward = false)
            return
        }

        if (tab.browsingMode != currentMode) {
            currentMode = tab.browsingMode
            applyModeUi(currentMode)
            refreshTabStrip()
            updateTabCountBadge()
        }

        currentUrl = tab.url
        if (!binding.etAddress.hasFocus()) {
            setAddressBarDisplayMode(tab.url)
        }

        if (tab.url == BLANK_URL || tab.url.isBlank()) {
            showHomePage()
            setLoadingUi(false)
            val webView = tabManager.getWebViewForTab(tab.id)
            updateNavigationButtons(
                canGoBack = webView?.canGoBack() == true,
                canGoForward = webView?.canGoForward() == true
            )
            return
        }

        val webView = tabManager.getWebViewForTab(tab.id)
        if (webView == null) {
            if (!isApplyingActiveTabChange) {
                isApplyingActiveTabChange = true
                try {
                    tabManager.switchToTab(tab.id, this)
                } finally {
                    isApplyingActiveTabChange = false
                }
            }
            return
        }

        hideHomePage()
        attachWebViewToContainer(webView)
        updateNavigationButtons(
            canGoBack = webView.canGoBack(),
            canGoForward = webView.canGoForward()
        )
    }

    override fun onTabListChanged(tabs: List<Tab>) {
        if (!hasCompletedInitialRestore) {
            hasCompletedInitialRestore = true
            val modeTabs = tabManager.getTabsByMode(currentMode)
            if (modeTabs.isEmpty()) {
                tabManager.createTab(BLANK_URL, currentMode, this)
                return
            } else {
                val target = tabManager.getActiveTab()?.takeIf { it.browsingMode == currentMode }
                    ?: modeTabs.first()
                tabManager.switchToTab(target.id, this)
                return
            }
        }
        refreshTabStrip()
        updateTabCountBadge()
        refreshTabOverviewIfOpen()
    }

    // --- PageEventListener ---

    override fun onPageStarted(tabId: String, url: String) {
        if (url != BLANK_URL) {
            AdBlocker.resetBlockCount(tabId)
        }
        val activeId = tabManager.getActiveTab()?.id
        if (tabId == activeId) {
            currentUrl = url
            if (url != BLANK_URL) {
                hideHomePage()
                if (!binding.etAddress.hasFocus()) {
                    setAddressBarDisplayMode(url)
                }
                setLoadingUi(true)
            } else {
                showHomePage()
                setLoadingUi(false)
            }
            val webView = tabManager.getWebViewForTab(tabId)
            updateNavigationButtons(
                canGoBack = webView?.canGoBack() == true,
                canGoForward = webView?.canGoForward() == true
            )
        }
        tabManager.updateTabInfo(tabId, url, null, null)
    }

    override fun onPageFinished(tabId: String, url: String, title: String) {
        tabManager.updateTabInfo(tabId, url, title, null)

        val activeId = tabManager.getActiveTab()?.id
        if (tabId == activeId) {
            currentUrl = url
            if (!binding.etAddress.hasFocus()) {
                setAddressBarDisplayMode(url)
            }
            setLoadingUi(false)
            val webView = tabManager.getWebViewForTab(tabId)
            updateNavigationButtons(
                canGoBack = webView?.canGoBack() == true,
                canGoForward = webView?.canGoForward() == true
            )
        }

        val tab = tabManager.getAllTabs().firstOrNull { it.id == tabId }
        if (tab?.browsingMode == BrowsingMode.NORMAL &&
            prefs.isHistoryEnabled &&
            url != BLANK_URL &&
            !url.startsWith("data:")
        ) {
            val entry = HistoryEntry(
                url = url,
                title = title.ifBlank { url },
                visitedAt = System.currentTimeMillis(),
                favicon = tab.favicon
            )
            lifecycleScope.launch(Dispatchers.IO) {
                historyDao.insertEntry(entry)
            }
        }
    }

    override fun onProgressChanged(tabId: String, progress: Int) {
        if (tabId != tabManager.getActiveTab()?.id) return
        if (currentUrl == BLANK_URL) {
            binding.progressBar.visibility = View.GONE
            return
        }
        if (progress in 1..99) {
            binding.progressBar.visibility = View.VISIBLE
            binding.progressBar.progress = progress
            setLoadingUi(true)
        } else {
            binding.progressBar.progress = 100
            binding.progressBar.visibility = View.GONE
            setLoadingUi(false)
        }
    }

    override fun onTitleChanged(tabId: String, title: String) {
        tabManager.updateTabInfo(tabId, null, title, null)
    }

    override fun onFaviconReceived(tabId: String, favicon: Bitmap?) {
        if (favicon == null) return
        val bytes = OrcaWebChromeClient.compressBitmapToWebp(favicon)
        tabManager.updateTabInfo(tabId, null, null, bytes)
    }

    override fun onNewTabRequested(url: String, sourceTabId: String) {
        tabManager.createTab(
            url = url,
            mode = currentMode,
            context = this,
            isRedirect = true,
            sourceTabId = sourceTabId
        )
    }

    override fun onRedirectIntercepted(targetUrl: String, sourceDomain: String) {
        runOnUiThread {
            if (isFinishing || isDestroyed || supportFragmentManager.isStateSaved) return@runOnUiThread
            if (supportFragmentManager.findFragmentByTag(RedirectPromptDialog.TAG) != null) {
                return@runOnUiThread
            }

            val dialog = RedirectPromptDialog.newInstance(targetUrl, sourceDomain)
            dialog.onDecision = { allowed ->
                if (allowed && !isFinishing && !isDestroyed) {
                    val activeTab = tabManager.getActiveTab()
                    activeTab?.id?.let { PopupBlocker.recordPopup(it) }
                    tabManager.createTab(
                        url = targetUrl,
                        mode = currentMode,
                        context = this,
                        isRedirect = true,
                        sourceTabId = activeTab?.id
                    )
                }
            }
            dialog.show(supportFragmentManager, RedirectPromptDialog.TAG)
        }
    }

    override fun onTabCloseRequested(tabId: String) {
        tabManager.closeTab(tabId)
    }

    override fun onNavigationStateChanged(tabId: String, canGoBack: Boolean, canGoForward: Boolean) {
        if (tabId == tabManager.getActiveTab()?.id) {
            updateNavigationButtons(canGoBack, canGoForward)
        }
    }

    override fun onRenderProcessGone(tabId: String) {
        runOnUiThread {
            if (isFinishing || isDestroyed) return@runOnUiThread
            tabManager.handleRenderProcessGone(tabId, this)
            Toast.makeText(this, "Tab recovered after renderer reset", Toast.LENGTH_SHORT).show()
        }
    }

    // --- Address Bar Modes ---

    fun setAddressBarDisplayMode(url: String) {
        currentUrl = url
        if (url == BLANK_URL || url.isBlank()) {
            binding.etAddress.setText("")
            return
        }
        val host = try {
            Uri.parse(url).host
        } catch (_: Exception) {
            null
        }
        val display = if (!host.isNullOrBlank()) {
            host.removePrefix("www.")
        } else {
            url
        }
        binding.etAddress.setText(display)
    }

    fun setAddressBarEditMode() {
        val textToEdit = if (currentUrl == BLANK_URL) "" else currentUrl
        binding.etAddress.setText(textToEdit)
        if (!binding.etAddress.hasFocus()) {
            binding.etAddress.requestFocus()
        }
        binding.etAddress.selectAll()
        showKeyboard(binding.etAddress)
    }

    // --- Browsing Mode Switching ---

    fun switchBrowsingMode(mode: BrowsingMode) {
        currentMode = mode
        prefs.setBrowsingMode(mode)
        applyModeUi(mode)

        if (mode == BrowsingMode.TOR) {
            if (!EmbeddedTorManager.isConnected()) {
                showTorConnectDialog {
                    if (!isFinishing && !isDestroyed && currentMode == BrowsingMode.TOR) {
                        activateTorModeTabs()
                    }
                }
                refreshTabStrip()
                updateTabCountBadge()
                return
            }
            val socksPort = EmbeddedTorManager.getSocksPort()
            if (socksPort > 0) {
                WebViewProxyUtil.applyProxy(null, "127.0.0.1", socksPort)
            }
        } else {
            WebViewProxyUtil.clearProxy(null)
        }

        val modeTabs = tabManager.getTabsByMode(mode)
        if (modeTabs.isEmpty()) {
            tabManager.createTab(BLANK_URL, mode, this)
        } else {
            val targetTab = modeTabs.firstOrNull { it.isActive } ?: modeTabs.last()
            tabManager.switchToTab(targetTab.id, this)
        }
        refreshTabStrip()
        updateTabCountBadge()
    }

    private fun activateTorModeTabs() {
        val socksPort = EmbeddedTorManager.getSocksPort()
        if (socksPort > 0) {
            WebViewProxyUtil.applyProxy(null, "127.0.0.1", socksPort)
        }
        val modeTabs = tabManager.getTabsByMode(BrowsingMode.TOR)
        if (modeTabs.isEmpty()) {
            tabManager.createTab(BLANK_URL, BrowsingMode.TOR, this)
        } else {
            val targetTab = modeTabs.firstOrNull { it.isActive } ?: modeTabs.last()
            tabManager.switchToTab(targetTab.id, this)
        }
        refreshTabStrip()
        updateTabCountBadge()
    }

    fun updateTabCountBadge() {
        val count = tabManager.getTabCount(currentMode)
        binding.tvTabCount.text = count.coerceAtLeast(0).toString()
    }

    // --- Setup & Helpers ---

    private fun registerFileChooserLauncher() {
        fileChooserLauncher = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->
            val uris = WebChromeClient.FileChooserParams.parseResult(
                result.resultCode,
                result.data
            )
            pendingChromeClientForFile?.onFileChooserResult(uris)
            pendingChromeClientForFile = null
        }
    }

    private fun setupHomePageController() {
        homePageController = HomePageController(binding.homeContainer).apply {
            onNavigate = { url -> navigateToInput(url) }
            onSearchBarClicked = { setAddressBarEditMode() }
        }
    }

    private fun setupTabStrip() {
        tabStripAdapter = TabStripAdapter().apply {
            onTabSelected = { tabId ->
                tabManager.switchToTab(tabId, this@MainActivity)
            }
            onTabClosed = { tabId ->
                tabManager.closeTab(tabId)
            }
        }
        binding.rvTabStrip.apply {
            layoutManager = LinearLayoutManager(this@MainActivity, LinearLayoutManager.HORIZONTAL, false)
            adapter = tabStripAdapter
        }
    }

    private fun setupToolbarButtons() {
        binding.btnBack.setOnClickListener {
            val webView = activeWebView()
            if (webView != null && webView.canGoBack()) {
                webView.goBack()
            }
        }

        binding.btnForward.setOnClickListener {
            val webView = activeWebView()
            if (webView != null && webView.canGoForward()) {
                webView.goForward()
            }
        }

        binding.btnReload.setOnClickListener {
            val webView = activeWebView() ?: return@setOnClickListener
            if (isCurrentPageLoading) {
                webView.stopLoading()
                setLoadingUi(false)
            } else if (currentUrl != BLANK_URL) {
                webView.reload()
            }
        }

        binding.btnHome.setOnClickListener {
            val searchEngine = SearchEngine.getByKey(prefs.defaultSearchEngine)
            navigateToInput(searchEngine.homepageUrl)
        }

        binding.btnNewTab.setOnClickListener {
            tabManager.createTab(BLANK_URL, currentMode, this)
        }

        binding.btnTabCountContainer.setOnClickListener {
            openTabOverview()
        }

        binding.btnSidebar.setOnClickListener {
            openTabOverview()
        }

        binding.btnMenu.setOnClickListener { anchor ->
            showBrowserMenu(anchor)
        }

        binding.btnBackToNormal.setOnClickListener {
            switchBrowsingMode(BrowsingMode.NORMAL)
        }

        binding.btnPageOptions.setOnClickListener {
            val activeTabId = tabManager.getActiveTab()?.id.orEmpty()
            val blockedCount = AdBlocker.getBlockCount(activeTabId)
            Toast.makeText(
                this,
                "\uD83D\uDEE1\uFE0F $blockedCount ads blocked on this page",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun setupAddressBar() {
        binding.etAddress.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                setAddressBarEditMode()
            } else {
                setAddressBarDisplayMode(currentUrl)
            }
        }

        binding.etAddress.setOnClickListener {
            if (!binding.etAddress.hasFocus()) {
                binding.etAddress.requestFocus()
            } else {
                binding.etAddress.selectAll()
            }
        }

        binding.etAddress.setOnEditorActionListener { _, actionId, event ->
            val isGoAction = actionId == EditorInfo.IME_ACTION_GO ||
                actionId == EditorInfo.IME_ACTION_SEARCH ||
                actionId == EditorInfo.IME_ACTION_DONE ||
                actionId == EditorInfo.IME_ACTION_SEND
            val isEnterKey = event != null &&
                (event.keyCode == KeyEvent.KEYCODE_ENTER ||
                    event.keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER) &&
                event.action == KeyEvent.ACTION_DOWN

            if (isGoAction || isEnterKey) {
                navigateToInput(binding.etAddress.text?.toString().orEmpty())
                true
            } else {
                false
            }
        }
    }

    private fun setupBackPressHandling() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    findController.isVisible() -> findController.hide()
                    binding.etAddress.hasFocus() -> {
                        binding.etAddress.clearFocus()
                        hideKeyboard(binding.etAddress)
                    }
                    binding.overlayContainer.visibility == View.VISIBLE -> {
                        val overview = supportFragmentManager.findFragmentByTag(TAG_TAB_OVERVIEW)
                            as? TabOverviewFragment
                        if (overview?.handleBackPressed() != true) {
                            closeTabOverview()
                        }
                    }
                    activeWebView()?.canGoBack() == true -> activeWebView()?.goBack()
                    else -> moveTaskToBack(true)
                }
            }
        })
    }

    private fun showBrowserMenu(anchor: View) {
        val popup = BrowserMenuPopup(anchor, this).apply {
            onDesktopModeToggled = { enabled ->
                prefs.isDesktopMode = enabled
                activeWebView()?.applyDesktopMode(enabled)
            }
            onJavaScriptToggled = { enabled ->
                prefs.isJavaScriptEnabled = enabled
                activeWebView()?.updateJavaScript(enabled)
            }
            onFindInPage = {
                findController.show()
            }
            onShare = {
                shareCurrentPage()
            }
            onSettings = {
                openSettingsScreen()
            }
            onExit = {
                if (tabManager.getTabCount(BrowsingMode.INCOGNITO) > 0) {
                    tabManager.clearIncognitoSession()
                }
                if (tabManager.getTabCount(BrowsingMode.TOR) > 0) {
                    tabManager.clearTorSession()
                }
                tabManager.persistSession(lifecycleScope)
                finishAffinity()
            }
        }
        popup.show(anchor)
    }

    private fun shareCurrentPage() {
        val urlToShare = currentUrl.takeIf { it.isNotBlank() && it != BLANK_URL } ?: return
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, urlToShare)
        }
        startActivity(Intent.createChooser(shareIntent, getString(R.string.share)))
    }

    fun openSettingsScreen() {
        startActivity(Intent(this, SettingsActivity::class.java))
    }

    fun openTabOverview() {
        val existing = supportFragmentManager.findFragmentByTag(TAG_TAB_OVERVIEW) as? TabOverviewFragment
        if (existing != null) {
            binding.overlayContainer.visibility = View.VISIBLE
            existing.refreshTabs()
            return
        }

        val fragment = TabOverviewFragment.newInstance(currentMode)
        binding.overlayContainer.visibility = View.VISIBLE
        supportFragmentManager.beginTransaction()
            .add(R.id.overlay_container, fragment, TAG_TAB_OVERVIEW)
            .commit()
    }

    fun closeTabOverview() {
        val existing = supportFragmentManager.findFragmentByTag(TAG_TAB_OVERVIEW)
        if (existing != null) {
            supportFragmentManager.beginTransaction()
                .remove(existing)
                .commitAllowingStateLoss()
        }
        binding.overlayContainer.visibility = View.GONE
    }

    fun selectTabFromOverview(tabId: String) {
        tabManager.switchToTab(tabId, this)
        closeTabOverview()
    }

    fun createNewTabFromOverview(mode: BrowsingMode) {
        if (currentMode != mode) {
            currentMode = mode
            prefs.setBrowsingMode(mode)
            applyModeUi(mode)
        }
        if (mode == BrowsingMode.TOR) {
            closeTabOverview()
            if (!EmbeddedTorManager.isConnected()) {
                showTorConnectDialog {
                    if (!isFinishing && !isDestroyed && currentMode == BrowsingMode.TOR) {
                        activateTorModeTabs()
                    }
                }
                return
            }
            val socksPort = EmbeddedTorManager.getSocksPort()
            if (socksPort > 0) {
                WebViewProxyUtil.applyProxy(null, "127.0.0.1", socksPort)
            }
        } else {
            WebViewProxyUtil.clearProxy(null)
            closeTabOverview()
        }
        tabManager.createTab(BLANK_URL, mode, this)
    }

    fun openUrlFromHistory(url: String) {
        closeTabOverview()
        if (currentMode != BrowsingMode.NORMAL) {
            switchBrowsingMode(BrowsingMode.NORMAL)
        }
        navigateToInput(url)
    }

    private fun refreshTabOverviewIfOpen() {
        val overview = supportFragmentManager.findFragmentByTag(TAG_TAB_OVERVIEW) as? TabOverviewFragment
        overview?.refreshTabs()
    }

    private fun handleTorStateChanged(state: TorState) {
        runOnUiThread {
            if (isFinishing || isDestroyed) return@runOnUiThread
            val isDialogShowing =
                supportFragmentManager.findFragmentByTag(TorConnectDialog.TAG) != null

            when (state) {
                TorState.CONNECTING -> {
                    // Progress is presented directly inside TorConnectDialog
                }

                TorState.CONNECTED -> {
                    val socksPort = EmbeddedTorManager.getSocksPort()
                    if (currentMode == BrowsingMode.TOR && socksPort > 0) {
                        val webView = activeWebView()
                        WebViewProxyUtil.applyProxy(webView, "127.0.0.1", socksPort)
                    }
                }

                TorState.NOT_SUPPORTED -> {
                    if (!isDialogShowing) {
                        Toast.makeText(
                            this,
                            TorState.NOT_SUPPORTED.displayText,
                            Toast.LENGTH_LONG
                        ).show()
                        if (currentMode == BrowsingMode.TOR) {
                            switchBrowsingMode(BrowsingMode.NORMAL)
                        }
                    }
                }

                TorState.ERROR -> {
                    if (!isDialogShowing) {
                        val message = EmbeddedTorManager.getLastError()
                            ?.takeIf { it.isNotBlank() }
                            ?: TorState.ERROR.displayText
                        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
                    }
                }

                TorState.DISCONNECTED -> {
                    if (currentMode == BrowsingMode.TOR) {
                        WebViewProxyUtil.clearProxy(null)
                    }
                }
            }
        }
    }

    private fun showTorConnectDialog(onConnected: () -> Unit) {
        if (isFinishing || isDestroyed || supportFragmentManager.isStateSaved) return
        val existing = supportFragmentManager.findFragmentByTag(TorConnectDialog.TAG)
            as? TorConnectDialog
        if (existing != null) {
            existing.onTorConnected = onConnected
            existing.onTorCancelled = {
                if (!isFinishing && !isDestroyed && currentMode == BrowsingMode.TOR) {
                    switchBrowsingMode(BrowsingMode.NORMAL)
                }
            }
            return
        }

        val dialog = TorConnectDialog.newInstance().apply {
            onTorConnected = onConnected
            onTorCancelled = {
                if (!isFinishing && !isDestroyed && currentMode == BrowsingMode.TOR) {
                    switchBrowsingMode(BrowsingMode.NORMAL)
                }
            }
        }
        dialog.show(supportFragmentManager, TorConnectDialog.TAG)
    }

    private fun navigateToInput(rawInput: String) {
        val trimmed = rawInput.trim()
        if (trimmed.isEmpty()) {
            binding.etAddress.clearFocus()
            hideKeyboard(binding.etAddress)
            return
        }

        val targetUrl = resolveNavigationUrl(trimmed)
        currentUrl = targetUrl
        binding.etAddress.clearFocus()
        hideKeyboard(binding.etAddress)
        setAddressBarDisplayMode(targetUrl)

        val activeTab = tabManager.getActiveTab()
        if (activeTab == null) {
            tabManager.createTab(targetUrl, currentMode, this)
            return
        }

        if (targetUrl == BLANK_URL) {
            tabManager.updateTabInfo(activeTab.id, BLANK_URL, getString(R.string.new_tab), null)
            showHomePage()
            activeWebView()?.loadUrl(BLANK_URL)
            return
        }

        var mutableWebView = tabManager.getWebViewForTab(activeTab.id)
        if (mutableWebView == null) {
            isApplyingActiveTabChange = true
            try {
                tabManager.switchToTab(activeTab.id, this)
            } finally {
                isApplyingActiveTabChange = false
            }
            mutableWebView = tabManager.getWebViewForTab(activeTab.id)
        }

        val resolvedWebView = mutableWebView
        if (resolvedWebView == null) {
            tabManager.createTab(targetUrl, currentMode, this)
            return
        }

        hideHomePage()
        attachWebViewToContainer(resolvedWebView)
        tabManager.updateTabInfo(activeTab.id, targetUrl, null, null)
        if (currentMode == BrowsingMode.TOR && !EmbeddedTorManager.isConnected()) {
            showTorConnectDialog {
                if (!isFinishing && !isDestroyed && currentMode == BrowsingMode.TOR) {
                    val torPort = EmbeddedTorManager.getSocksPort()
                    if (torPort > 0) {
                        WebViewProxyUtil.applyProxy(resolvedWebView, "127.0.0.1", torPort)
                    }
                    try {
                        resolvedWebView.loadUrl(targetUrl)
                    } catch (_: Throwable) {
                        // Ignore load if WebView was detached during dialog wait
                    }
                }
            }
        } else {
            try {
                resolvedWebView.loadUrl(targetUrl)
            } catch (_: Throwable) {
                tabManager.handleRenderProcessGone(activeTab.id, this)
            }
        }
    }

    private fun resolveNavigationUrl(input: String): String {
        if (input.startsWith("http://", ignoreCase = true) ||
            input.startsWith("https://", ignoreCase = true) ||
            input.startsWith("about:", ignoreCase = true)
        ) {
            return input
        }
        val looksLikeDomain = !input.contains(" ") &&
            (input.contains(".") || input.startsWith("localhost", ignoreCase = true))
        if (looksLikeDomain) {
            return "https://$input"
        }
        return SearchEngine.getByKey(prefs.defaultSearchEngine).buildSearchUrl(input)
    }

    private fun applyModeUi(mode: BrowsingMode) {
        val toolbarColor = when (mode) {
            BrowsingMode.NORMAL -> ContextCompat.getColor(this, R.color.colorPrimary)
            BrowsingMode.INCOGNITO -> ContextCompat.getColor(this, R.color.colorIncognitoToolbar)
            BrowsingMode.TOR -> ContextCompat.getColor(this, R.color.colorTorToolbar)
        }
        binding.topToolbar.setBackgroundColor(toolbarColor)
        window.statusBarColor = toolbarColor

        val addressBarDrawable = when (mode) {
            BrowsingMode.NORMAL -> R.drawable.bg_address_bar
            BrowsingMode.INCOGNITO -> R.drawable.bg_address_bar_incognito
            BrowsingMode.TOR -> R.drawable.bg_address_bar_tor
        }
        binding.addressBarContainer.setBackgroundResource(addressBarDrawable)

        binding.btnBackToNormal.visibility = if (mode == BrowsingMode.NORMAL) {
            View.GONE
        } else {
            View.VISIBLE
        }
    }

    private fun configureWebViewCallbacks(tabId: String, webView: OrcaWebView) {
        val chromeClient = OrcaWebChromeClient(tabId, this).apply {
            onFaviconBytesReceived = { id, bytes ->
                tabManager.updateTabInfo(id, null, null, bytes)
            }
            fileChooserHandler = { callback, params ->
                launchFileChooser(this, callback, params)
            }
        }
        webView.webChromeClient = chromeClient
        findController.attachFindListener(webView)
    }

    private fun launchFileChooser(
        chromeClient: OrcaWebChromeClient,
        callback: ValueCallback<Array<Uri>>,
        params: WebChromeClient.FileChooserParams?
    ): Boolean {
        return try {
            val intent = params?.createIntent() ?: Intent(Intent.ACTION_GET_CONTENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "*/*"
            }
            pendingChromeClientForFile = chromeClient
            fileChooserLauncher.launch(intent)
            true
        } catch (_: Exception) {
            callback.onReceiveValue(null)
            pendingChromeClientForFile = null
            false
        }
    }

    private fun attachWebViewToContainer(webView: OrcaWebView) {
        if (webView.parent === binding.webviewContainer) {
            webView.visibility = View.VISIBLE
            return
        }
        (webView.parent as? ViewGroup)?.removeView(webView)
        binding.webviewContainer.removeAllViews()
        binding.webviewContainer.addView(
            webView,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        webView.visibility = View.VISIBLE
    }

    private fun showHomePage() {
        binding.webviewContainer.visibility = View.GONE
        homePageController.show(binding.homeContainer)
    }

    private fun hideHomePage() {
        homePageController.hide(binding.homeContainer)
        binding.webviewContainer.visibility = View.VISIBLE
    }

    private fun activeWebView(): OrcaWebView? {
        val activeTab = tabManager.getActiveTab() ?: return null
        return tabManager.getWebViewForTab(activeTab.id)
    }

    private fun refreshTabStrip() {
        tabStripAdapter.submitList(tabManager.getTabsByMode(currentMode))
    }

    private fun setLoadingUi(loading: Boolean) {
        isCurrentPageLoading = loading
        binding.btnReload.setImageResource(
            if (loading) R.drawable.ic_stop_loading else R.drawable.ic_reload
        )
        if (!loading) {
            binding.progressBar.visibility = View.GONE
        }
    }

    private fun updateNavigationButtons(canGoBack: Boolean, canGoForward: Boolean) {
        val enabledColor = ContextCompat.getColor(this, R.color.textPrimary)
        val disabledColor = ContextCompat.getColor(this, R.color.colorDivider)

        binding.btnBack.isEnabled = canGoBack
        binding.btnBack.setColorFilter(if (canGoBack) enabledColor else disabledColor)

        binding.btnForward.isEnabled = canGoForward
        binding.btnForward.setColorFilter(if (canGoForward) enabledColor else disabledColor)
    }

    private fun showKeyboard(target: View) {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager ?: return
        target.post {
            imm.showSoftInput(target, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    private fun hideKeyboard(target: View) {
        val token = target.windowToken ?: return
        val imm = getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager ?: return
        try {
            imm.hideSoftInputFromWindow(token, 0)
        } catch (_: Exception) {
            // Window token no longer attached
        }
    }

    companion object {
        private const val TAG = "OrcaMainActivity"
        private const val BLANK_URL = "about:blank"
        private const val TAG_TAB_OVERVIEW = "tag_tab_overview"
    }
}
