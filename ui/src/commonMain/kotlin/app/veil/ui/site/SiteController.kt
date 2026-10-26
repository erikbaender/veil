package app.veil.ui.site

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.veil.core.web.SiteTab
import app.veil.core.web.TabPaths
import kotlinx.coroutines.channels.Channel

/** Instagram's website, the only content the app shows. */
object Site {
    const val HOME = "https://www.instagram.com/"
}

/** Load error shown in place of the page. */
data class SiteError(val description: String)

/** Commands from the shell to the platform site view, handled one after another. */
sealed interface SiteRequest {
    data class Select(val tab: SiteTab) : SiteRequest
    data class Link(val url: String) : SiteRequest
    data object Reload : SiteRequest
    data object Back : SiteRequest
    data object Clear : SiteRequest
}

/**
 * State and commands for the embedded site, shared between the native shell and the
 * platform web view. The shell sends commands; the web view reports state back.
 */
class SiteController(startTab: SiteTab = SiteTab.HOME) {
    companion object {
        /** Each half of the fade through black when switching to a ready tab. */
        const val SWITCH_FADE_MS = 250
    }

    internal val requests = Channel<SiteRequest>(Channel.UNLIMITED)

    /** The tab shown (highlighted in the bar). Moves at once on a tap. */
    var selectedTab by mutableStateOf(startTab)
        internal set

    /** Tabs can't be switched (sign-in or a security check is on screen). */
    var tabsLocked by mutableStateOf(false)
        internal set

    var currentUrl by mutableStateOf(TabPaths.ORIGIN + "/")
        internal set
    var canGoBack by mutableStateOf(false)
        internal set
    var isLoading by mutableStateOf(true)
        internal set
    var progress by mutableStateOf(0f)
        internal set
    var error by mutableStateOf<SiteError?>(null)
        internal set

    /** Chats with unread messages, from the site's own badge; null while unknown (signed out, loading). */
    var unreadMessages by mutableStateOf<Int?>(null)
        internal set

    /** Incremented whenever the app starts a navigation (tab load, link, reload); drives the loading veil. */
    var navigationCount by mutableStateOf(1)
        private set

    /** The navigation whose page has visibly settled (see PageStyle.SETTLED_SCRIPT). */
    var settledNavigation by mutableStateOf(0)
        private set

    /** The veil should vanish at once instead of fading (switched to a tab that was ready). */
    var veilInstant by mutableStateOf(false)
        private set

    /**
     * A switch to a ready tab is under way: the screen fades to black for [SWITCH_FADE_MS], the
     * tab is swapped while it is covered, then it fades back in.
     */
    var switchCover by mutableStateOf(false)
        internal set

    /** True while the latest navigation's page is still loading or rearranging itself. */
    val isSettling: Boolean get() = settledNavigation != navigationCount

    /** A story is open: like the official app, it covers the whole screen. */
    val storyOpen: Boolean get() = TabPaths.pathOf(currentUrl)?.startsWith("/stories/") == true

    fun selectTab(tab: SiteTab) {
        if (tabsLocked) return
        error = null
        selectedTab = tab
        requests.trySend(SiteRequest.Select(tab))
    }

    /** Opens an Instagram link handed to the app. */
    fun openLink(url: String) {
        error = null
        requests.trySend(SiteRequest.Link(url))
    }

    fun reload() {
        error = null
        requests.trySend(SiteRequest.Reload)
    }

    /** System Back (only while [canGoBack]). */
    internal fun back() {
        requests.trySend(SiteRequest.Back)
    }

    /** Wipes cookies, storage, cache and history (signs the user out). */
    fun clearSiteData() {
        error = null
        requests.trySend(SiteRequest.Clear)
    }

    /** A navigation starts: the veil covers the page until it settles. */
    internal fun startNavigation() {
        veilInstant = false
        isLoading = true
        navigationCount++
    }

    /** The shown page is ready: lift the veil ([instant]ly when switching to a ready tab). */
    internal fun finishNavigation(instant: Boolean = false) {
        veilInstant = instant
        settledNavigation = navigationCount
    }
}
