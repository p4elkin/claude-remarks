package dev.sasha.clauderemarks.preview

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The arithmetic behind what the preview page highlights: which resolved remarks belong on the page
 * at all, and where each one's highlight starts in the .md source.
 *
 * No fixture and no platform class, the same reason PreviewSelectionTest runs in milliseconds: this
 * file takes plain values rather than RemarkState/ResolvedRemark, so nothing here needs a project or
 * a Document to construct.
 */
class PreviewHighlightsTest {

    private val previewedPath = "docs/notes.md"

    private fun candidate(
        path: String? = previewedPath,
        isOrphaned: Boolean = false,
        startLine: Int = 0,
        startColumn: Int = 0,
        asksForAnswer: Boolean = false,
        hasAnswer: Boolean = false,
    ) = HighlightCandidate(path, isOrphaned, startLine, startColumn, asksForAnswer, hasAnswer)

    @Test
    fun `a plain remark on the previewed file produces a remark highlight at its source offset`() {
        val source = "first line\nsecond line\n"

        val highlights = highlightsFor(listOf(candidate(startLine = 1, startColumn = 3)), previewedPath, source)

        assertEquals(listOf(PreviewHighlight(sourceStartOffset = 14, isUnansweredQuestion = false)), highlights)
    }

    @Test
    fun `an unanswered question produces a question highlight`() {
        val source = "a line\n"

        val highlights = highlightsFor(listOf(candidate(asksForAnswer = true, hasAnswer = false)), previewedPath, source)

        assertEquals(true, highlights.single().isUnansweredQuestion)
    }

    @Test
    fun `a question that already has an answer highlights like a plain remark`() {
        val source = "a line\n"

        val highlights = highlightsFor(listOf(candidate(asksForAnswer = true, hasAnswer = true)), previewedPath, source)

        assertEquals(false, highlights.single().isUnansweredQuestion)
    }

    /** The first of the three exclusions: an orphaned remark's stored offsets mean nothing. */
    @Test
    fun `an orphaned remark is excluded`() {
        val source = "a line\n"

        val highlights = highlightsFor(listOf(candidate(isOrphaned = true)), previewedPath, source)

        assertTrue(highlights.isEmpty())
    }

    /**
     * The second exclusion: a remark about no file, which store/RemarkResolver.kt's isAboutNoFile
     * treats as a null or empty path — checked both ways here for the same reason.
     */
    @Test
    fun `a remark about no file is excluded`() {
        val source = "a line\n"

        assertTrue(highlightsFor(listOf(candidate(path = null)), previewedPath, source).isEmpty())
        assertTrue(highlightsFor(listOf(candidate(path = "")), previewedPath, source).isEmpty())
    }

    /** The third exclusion: a remark that is real and resolved, but names a different file. */
    @Test
    fun `a remark for a different file than the one previewed is excluded`() {
        val source = "a line\n"

        val highlights = highlightsFor(listOf(candidate(path = "docs/other.md")), previewedPath, source)

        assertTrue(highlights.isEmpty())
    }

    @Test
    fun `several remarks on the previewed file all produce their own highlight`() {
        val source = "one\ntwo\nthree\n"

        val highlights = highlightsFor(
            listOf(candidate(startLine = 0, startColumn = 0), candidate(startLine = 2, startColumn = 1)),
            previewedPath,
            source,
        )

        assertEquals(2, highlights.size)
    }

    /**
     * A stored line past the end of the source, which a stale render or a hand-edited store could
     * produce: skipped rather than thrown, the same silent-skip rule the page itself follows for an
     * offset that matches no element.
     */
    @Test
    fun `a start line past the end of the source is excluded rather than throwing`() {
        val source = "one line\n"

        val highlights = highlightsFor(listOf(candidate(startLine = 5)), previewedPath, source)

        assertTrue(highlights.isEmpty())
    }

    @Test
    fun `toJson writes offset and kind for each highlight, in order`() {
        val json = toJson(
            listOf(
                PreviewHighlight(sourceStartOffset = 10, isUnansweredQuestion = false),
                PreviewHighlight(sourceStartOffset = 42, isUnansweredQuestion = true),
            )
        )

        assertEquals("""[{"offset":10,"kind":"remark"},{"offset":42,"kind":"question"}]""", json)
    }

    @Test
    fun `toJson of an empty list is an empty array`() {
        assertEquals("[]", toJson(emptyList()))
    }
}
