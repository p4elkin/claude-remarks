package dev.sasha.clauderemarks.review

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.sasha.clauderemarks.model.RemarkStatus
import dev.sasha.clauderemarks.store.RemarkStore
import dev.sasha.clauderemarks.store.TempPaths
import dev.sasha.clauderemarks.store.addRemark
import dev.sasha.clauderemarks.store.settleInvocationQueue
import java.nio.file.Files
import java.nio.file.Path

/**
 * `answerWaitingReview` and `markSent` are both synchronous, ordinary calls against a project
 * service, so the tests that drive them need no `settleInvocationQueue()` of their own. It still
 * appears after `finishReview` and `expireStaleReview` below: both of those queue their store
 * mutation and their balloon through `invokeLater` (see `reportLater` in this same file), so an
 * assertion made right after calling either would see the state before that finishes.
 *
 * The three acknowledgement tests below, and one rejection test, reach the `Sent` phase by calling
 * `WaitingReviewService.markSent`/`answerWaitingReview` directly rather than through a publish
 * action — that whole pipeline is exercised by `action/PublishRemarksTest` instead, for the same
 * reason `sendToWaitingReview`'s own removed KDoc gave: pumping a read action plus an EDT callback
 * in a light fixture buys a flaky test for very little.
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
        WaitingReviewService.getInstance(project).start("s1", "a label", 1800)
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
        WaitingReviewService.getInstance(project).start("s1", "a label", 1800)
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
        WaitingReviewService.getInstance(project).start("s1", "a label", 1800)
        answerWaitingReview(project, "s1", listOf("a"))

        val sentence = answerWaitingReview(project, "s1", listOf("a", "b"))

        assertNull(sentence)
        val phase = WaitingReviewService.getInstance(project).current()!!.phase
        assertTrue(phase is ReviewPhase.Sent)
        assertEquals(listOf("a", "b"), (phase as ReviewPhase.Sent).ids)
    }

    /** The phase's central decision, on the new path: only a `read` acknowledgement marks anything. */
    fun testNothingIsMarkedReadUntilTheAcknowledgement() {
        WaitingReviewService.getInstance(project).start("s1", "a label", 1800)
        val remark = addRemark(project, "A.kt", LINES, 0..0, "a note", null)

        answerWaitingReview(project, "s1", listOf(remark.id!!))

        assertEquals(RemarkStatus.PENDING, statusOf(remark.id!!))
    }

    /** The header reads back with rejected: yes, remarks: 0 and the review's own session. */
    fun testRejectingWritesARejectionBatchToThePublishedFile() {
        val dir = temp.dir("send-review-test")
        val root = identity()
        WaitingReviewService.getInstance(project).start("s1", "a label", 1800)

        rejectWaitingReview(project, dir)

        val content = Files.readString(dir.resolve(publishedName(root.toString())))
        val header = publishedHeaderOf(content)!!
        assertTrue(header.rejected)
        assertEquals(0, header.remarks)
        assertEquals("s1", header.reviewSession)
        assertNull(WaitingReviewService.getInstance(project).current())
    }

    fun testRejectingLeavesEveryRemarkPending() {
        WaitingReviewService.getInstance(project).start("s1", "a label", 1800)
        val remark = addRemark(project, "A.kt", LINES, 0..0, "a note", null)

        rejectWaitingReview(project, temp.dir("send-review-test"))

        assertEquals(RemarkStatus.PENDING, statusOf(remark.id!!))
    }

    /**
     * Reject after a Sent phase must not touch the published file: the phase guard in
     * rejectWaitingReview only clears the review. The batch is written by hand here, through
     * writePublished, to stand in for what a publish would already have written before answering.
     */
    fun testRejectingAfterASendDoesNotOverwriteThePublishedFile() {
        val dir = temp.dir("send-review-test")
        WaitingReviewService.getInstance(project).start("s1", "a label", 1800)
        val remark = addRemark(project, "A.kt", LINES, 0..0, "a note about A", null)
        val root = identity()
        val header = PublishedHeader(
            nonce = "n1",
            publishedAt = System.currentTimeMillis(),
            commit = null,
            remarks = 1,
            reviewSession = "s1",
            reviewLabel = "a label",
            rejected = false,
        ).render()
        writePublished(root, header + "\n" + "a note about A", dir)
        answerWaitingReview(project, "s1", listOf(remark.id!!))
        val publishedContent = Files.readString(dir.resolve(publishedName(root.toString())))

        rejectWaitingReview(project, dir)

        assertEquals(publishedContent, Files.readString(dir.resolve(publishedName(root.toString()))))
        assertNull(WaitingReviewService.getInstance(project).current())
    }

    /** An ack leaves the remarks READ, not PUBLISHED: only a real `read` acknowledgement over
     *  POST /api/claude-remarks/ack may reach READ, per CLAUDE.md's guard on markRemarksRead. */
    fun testAReadAcknowledgementAfterASendMarksTheRemarksRead() {
        WaitingReviewService.getInstance(project).start("s1", "a label", 1800)
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
        WaitingReviewService.getInstance(project).start("s1", "a label", 1800)
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
        val started = System.currentTimeMillis()
        WaitingReviewService.getInstance(project).start("s1", "a label", 1800)
        val remark = addRemark(project, "A.kt", LINES, 0..0, "a note", null)
        answerWaitingReview(project, "s1", listOf(remark.id!!))

        expireStaleReview(project, now = started + 1_800_001L)
        settleInvocationQueue()

        assertEquals(RemarkStatus.PENDING, statusOf(remark.id!!))
        assertNull(WaitingReviewService.getInstance(project).current())
    }

    /** Pointed at a directory that cannot be created: the write throws, and the review is still cleared. */
    fun testAFailedRejectionWriteStillClearsTheReview() {
        // The parent of badDir is a regular file, so Files.createDirectories throws when the write
        // tries to create badDir itself.
        val badDir = temp.file("reject-review-blocked", ".txt").resolve("subdir")
        identity() // forces the project root to resolve, so the failure below is genuinely badDir's
        WaitingReviewService.getInstance(project).start("s1", "a label", 1800)
        val remark = addRemark(project, "A.kt", LINES, 0..0, "a note", null)

        rejectWaitingReview(project, badDir)

        assertNull(WaitingReviewService.getInstance(project).current())
        assertEquals(RemarkStatus.PENDING, statusOf(remark.id!!))
    }

    private fun statusOf(id: String) = RemarkStore.getInstance(project).all().single { it.id == id }.status

    /**
     * [projectIdentity] for the fixture project, guaranteed non-null. `projectIdentity` answers null
     * for a base path that does not exist on disk yet, which the light fixture project does not
     * guarantee between test methods, so this creates it first.
     */
    private fun identity(): Path {
        Files.createDirectories(Path.of(project.basePath!!))
        return projectIdentity(project)!!
    }

    private companion object {
        val LINES = listOf("alpha", "beta")
    }
}
