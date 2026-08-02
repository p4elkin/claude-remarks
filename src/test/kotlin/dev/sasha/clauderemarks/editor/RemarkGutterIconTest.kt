package dev.sasha.clauderemarks.editor

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.sasha.clauderemarks.model.RemarkSeverity
import dev.sasha.clauderemarks.model.RemarkTag
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The platform renders a gutter tooltip as HTML. That decides two things at once. A plain "\n"
 * would not break a line, so the orphaned and sent notes would run into the remark text. And the
 * remark text is whatever the user typed, so a remark reading "why is List<String> here?" would
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

    @Test
    fun `the tag is shown lowercase`() {
        assertTrue(tooltipFor(placement(tag = RemarkTag.BUG)).contains("[bug]"))
    }

    @Test
    fun `the severity is shown lowercase`() {
        assertTrue(tooltipFor(placement(severity = RemarkSeverity.MUST)).contains("must"))
    }

    @Test
    fun `an orphan and a sent remark each say so on their own line`() {
        val html = tooltipFor(placement(orphaned = true, sent = true))

        assertTrue(html.contains("<br/>(orphaned"))
        assertTrue(html.contains("<br/>(sent)"))
    }

    @Test
    fun `the tooltip is wrapped in html, or the breaks would be printed literally`() {
        assertTrue(tooltipFor(placement()).startsWith("<html>"))
    }

    private fun placement(
        text: String = "why?",
        tag: RemarkTag? = null,
        severity: RemarkSeverity = RemarkSeverity.SHOULD,
        sent: Boolean = false,
        orphaned: Boolean = false,
    ) = RemarkPlacement(
        id = "r-1",
        text = text,
        tag = tag,
        severity = severity,
        sent = sent,
        startLine = 4,
        endLine = 6,
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

    fun testARemarkThatBecameSentIsADifferentRendererSoTheIconDims() {
        assertFalse(renderer() == renderer(sent = true))
    }

    private fun renderer(id: String = "r-1", text: String = "why?", sent: Boolean = false) =
        RemarkGutterIconRenderer(project, id, text, sent)
}
