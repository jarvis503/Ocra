package com.orca.browser.adblocker

import android.content.Context
import android.net.Uri
import com.orca.browser.browser.AuthDomainWhitelist
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

object AdBlocker {

    private val loaderScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val pageBlockCounts = ConcurrentHashMap<String, AtomicInteger>()

    @Volatile
    private var blockedHosts: HashSet<String>? = null

    @Volatile
    private var cachedCosmeticScript: String? = null

    @Volatile
    private var cachedYouTubeScript: String? = null

    @Volatile
    private var isInitialized = false

    private val BLOCKED_PATH_PATTERNS = listOf(
        "/pagead/",
        "/ad/",
        "/ads/",
        "/adserv",
        "/doubleclick/"
    )

    private val ALWAYS_BLOCKED_PATH_PATTERNS = listOf(
        "/pagead/",
        "/adserv",
        "/doubleclick/"
    )

    private val BLOCKED_AD_NETWORK_HOSTS = listOf(
        "googlesyndication",
        "googleads",
        "adservice",
        "amazon-adsystem.com",
        "moat.com",
        "adsafeprotected"
    )

    private val AD_SUBDOMAIN_PREFIXES = listOf(
        "ad.",
        "ads.",
        "adserver."
    )

    private val YOUTUBE_AD_HOSTS = setOf(
        "googleads.g.doubleclick.net",
        "static.doubleclick.net",
        "ad.doubleclick.net",
        "pubads.g.doubleclick.net",
        "s0.2mdn.net",
        "ads.youtube.com",
        "ad.youtube.com",
        "ads.yt.be",
        "imasdk.googleapis.com"
    )

    private val YOUTUBE_AD_PATH_PATTERNS = listOf(
        "/api/stats/ads",
        "/pagead/",
        "/ptracking",
        "/get_midroll_"
    )

    fun initialize(context: Context) {
        if (blockedHosts != null && cachedCosmeticScript != null && cachedYouTubeScript != null) {
            return
        }
        synchronized(this) {
            if (isInitialized && blockedHosts != null) return
            isInitialized = true
        }

        val appContext = context.applicationContext
        loaderScope.launch {
            loadBlockedHosts(appContext)
            loadAssetScript(appContext, "adblock_cosmetic.js")?.let {
                cachedCosmeticScript = it
            }
            loadAssetScript(appContext, "adblock_youtube.js")?.let {
                cachedYouTubeScript = it
            }
        }
    }

    fun isBlocked(url: String): Boolean {
        if (url.isBlank()) return false
        if (AuthDomainWhitelist.isAuthDomain(url)) return false

        val uri = parseUriSafely(url) ?: return false
        val host = uri.host?.lowercase().orEmpty()
        if (host.isEmpty()) return false

        if (isAdSubdomainOrNetworkHost(host)) return true

        val hosts = blockedHosts ?: return false
        if (hosts.contains(host)) return true

        var candidate = host
        for (level in 0 until 2) {
            val dotIndex = candidate.indexOf('.')
            if (dotIndex == -1 || dotIndex == candidate.lastIndex) break
            candidate = candidate.substring(dotIndex + 1)
            if (hosts.contains(candidate)) return true
        }

        return false
    }

    fun isYouTubeAd(url: String): Boolean {
        if (url.isBlank()) return false
        if (AuthDomainWhitelist.isAuthDomain(url)) return false

        val uri = parseUriSafely(url) ?: return false
        val host = uri.host?.lowercase().orEmpty()
        val path = uri.encodedPath?.lowercase().orEmpty()
        val query = uri.encodedQuery?.lowercase().orEmpty()

        if (host in YOUTUBE_AD_HOSTS ||
            host.endsWith(".doubleclick.net") ||
            host == "doubleclick.net" ||
            host.endsWith(".2mdn.net") ||
            host == "2mdn.net"
        ) {
            return true
        }

        val isYouTubeHost = host == "youtube.com" ||
            host.endsWith(".youtube.com") ||
            host == "youtube-nocookie.com" ||
            host.endsWith(".youtube-nocookie.com")

        if (isYouTubeHost) {
            if (host.endsWith("youtube-nocookie.com") && path.startsWith("/ads")) {
                return true
            }
            if (YOUTUBE_AD_PATH_PATTERNS.any { pattern -> path.contains(pattern) }) {
                return true
            }
            if (path.contains("/api/stats/qoe") && query.contains("adformat")) {
                return true
            }
            if (path.contains("/get_video_info") &&
                (query.contains("ad_") || query.contains("adformat="))
            ) {
                return true
            }
        }

        if (host == "youtubei.googleapis.com" &&
            path.contains("/youtubei/v1/player/ad_break")
        ) {
            return true
        }

        return false
    }

    fun shouldBlockResource(url: String): Boolean {
        return shouldBlockResource(url, isMainFrame = false)
    }

    fun shouldBlockResource(url: String, isMainFrame: Boolean): Boolean {
        if (url.isBlank()) return false
        if (AuthDomainWhitelist.isAuthDomain(url)) return false
        if (isBlocked(url) || isYouTubeAd(url)) return true

        val uri = parseUriSafely(url)
        val path = uri?.encodedPath?.lowercase().orEmpty()
        val lowerUrl = url.lowercase()

        if (lowerUrl.contains("t.co/tracking")) return true

        val pathPatterns = if (isMainFrame) {
            ALWAYS_BLOCKED_PATH_PATTERNS
        } else {
            BLOCKED_PATH_PATTERNS
        }
        return pathPatterns.any { pattern -> path.contains(pattern) }
    }

    fun getAdBlockScript(): String {
        return cachedCosmeticScript ?: DEFAULT_COSMETIC_SCRIPT
    }

    fun getAdBlockScript(context: Context): String {
        cachedCosmeticScript?.let { return it }
        val loaded = loadAssetScript(context.applicationContext, "adblock_cosmetic.js")
        if (loaded != null) {
            cachedCosmeticScript = loaded
            return loaded
        }
        return DEFAULT_COSMETIC_SCRIPT
    }

    fun getYouTubeAdBlockScript(): String {
        return cachedYouTubeScript ?: DEFAULT_YOUTUBE_SCRIPT
    }

    fun getYouTubeAdBlockScript(context: Context): String {
        cachedYouTubeScript?.let { return it }
        val loaded = loadAssetScript(context.applicationContext, "adblock_youtube.js")
        if (loaded != null) {
            cachedYouTubeScript = loaded
            return loaded
        }
        return DEFAULT_YOUTUBE_SCRIPT
    }

    fun incrementBlockCount(tabId: String) {
        val counter = pageBlockCounts[tabId] ?: pageBlockCounts.putIfAbsent(tabId, AtomicInteger(0))
            ?: pageBlockCounts[tabId]
        counter?.incrementAndGet()
    }

    fun getBlockCount(tabId: String): Int {
        return pageBlockCounts[tabId]?.get() ?: 0
    }

    fun resetBlockCount(tabId: String) {
        pageBlockCounts[tabId] = AtomicInteger(0)
    }

    fun clearAllCounts() {
        pageBlockCounts.clear()
    }

    private fun isAdSubdomainOrNetworkHost(host: String): Boolean {
        if (BLOCKED_AD_NETWORK_HOSTS.any { token -> host.contains(token) }) {
            return true
        }
        return AD_SUBDOMAIN_PREFIXES.any { prefix ->
            host.startsWith(prefix) || host.contains(".$prefix")
        }
    }

    private fun parseUriSafely(url: String): Uri? {
        return try {
            Uri.parse(url)
        } catch (_: Exception) {
            null
        }
    }

    private fun loadBlockedHosts(appContext: Context) {
        val loaded = HashSet<String>(4096)
        try {
            appContext.assets.open("ad_hosts.txt").use { stream ->
                BufferedReader(InputStreamReader(stream)).use { reader ->
                    var line: String?
                    while (reader.readLine().also { line = it } != null) {
                        val trimmed = line?.trim()?.lowercase().orEmpty()
                        if (trimmed.isEmpty() || trimmed.startsWith("#") || trimmed.contains("/")) {
                            continue
                        }
                        loaded.add(trimmed)
                    }
                }
            }
        } catch (_: Exception) {
            // Failed to load ad_hosts.txt
        }
        blockedHosts = loaded
    }

    private fun loadAssetScript(appContext: Context, assetName: String): String? {
        return try {
            appContext.assets.open(assetName).bufferedReader().use { it.readText() }
        } catch (_: Exception) {
            null
        }
    }

    private val DEFAULT_COSMETIC_SCRIPT = """
        (function() {
          var id = 'orca-adblock-cosmetic-style';
          if (document.getElementById(id)) return;
          var s = document.createElement('style');
          s.id = id;
          s.textContent = '.adsbygoogle,ins.adsbygoogle,[id^="google_ads"],[id^="div-gpt-ad"],iframe[src*="doubleclick"],iframe[src*="googlesyndication"],amp-ad,amp-embed{display:none!important;height:0!important;overflow:hidden!important;}';
          (document.head || document.documentElement).appendChild(s);
        })();
    """.trimIndent()

    private val DEFAULT_YOUTUBE_SCRIPT = """
        (function() {
          if (!location.hostname.includes('youtube.com') || location.hostname.startsWith('accounts.')) return;
          var id = 'orca-adblock-yt-style';
          if (!document.getElementById(id)) {
            var s = document.createElement('style');
            s.id = id;
            s.textContent = '.ytp-ad-overlay-container,.ytp-ad-module,#player-ads,#masthead-ad,ytd-ad-slot-renderer,ytm-ad-slot-renderer{display:none!important;}';
            (document.head || document.documentElement).appendChild(s);
          }
          var skip = document.querySelector('.ytp-skip-ad-button,.ytp-ad-skip-button,.ytp-ad-skip-button-modern');
          if (skip) skip.click();
        })();
    """.trimIndent()
}
