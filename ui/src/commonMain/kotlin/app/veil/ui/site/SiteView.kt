package app.veil.ui.site

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import app.veil.core.settings.Settings

/** The embedded website, driven by [controller]. Implemented per platform. */
@Composable
expect fun SiteView(
    controller: SiteController,
    settings: Settings,
    onOpenExternal: (String) -> Unit,
    /** Called with the user's own profile path (e.g. `/name/`) when it is found on the page. */
    onProfileFound: (String) -> Unit,
    modifier: Modifier = Modifier,
)
