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
 * The one test class that runs `execute`. Every test below that inspects `sent` also proves a
 * single defect nothing else can see: the JSON writer wraps a buffering OutputStreamWriter, so a
 * missing `close()` before `send` produces a 200 with an empty body — the skill then finds no
 * `status` field and hangs to its own timeout.
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
        PublishedBatchService.getInstance(project).clear()
        deletePublishedFile()
    }

    override fun tearDown() {
        RemarkStore.getInstance(project).clear()
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

    /** Any sub-path `execute` does not dispatch on is refused with `bad-request`. */
    fun testAnUnknownActionAnswersBadRequest() {
        val sent = post("/api/claude-remarks/frobnicate", """{"project":"/nope"}""")

        assertTrue(sent, sent.contains("bad-request"))
    }

    /**
     * The transport fact as a test: a fetch's response body carries the whole published file
     * itself, not a path, straight off the merged file — the same way a remote agent with no review
     * running would read it.
     *
     * The `nonce` and `bytes` fields are asserted here because the remote loop is built on them:
     * `watch-remarks.sh` reads `.nonce` off exactly this response to decide whether a batch is new,
     * and the skill sends the same value back as the published-read key.
     */
    fun testAFetchAfterThePublishCarriesTheWholePromptInTheBody() {
        writePublished(identity(), publishedBody(reviewSession = "s1"))

        val sent = post("/api/claude-remarks/fetch", """{"project":"${projectPath()}"}""")

        assertTrue(sent, sent.contains("\"ready\""))
        assertTrue(sent, sent.contains("a note about A"))
        assertTrue(sent, sent.contains("\"nonce\""))
        assertTrue(sent, sent.contains("\"n1\""))
        assertTrue(sent, sent.contains("\"bytes\""))
    }

    /**
     * Fetching is not reading: it must not mark anything read. It still has to answer `ready`,
     * reading straight off the merged file regardless of anything else stored alongside it.
     */
    fun testAFetchMarksNothingRead() {
        val remark = addRemark(project, "A.kt", listOf("alpha"), 0..0, "a note")
        writePublished(identity(), publishedBody(reviewSession = "s1"))

        val sent = post("/api/claude-remarks/fetch", """{"project":"${projectPath()}"}""")

        assertTrue(sent, sent.contains("\"ready\""))
        assertEquals(RemarkStatus.PENDING, RemarkStore.getInstance(project).all().single { it.id == remark.id }.status)
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
     * The other two answers `handleFetch` can give for a malformed or misdirected request: a
     * missing `project`, and a `project` nothing has open. There is no `session` field left on the
     * request at all, so there is nothing left to make optional about it.
     */
    fun testAFetchWithNoProjectAnswersBadRequest() {
        val sent = post("/api/claude-remarks/fetch", """{}""")

        assertTrue(sent, sent.contains("\"bad-request\""))
    }

    fun testAFetchForAProjectNothingHasOpenAnswersUnknownProject() {
        val sent = post("/api/claude-remarks/fetch", """{"project":"/nope"}""")

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

        val sent = post("/api/claude-remarks/fetch", """{"project":"${projectPath()}"}""")

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

        val sent = post("/api/claude-remarks/fetch", """{"project":"${projectPath()}"}""")

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

        val sent = post("/api/claude-remarks/fetch", """{"project":"${projectPath()}"}""")

        assertTrue(sent, sent.contains("\"failed\""))
        assertTrue(sent, sent.contains("\"detail\""))
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

    /**
     * The cap is on the UTF-8 byte length, and the boundary is where that matters: one byte under it
     * is accepted and one byte over it is refused. The 20 000-character body above is far enough over
     * to pass whether the cap counts bytes or characters, and whether it is `>` or `>=`.
     */
    fun testTheAnswerSizeCapIsCheckedAtItsExactBoundary() {
        val remark = addRemark(project, "A.kt", listOf("alpha"), 0..0, "why?", asksForAnswer = true)
        val nonce = PublishedBatchService.getInstance(project).record(listOf(remark.id!!))

        val atTheLimit = post("/api/claude-remarks/answer", answerBody(nonce, remark.id!!, "x".repeat(MAX_ANSWER_BYTES)))
        settleInvocationQueue()
        assertTrue(atTheLimit, atTheLimit.contains("\"ok\""))

        val oneOver = post("/api/claude-remarks/answer", answerBody(nonce, remark.id!!, "x".repeat(MAX_ANSWER_BYTES + 1)))
        settleInvocationQueue()
        assertTrue(oneOver, oneOver.contains("\"too-large\""))
    }

    /**
     * A two-byte character counts as two. Counting characters instead would let a body of
     * MAX_ANSWER_BYTES multi-byte characters through at twice the intended size, and the cap is about
     * what lands in workspace.xml.
     */
    fun testTheAnswerSizeCapCountsBytesAndNotCharacters() {
        val remark = addRemark(project, "A.kt", listOf("alpha"), 0..0, "why?", asksForAnswer = true)
        val nonce = PublishedBatchService.getInstance(project).record(listOf(remark.id!!))

        // Half the cap in characters, the whole cap plus two bytes in UTF-8.
        val body = "ä".repeat(MAX_ANSWER_BYTES / 2 + 1)
        val sent = post("/api/claude-remarks/answer", answerBody(nonce, remark.id!!, body))
        settleInvocationQueue()

        assertTrue(sent, sent.contains("\"too-large\""))
    }

    /** The `answer` action requires a session the same way `published-read` does, even though
     *  nothing reads it: a caller that omits the field is told so rather than silently accepted. */
    fun testAnAnswerWithABlankSessionAnswersBadRequest() {
        val sent = post(
            "/api/claude-remarks/answer",
            """{"session":"  ","project":"${projectPath()}","nonce":"n1","remarkId":"r1","answer":"because"}""",
        )

        assertTrue(sent, sent.contains("\"bad-request\""))
    }

    fun testAnAnswerWithNoSessionAtAllAnswersBadRequest() {
        val sent = post(
            "/api/claude-remarks/answer",
            """{"project":"${projectPath()}","nonce":"n1","remarkId":"r1","answer":"because"}""",
        )

        assertTrue(sent, sent.contains("\"bad-request\""))
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

    /**
     * The `open` action's ordinary case: a real project and a files list that survives
     * filterReviewPaths answers `ok` with the accepted count. dispatchAllInvocationEvents settles the
     * invokeLater openReviewFiles queues so it does not leak into the next test.
     */
    fun testAnOpenForARealProjectAnswersOkWithTheAcceptedCount() {
        val sent = post("/api/claude-remarks/open", """{"project":"${projectPath()}","files":["A.kt","B.kt"]}""")
        UIUtil.dispatchAllInvocationEvents()

        assertTrue(sent, sent.contains("\"ok\""))
        assertTrue(sent, sent.filterNot { it.isWhitespace() }.contains("\"opened\":2"))
    }

    /** An absent files list is a legitimate no-op, not a refusal: opening nothing is still `ok`. */
    fun testAnOpenWithNoFilesAnswersOkWithOpenedZero() {
        val sent = post("/api/claude-remarks/open", """{"project":"${projectPath()}"}""")

        assertTrue(sent, sent.contains("\"ok\""))
        assertTrue(sent, sent.filterNot { it.isWhitespace() }.contains("\"opened\":0"))
    }

    /** The same unknown-project answer every other action gives, naming the identities that are open. */
    fun testAnOpenForAProjectNothingHasOpenAnswersUnknownProject() {
        val sent = post("/api/claude-remarks/open", """{"project":"/nope","files":["A.kt"]}""")

        assertTrue(sent, sent.contains("\"unknown-project\""))
        assertTrue(sent, sent.contains("\"open\""))
    }

    /** A missing project is bad-request with a detail, the parity every other action's own test pins. */
    fun testAnOpenWithNoProjectAnswersBadRequestWithADetail() {
        val sent = post("/api/claude-remarks/open", """{"files":["A.kt"]}""")

        assertTrue(sent, sent.contains("\"bad-request\""))
        assertTrue(sent, sent.contains("\"detail\""))
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
