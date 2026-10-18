package app.veil.core.web

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AutoLoadBudgetTest {
    private val minute = 60_000L

    @Test
    fun eachTabAtMostOncePerWindow() {
        val b = AutoLoadBudget()
        assertTrue(b.allow(0, SiteTab.REELS))
        b.record(0, SiteTab.REELS)
        assertFalse(b.allow(10 * minute, SiteTab.REELS))
        assertTrue(b.allow(10 * minute, SiteTab.SEARCH))
        assertTrue(b.allow(30 * minute, SiteTab.REELS))
    }

    @Test
    fun hourlyCapCountsRetriesToo() {
        val b = AutoLoadBudget(perHour = 3)
        b.record(0, SiteTab.HOME)
        b.record(0, SiteTab.REELS)
        b.record(0, null)
        assertFalse(b.allow(minute, SiteTab.SEARCH))
        assertTrue(b.allow(61 * minute, SiteTab.SEARCH))
    }
}
