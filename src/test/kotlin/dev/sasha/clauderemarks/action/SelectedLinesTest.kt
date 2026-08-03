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

/**
 * The column math [selectedColumns] does on the same document and the same line starts (0, 6, 11,
 * 17) [SelectedLinesTest] documents. "beta" is line 1, at offsets 6("b")-9("a"), 10 the newline.
 * "gamma" is line 2, at offsets 11("g")-15("a"), 16 the newline.
 */
class SelectedColumnsTest : BasePlatformTestCase() {

    private val document by lazy { EditorFactory.getInstance().createDocument("alpha\nbeta\ngamma\n") }

    fun testAPartialSelectionInsideOneLineGivesItsColumns() {
        // "et" inside "beta": offsets 7-9, line starts at 6, so columns 1 and 3.
        assertEquals(1 to 3, selectedColumns(document, 7, 9))
    }

    fun testAWholeLineSelectionGivesNoColumns() {
        // "beta" selected from its first character to its last, no more: offsets 6-10.
        assertEquals(0 to 0, selectedColumns(document, 6, 10))
    }

    /**
     * "eta" (from "beta", column 1) through "gam" (of "gamma", up to column 3): the start column
     * belongs to the selection's first line, the end column to its last, same as the IntRange
     * [selectedLines] returns for the same selection.
     */
    fun testAPartialMultiLineSelectionGivesColumnsOnBothEnds() {
        assertEquals(1 to 3, selectedColumns(document, 7, 14))
    }

    /**
     * "beta\ngamma\n" selected whole, gutter-drag style: selectionEnd lands on the first offset of
     * the following (empty) line, which selectedLines already treats as not part of the selection.
     */
    fun testAWholeMultiLineSelectionGivesNoColumns() {
        assertEquals(0 to 0, selectedColumns(document, 6, 17))
    }

    fun testAnEmptySelectionGivesNoColumns() {
        assertEquals(0 to 0, selectedColumns(document, 7, 7))
    }
}
