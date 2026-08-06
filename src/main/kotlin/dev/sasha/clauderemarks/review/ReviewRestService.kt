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
 * `POST /api/claude-remarks/fetch`, `POST /api/claude-remarks/published-read`,
 * `POST /api/claude-remarks/answer` and `POST /api/claude-remarks/open`. All four are actions a
 * Claude Code skill takes against one repository's open IDE, matched by [ReviewRestService.matchProject]
 * the same way each time. `fetch` hands the one merged published file's content back in the response
 * body itself, for an agent with no filesystem access to it — it changes nothing, so it is safe to
 * call as often as a poll likes. `published-read` acknowledges a batch a publish wrote to that file,
 * keyed to the nonce its header carries, and is the one action that marks the batch's remarks read.
 * `answer` is the one action that carries content *into* the IDE rather than a control signal: the
 * markdown a session wrote in reply to one remark it was shown, keyed to the same batch nonce for the
 * same reason a read acknowledgement is. `open` asks the IDE to open a real diff over a set of files a
 * session names, so a session that has just produced a diff can put those files in front of the
 * person before they write remarks about it.
 *
 * The answer is always HTTP 200 with a `status` field. `fetch` answers one of `ready`, `no-review`,
 * `too-large`, `unknown-project`, `bad-request`, `failed`; `published-read` answers one of `ok`,
 * `already-read`, `unknown-batch`, `unknown-project`, `bad-request`; `answer` answers one of `ok`,
 * `unknown-batch`, `unknown-remark`, `too-large`, `unknown-project`, `bad-request`; `open` answers one
 * of `ok`, `unknown-project`, `bad-request`. Real status codes stay reserved for what
 * `RestService.process` produces above this class — 403, 429, and 400 or 500 from its catch — so a
 * plumbing failure never looks like an application answer to the shell script reading it.
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

/** Gson fills this by reflection too. No `event` and no `label`: a fetch changes nothing. */
private class FetchRequest(
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
 * Gson fills these by reflection too. [nonce] is the published batch that carried the question, and
 * it is what proves the caller actually read that batch: a process that could guess a remark id but
 * has never read a batch cannot write into the IDE's own state. [remarkId] has to be one the batch
 * carried. [answer] is markdown.
 *
 * [session] is reported by nobody and checked against nothing, exactly as in [PublishedReadRequest]:
 * it is a name the calling session invents for itself, and it is required only so a malformed caller
 * is told so rather than silently accepted.
 */
private class AnswerRequest(
    val session: String? = null,
    val project: String? = null,
    val nonce: String? = null,
    val remarkId: String? = null,
    val answer: String? = null,
)

/**
 * Gson fills these by reflection too. [files] are paths relative to the repository root, the same
 * shape `git diff --name-only` prints — which is what the skill already computes. An absent or empty
 * list opens nothing, and that is a legitimate no-op rather than a refusal.
 */
private class OpenRequest(
    val project: String? = null,
    val files: List<String>? = null,
)

/**
 * The largest published file the fetch action will put in a response. A remark with its code context
 * is a few hundred bytes, so this is thousands of remarks — unreachable in ordinary use, and still a
 * bound on what one response allocates. Named rather than inlined because the skill's own message
 * quotes the number back to the person.
 */
private const val MAX_PUBLISHED_BYTES = 1_048_576L

/**
 * The largest answer the `answer` action will accept, in UTF-8 bytes. Sixteen kilobytes is roughly
 * two and a half thousand words, far more than a reading-pass question needs, and an answer goes
 * straight into `workspace.xml` — which the platform rewrites on every change and the tool window
 * resolves against, so an unbounded one is a cost paid on every save.
 *
 * Refused rather than truncated, the same argument [MAX_PUBLISHED_BYTES] makes in the other
 * direction: a markdown document cut in the middle looks complete to whoever reads it next.
 */
internal const val MAX_ANSWER_BYTES = 16_384

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
            "fetch" -> handleFetch(request, writer)
            "published-read" -> handlePublishedRead(request, writer)
            "answer" -> handleAnswer(request, writer)
            "open" -> handleOpen(request, writer)
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

    /**
     * Hands the merged published file back in the response body, so an agent on another machine can
     * read remarks it has no filesystem access to. Answers `ready`, `no-review`, `too-large`,
     * `unknown-project`, `bad-request` or `failed`.
     *
     * **Changes nothing.** Not the store. The `published-read` action is the only thing that marks a
     * remark read, so a fetch whose response is lost to a dead tunnel costs one retry rather than a
     * delivery the IDE believes in and the agent never got. It is therefore safe to call as often as
     * the skill likes, which is what a poll needs.
     *
     * **`no-review` means nothing has been published for this project at all.** The name is a
     * leftover: it kept the word from when a review was the only thing that ever published, and
     * renaming it now would break every deployed copy of the skill and the watcher for a cosmetic
     * gain.
     */
    private fun handleFetch(request: FullHttpRequest, writer: JsonWriter) {
        val body = runCatching {
            gson.fromJson<FetchRequest?>(createJsonReader(request), FetchRequest::class.java)
        }
        val parsed = body.getOrNull()
        val wanted = parsed?.project
        if (wanted.isNullOrBlank()) {
            badRequest(writer, body.exceptionOrNull(), "expected a JSON object with project")
            return
        }
        val project = matchProject(wanted, writer) ?: return
        // projectIdentity is a plain java.nio call (toRealPath plus a walk up the tree for .git),
        // never a VFS one, the same reason toRealPath() is allowed in this file elsewhere.
        val identity = projectIdentity(project)
        if (identity == null) {
            // Not `no-review`: that reads as "nothing has been published, poll again", and the
            // caller would then spend its whole deadline on what is really a failure it can do
            // nothing about. The project matched a moment ago, so its directory has gone since — a
            // checkout deleted or unmounted under the open project.
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
                // Nothing has been published for this project at all.
                writer.name("status").value("no-review")
            is PublishedRead.TooLarge -> {
                writer.name("status").value("too-large")
                writer.name("bytes").value(read.bytes)
                writer.name("limit").value(MAX_PUBLISHED_BYTES)
            }
            is PublishedRead.Content -> {
                val header = publishedHeaderOf(read.text)
                if (header == null) {
                    // A lie is not a better answer than an error: this plugin wrote every published
                    // file with a parseable header, so one that does not parse means something else
                    // touched it, or an older plugin's file is still there.
                    writer.name("status").value("failed")
                    writer.name("detail").value("the published file's header could not be parsed")
                } else {
                    writer.name("status").value("ready")
                    writer.name("content").value(read.text)
                    writer.name("nonce").value(header.nonce)
                    writer.name("bytes").value(read.bytes)
                }
            }
        }
    }

    /**
     * `POST /api/claude-remarks/published-read`. Acknowledges a batch a publish wrote to the merged
     * file, keyed to the nonce that batch's header carries. `session` is a name the calling session
     * invents for itself; the endpoint never hands one out and never checks it, only reports it back
     * on `already-read`, so a second session can be told who got there first.
     *
     * This handler does nothing but parse, call [matchProject] and [reportPublishedRead], and write
     * fields. Every consequence — marking the batch's remarks read, the balloon — lives in
     * review/PublishedAck.kt, kept out of this file for the same reason [handleAnswer]'s consequences
     * live in review/AnswerReceipt.kt.
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
     * `POST /api/claude-remarks/answer`. Stores the markdown a Claude Code session wrote in reply to
     * one remark it was shown. Answers `ok`, `unknown-batch`, `unknown-remark`, `too-large`,
     * `unknown-project` or `bad-request`.
     *
     * `nonce` is what proves the caller read the batch that carried the question, and `remarkId` has
     * to be one that batch carried. Together they are what stops a process that could guess an id
     * from writing into the IDE's own state. The token check above is the outer gate; this pair is
     * what keeps one batch's caller from writing about a different batch.
     *
     * **This deliberately does not look at `asksForAnswer`.** An answer to an unmarked remark is
     * `ok` and is stored. The flag decides what the *skill* does, and a second copy of that decision
     * here would be a second place that can disagree — with the store's copy winning silently over
     * the prompt the session actually read. An answer nobody asked for is a row a person can delete;
     * a refused answer that was correctly asked for is work thrown away.
     *
     * A second answer for a remark already answered is `ok` too. There is no separate status for a
     * replacement: the caller did nothing wrong, and replacing is the intended behaviour.
     *
     * Like [handlePublishedRead] above, this parses, calls [matchProject] and one function in
     * another file, and writes fields. Every consequence — resolving the remark, capturing its
     * position, building the answer, storing it, the balloon — lives in review/AnswerReceipt.kt, kept
     * out of this file for the same reason [handlePublishedRead]'s consequences live in
     * review/PublishedAck.kt.
     */
    private fun handleAnswer(request: FullHttpRequest, writer: JsonWriter) {
        val body = runCatching {
            gson.fromJson<AnswerRequest?>(createJsonReader(request), AnswerRequest::class.java)
        }
        val parsed = body.getOrNull()
        val session = parsed?.session
        val wanted = parsed?.project
        val nonce = parsed?.nonce
        val remarkId = parsed?.remarkId
        val markdown = parsed?.answer
        if (session.isNullOrBlank() || wanted.isNullOrBlank() || nonce.isNullOrBlank() ||
            remarkId.isNullOrBlank() || markdown.isNullOrBlank()
        ) {
            badRequest(
                writer,
                body.exceptionOrNull(),
                "expected a JSON object with session, project, nonce, remarkId and answer",
            )
            return
        }
        // Measured in bytes, not characters: the cap is about what lands in workspace.xml, and a
        // markdown answer full of code fences and non-ASCII is not one byte per character.
        val bytes = markdown.toByteArray(StandardCharsets.UTF_8).size
        if (bytes > MAX_ANSWER_BYTES) {
            writer.name("status").value("too-large")
            writer.name("bytes").value(bytes)
            writer.name("limit").value(MAX_ANSWER_BYTES)
            return
        }
        val project = matchProject(wanted, writer) ?: return
        writer.name("status").value(
            when (reportAnswer(project, nonce, remarkId, markdown)) {
                AnswerOutcome.OK -> "ok"
                AnswerOutcome.UNKNOWN_BATCH -> "unknown-batch"
                AnswerOutcome.UNKNOWN_REMARK -> "unknown-remark"
            }
        )
    }

    /**
     * `POST /api/claude-remarks/open`. Opens a real diff over the files a session names, the useful
     * half of what `start` used to do on its own first accept. Answers `ok`, `unknown-project` or
     * `bad-request`; there is no review to conflict with any more, so there is no `waiting` or
     * `conflict` here the way `start` once had.
     *
     * **`opened` counts paths accepted, not editors opened.** The file that owns the VFS and the
     * editor writes back through its own `invokeLater`, on purpose, so this response is written and
     * sent before a single editor has appeared. The count is the one that call returns, so the number
     * in the response and the work that produced it cannot come apart.
     *
     * Like [handlePublishedRead] above, this parses, calls [matchProject] and one function in
     * another file, and writes two fields. The file-opening call lives in review/OpenReviewFiles.kt,
     * for the same reason [handleAnswer]'s consequences live in review/AnswerReceipt.kt.
     */
    private fun handleOpen(request: FullHttpRequest, writer: JsonWriter) {
        val body = runCatching {
            gson.fromJson<OpenRequest?>(createJsonReader(request), OpenRequest::class.java)
        }
        val parsed = body.getOrNull()
        val wanted = parsed?.project
        if (wanted.isNullOrBlank()) {
            badRequest(writer, body.exceptionOrNull(), "expected a JSON object with project, and optionally files")
            return
        }
        val project = matchProject(wanted, writer) ?: return
        val opened = openReviewFiles(project, parsed.files)
        writer.name("status").value("ok")
        writer.name("opened").value(opened)
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
