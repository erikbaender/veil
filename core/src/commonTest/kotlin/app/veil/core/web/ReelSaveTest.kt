package app.veil.core.web

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ReelSaveTest {
    @Test
    fun parsesPageMessages() {
        assertEquals(ReelSave.Request.State("C1x_Ab-9"), ReelSave.parse("state:C1x_Ab-9"))
        assertEquals(ReelSave.Request.Toggle("C1xAb9z", save = true), ReelSave.parse("save:C1xAb9z"))
        assertEquals(ReelSave.Request.Toggle("C1xAb9z", save = false), ReelSave.parse("unsave:C1xAb9z"))
    }

    @Test
    fun rejectsMalformedMessages() {
        assertNull(ReelSave.parse(null))
        assertNull(ReelSave.parse("state"))
        assertNull(ReelSave.parse("like:C1xAb9z"))
        assertNull(ReelSave.parse("save:../../x"))
        assertNull(ReelSave.parse("save:a b c d e"))
        assertNull(ReelSave.parse("save:abc"))
    }

    @Test
    fun formatsReplies() {
        assertEquals("C1xAb9z:1", ReelSave.reply("C1xAb9z", true))
        assertEquals("C1xAb9z:0", ReelSave.reply("C1xAb9z", false))
        assertEquals("C1xAb9z:?", ReelSave.reply("C1xAb9z", null))
        assertEquals("https://www.instagram.com/reel/C1xAb9z/", ReelSave.reelUrl("C1xAb9z"))
    }

    @Test
    fun pageScriptUsesTheBridge() {
        assertTrue("window.${ReelSave.BRIDGE}" in ReelSave.PAGE_SCRIPT)
    }

    @Test
    fun pageScriptAsksNothingOnItsOwn() {
        // Only a tap sends a message; reels scrolled past cause no background page loads.
        assertFalse("postMessage('state:'" in ReelSave.PAGE_SCRIPT)
    }
}
