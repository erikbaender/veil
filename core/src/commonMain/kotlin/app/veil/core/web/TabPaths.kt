package app.veil.core.web

/** The app's tabs. Each keeps a page of its own, like a browser tab, with its own history. */
enum class SiteTab { HOME, REELS, MESSAGES, SEARCH, PROFILE }

/** Which site paths belong to which tab, and where each tab starts. */
object TabPaths {
    const val ORIGIN = "https://www.instagram.com"

    private val reelInViewer = Regex("^/reels/[A-Za-z0-9_-]{5,64}/$")

    /**
     * Path of an Instagram page URL, without query or fragment and with a trailing slash
     * (`/explore/`), or null for anything that isn't an Instagram page (about:blank, other hosts).
     */
    fun pathOf(url: String?): String? {
        if (url == null) return null
        val rest = listOf("$ORIGIN", "https://instagram.com").firstNotNullOfOrNull { origin ->
            url.takeIf { it.startsWith(origin) }?.substring(origin.length)
        } ?: return null
        // "https://www.instagram.com.example" must not count.
        if (rest.isNotEmpty() && rest[0] != '/' && rest[0] != '?' && rest[0] != '#') return null
        val path = rest.substringBefore('#').substringBefore('?').ifEmpty { "/" }
        return if (path.endsWith("/")) path else "$path/"
    }

    /** Where [tab] starts. The profile tab opens the own profile at its last-used [section]. */
    fun rootPath(tab: SiteTab, profilePath: String, section: String): String = when (tab) {
        SiteTab.HOME -> "/"
        SiteTab.REELS -> "/reels/"
        SiteTab.MESSAGES -> "/direct/inbox/"
        SiteTab.SEARCH -> "/explore/"
        SiteTab.PROFILE -> profileBase(profilePath)
            ?.let { it + section.takeIf { s -> s in ProfileLink.SECTIONS }.orEmpty() }
            ?: ProfileLink.FALLBACK_PATH
    }

    fun rootUrl(tab: SiteTab, profilePath: String, section: String): String = ORIGIN + rootPath(tab, profilePath, section)

    /**
     * Whether [path] is a top level of [tab]: re-tapping the tab there scrolls up instead of
     * going back, and Back there leaves the tab. The Reels viewer rewrites its URL per reel.
     */
    fun isRoot(tab: SiteTab?, path: String?, profilePath: String): Boolean {
        if (tab == null || path == null) return false
        return when (tab) {
            SiteTab.HOME -> path == "/"
            SiteTab.REELS -> path == "/reels/" || reelInViewer.matches(path)
            SiteTab.MESSAGES -> path == "/direct/inbox/"
            SiteTab.SEARCH -> path == "/explore/"
            SiteTab.PROFILE -> {
                val base = profileBase(profilePath) ?: return path == ProfileLink.FALLBACK_PATH
                ProfileLink.SECTIONS.any { path.equals(base + it, ignoreCase = true) }
            }
        }
    }

    /**
     * The tab an opened link belongs to, or null for content that opens on top of the current
     * tab (posts, single reels, stories, other people's profiles).
     */
    fun route(path: String, profilePath: String): SiteTab? {
        val base = profileBase(profilePath)
        return when {
            path == "/" -> SiteTab.HOME
            path == "/reels/" -> SiteTab.REELS
            path.startsWith("/direct/") -> SiteTab.MESSAGES
            path.startsWith("/explore/") -> SiteTab.SEARCH
            base != null && path.startsWith(base, ignoreCase = true) -> SiteTab.PROFILE
            else -> null
        }
    }

    private fun profileBase(profilePath: String): String? =
        profilePath.trim('/').takeIf { it.isNotEmpty() }?.let { "/$it/" }
}
