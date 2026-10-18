package app.veil.core.web

/** Removes tracking parameters from links before they are opened or shared. */
object LinkSanitizer {
    private val trackingParams = setOf(
        "igsh", "igshid", "ig_rid", "ig_mid", "fbclid", "gclid", "dclid", "msclkid",
        "mc_cid", "mc_eid", "si", "ref_src", "ref_url", "_ga", "_gl", "yclid",
    )

    private fun isTracking(name: String): Boolean {
        val n = name.lowercase()
        return n in trackingParams || n.startsWith("utm_")
    }

    /**
     * Returns [url] without tracking parameters. Instagram's outbound redirect
     * (`l.instagram.com/?u=<target>`) is unwrapped to its target first.
     */
    fun clean(url: String): String {
        val unwrapped = unwrapRedirect(url) ?: url
        val fragmentStart = unwrapped.indexOf('#')
        val fragment = if (fragmentStart >= 0) unwrapped.substring(fragmentStart) else ""
        val withoutFragment = if (fragmentStart >= 0) unwrapped.substring(0, fragmentStart) else unwrapped
        val queryStart = withoutFragment.indexOf('?')
        if (queryStart < 0) return unwrapped
        val base = withoutFragment.substring(0, queryStart)
        val kept = withoutFragment.substring(queryStart + 1)
            .split('&')
            .filter { it.isNotEmpty() && !isTracking(it.substringBefore('=')) }
        return base + (if (kept.isEmpty()) "" else "?" + kept.joinToString("&")) + fragment
    }

    private fun unwrapRedirect(url: String): String? {
        val hostStart = url.indexOf("://").takeIf { it >= 0 }?.plus(3) ?: return null
        val host = url.substring(hostStart).substringBefore('/').substringBefore('?').lowercase()
        if (host != "l.instagram.com") return null
        val query = url.substringAfter('?', "")
        val target = query.split('&').firstOrNull { it.startsWith("u=") }?.removePrefix("u=") ?: return null
        return percentDecode(target)
    }

    private fun percentDecode(s: String): String {
        val out = StringBuilder()
        val bytes = ArrayList<Byte>()
        var i = 0
        fun flush() {
            if (bytes.isNotEmpty()) {
                out.append(bytes.toByteArray().decodeToString())
                bytes.clear()
            }
        }
        while (i < s.length) {
            val c = s[i]
            if (c == '%' && i + 2 < s.length) {
                val v = s.substring(i + 1, i + 3).toIntOrNull(16)
                if (v != null) {
                    bytes.add(v.toByte())
                    i += 3
                    continue
                }
            }
            flush()
            out.append(if (c == '+') ' ' else c)
            i++
        }
        flush()
        return out.toString()
    }
}
