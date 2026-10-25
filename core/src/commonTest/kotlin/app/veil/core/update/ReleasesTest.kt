package app.veil.core.update

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class ReleasesTest {
    private fun release(
        tag: String = "v2026.11.0",
        draft: Boolean = false,
        prerelease: Boolean = false,
        assets: String = apk,
    ) = """
        {"tag_name":"$tag","name":"Veil","draft":$draft,"prerelease":$prerelease,"immutable":false,
         "html_url":"https://github.com/erikbaender/veil/releases/tag/$tag",
         "body":"Fixes.\n\n🤖 Generated with [Claude Code](https://claude.com/claude-code)\n",
         "assets":[$assets]}
    """.trimIndent()

    private val apk = """
        {"name":"veil-2026.11.0.apk","size":2398233,"state":"uploaded","content_type":"application/vnd.android.package-archive",
         "digest":"sha256:73a246007c56aecce6b3ea84c0df240f1e9e6237b97fa26b2c7ae8e6bec24582",
         "browser_download_url":"https://github.com/erikbaender/veil/releases/download/v2026.11.0/veil-2026.11.0.apk"}
    """.trimIndent()

    @Test
    fun versionCodesFollowTheBuildScript() {
        assertEquals(20261000, Releases.versionCode("2026.10.0"))
        assertEquals(20261001, Releases.versionCode("v2026.10.1"))
        assertEquals(20270112, Releases.versionCode("v2027.1.12"))
        assertNull(Releases.versionCode("v2026.13.0"))
        assertNull(Releases.versionCode("v1.2.3"))
        assertNull(Releases.versionCode("latest"))
    }

    @Test
    fun offersOnlyNewerReleases() {
        val found = assertNotNull(Releases.newer(release(), currentCode = 20261000))
        assertEquals("2026.11.0", found.version)
        assertEquals(20261100, found.versionCode)
        assertEquals("https://github.com/erikbaender/veil/releases/download/v2026.11.0/veil-2026.11.0.apk", found.apkUrl)
        assertEquals("73a246007c56aecce6b3ea84c0df240f1e9e6237b97fa26b2c7ae8e6bec24582", found.sha256)
        assertEquals(2398233, found.apkSize)

        assertNull(Releases.newer(release(), currentCode = 20261100))
        assertNull(Releases.newer(release(), currentCode = 20261200))
    }

    @Test
    fun skipsDraftsPrereleasesAndReleasesWithoutAnApk() {
        assertNull(Releases.newer(release(draft = true), 20261000))
        assertNull(Releases.newer(release(prerelease = true), 20261000))
        assertNull(Releases.newer(release(assets = ""), 20261000))
        assertNull(Releases.newer(release(tag = "nightly"), 20261000))
        assertNull(Releases.newer(release(assets = apk.replace("https://", "http://")), 20261000))
        assertNull(Releases.newer(release(assets = apk.replace("\"uploaded\"", "\"open\"")), 20261000))
    }

    @Test
    fun unknownDigestIsNull() {
        val noDigest = apk.replace("sha256:", "md5:")
        assertNull(assertNotNull(Releases.newer(release(assets = noDigest), 20261000)).sha256)
    }

    @Test
    fun rejectsSomethingElse() {
        assertFailsWith<Exception> { Releases.newer("""{"message":"Not Found"}""", 20261000) }
    }

    @Test
    fun shortNotesArePlainText() {
        val notes = "## What's new\n- **Updates** from *within* the app, see `Settings`.\n" +
            "- [Release page](https://github.com/erikbaender/veil/releases)\n\n\n\n## Install\nOpen it."
        assertEquals(
            "What's new\n• Updates from within the app, see Settings.\n• Release page\n\nInstall\nOpen it.",
            Releases.shortNotes(notes),
        )
    }

    @Test
    fun shortNotesDropTheAttributionAndStayShort() {
        assertEquals("Fixes.", Releases.shortNotes("Fixes.\n\n🤖 Generated with [Claude Code](https://claude.com/claude-code)\n"))
        val long = (1..100).joinToString("\n") { "- change $it" }
        val short = Releases.shortNotes(long, maxChars = 50)
        assert(short.length <= 52) { short }
        assert(short.endsWith("…"))
    }
}
