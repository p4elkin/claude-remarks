package dev.sasha.clauderemarks.review

import com.intellij.testFramework.fixtures.BasePlatformTestCase
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

        service.markSent(listOf("a", "b"))

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
        service.markSent(listOf("a", "b"))

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

    fun testExpireIfStaleRemovesAReviewPastItsDeadlineAndNothingElse() {
        val service = WaitingReviewService.getInstance(project)
        val outputPath = Files.createTempDirectory("waiting-review-service-test")
        service.start("s1", "a label", 0L, outputPath)

        assertNotNull(service.expireIfStale())

        val otherPath = Files.createTempDirectory("waiting-review-service-test")
        service.start("s2", "another label", 1800L, otherPath)

        assertNull(service.expireIfStale())
        assertNotNull(service.current())
    }
}
