package dev.sasha.clauderemarks.review

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.ui.UIUtil
import dev.sasha.clauderemarks.model.RemarkStatus
import dev.sasha.clauderemarks.store.RemarkStore
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

    override fun setUp() {
        super.setUp()
        // The light fixture project is shared across test classes.
        RemarkStore.getInstance(project).clear()
        WaitingReviewService.getInstance(project).clear()
    }

    override fun tearDown() {
        RemarkStore.getInstance(project).clear()
        WaitingReviewService.getInstance(project).clear()
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
        WaitingReviewService.getInstance(project).start("s1", "a label", 1800, Files.createTempDirectory("ack-smoke"))
        WaitingReviewService.getInstance(project).markSent(listOf(remark.id!!))
        // The real, symlink-resolved path: projectForPath compares it as given, and the endpoint's
        // own side resolves every open project's basePath with toRealPath() before comparing.
        val projectPath = Path.of(project.basePath!!).toRealPath().toString()

        val sent = post("/api/claude-remarks/ack", """{"session":"s1","project":"$projectPath","event":"read"}""")
        // finishReview's store mutation and balloon run inside invokeLater.
        UIUtil.dispatchAllInvocationEvents()

        assertTrue(sent, sent.contains("\"status\""))
        assertTrue(sent, sent.contains("\"ok\""))
        assertEquals(RemarkStatus.SENT, RemarkStore.getInstance(project).all().single { it.id == remark.id }.status)
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
