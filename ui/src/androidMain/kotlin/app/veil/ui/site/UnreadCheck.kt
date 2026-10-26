package app.veil.ui.site

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import android.view.View
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import app.veil.core.web.AuthPaths
import app.veil.core.web.PageScripts
import app.veil.core.web.TabPaths
import app.veil.core.web.UnreadChat
import app.veil.core.web.UnreadMessages
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * One background look at the unread-chats badge: an invisible WebView (same login, same
 * hardening) opens Instagram's start page without pictures or video and reads the number from
 * the site's own Messages link. Only if there are more unread chats than the user knew about, it
 * goes on to the inbox list to read who wrote. It never opens a chat, so nothing is marked as read.
 */
object UnreadCheck {
    sealed interface Result {
        /** [chats]: the unread chats named in the inbox, empty if they weren't looked up or found. */
        data class Count(val unread: Int, val chats: List<UnreadChat> = emptyList()) : Result
        /** The page showed sign-in or a security check; leave it to the user. */
        data class Blocked(val kind: AuthPaths.Kind) : Result
        /** No answer (offline, slow, the page changed). */
        data object Unknown : Result
    }

    /** True while the app's own screen is in front; its badge is live there, no check needed. */
    val appInFront: Boolean get() = HostSession.inFront

    /** [knownUnread]: unread chats the user already knows about; the inbox is read only beyond these. */
    suspend fun run(context: Context, knownUnread: Int): Result = withContext(Dispatchers.Main) {
        // A security check was seen in this run of the app: nothing loads on its own.
        if (HostSession.challengeSeen) return@withContext Result.Blocked(AuthPaths.Kind.CHALLENGE)
        val blocked = CompletableDeferred<AuthPaths.Kind>()
        val view = WebView(context.applicationContext)
        view.applyHardening()
        // Off screen it has no size, and the inbox would draw only the rows that fit: give it a phone screen's.
        val screen = context.resources.displayMetrics
        view.measure(
            View.MeasureSpec.makeMeasureSpec(screen.widthPixels, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(screen.heightPixels, View.MeasureSpec.EXACTLY),
        )
        view.layout(0, 0, screen.widthPixels, screen.heightPixels)
        view.settings.blockNetworkImage = true
        view.settings.mediaPlaybackRequiresUserGesture = true
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            WebViewCompat.addDocumentStartJavaScript(view, PageScripts.HELPER_MEDIA_OFF, setOf(TabPaths.ORIGIN))
        }
        view.webViewClient = object : WebViewClient() {
            // The engine is shared with the app's pages, whose host handles its loss.
            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean = true

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean = !request.isAllowed()

            // Only the site itself: no media from the CDNs (pictures, video), so a check costs little data.
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                if (request.isAllowed() && !isMedia(request)) null else blockedResponse()

            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) = checkAuth(view, url)

            override fun doUpdateVisitedHistory(view: WebView, url: String, isReload: Boolean) = checkAuth(view, url)

            private fun checkAuth(view: WebView, url: String) {
                val kind = AuthPaths.classify(TabPaths.pathOf(url))
                if (kind == AuthPaths.Kind.NONE) return
                view.stopLoading()
                blocked.complete(kind)
            }
        }
        // The app's screen pauses the engine's timers while it is away; run them for the check.
        view.resumeTimers()
        try {
            view.loadUrl(TabPaths.ORIGIN + "/")
            val found = withTimeoutOrNull(TIMEOUT_MS) { read(view, blocked) } ?: Result.Unknown
            if (found is Result.Count && found.unread > knownUnread) {
                val chats = withTimeoutOrNull(TIMEOUT_MS) { readChats(view, blocked, found.unread) }
                if (blocked.isCompleted) Result.Blocked(blocked.getCompleted()) else found.copy(chats = chats.orEmpty())
            } else {
                found
            }
        } finally {
            view.stopLoading()
            if (!HostSession.inFront) view.pauseTimers()
            view.destroy()
        }
    }

    private suspend fun read(view: WebView, blocked: CompletableDeferred<AuthPaths.Kind>): Result {
        delay(2_000)
        while (true) {
            if (blocked.isCompleted) return Result.Blocked(blocked.getCompleted())
            val answer = eval(view, UnreadMessages.SCRIPT)
            val count = UnreadMessages.parse(answer)
            if (count != null) {
                // The badge can arrive a moment after the link; give it a little longer to show.
                if (count == 0) {
                    delay(3_000)
                    if (blocked.isCompleted) return Result.Blocked(blocked.getCompleted())
                    return Result.Count(UnreadMessages.parse(eval(view, UnreadMessages.SCRIPT)) ?: 0)
                }
                return Result.Count(count)
            }
            if (eval(view, PageScripts.LOGIN_FORM_SCRIPT) == "true") return Result.Blocked(AuthPaths.Kind.LOGIN)
            delay(1_000)
        }
    }

    /** Opens the inbox list and reads its unread chats; null if it didn't show them in time. */
    private suspend fun readChats(view: WebView, blocked: CompletableDeferred<AuthPaths.Kind>, unread: Int): List<UnreadChat>? {
        view.loadUrl(TabPaths.ORIGIN + UnreadMessages.INBOX_PATH)
        delay(2_000)
        val wanted = minOf(unread, UnreadMessages.MAX_CHATS)
        var best: List<UnreadChat>? = null
        var unchanged = 0
        repeat(20) {
            if (blocked.isCompleted) return null
            val chats = UnreadMessages.parseChats(eval(view, UnreadMessages.CHATS_SCRIPT))
            if (chats != null) {
                if (chats.size >= wanted) return chats
                // The list fills in over a moment (rows, then their unread marks): take what it
                // shows once it has stopped growing.
                if (chats.size > (best?.size ?: -1)) { best = chats; unchanged = 0 } else if (++unchanged >= 3) return best
            }
            delay(1_000)
        }
        return best
    }

    private fun isMedia(request: WebResourceRequest): Boolean {
        val host = request.url.host.orEmpty()
        val path = request.url.path.orEmpty()
        return host.startsWith("scontent") || host.startsWith("video") || path.endsWith(".mp4") ||
            request.requestHeaders["Accept"]?.startsWith("image/") == true
    }

    private suspend fun eval(view: WebView, script: String): String = withTimeoutOrNull(1_500) {
        suspendCancellableCoroutine { cont ->
            view.evaluateJavascript(script) { result -> if (cont.isActive) cont.resume(result.orEmpty()) }
        }
    }.orEmpty().also { if (it.isEmpty()) Log.d("Veil", "unread check: page didn't answer") }

    private const val TIMEOUT_MS = 45_000L
}
