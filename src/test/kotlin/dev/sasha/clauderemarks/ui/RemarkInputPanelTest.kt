package dev.sasha.clauderemarks.ui

import com.intellij.testFramework.fixtures.BasePlatformTestCase
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
        assertEquals("why?", remarkInputResult("  why?\n "))
    }

    @Test
    fun `text that is only whitespace is not a submission`() {
        assertNull(remarkInputResult("   \n\t "))
    }

    @Test
    fun `a newline inside the text is kept`() {
        assertEquals("one\ntwo", remarkInputResult("one\ntwo"))
    }
}

/** The bindings, on a real component. Looked up, not dispatched: no window is needed. */
class RemarkInputPanelTest : BasePlatformTestCase() {

    fun testEnterIsBoundToSubmitAndShiftEnterToANewline() {
        val panel = RemarkInputPanel(project, "")
        val map = panel.textArea.getInputMap(JComponent.WHEN_FOCUSED)

        assertEquals(SUBMIT_KEY, map.get(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0)))
        assertEquals(
            NEWLINE_KEY,
            map.get(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, InputEvent.SHIFT_DOWN_MASK)),
        )
        assertNotNull(panel.textArea.actionMap.get(SUBMIT_KEY))
        assertNotNull(panel.textArea.actionMap.get(NEWLINE_KEY))
    }

    fun testSubmitHandsBackTheTypedText() {
        val panel = RemarkInputPanel(project, "")
        panel.textArea.text = "  why is this locked?  "
        var got: String? = null
        panel.onSubmit = { got = it }

        panel.submit()

        assertEquals("why is this locked?", got)
    }

    fun testSubmittingEmptyTextDoesNothing() {
        val panel = RemarkInputPanel(project, "   ")
        var fired = false
        panel.onSubmit = { fired = true }

        panel.submit()

        assertFalse(fired)
    }

    fun testAnExistingRemarkOpensPreFilled() {
        val panel = RemarkInputPanel(project, "old note")

        assertEquals("old note", panel.textArea.text)
    }

    /**
     * The chip row and its five Alt keys used to live here, and so did the tests that pinned them:
     * every chip has an Alt key, every Alt key picks its own chip, Enter on the chip row submits
     * too. Phase 11 took the tag off a remark, so all of that is gone rather than kept dark. What is
     * left is the promise the popup still makes — type your remark, press Enter — and the one
     * keystroke the platform is most likely to take, below.
     *
     * Deleting the two lines that install that keystroke leaves the class-name chooser unreachable
     * with nothing else failing, so the binding is pinned here and the keystroke itself is a hand
     * check.
     */
    fun testTheClassNameChooserHasItsOwnKey() {
        val panel = RemarkInputPanel(project, "")
        val map = panel.textArea.getInputMap(JComponent.WHEN_FOCUSED)

        assertEquals(CLASS_NAME_KEY, map.get(CLASS_NAME_STROKE))
        assertNotNull(panel.textArea.actionMap.get(CLASS_NAME_KEY))
        // Not Ctrl+Space: Basic Completion is enabled in a modal context, so the platform really does
        // offer that combination to it while this popup is focused, and on macOS the OS takes it too.
        assertNull(map.get(KeyStroke.getKeyStroke(KeyEvent.VK_SPACE, InputEvent.CTRL_DOWN_MASK)))
    }
}
