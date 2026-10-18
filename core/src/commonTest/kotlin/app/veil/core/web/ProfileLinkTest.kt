package app.veil.core.web

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ProfileLinkTest {
    @Test
    fun picksTheProfileLink() {
        assertEquals("/jane.doe/", ProfileLink.parse("\"/jane.doe/\""))
        assertEquals("/jane_doe/", ProfileLink.parse("\"/\\n/explore/\\n/reels/\\n/direct/inbox/\\n/jane_doe/\""))
    }

    @Test
    fun ignoresSiteSections() {
        assertNull(ProfileLink.parse("\"/explore/\\n/reels/\\n/accounts/\""))
        assertNull(ProfileLink.parse("\"\""))
        assertNull(ProfileLink.parse(null))
        assertNull(ProfileLink.parse("\"/p/ABC123/\""))
    }

    @Test
    fun recognisesProfileSections() {
        assertEquals("", ProfileLink.section("https://www.instagram.com/jane.doe/", "/jane.doe/"))
        assertEquals("", ProfileLink.section("https://www.instagram.com/jane.doe", "/jane.doe/"))
        assertEquals("reels/", ProfileLink.section("https://www.instagram.com/jane.doe/reels/", "/jane.doe/"))
        assertEquals("tagged/", ProfileLink.section("https://www.instagram.com/Jane.Doe/tagged/?hl=en", "/jane.doe/"))
        assertEquals("saved/", ProfileLink.section("https://www.instagram.com/jane.doe/saved/", "/jane.doe/"))
        assertEquals("saved/", ProfileLink.section("https://www.instagram.com/jane.doe/saved/all-posts/", "/jane.doe/"))
    }

    @Test
    fun ignoresOtherPages() {
        assertNull(ProfileLink.section("https://www.instagram.com/someone.else/", "/jane.doe/"))
        assertNull(ProfileLink.section("https://www.instagram.com/jane.doe/following/", "/jane.doe/"))
        assertNull(ProfileLink.section("https://www.instagram.com/jane.doe.two/", "/jane.doe/"))
        assertNull(ProfileLink.section("https://www.instagram.com/jane.doe/", ""))
    }
}
