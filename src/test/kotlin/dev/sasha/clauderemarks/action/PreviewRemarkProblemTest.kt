package dev.sasha.clauderemarks.action

import dev.sasha.clauderemarks.preview.SourceRange
import dev.sasha.clauderemarks.preview.StoredSelection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The two parts of the preview entry point a test can reach: whether the selection the browser
 * stored is the one this right click is about, and whether the source behind it has changed since.
 *
 * No fixture, because [previewRemarkProblem] and [previewStampProblem] are both pure. The action
 * around them needs a JCEF panel, a rendered page and a real browser selection, none of which a light
 * fixture has, so its checks are hand checks in the phase 9 plan and not tests here.
 */
class PreviewRemarkProblemTest {

    @Test
    fun `nothing stored asks for a selection in the preview`() {
        val problem = previewRemarkProblem(null, FILE_URL)

        assertEquals(
            "Select something in the rendered preview first, then try again.",
            problem,
        )
    }

    @Test
    fun `a stored selection for this preview's own file is accepted`() {
        assertNull(previewRemarkProblem(StoredSelection(FILE_URL, RANGE, STAMP), FILE_URL))
    }

    /**
     * Two previews open side by side, the selection made in one and the right click done in the
     * other. Storing the remark anyway would put it on the file the person was not looking at.
     */
    @Test
    fun `a stored selection from another preview is refused`() {
        val problem = previewRemarkProblem(StoredSelection(OTHER_URL, RANGE, STAMP), FILE_URL)

        assertTrue(
            "the refusal has to say another preview holds it, got: $problem",
            problem != null && problem.contains("another preview"),
        )
    }

    /**
     * No file in the data context is not a refusal: the stored url is then the only thing that names
     * a file, and it is the one the selection was made in, so it is the right one to act on.
     */
    @Test
    fun `a stored selection is accepted when nothing else names a file`() {
        assertNull(previewRemarkProblem(StoredSelection(FILE_URL, RANGE, STAMP), null))
    }

    /** The source has not been touched since the page reported the selection. */
    @Test
    fun `an unchanged source keeps the selection`() {
        assertNull(previewStampProblem(StoredSelection(FILE_URL, RANGE, STAMP), STAMP))
    }

    /**
     * The window this closes: the source is edited between the browser posting a selection and the
     * person right-clicking. The offsets can still fit the new text and still describe different
     * words, so the length check the action makes afterwards would let this through.
     */
    @Test
    fun `an edited source refuses the selection`() {
        assertEquals(MOVED_ON, previewStampProblem(StoredSelection(FILE_URL, RANGE, STAMP), STAMP + 1))
    }

    private companion object {
        const val FILE_URL = "file:///project/docs/plans/one.md"
        const val OTHER_URL = "file:///project/docs/plans/two.md"
        const val STAMP = 4321L
        val RANGE = SourceRange(10, 20)
    }
}
