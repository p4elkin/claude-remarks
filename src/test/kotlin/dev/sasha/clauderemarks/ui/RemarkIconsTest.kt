package dev.sasha.clauderemarks.ui

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * Plain JUnit: resource resolution needs no fixture. This is the test that catches a typo in a
 * resource path or a file that did not make it into the jar — `IconLoader` only logs and returns a
 * placeholder at runtime, and a test that only calls `RemarkStatusLook.icon(...)` still gets a
 * non-null `Icon` back either way. See `RemarkIcons.kt`'s KDoc.
 *
 * The fixture-backed half that reads each loaded icon's width lives in [RemarkIconsFixtureTest],
 * the same split `OpenReviewFilesTest`/`OpenReviewFilesFixtureTest` already uses.
 */
class RemarkIconsTest {

    @Test
    fun `all six icon resources resolve on the classpath`() {
        listOf(
            "questionPending", "questionPending_dark",
            "questionPublished", "questionPublished_dark",
            "questionAnswered", "questionAnswered_dark",
        ).forEach { name ->
            val resource = RemarkIcons::class.java.getResource("/dev/sasha/clauderemarks/icons/$name.svg")
            assertNotNull("expected /dev/sasha/clauderemarks/icons/$name.svg on the classpath", resource)
        }
    }
}

/**
 * `IconLoader` wants an application, and reading a loaded icon's width is what catches an SVG that
 * fails to parse rather than only failing to resolve as a resource — the failure mode
 * [RemarkIconsTest] above cannot reach on its own.
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
}
