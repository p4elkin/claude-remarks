package dev.sasha.clauderemarks.review

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.sasha.clauderemarks.model.RemarkStatus
import dev.sasha.clauderemarks.store.RemarkStore
import dev.sasha.clauderemarks.store.TempPaths
import dev.sasha.clauderemarks.store.addRemark
import dev.sasha.clauderemarks.store.settleInvocationQueue
import java.nio.file.Files

/**
 * `answerWaitingReview` and `markSent` are both synchronous, ordinary calls against a project
 * service, so the tests that drive them need no `settleInvocationQueue()` of their own. It still
 * appears after `finishReview` and `expireStaleReview` below: both of those queue their store
 * mutation and their balloon through `invokeLater` (see `reportLater` in this same file), so an
 * assertion made right after calling either would see the state before that finishes.
 *
 * The rejection tests and the three acknowledgement tests below reach the `Sent` phase by calling
 * `atomicWriteString`/`WaitingReviewService.markSent` directly rather than through a send action —
 * there is no send action any more, publishing is how a waiting review is answered, and that
 * pipeline is exercised by `action/PublishRemarksTest` instead, for the same reason
 * `sendToWaitingReview`'s own removed KDoc gave: pumping a read action plus an EDT callback in a
 * light fixture buys a flaky test for very little.
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

    /** The header carries the review it answers, per PublishRemarks.kt — this is the stamp itself. */
    fun testAnsweringAWaitingReviewRecordsWhatWasPublished() {
        WaitingReviewService.getInstance(project).start("s1", "a label", 1800, temp.dir("send-review-test"))
        val remark = addRemark(project, "A.kt", LINES, 0..0, "a note", null)

        val sentence = answerWaitingReview(project, "s1", listOf(remark.id!!))

        assertNull(sentence)
        val waiting = WaitingReviewService.getInstance(project).current()
        assertNotNull(waiting)
        val phase = waiting!!.phase
        assertTrue(phase is ReviewPhase.Sent)
        assertEquals(listOf(remark.id), (phase as ReviewPhase.Sent).ids)
    }

    /**
     * The window between a publish snapshotting the waiting review and answering it: the review can
     * have been rejected, acknowledged, or run past its deadline in between, all on other threads.
     * markSent finding nothing to stamp is the safe outcome, and answerWaitingReview has to say so
     * rather than claim a handover that did not happen.
     */
    fun testAnsweringAReviewThatAlreadyEndedSaysSoInsteadOfClaimingAHandover() {
        WaitingReviewService.getInstance(project).start("s1", "a label", 1800, temp.dir("send-review-test"))
        WaitingReviewService.getInstance(project).clear("s1")

        val sentence = answerWaitingReview(project, "s1", listOf("a"))

        assertNotNull(sentence)
    }

    /**
     * Phase 7's "already sent" refusal forbade a second send while the first was still unread. That
     * refusal is gone: publishing again while a review is still Sent is now the normal way to add
     * more to it, and the second answer simply replaces the first's recorded ids.
     */
    fun testAnsweringAReviewASecondTimeReplacesTheRecordedIds() {
        WaitingReviewService.getInstance(project).start("s1", "a label", 1800, temp.dir("send-review-test"))
        answerWaitingReview(project, "s1", listOf("a"))

        val sentence = answerWaitingReview(project, "s1", listOf("a", "b"))

        assertNull(sentence)
        val phase = WaitingReviewService.getInstance(project).current()!!.phase
        assertTrue(phase is ReviewPhase.Sent)
        assertEquals(listOf("a", "b"), (phase as ReviewPhase.Sent).ids)
    }

    /** The phase's central decision, on the new path: only a `read` acknowledgement marks anything. */
    fun testNothingIsMarkedReadUntilTheAcknowledgement() {
        WaitingReviewService.getInstance(project).start("s1", "a label", 1800, temp.dir("send-review-test"))
        val remark = addRemark(project, "A.kt", LINES, 0..0, "a note", null)

        answerWaitingReview(project, "s1", listOf(remark.id!!))

        assertEquals(RemarkStatus.PENDING, statusOf(remark.id!!))
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

    /**
     * Reject after a Sent phase must not touch the handoff file: the phase guard in
     * rejectWaitingReview only clears the review. `answerWaitingReview` is what reaches Sent now —
     * there is no send action to write the file, so the file is written by hand here to stand in
     * for what a publish would have written before answering.
     */
    fun testRejectingAfterASendDoesNotOverwriteTheHandoffFile() {
        val outputPath = temp.dir("send-review-test")
        WaitingReviewService.getInstance(project).start("s1", "a label", 1800, outputPath)
        val remark = addRemark(project, "A.kt", LINES, 0..0, "a note about A", null)
        atomicWriteString(handoffFile(outputPath), "a note about A")
        answerWaitingReview(project, "s1", listOf(remark.id!!))
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

        answerWaitingReview(project, "s1", listOf(remark.id!!))

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
        answerWaitingReview(project, "s1", listOf(remark.id!!))

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
        answerWaitingReview(project, "s1", listOf(remark.id!!))

        expireStaleReview(project, now = started + 1_800_001L)
        settleInvocationQueue()

        assertEquals(RemarkStatus.PENDING, statusOf(remark.id!!))
        assertNull(WaitingReviewService.getInstance(project).current())
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

    private fun statusOf(id: String) = RemarkStore.getInstance(project).all().single { it.id == id }.status

    private companion object {
        val LINES = listOf("alpha", "beta")
    }
}
