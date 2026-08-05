package dev.sasha.clauderemarks.review

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.ui.UIUtil
import dev.sasha.clauderemarks.anchor.hashLines
import dev.sasha.clauderemarks.model.AnswerState
import dev.sasha.clauderemarks.model.RemarkStatus
import dev.sasha.clauderemarks.store.RemarkStore
import dev.sasha.clauderemarks.store.addGeneralRemark
import dev.sasha.clauderemarks.store.addRemark
import dev.sasha.clauderemarks.store.deleteRemark
import dev.sasha.clauderemarks.store.fileUnderProjectRoot
import dev.sasha.clauderemarks.store.remark
import dev.sasha.clauderemarks.store.settleInvocationQueue

/**
 * What the `answer` action causes, on the other side of the endpoint.
 *
 * Fixture-backed because everything here goes through project-level services and a real `Document`.
 * [settleInvocationQueue] rather than one `dispatchAllInvocationEvents`, because `reportAnswer`
 * queues a `ReadAction.nonBlocking` on a pooled thread and only its `finishOnUiThread` half lands on
 * the EDT — a single drain usually runs before the pooled half has even started.
 */
class AnswerReceiptTest : BasePlatformTestCase() {

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

    /**
     * The ordinary case, and the one thing it must not do. `READ` means an agent said it read the
     * remarks, and answering one of them is not saying that — the `published-read` acknowledgement
     * stays the only thing on this route that can move a remark's status.
     */
    fun testAnsweringStoresTheAnswerAndMarksNothingRead() {
        val remark = addRemark(project, "A.kt", LINES, 0..0, "why is this synchronized?")
        val nonce = record(remark.id!!)

        val outcome = reportAnswer(project, nonce, remark.id!!, "because two threads write it")
        settleInvocationQueue()

        assertEquals(AnswerOutcome.OK, outcome)
        assertEquals("because two threads write it", answers().single().markdown)
        assertEquals(RemarkStatus.PENDING, statusOf(remark.id!!))
    }

    /**
     * Answering must not consume the batch. A session that answers its questions and only then
     * acknowledges the batch it read is an ordinary order, not an anomaly, and several marked
     * remarks in one batch each get their own answer against the same nonce.
     */
    fun testAnsweringDoesNotConsumeTheBatch() {
        val remark = addRemark(project, "A.kt", LINES, 0..0, "why is this synchronized?")
        val nonce = record(remark.id!!)

        reportAnswer(project, nonce, remark.id!!, "one")
        reportAnswer(project, nonce, remark.id!!, "two")
        settleInvocationQueue()
        val acknowledgement = reportPublishedRead(project, nonce, "s1")
        UIUtil.dispatchAllInvocationEvents()

        assertEquals(PublishedAckOutcome.OK, acknowledgement.outcome)
        assertEquals(RemarkStatus.READ, statusOf(remark.id!!))
    }

    /**
     * The ordinary case for a question asked through Ask Claude: nothing ever started a review, and
     * the answer still lands. The action is keyed to a batch nonce, exactly the way `published-read`
     * is, and it never touches the review service at all.
     */
    fun testAnAnswerWorksWithNoReviewEverStarted() {
        val remark = addRemark(project, "A.kt", LINES, 0..0, "why is this synchronized?")
        val nonce = record(remark.id!!)

        val outcome = reportAnswer(project, nonce, remark.id!!, "because two threads write it")
        settleInvocationQueue()

        assertEquals(AnswerOutcome.OK, outcome)
        assertEquals(1, answers().size)
        assertNull(WaitingReviewService.getInstance(project).current())
    }

    /**
     * The person cleared the question in the seconds between publishing it and the answer arriving.
     * The answer is the thing worth keeping, so it is stored with no question and no anchor rather
     * than dropped — losing it here would be exactly the silent loss this plugin refuses everywhere
     * else.
     */
    fun testAnAnswerForARemarkDeletedInBetweenIsStoredWithNoAnchor() {
        val remark = addRemark(project, "A.kt", LINES, 0..0, "why is this synchronized?")
        val nonce = record(remark.id!!)
        deleteRemark(project, remark.id!!)

        val outcome = reportAnswer(project, nonce, remark.id!!, "because two threads write it")
        settleInvocationQueue()

        assertEquals(AnswerOutcome.OK, outcome)
        val stored = answers().single()
        assertEquals("because two threads write it", stored.markdown)
        assertEquals(remark.id, stored.remarkId)
        assertTrue(stored.path, stored.path.isNullOrEmpty())
        assertTrue(stored.question, stored.question.isNullOrEmpty())
        assertTrue(stored.textHash, stored.textHash.isNullOrEmpty())
    }

    /** A nonce this plugin never recorded, or one that fell off the remembered sixteen. */
    fun testAnUnknownNonceIsRefusedAndStoresNothing() {
        val remark = addRemark(project, "A.kt", LINES, 0..0, "why is this synchronized?")

        val outcome = reportAnswer(project, "does-not-exist", remark.id!!, "an answer")
        settleInvocationQueue()

        assertEquals(AnswerOutcome.UNKNOWN_BATCH, outcome)
        assertTrue(answers().toString(), answers().isEmpty())
    }

    /**
     * A real batch that never carried this remark: a session answering a question it invented, or
     * one from an older batch. Without the membership check a caller could write about any remark
     * whose id it could guess.
     */
    fun testARemarkTheBatchNeverCarriedIsRefusedAndStoresNothing() {
        val shown = addRemark(project, "A.kt", LINES, 0..0, "why is this synchronized?")
        val hidden = addRemark(project, "B.kt", LINES, 0..0, "and this one?")
        val nonce = record(shown.id!!)

        val outcome = reportAnswer(project, nonce, hidden.id!!, "an answer")
        settleInvocationQueue()

        assertEquals(AnswerOutcome.UNKNOWN_REMARK, outcome)
        assertTrue(answers().toString(), answers().isEmpty())
    }

    /**
     * A question answered twice ends with one answer carrying the second body. `reportAnswer` goes
     * through `recordAnswer`, which goes through the store's upsert, so replacement happens in one
     * place and this is what proves the endpoint route really reaches it.
     */
    fun testASecondAnswerForTheSameRemarkReplacesTheFirst() {
        val remark = addRemark(project, "A.kt", LINES, 0..0, "why is this synchronized?")
        val nonce = record(remark.id!!)

        reportAnswer(project, nonce, remark.id!!, "the first body")
        settleInvocationQueue()
        reportAnswer(project, nonce, remark.id!!, "the second body")
        settleInvocationQueue()

        assertEquals("the second body", answers().single().markdown)
    }

    /**
     * The answer copies the remark's text as its own question, so an answer whose remark is later
     * cleared still reads as a reply to something rather than as a paragraph with no subject.
     */
    fun testTheAnswerCarriesTheRemarksTextAsItsQuestion() {
        val remark = addRemark(project, "A.kt", LINES, 0..0, "why is this synchronized?")
        val nonce = record(remark.id!!)

        reportAnswer(project, nonce, remark.id!!, "because two threads write it")
        settleInvocationQueue()

        assertEquals("why is this synchronized?", answers().single().question)
    }

    /**
     * The anchor is captured fresh at the position the remark resolves to *now*, not copied field
     * for field off the remark. The remark below is stored pointing at line 0 while its text really
     * sits on line 2, so a verbatim copy would give the answer `startLine = 0` and a fresh capture
     * gives it 2. An answer usually outlives its remark, so it needs the whole search radius ahead
     * of it rather than whatever the remark has already spent.
     */
    fun testTheAnswerCapturesAFreshAnchorAtTheResolvedPosition() {
        fileUnderProjectRoot(project, "receipt/Moved.kt", "alpha\nbeta\ngamma\n")
        val stale = remark(
            id = "r-moved",
            path = "receipt/Moved.kt",
            startLine = 0,
            endLine = 0,
            text = "what is gamma?",
            textHash = hashLines(listOf("gamma")),
        )
        RemarkStore.getInstance(project).add(stale)
        val nonce = record("r-moved")

        reportAnswer(project, nonce, "r-moved", "the third line")
        settleInvocationQueue()

        val stored = answers().single()
        assertEquals("receipt/Moved.kt", stored.path)
        assertEquals(2, stored.startLine)
        assertEquals(2, stored.endLine)
        assertEquals(hashLines(listOf("gamma")), stored.textHash)
    }

    /**
     * A general remark has no file at all, so there is nothing fresh to capture and the stored
     * fields are copied as they are. Its answer therefore carries an empty path and resolves as
     * itself, exactly the way `isAboutNoFile` already handles the remark.
     */
    fun testAnAnswerToAGeneralRemarkCarriesNoPath() {
        val remark = addGeneralRemark(project, "is the whole change worth splitting?")
        val nonce = record(remark.id!!)

        reportAnswer(project, nonce, remark.id!!, "no, it is one change")
        settleInvocationQueue()

        val stored = answers().single()
        assertTrue(stored.path, stored.path.isNullOrEmpty())
        assertEquals("is the whole change worth splitting?", stored.question)
    }

    /**
     * A remark whose code is gone has no resolved position worth capturing, so its stored fields are
     * copied as they are and the answer starts out orphaned at the same place the remark is. Dropping
     * the answer, or storing it with no position at all, would both lose where the question was
     * about.
     */
    fun testAnAnswerForAnOrphanedRemarkCopiesTheStoredPosition() {
        fileUnderProjectRoot(project, "receipt/Gone.kt", "something else entirely\n")
        val orphaned = remark(
            id = "r-gone",
            path = "receipt/Gone.kt",
            startLine = 7,
            endLine = 7,
            text = "what happened here?",
            textHash = hashLines(listOf("a line that is not there")),
        )
        RemarkStore.getInstance(project).add(orphaned)
        val nonce = record("r-gone")

        reportAnswer(project, nonce, "r-gone", "it was deleted")
        settleInvocationQueue()

        val stored = answers().single()
        assertEquals("receipt/Gone.kt", stored.path)
        assertEquals(7, stored.startLine)
        assertEquals(7, stored.endLine)
    }

    private fun record(vararg ids: String) =
        PublishedBatchService.getInstance(project).record(ids.toList())

    private fun answers(): List<AnswerState> = RemarkStore.getInstance(project).allAnswers()

    private fun statusOf(id: String) = RemarkStore.getInstance(project).all().single { it.id == id }.status

    private companion object {
        val LINES = listOf("alpha", "beta")
    }
}
