package dev.sasha.clauderemarks.anchor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The one place the sub-line rule lives, tested on its own. `phraseAt`, `markersValid` in
 * `render/PromptRenderer.kt`, the tree row and the history entry all ask it, so what is pinned here
 * is what all four of them do.
 */
class SubLineRangeTest {

    @Test
    fun `inside one line the end column must come after the start column`() {
        assertTrue(hasSubLineRange(9, 9, 4, 11))
        assertFalse(hasSubLineRange(9, 9, 11, 4))
    }

    /**
     * The case an ordered check gets wrong. Across lines the two columns are measured from two
     * different line starts, so a long first line and a short last line put the end column before
     * the start column for a perfectly real selection.
     */
    @Test
    fun `across lines the two columns are not ordered against each other`() {
        assertTrue(hasSubLineRange(9, 11, 25, 8))
    }

    @Test
    fun `the whole-line sentinel is not a sub-line range`() {
        assertFalse(hasSubLineRange(9, 9, 0, 0))
        assertFalse(hasSubLineRange(9, 11, 0, 0))
    }

    /** Reachable only from a hand-edited workspace.xml, refused either way. */
    @Test
    fun `a negative column is not a sub-line range`() {
        assertFalse(hasSubLineRange(9, 9, -1, 5))
        assertFalse(hasSubLineRange(9, 11, 3, -1))
    }

    @Test
    fun `a whole-line remark reads as a plain line range`() {
        assertEquals("10-12", positionLabel(9, 11, 0, 0))
    }

    @Test
    fun `a sub-line remark inside one line reads as one line with two columns`() {
        assertEquals("10:5-12", positionLabel(9, 9, 4, 11))
    }

    @Test
    fun `a sub-line remark across lines reads as a line and column at each end`() {
        assertEquals("10:26-12:9", positionLabel(9, 11, 25, 8))
    }
}
