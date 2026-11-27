package com.orca.browser.ui.home

import android.graphics.Color

data class HomeShortcut(
    val name: String,
    val url: String,
    val letter: String,
    val color: Int
) {
    companion object {
        val DEFAULT_SHORTCUTS = listOf(
            HomeShortcut(
                name = "Google",
                url = "https://www.google.com",
                letter = "G",
                color = Color.parseColor("#4285F4")
            ),
            HomeShortcut(
                name = "YouTube",
                url = "https://www.youtube.com",
                letter = "Y",
                color = Color.parseColor("#FF0000")
            ),
            HomeShortcut(
                name = "Wikipedia",
                url = "https://www.wikipedia.org",
                letter = "W",
                color = Color.parseColor("#636466")
            ),
            HomeShortcut(
                name = "Reddit",
                url = "https://www.reddit.com",
                letter = "R",
                color = Color.parseColor("#FF4500")
            ),
            HomeShortcut(
                name = "Twitter/X",
                url = "https://x.com",
                letter = "X",
                color = Color.parseColor("#1DA1F2")
            ),
            HomeShortcut(
                name = "GitHub",
                url = "https://github.com",
                letter = "G",
                color = Color.parseColor("#8B5CF6")
            )
        )
    }
}
