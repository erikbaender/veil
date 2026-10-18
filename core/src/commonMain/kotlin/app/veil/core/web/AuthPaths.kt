package app.veil.core.web

/**
 * Recognises sign-in and security-check pages. While one is involved the app loads nothing on
 * its own: no preloading, no extra tabs. Those pages are left entirely to the user.
 */
object AuthPaths {
    enum class Kind { NONE, LOGIN, CHALLENGE }

    private val challenge = listOf("/challenge/", "/checkpoint/", "/auth_platform/", "/accounts/suspended/", "/accounts/disabled/")
    private val login = listOf(
        "/accounts/login/", "/accounts/emailsignup/", "/accounts/signup/", "/accounts/onetap/",
        "/accounts/password/reset/", "/accounts/logout/",
    )

    /** Kind of page at [path] (as from [TabPaths.pathOf]); null paths are NONE. */
    fun classify(path: String?): Kind = when {
        path == null -> Kind.NONE
        challenge.any { path.startsWith(it) } -> Kind.CHALLENGE
        login.any { path.startsWith(it) } -> Kind.LOGIN
        else -> Kind.NONE
    }
}
