package app.veil.ui.site

import android.app.ActivityManager
import android.content.Context
import android.net.ConnectivityManager
import app.veil.core.web.MemoryBudget
import java.io.File

/** Reads how much memory the system has free right now, for [MemoryBudget]. */
internal class MemoryReader(context: Context) {
    private val activityManager = context.getSystemService(ActivityManager::class.java)
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)

    /** Diagnostics: pretend this many MiB are free (0 = off). */
    var fakeFreeMib = 0L

    fun read(): MemoryBudget.Sample {
        val info = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(info)
        // availMem counts some shared buffers (including the engine's own) as free; the kernel's
        // MemAvailable is the more honest figure, so take the smaller of the two.
        val available = if (fakeFreeMib > 0) fakeFreeMib * MIB else minOf(info.availMem, memAvailable() ?: info.availMem)
        return MemoryBudget.Sample(available, info.threshold, info.lowMemory && fakeFreeMib == 0L)
    }

    /** Data Saver is on: the user wants no background data use. */
    fun dataSaverOn(): Boolean =
        connectivity?.restrictBackgroundStatus == ConnectivityManager.RESTRICT_BACKGROUND_STATUS_ENABLED

    private fun memAvailable(): Long? = try {
        File("/proc/meminfo").useLines { lines ->
            lines.firstOrNull { it.startsWith("MemAvailable:") }
                ?.substringAfter(':')?.trim()?.substringBefore(' ')?.toLongOrNull()?.times(1024)
        }
    } catch (_: Exception) {
        null
    }

    private companion object {
        const val MIB = 1024L * 1024
    }
}
