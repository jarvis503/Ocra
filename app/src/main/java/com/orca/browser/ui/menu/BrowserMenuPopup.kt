package com.orca.browser.ui.menu

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.PopupWindow
import androidx.core.content.ContextCompat
import com.google.android.material.switchmaterial.SwitchMaterial
import com.orca.browser.R
import com.orca.browser.data.prefs.PrefsManager
import com.orca.browser.databinding.PopupBrowserMenuBinding

class BrowserMenuPopup(
    private val context: Context,
    private val defaultAnchor: View? = null
) {

    constructor(anchor: View, context: Context) : this(context = context, defaultAnchor = anchor)

    private val binding = PopupBrowserMenuBinding.inflate(LayoutInflater.from(context))
    private val prefs = PrefsManager.getInstance(context)
    private val widthPx = (POPUP_WIDTH_DP * context.resources.displayMetrics.density).toInt()
    private val popupWindow: PopupWindow

    var onDesktopModeToggled: ((Boolean) -> Unit)? = null
    var onJavaScriptToggled: ((Boolean) -> Unit)? = null
    var onFindInPage: (() -> Unit)? = null
    var onShare: (() -> Unit)? = null
    var onSettings: (() -> Unit)? = null
    var onExit: (() -> Unit)? = null

    init {
        popupWindow = PopupWindow(
            binding.root,
            widthPx,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            true
        ).apply {
            isOutsideTouchable = true
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            elevation = ELEVATION_DP * context.resources.displayMetrics.density
            animationStyle = android.R.style.Animation_Dialog
        }

        configureSwitches()
        configureClickListeners()
    }

    fun show(anchor: View) {
        val xOffset = anchor.width - widthPx
        popupWindow.showAsDropDown(anchor, xOffset, 0, Gravity.START)
    }

    fun show() {
        val anchor = defaultAnchor ?: return
        show(anchor)
    }

    fun dismiss() {
        if (popupWindow.isShowing) {
            popupWindow.dismiss()
        }
    }

    private fun configureSwitches() {
        styleSwitch(binding.switchDesktop)
        styleSwitch(binding.switchJs)

        binding.switchDesktop.isChecked = prefs.isDesktopMode
        binding.switchJs.isChecked = prefs.isJavaScriptEnabled

        binding.switchDesktop.setOnCheckedChangeListener { _, isChecked ->
            onDesktopModeToggled?.invoke(isChecked)
            dismiss()
        }

        binding.switchJs.setOnCheckedChangeListener { _, isChecked ->
            onJavaScriptToggled?.invoke(isChecked)
            dismiss()
        }
    }

    private fun configureClickListeners() {
        binding.menuDesktopMode.setOnClickListener {
            binding.switchDesktop.toggle()
        }

        binding.menuJavascript.setOnClickListener {
            binding.switchJs.toggle()
        }

        binding.menuFind.setOnClickListener {
            onFindInPage?.invoke()
            dismiss()
        }

        binding.menuShare.setOnClickListener {
            onShare?.invoke()
            dismiss()
        }

        binding.menuSettings.setOnClickListener {
            onSettings?.invoke()
            dismiss()
        }

        binding.menuExit.setOnClickListener {
            onExit?.invoke()
            dismiss()
        }
    }

    private fun styleSwitch(switchView: SwitchMaterial) {
        val accent = ContextCompat.getColor(context, R.color.colorAccent)
        val divider = ContextCompat.getColor(context, R.color.colorDivider)
        val thumbOff = ContextCompat.getColor(context, R.color.colorTextSecondary)
        val trackOn = Color.parseColor("#004080")

        val states = arrayOf(
            intArrayOf(android.R.attr.state_checked),
            intArrayOf(-android.R.attr.state_checked)
        )

        switchView.thumbTintList = ColorStateList(states, intArrayOf(accent, thumbOff))
        switchView.trackTintList = ColorStateList(states, intArrayOf(trackOn, divider))
    }

    companion object {
        private const val POPUP_WIDTH_DP = 260
        private const val ELEVATION_DP = 8f
    }
}
