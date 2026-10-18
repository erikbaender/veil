package app.veil.core.web

import app.veil.core.settings.Settings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PageStyleTest {
    @Test
    fun sponsoredIsAlwaysHidden() {
        val labels = PageStyle.labels(Settings(hideSuggestedPosts = false, hideSuggestedAccounts = false))
        assertEquals(PageStyle.AD_LABELS, labels)
        assertTrue("Ad" in labels)
    }

    @Test
    fun suggestedContentFollowsSettings() {
        val labels = PageStyle.labels(Settings(hideSuggestedPosts = true, hideSuggestedAccounts = true))
        assertTrue("Suggested posts" in labels)
        assertEquals(labels.distinct(), labels)
    }

    @Test
    fun scriptCarriesLabels() {
        val script = PageStyle.script(Settings())
        assertTrue("\"Sponsored\"" in script)
    }
}
