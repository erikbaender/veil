package app.veil.core.web

import app.veil.core.web.AuthPaths.Kind
import kotlin.test.Test
import kotlin.test.assertEquals

class AuthPathsTest {
    @Test
    fun signInAndChecks() {
        assertEquals(Kind.LOGIN, AuthPaths.classify("/accounts/login/"))
        assertEquals(Kind.LOGIN, AuthPaths.classify("/accounts/login/two_factor/"))
        assertEquals(Kind.LOGIN, AuthPaths.classify("/accounts/onetap/"))
        assertEquals(Kind.CHALLENGE, AuthPaths.classify("/challenge/action/"))
        assertEquals(Kind.CHALLENGE, AuthPaths.classify("/checkpoint/"))
        assertEquals(Kind.CHALLENGE, AuthPaths.classify("/accounts/suspended/"))
    }

    @Test
    fun ordinaryAccountPagesAreNotStops() {
        assertEquals(Kind.NONE, AuthPaths.classify("/accounts/edit/"))
        assertEquals(Kind.NONE, AuthPaths.classify("/accounts/activity/"))
        assertEquals(Kind.NONE, AuthPaths.classify("/accounts/password/change/"))
        assertEquals(Kind.NONE, AuthPaths.classify("/"))
        assertEquals(Kind.NONE, AuthPaths.classify(null))
    }
}
