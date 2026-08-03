package dev.sasha.clauderemarks.review

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.ui.UIUtil
import dev.sasha.clauderemarks.model.RemarkStatus
import dev.sasha.clauderemarks.store.RemarkStore
import dev.sasha.clauderemarks.store.addRemark
import java.nio.file.Files

/**
 * The failure-path test here — [testAFailedWriteMarksNothingSentAndLeavesTheReviewWaiting] — is
 * the only guard on CLAUDE.md rule 8: nothing is marked sent unless the handover succeeded.
 */
class SendReviewTest : BasePlatformTestCase() {

    override fun setUp() {
        super.setUp()
        // The light fixture project is shared across test classes.
        RemarkStore.getInstance(project).clear()
        WaitingReviewService.getInstance(project).clear()
    }

    override fun tearDown() {
        RemarkStore.getInstance(project).clear()
        WaitingReviewService.getInstance(project).clear()
        super.tearDown()
    }

    fun testSendingWritesTheWholePromptToTheWaitingReviewsOutputPath() {
        val outputPath = Files.createTempDirectory("send-review-test")
        WaitingReviewService.getInstance(project).start("s1", "a label", 1800, outputPath)
        addRemark(project, "A.kt", LINES, 0..0, "a note about A", null)

        sendToWaitingReview(project)
        settle()

        assertTrue(Files.readString(handoffFile(outputPath)).contains("a note about A"))
    }

    fun testSendingMarksTheRemarksSent() {
        val outputPath = Files.createTempDirectory("send-review-test")
        WaitingReviewService.getInstance(project).start("s1", "a label", 1800, outputPath)
        val remark = addRemark(project, "A.kt", LINES, 0..0, "a note", null)

        sendToWaitingReview(project)
        settle()

        assertEquals(RemarkStatus.SENT, statusOf(remark.id!!))
    }

    fun testSendingClearsTheWaitingReview() {
        val outputPath = Files.createTempDirectory("send-review-test")
        WaitingReviewService.getInstance(project).start("s1", "a label", 1800, outputPath)
        addRemark(project, "A.kt", LINES, 0..0, "a note", null)

        sendToWaitingReview(project)
        settle()

        assertNull(WaitingReviewService.getInstance(project).current())
    }

    fun testAFailedWriteMarksNothingSentAndLeavesTheReviewWaiting() {
        // The parent of outputPath is a regular file, so Files.createDirectories throws when the
        // write tries to create outputPath itself.
        val outputPath = Files.createTempFile("send-review-blocked", ".txt").resolve("subdir")
        WaitingReviewService.getInstance(project).start("s1", "a label", 1800, outputPath)
        val remark = addRemark(project, "A.kt", LINES, 0..0, "a note", null)

        sendToWaitingReview(project)
        settle()

        assertEquals(RemarkStatus.PENDING, statusOf(remark.id!!))
        assertNotNull(WaitingReviewService.getInstance(project).current())
    }

    fun testSendingWithNothingPendingLeavesTheReviewWaitingAndWritesNoFile() {
        val outputPath = Files.createTempDirectory("send-review-test")
        WaitingReviewService.getInstance(project).start("s1", "a label", 1800, outputPath)

        sendToWaitingReview(project)
        settle()

        assertNotNull(WaitingReviewService.getInstance(project).current())
        assertFalse(Files.exists(handoffFile(outputPath)))
    }

    fun testRejectingWritesTheMarkerAndClearsTheReview() {
        val outputPath = Files.createTempDirectory("send-review-test")
        WaitingReviewService.getInstance(project).start("s1", "a label", 1800, outputPath)

        rejectWaitingReview(project)

        val firstLine = Files.readString(handoffFile(outputPath)).lineSequence().first()
        assertEquals("<!-- claude-remarks: rejected -->", firstLine)
        assertNull(WaitingReviewService.getInstance(project).current())
    }

    fun testRejectingLeavesEveryRemarkPending() {
        val outputPath = Files.createTempDirectory("send-review-test")
        WaitingReviewService.getInstance(project).start("s1", "a label", 1800, outputPath)
        val remark = addRemark(project, "A.kt", LINES, 0..0, "a note", null)

        rejectWaitingReview(project)

        assertEquals(RemarkStatus.PENDING, statusOf(remark.id!!))
    }

    fun testAFailedRejectionStillClearsTheReview() {
        // The parent of outputPath is a regular file, so Files.createDirectories throws when the
        // write tries to create outputPath itself.
        val outputPath = Files.createTempFile("reject-review-blocked", ".txt").resolve("subdir")
        WaitingReviewService.getInstance(project).start("s1", "a label", 1800, outputPath)
        val remark = addRemark(project, "A.kt", LINES, 0..0, "a note", null)

        rejectWaitingReview(project)

        assertNull(WaitingReviewService.getInstance(project).current())
        assertEquals(RemarkStatus.PENDING, statusOf(remark.id!!))
    }

    private fun statusOf(id: String) = RemarkStore.getInstance(project).all().single { it.id == id }.status

    // sendToWaitingReview hops off the EDT (the read action) and back (finishOnUiThread), so an
    // assertion made right after calling it sees the state before that finishes.
    private fun settle() {
        repeat(10) {
            UIUtil.dispatchAllInvocationEvents()
            Thread.sleep(10)
        }
        UIUtil.dispatchAllInvocationEvents()
    }

    private companion object {
        val LINES = listOf("alpha", "beta")
    }
}
