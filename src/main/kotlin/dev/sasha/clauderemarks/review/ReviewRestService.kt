package dev.sasha.clauderemarks.review

import com.google.gson.stream.JsonWriter
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.util.io.BufferExposingByteArrayOutputStream
import io.netty.channel.ChannelHandlerContext
import io.netty.handler.codec.http.FullHttpRequest
import io.netty.handler.codec.http.HttpHeaderNames
import io.netty.handler.codec.http.HttpMethod
import io.netty.handler.codec.http.QueryStringDecoder
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import org.jetbrains.ide.RestService

/**
 * `POST /api/claude-remarks/start`, `POST /api/claude-remarks/ack`, `POST /api/claude-remarks/fetch`
 * and `POST /api/claude-remarks/published-read`. The skill asks this IDE to hold a review open for a
 * repository, then later tells the IDE whether it read the remarks or gave up waiting. Where those
 * remarks land is not part of the answer since phase 10: both sides compute the one published file's
 * path from the repository path itself. `fetch` is a third moment: it hands that file's content back
 * in the response body itself, for an agent with no filesystem access to it. `published-read` is a
 * fourth, and independent of the other three: it acknowledges a batch a
 * publish wrote to the merged file, keyed to the nonce that batch's header carries rather than to a
 * review session, since a publish can happen with no review waiting at all.
 *
 * The answer is always HTTP 200 with a `status` field. `start` answers one of `waiting`,
 * `conflict`, `unknown-project`, `bad-request`; `ack` answers one of `ok`, `no-review`,
 * `not-sent`, `unknown-project`, `bad-request`; `fetch` answers one of `ready`, `waiting`,
 * `no-review`, `too-large`, `unknown-project`, `bad-request`, `failed`; `published-read` answers one
 * of `ok`, `already-read`, `unknown-batch`, `unknown-project`, `bad-request`. Real status codes stay
 * reserved for what `RestService.process` produces above this class — 403, 429, and 400 or 500
 * from its catch — so a plumbing failure never looks like an application answer to the shell
 * script reading it.
 */

/** The header the skill puts this IDE run's token in. */
internal const val TOKEN_HEADER = "X-Claude-Remarks-Token"

/** What reading the published file produced. [bytes] is the file's size, not the string's length. */
internal sealed interface PublishedRead {
    /** No file at that path: nothing has been published for this project yet. */
    data object Absent : PublishedRead
    data class TooLarge(val bytes: Long) : PublishedRead
    data class Content(val text: String, val bytes: Long) : PublishedRead
}

/**
 * The whole published file at [file], or a refusal. [limit] is a parameter rather than the constant
 * below so the boundary is testable in milliseconds instead of by writing a megabyte for every case.
 *
 * Over the limit the file is not read at all — the size is checked first, so an oversized published
 * file never becomes an oversized allocation. Truncating was the alternative and it is worse: a
 * markdown prompt cut in the middle looks complete to a model.
 *
 * The exists-then-size pair is not a race here: the plugin never deletes the published file. An
 * IOException from either call is left to the caller, which turns it into a `failed` answer, the
 * same way `handleFetch` below does with it.
 */
internal fun readPublished(file: Path, limit: Long): PublishedRead {
    if (!Files.exists(file)) return PublishedRead.Absent
    val bytes = Files.size(file)
    if (bytes > limit) return PublishedRead.TooLarge(bytes)
    return PublishedRead.Content(Files.readString(file, StandardCharsets.UTF_8), bytes)
}

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
 * editors once the review is accepted. Absent or empty means open nothing. [deadlineSeconds] is
 * how long the skill's own wait loop will poll; absent or out of range is clamped by
 * [clampDeadlineSeconds].
 */
private class StartRequest(
    val session: String? = null,
    val label: String? = null,
    val project: String? = null,
    val files: List<String>? = null,
    val deadlineSeconds: Long? = null,
)

/** Gson fills these by reflection too. [event] is `"read"` or `"abandoned"`, anything else is bad-request. */
private class AckRequest(
    val session: String? = null,
    val project: String? = null,
    val event: String? = null,
)

/** Gson fills these by reflection too. No `event` and no `label`: a fetch changes nothing. */
private class FetchRequest(
    val session: String? = null,
    val project: String? = null,
)

/**
 * Gson fills these by reflection too. [nonce] identifies the batch a publish recorded; [session] is
 * a name the calling session invents for itself. The endpoint never hands one out and never checks
 * it, only reports it back, which is what lets a second session be told who got there first.
 */
private class PublishedReadRequest(
    val session: String? = null,
    val project: String? = null,
    val nonce: String? = null,
)

/**
 * The default the skill's own `deadline_seconds` carries in
 * `docs/skill/claude-remarks-review/SKILL.md` step 3, and the bounds it is corrected to. Named
 * rather than inlined so that the number is greppable from the skill's side: the two documents have
 * to agree, and a bare 1800 in the middle of an expression is the kind of thing that drifts.
 */
private const val DEFAULT_DEADLINE_SECONDS = 1800L
private const val MIN_DEADLINE_SECONDS = 60L
private const val MAX_DEADLINE_SECONDS = 86_400L

/**
 * The largest published file the fetch action will put in a response. A remark with its code context
 * is a few hundred bytes, so this is thousands of remarks — unreachable in ordinary use, and still a
 * bound on what one response allocates. Named rather than inlined because the skill's own message
 * quotes the number back to the person.
 */
private const val MAX_PUBLISHED_BYTES = 1_048_576L

/**
 * The skill declares how long it will wait. The number arrives over HTTP, so it is bounded here,
 * at the edge, rather than deeper in. Absent means the 1800 seconds the skill's own documentation
 * has always used.
 */
internal fun clampDeadlineSeconds(seconds: Long?): Long =
    (seconds ?: DEFAULT_DEADLINE_SECONDS).coerceIn(MIN_DEADLINE_SECONDS, MAX_DEADLINE_SECONDS)

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
     * are not spelled out here either — the guard cannot tell a comment from code. The filesystem
     * calls this reaches — reading the published file, and working out which repository each open
     * project belongs to — are plain java.nio and are deliberately fine; anything that hands back a
     * `VirtualFile` would not be, so opening files lives in its own file.
     */
    override fun execute(
        urlDecoder: QueryStringDecoder,
        request: FullHttpRequest,
        context: ChannelHandlerContext,
    ): String? {
        val out = BufferExposingByteArrayOutputStream()
        val writer = createJsonWriter(out)
        writer.beginObject()

        // Copied from the platform's own UploadLogsService, which dispatches on a sub-path the
        // same way. Deliberately not substringAfterLast('/'): on the bare path
        // "/api/claude-remarks" that would return "claude-remarks", which would be read as an
        // action name instead of the empty one it actually is.
        val action = urlDecoder.path().split(getServiceName()).last().trimStart('/')
        when (action) {
            "start" -> handleStart(request, writer)
            "ack" -> handleAck(request, writer)
            "fetch" -> handleFetch(request, writer)
            "published-read" -> handlePublishedRead(request, writer)
            // A behaviour change worth naming: before this, any sub-path started a review because
            // execute never looked at it at all.
            else -> badRequest(writer, cause = null, fallbackDetail = "unknown action: $action")
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

    private fun handleStart(request: FullHttpRequest, writer: JsonWriter) {
        val body = runCatching {
            gson.fromJson<StartRequest?>(createJsonReader(request), StartRequest::class.java)
        }
        val parsed = body.getOrNull()
        val session = parsed?.session
        val label = parsed?.label
        val wanted = parsed?.project
        if (session.isNullOrBlank() || label.isNullOrBlank() || wanted.isNullOrBlank()) {
            // A typo in the skill must produce a readable answer, not a stack trace in the IDE log.
            badRequest(
                writer,
                body.exceptionOrNull(),
                "expected a JSON object with session, label and project",
            )
            return
        }
        // The session id is written into the published file's header, on a line a reader finds by
        // line number, and it is the one field there that is not rewritten before it goes in — it has
        // to come back out byte for byte, because the fetch matches it against what the skill sends.
        // So a control character in it is refused here instead, at the edge, rather than quietly
        // changed. A session like "x\nrejected: yes" would otherwise move every header line after it.
        if (session.any { it < ' ' }) {
            badRequest(writer, cause = null, fallbackDetail = "session must not contain control characters")
            return
        }
        val project = matchProject(wanted, writer) ?: return
        val deadline = clampDeadlineSeconds(parsed.deadlineSeconds)
        val result = WaitingReviewService.getInstance(project).start(session, label, deadline)
        when (result) {
            is StartResult.Accepted -> {
                writer.name("status").value("waiting")
                writer.name("project").value(project.name)
                // The one call into the file that owns the VFS and the editor. See
                // review/OpenReviewFiles.kt for why it lives there and not here. Only on a first
                // accept: a retry must not open a second diff window over the same changes.
                if (result.fresh) openReviewFiles(project, parsed.files)
            }
            is StartResult.Conflict -> {
                writer.name("status").value("conflict")
                writer.name("label").value(result.waiting.label)
                writer.name("startedAt").value(result.waiting.startedAt)
            }
        }
    }

    private fun handleAck(request: FullHttpRequest, writer: JsonWriter) {
        val body = runCatching {
            gson.fromJson<AckRequest?>(createJsonReader(request), AckRequest::class.java)
        }
        val parsed = body.getOrNull()
        val session = parsed?.session
        val wanted = parsed?.project
        val end = when (parsed?.event) {
            "read" -> ReviewEnd.READ
            "abandoned" -> ReviewEnd.ABANDONED
            else -> null
        }
        if (session.isNullOrBlank() || wanted.isNullOrBlank() || end == null) {
            badRequest(
                writer,
                body.exceptionOrNull(),
                "expected a JSON object with session, project and event (\"read\" or \"abandoned\")",
            )
            return
        }
        val project = matchProject(wanted, writer) ?: return
        // Everything the acknowledgement causes lives in the file that owns the editor side, see
        // review/SendReview.kt. This file only turns the outcome into a status field.
        writer.name("status").value(
            when (finishReview(project, session, end)) {
                AckOutcome.OK -> "ok"
                AckOutcome.NO_REVIEW -> "no-review"
                AckOutcome.NOT_SENT -> "not-sent"
            }
        )
    }

    /**
     * Hands the merged published file back in the response body, so an agent on another machine can
     * read remarks it has no filesystem access to. Answers `ready`, `waiting`, `no-review`,
     * `too-large`, `unknown-project`, `bad-request` or `failed`.
     *
     * **Changes nothing.** Not the store, not the review's phase, not the deadline. Fetching is not
     * reading: the `read` acknowledgement stays the only thing that marks a remark read, so a fetch whose
     * response is lost to a dead tunnel costs one retry rather than a delivery the IDE believes in and the
     * agent never got. It is therefore safe to call as often as the skill likes, which is what a poll
     * needs.
     *
     * A live review still in [ReviewPhase.Waiting] for this session answers `waiting` at once: nothing
     * has been published for it yet, so there is no file worth reading. Every other case — no live
     * review, or one already [ReviewPhase.Sent] — reads the one published file this project has, the
     * same file a publish writes and the same file a rejection lands in (`review/SendReview.kt`), so a
     * fetch after either still finds it. The file's own `review:` header field is what ties a batch to
     * the session that asked for it, since a publish or a rejection can happen with several sessions
     * having asked at different times: a file answering a different session, or none at all, is
     * `no-review`.
     */
    private fun handleFetch(request: FullHttpRequest, writer: JsonWriter) {
        val body = runCatching {
            gson.fromJson<FetchRequest?>(createJsonReader(request), FetchRequest::class.java)
        }
        val parsed = body.getOrNull()
        val session = parsed?.session
        val wanted = parsed?.project
        if (session.isNullOrBlank() || wanted.isNullOrBlank()) {
            badRequest(writer, body.exceptionOrNull(), "expected a JSON object with session and project")
            return
        }
        val project = matchProject(wanted, writer) ?: return
        val live = WaitingReviewService.getInstance(project).current()?.takeIf { it.sessionId == session }
        if (live != null && live.phase == ReviewPhase.Waiting) {
            // Nothing has been published yet. The skill's poll is supposed to come back.
            writer.name("status").value("waiting")
            return
        }
        // projectIdentity is a plain java.nio call (toRealPath plus a walk up the tree for .git),
        // never a VFS one, the same reason toRealPath() is allowed in this file elsewhere.
        val identity = projectIdentity(project)
        if (identity == null) {
            // Not `no-review`: that reads as "nothing is waiting, poll again", and the session would
            // then spend its whole deadline on what is really a failure it can do nothing about. The
            // project matched a moment ago, so its directory has gone since — a checkout deleted or
            // unmounted under the open project.
            writer.name("status").value("failed")
            writer.name("detail").value("the project's own directory could not be resolved")
            return
        }
        val file = handshakeDir().resolve(publishedName(identity.toString()))
        val read = try {
            readPublished(file, MAX_PUBLISHED_BYTES)
        } catch (e: IOException) {
            writer.name("status").value("failed")
            writer.name("detail").value(e.message ?: e.toString())
            return
        }
        when (read) {
            is PublishedRead.Absent ->
                // Nothing has been published or rejected for this project at all.
                writer.name("status").value("no-review")
            is PublishedRead.TooLarge -> {
                writer.name("status").value("too-large")
                writer.name("bytes").value(read.bytes)
                writer.name("limit").value(MAX_PUBLISHED_BYTES)
            }
            is PublishedRead.Content -> {
                val header = publishedHeaderOf(read.text)
                when {
                    header == null -> {
                        // A lie is not a better answer than an error: this plugin wrote every
                        // published file with a parseable header, so one that does not parse means
                        // something else touched it, or an older plugin's file is still there.
                        writer.name("status").value("failed")
                        writer.name("detail").value("the published file's header could not be parsed")
                    }
                    header.reviewSession == session -> {
                        writer.name("status").value("ready")
                        writer.name("content").value(read.text)
                        writer.name("nonce").value(header.nonce)
                        writer.name("bytes").value(read.bytes)
                    }
                    // The file exists but answers a different session's review, or none at all —
                    // a plain publish with no review waiting also reaches this branch.
                    else -> writer.name("status").value("no-review")
                }
            }
        }
    }

    /**
     * `POST /api/claude-remarks/published-read`. Acknowledges a batch a publish wrote to the merged
     * file, keyed to the nonce that batch's header carries rather than to a review session, since a
     * publish can happen with no review waiting at all. `session` is a name the calling session
     * invents for itself; the endpoint never hands one out and never checks it, only reports it back
     * on `already-read`, so a second session can be told who got there first.
     *
     * This handler does nothing but parse, call [matchProject] and [reportPublishedRead], and write
     * fields. Every consequence — marking the batch's remarks read, the balloon — lives in
     * review/PublishedAck.kt, for the same reason the ack action's consequences live in
     * review/SendReview.kt rather than here.
     */
    private fun handlePublishedRead(request: FullHttpRequest, writer: JsonWriter) {
        val body = runCatching {
            gson.fromJson<PublishedReadRequest?>(createJsonReader(request), PublishedReadRequest::class.java)
        }
        val parsed = body.getOrNull()
        val session = parsed?.session
        val wanted = parsed?.project
        val nonce = parsed?.nonce
        if (session.isNullOrBlank() || wanted.isNullOrBlank() || nonce.isNullOrBlank()) {
            badRequest(
                writer,
                body.exceptionOrNull(),
                "expected a JSON object with session, project and nonce",
            )
            return
        }
        val project = matchProject(wanted, writer) ?: return
        val answer = reportPublishedRead(project, nonce, session)
        when (answer.outcome) {
            PublishedAckOutcome.OK -> {
                writer.name("status").value("ok")
                writer.name("remarks").value(answer.remarks)
            }
            PublishedAckOutcome.ALREADY_READ -> {
                writer.name("status").value("already-read")
                writer.name("remarks").value(answer.remarks)
                writer.name("session").value(answer.readBy)
                writer.name("readAt").value(answer.readAt)
            }
            PublishedAckOutcome.UNKNOWN_BATCH -> writer.name("status").value("unknown-batch")
        }
    }

    /**
     * The refusal both actions and the unknown-action branch share, written once for the same reason
     * [matchProject] below factors out the unknown-project answer. [cause] is the JSON parse failure
     * if there was one: its message names the character that broke, which is more use to whoever is
     * fixing the caller than [fallbackDetail], which can only describe the shape that was wanted.
     */
    private fun badRequest(writer: JsonWriter, cause: Throwable?, fallbackDetail: String) {
        writer.name("status").value("bad-request")
        writer.name("detail").value(cause?.message ?: fallbackDetail)
    }

    /**
     * Which open project a request is about, or `unknown-project` written into [writer] and null.
     *
     * [projectIdentity] is what each open project is compared by, the same function that names the
     * handshake file and the published file. The skill sends what `git rev-parse --show-toplevel`
     * prints, so the two sides have to agree on both points that answer moves: the physical path,
     * for a symlinked checkout, and the repository root, for a project opened on a module below it.
     * Comparing base paths here answered `unknown-project` for a plainly open project in the second
     * case. It answers null for a project whose directory has been deleted, which still appears in
     * openProjects, and such a project is simply left out of the list.
     */
    private fun matchProject(wanted: String, writer: JsonWriter): Project? {
        val open = ProjectManager.getInstance().openProjects.mapNotNull { project ->
            val identity = projectIdentity(project.basePath) ?: return@mapNotNull null
            identity to project
        }
        val project = projectForPath(wanted, open)
        if (project == null) {
            writer.name("status").value("unknown-project")
            writer.name("open").beginArray()
            open.forEach { writer.value(it.first.toString()) }
            writer.endArray()
        }
        return project
    }
}
