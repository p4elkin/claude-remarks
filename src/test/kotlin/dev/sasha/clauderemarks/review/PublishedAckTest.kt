package dev.sasha.clauderemarks.review

import com.intellij.notification.Notification
import com.intellij.notification.Notifications
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.ui.UIUtil
import dev.sasha.clauderemarks.model.RemarkStatus
import dev.sasha.clauderemarks.store.RemarkStore
import dev.sasha.clauderemarks.store.addRemark
import dev.sasha.clauderemarks.store.markRemarksPublished
import org.junit.Assert.assertNotEquals

/**
 * PublishedBatchService's transitions, fixture-backed because a project-level @Service needs a
 * project. UIUtil.dispatchAllInvocationEvents() drains reportPublishedRead's invokeLater after every
 * acknowledgement that answers OK, the same reason ReviewLifecycleTest calls settleInvocationQueue()
 * after every acknowledgement.
 */
class PublishedAckTest : BasePlatformTestCase() {

    override fun setUp() {
        super.setUp()
        // The light fixture project is shared across test methods and test classes.
        RemarkStore.getInstance(project).clear()
        PublishedBatchService.getInstance(project).clear()
        WaitingReviewService.getInstance(project).clear()
    }

    override fun tearDown() {
        RemarkStore.getInstance(project).clear()
        PublishedBatchService.getInstance(project).clear()
        WaitingReviewService.getInstance(project).clear()
        UIUtil.dispatchAllInvocationEvents()
        super.tearDown()
    }

    fun testAnAcknowledgementOfARecordedBatchAnswersOkAndMarksItsRemarksRead() {
        val a = addRemark(project, "A.kt", LINES, 0..0, "a note")
        val b = addRemark(project, "B.kt", LINES, 0..0, "another note")
        val nonce = PublishedBatchService.getInstance(project).record(listOf(a.id!!, b.id!!))

        val answer = reportPublishedRead(project, nonce, "s1")
        UIUtil.dispatchAllInvocationEvents()

        assertEquals(PublishedAckOutcome.OK, answer.outcome)
        assertEquals(2, answer.remarks)
        assertEquals(RemarkStatus.READ, statusOf(a.id!!))
        assertEquals(RemarkStatus.READ, statusOf(b.id!!))
    }

    fun testASecondSessionAcknowledgingTheSameBatchIsToldWhoWasFirst() {
        val a = addRemark(project, "A.kt", LINES, 0..0, "a note")
        val nonce = PublishedBatchService.getInstance(project).record(listOf(a.id!!))
        reportPublishedRead(project, nonce, "s1")
        UIUtil.dispatchAllInvocationEvents()

        val answer = reportPublishedRead(project, nonce, "s2")

        assertEquals(PublishedAckOutcome.ALREADY_READ, answer.outcome)
        assertEquals("s1", answer.readBy)
    }

    fun testTheSameSessionAcknowledgingTwiceIsToldItWasItself() {
        val a = addRemark(project, "A.kt", LINES, 0..0, "a note")
        val nonce = PublishedBatchService.getInstance(project).record(listOf(a.id!!))
        reportPublishedRead(project, nonce, "s1")
        UIUtil.dispatchAllInvocationEvents()

        val answer = reportPublishedRead(project, nonce, "s1")

        assertEquals(PublishedAckOutcome.ALREADY_READ, answer.outcome)
        assertEquals("s1", answer.readBy)
    }

    fun testANonceNothingRecordedAnswersUnknownBatchAndMarksNothing() {
        val a = addRemark(project, "A.kt", LINES, 0..0, "a note")

        val answer = reportPublishedRead(project, "does-not-exist", "s1")
        UIUtil.dispatchAllInvocationEvents()

        assertEquals(PublishedAckOutcome.UNKNOWN_BATCH, answer.outcome)
        assertEquals(RemarkStatus.PENDING, statusOf(a.id!!))
    }

    fun testOnlyTheLastSixteenBatchesAreRemembered() {
        val service = PublishedBatchService.getInstance(project)
        val nonces = (0 until 17).map { service.record(emptyList()) }

        val first = reportPublishedRead(project, nonces.first(), "s1")
        val second = reportPublishedRead(project, nonces[1], "s1")

        assertEquals(PublishedAckOutcome.UNKNOWN_BATCH, first.outcome)
        assertEquals(PublishedAckOutcome.OK, second.outcome)
    }

    fun testAnAcknowledgementMarksOnlyItsOwnBatch() {
        val a = addRemark(project, "A.kt", LINES, 0..0, "an older note")
        val b = addRemark(project, "B.kt", LINES, 0..0, "a newer note")
        markRemarksPublished(project, listOf(a.id!!, b.id!!))
        val first = PublishedBatchService.getInstance(project).record(listOf(a.id!!))
        PublishedBatchService.getInstance(project).record(listOf(b.id!!))

        reportPublishedRead(project, first, "s1")
        UIUtil.dispatchAllInvocationEvents()

        assertEquals(RemarkStatus.READ, statusOf(a.id!!))
        assertEquals(RemarkStatus.PUBLISHED, statusOf(b.id!!))
    }

    /**
     * Every batch gets its own nonce from `record`, so two batches recorded in a row can never be
     * acknowledged by naming the same one. The nonce used to be minted by each caller and passed in,
     * which is what made "record it before you write it" a rule a third caller could break.
     */
    fun testEachRecordedBatchGetsItsOwnNonce() {
        val service = PublishedBatchService.getInstance(project)

        assertNotEquals(service.record(emptyList()), service.record(emptyList()))
    }

    /**
     * The other half of recording before the write: a publish whose write then failed drops its
     * batch again. Without this the failed batch would sit in the remembered sixteen for good, and
     * enough failed publishes would push a real, readable batch out into unknown-batch.
     */
    fun testAForgottenBatchIsUnknownAgain() {
        val a = addRemark(project, "A.kt", LINES, 0..0, "a note")
        val service = PublishedBatchService.getInstance(project)
        val nonce = service.record(listOf(a.id!!))

        service.forget(nonce)
        val answer = reportPublishedRead(project, nonce, "s1")
        UIUtil.dispatchAllInvocationEvents()

        assertEquals(PublishedAckOutcome.UNKNOWN_BATCH, answer.outcome)
        assertEquals(RemarkStatus.PENDING, statusOf(a.id!!))
    }

    /**
     * A batch that answered a waiting review ends that review too. Without it the remarks would be
     * READ while the review stayed in its Sent phase, and the review's own expiry would then tell the
     * person the agent left without reading remarks the store already says were read.
     */
    fun testAcknowledgingABatchThatAnsweredAReviewEndsThatReviewToo() {
        val a = addRemark(project, "A.kt", LINES, 0..0, "a note")
        WaitingReviewService.getInstance(project).start("review-1", "a label", 1800)
        val nonce = PublishedBatchService.getInstance(project).record(listOf(a.id!!), "review-1")
        WaitingReviewService.getInstance(project).markSent("review-1", listOf(a.id!!))

        val answer = reportPublishedRead(project, nonce, "s1")
        UIUtil.dispatchAllInvocationEvents()

        assertEquals(PublishedAckOutcome.OK, answer.outcome)
        assertEquals(RemarkStatus.READ, statusOf(a.id!!))
        assertNull(WaitingReviewService.getInstance(project).current())
    }

    /**
     * The same thing one step earlier in a publish: the acknowledgement lands after the file was
     * written, while the publish has not yet stamped the review `Sent`. Ending the review from the
     * calling thread answered NOT_SENT there and left the review alive to expire later. Queued on
     * the EDT instead, it runs behind the publish that is still finishing, so it sees the `Sent`
     * phase and ends the review. markSent standing between the acknowledgement and the drained
     * queue is what puts the test inside that window.
     */
    fun testAnAcknowledgementLandingBeforeTheReviewIsStampedSentStillEndsIt() {
        val a = addRemark(project, "A.kt", LINES, 0..0, "a note")
        WaitingReviewService.getInstance(project).start("review-1", "a label", 1800)
        val nonce = PublishedBatchService.getInstance(project).record(listOf(a.id!!), "review-1")

        val answer = reportPublishedRead(project, nonce, "s1")
        WaitingReviewService.getInstance(project).markSent("review-1", listOf(a.id!!))
        UIUtil.dispatchAllInvocationEvents()

        assertEquals(PublishedAckOutcome.OK, answer.outcome)
        assertEquals(RemarkStatus.READ, statusOf(a.id!!))
        assertNull(WaitingReviewService.getInstance(project).current())
    }

    /**
     * A publish with no review waiting leaves any later review alone: the batch carries no session,
     * so there is nothing for it to end.
     */
    fun testAcknowledgingABatchWithNoReviewLeavesALiveReviewAlone() {
        val a = addRemark(project, "A.kt", LINES, 0..0, "a note")
        val nonce = PublishedBatchService.getInstance(project).record(listOf(a.id!!))
        WaitingReviewService.getInstance(project).start("review-1", "a label", 1800)

        reportPublishedRead(project, nonce, "s1")
        UIUtil.dispatchAllInvocationEvents()

        assertNotNull(WaitingReviewService.getInstance(project).current())
    }

    /**
     * A rejection is recorded as a batch with no ids, so acknowledging one must not show a balloon
     * saying Claude Code read zero remarks. The answer is still ok: the batch is real and this
     * session is the first to name it.
     *
     * Both halves in one test on purpose. The ordinary batch afterwards is what proves the listener
     * really does see this plugin's balloons, so the empty case's assertion cannot pass by seeing
     * nothing at all.
     */
    fun testABatchWithNoRemarksShowsNoBalloonWhileAnOrdinaryOneDoes() {
        val shown = mutableListOf<String>()
        project.messageBus.connect(testRootDisposable).subscribe(
            Notifications.TOPIC,
            object : Notifications {
                override fun notify(notification: Notification) {
                    shown += notification.content
                }
            },
        )
        val a = addRemark(project, "A.kt", LINES, 0..0, "a note")
        val service = PublishedBatchService.getInstance(project)
        val empty = service.record(emptyList(), "review-1")
        val ordinary = service.record(listOf(a.id!!))

        val answer = reportPublishedRead(project, empty, "s1")
        UIUtil.dispatchAllInvocationEvents()

        assertEquals(PublishedAckOutcome.OK, answer.outcome)
        assertEquals(0, answer.remarks)
        assertEquals(shown.toString(), 0, shown.size)

        reportPublishedRead(project, ordinary, "s2")
        UIUtil.dispatchAllInvocationEvents()

        assertEquals(shown.toString(), 1, shown.size)
    }

    /**
     * The lookup the `answer` action does before it stores anything: the nonce names a remembered
     * batch, and that batch carried the remark being answered.
     */
    fun testALookupOfARemarkItsBatchCarriesAnswersOk() {
        val a = addRemark(project, "A.kt", LINES, 0..0, "a note")
        val b = addRemark(project, "B.kt", LINES, 0..0, "another note")
        val service = PublishedBatchService.getInstance(project)
        val nonce = service.record(listOf(a.id!!, b.id!!))

        assertEquals(BatchLookup.OK, service.batchCarries(nonce, a.id!!))
        assertEquals(BatchLookup.OK, service.batchCarries(nonce, b.id!!))
    }

    /**
     * A real batch that never carried this id: a session answering a question it invented, or one
     * from an older batch. That is a different refusal from an unknown nonce, and the endpoint says
     * so with a different status.
     */
    fun testALookupOfARemarkItsBatchDoesNotCarryAnswersUnknownRemark() {
        val a = addRemark(project, "A.kt", LINES, 0..0, "a note")
        val b = addRemark(project, "B.kt", LINES, 0..0, "another note")
        val service = PublishedBatchService.getInstance(project)
        val nonce = service.record(listOf(a.id!!))

        assertEquals(BatchLookup.UNKNOWN_REMARK, service.batchCarries(nonce, b.id!!))
    }

    fun testALookupAgainstANonceNothingRecordedAnswersUnknownBatch() {
        val a = addRemark(project, "A.kt", LINES, 0..0, "a note")
        val service = PublishedBatchService.getInstance(project)

        assertEquals(BatchLookup.UNKNOWN_BATCH, service.batchCarries("does-not-exist", a.id!!))
    }

    /**
     * A batch that fell off the remembered sixteen answers UNKNOWN_BATCH too, the same way
     * `acknowledge` does — the two are the same two cases behind one word.
     */
    fun testALookupAgainstABatchPushedOutOfTheRememberedSixteenAnswersUnknownBatch() {
        val a = addRemark(project, "A.kt", LINES, 0..0, "a note")
        val service = PublishedBatchService.getInstance(project)
        val nonces = (0 until 17).map { service.record(listOf(a.id!!)) }

        assertEquals(BatchLookup.UNKNOWN_BATCH, service.batchCarries(nonces.first(), a.id!!))
        assertEquals(BatchLookup.OK, service.batchCarries(nonces[1], a.id!!))
    }

    /**
     * A lookup must not consume the batch. Several marked remarks in one batch each get their own
     * answer, so the same nonce is looked up once per answer, and the batch still has to be
     * acknowledgeable through published-read afterwards. If batchCarries ever stamped readBy the way
     * acknowledge does, the acknowledgement below would come back ALREADY_READ instead of OK.
     */
    fun testALookupNeverConsumesTheBatch() {
        val a = addRemark(project, "A.kt", LINES, 0..0, "a note")
        val service = PublishedBatchService.getInstance(project)
        val nonce = service.record(listOf(a.id!!))

        repeat(3) { service.batchCarries(nonce, a.id!!) }
        service.batchCarries(nonce, "not-in-this-batch")
        val answer = reportPublishedRead(project, nonce, "s1")
        UIUtil.dispatchAllInvocationEvents()

        assertEquals(PublishedAckOutcome.OK, answer.outcome)
        assertEquals(1, answer.remarks)
        assertEquals(RemarkStatus.READ, statusOf(a.id!!))
    }

    /**
     * The other direction of the same rule: an acknowledgement does not hide the batch from a later
     * lookup. An agent that acknowledges the batch it read and only then answers its questions is
     * the ordinary order, not an anomaly.
     */
    fun testAnAcknowledgedBatchIsStillFoundByALookup() {
        val a = addRemark(project, "A.kt", LINES, 0..0, "a note")
        val service = PublishedBatchService.getInstance(project)
        val nonce = service.record(listOf(a.id!!))

        reportPublishedRead(project, nonce, "s1")
        UIUtil.dispatchAllInvocationEvents()

        assertEquals(BatchLookup.OK, service.batchCarries(nonce, a.id!!))
    }

    private fun statusOf(id: String) = RemarkStore.getInstance(project).all().single { it.id == id }.status

    private companion object {
        val LINES = listOf("alpha", "beta")
    }
}
