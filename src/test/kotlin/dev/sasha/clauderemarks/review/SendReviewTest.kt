package dev.sasha.clauderemarks.review

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.sasha.clauderemarks.model.RemarkStatus
import dev.sasha.clauderemarks.store.RemarkStore
import dev.sasha.clauderemarks.store.TempPaths
import dev.sasha.clauderemarks.store.addRemark
import dev.sasha.clauderemarks.store.settleInvocationQueue
import java.nio.file.Files

/**
 * The failure-path test here — [testAFailedWriteMarksNothingSentAndLeavesTheReviewWaiting] — is
 * the only guard on CLAUDE.md rule 8: nothing is marked sent unless the handover succeeded.
 *
 * `settleInvocationQueue()` appears after every send below because sendToWaitingReview hops off the
 * EDT (the read action) and back (finishOnUiThread), so an assertion made right after calling it
 * would see the state before that finishes.
 */
class SendReviewTest : BasePlatformTestCase() {

    private val temp = TempPaths()

    override fun setUp() {
        super.setUp()
        // The light fixture project is shared across test classes.
        RemarkStore.getInstance(project).clear()
        WaitingReviewService.getInstance(project).clear()
    }

    override fun tearDown() {
        RemarkStore.getInstance(project).clear()
        WaitingReviewService.getInstance(project).clear()
        temp.deleteAll()
        super.tearDown()
    }

    fun testSendingWritesTheWholePromptToTheWaitingReviewsOutputPath() {
        val outputPath = temp.dir("send-review-test")
        WaitingReviewService.getInstance(project).start("s1", "a label", 1800, outputPath)
        addRemark(project, "A.kt", LINES, 0..0, "a note about A", null)

        sendToWaitingReview(project)
        settleInvocationQueue()

        assertTrue(Files.readString(handoffFile(outputPath)).contains("a note about A"))
    }

    fun testSendingMarksNothingUntilTheAgentAcknowledges() {
        val outputPath = temp.dir("send-review-test")
        WaitingReviewService.getInstance(project).start("s1", "a label", 1800, outputPath)
        val remark = addRemark(project, "A.kt", LINES, 0..0, "a note", null)

        sendToWaitingReview(project)
        settleInvocationQueue()

        assertEquals(RemarkStatus.PENDING, statusOf(remark.id!!))
    }

    fun testSendingKeepsTheReviewAndRecordsWhatWasWritten() {
        val outputPath = temp.dir("send-review-test")
        WaitingReviewService.getInstance(project).start("s1", "a label", 1800, outputPath)
        val remark = addRemark(project, "A.kt", LINES, 0..0, "a note", null)

        sendToWaitingReview(project)
        settleInvocationQueue()

        val waiting = WaitingReviewService.getInstance(project).current()
        assertNotNull(waiting)
        val phase = waiting!!.phase
        assertTrue(phase is ReviewPhase.Sent)
        assertEquals(listOf(remark.id), (phase as ReviewPhase.Sent).ids)
    }

    fun testAFailedWriteMarksNothingSentAndLeavesTheReviewWaiting() {
        // The parent of outputPath is a regular file, so Files.createDirectories throws when the
        // write tries to create outputPath itself.
        val outputPath = temp.file("send-review-blocked", ".txt").resolve("subdir")
        WaitingReviewService.getInstance(project).start("s1", "a label", 1800, outputPath)
        val remark = addRemark(project, "A.kt", LINES, 0..0, "a note", null)

        sendToWaitingReview(project)
        settleInvocationQueue()

        assertEquals(RemarkStatus.PENDING, statusOf(remark.id!!))
        assertNotNull(WaitingReviewService.getInstance(project).current())
    }

    fun testSendingWithNothingPendingLeavesTheReviewWaitingAndWritesNoFile() {
        val outputPath = temp.dir("send-review-test")
        WaitingReviewService.getInstance(project).start("s1", "a label", 1800, outputPath)

        sendToWaitingReview(project)
        settleInvocationQueue()

        assertNotNull(WaitingReviewService.getInstance(project).current())
        assertFalse(Files.exists(handoffFile(outputPath)))
    }

    fun testRejectingWritesTheMarkerAndClearsTheReview() {
        val outputPath = temp.dir("send-review-test")
        WaitingReviewService.getInstance(project).start("s1", "a label", 1800, outputPath)

        rejectWaitingReview(project)

        val firstLine = Files.readString(handoffFile(outputPath)).lineSequence().first()
        assertEquals("<!-- claude-remarks: rejected -->", firstLine)
        assertNull(WaitingReviewService.getInstance(project).current())
    }

    fun testRejectingLeavesEveryRemarkPending() {
        val outputPath = temp.dir("send-review-test")
        WaitingReviewService.getInstance(project).start("s1", "a label", 1800, outputPath)
        val remark = addRemark(project, "A.kt", LINES, 0..0, "a note", null)

        rejectWaitingReview(project)

        assertEquals(RemarkStatus.PENDING, statusOf(remark.id!!))
    }

    fun testASecondSendWhileWaitingForTheAcknowledgementIsRefused() {
        val outputPath = temp.dir("send-review-test")
        WaitingReviewService.getInstance(project).start("s1", "a label", 1800, outputPath)
        addRemark(project, "A.kt", LINES, 0..0, "a note", null)
        sendToWaitingReview(project)
        settleInvocationQueue()
        val contentAfterFirstSend = Files.readString(handoffFile(outputPath))

        addRemark(project, "A.kt", LINES, 0..0, "a second note", null)
        sendToWaitingReview(project)
        settleInvocationQueue()

        assertEquals(contentAfterFirstSend, Files.readString(handoffFile(outputPath)))
    }

    fun testRejectingAfterASendDoesNotOverwriteTheHandoffFile() {
        val outputPath = temp.dir("send-review-test")
        WaitingReviewService.getInstance(project).start("s1", "a label", 1800, outputPath)
        addRemark(project, "A.kt", LINES, 0..0, "a note about A", null)
        sendToWaitingReview(project)
        settleInvocationQueue()
        val sentContent = Files.readString(handoffFile(outputPath))

        rejectWaitingReview(project)

        assertEquals(sentContent, Files.readString(handoffFile(outputPath)))
        assertNull(WaitingReviewService.getInstance(project).current())
    }

    /** An ack leaves the remarks READ, not PUBLISHED: only a real `read` acknowledgement over
     *  POST /api/claude-remarks/ack may reach READ, per CLAUDE.md's guard on markRemarksRead. */
    fun testAReadAcknowledgementAfterASendMarksTheRemarksRead() {
        val outputPath = temp.dir("send-review-test")
        WaitingReviewService.getInstance(project).start("s1", "a label", 1800, outputPath)
        val remark = addRemark(project, "A.kt", LINES, 0..0, "a note", null)

        sendToWaitingReview(project)
        settleInvocationQueue()

        val outcome = finishReview(project, "s1", ReviewEnd.READ)
        settleInvocationQueue()

        assertEquals(AckOutcome.OK, outcome)
        assertEquals(RemarkStatus.READ, statusOf(remark.id!!))
        assertNull(WaitingReviewService.getInstance(project).current())
    }

    /**
     * The phase's central decision, from the other side: only a `read` acknowledgement marks
     * anything sent. An agent that gave up after the file was written leaves every remark pending.
     */
    fun testAnAbandonedAcknowledgementAfterASendLeavesTheRemarksPending() {
        val outputPath = temp.dir("send-review-test")
        WaitingReviewService.getInstance(project).start("s1", "a label", 1800, outputPath)
        val remark = addRemark(project, "A.kt", LINES, 0..0, "a note", null)
        sendToWaitingReview(project)
        settleInvocationQueue()

        val outcome = finishReview(project, "s1", ReviewEnd.ABANDONED)
        settleInvocationQueue()

        assertEquals(AckOutcome.OK, outcome)
        assertEquals(RemarkStatus.PENDING, statusOf(remark.id!!))
        assertNull(WaitingReviewService.getInstance(project).current())
    }

    /** The deadline path, the one that runs with nobody watching, marks nothing sent either. */
    fun testTheDeadlinePassingAfterASendLeavesTheRemarksPending() {
        val outputPath = temp.dir("send-review-test")
        val started = System.currentTimeMillis()
        WaitingReviewService.getInstance(project).start("s1", "a label", 1800, outputPath)
        val remark = addRemark(project, "A.kt", LINES, 0..0, "a note", null)
        sendToWaitingReview(project)
        settleInvocationQueue()

        expireStaleReview(project, now = started + 1_800_001L)
        settleInvocationQueue()

        assertEquals(RemarkStatus.PENDING, statusOf(remark.id!!))
        assertNull(WaitingReviewService.getInstance(project).current())
    }

    /**
     * The window between the snapshot the send starts from and the write it ends with: prepare()
     * runs off the EDT, so a Reject can land in between. The rejection body must survive, and the
     * balloon must not claim a send.
     */
    fun testASendWhoseReviewEndedMidRenderDoesNotOverwriteTheRejection() {
        val outputPath = temp.dir("send-review-test")
        WaitingReviewService.getInstance(project).start("s1", "a label", 1800, outputPath)
        addRemark(project, "A.kt", LINES, 0..0, "a note about A", null)

        // The write in sendToWaitingReview happens inside finishOnUiThread, which has not run yet:
        // nothing here has drained the queue.
        sendToWaitingReview(project)
        rejectWaitingReview(project)
        settleInvocationQueue()

        val firstLine = Files.readString(handoffFile(outputPath)).lineSequence().first()
        assertEquals(REJECTED_MARKER, firstLine)
    }

    fun testAFailedRejectionStillClearsTheReview() {
        // The parent of outputPath is a regular file, so Files.createDirectories throws when the
        // write tries to create outputPath itself.
        val outputPath = temp.file("reject-review-blocked", ".txt").resolve("subdir")
        WaitingReviewService.getInstance(project).start("s1", "a label", 1800, outputPath)
        val remark = addRemark(project, "A.kt", LINES, 0..0, "a note", null)

        rejectWaitingReview(project)

        assertNull(WaitingReviewService.getInstance(project).current())
        assertEquals(RemarkStatus.PENDING, statusOf(remark.id!!))
    }

    /**
     * The toolbar button and the Tools-menu action both read this one condition, so it is guarded
     * where it lives. Both directions: a condition that is permanently false would pass the second
     * assertion on its own.
     */
    fun testCanSendIsTrueWhileWaitingAndFalseOnceTheRemarksAreSent() {
        WaitingReviewService.getInstance(project).start("s1", "a label", 1800, temp.dir("send-review-test"))
        addRemark(project, "A.kt", LINES, 0..0, "a note", null)

        assertTrue(canSend(project))

        WaitingReviewService.getInstance(project).markSent("s1", listOf("a"))

        assertFalse(canSend(project))
    }

    private fun statusOf(id: String) = RemarkStore.getInstance(project).all().single { it.id == id }.status

    private companion object {
        val LINES = listOf("alpha", "beta")
    }
}
