package dev.sasha.clauderemarks.skill

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Plain JUnit: [shouldNotifySkillInstall] takes plain booleans, a plain string and the
 * platform-free [SkillInstall.SkillPresence] sealed class, so this needs no fixture.
 */
class SkillInstallNotificationTest {

    private val bundledVersion = "0.12.0"

    private fun decide(
        claudeCodeFound: Boolean = true,
        presence: SkillInstall.SkillPresence = SkillInstall.SkillPresence.Missing,
        dismissed: Boolean = false,
        alreadyShownThisRun: Boolean = false,
    ): Boolean = shouldNotifySkillInstall(
        claudeCodeFound = claudeCodeFound,
        presence = presence,
        bundledVersion = bundledVersion,
        dismissed = dismissed,
        alreadyShownThisRun = alreadyShownThisRun,
    )

    @Test
    fun `does not fire when Claude Code was not detected`() {
        assertFalse(decide(claudeCodeFound = false))
    }

    @Test
    fun `does not fire once the person dismissed it`() {
        assertFalse(decide(dismissed = true))
    }

    @Test
    fun `does not fire a second time in the same IDE run`() {
        assertFalse(decide(alreadyShownThisRun = true))
    }

    @Test
    fun `never fires for a symlink, the development checkout`() {
        assertFalse(decide(presence = SkillInstall.SkillPresence.Symlink))
    }

    @Test
    fun `does not fire when the installed copy is already up to date`() {
        assertFalse(decide(presence = SkillInstall.SkillPresence.Present(bundledVersion)))
    }

    @Test
    fun `fires when the installed copy is an older version`() {
        assertTrue(decide(presence = SkillInstall.SkillPresence.Present("0.11.0")))
    }

    @Test
    fun `fires when the installed copy carries no version stamp`() {
        assertTrue(decide(presence = SkillInstall.SkillPresence.Present(null)))
    }

    @Test
    fun `fires when nothing is installed at all`() {
        assertTrue(decide(presence = SkillInstall.SkillPresence.Missing))
    }
}
