package app.veil.ui.site

import android.content.BroadcastReceiver
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import app.veil.core.settings.Settings
import app.veil.core.web.PageStyle
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
actual fun SiteView(
    controller: SiteController,
    settings: Settings,
    onOpenExternal: (String) -> Unit,
    onProfileFound: (String) -> Unit,
    modifier: Modifier,
) {
    val openExternal by rememberUpdatedState(onOpenExternal)
    val currentSettings by rememberUpdatedState(settings)
    val profileFound by rememberUpdatedState(onProfileFound)
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle

    // File uploads (posting, profile photo, sending photos) go through the system Photo Picker,
    // so the app never needs storage permissions.
    var pendingUpload by remember { mutableStateOf<ValueCallback<Array<Uri>>?>(null) }
    val pickOne = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        pendingUpload?.onReceiveValue(listOfNotNull(uri).toTypedArray())
        pendingUpload = null
    }
    val pickMany = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia()) { uris ->
        pendingUpload?.onReceiveValue(uris.toTypedArray())
        pendingUpload = null
    }
    val showFileChooser: (ValueCallback<Array<Uri>>, WebChromeClient.FileChooserParams) -> Boolean = { callback, params ->
        pendingUpload?.onReceiveValue(null)
        pendingUpload = callback
        val accept = params.acceptTypes.joinToString(",")
        val type = when {
            accept.contains("video") && !accept.contains("image") -> ActivityResultContracts.PickVisualMedia.VideoOnly
            accept.contains("image") && !accept.contains("video") -> ActivityResultContracts.PickVisualMedia.ImageOnly
            else -> ActivityResultContracts.PickVisualMedia.ImageAndVideo
        }
        val request = PickVisualMediaRequest(type)
        if (params.mode == WebChromeClient.FileChooserParams.MODE_OPEN_MULTIPLE) pickMany.launch(request) else pickOne.launch(request)
        true
    }

    val host = remember {
        SiteHost(
            context, controller, scope, { currentSettings }, { openExternal(it) },
            { cb, params -> showFileChooser(cb, params) }, { profileFound(it) },
        )
    }

    AndroidView(modifier = modifier, factory = { host.stack })

    // Requests are handled one at a time; a newer tap supersedes a switch still in progress.
    // Signing out always runs to the end.
    LaunchedEffect(host) {
        controller.requests.receiveAsFlow().collectLatest { request ->
            if (request is SiteRequest.Clear) withContext(NonCancellable) { host.handle(request) } else host.handle(request)
        }
    }
    LaunchedEffect(host, settings) {
        host.evaluateEverywhere(PageStyle.script(settings))
    }
    // While the app is in front: follow free memory, load other tabs in quiet moments, and keep
    // the shown page sized for the site's hidden tab bar.
    LaunchedEffect(host, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            launch {
                while (true) {
                    host.sampleMemory()
                    delay(15_000)
                }
            }
            launch { host.runPreloader() }
            launch {
                while (true) {
                    host.pollUnread()
                    delay(10_000)
                }
            }
            launch {
                while (true) {
                    host.pollReserved()
                    delay(500)
                }
            }
        }
    }
    DisposableEffect(host, lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> host.onStart()
                Lifecycle.Event.ON_STOP -> host.onStop()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    DisposableEffect(host) {
        val callbacks = object : ComponentCallbacks2 {
            override fun onTrimMemory(level: Int) = host.onTrimMemory(level)

            override fun onConfigurationChanged(newConfig: Configuration) = Unit

            @Deprecated("Deprecated in Java")
            override fun onLowMemory() = host.onLowMemory()
        }
        context.applicationContext.registerComponentCallbacks(callbacks)
        // Diagnostics for testing over adb: only senders holding DUMP (the shell) can reach it.
        val diagnostics = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                scope.launch { host.diagnose(intent) }
            }
        }
        val filter = IntentFilter().apply {
            addAction(SiteHost.DIAG_DUMP)
            addAction(SiteHost.DIAG_FAKE_FREE)
            addAction(SiteHost.DIAG_KILL_ENGINE)
        }
        if (Build.VERSION.SDK_INT >= 33) {
            context.registerReceiver(diagnostics, filter, android.Manifest.permission.DUMP, null, Context.RECEIVER_EXPORTED)
        } else {
            context.registerReceiver(diagnostics, filter, android.Manifest.permission.DUMP, null)
        }
        onDispose {
            context.applicationContext.unregisterComponentCallbacks(callbacks)
            context.unregisterReceiver(diagnostics)
        }
    }
    // Back at the Home tab's start leaves the app without closing it, so every tab is still
    // there on return (also when the app was opened from a link rather than the launcher).
    val activity = LocalActivity.current
    BackHandler {
        if (controller.canGoBack) controller.back() else activity?.moveTaskToBack(true)
    }
    // Destroy the WebViews only when this screen leaves composition.
    DisposableEffect(host) {
        onDispose { host.destroy() }
    }
}
