package app.veil.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.veil.core.settings.Settings
import app.veil.core.settings.SettingsRepository
import app.veil.core.settings.StartTab
import app.veil.core.web.ProfileLink
import app.veil.core.web.SiteTab
import app.veil.ui.settings.SettingsScreen
import app.veil.ui.shell.BottomBar
import app.veil.ui.shell.LoadingVeil
import app.veil.ui.shell.Tab
import app.veil.ui.site.SiteController
import app.veil.ui.site.SiteError
import app.veil.ui.site.SiteView
import kotlinx.coroutines.launch

private val Blue = Color(0xFF0095F6)
private val LightColors = lightColorScheme(
    primary = Blue, background = Color.White, surface = Color.White, onSurface = Color.Black,
    outlineVariant = Color(0xFFDBDBDB),
)
private val DarkColors = darkColorScheme(
    primary = Blue, background = Color.Black, surface = Color.Black, onSurface = Color(0xFFF5F5F5),
    outlineVariant = Color(0xFF262626),
)

@Composable
fun VeilApp(
    settingsRepository: SettingsRepository,
    versionName: String,
    /** Tab to open at launch (the last used of Home and Reels). */
    startTab: StartTab,
    incomingUrl: String?,
    onIncomingUrlConsumed: () -> Unit,
    onOpenExternal: (String) -> Unit,
) {
    val settings by settingsRepository.settings.collectAsStateWithLifecycle()
    val controller = remember { SiteController(if (startTab == StartTab.REELS) SiteTab.REELS else SiteTab.HOME) }
    val scope = rememberCoroutineScope()
    var showSettings by remember { mutableStateOf(false) }
    val update: ((Settings) -> Settings) -> Unit = { transform -> scope.launch { settingsRepository.update(transform) } }

    val currentTab = Tab.of(controller.selectedTab)
    // Remember the last used of Home and Reels for the next launch.
    LaunchedEffect(currentTab) {
        val start = when (currentTab) {
            Tab.Home -> StartTab.HOME
            Tab.Reels -> StartTab.REELS
            else -> null
        }
        if (start != null) update { if (it.startTab == start) it else it.copy(startTab = start) }
    }
    // Remember which own-profile tab (posts, Reels, Saved, tagged) was open last.
    LaunchedEffect(controller.currentUrl, settings.profilePath, currentTab) {
        if (currentTab != Tab.Profile) return@LaunchedEffect
        val section = ProfileLink.section(controller.currentUrl, settings.profilePath)
        if (section != null) update { if (it.profileSection == section) it else it.copy(profileSection = section) }
    }

    LaunchedEffect(incomingUrl) {
        if (incomingUrl != null) {
            showSettings = false
            controller.openLink(incomingUrl)
            onIncomingUrlConsumed()
        }
    }

    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize()) {
                // Without the bar (stories) the page itself keeps clear of the system navigation bar.
                val bottomInset = if (controller.storyOpen) Modifier.navigationBarsPadding() else Modifier
                Box(Modifier.weight(1f).fillMaxWidth().statusBarsPadding().then(bottomInset).clipToBounds()) {
                    SiteView(
                        controller = controller,
                        settings = settings,
                        onOpenExternal = onOpenExternal,
                        onProfileFound = { path ->
                            if (path != settings.profilePath) {
                                // Another account: its profile tabs start fresh.
                                update { it.copy(profilePath = path, profileSection = if (it.profilePath.isEmpty()) it.profileSection else "") }
                            }
                        },
                        modifier = Modifier.fillMaxSize(),
                    )
                    // Switching to a ready tab: a quick fade through black.
                    val switchCover by animateFloatAsState(
                        targetValue = if (controller.switchCover) 1f else 0f,
                        animationSpec = tween(SiteController.SWITCH_FADE_MS, easing = LinearEasing),
                    )
                    if (switchCover > 0f) {
                        Box(Modifier.fillMaxSize().graphicsLayer { alpha = switchCover }.background(MaterialTheme.colorScheme.surface))
                    }
                    // Covers the page from the start of a navigation until the page itself has settled.
                    LoadingVeil(
                        visible = controller.isSettling && controller.error == null,
                        instant = controller.veilInstant,
                        modifier = Modifier.fillMaxSize(),
                    )
                    controller.error?.let { ErrorState(it, onRetry = controller::reload) }
                }
                // Stories cover the whole screen, as in the official app.
                if (!controller.storyOpen) {
                    BottomBar(
                        currentTab = currentTab,
                        onSelect = { tab -> controller.selectTab(tab.site) },
                        onOpenSettings = { showSettings = true },
                    )
                }
            }
            if (showSettings) {
                SettingsScreen(
                    settings = settings,
                    versionName = versionName,
                    onUpdate = update,
                    onSignOut = {
                        update { it.copy(profilePath = "") }
                        controller.clearSiteData()
                        showSettings = false
                    },
                    onBack = { showSettings = false },
                )
            }
        }
    }
}

@Composable
private fun ErrorState(error: SiteError, onRetry: () -> Unit) {
    Surface(Modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxSize().padding(32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("Couldn't load Instagram", style = MaterialTheme.typography.titleMedium)
            Text(error.description, textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyMedium)
            Button(onClick = onRetry) { Text("Try again") }
        }
    }
}
