package dev.sasha.clauderemarks.settings

import com.intellij.openapi.util.JDOMUtil
import com.intellij.util.xmlb.XmlSerializer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RemarkSettingsTest {

    @Test
    fun `an edited header survives a write and read cycle`() {
        val original = RemarkSettings.SettingsState()
        original.promptHeader = "my own header\nover two lines"

        val restored = XmlSerializer.deserialize(
            JDOMUtil.load(JDOMUtil.write(XmlSerializer.serialize(original))),
            RemarkSettings.SettingsState::class.java,
        )

        assertEquals("my own header\nover two lines", restored.promptHeader)
    }

    @Test
    fun `an untouched settings object reads back as the default header`() {
        val restored = XmlSerializer.deserialize(
            JDOMUtil.load(JDOMUtil.write(XmlSerializer.serialize(RemarkSettings.SettingsState()))),
            RemarkSettings.SettingsState::class.java,
        )

        assertEquals(DEFAULT_PROMPT_HEADER, restored.promptHeader)
    }

    @Test
    fun `a dismissed skill-install prompt survives a write and read cycle`() {
        val original = RemarkSettings.SettingsState()
        original.skillInstallPromptDismissed = true

        val restored = XmlSerializer.deserialize(
            JDOMUtil.load(JDOMUtil.write(XmlSerializer.serialize(original))),
            RemarkSettings.SettingsState::class.java,
        )

        assertTrue(restored.skillInstallPromptDismissed)
    }

    @Test
    fun `an untouched settings object reads back with the skill-install prompt not dismissed`() {
        val restored = XmlSerializer.deserialize(
            JDOMUtil.load(JDOMUtil.write(XmlSerializer.serialize(RemarkSettings.SettingsState()))),
            RemarkSettings.SettingsState::class.java,
        )

        assertFalse(restored.skillInstallPromptDismissed)
    }

    @Test
    fun `a blank header falls back to the default rather than sending nothing`() {
        val settings = RemarkSettings()

        settings.promptHeader = "   \n  "

        assertEquals(DEFAULT_PROMPT_HEADER, settings.promptHeader)
    }

    @Test
    fun `the default header still explains an orphan and the quoted lines`() {
        assertTrue(DEFAULT_PROMPT_HEADER.contains("orphaned"))
        assertTrue(DEFAULT_PROMPT_HEADER.contains("\">\""))
    }

    /**
     * The header used to ask the model to sort each remark into QUESTION or INSTRUCTION by reading
     * it. A remark that asks for an answer now says so in its own heading, set when the remark was
     * written, so there is nothing left to guess — and the guess was wrong often enough to be worth
     * a test that stops it coming back.
     */
    @Test
    fun `the default header no longer asks the model to work out which remarks are questions`() {
        assertFalse(DEFAULT_PROMPT_HEADER, DEFAULT_PROMPT_HEADER.contains("QUESTION"))
        assertFalse(DEFAULT_PROMPT_HEADER, DEFAULT_PROMPT_HEADER.contains("INSTRUCTION"))
    }
}
