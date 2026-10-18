package app.veil.core.web

import kotlin.test.Test
import kotlin.test.assertTrue

class PageScriptsTest {
    private val scripts = listOf(
        PageScripts.MEDIA_GUARD, PageScripts.HIDE_SCRIPT, PageScripts.UNPARK_SCRIPT, PageScripts.REVEAL_PREP_SCRIPT,
        PageScripts.REVEAL_READY_SCRIPT, PageScripts.WAKE_LAZY_LOADING, PageScripts.SCROLL_TOP_SCRIPT,
        PageScripts.DIALOG_OPEN_SCRIPT, PageScripts.LOGIN_FORM_SCRIPT, PageScripts.HELPER_MEDIA_OFF,
    )

    @Test
    fun scriptsAreSelfContained() {
        scripts.forEach { assertTrue(it.trim().startsWith("(function"), it.take(40)) }
    }

    @Test
    fun mediaGuardInstallsOnce() {
        assertTrue("if (window.__veilGuard) return" in PageScripts.MEDIA_GUARD)
    }

    @Test
    fun hiddenPagesDoNoStyleWork() {
        assertTrue("visibilityState === 'hidden'" in PageStyle.script(app.veil.core.settings.Settings()))
        assertTrue("visibilityState === 'hidden'" in ReelSave.PAGE_SCRIPT)
    }
}
