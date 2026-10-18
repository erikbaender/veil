package app.veil.core.web

import app.veil.core.web.MemoryBudget.Sample
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MemoryBudgetTest {
    private val mib = 1024L * 1024
    private val threshold = 216 * mib
    private fun free(mb: Long, low: Boolean = false) = Sample(mb * mib, threshold, low)

    @Test
    fun startsFromTheFreeMemory() {
        assertEquals(4, MemoryBudget().also { it.onSample(0, free(1500), 0) }.cap)
        assertEquals(1, MemoryBudget().also { it.onSample(0, free(700), 0) }.cap)
        assertEquals(0, MemoryBudget().also { it.onSample(0, free(300), 0) }.cap)
        assertEquals(0, MemoryBudget().also { it.onSample(0, free(4000, low = true), 0) }.cap)
    }

    @Test
    fun shrinksOneStepAtATime() {
        val b = MemoryBudget()
        b.onSample(0, free(1500), 4)
        assertEquals(3, b.onSample(1_000, free(400), 4))
        // Freed memory takes a while to show; no second step within the hold.
        assertEquals(3, b.onSample(10_000, free(400), 3))
        assertEquals(2, b.onSample(1_000 + MemoryBudget.SHRINK_HOLD_MS, free(400), 3))
    }

    @Test
    fun growsBackOnlyAfterTheHold() {
        val b = MemoryBudget()
        b.onSample(0, free(1500), 4)
        b.onSample(0, free(400), 4)
        assertEquals(3, b.cap)
        b.onSample(60_000, free(1500), 3)
        assertEquals(3, b.cap)
        b.onSample(MemoryBudget.PRESSURE_HOLD_MS, free(1500), 3)
        assertEquals(4, b.cap)
    }

    @Test
    fun lowMemoryReleasesEverything() {
        val b = MemoryBudget()
        b.onSample(0, free(1500), 4)
        assertEquals(0, b.onSample(1_000, free(1500, low = true), 4))
        assertFalse(b.mayPreload(2_000, free(1500), 0))
    }

    @Test
    fun backgroundAloneKeepsEverything() {
        val b = MemoryBudget()
        b.onSample(0, free(1500), 4)
        assertEquals(4, b.onBackground(1_000, free(1500), 4))
        assertEquals(1, b.onBackground(2_000, free(300), 4))
    }

    @Test
    fun preloadsAreSpacedByHowMuchIsFree() {
        val b = MemoryBudget()
        b.onSample(0, free(2000), 0)
        assertTrue(b.mayPreload(0, free(2000), 0))
        b.onPreloadDone(0)
        assertFalse(b.mayPreload(1_000, free(2000), 1))
        assertTrue(b.mayPreload(MemoryBudget.SHORT_GAP_MS, free(2000), 1))
        assertFalse(b.mayPreload(MemoryBudget.SHORT_GAP_MS, free(1000), 1))
        assertTrue(b.mayPreload(MemoryBudget.LONG_GAP_MS, free(1000), 1))
        assertFalse(b.mayPreload(MemoryBudget.LONG_GAP_MS, free(2000), 4))
    }

    @Test
    fun rendererDeathsLowerTheCeiling() {
        val b = MemoryBudget()
        b.onSample(0, free(2000), 4)
        b.onRendererDeath(1_000, crashed = true)
        assertEquals(2, b.ceiling)
        assertEquals(2, b.cap)
        b.onRendererDeath(2_000, crashed = false)
        assertEquals(0, b.ceiling)
        assertEquals(0, b.cap)
    }
}
