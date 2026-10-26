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
    fun parsesChatsFromTheInbox() {
        assertEquals(
            listOf(UnreadChat("Anna", "See you!"), UnreadChat("Ben", "")),
            UnreadMessages.parseChats("""[{"name":"Anna","preview":"See you!"},{"name":"Ben","preview":""},{"name":" ","preview":"x"}]"""),
        )
        assertEquals(emptyList(), UnreadMessages.parseChats("[]"))
        assertNull(UnreadMessages.parseChats("null"))
        assertNull(UnreadMessages.parseChats(""))
        assertTrue(UnreadMessages.CHATS_SCRIPT.trim().startsWith("(function"))
    }

    @Test
    fun namesWhoWrote() {
        val anna = UnreadChat("Anna", "Hi")
        val ben = UnreadChat("Ben", "Hey")
        val cleo = UnreadChat("Cleo", "Yo")
        assertEquals("Anna", UnreadMessages.senders(1, listOf(anna)))
        assertEquals("Anna and Ben", UnreadMessages.senders(2, listOf(anna, ben)))
        assertEquals("Anna, Ben and 1 more", UnreadMessages.senders(3, listOf(anna, ben, cleo)))
        assertEquals("Anna and 3 more", UnreadMessages.senders(4, listOf(anna)))
        assertEquals("2 unread chats", UnreadMessages.senders(2, emptyList()))
    }

    @Test
    fun notificationTextIsOnlyACount() {
        assertEquals("1 unread chat", UnreadMessages.notificationText(1))
        assertEquals("4 unread chats", UnreadMessages.notificationText(4))
    }
}
