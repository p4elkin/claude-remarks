package dev.sasha.clauderemarks.skill

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Plain JUnit: [skillRowText] takes only plain values, no platform import, so this needs no fixture.
 */
class SkillRowTextTest {

    @Test
    fun `a harness the plugin cannot install into reads as found, with no button`() {
        val text = skillRowText(installable = false, presence = null, bundledVersion = "0.12.0")

        assertNull(text.buttonLabel)
        assertTrue(text.statusText.contains("cannot install"))
    }

    @Test
    fun `an installable harness with nothing at its skill directory reads as not installed`() {
        val text = skillRowText(
            installable = true,
            presence = SkillInstall.SkillPresence.Missing,
            bundledVersion = "0.12.0",
        )

        assertEquals("not installed", text.statusText)
        assertEquals("Install", text.buttonLabel)
    }

    @Test
    fun `a null presence on an installable harness reads the same as Missing`() {
        val text = skillRowText(installable = true, presence = null, bundledVersion = "0.12.0")

        assertEquals("not installed", text.statusText)
        assertEquals("Install", text.buttonLabel)
    }

    @Test
    fun `a symlinked skill directory reads as a development install, with no button`() {
        val text = skillRowText(
            installable = true,
            presence = SkillInstall.SkillPresence.Symlink,
            bundledVersion = "0.12.0",
        )

        assertNull(text.buttonLabel)
        assertTrue(text.statusText.contains("symlink"))
        assertTrue(text.statusText.contains("development install"))
    }

    @Test
    fun `an installed copy with no readable stamp reads as version unknown, and still offers Reinstall`() {
        val text = skillRowText(
            installable = true,
            presence = SkillInstall.SkillPresence.Present(null),
            bundledVersion = "0.12.0",
        )

        assertEquals("installed, version unknown", text.statusText)
        assertEquals("Reinstall", text.buttonLabel)
    }

    @Test
    fun `an installed copy matching the bundled version reads as up to date`() {
        val text = skillRowText(
            installable = true,
            presence = SkillInstall.SkillPresence.Present("0.12.0"),
            bundledVersion = "0.12.0",
        )

        assertEquals("up to date", text.statusText)
        assertEquals("Reinstall", text.buttonLabel)
    }

    @Test
    fun `an installed copy older than the bundled version names both versions`() {
        val text = skillRowText(
            installable = true,
            presence = SkillInstall.SkillPresence.Present("0.11.0"),
            bundledVersion = "0.12.0",
        )

        assertEquals("0.11.0 installed, 0.12.0 bundled", text.statusText)
        assertEquals("Reinstall", text.buttonLabel)
    }

    @Test
    fun `an installed copy newer than the bundled version still just names both versions`() {
        // Not a case this plugin expects to see in the wild, but the function must not throw or
        // guess: it reports what it read against what it has, in the order the two are compared.
        val text = skillRowText(
            installable = true,
            presence = SkillInstall.SkillPresence.Present("0.13.0"),
            bundledVersion = "0.12.0",
        )

        assertEquals("0.13.0 installed, 0.12.0 bundled", text.statusText)
        assertEquals("Reinstall", text.buttonLabel)
    }
}
