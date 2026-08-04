package dev.sasha.clauderemarks.review

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.ui.UIUtil
import dev.sasha.clauderemarks.model.RemarkStatus
import dev.sasha.clauderemarks.store.RemarkStore
import dev.sasha.clauderemarks.store.addRemark
import dev.sasha.clauderemarks.store.markRemarksPublished

/**
 * PublishedBatchService's transitions, fixture-backed because a project-level @Service needs a
 * project. UIUtil.dispatchAllInvocationEvents() drains reportPublishedRead's invokeLater after every
 * acknowledgement that answers OK, the same reason SendReviewTest calls settleInvocationQueue()
 * after every send.
 */
class PublishedAckTest : BasePlatformTestCase() {

    override fun setUp() {
        super.setUp()
        // The light fixture project is shared across test methods and test classes.
        RemarkStore.getInstance(project).clear()
        PublishedBatchService.getInstance(project).clear()
    }

    override fun tearDown() {
        RemarkStore.getInstance(project).clear()
        PublishedBatchService.getInstance(project).clear()
        UIUtil.dispatchAllInvocationEvents()
        super.tearDown()
    }

    fun testAnAcknowledgementOfARecordedBatchAnswersOkAndMarksItsRemarksRead() {
        val a = addRemark(project, "A.kt", LINES, 0..0, "a note", null)
        val b = addRemark(project, "B.kt", LINES, 0..0, "another note", null)
        PublishedBatchService.getInstance(project).record("n1", listOf(a.id!!, b.id!!))

        val answer = reportPublishedRead(project, "n1", "s1")
        UIUtil.dispatchAllInvocationEvents()

        assertEquals(PublishedAckOutcome.OK, answer.outcome)
        assertEquals(2, answer.remarks)
        assertEquals(RemarkStatus.READ, statusOf(a.id!!))
        assertEquals(RemarkStatus.READ, statusOf(b.id!!))
    }

    fun testASecondSessionAcknowledgingTheSameBatchIsToldWhoWasFirst() {
        val a = addRemark(project, "A.kt", LINES, 0..0, "a note", null)
        PublishedBatchService.getInstance(project).record("n1", listOf(a.id!!))
        reportPublishedRead(project, "n1", "s1")
        UIUtil.dispatchAllInvocationEvents()

        val answer = reportPublishedRead(project, "n1", "s2")

        assertEquals(PublishedAckOutcome.ALREADY_READ, answer.outcome)
        assertEquals("s1", answer.readBy)
    }

    fun testTheSameSessionAcknowledgingTwiceIsToldItWasItself() {
        val a = addRemark(project, "A.kt", LINES, 0..0, "a note", null)
        PublishedBatchService.getInstance(project).record("n1", listOf(a.id!!))
        reportPublishedRead(project, "n1", "s1")
        UIUtil.dispatchAllInvocationEvents()

        val answer = reportPublishedRead(project, "n1", "s1")

        assertEquals(PublishedAckOutcome.ALREADY_READ, answer.outcome)
        assertEquals("s1", answer.readBy)
    }

    fun testANonceNothingRecordedAnswersUnknownBatchAndMarksNothing() {
        val a = addRemark(project, "A.kt", LINES, 0..0, "a note", null)

        val answer = reportPublishedRead(project, "does-not-exist", "s1")
        UIUtil.dispatchAllInvocationEvents()

        assertEquals(PublishedAckOutcome.UNKNOWN_BATCH, answer.outcome)
        assertEquals(RemarkStatus.PENDING, statusOf(a.id!!))
    }

    fun testOnlyTheLastSixteenBatchesAreRemembered() {
        val service = PublishedBatchService.getInstance(project)
        for (i in 0 until 17) service.record("n$i", emptyList())

        val first = reportPublishedRead(project, "n0", "s1")
        val second = reportPublishedRead(project, "n1", "s1")

        assertEquals(PublishedAckOutcome.UNKNOWN_BATCH, first.outcome)
        assertEquals(PublishedAckOutcome.OK, second.outcome)
    }

    fun testAnAcknowledgementMarksOnlyItsOwnBatch() {
        val a = addRemark(project, "A.kt", LINES, 0..0, "an older note", null)
        val b = addRemark(project, "B.kt", LINES, 0..0, "a newer note", null)
        markRemarksPublished(project, listOf(a.id!!, b.id!!))
        PublishedBatchService.getInstance(project).record("n1", listOf(a.id!!))
        PublishedBatchService.getInstance(project).record("n2", listOf(b.id!!))

        reportPublishedRead(project, "n1", "s1")
        UIUtil.dispatchAllInvocationEvents()

        assertEquals(RemarkStatus.READ, statusOf(a.id!!))
        assertEquals(RemarkStatus.PUBLISHED, statusOf(b.id!!))
    }

    private fun statusOf(id: String) = RemarkStore.getInstance(project).all().single { it.id == id }.status

    private companion object {
        val LINES = listOf("alpha", "beta")
    }
}
