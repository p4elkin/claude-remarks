package dev.sasha.clauderemarks.review

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.ui.UIUtil
import dev.sasha.clauderemarks.model.RemarkStatus
import dev.sasha.clauderemarks.store.RemarkStore
import dev.sasha.clauderemarks.store.addRemark
import dev.sasha.clauderemarks.store.settleInvocationQueue
import io.netty.buffer.ByteBufHolder
import io.netty.buffer.Unpooled
import io.netty.channel.ChannelInboundHandlerAdapter
import io.netty.channel.embedded.EmbeddedChannel
import io.netty.handler.codec.http.DefaultFullHttpRequest
import io.netty.handler.codec.http.HttpMethod
import io.netty.handler.codec.http.HttpVersion
import io.netty.handler.codec.http.QueryStringDecoder
import java.nio.file.Files
import java.nio.file.Path

/**
 * The one test class that runs `execute`. The first test exists for a single defect nothing else
 * can see: the JSON writer wraps a buffering OutputStreamWriter, so a missing `close()` before
 * `send` produces a 200 with an empty body — the skill then finds no `status` field and hangs to
 * its own timeout.
 *
 * It calls `execute` directly, not `process`. `process` catches Throwable and calls `LOG.error`,
 * which throws in tests, and it touches a per-instance request counter. The trust rule that
 * `process` would exercise is already covered by `requestIsAllowed`'s five tests in
 * [ReviewRequestTest].
 */
class ReviewEndpointSmokeTest : BasePlatformTestCase() {

    override fun setUp() {
        super.setUp()
        // The light fixture project is shared across test classes.
        RemarkStore.getInstance(project).clear()
        WaitingReviewService.getInstance(project).clear()
        PublishedBatchService.getInstance(project).clear()
        deletePublishedFile()
    }

    override fun tearDown() {
        RemarkStore.getInstance(project).clear()
        WaitingReviewService.getInstance(project).clear()
        PublishedBatchService.getInstance(project).clear()
        deletePublishedFile()
        UIUtil.dispatchAllInvocationEvents()
        super.tearDown()
    }

    /**
     * The fetch tests below write straight to `handshakeDir()` — the real project-identity-keyed
     * published file, not one of `temp`'s own directories — because `handleFetch` resolves that path
     * itself and cannot be pointed at a test directory. `handshakeDir()`'s unit-test branch already
     * keeps that off the developer's real `~/.claude-remarks`, but the file it does write persists
     * across test methods and across gradle runs unless removed here.
     */
    private fun deletePublishedFile() {
        val root = projectIdentity(project) ?: return
        val file = handshakeDir().resolve(publishedName(root.toString()))
        // deleteIfExists, not delete: one test replaces this file with a directory, to make the read
        // itself fail, and an empty directory is removed by the same call.
        Files.deleteIfExists(file)
    }

    /**
     * The project path here is deliberately one nothing has open, so the answer is
     * `unknown-project`: no waiting review is started and no temp directory is created. The
     * fixture is needed even so — listing the open projects goes through
     * `ProjectManager.getInstance()`, which needs a running Application.
     */
    fun testExecuteAnswersWithANonEmptyJsonBody() {
        val sent = post("/api/claude-remarks/start", """{"session":"s1","label":"t","project":"/nope"}""")

        assertTrue(sent, sent.contains("\"status\""))
        assertTrue(sent, sent.contains("unknown-project"))
    }

    /**
     * This is the only guard on the behaviour change task 5 makes: before it, any sub-path started
     * a review because `execute` never looked at the path at all.
     */
    fun testAnUnknownActionDoesNotStartAReview() {
        val sent = post("/api/claude-remarks/frobnicate", """{"session":"s1","label":"t","project":"/nope"}""")

        assertTrue(sent, sent.contains("bad-request"))
        assertNull(WaitingReviewService.getInstance(project).current())
    }

    fun testAnAcknowledgementOfASentReviewAnswersOk() {
        val remark = addRemark(project, "A.kt", listOf("alpha"), 0..0, "a note")
        WaitingReviewService.getInstance(project).start("s1", "a label", 1800)
        WaitingReviewService.getInstance(project).markSent("s1", listOf(remark.id!!))

        val sent = post("/api/claude-remarks/ack", """{"session":"s1","project":"${projectPath()}","event":"read"}""")
        // finishReview's store mutation and balloon run inside invokeLater.
        UIUtil.dispatchAllInvocationEvents()

        assertTrue(sent, sent.contains("\"status\""))
        assertTrue(sent, sent.contains("\"ok\""))
        assertEquals(RemarkStatus.READ, RemarkStore.getInstance(project).all().single { it.id == remark.id }.status)
    }

    /**
     * The other four answers `handleAck` can give. The skill branches on these exact strings, so the
     * outcome-to-string mapping is pinned here rather than left to be swapped unnoticed.
     */
    fun testAnAcknowledgementWithNothingWaitingAnswersNoReview() {
        val sent = post("/api/claude-remarks/ack", """{"session":"s1","project":"${projectPath()}","event":"read"}""")

        assertTrue(sent, sent.contains("\"no-review\""))
    }

    fun testAReadAcknowledgementForAReviewThatWasNeverSentAnswersNotSent() {
        WaitingReviewService.getInstance(project).start("s1", "a label", 1800)

        val sent = post("/api/claude-remarks/ack", """{"session":"s1","project":"${projectPath()}","event":"read"}""")

        assertTrue(sent, sent.contains("\"not-sent\""))
        assertNotNull(WaitingReviewService.getInstance(project).current())
    }

    fun testAnAcknowledgementForAProjectNothingHasOpenAnswersUnknownProject() {
        val sent = post("/api/claude-remarks/ack", """{"session":"s1","project":"/nope","event":"read"}""")

        assertTrue(sent, sent.contains("\"unknown-project\""))
    }

    fun testAnAcknowledgementWithAnEventNobodyRecognizesAnswersBadRequest() {
        WaitingReviewService.getInstance(project).start("s1", "a label", 1800)

        val sent = post("/api/claude-remarks/ack", """{"session":"s1","project":"${projectPath()}","event":"nonsense"}""")

        // Not read as "abandoned": an unrecognized event must not end the review.
        assertTrue(sent, sent.contains("\"bad-request\""))
        assertNotNull(WaitingReviewService.getInstance(project).current())
    }

    /**
     * The deadline the skill declares really reaches the review. Without this, `handleStart` could
     * ignore `deadlineSeconds` and hardcode its own number with the whole suite still green — which
     * is the one thing the skill's own wait loop cannot survive, since the two clocks then disagree.
     * This is also the only test that posts a `files` list through the endpoint.
     */
    fun testTheStartRequestsDeadlineReachesTheReview() {
        val before = System.currentTimeMillis()

        val sent = post(
            "/api/claude-remarks/start",
            """{"session":"s1","label":"t","project":"${projectPath()}","deadlineSeconds":60,"files":["A.kt"]}""",
        )
        UIUtil.dispatchAllInvocationEvents()

        assertTrue(sent, sent.contains("\"waiting\""))
        val deadlineAt = WaitingReviewService.getInstance(project).current()!!.deadlineAt
        assertTrue("$deadlineAt is not about 60 seconds after $before", deadlineAt in before + 60_000..before + 70_000)
    }

    /**
     * A fetch before anything is published answers `waiting`, with no `content` field — the skill's
     * poll is supposed to come back.
     */
    fun testAFetchBeforeAnythingIsPublishedAnswersWaiting() {
        WaitingReviewService.getInstance(project).start("s1", "a label", 1800)

        val sent = post("/api/claude-remarks/fetch", """{"session":"s1","project":"${projectPath()}"}""")

        assertTrue(sent, sent.contains("\"waiting\""))
        assertFalse(sent, sent.contains("\"content\""))
    }

    /**
     * The transport fact as a test: once the published file names this session's review, a fetch's
     * response body carries the whole file itself, not a path. No live review is needed at all — the
     * fetch reads straight off the merged file, the same way a remote agent with no review running
     * would.
     *
     * The `nonce` and `bytes` fields are asserted here because the remote loop is built on them:
     * `watch-remarks.sh` reads `.nonce` off exactly this response to decide whether a batch is new,
     * and the skill sends the same value back as the published-read key.
     */
    fun testAFetchAfterThePublishCarriesTheWholePromptInTheBody() {
        writePublished(identity(), publishedBody(reviewSession = "s1"))

        val sent = post("/api/claude-remarks/fetch", """{"session":"s1","project":"${projectPath()}"}""")

        assertTrue(sent, sent.contains("\"ready\""))
        assertTrue(sent, sent.contains("a note about A"))
        assertTrue(sent, sent.contains("\"nonce\""))
        assertTrue(sent, sent.contains("\"n1\""))
        assertTrue(sent, sent.contains("\"bytes\""))
    }

    /**
     * Fetching is not reading: it must not mark anything read or touch the review's phase at all.
     *
     * It still has to answer `ready`. This is the only test that sets up a live review in the Sent
     * phase with a published file beside it, so it is the only place where "a live review that has
     * been answered reads the file rather than being told to keep polling" can be pinned at all.
     */
    fun testAFetchMarksNothingReadAndLeavesTheReviewAlone() {
        val remark = addRemark(project, "A.kt", listOf("alpha"), 0..0, "a note")
        writePublished(identity(), publishedBody(reviewSession = "s1"))
        WaitingReviewService.getInstance(project).start("s1", "a label", 1800)
        WaitingReviewService.getInstance(project).markSent("s1", listOf(remark.id!!))

        val sent = post("/api/claude-remarks/fetch", """{"session":"s1","project":"${projectPath()}"}""")

        assertTrue(sent, sent.contains("\"ready\""))
        assertFalse(sent, sent.contains("\"waiting\""))
        assertEquals(RemarkStatus.PENDING, RemarkStore.getInstance(project).all().single { it.id == remark.id }.status)
        val current = WaitingReviewService.getInstance(project).current()
        assertNotNull(current)
        assertTrue(current!!.phase is ReviewPhase.Sent)
    }

    /**
     * A rejection is a batch like any other, written into the same published file
     * (`review/ReviewLifecycle.kt`'s `rejectWaitingReview`), and the review is then cleared. A fetch after
     * that still has to hand the rejection back — the whole point of moving it onto the one file.
     */
    fun testAFetchStillCarriesARejection() {
        WaitingReviewService.getInstance(project).start("s1", "a label", 1800)

        rejectWaitingReview(project)

        val sent = post("/api/claude-remarks/fetch", """{"session":"s1","project":"${projectPath()}"}""")

        assertTrue(sent, sent.contains("\"ready\""))
        // A single line: the JSON encoding escapes REJECTION_BODY's real newlines to \n, so a
        // substring check has to stay on one line to match the encoded response.
        assertTrue(sent, sent.contains(REJECTION_BODY.lines().first()))
    }

    /**
     * A batch that answers a different session, or none at all, must not answer with that batch's
     * content — the header's `review:` comparison in handleFetch, as a test.
     *
     * Since phase 11 this is also one half of the regression guard on relaxing the action: a body
     * that carries a session still goes through the header gate exactly as it did before, and only a
     * body with no session at all skips it.
     */
    fun testAFetchForABatchThatAnswersAnotherSessionAnswersNoReview() {
        writePublished(identity(), publishedBody(reviewSession = "s1"))

        val sent = post("/api/claude-remarks/fetch", """{"session":"s2","project":"${projectPath()}"}""")

        assertTrue(sent, sent.contains("\"no-review\""))
        assertFalse(sent, sent.contains("a note about A"))
    }

    /**
     * The other half of that regression guard, and the sharper one: a plain publish writes no review
     * field at all, and a caller that names a session still gets `no-review` for it. This is what
     * "purely additive" means as a test — relaxing the gate must not make a session-carrying caller
     * start matching batches it never matched before.
     */
    fun testAFetchWithASessionStillAnswersNoReviewForAPlainPublish() {
        writePublished(identity(), publishedBody(reviewSession = null))

        val sent = post("/api/claude-remarks/fetch", """{"session":"s1","project":"${projectPath()}"}""")

        assertTrue(sent, sent.contains("\"no-review\""))
        assertFalse(sent, sent.contains("a note about A"))
    }

    /**
     * The case that was impossible before phase 11, and the whole reason the action was relaxed. A
     * plain publish writes `review: none`, so `header.reviewSession` is null and the comparison is
     * false for every session id any caller could send — a batch that does not answer a review was
     * unreachable over the tunnel. With no session in the body there is nothing to compare against,
     * so the gate is skipped and the batch comes back.
     *
     * `nonce` matters as much as `content` here: listen mode arms its watcher with the nonce it read
     * off exactly this response.
     */
    fun testASessionLessFetchAnswersReadyForAPlainPublish() {
        writePublished(identity(), publishedBody(reviewSession = null))

        val sent = post("/api/claude-remarks/fetch", """{"project":"${projectPath()}"}""")

        assertTrue(sent, sent.contains("\"ready\""))
        assertTrue(sent, sent.contains("a note about A"))
        assertTrue(sent, sent.contains("\"nonce\""))
        assertTrue(sent, sent.contains("\"n1\""))
    }

    /**
     * An absent session means "any batch", including one answering somebody else's review. That is
     * deliberate and not a hole: `session` was never a secret, the token in `isHostTrusted` is what
     * gates this route, and the skill's listen mode has its own rule for a batch that names a session
     * — say so, name it, act on nothing. Without this test that behaviour reads as a bug and gets
     * "fixed" into a refusal the listener cannot work around.
     */
    fun testASessionLessFetchAlsoCarriesABatchAnsweringSomebodyElsesReview() {
        writePublished(identity(), publishedBody(reviewSession = "s1"))

        val sent = post("/api/claude-remarks/fetch", """{"project":"${projectPath()}"}""")

        assertTrue(sent, sent.contains("\"ready\""))
        assertTrue(sent, sent.contains("a note about A"))
    }

    /** Nothing published for this project at all, asked without a session: still the ordinary poll answer. */
    fun testASessionLessFetchWithNothingPublishedAnswersNoReview() {
        val sent = post("/api/claude-remarks/fetch", """{"project":"${projectPath()}"}""")

        assertTrue(sent, sent.contains("\"no-review\""))
    }

    /**
     * The live-review short-circuit is skipped when there is no session. It exists to tell one
     * session's own poll to come back, and a listener has no review of its own to be waiting for — so
     * a session-less fetch reads the file even while somebody else's review sits in `Waiting`, and
     * answers on what it finds there.
     */
    fun testASessionLessFetchDoesNotWaitOnSomebodyElsesReview() {
        WaitingReviewService.getInstance(project).start("s1", "a label", 1800)

        val sent = post("/api/claude-remarks/fetch", """{"project":"${projectPath()}"}""")

        assertTrue(sent, sent.contains("\"no-review\""))
        assertFalse(sent, sent.contains("\"waiting\""))
    }

    /**
     * The other two answers `handleFetch` can give for a malformed or misdirected request, the same
     * pair `start` and `ack` already have their own parity tests for. Only `project` is required
     * since phase 11: a body with no `session` is a listener's fetch, not a malformed one.
     */
    fun testAFetchWithNoProjectAnswersBadRequest() {
        val sent = post("/api/claude-remarks/fetch", """{"session":"s1"}""")

        assertTrue(sent, sent.contains("\"bad-request\""))
    }

    fun testAFetchForAProjectNothingHasOpenAnswersUnknownProject() {
        val sent = post("/api/claude-remarks/fetch", """{"session":"s1","project":"/nope"}""")

        assertTrue(sent, sent.contains("\"unknown-project\""))
    }

    /**
     * The one test that exercises the real MAX_PUBLISHED_BYTES; the boundary itself is
     * ReviewRequestTest's job. No header is needed: the size check runs before the header is parsed.
     */
    fun testAFetchOverTheSizeLimitAnswersTooLargeAndNoContent() {
        val marker = "MARKER-END-OF-FILE"
        val body = "x".repeat(1_100_000) + "\n" + marker
        writePublished(identity(), body)

        val sent = post("/api/claude-remarks/fetch", """{"session":"s1","project":"${projectPath()}"}""")

        assertTrue(sent, sent.contains("\"too-large\""))
        assertTrue(sent, sent.contains("\"limit\""))
        assertFalse(sent, sent.contains(marker))
    }

    /**
     * A file this plugin did not write — or one an older plugin version wrote before this header
     * shape existed — must not be handed to a model as though it parsed. A lie is not a better
     * answer than an error.
     */
    fun testAFetchOfAFileWithABrokenHeaderAnswersFailed() {
        writePublished(identity(), "not a published file at all")

        val sent = post("/api/claude-remarks/fetch", """{"session":"s1","project":"${projectPath()}"}""")

        assertTrue(sent, sent.contains("\"failed\""))
    }

    /**
     * The read itself failing, rather than the header not parsing: a directory sits where the
     * published file should be, so the file exists and has a size but cannot be read as text. The
     * answer has to carry the reason with it — `no-review` would send the watcher back to polling for
     * something that will never arrive.
     */
    fun testAFetchThatCannotReadTheFileAnswersFailedWithADetail() {
        Files.createDirectories(handshakeDir().resolve(publishedName(identity().toString())))

        val sent = post("/api/claude-remarks/fetch", """{"session":"s1","project":"${projectPath()}"}""")

        assertTrue(sent, sent.contains("\"failed\""))
        assertTrue(sent, sent.contains("\"detail\""))
    }

    /**
     * The session id goes into the published file's header on a line the reader finds by number, and
     * it is the one header field written back out unchanged, since the fetch matches it. So a control
     * character in it is refused at the edge. Without this, a session like "x\nrejected: yes" would
     * move every header line after it and make a reader see a rejection that never happened.
     */
    fun testAStartWithAControlCharacterInTheSessionAnswersBadRequestAndStartsNothing() {
        val sent = post(
            "/api/claude-remarks/start",
            """{"session":"s1\nrejected: yes","label":"t","project":"${projectPath()}"}""",
        )

        assertTrue(sent, sent.contains("\"bad-request\""))
        assertNull(WaitingReviewService.getInstance(project).current())
    }

    /** A published batch naming [reviewSession], for the fetch tests that only need the header shape. */
    private fun publishedBody(reviewSession: String?): String {
        val header = PublishedHeader(
            nonce = "n1",
            publishedAt = System.currentTimeMillis(),
            commit = null,
            remarks = 1,
            reviewSession = reviewSession,
            reviewLabel = "a label",
            rejected = false,
        ).render()
        return header + "\n" + "a note about A"
    }

    /**
     * The ordinary case: a batch recorded through PublishedBatchService, then acknowledged over the
     * endpoint, answers ok and marks its remarks read. reportPublishedRead's consequences run inside
     * invokeLater, the same as the ack action's.
     */
    fun testAPublishedReadForARecordedBatchAnswersOkAndMarksTheRemarksRead() {
        val remark = addRemark(project, "A.kt", listOf("alpha"), 0..0, "a note")
        val nonce = PublishedBatchService.getInstance(project).record(listOf(remark.id!!))

        val sent = post(
            "/api/claude-remarks/published-read",
            """{"session":"s1","project":"${projectPath()}","nonce":"$nonce"}""",
        )
        UIUtil.dispatchAllInvocationEvents()

        assertTrue(sent, sent.contains("\"ok\""))
        // The count, not only the status: the skill prints it back to the person. Whitespace is
        // taken out first, because the platform's own JSON writer indents what it writes.
        assertTrue(sent, sent.filterNot { it.isWhitespace() }.contains("\"remarks\":1"))
        assertEquals(RemarkStatus.READ, RemarkStore.getInstance(project).all().single { it.id == remark.id }.status)
    }

    /**
     * A second published-read for the same batch, from a different session, does not mark anything
     * twice: it is told already-read and who got there first.
     */
    fun testASecondPublishedReadForTheSameBatchAnswersAlreadyReadAndNamesTheFirstSession() {
        val remark = addRemark(project, "A.kt", listOf("alpha"), 0..0, "a note")
        val nonce = PublishedBatchService.getInstance(project).record(listOf(remark.id!!))
        post(
            "/api/claude-remarks/published-read",
            """{"session":"s1","project":"${projectPath()}","nonce":"$nonce"}""",
        )
        UIUtil.dispatchAllInvocationEvents()

        val sent = post(
            "/api/claude-remarks/published-read",
            """{"session":"s2","project":"${projectPath()}","nonce":"$nonce"}""",
        )

        assertTrue(sent, sent.contains("\"already-read\""))
        assertTrue(sent, sent.contains("\"s1\""))
        // Both counters the answer carries, so a rename or a removal of either is caught here.
        assertTrue(sent, sent.contains("\"remarks\""))
        assertTrue(sent, sent.contains("\"readAt\""))
    }

    /** A nonce this plugin never recorded, or one that fell off the remembered sixteen. */
    fun testAPublishedReadForANonceNothingRecordedAnswersUnknownBatch() {
        val sent = post(
            "/api/claude-remarks/published-read",
            """{"session":"s1","project":"${projectPath()}","nonce":"does-not-exist"}""",
        )

        assertTrue(sent, sent.contains("\"unknown-batch\""))
    }

    fun testAPublishedReadWithNoNonceAnswersBadRequest() {
        val sent = post(
            "/api/claude-remarks/published-read",
            """{"session":"s1","project":"${projectPath()}"}""",
        )

        assertTrue(sent, sent.contains("\"bad-request\""))
    }

    fun testAPublishedReadForAProjectNothingHasOpenAnswersUnknownProject() {
        val sent = post(
            "/api/claude-remarks/published-read",
            """{"session":"s1","project":"/nope","nonce":"n1"}""",
        )

        assertTrue(sent, sent.contains("\"unknown-project\""))
    }

    /**
     * The `answer` action's ordinary case, at the HTTP layer: a marked remark the named batch really
     * carried, answered with markdown, and the answer is in the store afterwards.
     *
     * settleInvocationQueue rather than one dispatchAllInvocationEvents: `reportAnswer` queues a
     * read action on a pooled thread and only its finishing half lands on the EDT.
     */
    fun testAnAnswerForARemarkItsBatchCarriedAnswersOkAndStoresIt() {
        val remark = addRemark(project, "A.kt", listOf("alpha"), 0..0, "why?", asksForAnswer = true)
        val nonce = PublishedBatchService.getInstance(project).record(listOf(remark.id!!))

        val sent = post("/api/claude-remarks/answer", answerBody(nonce, remark.id!!, "because of X"))
        settleInvocationQueue()

        assertTrue(sent, sent.contains("\"ok\""))
        assertEquals("because of X", RemarkStore.getInstance(project).allAnswers().single().markdown)
    }

    /**
     * A replacement has no status of its own: the caller did nothing wrong, and at most one answer
     * per remark is enforced in the store rather than refused at the edge. A re-publish mints a fresh
     * nonce and a watcher compares nonces rather than content, so the same question reaching a
     * session twice is ordinary.
     */
    fun testASecondAnswerForTheSameRemarkIsAlsoOk() {
        val remark = addRemark(project, "A.kt", listOf("alpha"), 0..0, "why?", asksForAnswer = true)
        val nonce = PublishedBatchService.getInstance(project).record(listOf(remark.id!!))
        post("/api/claude-remarks/answer", answerBody(nonce, remark.id!!, "the first body"))
        settleInvocationQueue()

        val sent = post("/api/claude-remarks/answer", answerBody(nonce, remark.id!!, "the second body"))
        settleInvocationQueue()

        assertTrue(sent, sent.contains("\"ok\""))
        assertEquals("the second body", RemarkStore.getInstance(project).allAnswers().single().markdown)
    }

    /**
     * The endpoint deliberately does not look at `asksForAnswer`. The flag decides what the skill
     * does, and a second copy of that decision here would be a second place that can disagree — with
     * the store's copy winning silently over the prompt the session actually read. Without this test
     * that decision would be quietly reversed by the first person who reads the handler and assumes
     * a check is missing.
     */
    fun testAnAnswerForAnUnmarkedRemarkIsAlsoOk() {
        val remark = addRemark(project, "A.kt", listOf("alpha"), 0..0, "just a note")
        val nonce = PublishedBatchService.getInstance(project).record(listOf(remark.id!!))

        val sent = post("/api/claude-remarks/answer", answerBody(nonce, remark.id!!, "an answer anyway"))
        settleInvocationQueue()

        assertFalse(remark.asksForAnswer)
        assertTrue(sent, sent.contains("\"ok\""))
        assertEquals(1, RemarkStore.getInstance(project).allAnswers().size)
    }

    fun testAnAnswerForANonceNothingRecordedAnswersUnknownBatch() {
        val remark = addRemark(project, "A.kt", listOf("alpha"), 0..0, "why?", asksForAnswer = true)

        val sent = post("/api/claude-remarks/answer", answerBody("does-not-exist", remark.id!!, "because"))
        settleInvocationQueue()

        assertTrue(sent, sent.contains("\"unknown-batch\""))
        assertTrue(RemarkStore.getInstance(project).allAnswers().isEmpty())
    }

    /**
     * A real batch that never carried this id — a session answering a question it invented, or one
     * from an older batch. A different refusal from an unknown nonce, and the endpoint says so with
     * a different status, because the two need different handling by the caller.
     */
    fun testAnAnswerForARemarkItsBatchNeverCarriedAnswersUnknownRemark() {
        val shown = addRemark(project, "A.kt", listOf("alpha"), 0..0, "why?", asksForAnswer = true)
        val hidden = addRemark(project, "B.kt", listOf("beta"), 0..0, "and this?")
        val nonce = PublishedBatchService.getInstance(project).record(listOf(shown.id!!))

        val sent = post("/api/claude-remarks/answer", answerBody(nonce, hidden.id!!, "because"))
        settleInvocationQueue()

        assertTrue(sent, sent.contains("\"unknown-remark\""))
        assertTrue(RemarkStore.getInstance(project).allAnswers().isEmpty())
    }

    /**
     * Over MAX_ANSWER_BYTES the answer is refused whole rather than truncated, the same argument the
     * fetch's own size cap makes: a markdown document cut in the middle looks complete to whoever
     * reads it next. The answer goes straight into workspace.xml, which the platform rewrites on
     * every change.
     */
    fun testAnAnswerOverTheSizeCapAnswersTooLargeAndStoresNothing() {
        val remark = addRemark(project, "A.kt", listOf("alpha"), 0..0, "why?", asksForAnswer = true)
        val nonce = PublishedBatchService.getInstance(project).record(listOf(remark.id!!))

        val sent = post("/api/claude-remarks/answer", answerBody(nonce, remark.id!!, "x".repeat(20_000)))
        settleInvocationQueue()

        assertTrue(sent, sent.contains("\"too-large\""))
        assertTrue(sent, sent.contains("\"limit\""))
        assertTrue(RemarkStore.getInstance(project).allAnswers().isEmpty())
    }

    fun testAnAnswerWithNoRemarkIdAnswersBadRequest() {
        val sent = post(
            "/api/claude-remarks/answer",
            """{"session":"s1","project":"${projectPath()}","nonce":"n1","answer":"because"}""",
        )

        assertTrue(sent, sent.contains("\"bad-request\""))
    }

    fun testAnAnswerForAProjectNothingHasOpenAnswersUnknownProject() {
        val sent = post(
            "/api/claude-remarks/answer",
            """{"session":"s1","project":"/nope","nonce":"n1","remarkId":"r1","answer":"because"}""",
        )

        assertTrue(sent, sent.contains("\"unknown-project\""))
    }

    /** The five fields the `answer` action takes, so each test above spells out only what it varies. */
    private fun answerBody(nonce: String, remarkId: String, answer: String): String =
        """{"session":"s1","project":"${projectPath()}","nonce":"$nonce",""" +
            """"remarkId":"$remarkId","answer":"$answer"}"""

    /**
     * projectForPath compares the path as given, and the endpoint resolves every open project's
     * basePath with toRealPath() before comparing, so a symlinked checkout still matches.
     *
     * The createDirectories is not decoration: the light fixture project is reused across test
     * methods but its temp base directory is created for, and deleted after, the one method that
     * first built it. Without this, every method after that one gets NoSuchFileException here — and
     * `matchProject` catches the same failure on its own side and answers `unknown-project`, so the
     * test would look like a wrong status rather than a missing directory.
     */
    private fun projectPath(): String {
        val base = Path.of(project.basePath!!)
        Files.createDirectories(base)
        return base.toRealPath().toString()
    }

    /**
     * [projectIdentity] for the fixture project, guaranteed non-null. `projectIdentity` answers null
     * for a base path that does not exist on disk yet, the same reason [projectPath] above has to
     * create the directory before calling `toRealPath()` on it — so this does the same before asking.
     */
    private fun identity(): Path {
        Files.createDirectories(Path.of(project.basePath!!))
        return projectIdentity(project)!!
    }

    /** One no-op handler, not a bare `EmbeddedChannel()`: `firstContext()` returns null when the
     * pipeline holds nothing but its own head and tail, and `execute` needs a real context. */
    private fun post(path: String, json: String): String {
        val body = Unpooled.copiedBuffer(json, Charsets.UTF_8)
        val request = DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.POST, path, body)
        val channel = EmbeddedChannel(ChannelInboundHandlerAdapter())

        ReviewRestService().execute(
            QueryStringDecoder(request.uri()),
            request,
            channel.pipeline().firstContext(),
        )

        return channel.outboundMessages().joinToString("") {
            (it as? ByteBufHolder)?.content()?.toString(Charsets.UTF_8) ?: ""
        }
    }
}
