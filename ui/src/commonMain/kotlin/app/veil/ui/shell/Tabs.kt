package app.veil.ui.shell

import androidx.compose.ui.graphics.vector.ImageVector
import app.veil.core.web.SiteTab

/** Bottom-bar destinations; each shows its own page of the site (see [SiteTab]). */
enum class Tab(val label: String, val icon: ImageVector, val selectedIcon: ImageVector, val site: SiteTab) {
    Home("Home", TabIcons.home, TabIcons.homeSelected, SiteTab.HOME),
    Reels("Reels", TabIcons.reels, TabIcons.reelsSelected, SiteTab.REELS),
    Messages("Messages", TabIcons.messages, TabIcons.messagesSelected, SiteTab.MESSAGES),
    Search("Search", TabIcons.search, TabIcons.searchSelected, SiteTab.SEARCH),
    Profile("Profile", TabIcons.profile, TabIcons.profileSelected, SiteTab.PROFILE);

    companion object {
        fun of(site: SiteTab): Tab = entries.first { it.site == site }
    }
}
