package app.veil.core.update

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** A newer Veil build published on GitHub. */
data class Release(
    val version: String,
    val versionCode: Int,
    val apkUrl: String,
    val apkSize: Long,
    /** Hex SHA-256 of the APK as GitHub reports it, or null if it doesn't. */
    val sha256: String?,
    /** Release notes (Markdown), as written on the release page. */
    val notes: String,
    val pageUrl: String,
)

/** Reading GitHub's "latest release" answer. The app asks only the one repository it comes from. */
object Releases {
    const val REPOSITORY = "erikbaender/veil"
    const val LATEST_URL = "https://api.github.com/repos/$REPOSITORY/releases/latest"

    private val json = Json { ignoreUnknownKeys = true }
    private val tagPattern = Regex("^v?(\\d{4})\\.(\\d{1,2})\\.(\\d{1,2})$")
    private val sha256Pattern = Regex("^sha256:([0-9a-f]{64})$")

    /**
     * Version code of a `year.month.release` version or `v`-prefixed tag (2026.10.1 -> 20261001),
     * the same rule as the build script; null if it isn't one.
     */
    fun versionCode(tag: String): Int? {
        val (year, month, release) = tagPattern.matchEntire(tag.trim())?.destructured ?: return null
        val m = month.toInt()
        if (m !in 1..12) return null
        return year.toInt() * 10_000 + m * 100 + release.toInt()
    }

    /**
     * The release in [body] if it is newer than [currentCode] and carries an APK; null otherwise.
     * Throws on a body that isn't a release at all.
     */
    fun newer(body: String, currentCode: Int): Release? {
        val release = json.decodeFromString(GitHubRelease.serializer(), body)
        if (release.draft || release.prerelease) return null
        val code = versionCode(release.tagName) ?: return null
        if (code <= currentCode) return null
        val apk = release.assets.firstOrNull { it.state == "uploaded" && it.name.endsWith(".apk") } ?: return null
        if (!apk.url.startsWith("https://")) return null
        return Release(
            version = release.tagName.removePrefix("v"),
            versionCode = code,
            apkUrl = apk.url,
            apkSize = apk.size,
            sha256 = apk.digest?.let { sha256Pattern.matchEntire(it)?.groupValues?.get(1) },
            notes = release.body.orEmpty(),
            pageUrl = release.htmlUrl,
        )
    }

    /**
     * Release notes cut down for a dialog: plain text instead of Markdown (no heading marks,
     * emphasis, code marks or link targets; list items as bullets), no trailing attribution, at
     * most [maxChars].
     */
    fun shortNotes(notes: String, maxChars: Int = 600): String {
        val text = notes.lineSequence()
            .takeWhile { !it.startsWith("🤖") }
            .map(::plainLine)
            .joinToString("\n")
            .replace(Regex("\n{3,}"), "\n\n")
            .trim()
        return if (text.length <= maxChars) text else text.take(maxChars).substringBeforeLast('\n').trimEnd() + "\n…"
    }

    private val heading = Regex("^#{1,6}\\s+")
    private val listItem = Regex("^(\\s*)[-*+]\\s+")
    private val link = Regex("\\[([^\\]]+)]\\([^)]*\\)")
    private val emphasis = Regex("(\\*\\*|__|\\*|`)")

    private fun plainLine(line: String): String = line
        .replace(heading, "")
        .replace(listItem, "$1• ")
        .replace(link, "$1")
        .replace(emphasis, "")

    @Serializable
    private data class GitHubRelease(
        @SerialName("tag_name") val tagName: String,
        @SerialName("html_url") val htmlUrl: String = "",
        val body: String? = null,
        val draft: Boolean = false,
        val prerelease: Boolean = false,
        val assets: List<Asset> = emptyList(),
    )

    @Serializable
    private data class Asset(
        val name: String,
        val size: Long = 0,
        val state: String = "uploaded",
        val digest: String? = null,
        @SerialName("browser_download_url") val url: String,
    )
}
