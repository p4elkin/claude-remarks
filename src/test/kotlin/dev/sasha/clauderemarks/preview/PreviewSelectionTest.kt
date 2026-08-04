package dev.sasha.clauderemarks.preview

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The two pure halves of the preview selection: what the page is allowed to send, and how the coarse
 * range from the page's ancestors is narrowed onto the words a person actually highlighted.
 *
 * No fixture and no platform class anywhere in here, which is why these run in milliseconds. The
 * browser half is not reachable from any test in this project; its checks are hand checks.
 */
class PreviewSelectionTest {

    @Test
    fun `a well formed message parses into five fields`() {
        val parsed = parseSelectionMessage(
            """{"startFrom": 10, "startTo": 40, "endFrom": 60, "endTo": 90, "text": "the words"}"""
        )

        // Asserted one at a time on purpose: a parser that reads startTo and endFrom in the wrong
        // order still passes an assertion on the whole object built from those same two numbers.
        assertNotNull(parsed)
        assertEquals(10, parsed!!.startFrom)
        assertEquals(40, parsed.startTo)
        assertEquals(60, parsed.endFrom)
        assertEquals(90, parsed.endTo)
        assertEquals("the words", parsed.text)
    }

    @Test
    fun `a malformed message parses as null`() {
        assertNull(
            "broken json",
            parseSelectionMessage("""{"startFrom": 10, "startTo": 40, "endFrom": 60,""")
        )
        assertNull(
            "a missing field",
            parseSelectionMessage("""{"startFrom": 10, "startTo": 40, "endFrom": 60, "text": "the words"}""")
        )
        assertNull(
            "a negative offset",
            parseSelectionMessage("""{"startFrom": -1, "startTo": 40, "endFrom": 60, "endTo": 90, "text": "x"}""")
        )
        assertNull(
            "an end offset before its start",
            parseSelectionMessage("""{"startFrom": 10, "startTo": 40, "endFrom": 90, "endTo": 60, "text": "x"}""")
        )
    }

    @Test
    fun `the range tightens onto the highlighted words inside the line`() {
        val source = "A plan is a record of how the work happened.\n"
        val selection = PreviewSelection(
            startFrom = 0,
            startTo = 43,
            endFrom = 0,
            endTo = 43,
            text = "a record",
        )

        val range = narrowToSelection(source, selection)

        assertEquals(SourceRange(10, 18), range)
        assertEquals("a record", source.substring(range!!.startOffset, range.endOffsetExclusive))
    }

    @Test
    fun `the range stays coarse when markup sits between the highlighted words`() {
        // The page renders "some **bold** words" as "some bold words", so the highlighted text is
        // not in the source at all and one indexOf cannot find it. The whole line is the answer.
        val source = "some **bold** words"
        val selection = PreviewSelection(
            startFrom = 0,
            startTo = 19,
            endFrom = 0,
            endTo = 19,
            text = "some bold words",
        )

        assertEquals(SourceRange(0, 19), narrowToSelection(source, selection))
    }

    @Test
    fun `a selection that starts in one line and ends in another spans both`() {
        // Two source lines of one paragraph, so the page wraps each in its own span and the two
        // ancestors differ. The coarse range runs from the first one's start to the second one's end.
        val source = "first line here\nsecond line here\n"
        val acrossTwoLines = PreviewSelection(
            startFrom = 0,
            startTo = 15,
            endFrom = 16,
            endTo = 31,
            // A paragraph renders as one flowed line, so the highlighted text carries a space where
            // the source carries a newline. The search then fails and the whole span stands.
            text = "line here second line",
        )

        assertEquals(SourceRange(0, 31), narrowToSelection(source, acrossTwoLines))

        // The same two ancestors, with a highlighted text that does survive the render, narrow
        // across the line break rather than stopping at it.
        val withTheBreakKept = acrossTwoLines.copy(text = "line here\nsecond line")
        assertEquals(SourceRange(6, 27), narrowToSelection(source, withTheBreakKept))
    }

    @Test
    fun `a coarse range that does not fit the source gives null`() {
        // What a stale render sends after the file got shorter: the page still holds offsets from
        // the text it was built from.
        val source = "a short line\n"
        val selection = PreviewSelection(
            startFrom = 0,
            startTo = 400,
            endFrom = 0,
            endTo = 400,
            text = "short",
        )

        assertNull(narrowToSelection(source, selection))
    }

    @Test
    fun `the first occurrence wins when the words appear twice`() {
        // A known limit, pinned here so it stays a decision rather than becoming a surprise: select
        // the second "the" and the remark lands on the first one, a few characters away.
        val source = "the plan and the work"
        val selection = PreviewSelection(
            startFrom = 0,
            startTo = 21,
            endFrom = 0,
            endTo = 21,
            text = "the",
        )

        assertEquals(SourceRange(0, 3), narrowToSelection(source, selection))
    }
}
