package app.veil.core.web

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HostPolicyTest {
    @Test
    fun allowsInstagramAndItsMediaHosts() {
        assertTrue(HostPolicy.isAllowed("www.instagram.com"))
        assertTrue(HostPolicy.isAllowed("instagram.com"))
        assertTrue(HostPolicy.isAllowed("scontent-ber1-1.cdninstagram.com"))
        assertTrue(HostPolicy.isAllowed("static.cdninstagram.com"))
        assertTrue(HostPolicy.isAllowed("instagram.fber1-1.fna.fbcdn.net"))
        assertTrue(HostPolicy.isAllowed("WWW.INSTAGRAM.COM."))
        assertTrue(HostPolicy.isAllowed("edge-chat.instagram.com"))
    }

    @Test
    fun blocksEverythingElse() {
        assertFalse(HostPolicy.isAllowed("www.google-analytics.com"))
        assertFalse(HostPolicy.isAllowed("connect.facebook.net"))
        assertFalse(HostPolicy.isAllowed("evil-instagram.com"))
        assertFalse(HostPolicy.isAllowed("instagram.com.evil.example"))
        assertFalse(HostPolicy.isAllowed("fbcdn.net.evil.example"))
        assertFalse(HostPolicy.isAllowed(""))
    }
}

class LinkSanitizerTest {
    @Test
    fun stripsTrackingParameters() {
        assertEquals(
            "https://www.instagram.com/p/ABC123/",
            LinkSanitizer.clean("https://www.instagram.com/p/ABC123/?igsh=xyz&utm_source=ig_web_copy_link"),
        )
        assertEquals(
            "https://example.com/page?id=7#top",
            LinkSanitizer.clean("https://example.com/page?utm_medium=social&id=7&fbclid=abc#top"),
        )
    }

    @Test
    fun leavesCleanLinksAlone() {
        assertEquals("https://example.com/a?b=1", LinkSanitizer.clean("https://example.com/a?b=1"))
        assertEquals("https://example.com/", LinkSanitizer.clean("https://example.com/"))
    }

    @Test
    fun unwrapsInstagramOutboundRedirects() {
        assertEquals(
            "https://example.com/shop?item=5",
            LinkSanitizer.clean("https://l.instagram.com/?u=https%3A%2F%2Fexample.com%2Fshop%3Fitem%3D5%26utm_source%3Dig&e=AT0x"),
        )
    }
}

class IncomingLinksTest {
    @Test
    fun extractsAndCleansInstagramLinks() {
        assertEquals(
            "https://www.instagram.com/reel/XYZ/",
            IncomingLinks.instagramUrl("Look at this https://www.instagram.com/reel/XYZ/?igsh=abc123 !"),
        )
        assertEquals("https://instagram.com/p/ABC/", IncomingLinks.instagramUrl("https://instagram.com/p/ABC/"))
    }

    @Test
    fun ignoresOtherLinks() {
        assertEquals(null, IncomingLinks.instagramUrl("https://example.com/instagram.com/p/1"))
        assertEquals(null, IncomingLinks.instagramUrl(null))
    }
}
