package app.veil.core.web

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UnreadMessagesTest {
    @Test
    fun scriptIsSelfContained() {
        assertTrue(UnreadMessages.SCRIPT.trim().startsWith("(function"))
    }

    @Test
    fun parsesTheScriptsAnswer() {
        assertEquals(3, UnreadMessages.parse("3"))
        assertEquals(0, UnreadMessages.parse("0"))
        assertNull(UnreadMessages.parse("-1"))
        assertNull(UnreadMessages.parse(""))
        assertNull(UnreadMessages.parse("null"))
        assertNull(UnreadMessages.parse(null))
    }

    @Test
    fun notifiesOnlyAboutMoreThanKnown() {
        assertTrue(UnreadMessages.isNews(count = 1, lastKnown = 0))
        assertTrue(UnreadMessages.isNews(count = 3, lastKnown = 2))
        assertFalse(UnreadMessages.isNews(count = 2, lastKnown = 2))
        assertFalse(UnreadMessages.isNews(count = 0, lastKnown = 2))
    }

    @Test
    fun notificationTextIsOnlyACount() {
        assertEquals("1 unread chat", UnreadMessages.notificationText(1))
        assertEquals("4 unread chats", UnreadMessages.notificationText(4))
    }
}
