package com.orca.browser.ui.home

import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import androidx.recyclerview.widget.GridLayoutManager
import com.orca.browser.databinding.ViewHomePageBinding

class HomePageController(
    private val container: FrameLayout? = null
) {

    private var binding: ViewHomePageBinding? = null
    private var activeContainer: FrameLayout? = container
    private val shortcutAdapter = ShortcutAdapter()

    var onNavigate: ((String) -> Unit)? = null
    var onSearchBarClicked: (() -> Unit)? = null

    fun show(targetContainer: FrameLayout) {
        activeContainer = targetContainer
        val currentBinding = ensureInflated(targetContainer)
        currentBinding.root.visibility = View.VISIBLE
        targetContainer.visibility = View.VISIBLE
    }

    fun show() {
        val target = activeContainer ?: return
        show(target)
    }

    fun hide(targetContainer: FrameLayout) {
        activeContainer = targetContainer
        targetContainer.visibility = View.GONE
    }

    fun hide() {
        activeContainer?.visibility = View.GONE
    }

    fun isVisible(): Boolean {
        return activeContainer?.visibility == View.VISIBLE
    }

    private fun ensureInflated(targetContainer: FrameLayout): ViewHomePageBinding {
        val existing = binding
        if (existing != null && existing.root.parent === targetContainer) {
            return existing
        }

        targetContainer.removeAllViews()
        val inflated = ViewHomePageBinding.inflate(
            LayoutInflater.from(targetContainer.context),
            targetContainer,
            false
        )

        shortcutAdapter.onShortcutClicked = { url ->
            onNavigate?.invoke(url)
        }

        inflated.rvShortcuts.apply {
            layoutManager = GridLayoutManager(targetContainer.context, SHORTCUT_COLUMNS)
            adapter = shortcutAdapter
        }

        inflated.homeSearchBar.setOnClickListener {
            onSearchBarClicked?.invoke()
        }

        targetContainer.addView(inflated.root)
        binding = inflated
        return inflated
    }

    companion object {
        private const val SHORTCUT_COLUMNS = 4
    }
}
