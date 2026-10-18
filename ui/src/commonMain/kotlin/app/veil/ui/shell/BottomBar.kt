package app.veil.ui.shell

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Instagram-style tab bar: hairline divider, five equal icon cells, no labels.
 * Long-pressing the Profile tab opens Veil's settings.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun BottomBar(
    currentTab: Tab?,
    onSelect: (Tab) -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    Column(modifier.fillMaxWidth()) {
        HorizontalDivider(thickness = 0.5.dp, color = colors.outlineVariant)
        Row(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .height(50.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Tab.entries.forEach { tab ->
                val selected = tab == currentTab
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .combinedClickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = ripple(bounded = false, radius = 24.dp),
                            onClickLabel = tab.label,
                            onLongClickLabel = if (tab == Tab.Profile) "Settings" else null,
                            onLongClick = if (tab == Tab.Profile) onOpenSettings else null,
                            onClick = { onSelect(tab) },
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = if (selected) tab.selectedIcon else tab.icon,
                        contentDescription = tab.label,
                        tint = colors.onSurface,
                        modifier = Modifier.size(26.dp),
                    )
                }
            }
        }
    }
}
