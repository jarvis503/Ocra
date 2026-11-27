package com.orca.browser.data.prefs

import android.content.Context
import android.content.SharedPreferences
import com.orca.browser.data.model.BrowsingMode

class PrefsManager private constructor(context: Context) {

    private val prefs: SharedPreferences = context.applicationContext.getSharedPreferences(
        PREFS_NAME,
        Context.MODE_PRIVATE
    )

    var defaultSearchEngine: String
        get() = prefs.getString(KEY_DEFAULT_SEARCH_ENGINE, DEFAULT_SEARCH_ENGINE)
            ?: DEFAULT_SEARCH_ENGINE
        set(value) {
            prefs.edit().putString(KEY_DEFAULT_SEARCH_ENGINE, value).apply()
        }

    var isHistoryEnabled: Boolean
        get() = prefs.getBoolean(KEY_HISTORY_ENABLED, true)
        set(value) {
            prefs.edit().putBoolean(KEY_HISTORY_ENABLED, value).apply()
        }

    var isDesktopMode: Boolean
        get() = prefs.getBoolean(KEY_DESKTOP_MODE, false)
        set(value) {
            prefs.edit().putBoolean(KEY_DESKTOP_MODE, value).apply()
        }

    var isJavaScriptEnabled: Boolean
        get() = prefs.getBoolean(KEY_JAVASCRIPT_ENABLED, true)
        set(value) {
            prefs.edit().putBoolean(KEY_JAVASCRIPT_ENABLED, value).apply()
        }

    var homepageUrl: String
        get() = prefs.getString(KEY_HOMEPAGE_URL, DEFAULT_HOMEPAGE_URL) ?: DEFAULT_HOMEPAGE_URL
        set(value) {
            prefs.edit().putString(KEY_HOMEPAGE_URL, value).apply()
        }

    var lastBrowsingMode: String
        get() = prefs.getString(KEY_LAST_BROWSING_MODE, BrowsingMode.NORMAL.name)
            ?: BrowsingMode.NORMAL.name
        set(value) {
            prefs.edit().putString(KEY_LAST_BROWSING_MODE, value).apply()
        }

    var torBridges: String
        get() = prefs.getString(KEY_TOR_BRIDGES, "") ?: ""
        set(value) {
            prefs.edit().putString(KEY_TOR_BRIDGES, value).apply()
        }

    fun getBrowsingMode(): BrowsingMode {
        return try {
            BrowsingMode.valueOf(lastBrowsingMode)
        } catch (_: IllegalArgumentException) {
            BrowsingMode.NORMAL
        }
    }

    fun setBrowsingMode(mode: BrowsingMode) {
        lastBrowsingMode = mode.name
    }

    companion object {
        private const val PREFS_NAME = "orca_prefs"
        private const val KEY_DEFAULT_SEARCH_ENGINE = "default_search_engine"
        private const val KEY_HISTORY_ENABLED = "history_enabled"
        private const val KEY_DESKTOP_MODE = "desktop_mode"
        private const val KEY_JAVASCRIPT_ENABLED = "javascript_enabled"
        private const val KEY_HOMEPAGE_URL = "homepage_url"
        private const val KEY_LAST_BROWSING_MODE = "last_browsing_mode"
        private const val KEY_TOR_BRIDGES = "tor_bridges"

        private const val DEFAULT_SEARCH_ENGINE = "google"
        private const val DEFAULT_HOMEPAGE_URL = "about:blank"

        @Volatile
        private var INSTANCE: PrefsManager? = null

        fun getInstance(context: Context): PrefsManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: PrefsManager(context.applicationContext).also { INSTANCE = it }
            }
        }
    }
}
