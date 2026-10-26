package app.veil.core.settings

import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable

/** User preferences. Defaults favour privacy and a clean feed. */
@Serializable
data class Settings(
    /** Hide "Suggested for you" posts and Reels in the feed. */
    val hideSuggestedPosts: Boolean = false,
    /** Hide "Suggested for you" account carousels. */
    val hideSuggestedAccounts: Boolean = true,
    /** Block screenshots and blank the app in the recents screen. */
    val secureScreen: Boolean = false,
    /** Path of the user's own profile (e.g. `/name/`), learned from the site for the Profile tab. */
    val profilePath: String = "",
    /** Which of Home or Reels was used last; the app opens there at launch. */
    val startTab: StartTab = StartTab.HOME,
    /** Last-used tab on the own profile: "" (posts), "reels/", "saved/" or "tagged/". */
    val profileSection: String = "",
    /** Ask GitHub once a day whether a newer Veil is out. */
    val checkForUpdates: Boolean = true,
    /** When the last update check ran (epoch milliseconds). */
    val lastUpdateCheck: Long = 0,
    /** Version whose update prompt the user put off; it isn't offered unasked again. */
    val dismissedUpdate: String = "",
    /** Check Instagram for unread messages in the background (about every 15 minutes) and notify. */
    val messageNotifications: Boolean = false,
    /** Unread chats the user already knows about (notified, or seen in the app). */
    val knownUnread: Int = 0,
    /**
     * A background check ran into sign-in or a security check: no more checks until the user has
     * opened the app (and dealt with it there).
     */
    val messageChecksHeld: Boolean = false,
)

enum class StartTab { HOME, REELS }

interface SettingsRepository {
    val settings: StateFlow<Settings>
    suspend fun update(transform: (Settings) -> Settings)
}
