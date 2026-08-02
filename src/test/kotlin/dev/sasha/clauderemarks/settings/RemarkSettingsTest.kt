package dev.sasha.clauderemarks.settings

import com.intellij.openapi.util.JDOMUtil
import com.intellij.util.xmlb.XmlSerializer
import org.junit.Assert.assertEquals
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
    fun `a blank header falls back to the default rather than sending nothing`() {
        val settings = RemarkSettings()

        settings.promptHeader = "   \n  "

        assertEquals(DEFAULT_PROMPT_HEADER, settings.promptHeader)
    }

    @Test
    fun `the default header says a question is answered, not turned into an edit`() {
        assertTrue(DEFAULT_PROMPT_HEADER.contains("QUESTION"))
        assertTrue(DEFAULT_PROMPT_HEADER.contains("orphaned"))
        assertTrue(DEFAULT_PROMPT_HEADER.contains("INSTRUCTION"))
    }
}
