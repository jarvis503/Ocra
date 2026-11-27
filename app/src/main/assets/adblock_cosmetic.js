// ===== GENERAL AD COSMETIC FILTER =====
(function() {
  'use strict';

  var host = (location && location.hostname ? location.hostname : '').toLowerCase();
  if (!host ||
      host === 'accounts.google.com' ||
      host === 'accounts.youtube.com' ||
      host === 'myaccount.google.com' ||
      host === 'login.microsoftonline.com' ||
      host === 'login.live.com' ||
      host === 'appleid.apple.com') {
    return;
  }

  var STYLE_ID = 'orca-adblock-cosmetic-style';
  var adCSS = `
    /* Google Ads */
    .adsbygoogle, ins.adsbygoogle,
    [id^="google_ads"], [id^="div-gpt-ad"],
    [class*="ad-container"], [class*="ad-wrapper"],
    [class*="ad-banner"], [class*="ad-slot"],
    [class*="advertisement"], [class*="advert-"],
    [id*="advertisement"], [data-ad], [data-ad-slot],

    /* Search Text Ads (preserves organic search results) */
    #tads, #tadsb, #bottomads, [data-text-ad],

    /* Common ad frames */
    iframe[src*="doubleclick"], iframe[src*="googlesyndication"],
    iframe[src*="amazon-adsystem"], iframe[id*="google_ads"],

    /* Popup overlays */
    [class*="popup-overlay"], [class*="modal-ad"],
    [class*="interstitial"], [id*="interstitial"],

    /* Social annoyances */
    [class*="cookie-banner"], [class*="cookie-consent"],
    [class*="newsletter-popup"], [class*="subscribe-modal"],

    /* Ad placeholders */
    [class*="ad-placeholder"], [class*="sponsored-content"],
    [class*="promoted-content"],
    [data-testid="ad-slot"], [data-testid="ad-container"],
    [data-testid="ad-banner"], [data-testid="sponsored-post"],
    [data-testid^="ad-"],

    /* Specific sites common patterns */
    .ad-unit, .ad-zone, .ad-box, .adUnit, .ad_unit,
    #ad-wrapper, #ad-container, .ad-panel, .ads-banner,
    .textad, .text-ad, .text_ad, .text-ads,
    div[aria-label="advertisement"],
    aside[aria-label="Ads"],
    [data-google-query-id],
    amp-ad, amp-embed
    {
      display: none !important;
      height: 0 !important;
      min-height: 0 !important;
      max-height: 0 !important;
      overflow: hidden !important;
      visibility: hidden !important;
    }
  `;

  function injectStyle() {
    if (document.getElementById(STYLE_ID)) return;
    var target = document.head || document.documentElement;
    if (!target) return;
    var style = document.createElement('style');
    style.id = STYLE_ID;
    style.textContent = adCSS;
    target.appendChild(style);
  }

  function hasProtectedContent(el) {
    if (!el || !el.querySelector) return false;
    var tag = (el.tagName || '').toUpperCase();
    if (tag === 'HTML' || tag === 'BODY' || tag === 'MAIN' || tag === 'FORM' || tag === 'VIDEO') {
      return true;
    }
    return !!el.querySelector('input[type="password"], video, form[action*="login"], form[action*="signin"]');
  }

  var REMOVE_SELECTORS = [
    '.adsbygoogle',
    'ins.adsbygoogle',
    '[id^="google_ads"]',
    '[id^="div-gpt-ad"]',
    'iframe[src*="doubleclick"]',
    'iframe[src*="googlesyndication"]',
    'iframe[src*="amazon-adsystem"]'
  ];

  function removeAds() {
    injectStyle();
    for (var i = 0; i < REMOVE_SELECTORS.length; i++) {
      var nodes = document.querySelectorAll(REMOVE_SELECTORS[i]);
      for (var j = 0; j < nodes.length; j++) {
        var el = nodes[j];
        if (!hasProtectedContent(el)) {
          el.remove();
        }
      }
    }
  }

  function isDynamicAdNode(el) {
    if (!el || el.nodeType !== 1) return false;
    if (hasProtectedContent(el)) return false;
    if (el.classList && (
      el.classList.contains('adsbygoogle') ||
      el.classList.contains('ad-container') ||
      el.classList.contains('ad-wrapper') ||
      el.classList.contains('ad-banner')
    )) {
      return true;
    }
    if (el.id && (el.id.indexOf('google_ads') === 0 || el.id.indexOf('div-gpt-ad') === 0)) {
      return true;
    }
    if (el.getAttribute && el.getAttribute('data-ad-slot')) {
      return true;
    }
    if (el.tagName === 'IFRAME') {
      var src = (el.getAttribute('src') || '').toLowerCase();
      if (src.indexOf('doubleclick') !== -1 || src.indexOf('googlesyndication') !== -1) {
        return true;
      }
    }
    return false;
  }

  function attachObserver() {
    if (window.__orcaCosmeticObserverAttached) return;
    var observeTarget = document.body || document.documentElement;
    if (!observeTarget) return;
    window.__orcaCosmeticObserverAttached = true;

    var observer = new MutationObserver(function(mutations) {
      for (var i = 0; i < mutations.length; i++) {
        var added = mutations[i].addedNodes;
        for (var j = 0; j < added.length; j++) {
          var node = added[j];
          if (isDynamicAdNode(node)) {
            node.remove();
          }
        }
      }
    });
    observer.observe(observeTarget, { childList: true, subtree: true });
  }

  injectStyle();
  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', function() {
      removeAds();
      attachObserver();
    });
  } else {
    removeAds();
    attachObserver();
  }
})();
