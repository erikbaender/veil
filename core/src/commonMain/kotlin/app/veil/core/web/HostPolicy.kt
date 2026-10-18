package app.veil.core.web

/**
 * Privacy guard: the only hosts the app may contact. Applied to every request the WebView
 * makes. Anything else is rejected before a connection is opened.
 */
object HostPolicy {
    /** Instagram itself and every first-party subdomain (web, chat, uploads). */
    private const val INSTAGRAM = "instagram.com"

    /** Instagram's media CDNs. */
    private val mediaSuffixes = listOf(".cdninstagram.com", ".fbcdn.net")

    fun isAllowed(host: String): Boolean {
        val h = host.lowercase().trimEnd('.')
        return h == INSTAGRAM || h.endsWith(".$INSTAGRAM") || mediaSuffixes.any { h.endsWith(it) }
    }
}
