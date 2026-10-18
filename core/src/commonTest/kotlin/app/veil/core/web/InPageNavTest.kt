package app.veil.core.web

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class InPageNavTest {
    @Test
    fun extractsInstagramPaths() {
        assertEquals("/", InPageNav.pathFor("https://www.instagram.com/"))
        assertEquals("/", InPageNav.pathFor("https://www.instagram.com"))
        assertEquals("/reels/", InPageNav.pathFor("https://www.instagram.com/reels/"))
        assertEquals("/jane.doe/saved/", InPageNav.pathFor("https://www.instagram.com/jane.doe/saved/"))
    }

    @Test
    fun rejectsOtherUrls() {
        assertNull(InPageNav.pathFor("https://example.com/reels/"))
        assertNull(InPageNav.pathFor("https://www.instagram.com.evil.example/"))
        assertNull(InPageNav.pathFor("https://www.instagram.com/explore/?q=x"))
        assertNull(InPageNav.pathFor("https://www.instagram.com/a'b/"))
    }

    @Test
    fun buildsClickScriptOnlyForSafePaths() {
        assertTrue("a[href=\"/reels/\"]" in InPageNav.clickScript("/reels/"))
        assertFailsWith<IllegalArgumentException> { InPageNav.clickScript("/x\"]),alert(1)//") }
    }
}
