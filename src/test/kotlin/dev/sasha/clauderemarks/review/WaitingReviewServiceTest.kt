package dev.sasha.clauderemarks.review

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.ui.UIUtil

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
        val service = startedReview(deadlineSeconds = 0L)

        assertNull(service.current())
    }

    fun testMarkingSentMovesThePhaseAndKeepsTheReview() {
        val service = startedReview()

        assertTrue(service.markSent("s1", listOf("a", "b")))

        val current = service.current()
        assertNotNull(current)
        val phase = current!!.phase
        assertTrue(phase is ReviewPhase.Sent)
        assertEquals(listOf("a", "b"), (phase as ReviewPhase.Sent).ids)
    }

    fun testAReadAcknowledgementOnASentReviewClearsItAndReportsOk() {
        val service = startedReview()
        service.markSent("s1", listOf("a", "b"))

        val (outcome, state) = service.acknowledge("s1", ReviewEnd.READ)

        assertEquals(AckOutcome.OK, outcome)
        assertEquals(listOf("a", "b"), (state!!.phase as ReviewPhase.Sent).ids)
        assertNull(service.current())
    }

    fun testAReadAcknowledgementOnAWaitingReviewChangesNothing() {
        val service = startedReview()

        val (outcome, state) = service.acknowledge("s1", ReviewEnd.READ)

        assertEquals(AckOutcome.NOT_SENT, outcome)
        assertNull(state)
        assertNotNull(service.current())
    }

    fun testAnAcknowledgementForAnotherSessionChangesNothing() {
        val service = startedReview()

        val (outcome, state) = service.acknowledge("s2", ReviewEnd.READ)

        assertEquals(AckOutcome.NO_REVIEW, outcome)
        assertNull(state)
        assertNotNull(service.current())
    }

    fun testAnAbandonedAcknowledgementClearsAWaitingReview() {
        val service = startedReview()

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
        val service = startedReview()

        // What a publish whose review ended mid-render would otherwise do to the review that
        // replaced it.
        assertFalse(service.markSent("s0", listOf("a", "b")))

        assertEquals(ReviewPhase.Waiting, service.current()!!.phase)
    }

    /**
     * The window the publish cannot close: it writes the published file outside the service's lock,
     * so the deadline task can end the review before the stamp lands. `false` is what tells the
     * publish to report that instead of claiming the remarks were handed over.
     */
    fun testMarkingSentAfterTheReviewEndedReportsThatNothingWasStamped() {
        val service = startedReview()
        service.clear("s1")

        assertFalse(service.markSent("s1", listOf("a", "b")))
        assertNull(service.current())
    }

    /**
     * A named instant rather than a zero deadline: `start(… 0L …)` also schedules an expiry with a
     * delay of zero, and that pool thread then races this test's own call.
     */
    fun testExpireIfStaleRemovesAReviewPastItsDeadline() {
        val started = System.currentTimeMillis()
        val service = startedReview()

        assertNotNull(service.expireIfStale(now = started + 1_800_001L))
        assertNull(service.current())
    }

    fun testExpireIfStaleLeavesALiveReviewAlone() {
        val service = startedReview()

        assertNull(service.expireIfStale())
        assertNotNull(service.current())
    }

    /**
     * Reject leaves nothing behind on the app-wide scheduled pool. Without this, the deadline task
     * stays queued for up to 24 hours holding the project, while the field's own comment claims it
     * was cancelled.
     */
    fun testClearingCancelsTheDeadlineTask() {
        val service = startedReview()
        assertTrue(service.expiryIsLive())

        service.clear()

        assertFalse(service.expiryIsLive())
    }

    fun testClearingNamesTheReviewItMeans() {
        val service = startedReview()

        service.clear("s0")

        assertNotNull(service.current())
    }

    /**
     * The three lines almost every test above opened with. Session "s1" and the label are the same
     * everywhere, because no test here asserts on either; what varies is the deadline, and the two
     * tests that need a named instant take their own clock reading before calling this.
     */
    private fun startedReview(deadlineSeconds: Long = 1800L): WaitingReviewService =
        WaitingReviewService.getInstance(project).also {
            it.start("s1", "a label", deadlineSeconds)
        }
}
