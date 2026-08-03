package dev.sasha.clauderemarks.review

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.ui.UIUtil
import dev.sasha.clauderemarks.model.RemarkStatus
import dev.sasha.clauderemarks.store.RemarkStore
import dev.sasha.clauderemarks.store.TempPaths
import dev.sasha.clauderemarks.store.addRemark
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
        val remark = addRemark(project, "A.kt", listOf("alpha"), 0..0, "a note", null)
        WaitingReviewService.getInstance(project).start("s1", "a label", 1800, temp.dir("ack-smoke"))
        WaitingReviewService.getInstance(project).markSent("s1", listOf(remark.id!!))

        val sent = post("/api/claude-remarks/ack", """{"session":"s1","project":"${projectPath()}","event":"read"}""")
        // finishReview's store mutation and balloon run inside invokeLater.
        UIUtil.dispatchAllInvocationEvents()

        assertTrue(sent, sent.contains("\"status\""))
        assertTrue(sent, sent.contains("\"ok\""))
        assertEquals(RemarkStatus.SENT, RemarkStore.getInstance(project).all().single { it.id == remark.id }.status)
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
        WaitingReviewService.getInstance(project).start("s1", "a label", 1800, temp.dir("ack-smoke"))

        val sent = post("/api/claude-remarks/ack", """{"session":"s1","project":"${projectPath()}","event":"read"}""")

        assertTrue(sent, sent.contains("\"not-sent\""))
        assertNotNull(WaitingReviewService.getInstance(project).current())
    }

    fun testAnAcknowledgementForAProjectNothingHasOpenAnswersUnknownProject() {
        val sent = post("/api/claude-remarks/ack", """{"session":"s1","project":"/nope","event":"read"}""")

        assertTrue(sent, sent.contains("\"unknown-project\""))
    }

    fun testAnAcknowledgementWithAnEventNobodyRecognizesAnswersBadRequest() {
        WaitingReviewService.getInstance(project).start("s1", "a label", 1800, temp.dir("ack-smoke"))

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
     * A fetch before the send has written anything answers `waiting`, with no `content` field — the
     * skill's poll is supposed to come back.
     */
    fun testAFetchBeforeTheSendAnswersWaiting() {
        WaitingReviewService.getInstance(project).start("s1", "a label", 1800, temp.dir("fetch-waiting"))

        val sent = post("/api/claude-remarks/fetch", """{"session":"s1","project":"${projectPath()}"}""")

        assertTrue(sent, sent.contains("\"waiting\""))
        assertFalse(sent, sent.contains("\"content\""))
    }

    /**
     * The transport fact as a test: after the send has written the file and marked it sent, a
     * fetch's response body carries the remark text itself, not a path.
     */
    fun testAFetchAfterTheSendCarriesTheWholePromptInTheBody() {
        val dir = temp.dir("fetch-ready")
        WaitingReviewService.getInstance(project).start("s1", "a label", 1800, dir)
        atomicWriteString(handoffFile(dir), "a note about A")
        WaitingReviewService.getInstance(project).markSent("s1", listOf("r1"))

        val sent = post("/api/claude-remarks/fetch", """{"session":"s1","project":"${projectPath()}"}""")

        assertTrue(sent, sent.contains("\"ready\""))
        assertTrue(sent, sent.contains("a note about A"))
    }

    /**
     * Fetching is not reading: it must not mark anything sent or touch the review's phase at all.
     */
    fun testAFetchMarksNothingSentAndLeavesTheReviewAlone() {
        val remark = addRemark(project, "A.kt", listOf("alpha"), 0..0, "a note", null)
        val dir = temp.dir("fetch-no-mutate")
        WaitingReviewService.getInstance(project).start("s1", "a label", 1800, dir)
        atomicWriteString(handoffFile(dir), "a note about A")
        WaitingReviewService.getInstance(project).markSent("s1", listOf(remark.id!!))

        post("/api/claude-remarks/fetch", """{"session":"s1","project":"${projectPath()}"}""")

        assertEquals(RemarkStatus.PENDING, RemarkStore.getInstance(project).all().single { it.id == remark.id }.status)
        val current = WaitingReviewService.getInstance(project).current()
        assertNotNull(current)
        assertTrue(current!!.phase is ReviewPhase.Sent)
    }

    /**
     * The rejection body is written into the handoff file and then the review is cleared, in that
     * order (review/SendReview.kt). A fetch after that still has to hand the rejection back — the
     * whole reason WaitingReviewService remembers the ended review's path.
     */
    fun testAFetchOfARejectedReviewStillCarriesTheRejectionBody() {
        val dir = temp.dir("fetch-rejected")
        WaitingReviewService.getInstance(project).start("s1", "a label", 1800, dir)
        atomicWriteString(handoffFile(dir), REJECTION_BODY)
        WaitingReviewService.getInstance(project).clear("s1")

        val sent = post("/api/claude-remarks/fetch", """{"session":"s1","project":"${projectPath()}"}""")

        assertTrue(sent, sent.contains("\"ready\""))
        assertTrue(sent, sent.contains("<!-- claude-remarks: rejected -->"))
    }

    /**
     * A session id nothing knows about, while a different review is waiting, must not answer with
     * that other review's content — the session comparison in handleFetch, as a test.
     */
    fun testAFetchForASessionNothingKnowsAboutAnswersNoReview() {
        val dir = temp.dir("fetch-other-session")
        WaitingReviewService.getInstance(project).start("s1", "a label", 1800, dir)
        atomicWriteString(handoffFile(dir), "a note about A")
        WaitingReviewService.getInstance(project).markSent("s1", listOf("r1"))

        val sent = post("/api/claude-remarks/fetch", """{"session":"s2","project":"${projectPath()}"}""")

        assertTrue(sent, sent.contains("\"no-review\""))
        assertFalse(sent, sent.contains("a note about A"))
    }

    /**
     * The other two answers `handleFetch` can give for a malformed or misdirected request, the same
     * pair `start` and `ack` already have their own parity tests for.
     */
    fun testAFetchWithNoSessionAnswersBadRequest() {
        val sent = post("/api/claude-remarks/fetch", """{"project":"${projectPath()}"}""")

        assertTrue(sent, sent.contains("\"bad-request\""))
    }

    fun testAFetchForAProjectNothingHasOpenAnswersUnknownProject() {
        val sent = post("/api/claude-remarks/fetch", """{"session":"s1","project":"/nope"}""")

        assertTrue(sent, sent.contains("\"unknown-project\""))
    }

    /**
     * The one test that exercises the real MAX_HANDOFF_BYTES; the boundary itself is task 3's job.
     */
    fun testAFetchOverTheSizeLimitAnswersTooLargeAndNoContent() {
        val dir = temp.dir("fetch-too-large")
        WaitingReviewService.getInstance(project).start("s1", "a label", 1800, dir)
        val marker = "MARKER-END-OF-FILE"
        val body = "x".repeat(1_100_000) + "\n" + marker
        atomicWriteString(handoffFile(dir), body)
        WaitingReviewService.getInstance(project).markSent("s1", listOf("r1"))

        val sent = post("/api/claude-remarks/fetch", """{"session":"s1","project":"${projectPath()}"}""")

        assertTrue(sent, sent.contains("\"too-large\""))
        assertTrue(sent, sent.contains("\"limit\""))
        assertFalse(sent, sent.contains(marker))
    }

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
