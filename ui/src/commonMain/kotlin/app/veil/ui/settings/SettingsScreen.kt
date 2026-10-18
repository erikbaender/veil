package app.veil.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.veil.core.settings.Settings
import app.veil.ui.platform.SystemBackHandler

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    settings: Settings,
    versionName: String,
    onUpdate: ((Settings) -> Settings) -> Unit,
    onSignOut: () -> Unit,
    onBack: () -> Unit,
) {
    var confirmSignOut by remember { mutableStateOf(false) }
    SystemBackHandler(onBack = onBack)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())) {
            SectionTitle("Feed")
            Toggle(
                title = "Hide suggested posts",
                summary = "Hide \"Suggested for you\" posts and Reels. Sponsored posts are always hidden.",
                checked = settings.hideSuggestedPosts,
            ) { value -> onUpdate { it.copy(hideSuggestedPosts = value) } }
            Toggle(
                title = "Hide suggested accounts",
                summary = "Hide account suggestions in the feed.",
                checked = settings.hideSuggestedAccounts,
            ) { value -> onUpdate { it.copy(hideSuggestedAccounts = value) } }

            HorizontalDivider()
            SectionTitle("Privacy")
            Toggle(
                title = "Block screenshots",
                summary = "Prevents screenshots and hides the app's content in the recent apps screen.",
                checked = settings.secureScreen,
            ) { value -> onUpdate { it.copy(secureScreen = value) } }
            ListItem(
                headlineContent = { Text("Sign out and clear data") },
                supportingContent = { Text("Removes cookies, site storage and cache from this device.") },
                modifier = Modifier.clickable { confirmSignOut = true },
            )

            HorizontalDivider()
            SectionTitle("About")
            ListItem(
                headlineContent = { Text("Veil $versionName") },
                supportingContent = {
                    Text(
                        "Shows Instagram's website with ads and third-party trackers blocked. " +
                            "Only Instagram's own servers are contacted. No analytics, no backups of your data.",
                    )
                },
            )
        }
    }

    if (confirmSignOut) {
        AlertDialog(
            onDismissRequest = { confirmSignOut = false },
            title = { Text("Sign out?") },
            text = { Text("You'll need to log in to Instagram again.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmSignOut = false
                    onSignOut()
                }) { Text("Sign out") }
            },
            dismissButton = { TextButton(onClick = { confirmSignOut = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, top = 20.dp, bottom = 4.dp),
    )
}

@Composable
private fun Toggle(title: String, summary: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(summary) },
        trailingContent = { Switch(checked = checked, onCheckedChange = onChange) },
        modifier = Modifier.clickable { onChange(!checked) },
    )
}
