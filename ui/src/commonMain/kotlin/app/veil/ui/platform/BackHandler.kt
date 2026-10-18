package app.veil.ui.platform

import androidx.compose.runtime.Composable

/** System back gesture/button handler; the most recently composed enabled handler wins. */
@Composable
expect fun SystemBackHandler(enabled: Boolean = true, onBack: () -> Unit)
