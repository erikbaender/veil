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
)

enum class StartTab { HOME, REELS }

interface SettingsRepository {
    val settings: StateFlow<Settings>
    suspend fun update(transform: (Settings) -> Settings)
}
