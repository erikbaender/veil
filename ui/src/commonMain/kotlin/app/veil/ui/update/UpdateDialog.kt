package app.veil.ui.update

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.veil.core.update.Releases
import app.veil.core.update.UpdateStatus

/**
 * The update offer and its progress. Shows nothing for states without a release to talk about.
 * [onLater] puts the offer off; [onClose] just closes the dialog.
 */
@Composable
fun UpdateDialog(
    status: UpdateStatus,
    onInstall: () -> Unit,
    onCancel: () -> Unit,
    onLater: () -> Unit,
    onClose: () -> Unit,
) {
    when (status) {
        is UpdateStatus.Available -> AlertDialog(
            onDismissRequest = onClose,
            title = { Text("Veil ${status.release.version} is available") },
            text = {
                val notes = Releases.shortNotes(status.release.notes)
                Column(Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState())) {
                    if (notes.isNotEmpty()) Text(notes, style = MaterialTheme.typography.bodyMedium)
                }
            },
            confirmButton = { TextButton(onClick = onInstall) { Text("Update") } },
            dismissButton = { TextButton(onClick = onLater) { Text("Later") } },
        )
        is UpdateStatus.Downloading -> AlertDialog(
            onDismissRequest = {},
            title = { Text("Downloading Veil ${status.release.version}") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    val progress = status.progress
                    if (progress != null) {
                        LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                        Text("${(progress * 100).toInt()} %", style = MaterialTheme.typography.bodySmall)
                    } else {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                    }
                }
            },
            confirmButton = { TextButton(onClick = onCancel) { Text("Cancel") } },
        )
        is UpdateStatus.Installing -> AlertDialog(
            onDismissRequest = onClose,
            title = { Text("Installing Veil ${status.release.version}") },
            text = {
                Text(
                    "Confirm the update in Android's installer. If Android asks, allow Veil to install apps, " +
                        "then tap Update again. Veil closes when the update is done; open it again.",
                )
            },
            confirmButton = { TextButton(onClick = onClose) { Text("OK") } },
        )
        is UpdateStatus.Failed -> if (status.release != null) {
            AlertDialog(
                onDismissRequest = onClose,
                title = { Text("Update failed") },
                text = { Text(status.message) },
                confirmButton = { TextButton(onClick = onInstall) { Text("Try again") } },
                dismissButton = { TextButton(onClick = onClose) { Text("Close") } },
            )
        }
        else -> Unit
    }
}
