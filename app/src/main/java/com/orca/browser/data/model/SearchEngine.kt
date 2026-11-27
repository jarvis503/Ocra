package com.orca.browser.data.model

import android.net.Uri

data class SearchEngine(
    val key: String,
    val displayName: String,
    val searchUrlTemplate: String,
    val homepageUrl: String
) {
    fun buildSearchUrl(query: String): String {
        return searchUrlTemplate.replace("%s", Uri.encode(query))
    }

    companion object {
        val GOOGLE = SearchEngine(
            key = "google",
            displayName = "Google",
            searchUrlTemplate = "https://www.google.com/search?q=%s",
            homepageUrl = "https://www.google.com"
        )

        val BRAVE = SearchEngine(
            key = "brave",
            displayName = "Brave",
            searchUrlTemplate = "https://search.brave.com/search?q=%s",
            homepageUrl = "https://search.brave.com"
        )

        val DUCKDUCKGO = SearchEngine(
            key = "duckduckgo",
            displayName = "DuckDuckGo",
            searchUrlTemplate = "https://duckduckgo.com/?q=%s",
            homepageUrl = "https://duckduckgo.com"
        )

        val BING = SearchEngine(
            key = "bing",
            displayName = "Bing",
            searchUrlTemplate = "https://www.bing.com/search?q=%s",
            homepageUrl = "https://www.bing.com"
        )

        val STARTPAGE = SearchEngine(
            key = "startpage",
            displayName = "Startpage",
            searchUrlTemplate = "https://www.startpage.com/do/search?q=%s",
            homepageUrl = "https://www.startpage.com"
        )

        private val ALL_ENGINES = listOf(
            GOOGLE,
            BRAVE,
            DUCKDUCKGO,
            BING,
            STARTPAGE
        )

        fun getByKey(key: String): SearchEngine {
            return ALL_ENGINES.firstOrNull { it.key.equals(key, ignoreCase = true) } ?: GOOGLE
        }

        fun getAllEngines(): List<SearchEngine> = ALL_ENGINES
    }
}
