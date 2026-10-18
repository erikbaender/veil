package app.veil.core.web

/** Small scripts the app runs in its tab pages. Appearance and playback only; none sends anything. */
object PageScripts {
    /**
     * Installed at document start in every tab page. A page in a hidden tab plays nothing: media
     * pauses when the page is hidden (and anything that tries to start is stopped), and what was
     * playing resumes when the tab is shown again.
     */
    const val MEDIA_GUARD: String = """
(function () {
  if (window.__veilGuard) return; window.__veilGuard = true;
  var parked = [];
  function park() {
    document.querySelectorAll('video, audio').forEach(function (m) {
      if (!m.paused && !m.ended) { if (parked.indexOf(m) < 0) parked.push(m); try { m.pause(); } catch (e) {} }
    });
  }
  function unpark() {
    window.__veilParked = false;
    var list = parked; parked = [];
    list.forEach(function (m) {
      if (!m.isConnected) return;
      try { var p = m.play(); if (p && p.catch) p.catch(function () {}); } catch (e) {}
    });
  }
  window.__veilPark = function () { window.__veilParked = true; park(); };
  window.__veilUnpark = unpark;
  document.addEventListener('play', function (e) {
    if (document.visibilityState !== 'hidden' && !window.__veilParked) return;
    try { e.target.pause(); } catch (x) {}
  }, true);
  document.addEventListener('visibilitychange', function () {
    if (document.visibilityState === 'hidden') park(); else unpark();
  }, true);
})();
"""

    /**
     * Run on the page being switched away from: parks its media and drops focus (closing the
     * keyboard). Answers whether the page holds typed text, so it is released last.
     */
    const val HIDE_SCRIPT: String = """
(function () {
  if (window.__veilPark) window.__veilPark();
  else document.querySelectorAll('video, audio').forEach(function (m) { try { m.pause(); } catch (e) {} });
  var a = document.activeElement; if (a && a !== document.body && a.blur) a.blur();
  var f = document.querySelectorAll('textarea, input[type="text"], [contenteditable="true"]');
  for (var i = 0; i < f.length; i++) {
    var v = f[i].isContentEditable ? f[i].textContent : f[i].value;
    if (v && v.trim()) return true;
  }
  return false;
})()
"""

    /** Undoes [HIDE_SCRIPT] when a switch is called off. */
    const val UNPARK_SCRIPT: String = "(function () { if (window.__veilUnpark) window.__veilUnpark(); })()"

    /**
     * Run on a page about to be shown for the first time (already visible beneath the current
     * one): applies the page style, wakes lazy lists that only load on scroll or resize, and
     * answers the space the page keeps for the site's (hidden) tab bar, in CSS px.
     */
    const val REVEAL_PREP_SCRIPT: String = """
(function () {
  try { if (window.__veilApply) window.__veilApply(true); } catch (e) {}
  window.scrollBy(0, 1); window.scrollBy(0, -1); window.dispatchEvent(new Event('resize'));
  document.querySelectorAll('main, [role="main"]').forEach(function (m) { m.dispatchEvent(new Event('scroll')); });
  // Decode the on-screen images now, so they are painted when the page comes to the front.
  var vh = innerHeight, waits = [];
  document.querySelectorAll('img').forEach(function (i) {
    var r = i.getBoundingClientRect();
    if (r.width > 0 && r.bottom > 0 && r.top < vh && i.decode) waits.push(i.decode().catch(function () {}));
  });
  window.__veilDecoded = false;
  Promise.all(waits).then(function () { window.__veilDecoded = true; });
  var n = window.__veilNav; return (n && n.isConnected) ? Math.round(window.__veilNavH || 0) : 0;
})()
"""

    /** Whether the page's visible part is ready: no loading placeholders, images decoded, videos have data. */
    const val REVEAL_READY_SCRIPT: String = """
(function () {
  var vh = innerHeight, vw = innerWidth;
  function on(el) { var r = el.getBoundingClientRect(); return r.width > 0 && r.height > 0 && r.bottom > 0 && r.top < vh && r.right > 0 && r.left < vw; }
  var l = document.querySelectorAll('[data-visualcompletion="loading-state"], [role="progressbar"]');
  for (var i = 0; i < l.length; i++) if (on(l[i])) return false;
  if (window.__veilDecoded === false) return false;
  var im = document.images;
  for (var k = 0; k < im.length; k++) if (on(im[k]) && !(im[k].complete && im[k].naturalWidth > 0)) return false;
  var v = document.querySelectorAll('video');
  for (var j = 0; j < v.length; j++) if (on(v[j]) && v[j].readyState < 2 && !v[j].poster) return false;
  return true;
})()
"""

    /** Lazy lists (Explore's grid) only fetch on scroll or resize; nudges them without moving the page. */
    const val WAKE_LAZY_LOADING: String =
        "(function(){window.scrollBy(0,1);window.scrollBy(0,-1);window.dispatchEvent(new Event('resize'));" +
            "document.querySelectorAll('main, [role=\"main\"]').forEach(function(m){m.dispatchEvent(new Event('scroll'))})})()"

    /**
     * Re-tapping the shown tab: scrolls the page's main scroller to the top (smoothly) and
     * answers how far down it was, in CSS px. 0 means it already was at the top.
     */
    const val SCROLL_TOP_SCRIPT: String = """
(function () {
  var el = document.elementFromPoint(innerWidth / 2, innerHeight / 2), s = null;
  for (; el && el !== document.body && el !== document.documentElement; el = el.parentElement) {
    var o = getComputedStyle(el).overflowY;
    if ((o === 'auto' || o === 'scroll') && el.scrollHeight > el.clientHeight + 1 && el.scrollTop > 0) { s = el; break; }
  }
  s = s || document.scrollingElement || document.documentElement;
  var top = s.scrollTop;
  if (top > 8) { if (top > innerHeight * 2) s.scrollTop = innerHeight; s.scrollTo({ top: 0, behavior: 'smooth' }); }
  return Math.round(top);
})()
"""

    /** Whether a dialog or sheet is open on the page (no background loading meanwhile). */
    const val DIALOG_OPEN_SCRIPT: String = """
(function () {
  var d = document.querySelectorAll('[role="dialog"], [aria-modal="true"]');
  for (var i = 0; i < d.length; i++) { var r = d[i].getBoundingClientRect(); if (r.width > 0 && r.height > 0) return true; }
  return false;
})()
"""

    /** Whether the page shows a sign-in form. */
    const val LOGIN_FORM_SCRIPT: String = "(function () { return !!document.querySelector('input[name=\"password\"]'); })()"

    /** Installed at document start in the hidden reel-save helper: it never plays anything. */
    const val HELPER_MEDIA_OFF: String = """
(function () {
  document.addEventListener('play', function (e) { try { e.target.muted = true; e.target.pause(); } catch (x) {} }, true);
  document.addEventListener('loadedmetadata', function (e) { try { e.target.muted = true; } catch (x) {} }, true);
})();
"""
}
