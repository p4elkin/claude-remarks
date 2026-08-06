package dev.sasha.clauderemarks.ui

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * Plain JUnit: resource resolution needs no fixture. This is the test that catches a typo in a
 * resource path or a file that did not make it into the jar — `IconLoader` hands back an icon that
 * has not looked at the path yet, so nothing fails at the `load` line, and a test that only calls
 * `RemarkStatusLook.icon(...)` still gets a non-null `Icon` back either way. See `RemarkIcons.kt`'s
 * KDoc.
 *
 * ⚠️ The path comes from `RemarkIcons.resourcePath`, the same expression production hands
 * `IconLoader`, never from a copy of it written here. A copy would leave a typo in the production
 * template invisible: all six files would still sit under the path this test checked.
 *
 * The fixture-backed half that reads each loaded icon's width lives in [RemarkIconsFixtureTest],
 * the same split `OpenReviewFilesTest`/`OpenReviewFilesFixtureTest` already uses.
 */
class RemarkIconsTest {

    @Test
    fun `all six icon resources resolve on the classpath, at the path production asks for`() {
        listOf(
            "questionPending", "questionPending_dark",
            "questionPublished", "questionPublished_dark",
            "questionAnswered", "questionAnswered_dark",
        ).forEach { name ->
            val path = RemarkIcons.resourcePath(name)
            assertNotNull("expected $path on the classpath", RemarkIcons::class.java.getResource(path))
        }
    }
}

/**
 * `IconLoader` wants an application, and reading a loaded icon's width is what catches an SVG that
 * fails to parse rather than only failing to resolve as a resource — the failure mode
 * [RemarkIconsTest] above cannot reach on its own.
 *
 * **Why 16 is a real assertion and not a tautology.** A `CachedImageIcon` resolves on the first call
 * that needs a size, and `ScaledIconCache` falls back to the platform's `EMPTY_ICON` when the image
 * cannot be loaded at all. That fallback is **1×1**, not 16×16, so a path that resolves to nothing
 * and an SVG that does not parse both report a width of 1 here. Checked by hand: pointing
 * `QuestionPending` at a name with no file behind it fails
 * [testQuestionPendingIsSixteenWide] with `expected:<16> but was:<1>`.
 */
class RemarkIconsFixtureTest : BasePlatformTestCase() {

    fun testQuestionPendingIsSixteenWide() {
        assertEquals(16, RemarkIcons.QuestionPending.iconWidth)
    }

    fun testQuestionPublishedIsSixteenWide() {
        assertEquals(16, RemarkIcons.QuestionPublished.iconWidth)
    }

    fun testQuestionAnsweredIsSixteenWide() {
        assertEquals(16, RemarkIcons.QuestionAnswered.iconWidth)
    }

    /**
     * The three are three different files, so they must be three different icons. This is what would
     * catch the three loads collapsing onto one shared object — two `val`s accidentally naming the
     * same resource, say — which the three width assertions above cannot see, since one icon drawn
     * three times is 16 wide every time.
     *
     * It does **not** catch a path with no file behind it: `IconLoader` caches per path and hands back
     * its own wrapper either way, so three broken paths would still be three distinct icons. The width
     * assertions are what cover that. Checked by hand with the same broken-path run.
     */
    fun testTheThreeIconsAreThreeDistinctIcons() {
        val icons = setOf(
            RemarkIcons.QuestionPending,
            RemarkIcons.QuestionPublished,
            RemarkIcons.QuestionAnswered,
        )

        assertEquals(icons.toString(), 3, icons.size)
    }
}
