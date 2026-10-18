package app.veil.core.web

/**
 * Adds a Save (bookmark) button to the web Reels viewer, which lacks one. Saving itself is done
 * by Instagram's own Save button on the reel's page, opened by the app in a hidden helper view.
 *
 * Page <-> app messages travel over an origin-restricted channel named [BRIDGE]:
 * page to app: `save:<code>`, `unsave:<code>` (only when the user taps the button; `state:<code>`
 * is understood but no longer sent);
 * app to page: `<code>:1` (saved), `<code>:0` (not saved), `<code>:?` (unknown / failed).
 */
object ReelSave {
    const val BRIDGE = "veilBridge"

    private val codePattern = Regex("^[A-Za-z0-9_-]{5,64}$")

    sealed interface Request {
        val code: String
        data class State(override val code: String) : Request
        data class Toggle(override val code: String, val save: Boolean) : Request
    }

    /** Parses a message from the page; anything malformed is ignored (null). */
    fun parse(message: String?): Request? {
        val (kind, code) = message?.split(':', limit = 2)?.takeIf { it.size == 2 } ?: return null
        if (!codePattern.matches(code)) return null
        return when (kind) {
            "state" -> Request.State(code)
            "save" -> Request.Toggle(code, save = true)
            "unsave" -> Request.Toggle(code, save = false)
            else -> null
        }
    }

    /** Reply for the page; [saved] null means unknown. */
    fun reply(code: String, saved: Boolean?): String = "$code:" + when (saved) {
        true -> "1"
        false -> "0"
        null -> "?"
    }

    /** Reel page URL for a shortcode. */
    fun reelUrl(code: String): String = "https://www.instagram.com/reel/$code/"

    /**
     * Script for the Reels viewer: clones the Share action (so size, colour and spacing match
     * exactly), swaps its icon for a bookmark, inserts it below Share and wires it to the app.
     * Safe to run repeatedly.
     */
    val PAGE_SCRIPT: String = """
        (function () {
          if (window.__veilReelSave) { window.__veilReelSave.scan(); return; }
          var bridge = window.$BRIDGE;
          if (!bridge) return;
          var saved = {};      // code -> true / false (unknown = absent)
          var OUTLINE = '<path d="M20 21 12 13.44 4 21V3h16Z" fill="none" stroke="currentColor" ' +
            'stroke-width="2" stroke-linecap="round" stroke-linejoin="round"></path>';
          var FILLED = '<path d="M20 21 12 13.44 4 21V3h16Z" fill="currentColor" stroke="currentColor" ' +
            'stroke-width="2" stroke-linecap="round" stroke-linejoin="round"></path>';

          function currentCode() {
            var m = location.pathname.match(/^\/reels?\/([A-Za-z0-9_-]{5,64})/);
            return m ? m[1] : null;
          }

          function paint(button) {
            var code = button.dataset.veilCode;
            var state = saved[code];
            var svg = button.querySelector('svg');
            if (!svg) return;
            // Only touch the DOM when something changed: every change wakes the page's observers
            // (including ours), and repainting unconditionally would loop forever.
            var look = (state ? 'filled' : 'outline') + (button.dataset.veilBusy ? '-busy' : '');
            if (button.dataset.veilLook === look) return;
            button.dataset.veilLook = look;
            svg.innerHTML = state ? FILLED : OUTLINE;
            svg.setAttribute('aria-label', state ? 'Remove' : 'Save');
            button.setAttribute('aria-label', state ? 'Remove from saved' : 'Save');
            button.style.opacity = button.dataset.veilBusy ? '0.5' : '';
          }

          function paintAll() {
            document.querySelectorAll('[data-veil-save]').forEach(paint);
          }

          // The action column: the nearest ancestor of Share whose children are separate actions.
          function actionItem(shareSvg) {
            var item = shareSvg;
            for (var depth = 0; depth < 8 && item.parentElement; depth++) {
              var parent = item.parentElement;
              var actions = 0;
              for (var i = 0; i < parent.children.length; i++) {
                if (parent.children[i].querySelector('svg')) actions++;
              }
              if (actions >= 3) return item;
              item = parent;
            }
            return null;
          }

          function onScreen(el) {
            var r = el.getBoundingClientRect();
            return r.width > 0 && r.bottom > 0 && r.top < window.innerHeight;
          }

          function scan() {
            // Nothing happens in a hidden tab; the reel shown when the tab returns is scanned then.
            if (document.visibilityState === 'hidden') return;
            var code = currentCode();
            if (!code) return;
            document.querySelectorAll('svg[aria-label="Share"]').forEach(function (shareSvg) {
              if (!onScreen(shareSvg)) return;
              var item = actionItem(shareSvg);
              if (!item) return;
              var existing = item.nextElementSibling;
              var button = existing && existing.dataset && existing.dataset.veilSave ? existing : null;
              if (!button) {
                button = item.cloneNode(true);
                button.dataset.veilSave = '1';
                // Drop counters or captions the Share action may carry; keep only the icon.
                button.querySelectorAll('span').forEach(function (s) { if (!s.querySelector('svg')) s.remove(); });
                button.addEventListener('click', function (e) {
                  e.preventDefault();
                  e.stopPropagation();
                  var c = button.dataset.veilCode;
                  if (!c || button.dataset.veilBusy) return;
                  button.dataset.veilBusy = '1';
                  saved[c] = !saved[c];               // optimistic; unknown counts as not saved
                  paint(button);
                  bridge.postMessage((saved[c] ? 'save:' : 'unsave:') + c);
                }, true);
                item.parentElement.insertBefore(button, item.nextSibling);
              }
              if (button.dataset.veilCode !== code) {
                button.dataset.veilCode = code;
                delete button.dataset.veilBusy;
              }
              paint(button);
            });
            // The saved state is learned only when the user taps the button: asking for every reel
            // scrolled past would load a page per reel in the background.
          }

          bridge.onmessage = function (event) {
            var parts = String(event.data).split(':');
            if (parts.length !== 2) return;
            var code = parts[0];
            if (parts[1] === '1') saved[code] = true;
            else if (parts[1] === '0') saved[code] = false;
            else { delete saved[code]; }       // unknown (failed): back to the plain button
            document.querySelectorAll('[data-veil-save]').forEach(function (b) {
              if (b.dataset.veilCode === code) delete b.dataset.veilBusy;
            });
            paintAll();
          };

          window.__veilReelSave = { scan: scan };
          document.addEventListener('visibilitychange', function () { if (!document.hidden) scan(); });
          var pending = false;
          new MutationObserver(function () {
            if (pending) return;
            pending = true;
            setTimeout(function () { pending = false; scan(); }, 300);
          }).observe(document.documentElement, { childList: true, subtree: true });
          scan();
        })();
    """.trimIndent()

    /** Helper-view script: `saved`, `unsaved`, or empty while the reel's Save button isn't there yet. */
    const val STATE_SCRIPT: String =
        "(function () { if (document.querySelector('svg[aria-label=\"Remove\"]')) return 'saved';" +
            " return document.querySelector('svg[aria-label=\"Save\"]') ? 'unsaved' : ''; })()"

    /** Helper-view script: taps Instagram's own Save/Remove button on the reel page. */
    const val CLICK_SCRIPT: String =
        "(function () { var s = document.querySelector('svg[aria-label=\"Save\"], svg[aria-label=\"Remove\"]');" +
            " if (!s) return false; var b = s.closest('[role=\"button\"], button') || s.parentElement;" +
            " b.click(); return true; })()"
}
