package dev.sasha.clauderemarks.review

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.netty.buffer.ByteBufHolder
import io.netty.buffer.Unpooled
import io.netty.channel.ChannelInboundHandlerAdapter
import io.netty.channel.embedded.EmbeddedChannel
import io.netty.handler.codec.http.DefaultFullHttpRequest
import io.netty.handler.codec.http.HttpMethod
import io.netty.handler.codec.http.HttpVersion
import io.netty.handler.codec.http.QueryStringDecoder

/**
 * The one test that runs `execute`. It exists for a single defect nothing else can see: the JSON
 * writer wraps a buffering OutputStreamWriter, so a missing `close()` before `send` produces a 200
 * with an empty body — the skill then finds no `status` field and hangs to its own timeout.
 *
 * It calls `execute` directly, not `process`. `process` catches Throwable and calls `LOG.error`,
 * which throws in tests, and it touches a per-instance request counter. The trust rule that
 * `process` would exercise is already covered by `requestIsAllowed`'s five tests in
 * [ReviewRequestTest].
 *
 * The project path is deliberately one nothing has open, so the answer is `unknown-project`: no
 * waiting review is started and no temp directory is created. The fixture is needed even so —
 * listing the open projects goes through `ProjectManager.getInstance()`, which needs a running
 * Application.
 */
class ReviewEndpointSmokeTest : BasePlatformTestCase() {

    fun testExecuteAnswersWithANonEmptyJsonBody() {
        val body = Unpooled.copiedBuffer("""{"session":"s1","label":"t","project":"/nope"}""", Charsets.UTF_8)
        val request = DefaultFullHttpRequest(
            HttpVersion.HTTP_1_1,
            HttpMethod.POST,
            "/api/claude-remarks/start",
            body,
        )
        // One no-op handler, not a bare EmbeddedChannel(): firstContext() returns null when the
        // pipeline holds nothing but its own head and tail, and execute needs a real context.
        val channel = EmbeddedChannel(ChannelInboundHandlerAdapter())

        ReviewRestService().execute(
            QueryStringDecoder(request.uri()),
            request,
            channel.pipeline().firstContext(),
        )

        val sent = channel.outboundMessages().joinToString("") {
            (it as? ByteBufHolder)?.content()?.toString(Charsets.UTF_8) ?: ""
        }
        assertTrue(sent, sent.contains("\"status\""))
        assertTrue(sent, sent.contains("unknown-project"))
    }
}
