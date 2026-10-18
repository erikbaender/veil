package app.veil.core.web

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TabPathsTest {
    private val me = "/someone/"

    @Test
    fun pathsAreNormalised() {
        assertEquals("/", TabPaths.pathOf("https://www.instagram.com"))
        assertEquals("/", TabPaths.pathOf("https://www.instagram.com/?variant=following"))
        assertEquals("/explore/", TabPaths.pathOf("https://www.instagram.com/explore"))
        assertEquals("/p/abc/", TabPaths.pathOf("https://instagram.com/p/abc/#x"))
        assertNull(TabPaths.pathOf("about:blank"))
        assertNull(TabPaths.pathOf("https://www.instagram.com.example/"))
        assertNull(TabPaths.pathOf("https://example.com/"))
    }

    @Test
    fun rootsPerTab() {
        assertEquals("/reels/", TabPaths.rootPath(SiteTab.REELS, me, ""))
        assertEquals("/someone/saved/", TabPaths.rootPath(SiteTab.PROFILE, me, "saved/"))
        assertEquals("/someone/", TabPaths.rootPath(SiteTab.PROFILE, me, "bogus/"))
        assertEquals(ProfileLink.FALLBACK_PATH, TabPaths.rootPath(SiteTab.PROFILE, "", ""))
        assertEquals("https://www.instagram.com/direct/inbox/", TabPaths.rootUrl(SiteTab.MESSAGES, me, ""))
    }

    @Test
    fun rootDetection() {
        assertTrue(TabPaths.isRoot(SiteTab.HOME, "/", me))
        assertFalse(TabPaths.isRoot(SiteTab.HOME, "/p/abc/", me))
        assertTrue(TabPaths.isRoot(SiteTab.REELS, "/reels/DAbc_12-x/", me))
        assertFalse(TabPaths.isRoot(SiteTab.REELS, "/reels/audio/123/x/", me))
        assertTrue(TabPaths.isRoot(SiteTab.PROFILE, "/SomeOne/tagged/", me))
        assertFalse(TabPaths.isRoot(SiteTab.PROFILE, "/someone/followers/", me))
        assertTrue(TabPaths.isRoot(SiteTab.PROFILE, ProfileLink.FALLBACK_PATH, ""))
        assertFalse(TabPaths.isRoot(SiteTab.MESSAGES, "/direct/t/123/", me))
        assertFalse(TabPaths.isRoot(null, "/", me))
        assertFalse(TabPaths.isRoot(SiteTab.HOME, null, me))
    }

    @Test
    fun linksRouteToTheirTab() {
        assertEquals(SiteTab.HOME, TabPaths.route("/", me))
        assertEquals(SiteTab.REELS, TabPaths.route("/reels/", me))
        assertEquals(SiteTab.MESSAGES, TabPaths.route("/direct/t/123/", me))
        assertEquals(SiteTab.SEARCH, TabPaths.route("/explore/tags/cats/", me))
        assertEquals(SiteTab.PROFILE, TabPaths.route("/someone/saved/", me))
        assertNull(TabPaths.route("/p/abc/", me))
        assertNull(TabPaths.route("/reel/abc/", me))
        assertNull(TabPaths.route("/someone_else/", me))
        assertNull(TabPaths.route("/someone/", ""))
    }
}
