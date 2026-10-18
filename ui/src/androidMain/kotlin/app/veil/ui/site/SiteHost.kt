package app.veil.ui.site

import android.content.ComponentCallbacks2
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.webkit.CookieManager
import android.webkit.PermissionRequest
import android.webkit.RenderProcessGoneDetail
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import app.veil.core.settings.Settings
import app.veil.core.web.AuthPaths
import app.veil.core.web.AutoLoadBudget
import app.veil.core.web.InPageNav
import app.veil.core.web.MemoryBudget
import app.veil.core.web.PageScripts
import app.veil.core.web.PageStyle
import app.veil.core.web.ProfileLink
import app.veil.core.web.ReelSave
import app.veil.core.web.SiteTab
import app.veil.core.web.TabPaths
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume
import kotlin.math.roundToInt

/**
 * What must outlive the screen (it is rebuilt when, for example, the system theme changes):
 * the memory budget and the safety latches and limits on loading anything automatically.
 */
internal object HostSession {
    val budget = MemoryBudget()
    val autoLoads = AutoLoadBudget()
    /** A security check was seen: the app loads nothing on its own for the rest of this run. */
    var challengeSeen = false
    var preloadHoldUntil = 0L
    /** When the browser engine was lost (killed or crashed). */
    val engineDeaths = mutableListOf<Long>()
}

/**
 * Owns the app's WebViews: one page per tab, each with its own history, like browser tabs.
 * The shown tab is on top; the others wait hidden (paused, silent, doing no work) and appear the
 * instant their tab is tapped. After launch the other tabs load in the background, one at a time
 * in quiet moments, as far as the free memory allows (see [MemoryBudget]); with too little memory
 * the app falls back to one page that switches in place.
 *
 * The app only ever loads Instagram's own pages, never more often than a person opening a few
 * tabs would, and nothing at all on its own around sign-in or security checks.
 */
internal class SiteHost(
    private val context: Context,
    private val controller: SiteController,
    private val scope: CoroutineScope,
    private val settings: () -> Settings,
    private val openExternal: (String) -> Unit,
    private val showFileChooser: (ValueCallback<Array<Uri>>, WebChromeClient.FileChooserParams) -> Boolean,
    private val profileFound: (String) -> Unit,
) {
    val stack = PageStack(context)

    private val pages = mutableMapOf<SiteTab, Page>()
    /** A link opened on top of the tabs (a post, someone's profile); closed by Back or a tab tap. */
    private var overlay: Page? = null
    private var active: Page
    /** The page a switch in progress is about to show; it counts as shown, never as background. */
    private var incoming: Page? = null
    private var selected: SiteTab = controller.selectedTab
    private val startTab: SiteTab = controller.selectedTab
    /** A switch to another tab was started but not finished (called off by a newer tap). */
    private var switchPending = false

    private val session = HostSession
    private val budget = session.budget
    private val memory = MemoryReader(context)
    private var helper: ReelSaveHelper? = null

    private enum class Account { UNKNOWN, LOGGED_IN, LOGGED_OUT, CHALLENGE }
    private var account = Account.UNKNOWN
    private var lastAccountCheck = 0L
    private val failedThisRun = mutableSetOf<SiteTab>()

    private var lastSwitchAt = 0L
    private var started = true
    private var backgroundedAt = 0L

    private var settleJob: Job? = null
    private var settleTarget: Page? = null

    private var rendererGone = false
    private var recovery: Recovery? = null

    private class Recovery(val tab: SiteTab, val url: String?, val load: Boolean)

    private val density = context.resources.displayMetrics.density
    private val documentStartScripts = WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)

    private val profilePath: String get() = settings().profilePath
    private fun now() = SystemClock.elapsedRealtime()
    private fun rootUrl(tab: SiteTab?) = TabPaths.rootUrl(tab ?: SiteTab.HOME, profilePath, settings().profileSection)
    private fun isRoot(page: Page) = TabPaths.isRoot(page.tab, page.path, profilePath)
    private fun allPages(): List<Page> = pages.values.toList() + listOfNotNull(overlay?.takeIf { it.tab == null })
    private fun hiddenPages(): List<Page> = pages.values.filter { it !== active && it !== incoming }

    init {
        stack.onHeightChanged = { allPages().forEach(::applyHeight) }
        val first = createPage(selected)
        active = first
        commit(first)
        first.view.resumeTimers()
        first.toRoot = true
        first.view.loadUrl(rootUrl(selected))
        // The controller starts with this navigation open, so the veil is already up.
        startSettle(first, controller.navigationCount)
    }

    // ---- Requests ---------------------------------------------------------------------------

    suspend fun handle(request: SiteRequest) {
        if (active.dead) {
            if (rendererGone) return
            rebuild()
        }
        if (request !is SiteRequest.Select) reconcileSelection()
        when (request) {
            is SiteRequest.Select -> select(request.tab)
            is SiteRequest.Link -> openLink(request.url)
            SiteRequest.Reload -> reloadActive()
            SiteRequest.Back -> back()
            SiteRequest.Clear -> signOut()
        }
    }

    /** After a switch was called off, the selection follows the page actually shown. */
    private fun reconcileSelection() {
        val tab = active.tab ?: return
        if (active === overlay || tab == selected) return
        selected = tab
        controller.selectedTab = tab
        switchPending = false
        sync()
    }

    private suspend fun select(tab: SiteTab) {
        if (controller.tabsLocked) {
            controller.selectedTab = selected
            return
        }
        lastSwitchAt = now()
        // A link page left behind by an interrupted request.
        overlay?.takeIf { it !== active }?.let(::destroyPage)
        val fromOverlay = active === overlay
        if (!fromOverlay && pages[tab] === active) {
            if (switchPending) {
                // Tapped back to the shown tab while a switch away was under way: just stay.
                switchPending = false
                selected = tab
                controller.selectedTab = tab
                sync()
            } else {
                retap(tab)
            }
            return
        }
        selected = tab
        controller.selectedTab = tab
        switchPending = true
        if (budget.cap == 0) {
            switchInPlace(tab)
            switchPending = false
            return
        }
        val page = pages[tab]
        val startedAt = now()
        when {
            page == null -> openFresh(tab)
            page.failed -> {
                page.failed = false
                page.loading = true
                page.view.reload()
                veilShow(page)
            }
            !page.settled -> veilShow(page)
            else -> swapTo(page)
        }
        log("switch to ${tab.name.lowercase()}: ${if (controller.isSettling) "veil" else "instant"} (${now() - startedAt} ms)")
        sampleMemory()
    }

    /** Re-tapping the shown tab: back to its start, else up to the top, else refresh (as in the app). */
    private suspend fun retap(tab: SiteTab) {
        val page = active
        if (!page.settled) return
        when {
            !isRoot(page) -> popToRoot(page)
            tab == SiteTab.REELS -> reloadRoot(page)
            (page.eval(PageScripts.SCROLL_TOP_SCRIPT)?.toDoubleOrNull() ?: 0.0) <= 8.0 -> reloadRoot(page)
        }
    }

    /** System Back: the tab's own history, then its start, then the Home tab, then leave the app. */
    private suspend fun back() {
        val page = active
        when {
            controller.tabsLocked -> if (page.view.canGoBack()) page.view.goBack()
            page === overlay -> if (page.view.canGoBack()) page.view.goBack() else select(selected)
            !isRoot(page) -> if (page.view.canGoBack()) page.view.goBack() else reloadRoot(page)
            page.tab != SiteTab.HOME -> {
                controller.selectedTab = SiteTab.HOME
                select(SiteTab.HOME)
            }
        }
    }

    /**
     * An Instagram link from outside: links to a tab's own pages open in that tab, anything else
     * (a post, someone's profile) on top of the current tab, so the tab itself stays as it was.
     */
    private suspend fun openLink(url: String) {
        val path = TabPaths.pathOf(url)
        val tab = path?.let { TabPaths.route(it, profilePath) }
        if (account != Account.LOGGED_IN || budget.cap == 0 || path == null) {
            // Signed-in state not known yet (cold start) or a single page: open it right here,
            // under the tab it belongs to.
            val page = active
            if (tab != null && page !== overlay && page.tab != tab) rekey(page, tab)
            navigateWithin(page, url)
            return
        }
        if (tab != null) {
            if (pages[tab] !== active || active === overlay) {
                controller.selectedTab = tab
                select(tab)
            }
            val page = active
            if (page.tab != tab || page.path == path) return
            if (TabPaths.isRoot(tab, path, profilePath)) popToRoot(page) else navigateWithin(page, url)
            return
        }
        overlay?.takeIf { it === active }?.let {
            navigateWithin(it, url)
            return
        }
        overlay?.let(::destroyPage)
        val page = createPage(null)
        overlay = page
        page.view.loadUrl(url)
        veilShow(page)
        enforceCap()
    }

    private fun reloadActive() {
        val page = active
        page.failed = false
        controller.error = null
        beginNavigation(page, loading = true)
        if (TabPaths.pathOf(page.url) == null) page.view.loadUrl(rootUrl(page.tab ?: selected)) else page.view.reload()
    }

    // ---- Switching --------------------------------------------------------------------------

    /**
     * Shows a ready page through a short fade to black: once covered, the page is made visible
     * beneath the current one and brought to the front when its content is drawn, then the black
     * fades away. If it can't draw in time the switch happens under the veil instead.
     */
    private suspend fun swapTo(target: Page) {
        val out = active
        if (target === out) return
        incoming = target
        var committed = false
        // Fade to black, then wake and swap in the new tab while the screen is covered: its first
        // drawing can hold up frames for a moment, which would stutter a fade still running.
        controller.switchCover = true
        val coverStartedAt = now()
        try {
            out.hasDraft = out.eval(PageScripts.HIDE_SCRIPT, 300) == "true"
            hideKeyboard()
            delay(maxOf(0L, SiteController.SWITCH_FADE_MS - (now() - coverStartedAt)))
            if (target.dead) return
            out.view.bringToFront()
            applyHeight(target)
            target.view.visibility = View.VISIBLE
            target.view.onResume()
            val first = !target.everShown
            val startedAt = now()
            val cap = if (first) FIRST_REVEAL_MS else REVEAL_MS
            if (first) target.eval(PageScripts.REVEAL_PREP_SCRIPT, 500)?.toDoubleOrNull()?.let { setReserved(target, it) }
            // Drawn: the page has a frame to show. Without one the switch would flash blank.
            val drawn = withTimeoutOrNull(cap) { target.awaitVisualState() } ?: false
            // On a first showing, also give its on-screen images a moment to be painted.
            if (drawn && first) {
                withTimeoutOrNull(maxOf(0L, cap - (now() - startedAt))) {
                    while (target.eval(PageScripts.REVEAL_READY_SCRIPT, 300) != "true") delay(50)
                }
            }
            if (target.dead) return
            if (drawn) {
                commit(target)
                committed = true
                // A veil still up for another page no longer covers what is shown.
                cancelSettle()
                controller.finishNavigation(instant = true)
            } else {
                beginNavigation(target, loading = target.loading)
                delay(VEIL_COVER_MS)
                if (target.dead) return
                commit(target)
                committed = true
            }
        } finally {
            incoming = null
            controller.switchCover = false
            if (!committed) abandon(target, out, cancelled = !currentCoroutineContext().isActive)
        }
    }

    /** Shows [target] under the veil (it is still loading): the veil covers the switch, then the page. */
    private suspend fun veilShow(target: Page) {
        beginNavigation(target, loading = target.loading)
        val out = active
        if (target === out) return
        incoming = target
        var committed = false
        try {
            if (!out.dead) out.view.evaluateJavascript(PageScripts.HIDE_SCRIPT) { if (!out.dead) out.hasDraft = it == "true" }
            hideKeyboard()
            applyHeight(target)
            delay(VEIL_COVER_MS)
            if (!target.dead) {
                commit(target)
                committed = true
            }
        } finally {
            incoming = null
            if (!committed) abandon(target, out, cancelled = !currentCoroutineContext().isActive)
        }
    }

    /**
     * A switch was called off (a newer tap) or its page is gone: the shown page carries on as it
     * was, playing again, with no veil left over it.
     */
    private fun abandon(target: Page, out: Page, cancelled: Boolean) {
        if (!out.dead && out === active) out.view.evaluateJavascript(PageScripts.UNPARK_SCRIPT, null)
        if (settleTarget === target) {
            cancelSettle()
            controller.finishNavigation(instant = true)
        }
        if (!target.dead && target !== active) {
            target.view.onPause()
            target.view.visibility = View.INVISIBLE
            if (!target.settled) watchSettling(target)
        }
        if (!cancelled) {
            // Nothing newer is coming: the bar follows the page that is still shown.
            switchPending = false
            reconcileSelection()
        }
    }

    /** Opens a tab whose page isn't loaded (first visit, or released for memory). */
    private suspend fun openFresh(tab: SiteTab) {
        var url = rootUrl(tab)
        if (tab == SiteTab.PROFILE && profilePath.isEmpty()) {
            ProfileLink.parse(active.eval(ProfileLink.script))?.let { found ->
                profileFound(found)
                url = TabPaths.ORIGIN + found
            }
        }
        val page = createPage(tab)
        page.toRoot = true
        page.view.loadUrl(url)
        veilShow(page)
        enforceCap()
    }

    /** Memory-efficient mode: a single page that switches tabs in place. */
    private suspend fun switchInPlace(tab: SiteTab) {
        val page = active
        rekey(page, tab)
        navigateWithin(page, rootUrl(tab))
    }

    /** Makes [page] the page of [tab] (replacing any other page of it) and selects that tab. */
    private fun rekey(page: Page, tab: SiteTab) {
        if (page === overlay) overlay = null
        pages.entries.removeAll { it.value === page }
        pages[tab]?.let(::destroyPage)
        page.tab = tab
        pages[tab] = page
        selected = tab
        controller.selectedTab = tab
    }

    /** Navigates [page] to [url]: in-page through the site's own link when possible. */
    private suspend fun navigateWithin(page: Page, url: String) {
        page.toRoot = TabPaths.isRoot(page.tab, TabPaths.pathOf(url), profilePath)
        if (!page.loadedOnce) {
            beginNavigation(page, loading = true)
            page.view.loadUrl(url)
            return
        }
        val path = InPageNav.pathFor(url)
        val routed = path != null && page.eval(InPageNav.clickScript(path)) == "true"
        beginNavigation(page, loading = !routed)
        if (!routed) page.view.loadUrl(url)
    }

    /** Back to the tab's start: through its history when it was there before (no reload). */
    private suspend fun popToRoot(page: Page) {
        val history = page.view.copyBackForwardList()
        val current = history.currentIndex
        for (i in current - 1 downTo 0) {
            if (TabPaths.isRoot(page.tab, TabPaths.pathOf(history.getItemAtIndex(i)?.url), profilePath)) {
                page.view.goBackOrForward(i - current)
                return
            }
        }
        val tab = page.tab ?: return
        val path = TabPaths.rootPath(tab, profilePath, settings().profileSection)
        page.toRoot = true
        if (page.eval(InPageNav.clickScript(path)) == "true") {
            beginNavigation(page, loading = false)
        } else {
            reloadRoot(page)
        }
    }

    private fun reloadRoot(page: Page) {
        page.toRoot = true
        page.reelsRetried = false
        beginNavigation(page, loading = true)
        page.view.loadUrl(rootUrl(page.tab))
    }

    /** Puts [target] on top and hides the page that was shown. */
    private fun commit(target: Page) {
        val out = active
        target.view.visibility = View.VISIBLE
        target.view.onResume()
        target.view.bringToFront()
        active = target
        switchPending = false
        target.everShown = true
        target.lastShownAt = now()
        if (out !== target) hide(out)
        // A load that failed before the page came to the front is reported now.
        if (target.failed) controller.error = SiteError(target.errorText ?: "Couldn't load the page")
        sync()
    }

    private fun hide(page: Page) {
        if (page.dead) return
        page.view.evaluateJavascript(PageScripts.HIDE_SCRIPT) { if (!page.dead) page.hasDraft = it == "true" }
        page.view.onPause()
        page.view.visibility = View.INVISIBLE
        page.lastShownAt = now()
        if (page === overlay) {
            destroyPage(page)
            return
        }
        // Nothing keeps being viewed out of sight: a story or live would play on (and count the
        // user as watching), an open conversation could mark new messages as read.
        if (isBeingViewed(page.path)) leave(page)
        if (!page.settled && page !== settleTarget) watchSettling(page)
    }

    /** Takes a hidden page back to its latest history entry that isn't being viewed, else to its start. */
    private fun leave(page: Page) {
        val history = page.view.copyBackForwardList()
        val current = history.currentIndex
        for (i in current - 1 downTo 0) {
            val path = TabPaths.pathOf(history.getItemAtIndex(i)?.url) ?: continue
            if (!isBeingViewed(path)) {
                page.view.goBackOrForward(i - current)
                return
            }
        }
        page.toRoot = true
        page.settled = false
        page.view.loadUrl(rootUrl(page.tab))
    }

    /** Mirrors the shown page into the controller. */
    private fun sync() {
        val page = active
        if (page.dead) return
        controller.currentUrl = page.url.ifEmpty { controller.currentUrl }
        controller.isLoading = page.loading
        controller.canGoBack = if (controller.tabsLocked) page.view.canGoBack()
        else page === overlay || !isRoot(page) || page.tab != SiteTab.HOME
    }

    private fun hideKeyboard() {
        context.getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(stack.windowToken, 0)
    }

    // ---- Settling ---------------------------------------------------------------------------

    private fun beginNavigation(page: Page, loading: Boolean) {
        page.settled = false
        page.loading = loading
        controller.startNavigation()
        controller.isLoading = loading
        startSettle(page, controller.navigationCount)
    }

    private fun startSettle(page: Page, navigation: Int) {
        cancelSettle()
        page.settleWatch?.cancel()
        settleTarget = page
        settleJob = page.scope.launch { settle(page, navigation) }
    }

    /** Stops watching the current navigation; a page it left loading out of sight is watched quietly. */
    private fun cancelSettle() {
        settleJob?.cancel()
        settleJob = null
        val old = settleTarget ?: return
        settleTarget = null
        if (!old.dead && old !== active && !old.settled) watchSettling(old)
    }

    /** Watches [page] after a navigation until it has visibly settled (capped at 10 s), then lifts the veil. */
    private suspend fun settle(page: Page, navigation: Int) {
        val startedAt = now()
        var loadedAt = -1L
        var imagesSince = -1L
        while (now() - startedAt < 10_000) {
            delay(150)
            if (page.dead) return
            if (page.loading) continue
            if (loadedAt < 0) loadedAt = now() - startedAt
            val state = page.eval(PageStyle.SETTLED_SCRIPT, 1_000)
            if (state == "true") break
            // Layout is settled and only images are still arriving: give them a second, then reveal.
            if (state == "\"images\"") {
                if (imagesSince < 0) imagesSince = now()
                if (now() - imagesSince >= 1_000) break
            } else {
                imagesSince = -1L
            }
        }
        log("navigation $navigation (${describe(page)}): loaded ${loadedAt}ms, settled ${now() - startedAt}ms")
        if (!onSettled(page)) {
            startSettle(page, navigation)
            return
        }
        if (settleTarget === page) settleTarget = null
        settleJob = null
        if (navigation == controller.navigationCount) controller.finishNavigation()
        // Lazy lists (Explore's grid) only fetch more on scroll or resize; after an in-page switch
        // nothing has fired one yet, so the grid would stop after its first rows.
        if (page === active) page.view.evaluateJavascript(PageScripts.WAKE_LAZY_LOADING, null)
    }

    /** A hidden page finished loading: mark it settled once it visibly has. */
    private fun watchSettling(page: Page) {
        page.settleWatch?.cancel()
        page.settleWatch = page.scope.launch {
            val startedAt = now()
            while (now() - startedAt < 15_000) {
                delay(250)
                if (page.dead || page === settleTarget) return@launch
                if (page.loading) continue
                val state = page.eval(PageStyle.SETTLED_SCRIPT, 1_000)
                if (state == "true" || state == "\"images\"") {
                    if (!onSettled(page)) watchSettling(page)
                    return@launch
                }
            }
        }
    }

    /**
     * A page has settled. Returns false if it was sent elsewhere instead (Reels retry). Checks
     * for sign-in and security pages, and on the shown page whether the user is signed in.
     */
    private suspend fun onSettled(page: Page): Boolean {
        if (page.dead) return true
        val path = page.path
        val kind = AuthPaths.classify(path)
        if (kind != AuthPaths.Kind.NONE) {
            page.settled = true
            stopFor(page, kind)
            return true
        }
        // Instagram sometimes answers a fresh /reels/ load with the home feed: retry once.
        if (page.tab == SiteTab.REELS && page.toRoot && path == "/" && !page.reelsRetried) {
            page.reelsRetried = true
            session.autoLoads.record(now(), null)
            page.loading = true
            page.view.loadUrl(rootUrl(SiteTab.REELS))
            return false
        }
        page.settled = true
        if (page === active) checkAccount(page)
        return true
    }

    // ---- Account state ----------------------------------------------------------------------

    /**
     * Whether the user is signed in, read from the shown page: only the site's own link to the
     * user's profile counts as yes; a sign-in form counts as no; anything else leaves it open.
     */
    private suspend fun checkAccount(page: Page) {
        lastAccountCheck = now()
        val found = ProfileLink.parse(page.eval(ProfileLink.script))
        if (page.dead || page !== active) return
        if (found != null) {
            val known = profilePath
            if (account == Account.LOGGED_IN && known.isNotEmpty() && !known.equals(found, ignoreCase = true)) onAccountSwitch()
            if (!found.equals(known, ignoreCase = true)) profileFound(found)
            if (account != Account.LOGGED_IN) onLoggedIn(page)
        } else if (page.eval(PageScripts.LOGIN_FORM_SCRIPT) == "true") {
            stopFor(page, AuthPaths.Kind.LOGIN)
        }
    }

    /** Until the user is known to be signed in, the shown page is checked every few seconds (sign-in happens inside it). */
    private suspend fun recheckAccount() {
        val page = active
        if (page.dead || page.loading || now() - lastAccountCheck < ACCOUNT_RECHECK_MS) return
        if (AuthPaths.classify(page.path) != AuthPaths.Kind.NONE || TabPaths.pathOf(page.url) == null) return
        checkAccount(page)
    }

    private fun onLoggedIn(page: Page) {
        val fresh = account == Account.LOGGED_OUT || account == Account.CHALLENGE
        account = Account.LOGGED_IN
        controller.tabsLocked = false
        if (fresh) {
            // The sign-in happened in this page: it becomes the tab its address belongs to.
            rekey(page, page.path?.let { TabPaths.route(it, profilePath) } ?: SiteTab.HOME)
            page.view.clearHistory()
            // Give a fresh sign-in some quiet before anything loads in the background.
            session.preloadHoldUntil = maxOf(session.preloadHoldUntil, now() + FRESH_LOGIN_HOLD_MS)
        }
        log("signed in${if (fresh) " (fresh)" else ""}")
        sync()
    }

    private fun onAccountSwitch() {
        log("account changed: releasing background tabs")
        hiddenPages().forEach(::destroyPage)
        dropHelper()
        failedThisRun.clear()
        session.preloadHoldUntil = maxOf(session.preloadHoldUntil, now() + FRESH_LOGIN_HOLD_MS)
    }

    /**
     * A page reached sign-in or a security check. In a background tab: drop the background tabs
     * and load nothing more until the shown page is signed in again. On screen (or about to be):
     * that page becomes the only one, tabs are locked, and it is left entirely to the user.
     */
    private fun stopFor(page: Page, kind: AuthPaths.Kind) {
        if (page.dead) return
        if (kind == AuthPaths.Kind.CHALLENGE) session.challengeSeen = true
        if (page === incoming) commit(page)
        if (page !== active) {
            log("background tab reached ${kind.name.lowercase()}: releasing background tabs")
            page.view.stopLoading()
            hiddenPages().forEach(::destroyPage)
            dropHelper()
            if (account == Account.LOGGED_IN) account = Account.UNKNOWN
            session.preloadHoldUntil = maxOf(session.preloadHoldUntil, now() + FRESH_LOGIN_HOLD_MS)
            return
        }
        val next = if (kind == AuthPaths.Kind.CHALLENGE) Account.CHALLENGE else Account.LOGGED_OUT
        if (account == next && controller.tabsLocked) return
        log("shown page reached ${kind.name.lowercase()}: tabs locked")
        account = next
        controller.tabsLocked = true
        allPages().filter { it !== page }.forEach(::destroyPage)
        dropHelper()
        sync()
    }

    /** The reel-save helper ran into sign-in or a security check. */
    private fun onHelperAuthPage(kind: AuthPaths.Kind) {
        log("save helper reached ${kind.name.lowercase()}")
        if (kind == AuthPaths.Kind.CHALLENGE) session.challengeSeen = true
        if (account == Account.LOGGED_IN) account = Account.UNKNOWN
        session.preloadHoldUntil = maxOf(session.preloadHoldUntil, now() + FRESH_LOGIN_HOLD_MS)
        stack.post { dropHelper() }
    }

    /** Signs out: one page, everything wiped, back to the sign-in page. */
    private suspend fun signOut() {
        account = Account.LOGGED_OUT
        controller.tabsLocked = true
        cancelSettle()
        val home = pages[SiteTab.HOME] ?: createPage(SiteTab.HOME)
        selected = SiteTab.HOME
        controller.selectedTab = SiteTab.HOME
        commit(home)
        allPages().filter { it !== home }.forEach(::destroyPage)
        dropHelper()
        failedThisRun.clear()
        home.view.stopLoading()
        home.view.loadUrl("about:blank")
        WebStorage.getInstance().deleteAllData()
        home.view.clearCache(true)
        withTimeoutOrNull(5_000) {
            suspendCancellableCoroutine { cont -> CookieManager.getInstance().removeAllCookies { if (cont.isActive) cont.resume(Unit) } }
        }
        CookieManager.getInstance().flush()
        home.toRoot = true
        beginNavigation(home, loading = true)
        home.view.loadUrl(Site.HOME)
    }

    // ---- Background loading and memory ------------------------------------------------------

    /**
     * Runs while the app is in front: until the user is known to be signed in it watches for
     * that; then it loads the other tabs in the background, one at a time, while allowed.
     */
    suspend fun runPreloader() {
        while (true) {
            delay(1_000)
            if (account != Account.LOGGED_IN) {
                recheckAccount()
                continue
            }
            val tab = nextToPreload() ?: continue
            if (!preloadAllowed(tab)) continue
            if (active.eval(PageScripts.DIALOG_OPEN_SCRIPT, 500) != "false") continue
            // The user may have opened that tab meanwhile.
            if (nextToPreload() != tab || !preloadAllowed(tab)) continue
            preload(tab)
        }
    }

    /**
     * Next tab to load in the background. Messages is never loaded on its own (an open inbox may
     * show the user as active to the people they talk to); it is kept once the user opens it.
     */
    private fun nextToPreload(): SiteTab? = PRELOAD_ORDER.firstOrNull { tab ->
        tab != selected && tab !in failedThisRun && (tab != SiteTab.PROFILE || profilePath.isNotEmpty()) &&
            pages[tab].let { it == null || (it.stale && it !== active && it !== incoming && isRoot(it)) }
    }

    private fun preloadAllowed(tab: SiteTab): Boolean {
        val n = now()
        if (account != Account.LOGGED_IN || session.challengeSeen || n < session.preloadHoldUntil) return false
        // Not while the engine was lost recently: loading more is what likely got it killed.
        if (session.engineDeaths.any { n - it < MemoryBudget.DEATH_WINDOW_MS }) return false
        val shown = active
        if (shown.dead || shown === overlay || incoming != null || !shown.settled || shown.loading) return false
        if (AuthPaths.classify(shown.path) != AuthPaths.Kind.NONE) return false
        // Quiet moments only: the shown page has the engine to itself while the user is busy.
        if (n - stack.lastTouchAt < 2_000 || n - lastSwitchAt < 3_000) return false
        if (memory.dataSaverOn()) return false
        val reuse = pages[tab] != null
        if (!budget.mayPreload(n, memory.read(), hiddenPages().size - if (reuse) 1 else 0)) return false
        return session.autoLoads.allow(n, tab)
    }

    private suspend fun preload(tab: SiteTab) {
        session.autoLoads.record(now(), tab)
        val page = pages[tab] ?: createPage(tab)
        page.stale = false
        page.settled = false
        page.failed = false
        page.toRoot = true
        page.reelsRetried = false
        log("preloading ${tab.name.lowercase()}")
        page.view.loadUrl(rootUrl(tab))
        withTimeoutOrNull(12_000) {
            while (!page.dead && page !== active && page !== incoming && !page.settled && !page.failed) delay(250)
        }
        when {
            page.dead || page === active || page === incoming -> Unit
            page.failed || (page.settled && !isRoot(page)) -> {
                log("preloading ${tab.name.lowercase()} failed; it loads when opened")
                failedThisRun += tab
                destroyPage(page)
            }
        }
        budget.onPreloadDone(now())
    }

    /** Re-reads free memory and releases background tabs if it got tight. */
    fun sampleMemory() {
        val sample = memory.read()
        val hidden = hiddenPages().size
        val keep = budget.onSample(now(), sample, hidden)
        log("memory: ${sample.availableBytes / MIB} MB free, keep ${budget.cap} background tabs (have $hidden)")
        evictDownTo(keep)
    }

    fun onTrimMemory(level: Int) {
        val hidden = hiddenPages().size
        val keep = when {
            level >= ComponentCallbacks2.TRIM_MEMORY_COMPLETE || level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL -> budget.onPressure(now())
            level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW -> budget.onShrinkSignal(now(), hidden)
            // Sent on every switch to another app; only real shortage releases anything.
            level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND -> budget.onBackground(now(), memory.read(), hidden)
            else -> return
        }
        log("trim level $level: keep $keep background tabs (have $hidden)")
        evictDownTo(keep)
    }

    fun onLowMemory() = evictDownTo(budget.onPressure(now()))

    private fun enforceCap() {
        if (budget.cap >= 0) evictDownTo(minOf(hiddenPages().size, budget.cap))
    }

    /** Releases background tabs until [keep] remain: the least useful and least recently used first. */
    private fun evictDownTo(keep: Int) {
        var hidden = hiddenPages()
        while (hidden.size > keep) {
            val victim = hidden.minWith(compareBy<Page>({ evictionRank(it) }, { it.lastShownAt }))
            log("releasing ${describe(victim)} for memory")
            destroyPage(victim)
            hidden = hiddenPages()
        }
    }

    private fun evictionRank(page: Page): Int = when {
        page.tab == SiteTab.HOME -> 3
        page.tab == startTab -> 2
        page.hasDraft || !isRoot(page) -> 1
        else -> 0
    }

    // ---- Lifecycle --------------------------------------------------------------------------

    fun onStart() {
        if (started) return
        started = true
        livePage()?.view?.resumeTimers()
        if (!active.dead) active.view.onResume()
        if (recovery != null && !rendererGone) rebuild()
        // Long away: tabs in the background are refreshed (in quiet moments) before they're used.
        if (backgroundedAt > 0 && now() - backgroundedAt >= STALE_AFTER_MS) {
            hiddenPages().filter { isRoot(it) }.forEach { it.stale = true }
        }
    }

    fun onStop() {
        if (!started) return
        started = false
        backgroundedAt = now()
        if (!active.dead) active.view.onPause()
        // Pages do no work at all while the app is out of sight.
        livePage()?.view?.pauseTimers()
    }

    private fun livePage(): Page? = allPages().firstOrNull { !it.dead }

    fun destroy() {
        settleJob?.cancel()
        allPages().forEach { page ->
            page.dead = true
            page.cancelWork()
            stack.removeView(page.view)
            page.view.destroy()
        }
        pages.clear()
        overlay = null
        dropHelper()
    }

    // ---- Engine loss ------------------------------------------------------------------------

    /**
     * The browser engine process is gone (killed for memory or crashed). All views share it, so
     * every page is lost: rebuild the shown tab and keep fewer tabs from now on.
     */
    private fun onRendererGone(detail: RenderProcessGoneDetail): Boolean {
        if (rendererGone) return true
        rendererGone = true
        val crashed = detail.didCrash()
        Log.w(TAG, "engine gone (crashed=$crashed, priority=${detail.rendererPriorityAtExit()})")
        val shown = active
        val tab = shown.tab ?: selected
        val resume = shown.url.takeIf { url ->
            val path = TabPaths.pathOf(url)
            shown !== overlay && path != null && AuthPaths.classify(path) == AuthPaths.Kind.NONE && !isBeingViewed(path)
        }
        // Around sign-in or a security check the app never reloads on its own.
        val locked = controller.tabsLocked || session.challengeSeen
        settleJob?.cancel()
        allPages().forEach { it.dead = true; it.cancelWork() }
        stack.post {
            allPages().forEach { page ->
                stack.removeView(page.view)
                page.view.destroy()
            }
            pages.clear()
            overlay = null
            dropHelper()
            val n = now()
            session.engineDeaths += n
            session.engineDeaths.removeAll { n - it > MemoryBudget.DEATH_WINDOW_MS }
            budget.onRendererDeath(n, crashed)
            val recent = session.engineDeaths.count { n - it < REPEATED_DEATHS_MS }
            rendererGone = false
            recovery = Recovery(tab, resume.takeIf { recent < 2 }, load = !locked && recent < 3)
            if (started) rebuild()
        }
        return true
    }

    private fun rebuild() {
        val plan = recovery ?: Recovery(selected, null, load = true)
        recovery = null
        val page = createPage(plan.tab)
        commit(page)
        selected = plan.tab
        controller.selectedTab = plan.tab
        page.view.resumeTimers()
        if (plan.load) {
            page.toRoot = plan.url == null
            beginNavigation(page, loading = true)
            page.view.loadUrl(plan.url ?: rootUrl(plan.tab))
        } else {
            // Keeps stopping, or the user was signing in: wait for the user to ask.
            page.loading = false
            controller.error = SiteError("Instagram stopped. Try again to reload it.")
            sync()
        }
    }

    // ---- Pages ------------------------------------------------------------------------------

    private fun createPage(tab: SiteTab?): Page {
        val view = WebView(context)
        view.applyHardening()
        val night = (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        view.setBackgroundColor(if (night) android.graphics.Color.BLACK else android.graphics.Color.WHITE)
        view.isVerticalScrollBarEnabled = false
        view.isHorizontalScrollBarEnabled = false
        val page = Page(tab, view, scope)
        if (documentStartScripts) {
            WebViewCompat.addDocumentStartJavaScript(view, PageScripts.MEDIA_GUARD, setOf(TabPaths.ORIGIN))
        }
        view.webViewClient = PageClient(page)
        view.webChromeClient = PageChromeClient(page)
        if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            WebViewCompat.addWebMessageListener(view, ReelSave.BRIDGE, setOf(TabPaths.ORIGIN)) { _, message, _, isMainFrame, reply ->
                val request = ReelSave.parse(message.data)
                if (isMainFrame && request != null && !page.dead) {
                    if (page !== active || account != Account.LOGGED_IN || session.challengeSeen) {
                        // Only the shown tab of a signed-in user, and never around a security check.
                        reply.postMessage(ReelSave.reply(request.code, null))
                    } else {
                        page.scope.launch {
                            val saved = ensureHelper()?.handle(request)
                            if (!page.dead) reply.postMessage(ReelSave.reply(request.code, saved))
                        }
                    }
                }
            }
        }
        view.visibility = View.INVISIBLE
        view.onPause()
        // New pages go to the bottom, so they never cover the shown one. MATCH_PARENT matters:
        // with wrap-content height the WebView reports a zero-height viewport.
        stack.addView(view, 0, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT, Gravity.TOP))
        if (tab != null) {
            pages[tab]?.takeIf { it !== page }?.let(::destroyPage)
            pages[tab] = page
        }
        return page
    }

    private fun destroyPage(page: Page) {
        if (page.view.parent == null && page.dead) return
        page.dead = true
        page.cancelWork()
        pages.entries.removeAll { it.value === page }
        if (overlay === page) overlay = null
        if (incoming === page) incoming = null
        if (settleTarget === page) {
            // Its veil would otherwise wait for a page that is gone.
            settleTarget = null
            controller.finishNavigation()
        }
        stack.removeView(page.view)
        page.view.stopLoading()
        page.view.destroy()
    }

    private fun ensureHelper(): ReelSaveHelper? {
        helper?.let { return it }
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) return null
        return ReelSaveHelper(context, scope, ::onRendererGone, ::onHelperAuthPage).also {
            helper = it
            stack.addView(it.view, 0, FrameLayout.LayoutParams(1, 1))
        }
    }

    private fun dropHelper() {
        helper?.destroy()
        helper = null
    }

    /** Pages keep space at the bottom for the site's hidden tab bar; extend them so it ends up out of view. */
    private fun applyHeight(page: Page) {
        if (page.dead) return
        val params = page.view.layoutParams as? FrameLayout.LayoutParams ?: return
        val height = if (page.reservedPx > 0 && stack.height > 0) stack.height + page.reservedPx else FrameLayout.LayoutParams.MATCH_PARENT
        if (params.height != height) {
            params.height = height
            page.view.layoutParams = params
        }
    }

    private fun setReserved(page: Page, cssPx: Double) {
        val px = (cssPx * density).roundToInt()
        if (px != page.reservedPx) {
            page.reservedPx = px
            applyHeight(page)
        }
    }

    /** Follows the space the shown page keeps for the site's (hidden) tab bar. */
    suspend fun pollReserved() {
        val page = active
        val css = page.eval(PageStyle.RESERVED_BOTTOM_SCRIPT, 500)?.trim('"')?.toDoubleOrNull() ?: return
        if (page === active) setReserved(page, css)
    }

    fun evaluateEverywhere(script: String) = allPages().filter { !it.dead }.forEach { it.view.evaluateJavascript(script, null) }

    private inner class PageClient(private val page: Page) : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            if (request.isAllowed()) return false
            if (request.isForMainFrame) openExternal(request.url.toString())
            return true
        }

        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
            if (request.isAllowed()) null else blockedResponse()

        override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
            if (page.dead) return
            page.loading = true
            page.failed = false
            page.errorText = null
            page.url = url
            if (page === active) sync()
            checkPath()
        }

        override fun onPageFinished(view: WebView, url: String) {
            if (page.dead) return
            view.evaluateJavascript(PageStyle.script(settings()), null)
            view.evaluateJavascript(ReelSave.PAGE_SCRIPT, null)
            if (!documentStartScripts) view.evaluateJavascript(PageScripts.MEDIA_GUARD, null)
            page.loading = false
            page.loadedOnce = true
            page.url = url
            if (page === active) sync()
            else if (!page.settled && page !== settleTarget) watchSettling(page)
        }

        override fun doUpdateVisitedHistory(view: WebView, url: String, isReload: Boolean) {
            if (page.dead) return
            view.evaluateJavascript(ReelSave.PAGE_SCRIPT, null)
            page.url = url
            // The app sent this page to its tab's start and it got there: that is its new beginning.
            if (page.toRoot && isRoot(page)) {
                page.toRoot = false
                view.clearHistory()
            }
            if (page === active) sync()
            checkPath()
        }

        override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
            if (!request.isForMainFrame || page.dead) return
            page.failed = true
            page.loading = false
            page.errorText = error.description?.toString()
            if (page === active) {
                controller.error = SiteError(page.errorText ?: "Couldn't load the page")
                sync()
            }
        }

        override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, errorResponse: WebResourceResponse) {
            if (request.isForMainFrame && (errorResponse.statusCode >= 500 || errorResponse.statusCode == 429)) page.failed = true
        }

        override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean = onRendererGone(detail)

        /** Sign-in and security pages stop background loading; out of sight nothing stays being viewed. */
        private fun checkPath() {
            val path = page.path
            val kind = AuthPaths.classify(path)
            if (kind != AuthPaths.Kind.NONE) {
                stack.post { stopFor(page, kind) }
            } else if (isBeingViewed(path)) {
                // A story or thread that opened after the tab was hidden (it was still loading).
                stack.post { if (!page.dead && page !== active && page !== incoming && isBeingViewed(page.path)) leave(page) }
            }
        }
    }

    private inner class PageChromeClient(private val page: Page) : WebChromeClient() {
        override fun onProgressChanged(view: WebView, newProgress: Int) {
            if (page === active) controller.progress = newProgress / 100f
        }

        override fun onPermissionRequest(request: PermissionRequest) {
            // No camera or microphone access for the site.
            request.deny()
        }

        override fun onShowFileChooser(webView: WebView, filePathCallback: ValueCallback<Array<Uri>>, fileChooserParams: FileChooserParams): Boolean =
            showFileChooser(filePathCallback, fileChooserParams)
    }

    // ---- Diagnostics (adb only; see SiteView) -----------------------------------------------

    suspend fun diagnose(intent: Intent) {
        when (intent.action) {
            DIAG_FAKE_FREE -> {
                memory.fakeFreeMib = intent.getLongExtra("mb", 0L)
                log("diagnostics: free memory ${if (memory.fakeFreeMib > 0) "faked at ${memory.fakeFreeMib} MB" else "real"}")
                sampleMemory()
            }
            DIAG_KILL_ENGINE -> if (WebViewFeature.isFeatureSupported(WebViewFeature.GET_WEB_VIEW_RENDERER)) {
                WebViewCompat.getWebViewRenderProcess(active.view)?.terminate()
            }
            DIAG_DUMP -> {
                val sample = memory.read()
                log("dump: account=$account selected=$selected shown=${describe(active)} cap=${budget.cap} ceiling=${budget.ceiling} " +
                    "free=${sample.availableBytes / MIB}MB threshold=${sample.thresholdBytes / MIB}MB " +
                    "deaths=${session.engineDeaths.size} locked=${controller.tabsLocked} challenge=${session.challengeSeen}")
                allPages().forEach { page ->
                    val visibility = page.eval("document.visibilityState", 500)
                    log("  ${describe(page)}: shown=${page === active} settled=${page.settled} loading=${page.loading} " +
                        "visibility=$visibility reserved=${page.reservedPx}px stale=${page.stale}")
                }
            }
        }
    }

    /** A page's tab and whether it is at the tab's start; never the address (it may name people). */
    private fun describe(page: Page): String =
        (page.tab?.name?.lowercase() ?: "link") + if (page.tab != null && !isRoot(page)) " (inside)" else ""

    private fun log(message: String) = Log.d(TAG, message)

    companion object {
        const val DIAG_DUMP = "app.veil.diag.DUMP"
        const val DIAG_FAKE_FREE = "app.veil.diag.FAKE_FREE"
        const val DIAG_KILL_ENGINE = "app.veil.diag.KILL_ENGINE"

        private const val TAG = "Veil"
        private const val MIB = 1024L * 1024
        private val PRELOAD_ORDER = listOf(SiteTab.HOME, SiteTab.REELS, SiteTab.SEARCH, SiteTab.PROFILE)
        private val LIVE = Regex("^/[^/]+/live/")

        private const val VEIL_COVER_MS = 225L
        private const val FIRST_REVEAL_MS = 900L
        private const val REVEAL_MS = 400L
        private const val ACCOUNT_RECHECK_MS = 3_000L
        private const val FRESH_LOGIN_HOLD_MS = 5 * 60_000L
        private const val STALE_AFTER_MS = 30 * 60_000L
        private const val REPEATED_DEATHS_MS = 5 * 60_000L

        /** Pages where the user is watching or reading something live: never left open out of sight. */
        fun isBeingViewed(path: String?): Boolean =
            path != null && (path.startsWith("/stories/") || path.startsWith("/direct/t/") || LIVE.containsMatchIn(path))
    }
}
