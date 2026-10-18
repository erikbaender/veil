package app.veil.core.web

/**
 * How many tabs the app keeps loaded in the background, following the memory free on the system
 * right now (what the system and other apps leave over), not the memory built into the phone.
 *
 * The answer is a cap on hidden pages: with plenty free, every tab stays loaded and switching is
 * instant; as memory gets tight, the least useful hidden tabs are released one at a time; at 0
 * the app runs on a single page that switches in place (the memory-efficient mode). Freed memory
 * returns slowly, so shrinking waits between steps, and growing again waits longer still, which
 * keeps the budget from flapping.
 */
class MemoryBudget(private val maxHidden: Int = MAX_HIDDEN) {
    /** One reading of system memory. */
    data class Sample(val availableBytes: Long, val thresholdBytes: Long, val lowMemory: Boolean)

    /** Hidden pages allowed right now (-1 until the first sample). */
    var cap: Int = -1
        private set

    /** Upper bound for [cap]; lowered for good after the browser engine is killed. */
    var ceiling: Int = maxHidden
        private set

    private var shrinkHoldUntil = 0L
    private var growHoldUntil = 0L
    private var lastPreloadAt = Long.MIN_VALUE / 2
    private val deaths = mutableListOf<Long>()

    /** Below this much free memory hidden pages are released. */
    fun shrinkBelow(s: Sample): Long = maxOf(SHRINK_FLOOR, 2 * s.thresholdBytes)

    /** At or above this much free memory another page may be loaded. */
    fun growAt(s: Sample): Long = shrinkBelow(s) + GROW_MARGIN

    private fun ample(s: Sample): Long = growAt(s) + 2 * TAB_COST

    /** Takes a sample; returns how many hidden pages to keep (release the rest). */
    fun onSample(now: Long, s: Sample, hidden: Int): Int {
        if (cap < 0) {
            cap = when {
                s.lowMemory -> 0
                s.availableBytes >= growAt(s) -> ceiling
                s.availableBytes >= shrinkBelow(s) -> minOf(1, ceiling)
                else -> 0
            }
        }
        when {
            s.lowMemory -> onPressure(now)
            s.availableBytes < shrinkBelow(s) -> if (now >= shrinkHoldUntil) shrinkOne(now, hidden)
            s.availableBytes >= growAt(s) && now >= growHoldUntil && cap < ceiling -> {
                cap++
                growHoldUntil = now + GROW_STEP_MS
            }
        }
        return minOf(hidden, cap)
    }

    /** The system says memory is critically low: release every hidden page and hold off. */
    fun onPressure(now: Long): Int {
        cap = 0
        shrinkHoldUntil = now + SHRINK_HOLD_MS
        growHoldUntil = maxOf(growHoldUntil, now + PRESSURE_HOLD_MS)
        return 0
    }

    /** A milder signal (memory getting low while shown): release one page. */
    fun onShrinkSignal(now: Long, hidden: Int): Int {
        if (cap < 0) cap = ceiling
        if (now >= shrinkHoldUntil) shrinkOne(now, hidden)
        return minOf(hidden, cap)
    }

    /**
     * The app went to the background. That alone is no reason to drop anything (it happens on
     * every app switch); only real shortage is.
     */
    fun onBackground(now: Long, s: Sample, hidden: Int): Int = when {
        s.lowMemory -> onPressure(now)
        s.availableBytes < shrinkBelow(s) -> {
            cap = minOf(maxOf(cap, 0), 1)
            growHoldUntil = maxOf(growHoldUntil, now + PRESSURE_HOLD_MS)
            minOf(hidden, cap)
        }
        else -> hidden
    }

    /** Whether one more tab may be loaded in the background now. */
    fun mayPreload(now: Long, s: Sample, hidden: Int): Boolean {
        if (hidden >= cap || s.lowMemory) return false
        if (s.availableBytes < growAt(s) || now < growHoldUntil) return false
        val gap = if (s.availableBytes >= ample(s)) SHORT_GAP_MS else LONG_GAP_MS
        return now >= lastPreloadAt + gap
    }

    fun onPreloadDone(now: Long) {
        lastPreloadAt = now
    }

    /**
     * The browser engine was killed or crashed (every tab with it). Keep fewer tabs from now on:
     * two after one death, none after two within [DEATH_WINDOW_MS].
     */
    fun onRendererDeath(now: Long, crashed: Boolean) {
        deaths += now
        deaths.removeAll { now - it > DEATH_WINDOW_MS }
        ceiling = minOf(ceiling, if (deaths.size >= 2) 0 else 2)
        cap = minOf(maxOf(cap, 0), ceiling)
        // Killed rather than crashed means the system needed the memory.
        if (!crashed) growHoldUntil = maxOf(growHoldUntil, now + PRESSURE_HOLD_MS)
    }

    private fun shrinkOne(now: Long, hidden: Int) {
        cap = maxOf(0, minOf(cap, hidden) - 1)
        shrinkHoldUntil = now + SHRINK_HOLD_MS
        growHoldUntil = maxOf(growHoldUntil, now + PRESSURE_HOLD_MS)
    }

    companion object {
        private const val MIB = 1024L * 1024

        /** Hidden pages at most: every tab except the shown one. */
        const val MAX_HIDDEN = 4

        /**
         * Extra memory per loaded tab, with headroom: on a Pixel 7a a hidden tab measured about
         * 35 MB (hidden pages drop their drawn tiles), but tabs grow as they are used.
         */
        const val TAB_COST: Long = 100 * MIB
        const val SHRINK_FLOOR: Long = 512 * MIB
        const val GROW_MARGIN: Long = 384 * MIB

        const val SHRINK_HOLD_MS = 30_000L
        const val PRESSURE_HOLD_MS = 120_000L
        const val GROW_STEP_MS = 15_000L
        const val SHORT_GAP_MS = 3_000L
        const val LONG_GAP_MS = 15_000L
        const val DEATH_WINDOW_MS = 600_000L
    }
}
