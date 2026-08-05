package dev.sasha.clauderemarks.editor

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.sasha.clauderemarks.model.RemarkStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The platform renders a gutter tooltip as HTML. That decides two things at once. A plain "\n"
 * would not break a line, so the orphaned and handed-over notes would run into the remark text. And
 * the remark text is whatever the user typed, so a remark reading "why is List<String> here?" would
 * show as "why is  here?" — the browser eats <String> as a tag.
 */
class RemarkTooltipTest {

    @Test
    fun `angle brackets in a remark survive instead of being eaten as a tag`() {
        val html = tooltipFor(placement(text = "why is List<String> here?"))

        assertTrue(html.contains("List&lt;String&gt;"))
        assertFalse(html.contains("<String>"))
    }

    @Test
    fun `a newline in a remark becomes a line break`() {
        assertTrue(tooltipFor(placement(text = "one\ntwo")).contains("one<br/>two"))
    }

    /**
     * The tooltip is the one place the commit is always shown, cut to the same eight characters the
     * tree's orphan label and the prompt heading use.
     */
    @Test
    fun `the commit is shown short, and only when there is one`() {
        val stamped = tooltipFor(placement(commit = "0123456789abcdef0123456789abcdef01234567"))

        assertTrue(stamped, stamped.contains("commit 01234567"))
        assertFalse(stamped, stamped.contains("0123456789a"))
        assertFalse(tooltipFor(placement(commit = null)).contains("commit"))
    }

    @Test
    fun `an orphan and a published remark each say so on their own line`() {
        val html = tooltipFor(placement(orphaned = true, status = RemarkStatus.PUBLISHED))

        assertTrue(html.contains("<br/>(orphaned"))
        assertTrue(html.contains("<br/>(published)"))
    }

    @Test
    fun `a read remark says so on its own line`() {
        assertTrue(tooltipFor(placement(status = RemarkStatus.READ)).contains("<br/>(read)"))
    }

    @Test
    fun `the tooltip is wrapped in html, or the breaks would be printed literally`() {
        assertTrue(tooltipFor(placement()).startsWith("<html>"))
    }

    /** A phrase is arbitrary source text: it can hold "<" or "&" as easily as the remark text can. */
    @Test
    fun `the phrase is shown, escaped`() {
        assertTrue(tooltipFor(placement(phrase = "a < b && c")).contains("a &lt; b &amp;&amp; c"))
    }

    @Test
    fun `a whole-line remark's tooltip has nothing extra for the phrase`() {
        assertEquals(
            "<html>why?</html>",
            tooltipFor(placement(phrase = null)),
        )
    }

    /**
     * The grey `asks` word the tree row draws, said on the tooltip too. It says only that the
     * remark asks: an answer draws its own balloon on the same lines, so "answered" is already on
     * screen as a second icon rather than as a second word here.
     */
    @Test
    fun `a remark that asks for an answer says so on its own line`() {
        assertTrue(
            tooltipFor(placement(asksForAnswer = true)).contains("<br/>(asks for an answer)"),
        )
    }

    @Test
    fun `an ordinary remark says nothing about asking`() {
        assertFalse(tooltipFor(placement(asksForAnswer = false)).contains("asks"))
    }

    private fun placement(
        text: String = "why?",
        commit: String? = null,
        status: RemarkStatus = RemarkStatus.PENDING,
        asksForAnswer: Boolean = false,
        orphaned: Boolean = false,
        phrase: String? = null,
    ) = RemarkPlacement(
        id = "r-1",
        text = text,
        commit = commit,
        status = status,
        asksForAnswer = asksForAnswer,
        startLine = 4,
        endLine = 6,
        orphaned = orphaned,
        phrase = phrase,
    )
}

/**
 * The answer's own tooltip, which has a different job from a remark's: a remark's tooltip is the
 * whole remark, and an answer's is a preview of something that only opens on a click.
 */
class AnswerTooltipTest {

    @Test
    fun `the question comes first and the answer's first line under it`() {
        val html = answerTooltipFor(answerPlacement())

        assertTrue(html, html.contains("why is this synchronized?<br/>because two threads write it"))
    }

    /** An answer whose remark was already gone carries no question, and must not print a blank line. */
    @Test
    fun `an answer with no question starts at its own first line`() {
        assertEquals(
            "<html>because two threads write it<br/>(click to read the whole answer)</html>",
            answerTooltipFor(answerPlacement(question = "")),
        )
    }

    /** A question is whatever was typed and an answer body is markdown a model wrote. Both escape. */
    @Test
    fun `angle brackets survive on both halves instead of being eaten as tags`() {
        val html = answerTooltipFor(
            answerPlacement(question = "why List<String>?", firstLine = "because Map<K, V> is worse"),
        )

        assertTrue(html, html.contains("List&lt;String&gt;"))
        assertTrue(html, html.contains("Map&lt;K, V&gt;"))
        assertFalse(html, html.contains("<String>"))
    }

    @Test
    fun `an orphaned answer says its line numbers are stale`() {
        assertTrue(answerTooltipFor(answerPlacement(orphaned = true)).contains("<br/>(orphaned"))
        assertFalse(answerTooltipFor(answerPlacement()).contains("orphaned"))
    }

    /** Nothing else on screen says a gutter icon can be clicked, so the tooltip has to. */
    @Test
    fun `the tooltip says the icon opens the answer`() {
        assertTrue(answerTooltipFor(answerPlacement()).contains("click to read the whole answer"))
    }

    @Test
    fun `the tooltip is wrapped in html, or the breaks would be printed literally`() {
        assertTrue(answerTooltipFor(answerPlacement()).startsWith("<html>"))
    }

    private fun answerPlacement(
        question: String = "why is this synchronized?",
        firstLine: String = "because two threads write it",
        orphaned: Boolean = false,
    ) = AnswerPlacement(
        id = "a-1",
        question = question,
        firstLine = firstLine,
        markdown = "$firstLine\n\nand a second paragraph",
        startLine = 4,
        endLine = 4,
        orphaned = orphaned,
    )
}

/**
 * equals and hashCode carry a real job: the platform compares the old and the new renderer on
 * every highlighting pass to decide whether to repaint. Identity equality would make the icon
 * flicker on every pass, so equality is keyed on the remark id plus everything that changes what
 * is painted.
 *
 * A fixture, because the renderer holds a real Project. An earlier draft made that field nullable
 * only so this test could skip the fixture, which is the tail wagging the dog.
 */
class RemarkGutterRendererTest : BasePlatformTestCase() {

    fun testTwoRenderersForTheSameUnchangedRemarkAreEqual() {
        assertEquals(renderer(), renderer())
        assertEquals(renderer().hashCode(), renderer().hashCode())
    }

    fun testADifferentRemarkIdIsADifferentRenderer() {
        assertFalse(renderer() == renderer(id = "r-2"))
    }

    fun testChangedTextIsADifferentRendererSoTheTooltipIsRepainted() {
        assertFalse(renderer() == renderer(text = "something else"))
    }

    fun testARemarkThatBecamePublishedIsADifferentRendererSoTheIconDims() {
        assertFalse(renderer() == renderer(status = RemarkStatus.PUBLISHED))
    }

    private fun renderer(id: String = "r-1", text: String = "why?", status: RemarkStatus = RemarkStatus.PENDING) =
        RemarkGutterIconRenderer(project, id, text, status)
}

/**
 * The answer renderer's equality, which carries the same job the remark renderer's does: the
 * platform compares the old and the new one on every highlighting pass, so identity equality would
 * make the balloon flicker.
 *
 * The markdown is part of the key even though it is not drawn. It is what a click opens, and an
 * answer replaced in place has to open the second body, not the first.
 */
class AnswerGutterRendererTest : BasePlatformTestCase() {

    fun testTwoRenderersForTheSameUnchangedAnswerAreEqual() {
        assertEquals(renderer(), renderer())
        assertEquals(renderer().hashCode(), renderer().hashCode())
    }

    fun testADifferentAnswerIdIsADifferentRenderer() {
        assertFalse(renderer() == renderer(id = "a-2"))
    }

    fun testAChangedTooltipIsADifferentRendererSoTheHoverIsRepainted() {
        assertFalse(renderer() == renderer(tooltip = "<html>something else</html>"))
    }

    fun testAReplacedBodyIsADifferentRendererSoTheClickOpensTheNewOne() {
        assertFalse(renderer() == renderer(markdown = "a second, different answer"))
    }

    /** An answer and a remark are never the same renderer, whatever they carry. */
    fun testAnAnswerRendererIsNeverEqualToARemarkRenderer() {
        assertFalse(renderer() as Any == RemarkGutterIconRenderer(project, "a-1", "why?", RemarkStatus.PENDING))
    }

    private fun renderer(
        id: String = "a-1",
        tooltip: String = "<html>why?</html>",
        markdown: String = "because two threads write it",
    ) = AnswerGutterIconRenderer(
        project = project,
        id = id,
        tooltip = tooltip,
        question = "why?",
        markdown = markdown,
    )
}
