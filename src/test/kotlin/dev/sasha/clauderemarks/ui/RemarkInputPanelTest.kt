package dev.sasha.clauderemarks.ui

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.sasha.clauderemarks.model.RemarkTag
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import javax.swing.JComponent
import javax.swing.KeyStroke
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** The rules, without building a component. */
class RemarkInputRulesTest {

    @Test
    fun `whitespace around the text is trimmed off`() {
        assertEquals("why?", remarkInputResult("  why?\n ", null)?.text)
    }

    @Test
    fun `text that is only whitespace is not a submission`() {
        assertNull(remarkInputResult("   \n\t ", RemarkTag.BUG))
    }

    @Test
    fun `the tag comes through untouched`() {
        assertEquals(RemarkTag.BUG, remarkInputResult("x", RemarkTag.BUG)?.tag)
        assertNull(remarkInputResult("x", null)?.tag)
    }

    @Test
    fun `a newline inside the text is kept`() {
        assertEquals("one\ntwo", remarkInputResult("one\ntwo", null)?.text)
    }

    @Test
    fun `every tag has a label and every label maps back`() {
        for (tag in RemarkTag.entries) {
            assertEquals(tag, tagFromLabel(tagLabel(tag)))
        }
        assertEquals(NO_TAG_LABEL, tagLabel(null))
        assertNull(tagFromLabel(NO_TAG_LABEL))
        assertNull(tagFromLabel(null))
    }

    @Test
    fun `labels are lowercase, because they are printed straight into the prompt`() {
        assertEquals("bug", tagLabel(RemarkTag.BUG))
        assertEquals("refactor", tagLabel(RemarkTag.REFACTOR))
    }
}

/** The bindings, on a real component. Looked up, not dispatched: no window is needed. */
class RemarkInputPanelTest : BasePlatformTestCase() {

    fun testEnterIsBoundToSubmitAndShiftEnterToANewline() {
        val panel = RemarkInputPanel("", null)
        val map = panel.textArea.getInputMap(JComponent.WHEN_FOCUSED)

        assertEquals(SUBMIT_KEY, map.get(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0)))
        assertEquals(
            NEWLINE_KEY,
            map.get(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, InputEvent.SHIFT_DOWN_MASK)),
        )
        assertNotNull(panel.textArea.actionMap.get(SUBMIT_KEY))
        assertNotNull(panel.textArea.actionMap.get(NEWLINE_KEY))
    }

    fun testSubmitHandsBackTheTypedTextAndTheChosenTag() {
        val panel = RemarkInputPanel("", null)
        panel.textArea.text = "  why is this locked?  "
        panel.tagBox.selectedItem = tagLabel(RemarkTag.QUESTION)
        var got: RemarkInput? = null
        panel.onSubmit = { got = it }

        panel.submit()

        assertEquals("why is this locked?", got?.text)
        assertEquals(RemarkTag.QUESTION, got?.tag)
    }

    fun testSubmittingEmptyTextDoesNothing() {
        val panel = RemarkInputPanel("   ", null)
        var fired = false
        panel.onSubmit = { fired = true }

        panel.submit()

        assertFalse(fired)
    }

    fun testAnExistingRemarkOpensPreFilled() {
        val panel = RemarkInputPanel("old note", RemarkTag.REFACTOR)

        assertEquals("old note", panel.textArea.text)
        assertEquals(tagLabel(RemarkTag.REFACTOR), panel.tagBox.selectedItem)
    }
}
