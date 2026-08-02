package dev.sasha.clauderemarks.ui

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.ui.components.JBTextArea

/**
 * The chooser popup needs a window and is checked by hand. What is tested here is the part that
 * would be wrong silently: where the chosen name lands in the text, and that a missing extension
 * point answers an empty list instead of throwing.
 */
class ClassNameInsertTest : BasePlatformTestCase() {

    fun testTheChosenNameIsInsertedAtTheCaret() {
        val area = JBTextArea("see  for why")
        area.caretPosition = 4

        insertAtCaret(area, "JcrSessionProvider")

        assertEquals("see JcrSessionProvider for why", area.text)
    }

    fun testInsertingOverASelectionReplacesIt() {
        val area = JBTextArea("see WrongName for why")
        area.select(4, 13)

        insertAtCaret(area, "JcrSessionProvider")

        assertEquals("see JcrSessionProvider for why", area.text)
    }

    fun testAskingForNamesNeverThrows() {
        // Answers whatever this IDE has, including nothing at all. The only requirement is that a
        // missing extension point is an empty list, not an exception that kills the keystroke.
        assertNotNull(projectClassNames(project))
    }
}
