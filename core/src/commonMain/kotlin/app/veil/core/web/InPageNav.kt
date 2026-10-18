package app.veil.core.web

/**
 * Switches pages inside the already-loaded site by clicking the site's own link for the target
 * path, so Instagram routes client-side (no full reload of its scripts and data).
 */
object InPageNav {
    private val safePath = Regex("^/[A-Za-z0-9._/-]*$")

    /** The path to navigate to in-page, or null if [url] isn't an Instagram page we can route to. */
    fun pathFor(url: String): String? {
        val rest = url.removePrefix("https://www.instagram.com").takeIf { it != url } ?: return null
        val path = rest.substringBefore('#').ifEmpty { "/" }
        if ('?' in path) return null
        return path.takeIf { safePath.matches(it) }
    }

    /** Script that clicks the site's link to [path]; answers `true` if one was found. */
    fun clickScript(path: String): String {
        require(safePath.matches(path))
        // Marks the moment of the switch so the settle check waits for the page to actually change.
        return "(function(){var a=document.querySelector('a[href=\"$path\"]');if(!a)return false;" +
            "window.__veilNavAt=Date.now();a.click();return true;})()"
    }
}
