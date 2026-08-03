package dev.sasha.clauderemarks.review

import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.util.io.BufferExposingByteArrayOutputStream
import io.netty.channel.ChannelHandlerContext
import io.netty.handler.codec.http.FullHttpRequest
import io.netty.handler.codec.http.HttpHeaderNames
import io.netty.handler.codec.http.HttpMethod
import io.netty.handler.codec.http.QueryStringDecoder
import java.nio.file.Path
import org.jetbrains.ide.RestService

/**
 * `POST /api/claude-remarks/start`. The skill asks this IDE to hold a review open for a repository,
 * and gets back the path it should watch for the handover file.
 *
 * The answer is always HTTP 200 with a `status` field, one of exactly four values: `waiting`,
 * `conflict`, `unknown-project`, `bad-request`. Real status codes stay reserved for what
 * `RestService.process` produces above this class — 403, 429, and 400 or 500 from its catch — so a
 * plumbing failure never looks like an application answer to the shell script reading it.
 */

/** The header the skill puts this IDE run's token in. */
internal const val TOKEN_HEADER = "X-Claude-Remarks-Token"

/**
 * The waiting review's output path is a directory; this is the file inside it. Named here rather
 * than spelled out twice, because the endpoint promises this path in the response and the send
 * action has to write that same one.
 */
internal fun handoffFile(outputDir: Path): Path = outputDir.resolve("remarks.md")

/**
 * The whole authorisation rule, over four nullable strings so it can be tested without building an
 * HttpRequest.
 *
 * Refusing a request that carries `Origin` or `Referer` at all is not redundant with the platform's
 * own origin check, and the reason is specific to an IDE: the built-in web server serves files out
 * of open projects on 127.0.0.1, so a malicious `.html` committed into a repository the person
 * opens is served from a **local** origin, which the platform's check waves straight through. Only
 * this rule stops it. A command-line client never sends either header, so nothing is lost.
 *
 * The token is what stops another process on the same machine. It is minted once per IDE run and
 * written into the handshake file with owner-only permissions, so a process that cannot read that
 * file cannot drive this endpoint.
 */
internal fun requestIsAllowed(token: String?, expected: String, origin: String?, referer: String?): Boolean =
    origin == null && referer == null && token == expected

/**
 * Which open project a request is about, matched on the normalized path. The pairs come in already
 * normalized — see [ReviewRestService.execute] — and the wanted path is normalized here, so a
 * trailing slash from the caller still matches.
 */
internal fun <T> projectForPath(wanted: String, open: List<Pair<Path, T>>): T? {
    val target = runCatching { Path.of(wanted).normalize() }.getOrNull() ?: return null
    return open.firstOrNull { it.first == target }?.second
}

/**
 * Gson fills these by reflection; every field is nullable because the body is caller-supplied.
 * [files] is the cheap-diff-opening addition: paths relative to the repository root, opened in
 * editors once the review is accepted. Absent or empty means open nothing.
 */
private class StartRequest(
    val session: String? = null,
    val label: String? = null,
    val project: String? = null,
    val files: List<String>? = null,
)

class ReviewRestService : RestService() {

    override fun getServiceName(): String = "claude-remarks"

    /** The base class defaults to GET only, so this both adds POST and removes GET. */
    override fun isMethodSupported(method: HttpMethod): Boolean = method === HttpMethod.POST

    /**
     * Deliberately does **not** call `super`. The default returns true as soon as
     * `isOriginAllowed(request) == ALLOW`, which is what happens for any request with no `Origin`
     * and no `Referer` — a plain `curl` would already be trusted and the token check would be
     * decorative. Not calling super also makes the platform's referrer dialog unreachable, so no
     * request can pop a modal window at the person, and the `isRequestSigned` path unreachable,
     * which is unusable here anyway: its token expires a minute after last access and no outside
     * process can read it.
     *
     * This is the two-argument form, which is the non-deprecated one.
     */
    override fun isHostTrusted(request: FullHttpRequest, urlDecoder: QueryStringDecoder): Boolean =
        requestIsAllowed(
            token = request.headers().get(TOKEN_HEADER),
            expected = ReviewToken.value,
            origin = request.headers().get(HttpHeaderNames.ORIGIN),
            referer = request.headers().get(HttpHeaderNames.REFERER),
        )

    /**
     * Runs on a netty IO thread, which is neither the EDT nor a thread holding any IntelliJ lock,
     * so this method must not touch the VFS, must not touch Swing, and must not wait on the EDT.
     * `CLAUDE.md` greps this whole file for the names that would do any of those, which is why they
     * are not spelled out here either — the guard cannot tell a comment from code. The one
     * filesystem call below, `toRealPath()`, is plain java.nio and is deliberately fine; anything
     * that hands back a `VirtualFile` would not be, so opening files lives in its own file.
     */
    override fun execute(
        urlDecoder: QueryStringDecoder,
        request: FullHttpRequest,
        context: ChannelHandlerContext,
    ): String? {
        val out = BufferExposingByteArrayOutputStream()
        val writer = createJsonWriter(out)
        writer.beginObject()

        val body = runCatching {
            gson.fromJson<StartRequest?>(createJsonReader(request), StartRequest::class.java)
        }
        val parsed = body.getOrNull()
        val session = parsed?.session
        val label = parsed?.label
        val wanted = parsed?.project
        if (session.isNullOrBlank() || label.isNullOrBlank() || wanted.isNullOrBlank()) {
            // A typo in the skill must produce a readable answer, not a stack trace in the IDE log.
            writer.name("status").value("bad-request")
            writer.name("detail").value(
                body.exceptionOrNull()?.message ?: "expected a JSON object with session, label and project"
            )
        } else {
            // basePath is the path the project was opened with, which for a symlinked checkout is
            // the symlink, while the skill sends the physical path from `git rev-parse
            // --show-toplevel`. Without toRealPath() a plainly open project answers
            // unknown-project. The runCatching is for a project whose directory has been deleted:
            // it still appears in openProjects.
            val open = ProjectManager.getInstance().openProjects.mapNotNull { project ->
                val base = project.basePath ?: return@mapNotNull null
                val real = runCatching { Path.of(base).toRealPath() }.getOrNull() ?: return@mapNotNull null
                real to project
            }
            val project = projectForPath(wanted, open)
            if (project == null) {
                writer.name("status").value("unknown-project")
                writer.name("open").beginArray()
                open.forEach { writer.value(it.first.toString()) }
                writer.endArray()
            } else when (
                // 1800 is the skill's own long-standing literal timeout. Task 5 replaces this with
                // the request's own declared deadline, clamped at this boundary; this call is
                // provisional only until that task wires the real value through.
                val result = WaitingReviewService.getInstance(project).start(session, label, 1800L)
            ) {
                is StartResult.Accepted -> {
                    writer.name("status").value("waiting")
                    writer.name("output").value(handoffFile(result.state.outputPath).toString())
                    writer.name("project").value(project.name)
                    // The one call into the file that owns the VFS and the editor. See
                    // review/OpenReviewFiles.kt for why it lives there and not here.
                    openReviewFiles(project, parsed?.files)
                }
                is StartResult.Conflict -> {
                    writer.name("status").value("conflict")
                    writer.name("label").value(result.waiting.label)
                    writer.name("startedAt").value(result.waiting.startedAt)
                }
            }
        }

        writer.endObject()
        // Before send, always: createJsonWriter wraps a buffering OutputStreamWriter and send reads
        // the byte array's internal buffer, so without this every response is a 200 with an empty
        // body. The platform's own InstallPluginService closes it here for the same reason.
        writer.close()
        send(out, request, context)
        // Non-null would make the platform send a 400 with that text instead.
        return null
    }
}
