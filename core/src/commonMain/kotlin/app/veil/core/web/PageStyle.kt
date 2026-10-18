package app.veil.core.web

import app.veil.core.settings.Settings

/**
 * Cosmetic adjustments applied to the site after each page load. Appearance only: it hides
 * elements and never changes what the page sends.
 */
object PageStyle {
    /** Labels Instagram puts in the header of an ad post (English and German UI). */
    internal val AD_LABELS = listOf("Ad", "Sponsored", "Gesponsert", "Anzeige", "Werbung")

    /** Visible header labels that mark a post as hidden content, per setting. Ads are always hidden. */
    internal fun labels(settings: Settings): List<String> = buildList {
        addAll(AD_LABELS)
        if (settings.hideSuggestedPosts) {
            add("Suggested for you")
            add("Suggested posts")
            add("Suggested reels")
        }
        if (settings.hideSuggestedAccounts) add("Suggested for you")
    }.distinct()

    /**
     * Script returning the height (CSS px) of the site's own tab bar while it is on the page,
     * else 0. The page reserves that much space at the bottom even though the bar is hidden.
     */
    const val RESERVED_BOTTOM_SCRIPT: String =
        "(function () { var n = window.__veilNav; return (n && n.isConnected) ? Math.round(window.__veilNavH || 0) : 0; })()"

    /**
     * Script answering whether the page has visibly settled: the DOM has been quiet for a moment
     * and no loading placeholders or spinners are on screen. Answers `true`, `"images"` when only
     * on-screen images are still loading, or `false`. The first call installs the change tracker.
     */
    val SETTLED_SCRIPT: String = """
        (function () {
          if (!window.__veilWatch) {
            window.__veilWatch = true;
            window.__veilLastChange = Date.now();
            var bump = function () { window.__veilLastChange = Date.now(); };
            new MutationObserver(bump).observe(document.documentElement, { childList: true, subtree: true, characterData: true });
            try { new PerformanceObserver(bump).observe({ type: 'layout-shift', buffered: false }); } catch (e) {}
            return false;
          }
          if (document.readyState !== 'complete') return false;
          // After an in-page switch, the old page is still quiet: wait until the page has changed
          // (or, if nothing changes at all, for a moment).
          if (window.__veilNavAt && window.__veilLastChange <= window.__veilNavAt && Date.now() - window.__veilNavAt < 1500) return false;
          if (Date.now() - window.__veilLastChange < 400) return false;
          var vh = window.innerHeight, vw = window.innerWidth;
          function onScreen(el) {
            var r = el.getBoundingClientRect();
            return r.width > 0 && r.height > 0 && r.bottom > 0 && r.top < vh && r.right > 0 && r.left < vw;
          }
          var loading = document.querySelectorAll('[data-visualcompletion="loading-state"], [role="progressbar"]');
          for (var i = 0; i < loading.length; i++) if (onScreen(loading[i])) return false;
          if (!document.body || document.body.innerText.trim().length === 0) return false;
          var images = document.images;
          for (var j = 0; j < images.length; j++) if (!images[j].complete && onScreen(images[j])) return 'images';
          return true;
        })()
    """.trimIndent()

    /** Script to run in the page; safe to run repeatedly. */
    fun script(settings: Settings): String {
        val labels = labels(settings).joinToString(",") { "\"" + it.replace("\"", "") + "\"" }
        return """
            (function () {
              var labels = [$labels];
              window.__veilLabels = labels;
              if (window.__veilStyled) { window.__veilApply(); return; }
              window.__veilStyled = true;

              function hideSiteTabBar() {
                var wanted = ['/reels/', '/direct/inbox/', '/explore/'];
                document.querySelectorAll('div, nav').forEach(function (el) {
                  var style = window.getComputedStyle(el);
                  if (style.position !== 'fixed') return;
                  var rect = el.getBoundingClientRect();
                  // Only a short bar docked at the bottom edge, never a full-screen container.
                  if (rect.bottom < window.innerHeight - 2 || rect.height > 120 || rect.top < window.innerHeight * 0.7) return;
                  var hits = wanted.filter(function (href) { return el.querySelector('a[href^="' + href + '"]'); });
                  if (hits.length >= 2) {
                    // Remember the bar and its height: the page keeps space for it, which the app covers.
                    window.__veilNav = el;
                    window.__veilNavH = rect.height;
                    el.style.setProperty('display', 'none', 'important');
                  }
                });
              }

              // Hide a feed post without removing it from the layout. The feed only keeps posts near
              // the screen and positions the rest from measured sizes; display:none (size 0) corrupts
              // that bookkeeping and everything below renders black. Instead the post keeps its size,
              // is made invisible, and a matching negative margin lets the next post move into its place.
              function veilCollapse(article) {
                article.dataset.veilHidden = '1';
                article.style.setProperty('visibility', 'hidden', 'important');
                article.style.setProperty('pointer-events', 'none', 'important');
                var fit = function () {
                  article.style.setProperty('margin-bottom', (-article.getBoundingClientRect().height) + 'px', 'important');
                };
                fit();
                try { new ResizeObserver(fit).observe(article); } catch (e) {}
                article.querySelectorAll('video').forEach(function (v) { v.muted = true; v.pause(); });
              }

              // Posts whose header (the strip with the username) carries one of the labels.
              // Only standalone labels near the top count, so a caption saying "Ad" hides nothing.
              function hideLabelled() {
                var current = window.__veilLabels || [];
                document.querySelectorAll('article').forEach(function (article) {
                  if (article.dataset.veilHidden) return;
                  var top = article.getBoundingClientRect().top;
                  var spans = article.querySelectorAll('span, a, div, h2');
                  for (var i = 0; i < spans.length && i < 400; i++) {
                    var text = (spans[i].childElementCount === 0 ? spans[i].textContent : '').trim();
                    if (text && current.indexOf(text) >= 0 && spans[i].getBoundingClientRect().top - top < 90) {
                      veilCollapse(article);
                      return;
                    }
                  }
                });
              }

              // Floating "use the app" prompts docked in the lower half of the screen.
              var appPrompts = ['use the app', 'open app', 'get the app', 'open instagram', 'use the instagram app', 'open the app', 'switch to the app'];
              function hideAppBanners() {
                document.querySelectorAll('a, button, span, div[role="button"]').forEach(function (el) {
                  if (el.childElementCount > 2) return;
                  var text = (el.textContent || '').trim().toLowerCase();
                  if (appPrompts.indexOf(text) < 0) return;
                  for (var node = el, depth = 0; node && node !== document.body && depth < 8; node = node.parentElement, depth++) {
                    var pos = window.getComputedStyle(node).position;
                    if (pos !== 'fixed' && pos !== 'sticky') continue;
                    var rect = node.getBoundingClientRect();
                    if (rect.top > window.innerHeight * 0.5 && rect.height < window.innerHeight * 0.4) {
                      node.style.setProperty('display', 'none', 'important');
                    }
                    return;
                  }
                });
              }

              // Dark mode uses a dark grey page background; render it true black like the native bar.
              // Only the page background is changed: menus, cards and bubbles keep their lighter greys.
              if (!document.getElementById('veil-black')) {
                var black = document.createElement('style');
                black.id = 'veil-black';
                black.textContent = '.__fb-dark-mode{--ig-primary-background:0,0,0 !important;' +
                  '--ig-stroke-prism:0,0,0 !important;--web-wash:#000000 !important;}';
                (document.head || document.documentElement).appendChild(black);
              }

              // No scrollbars on the page's inner scroll areas either (the native app has none).
              if (!document.getElementById('veil-scrollbars')) {
                var bars = document.createElement('style');
                bars.id = 'veil-scrollbars';
                bars.textContent = '*{scrollbar-width:none !important}*::-webkit-scrollbar{display:none !important;width:0 !important;height:0 !important}';
                (document.head || document.documentElement).appendChild(bars);
              }

              // A page in a hidden tab does no work (all tabs share one engine thread with the shown
              // one); it catches up the moment it is shown, before its first frame.
              window.__veilApply = function (force) {
                if (!force && document.visibilityState === 'hidden') { window.__veilDirty = true; return; }
                window.__veilDirty = false;
                hideSiteTabBar(); hideLabelled(); hideAppBanners();
              };
              document.addEventListener('visibilitychange', function () {
                if (document.visibilityState !== 'hidden' && window.__veilDirty) window.__veilApply();
              });
              var pending = false;
              new MutationObserver(function () {
                if (pending) return;
                pending = true;
                setTimeout(function () { pending = false; window.__veilApply(); }, 250);
              }).observe(document.documentElement, { childList: true, subtree: true });
              window.__veilApply();
            })();
        """.trimIndent()
    }
}
