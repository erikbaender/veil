package app.veil.ui.site

import android.content.Context
import android.os.SystemClock
import android.view.MotionEvent
import android.webkit.WebView
import android.widget.FrameLayout
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import app.veil.core.web.SiteTab
import app.veil.core.web.TabPaths
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/** One tab's WebView and what the app knows about it. [tab] is null for a link opened on top. */
internal class Page(var tab: SiteTab?, val view: WebView, parent: CoroutineScope) {
    /** Work tied to this page; cancelled when the page is destroyed. */
    val scope = CoroutineScope(parent.coroutineContext + SupervisorJob(parent.coroutineContext[Job]))

    /** Destroyed (or its engine is gone): never touch [view] again. */
    var dead = false
    var url = ""
    var loading = true
    var loadedOnce = false
    /** Visibly settled since its last load or app navigation: can be shown without the veil. */
    var settled = false
    /** Its last load failed (network error, server error). */
    var failed = false
    var errorText: String? = null
    var everShown = false
    var lastShownAt = 0L
    /** Space (px) the page keeps at the bottom for the site's own (hidden) tab bar. */
    var reservedPx = 0
    /** Typed text was on the page when it was hidden; released last. */
    var hasDraft = false
    /** Loaded long ago (app was away for a while); refreshed in the background when allowed. */
    var stale = false
    /** The app sent this page to its tab's start: its history is cleared once it gets there. */
    var toRoot = false
    var reelsRetried = false
    var settleWatch: Job? = null

    val path: String? get() = TabPaths.pathOf(url)

    /** Runs [script] and returns its JSON result, or null if the page is gone or doesn't answer in time. */
    suspend fun eval(script: String, timeoutMs: Long = 1_500): String? {
        if (dead) return null
        return withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine { cont ->
                view.evaluateJavascript(script) { result -> if (cont.isActive) cont.resume(result.orEmpty()) }
            }
        }
    }

    /** Suspends until the page's current content is ready to draw; false if that can't be known. */
    suspend fun awaitVisualState(): Boolean {
        if (dead || !WebViewFeature.isFeatureSupported(WebViewFeature.VISUAL_STATE_CALLBACK)) return false
        return suspendCancellableCoroutine { cont ->
            WebViewCompat.postVisualStateCallback(view, SystemClock.uptimeMillis()) { if (cont.isActive) cont.resume(true) }
        }
    }

    fun cancelWork() = scope.cancel()
}

/**
 * Holds the tab pages on top of each other. Notes when the user last touched the screen, so
 * background loading waits for quiet moments, and reports its height for the pages' sizes.
 */
internal class PageStack(context: Context) : FrameLayout(context) {
    var lastTouchAt = 0L
        private set
    var onHeightChanged: ((Int) -> Unit)? = null

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        lastTouchAt = SystemClock.elapsedRealtime()
        return super.dispatchTouchEvent(ev)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (h != oldh) post { onHeightChanged?.invoke(h) }
    }
}
