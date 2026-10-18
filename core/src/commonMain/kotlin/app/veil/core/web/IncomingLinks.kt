package app.veil.core.web

/** Finds an Instagram page link in text handed to the app (a VIEW intent or shared text). */
object IncomingLinks {
    private val urlPattern = Regex("""https://(?:www\.)?instagram\.com/[^\s"'<>]*""", RegexOption.IGNORE_CASE)

    /** The first Instagram link in [text], cleaned of tracking parameters, or null. */
    fun instagramUrl(text: String?): String? {
        val match = text?.let { urlPattern.find(it) }?.value ?: return null
        return LinkSanitizer.clean(match.trimEnd('.', ',', ')', '!'))
    }
}
