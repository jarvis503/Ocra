// ===== YOUTUBE AD BLOCKER =====
(function() {
  'use strict';

  var host = (location && location.hostname ? location.hostname : '').toLowerCase();
  if (!host.includes('youtube.com') && !host.includes('youtube-nocookie.com')) return;
  if (host.startsWith('accounts.')) return;

  function isYouTubeAdRequestUrl(url) {
    if (typeof url !== 'string') return false;
    var lower = url.toLowerCase();
    return lower.indexOf('/pagead/') !== -1 ||
      lower.indexOf('/ptracking') !== -1 ||
      lower.indexOf('doubleclick.net') !== -1 ||
      lower.indexOf('/api/stats/ads') !== -1 ||
      lower.indexOf('get_midroll_') !== -1 ||
      lower.indexOf('/ad_break') !== -1 ||
      lower.indexOf('adformat=') !== -1 ||
      lower.indexOf('s0.2mdn.net') !== -1;
  }

  function stripPlayerAdsObject(obj) {
    if (!obj || typeof obj !== 'object') return;
    try {
      delete obj.adPlacements;
      delete obj.playerAds;
      delete obj.adSlots;
    } catch (_) {}
  }

  // --- 1. Intercept fetch for ad APIs & player ad payloads ---
  if (!window.__orcaYtFetchPatched && typeof window.fetch === 'function') {
    window.__orcaYtFetchPatched = true;
    var originalFetch = window.fetch;
    window.fetch = function() {
      var args = arguments;
      var first = args[0];
      var url = typeof first === 'string' ? first : (first && first.url ? first.url : '');
      if (isYouTubeAdRequestUrl(url)) {
        if (typeof url === 'string' && url.indexOf('ad_break') !== -1) {
          return Promise.resolve(new Response('{}', {
            status: 200,
            headers: { 'Content-Type': 'application/json' }
          }));
        }
        return Promise.resolve(new Response('', { status: 204 }));
      }
      var resultPromise = originalFetch.apply(this, args);
      if (typeof url === 'string' && url.indexOf('/youtubei/v1/player') !== -1) {
        return resultPromise.then(function(response) {
          try {
            var origJson = response.json.bind(response);
            response.json = function() {
              return origJson().then(function(data) {
                stripPlayerAdsObject(data);
                return data;
              });
            };
          } catch (_) {}
          return response;
        });
      }
      return resultPromise;
    };
  }

  // --- 2. Override XMLHttpRequest for ad requests ---
  if (!window.__orcaYtXhrPatched && window.XMLHttpRequest && XMLHttpRequest.prototype.open) {
    window.__orcaYtXhrPatched = true;
    var origOpen = XMLHttpRequest.prototype.open;
    XMLHttpRequest.prototype.open = function(method, url) {
      if (isYouTubeAdRequestUrl(url)) {
        var rest = Array.prototype.slice.call(arguments, 2);
        return origOpen.apply(this, [method, 'data:text/plain,', ...rest]);
      }
      return origOpen.apply(this, arguments);
    };
  }

  if (window.ytInitialPlayerResponse) {
    stripPlayerAdsObject(window.ytInitialPlayerResponse);
  }

  // --- 3. Auto-skip "Skip Ad" button & speed past unskippable ads ---
  var SKIP_SELECTORS = [
    '.ytp-skip-ad-button',
    '.ytp-ad-skip-button',
    '.ytp-ad-skip-button-modern',
    '.ytp-ad-skip-button-slot button',
    'button[aria-label*="Skip Ad"]',
    'button[aria-label*="Skip ads"]'
  ].join(', ');

  var AD_OVERLAY_SELECTORS = [
    '.ytp-ad-overlay-container',
    '.ytp-ad-text-overlay',
    '.ytp-ad-overlay-slot',
    '.ytp-ad-module',
    '#player-ads',
    '#masthead-ad',
    'ytd-ad-slot-renderer',
    'ytd-promoted-sparkles-web-renderer',
    'ytd-promoted-video-renderer',
    'ytd-display-ad-renderer',
    'ytd-companion-slot-renderer',
    'ytd-action-companion-ad-renderer',
    'ytd-in-feed-ad-layout-renderer',
    '.ytd-banner-promo-renderer',
    'ytm-ad-slot-renderer',
    'ytm-promoted-sparkles-web-renderer',
    'ytm-promoted-sparkles-text-search-renderer',
    'ytm-companion-slot-renderer',
    'ytm-promoted-video-renderer',
    'ytm-statement-banner-renderer',
    'ad-slot-renderer'
  ].join(', ');

  function skipAds() {
    var skipBtn = document.querySelector(SKIP_SELECTORS);
    if (skipBtn && typeof skipBtn.click === 'function') {
      skipBtn.click();
    }

    var player = document.querySelector('.html5-video-player, #movie_player');
    var video = (player && player.querySelector('video')) || document.querySelector('video');
    if (video) {
      var isAd = (player && (
        player.classList.contains('ad-showing') ||
        player.classList.contains('ad-interrupting')
      )) || !!document.querySelector('.ytp-ad-player-overlay, .ytp-ad-player-overlay-layout');

      if (isAd) {
        if (!window.__orcaYtAdPlaybackState) {
          window.__orcaYtAdPlaybackState = {
            rate: video.playbackRate || 1,
            muted: !!video.muted
          };
        }
        video.muted = true;
        if (Number.isFinite(video.duration) && video.duration > 0 && video.currentTime < video.duration - 0.1) {
          video.currentTime = video.duration;
        }
        try {
          video.playbackRate = 16;
        } catch (_) {}
      } else if (window.__orcaYtAdPlaybackState) {
        var prev = window.__orcaYtAdPlaybackState;
        window.__orcaYtAdPlaybackState = null;
        try {
          video.playbackRate = prev.rate || 1;
          video.muted = prev.muted;
        } catch (_) {}
      }
    }

    var overlays = document.querySelectorAll(AD_OVERLAY_SELECTORS);
    for (var i = 0; i < overlays.length; i++) {
      overlays[i].remove();
    }

    // Remove YouTube promo/enforcement popups without hiding genuine navigation/share/playlist dialogs
    var dialogs = document.querySelectorAll('tp-yt-paper-dialog');
    for (var j = 0; j < dialogs.length; j++) {
      var dlg = dialogs[j];
      if (dlg.querySelector('yt-mealbar-promo-renderer, ytd-enforcement-message-view-model')) {
        dlg.remove();
      }
    }
  }

  // --- 4. CSS to hide ad UI elements cleanly ---
  var STYLE_ID = 'orca-adblock-yt-style';
  function injectYouTubeStyles() {
    if (document.getElementById(STYLE_ID)) return;
    var target = document.head || document.documentElement;
    if (!target) return;

    var style = document.createElement('style');
    style.id = STYLE_ID;
    style.textContent = `
      /* Video ad overlays */
      .ytp-ad-overlay-container, .ytp-ad-text-overlay,
      .ytp-ad-overlay-slot, .ytp-ad-module,
      .ytp-ad-image-overlay, .ytp-ad-player-overlay,
      .ytp-ad-player-overlay-layout,
      .video-ads, #player-ads,

      /* Desktop page ads */
      #masthead-ad, ytd-ad-slot-renderer,
      ytd-promoted-sparkles-web-renderer,
      ytd-promoted-video-renderer, ytd-display-ad-renderer,
      ytd-companion-slot-renderer, ytd-action-companion-ad-renderer,
      ytd-in-feed-ad-layout-renderer, .ytd-banner-promo-renderer,
      ytd-promoted-sparkles-text-search-renderer,
      #related ytd-promoted-video-renderer,

      /* Mobile m.youtube.com page ads */
      ytm-ad-slot-renderer, ad-slot-renderer,
      ytm-promoted-sparkles-web-renderer,
      ytm-promoted-sparkles-text-search-renderer,
      ytm-companion-slot-renderer, ytm-promoted-video-renderer,
      ytm-statement-banner-renderer, ytm-brand-video-shelf-renderer,

      /* Shorts ads */
      ytd-reel-video-renderer [is-ad],

      /* Premium upsell */
      .ytd-mealbar-promo-renderer,
      ytd-enforcement-message-view-model
      {
        display: none !important;
      }

      /* Keep click surface clear after removing overlays */
      #movie_player .ytp-ad-overlay-container { pointer-events: none !important; }
    `;
    target.appendChild(style);
  }

  injectYouTubeStyles();
  skipAds();

  if (!window.__orcaYtPollInterval) {
    window.__orcaYtPollInterval = setInterval(skipAds, 500);
  }

  if (!window.__orcaYtNavPatched) {
    window.__orcaYtNavPatched = true;
    var origPushState = history.pushState;
    history.pushState = function() {
      origPushState.apply(this, arguments);
      setTimeout(function() {
        injectYouTubeStyles();
        skipAds();
      }, 500);
    };
    document.addEventListener('yt-navigate-finish', function() {
      injectYouTubeStyles();
      skipAds();
    });
    document.addEventListener('DOMContentLoaded', function() {
      injectYouTubeStyles();
      skipAds();
    });
    window.addEventListener('load', function() {
      injectYouTubeStyles();
      skipAds();
    });
  }
})();
