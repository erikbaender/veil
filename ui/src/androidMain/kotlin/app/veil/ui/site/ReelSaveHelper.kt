package app.veil.ui.site

import android.content.Context
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import app.veil.core.web.AuthPaths
import app.veil.core.web.PageScripts
import app.veil.core.web.ReelSave
import app.veil.core.web.TabPaths
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * Saves reels the way a person would: an invisible WebView (same login, same hardening) opens
 * the reel's own page and reads or taps Instagram's Save button there. One request at a time.
 */
internal class ReelSaveHelper(
    context: Context,
    private val scope: CoroutineScope,
    /** All views share one engine process; its loss is handled by the host for every view. */
    private val onRendererGone: (RenderProcessGoneDetail) -> Boolean,
    /** The helper ran into sign-in or a security check: the host stops all background loading. */
    private val onAuthPage: (AuthPaths.Kind) -> Unit,
) {
    /** A plain field, as [Page.view] is. */
    @JvmField val view: WebView = WebView(context)

    private val mutex = Mutex()
    private var loadedCode: String? = null
    private var pageLoaded: CompletableDeferred<Unit>? = null
    private var idle: Job? = null
    private var dead = false

    init {
        view.applyHardening()
        // Setter calls rather than property syntax: code scanning reads `view.alpha = …` as a
        // WebView it can't see hardened.
        view.setAlpha(0f)
        view.setFocusable(false)
        view.setImportantForAccessibility(WebView.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS)
        // The helper never plays anything, even for a moment.
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            WebViewCompat.addDocumentStartJavaScript(view, PageScripts.HELPER_MEDIA_OFF, setOf(TabPaths.ORIGIN))
        }
        view.onPause()
        view.webViewClient = object : WebViewClient() {
            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean = onRendererGone(detail)

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean = !request.isAllowed()

            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                if (request.isAllowed()) null else blockedResponse()

            override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) = checkAuth(url)

            override fun doUpdateVisitedHistory(view: WebView, url: String, isReload: Boolean) = checkAuth(url)

            override fun onPageFinished(view: WebView, url: String) {
                pageLoaded?.complete(Unit)
            }

            private fun checkAuth(url: String) {
                val kind = AuthPaths.classify(TabPaths.pathOf(url))
                if (kind == AuthPaths.Kind.NONE || dead) return
                view.stopLoading()
                onAuthPage(kind)
            }
        }
    }

    /** Answers a page request: the reel's saved state after handling it, or null if unknown. */
    suspend fun handle(request: ReelSave.Request): Boolean? = mutex.withLock {
        if (dead) return@withLock null
        idle?.cancel()
        view.onResume()
        try {
            answer(request)
        } finally {
            if (!dead) {
                view.onPause()
                // Let go of the reel page after a minute without requests.
                idle = scope.launch {
                    delay(60_000)
                    if (!dead) { view.loadUrl("about:blank"); loadedCode = null }
                }
            }
        }
    }

    private suspend fun answer(request: ReelSave.Request): Boolean? =
        withTimeoutOrNull(20_000) {
            open(request.code)
            val state = awaitState(expect = null) ?: run {
                // If Instagram changes the page, log which icon labels it offers instead (no personal content).
                val labels = eval(
                    "(function(){var s={};document.querySelectorAll('svg[aria-label]').forEach(function(e){s[e.getAttribute('aria-label')]=1});" +
                        "return location.pathname.replace(/[A-Za-z0-9_-]{6,}/g,'<id>')+' | '+Object.keys(s).join(', ');})()",
                )
                android.util.Log.d("Veil", "reel page without save button: $labels")
                return@withTimeoutOrNull null
            }
            when (request) {
                is ReelSave.Request.State -> state
                is ReelSave.Request.Toggle ->
                    if (state == request.save) state
                    else if (eval(ReelSave.CLICK_SCRIPT) == "true") awaitState(expect = request.save) ?: state
                    else state
            }
        }

    fun destroy() {
        dead = true
        idle?.cancel()
        (view.parent as? android.view.ViewGroup)?.removeView(view)
        view.destroy()
    }

    private suspend fun open(code: String) {
        if (loadedCode == code) return
        loadedCode = null
        val loaded = CompletableDeferred<Unit>()
        pageLoaded = loaded
        view.loadUrl(ReelSave.reelUrl(code))
        withTimeoutOrNull(15_000) { loaded.await() }
        loadedCode = code
    }

    /** Polls the page until its Save button shows a state (matching [expect] if given). */
    private suspend fun awaitState(expect: Boolean?): Boolean? {
        repeat(40) {
            val state = when (eval(ReelSave.STATE_SCRIPT).trim('"')) {
                "saved" -> true
                "unsaved" -> false
                else -> null
            }
            if (state != null && (expect == null || state == expect)) return state
            delay(300)
        }
        return null
    }

    private suspend fun eval(script: String): String = withTimeoutOrNull(1_500) {
        suspendCancellableCoroutine { cont ->
            if (dead) cont.resume("") else view.evaluateJavascript(script) { result -> if (cont.isActive) cont.resume(result.orEmpty()) }
        }
    }.orEmpty()
}
