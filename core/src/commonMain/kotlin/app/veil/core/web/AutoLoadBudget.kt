package app.veil.core.web

/**
 * Limits the pages the app loads on its own (preloading tabs), so its traffic stays like a person
 * opening a few browser tabs: each tab at most once per [perTabMs], and at most [perHour] in any
 * hour. Loads the user asks for (taps, links, refresh) are never counted or limited.
 */
class AutoLoadBudget(private val perTabMs: Long = 30 * 60_000L, private val perHour: Int = 8) {
    private class Load(val tab: SiteTab?, val at: Long)

    private val loads = mutableListOf<Load>()

    fun allow(now: Long, tab: SiteTab): Boolean {
        loads.removeAll { now - it.at >= HOUR_MS }
        if (loads.size >= perHour) return false
        return loads.none { it.tab == tab && now - it.at < perTabMs }
    }

    /** Records an automatic load; [tab] null counts only toward the hourly limit (e.g. a retry). */
    fun record(now: Long, tab: SiteTab?) {
        loads += Load(tab, now)
    }

    private companion object {
        const val HOUR_MS = 60 * 60_000L
    }
}
