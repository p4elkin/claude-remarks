package dev.sasha.clauderemarks.action

import com.intellij.openapi.editor.EditorFactory
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * The line math the debug action does on a selection, against a real Document.
 *
 * The document is "alpha\nbeta\ngamma\n": four lines, the last one empty, with line starts at
 * 0, 6, 11 and 17.
 */
class SelectedLinesTest : BasePlatformTestCase() {

    // Lazy on purpose: the test instance is built before setUp runs, so there is no
    // application to ask for an EditorFactory yet at field initialization time.
    private val document by lazy { EditorFactory.getInstance().createDocument("alpha\nbeta\ngamma\n") }

    fun testAWholeLineSelectionStopsAtTheLineItCovers() {
        // "alpha\n" selected: selectionEnd is the first offset of line 1, which is not part
        // of the selection.
        assertEquals(0..0, selectedLines(document, 0, 6))
    }

    fun testASelectionEndingMidLineKeepsThatLine() {
        assertEquals(0..1, selectedLines(document, 0, 8))
    }

    fun testASelectionInsideOneLineIsThatOneLine() {
        assertEquals(0..0, selectedLines(document, 1, 3))
    }

    fun testNoSelectionIsTheCaretLine() {
        assertEquals(1..1, selectedLines(document, 6, 6))
    }

    fun testSelectAllOnAFileEndingWithANewlineStopsAtTheLastRealLine() {
        assertEquals(0..2, selectedLines(document, 0, document.textLength))
    }
}
