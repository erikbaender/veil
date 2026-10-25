package app.veil.core.update

import kotlinx.coroutines.flow.StateFlow

/** Where the app's self-update stands. */
sealed interface UpdateStatus {
    /** Not checked yet in this run. */
    data object Idle : UpdateStatus
    data object Checking : UpdateStatus
    data object UpToDate : UpdateStatus
    data class Available(val release: Release) : UpdateStatus
    /** Downloading [release]; [progress] in 0..1, or null while the size is unknown. */
    data class Downloading(val release: Release, val progress: Float?) : UpdateStatus
    /** Handed to the system installer, which asks the user to confirm. */
    data class Installing(val release: Release) : UpdateStatus
    data class Failed(val message: String, val release: Release? = null) : UpdateStatus
    /** This build doesn't update itself (debug builds). */
    data object Unsupported : UpdateStatus
}

/** The app's updater as the interface sees it. */
interface Updates {
    val status: StateFlow<UpdateStatus>

    /** Asks GitHub for the latest release now. */
    fun check()

    /** Downloads the available release, verifies it and hands it to the system installer. */
    fun install()

    /** Stops a download in progress. */
    fun cancel()

    companion object {
        /** Automatic checks run at most this often. */
        const val CHECK_INTERVAL_MS: Long = 24L * 60 * 60 * 1000
    }
}
