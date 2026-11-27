package com.orca.browser.ui.overview

import android.content.res.Configuration
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.GridLayoutManager
import com.orca.browser.MainActivity
import com.orca.browser.R
import com.orca.browser.browser.TabManager
import com.orca.browser.data.model.BrowsingMode
import com.orca.browser.databinding.FragmentTabOverviewBinding
import com.orca.browser.databinding.ItemSidebarEntryBinding
import com.orca.browser.ui.history.HistoryFragment

class TabOverviewFragment : Fragment(), HistoryFragment.HistoryHost {

    private var _binding: FragmentTabOverviewBinding? = null
    private val binding get() = _binding!!

    private val tabManager = TabManager.getInstance()
    private val tabCardAdapter = TabCardAdapter()

    private var selectedMode: BrowsingMode = BrowsingMode.NORMAL
    private var showingHistory: Boolean = false
    private var searchQuery: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val modeName = arguments?.getString(ARG_MODE) ?: BrowsingMode.NORMAL.name
        selectedMode = try {
            BrowsingMode.valueOf(modeName)
        } catch (_: IllegalArgumentException) {
            BrowsingMode.NORMAL
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentTabOverviewBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupSidebar()
        setupTabGrid()
        setupSearchAndButtons()
        updateSidebarSelection()
        refreshTabs()
    }

    override fun onResume() {
        super.onResume()
        updateGridSpanCount(resources.configuration.orientation)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        updateGridSpanCount(newConfig.orientation)
    }

    fun updateGridSpanCount(orientation: Int) {
        val layoutManager = _binding?.rvTabGrid?.layoutManager as? GridLayoutManager ?: return
        layoutManager.spanCount = gridSpanCountForOrientation(orientation)
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }

    override fun onHistoryUrlClicked(url: String) {
        (activity as? MainActivity)?.openUrlFromHistory(url)
    }

    override fun onHistoryBackClicked() {
        showTabGridForMode(selectedMode, switchActivityMode = false)
    }

    fun handleBackPressed(): Boolean {
        if (showingHistory) {
            showTabGridForMode(selectedMode, switchActivityMode = false)
            return true
        }
        return false
    }

    fun refreshTabs() {
        val currentBinding = _binding ?: return
        val modeTabs = tabManager.getTabsByMode(selectedMode)
        val filtered = if (searchQuery.isBlank()) {
            modeTabs
        } else {
            val q = searchQuery.trim().lowercase()
            modeTabs.filter { tab ->
                tab.title.lowercase().contains(q) || tab.url.lowercase().contains(q)
            }
        }

        tabCardAdapter.activeTabId = tabManager.getActiveTab()?.id.orEmpty()
        tabCardAdapter.submitList(filtered)

        val showEmpty = filtered.isEmpty()
        currentBinding.emptyTabsState.visibility = if (showEmpty) View.VISIBLE else View.GONE
        currentBinding.rvTabGrid.visibility = if (showEmpty) View.GONE else View.VISIBLE

        updateSidebarBadges()
    }

    private fun setupSidebar() {
        configureSidebarRow(
            row = binding.sidebarNormal,
            iconRes = R.drawable.ic_normal,
            label = "Normal",
            showBadge = true
        )
        configureSidebarRow(
            row = binding.sidebarIncognito,
            iconRes = R.drawable.ic_incognito,
            label = "Incognito",
            showBadge = true
        )
        configureSidebarRow(
            row = binding.sidebarTor,
            iconRes = R.drawable.ic_tor,
            label = "Tor",
            showBadge = true
        )
        configureSidebarRow(
            row = binding.sidebarHistory,
            iconRes = R.drawable.ic_history,
            label = getString(R.string.history),
            showBadge = false
        )
        configureSidebarRow(
            row = binding.sidebarSettings,
            iconRes = R.drawable.ic_settings,
            label = getString(R.string.settings),
            showBadge = false
        )

        binding.sidebarNormal.root.setOnClickListener {
            showTabGridForMode(BrowsingMode.NORMAL, switchActivityMode = true)
        }
        binding.sidebarIncognito.root.setOnClickListener {
            showTabGridForMode(BrowsingMode.INCOGNITO, switchActivityMode = true)
        }
        binding.sidebarTor.root.setOnClickListener {
            showTabGridForMode(BrowsingMode.TOR, switchActivityMode = true)
        }
        binding.sidebarHistory.root.setOnClickListener {
            showHistoryPanel()
        }
        binding.sidebarSettings.root.setOnClickListener {
            (activity as? MainActivity)?.openSettingsScreen()
        }
    }

    private fun configureSidebarRow(
        row: ItemSidebarEntryBinding,
        iconRes: Int,
        label: String,
        showBadge: Boolean
    ) {
        row.ivSidebarIcon.setImageResource(iconRes)
        row.tvSidebarLabel.text = label
        row.tvSidebarBadge.visibility = if (showBadge) View.VISIBLE else View.GONE
    }

    private fun setupTabGrid() {
        tabCardAdapter.onTabClicked = { tabId ->
            val hostActivity = activity as? MainActivity
            if (hostActivity != null) {
                hostActivity.selectTabFromOverview(tabId)
            } else {
                tabManager.switchToTab(tabId, requireContext())
                dismissSelf()
            }
        }

        tabCardAdapter.onTabClosed = { tabId ->
            tabManager.closeTab(tabId)
            refreshTabs()
        }

        val spanCount = gridSpanCountForOrientation(resources.configuration.orientation)
        binding.rvTabGrid.apply {
            layoutManager = GridLayoutManager(requireContext(), spanCount)
            adapter = tabCardAdapter
        }
    }

    private fun setupSearchAndButtons() {
        binding.etSearchTabs.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                searchQuery = s?.toString().orEmpty()
                refreshTabs()
            }
        })

        val createNewTabAction = View.OnClickListener {
            val hostActivity = activity as? MainActivity
            if (hostActivity != null) {
                hostActivity.createNewTabFromOverview(selectedMode)
            } else {
                tabManager.createTab("about:blank", selectedMode, requireContext())
                dismissSelf()
            }
        }

        binding.btnOverviewNewTab.setOnClickListener(createNewTabAction)
        binding.btnEmptyNewTab.setOnClickListener(createNewTabAction)

        binding.btnDone.setOnClickListener {
            dismissSelf()
        }
    }

    private fun showTabGridForMode(mode: BrowsingMode, switchActivityMode: Boolean) {
        showingHistory = false
        selectedMode = mode

        binding.overviewHistoryContainer.visibility = View.GONE
        binding.tabGridPanel.visibility = View.VISIBLE

        if (switchActivityMode) {
            (activity as? MainActivity)?.switchBrowsingMode(mode)
        }

        updateSidebarSelection()
        refreshTabs()
    }

    private fun showHistoryPanel() {
        showingHistory = true
        updateSidebarSelection()

        binding.tabGridPanel.visibility = View.GONE
        binding.overviewHistoryContainer.visibility = View.VISIBLE

        val existing = childFragmentManager.findFragmentByTag(TAG_HISTORY_FRAGMENT) as? HistoryFragment
        if (existing == null) {
            val historyFragment = HistoryFragment().apply {
                onEntrySelected = { url ->
                    (activity as? MainActivity)?.openUrlFromHistory(url)
                }
                onBackClicked = {
                    showTabGridForMode(selectedMode, switchActivityMode = false)
                }
            }
            childFragmentManager.beginTransaction()
                .replace(R.id.overview_history_container, historyFragment, TAG_HISTORY_FRAGMENT)
                .commit()
        }
    }

    private fun updateSidebarSelection() {
        val normalSelected = !showingHistory && selectedMode == BrowsingMode.NORMAL
        val incognitoSelected = !showingHistory && selectedMode == BrowsingMode.INCOGNITO
        val torSelected = !showingHistory && selectedMode == BrowsingMode.TOR
        val historySelected = showingHistory

        applySidebarRowSelected(binding.sidebarNormal, normalSelected, R.color.colorAccent)
        applySidebarRowSelected(binding.sidebarIncognito, incognitoSelected, R.color.colorIncognitoAccent)
        applySidebarRowSelected(binding.sidebarTor, torSelected, R.color.colorTor)
        applySidebarRowSelected(binding.sidebarHistory, historySelected, R.color.colorAccent)
        applySidebarRowSelected(binding.sidebarSettings, false, R.color.colorAccent)
    }

    private fun applySidebarRowSelected(
        row: ItemSidebarEntryBinding,
        selected: Boolean,
        accentColorRes: Int
    ) {
        val context = requireContext()
        if (selected) {
            row.root.setBackgroundResource(R.drawable.bg_sidebar_item_selected)
            val accentColor = ContextCompat.getColor(context, accentColorRes)
            row.ivSidebarIcon.setColorFilter(accentColor)
            row.tvSidebarLabel.setTextColor(ContextCompat.getColor(context, R.color.textPrimary))
        } else {
            val outValue = TypedValue()
            context.theme.resolveAttribute(android.R.attr.selectableItemBackground, outValue, true)
            row.root.setBackgroundResource(outValue.resourceId)
            val defaultColor = ContextCompat.getColor(context, R.color.textPrimary)
            row.ivSidebarIcon.setColorFilter(defaultColor)
            row.tvSidebarLabel.setTextColor(defaultColor)
        }
    }

    private fun updateSidebarBadges() {
        binding.sidebarNormal.tvSidebarBadge.text =
            tabManager.getTabCount(BrowsingMode.NORMAL).toString()
        binding.sidebarIncognito.tvSidebarBadge.text =
            tabManager.getTabCount(BrowsingMode.INCOGNITO).toString()
        binding.sidebarTor.tvSidebarBadge.text =
            tabManager.getTabCount(BrowsingMode.TOR).toString()
    }

    private fun gridSpanCountForOrientation(orientation: Int): Int {
        return if (orientation == Configuration.ORIENTATION_LANDSCAPE) 3 else 2
    }

    private fun dismissSelf() {
        val hostActivity = activity as? MainActivity
        if (hostActivity != null) {
            hostActivity.closeTabOverview()
        } else {
            parentFragmentManager.beginTransaction().remove(this).commitAllowingStateLoss()
        }
    }

    companion object {
        private const val ARG_MODE = "arg_browsing_mode"
        private const val TAG_HISTORY_FRAGMENT = "tag_history_fragment"

        fun newInstance(mode: BrowsingMode): TabOverviewFragment {
            return TabOverviewFragment().apply {
                arguments = Bundle().apply {
                    putString(ARG_MODE, mode.name)
                }
            }
        }
    }
}
