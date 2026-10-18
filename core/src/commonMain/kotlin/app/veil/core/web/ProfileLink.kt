package app.veil.core.web

/** Finds the signed-in user's own profile link in Instagram's navigation. */
object ProfileLink {
    /** Page opened when the profile link can't be found (signed out users land on the login page). */
    const val FALLBACK_PATH = "/accounts/edit/"

    /** The own-profile tabs: posts, Reels, Saved and tagged, as path suffixes. */
    val SECTIONS = listOf("", "reels/", "saved/", "tagged/")

    /**
     * Which own-profile tab [url] shows, as one of [SECTIONS], or null when it isn't the profile
     * at [profilePath] (e.g. `/name/`). Pages inside a tab (e.g. `saved/all-posts/`) count as it.
     */
    fun section(url: String, profilePath: String): String? {
        if (profilePath.isBlank()) return null
        val path = url.substringAfter("instagram.com", "").substringBefore('?').substringBefore('#')
        val base = "/" + profilePath.trim('/') + "/"
        val normalized = if (path.endsWith("/")) path else "$path/"
        if (!normalized.startsWith(base, ignoreCase = true)) return null
        val rest = normalized.substring(base.length)
        if (rest.isEmpty()) return ""
        val tab = rest.substringBefore('/') + "/"
        return tab.takeIf { it in SECTIONS }
    }

    private val reserved = setOf("explore", "reels", "reel", "direct", "accounts", "p", "stories", "create", "about", "legal")
    private val profilePath = Regex("""^/[A-Za-z0-9._]{1,30}/$""")

    /**
     * Script returning the href of the profile link inside the site's tab bar (the bar that also
     * links to Explore and Reels), or an empty string.
     */
    val script: String = """
        (function () {
          var explore = document.querySelector('a[href="/explore/"]');
          for (var bar = explore; bar && bar !== document.body; bar = bar.parentElement) {
            if (!bar.querySelector('a[href="/reels/"]')) continue;
            var links = bar.querySelectorAll('a[href^="/"]');
            for (var i = 0; i < links.length; i++) {
              var img = links[i].querySelector('img[alt]');
              if (img) return links[i].getAttribute('href');
            }
            var hrefs = [];
            for (var j = 0; j < links.length; j++) hrefs.push(links[j].getAttribute('href'));
            return hrefs.join('\n');
          }
          return '';
        })()
    """.trimIndent()

    /**
     * Interprets the script result: the first single-segment path that isn't one of the site's
     * own sections, e.g. `/someone/`. Accepts the raw (JSON-quoted) value from evaluateJavascript.
     */
    fun parse(result: String?): String? {
        val text = result?.trim()?.removeSurrounding("\"")?.replace("\\n", "\n") ?: return null
        return text.lines()
            .map { it.trim() }
            .firstOrNull { profilePath.matches(it) && it.trim('/').lowercase() !in reserved }
    }
}
