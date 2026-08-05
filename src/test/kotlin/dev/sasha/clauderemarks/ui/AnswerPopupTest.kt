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

    private fun convert(markdown: String): String =
        ReadAction.compute<String, RuntimeException> {
            DocMarkdownToHtmlConverter.convert(project, markdown)
        }
}
