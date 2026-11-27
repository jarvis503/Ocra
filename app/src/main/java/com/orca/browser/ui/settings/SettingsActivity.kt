package com.orca.browser.ui.settings

import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import android.text.InputType
import android.webkit.CookieManager
import android.webkit.WebStorage
import android.webkit.WebView
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.orca.browser.R
import com.orca.browser.data.db.OrcaDatabase
import com.orca.browser.data.model.SearchEngine
import com.orca.browser.data.prefs.PrefsManager
import com.orca.browser.databinding.ActivitySettingsBinding
import com.orca.browser.tor.EmbeddedTorManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private lateinit var prefs: PrefsManager

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(R.style.AppTheme)
        super.onCreate(savedInstanceState)

        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val blackColor = ContextCompat.getColor(this, R.color.colorPrimary)
        window.statusBarColor = blackColor
        window.navigationBarColor = blackColor

        prefs = PrefsManager.getInstance(this)

        binding.btnSettingsBack.setOnClickListener {
            finish()
        }

        setupSearchEngineSection()
        setupPrivacySection()
    }

    private fun setupSearchEngineSection() {
        val checkedId = when (prefs.defaultSearchEngine.lowercase()) {
            SearchEngine.BRAVE.key -> R.id.rb_brave
            SearchEngine.DUCKDUCKGO.key -> R.id.rb_duckduckgo
            SearchEngine.BING.key -> R.id.rb_bing
            SearchEngine.STARTPAGE.key -> R.id.rb_startpage
            else -> R.id.rb_google
        }
        binding.rgSearchEngine.check(checkedId)

        binding.rgSearchEngine.setOnCheckedChangeListener { _, id ->
            val engineKey = when (id) {
                R.id.rb_brave -> SearchEngine.BRAVE.key
                R.id.rb_duckduckgo -> SearchEngine.DUCKDUCKGO.key
                R.id.rb_bing -> SearchEngine.BING.key
                R.id.rb_startpage -> SearchEngine.STARTPAGE.key
                else -> SearchEngine.GOOGLE.key
            }
            prefs.defaultSearchEngine = engineKey
        }
    }

    private fun setupPrivacySection() {
        binding.settingHistoryToggle.tvSettingTitle.text = "Save Browsing History"
        binding.settingHistoryToggle.tvSettingSubtitle.text =
            "Only in Normal mode. Never in Incognito or Tor."
        styleSwitch()
        binding.settingHistoryToggle.switchSetting.isChecked = prefs.isHistoryEnabled
        binding.settingHistoryToggle.switchSetting.setOnCheckedChangeListener { _, isChecked ->
            prefs.isHistoryEnabled = isChecked
        }
        binding.settingHistoryToggle.root.setOnClickListener {
            binding.settingHistoryToggle.switchSetting.toggle()
        }

        binding.settingClearHistory.tvActionTitle.text = "Clear Browsing History"
        binding.settingClearHistory.tvActionSubtitle.text = "Delete all saved history"
        binding.settingClearHistory.root.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Clear Browsing History")
                .setMessage("Clear all browsing history?")
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton("Clear") { _, _ ->
                    lifecycleScope.launch(Dispatchers.IO) {
                        OrcaDatabase.getInstance(applicationContext).historyDao().clearAllHistory()
                    }
                    Toast.makeText(this, "History cleared", Toast.LENGTH_SHORT).show()
                }
                .show()
        }

        binding.settingClearCookies.tvActionTitle.text = "Clear Cookies & Site Data"
        binding.settingClearCookies.tvActionSubtitle.text = "Removes login sessions"
        binding.settingClearCookies.root.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Clear Cookies & Site Data")
                .setMessage("Remove all cookies and site storage?")
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton("Clear") { _, _ ->
                    val cookieManager = CookieManager.getInstance()
                    cookieManager.removeAllCookies(null)
                    cookieManager.flush()
                    WebStorage.getInstance().deleteAllData()
                    Toast.makeText(this, "Cookies cleared", Toast.LENGTH_SHORT).show()
                }
                .show()
        }

        setupTorBridges()

        binding.settingClearCache.tvActionTitle.text = "Clear Cache"
        binding.settingClearCache.tvActionSubtitle.text = "Free up storage space"
        binding.settingClearCache.root.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Clear Cache")
                .setMessage("Clear cached web files?")
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton("Clear") { _, _ ->
                    val tempWebView = WebView(this)
                    tempWebView.clearCache(true)
                    tempWebView.destroy()
                    Toast.makeText(this, "Cache cleared", Toast.LENGTH_SHORT).show()
                }
                .show()
        }
    }

    private fun setupTorBridges() {
        fun refreshSubtitle() {
            val count = prefs.torBridges.lines().count {
                it.isNotBlank() && !it.trim().startsWith("#")
            }
            binding.settingTorBridges.tvActionSubtitle.text =
                if (count == 0) "Off. Tap to add custom Tor bridges"
                else "$count custom bridge(s) saved"
        }

        binding.settingTorBridges.tvActionTitle.text = "Tor Bridges"
        refreshSubtitle()
        binding.settingTorBridges.root.setOnClickListener {
            val input = EditText(this).apply {
                setText(prefs.torBridges)
                hint = "One bridge per line (vanilla, obfs4, meek_lite, or snowflake)"
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
                minLines = 4
                setTextColor(Color.WHITE)
                setHintTextColor(Color.GRAY)
                setPadding(48, 32, 48, 32)
            }
            AlertDialog.Builder(this)
                .setTitle("Tor Bridges")
                .setMessage(
                    "Paste custom Tor bridge lines (vanilla, obfs4, meek_lite, or snowflake) from " +
                        "bridges.torproject.org. Leave empty to use the connection dialog selection."
                )
                .setView(input)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton("Save") { _, _ ->
                    val text = input.text.toString()
                    val problem = EmbeddedTorManager.validateBridges(text)
                    if (problem != null) {
                        Toast.makeText(this, problem, Toast.LENGTH_LONG).show()
                    } else {
                        prefs.torBridges = text.trim()
                        EmbeddedTorManager.stop()
                        refreshSubtitle()
                        Toast.makeText(this, "Bridges saved", Toast.LENGTH_SHORT).show()
                    }
                }
                .show()
        }
    }

    private fun styleSwitch() {
        val accent = ContextCompat.getColor(this, R.color.colorAccent)
        val divider = ContextCompat.getColor(this, R.color.colorDivider)
        val thumbOff = ContextCompat.getColor(this, R.color.colorTextSecondary)
        val trackOn = Color.parseColor("#004080")

        val states = arrayOf(
            intArrayOf(android.R.attr.state_checked),
            intArrayOf(-android.R.attr.state_checked)
        )

        binding.settingHistoryToggle.switchSetting.thumbTintList =
            ColorStateList(states, intArrayOf(accent, thumbOff))
        binding.settingHistoryToggle.switchSetting.trackTintList =
            ColorStateList(states, intArrayOf(trackOn, divider))
    }
}
