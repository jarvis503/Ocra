package com.orca.browser.browser

import android.net.Uri

object AuthDomainWhitelist {

    private val WHITELISTED_DOMAINS = setOf(
        "accounts.google.com",
        "accounts.youtube.com",
        "myaccount.google.com",
        "oauth2.googleapis.com",
        "www.googleapis.com",
        "play.google.com",
        "login.microsoftonline.com",
        "login.live.com",
        "github.com",
        "api.twitter.com",
        "appleid.apple.com"
    )

    fun isAuthDomain(url: String): Boolean {
        if (url.isBlank()) return false
        val host = try {
            Uri.parse(url).host?.lowercase()
        } catch (_: Exception) {
            null
        } ?: return false

        if (host in WHITELISTED_DOMAINS) return true
        return WHITELISTED_DOMAINS.any { domain -> host.endsWith(".$domain") }
    }
}
