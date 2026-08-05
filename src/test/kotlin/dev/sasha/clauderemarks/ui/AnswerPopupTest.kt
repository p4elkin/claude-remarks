package dev.sasha.clauderemarks.ui

import com.intellij.markdown.utils.doc.DocMarkdownToHtmlConverter
import com.intellij.openapi.application.ReadAction
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * The one test that the platform's own markdown conversion resolves and returns in this build.
 *
 * It does not test the rendering. Whether a heading is drawn in a larger font, whether a fence is
 * coloured and whether a table gets column borders are all hand checks — there are no UI-rendering
 * tests in this project at all. What this pins is narrower and is the part that can break silently:
 * `DocMarkdownToHtmlConverter` is the phase's one new platform dependency, it carries no
 * `@ApiStatus` annotation so the plugin verifier says nothing about it, and a build where the class
 * moved or the two-argument overload disappeared would otherwise only be found by opening an answer
 * by hand.
 *
 * Fixture-backed, because `convert` takes a real `Project`: it looks the markdown language up
 * through the project and builds a `PsiFile` per code fence to highlight it.
 *
 * The conversion is wrapped in an explicit read action rather than relying on the test thread
 * happening to hold one. `convert` is `@RequiresReadLock`, and `ui/AnswerPopup.kt` calls it inside
 * `ReadAction.nonBlocking` for that reason, so the test calls it the same way the production code
 * does.
 */
class AnswerPopupTest : BasePlatformTestCase() {

    fun testAHeadingComesBackAsAHeadingTag() {
        val html = convert("# What the anchor does\n\nIt follows the code.\n")

        assertTrue("expected a heading tag in: $html", html.contains("<h"))
    }

    fun testAFencedBlockComesBackAsAPreTag() {
        val html = convert("Try this:\n\n```kotlin\nval x = 1\n```\n")

        assertTrue("expected a pre tag in: $html", html.contains("<pre"))
    }

    /**
     * What the popup is made of, without opening one: showing a popup needs a window, and the three
     * things that can silently regress are all in the component, not in the window. Reverting the
     * pane to a JBTextArea showing raw markdown, or dropping `isEditable = false` so a caret blinks
     * in an answer, both left the suite green while the two conversion tests above kept passing.
     */
    fun testTheAnswerPaneIsAReadOnlyHtmlPaneCarryingTheConvertedHtml() {
        val pane = answerPane(convert("# What the anchor does\n\nIt follows the code.\n"))

        assertFalse("the answer pane must not be editable", pane.isEditable)
        assertTrue("expected the converted HTML in the pane, was: ${pane.text}", pane.text.contains("<h"))
    }

    /**
     * The whole reason [answerBodyHtml] exists: the popup used to show the answer with no sign of
     * what had been asked, so a person reading one could not see their own question.
     */
    fun testTheQuestionIsDrawnAboveTheAnswer() {
        val body = answerBodyHtml("why is this synchronized?", convert("Because two threads write it.\n"))

        assertTrue("expected the question in: $body", body.contains("why is this synchronized?"))
        assertTrue("expected a label in: $body", body.contains("You asked"))
        assertTrue(
            "the question must come before the answer, was: $body",
            body.indexOf("why is this synchronized?") < body.indexOf("Because two threads write it"),
        )
    }

    /** The question is quoted and ruled off, so it cannot read as the answer's opening paragraph. */
    fun testTheQuestionIsSetApartFromTheAnswerBody() {
        val body = answerBodyHtml("why?", convert("Because.\n"))

        assertTrue("expected a quote block in: $body", body.contains("<blockquote>"))
        assertTrue("expected a rule between the two in: $body", body.contains("<hr/>"))
        assertTrue(
            "the rule must sit between the question and the answer, was: $body",
            body.indexOf("<hr/>") in (body.indexOf("why?") + 1) until body.indexOf("Because"),
        )
    }

    /**
     * The question is the person's own text, so "<" is a character and "#" is a character. Escaped
     * the way `RemarkGutterIcon.asHtml` escapes a tooltip, never handed to the markdown converter.
     */
    fun testTheQuestionIsEscapedRatherThanInterpreted() {
        val body = answerBodyHtml("why `a < b` and **not** <b>this</b>?", convert("Because.\n"))

        assertTrue("expected the < escaped in: $body", body.contains("a &lt; b"))
        assertTrue("expected the tag escaped in: $body", body.contains("&lt;b&gt;this&lt;/b&gt;"))
        assertTrue("the ** must stay literal in: $body", body.contains("**not**"))
    }

    /**
     * An answer whose remark was deleted before it arrived stores an empty question. Such an answer
     * shows alone — no label, no empty quote block, no rule hanging over nothing.
     */
    fun testAnEmptyQuestionLeavesNoLabelAndNoQuoteBlock() {
        val answer = convert("Because two threads write it.\n")

        assertEquals(answer, answerBodyHtml("", answer))
        assertEquals(answer, answerBodyHtml("   \n  ", answer))
    }

    /** The two halves reach the pane together, which is what a person actually sees. */
    fun testThePaneCarriesTheQuestionAndTheAnswer() {
        val pane = answerPane(answerBodyHtml("why?", convert("# Because\n\nTwo threads write it.\n")))

        assertTrue("expected the question in the pane, was: ${pane.text}", pane.text.contains("why?"))
        assertTrue("expected the converted HTML in the pane, was: ${pane.text}", pane.text.contains("<h"))
    }

    private fun convert(markdown: String): String =
        ReadAction.compute<String, RuntimeException> {
            DocMarkdownToHtmlConverter.convert(project, markdown)
        }
}
