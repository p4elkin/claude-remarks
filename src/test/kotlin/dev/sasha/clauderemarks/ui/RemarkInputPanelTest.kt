package dev.sasha.clauderemarks.ui

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.sasha.clauderemarks.model.RemarkTag
import java.awt.event.ActionEvent
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
        val panel = RemarkInputPanel(project, "", null)
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
        val panel = RemarkInputPanel(project, "", null)
        panel.textArea.text = "  why is this locked?  "
        panel.selectedTag = RemarkTag.QUESTION
        var got: RemarkInput? = null
        panel.onSubmit = { got = it }

        panel.submit()

        assertEquals("why is this locked?", got?.text)
        assertEquals(RemarkTag.QUESTION, got?.tag)
    }

    fun testSubmittingEmptyTextDoesNothing() {
        val panel = RemarkInputPanel(project, "   ", null)
        var fired = false
        panel.onSubmit = { fired = true }

        panel.submit()

        assertFalse(fired)
    }

    fun testAnExistingRemarkOpensPreFilled() {
        val panel = RemarkInputPanel(project, "old note", RemarkTag.REFACTOR)

        assertEquals("old note", panel.textArea.text)
        assertEquals(RemarkTag.REFACTOR, panel.selectedTag)
    }

    /** "(no tag)" is a chip like any other, so clearing the tag is one click, not a drop-down. */
    fun testTheNoTagChipIsFirstAndMeansNull() {
        val panel = RemarkInputPanel(project, "x", RemarkTag.BUG)

        assertEquals(NO_TAG_LABEL, TAG_CHOICES.first())
        panel.selectedTag = null
        assertNull(panel.selectedTag)
    }

    /**
     * `testEnterInTheTagChooserSavesOnlyWhenTheDropDownIsClosed` used to live here. It pinned
     * `enterInTagBox`, a branch that existed only because a combo box's selection was a two-step
     * gesture: highlight with the arrow keys, then commit — and Swing let the plugin's own Enter
     * binding fire before that commit happened, so pressing Enter while the drop-down was still
     * open saved the remark with the PREVIOUS tag instead of the highlighted one.
     *
     * A chip has no such two-step gesture. Choosing one sets `selectedItem` on the model directly,
     * with nothing left pending afterwards, so the whole hazard class `enterInTagBox` guarded
     * against cannot occur any more, and the method is gone rather than kept dark. What replaces
     * that test is this: proof that a chosen tag is visible through `selectedTag` immediately, with
     * no separate confirm step, and that `submit()` can be called right after choosing one.
     */
    fun testChoosingATagIsImmediateWithNoSeparateCommitStep() {
        val panel = RemarkInputPanel(project, "note", null)
        panel.textArea.text = "note"

        panel.selectedTag = RemarkTag.BUG
        assertEquals(RemarkTag.BUG, panel.selectedTag)

        var got: RemarkInput? = null
        panel.onSubmit = { got = it }
        panel.submit()

        assertEquals(RemarkTag.BUG, got?.tag)
    }

    /**
     * Restored alongside the chips: the old drop-down had its own WHEN_FOCUSED Enter binding
     * (`enterInTagBox`), so tabbing to it and pressing Enter still submitted the remark. The chip
     * row has no equivalent of its own, so without this binding that keyboard path is a dead end.
     * The promise this popup makes is "type your remark, press Enter", wherever focus happens to be.
     */
    fun testEnterOnTheChipRowSubmitsToo() {
        val panel = RemarkInputPanel(project, "note", null)
        var got: RemarkInput? = null
        panel.onSubmit = { got = it }

        val map = panel.tagChipsComponent.getInputMap(JComponent.WHEN_FOCUSED)
        val stroke = KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0)
        assertEquals(SUBMIT_KEY, map.get(stroke))
        panel.tagChipsComponent.actionMap.get(SUBMIT_KEY)
            .actionPerformed(ActionEvent(panel, ActionEvent.ACTION_PERFORMED, ""))

        assertEquals("note", got?.text)
    }

    fun testEveryChipHasAnAltKey() {
        val panel = RemarkInputPanel(project, "", null)
        val map = panel.textArea.getInputMap(JComponent.WHEN_FOCUSED)

        TAG_CHOICES.indices.forEach { index ->
            val stroke = KeyStroke.getKeyStroke(KeyEvent.VK_0 + index, InputEvent.ALT_DOWN_MASK)
            assertEquals("$TAG_KEY_PREFIX$index", map.get(stroke))
            assertNotNull(panel.textArea.actionMap.get(map.get(stroke)))
        }
    }

    /**
     * The binding existing is not the same as the binding doing the right thing. This presses the
     * actions and checks what the chips ended up saying, so a key wired to the wrong chip fails.
     */
    fun testAltOneSelectsTheFirstTagAndAltZeroClearsIt() {
        val panel = RemarkInputPanel(project, "", null)
        val event = ActionEvent(panel, ActionEvent.ACTION_PERFORMED, "")

        panel.textArea.actionMap.get("${TAG_KEY_PREFIX}1").actionPerformed(event)
        assertEquals(RemarkTag.entries.first(), panel.selectedTag)

        panel.textArea.actionMap.get("${TAG_KEY_PREFIX}4").actionPerformed(event)
        assertEquals(RemarkTag.entries.last(), panel.selectedTag)

        panel.textArea.actionMap.get("${TAG_KEY_PREFIX}0").actionPerformed(event)
        assertNull(panel.selectedTag)
    }
}
