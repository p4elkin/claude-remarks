package dev.sasha.clauderemarks.review

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.ui.UIUtil
import java.nio.file.Files

/**
 * The service's transitions: current() masking a stale review, markSent, acknowledge, and
 * expireIfStale. Fixture-backed because a project-level @Service needs a project; startOrConflict
 * itself is covered without one, in WaitingReviewTest.
 */
class WaitingReviewServiceTest : BasePlatformTestCase() {

    override fun setUp() {
        super.setUp()
        // The light fixture project is shared across test classes.
        WaitingReviewService.getInstance(project).clear()
    }

    override fun tearDown() {
        WaitingReviewService.getInstance(project).clear()
        // Every transition queues a notifyPanel, and the stale path queues a balloon. Draining them
        // here keeps them out of whichever test class runs next in this shared fixture.
        UIUtil.dispatchAllInvocationEvents()
        super.tearDown()
    }

    fun testAReviewPastItsDeadlineIsNotCurrent() {
        val service = WaitingReviewService.getInstance(project)
        val outputPath = Files.createTempDirectory("waiting-review-service-test")

        service.start("s1", "a label", 0L, outputPath)

        assertNull(service.current())
    }

    fun testMarkingSentMovesThePhaseAndKeepsTheReview() {
        val service = WaitingReviewService.getInstance(project)
        val outputPath = Files.createTempDirectory("waiting-review-service-test")
        service.start("s1", "a label", 1800L, outputPath)

        service.markSent("s1", listOf("a", "b"))

        val current = service.current()
        assertNotNull(current)
        val phase = current!!.phase
        assertTrue(phase is ReviewPhase.Sent)
        assertEquals(listOf("a", "b"), (phase as ReviewPhase.Sent).ids)
    }

    fun testAReadAcknowledgementOnASentReviewClearsItAndReportsOk() {
        val service = WaitingReviewService.getInstance(project)
        val outputPath = Files.createTempDirectory("waiting-review-service-test")
        service.start("s1", "a label", 1800L, outputPath)
        service.markSent("s1", listOf("a", "b"))

        val (outcome, state) = service.acknowledge("s1", ReviewEnd.READ)

        assertEquals(AckOutcome.OK, outcome)
        assertEquals(listOf("a", "b"), (state!!.phase as ReviewPhase.Sent).ids)
        assertNull(service.current())
    }

    fun testAReadAcknowledgementOnAWaitingReviewChangesNothing() {
        val service = WaitingReviewService.getInstance(project)
        val outputPath = Files.createTempDirectory("waiting-review-service-test")
        service.start("s1", "a label", 1800L, outputPath)

        val (outcome, state) = service.acknowledge("s1", ReviewEnd.READ)

        assertEquals(AckOutcome.NOT_SENT, outcome)
        assertNull(state)
        assertNotNull(service.current())
    }

    fun testAnAcknowledgementForAnotherSessionChangesNothing() {
        val service = WaitingReviewService.getInstance(project)
        val outputPath = Files.createTempDirectory("waiting-review-service-test")
        service.start("s1", "a label", 1800L, outputPath)

        val (outcome, state) = service.acknowledge("s2", ReviewEnd.READ)

        assertEquals(AckOutcome.NO_REVIEW, outcome)
        assertNull(state)
        assertNotNull(service.current())
    }

    fun testAnAbandonedAcknowledgementClearsAWaitingReview() {
        val service = WaitingReviewService.getInstance(project)
        val outputPath = Files.createTempDirectory("waiting-review-service-test")
        service.start("s1", "a label", 1800L, outputPath)

        val (outcome, _) = service.acknowledge("s1", ReviewEnd.ABANDONED)

        assertEquals(AckOutcome.OK, outcome)
        assertNull(service.current())
    }

    fun testAnAcknowledgementWithNoReviewAtAllChangesNothing() {
        val service = WaitingReviewService.getInstance(project)

        val (outcome, state) = service.acknowledge("s1", ReviewEnd.READ)

        assertEquals(AckOutcome.NO_REVIEW, outcome)
        assertNull(state)
    }

    fun testMarkingSentForAnotherSessionChangesNothing() {
        val service = WaitingReviewService.getInstance(project)
        val outputPath = Files.createTempDirectory("waiting-review-service-test")
        service.start("s1", "a label", 1800L, outputPath)

        // What a send whose review ended mid-render would otherwise do to the review that replaced it.
        service.markSent("s0", listOf("a", "b"))

        assertEquals(ReviewPhase.Waiting, service.current()!!.phase)
    }

    /**
     * A named instant rather than a zero deadline: `start(… 0L …)` also schedules an expiry with a
     * delay of zero, and that pool thread then races this test's own call.
     */
    fun testExpireIfStaleRemovesAReviewPastItsDeadline() {
        val service = WaitingReviewService.getInstance(project)
        val outputPath = Files.createTempDirectory("waiting-review-service-test")
        val started = System.currentTimeMillis()
        service.start("s1", "a label", 1800L, outputPath)

        assertNotNull(service.expireIfStale(now = started + 1_800_001L))
        assertNull(service.current())
    }

    fun testExpireIfStaleLeavesALiveReviewAlone() {
        val service = WaitingReviewService.getInstance(project)
        val outputPath = Files.createTempDirectory("waiting-review-service-test")
        service.start("s1", "a label", 1800L, outputPath)

        assertNull(service.expireIfStale())
        assertNotNull(service.current())
    }

    /**
     * Reject leaves nothing behind on the app-wide scheduled pool. Without this, the deadline task
     * stays queued for up to 24 hours holding the project, while the field's own comment claims it
     * was cancelled.
     */
    fun testClearingCancelsTheDeadlineTask() {
        val service = WaitingReviewService.getInstance(project)
        val outputPath = Files.createTempDirectory("waiting-review-service-test")
        service.start("s1", "a label", 1800L, outputPath)
        assertTrue(service.expiryIsLive())

        service.clear()

        assertFalse(service.expiryIsLive())
    }

    fun testClearingNamesTheReviewItMeans() {
        val service = WaitingReviewService.getInstance(project)
        val outputPath = Files.createTempDirectory("waiting-review-service-test")
        service.start("s1", "a label", 1800L, outputPath)

        service.clear("s0")

        assertNotNull(service.current())
    }
}
