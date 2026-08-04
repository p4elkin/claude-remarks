package dev.sasha.clauderemarks.action

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.Project
import com.intellij.util.concurrency.AppExecutorUtil
import dev.sasha.clauderemarks.model.RemarkStatus
import dev.sasha.clauderemarks.render.clipboardPayload
import dev.sasha.clauderemarks.render.collectForPrompt
import dev.sasha.clauderemarks.render.renderPrompt
import dev.sasha.clauderemarks.review.PublishedHeader
import dev.sasha.clauderemarks.review.handshakeDir
import dev.sasha.clauderemarks.review.projectIdentity
import dev.sasha.clauderemarks.review.writePublished
import dev.sasha.clauderemarks.settings.RemarkSettings
import dev.sasha.clauderemarks.store.RemarkStore
import dev.sasha.clauderemarks.store.headCommit
import dev.sasha.clauderemarks.store.markRemarksPublished
import dev.sasha.clauderemarks.store.resolveAll
import java.io.IOException
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.CancellationException

private val LOG = Logger.getInstance("dev.sasha.clauderemarks.action.PublishRemarks")

private const val NOTIFICATION_GROUP = "Claude Remarks"

/** The coalesce key standing in for "every pending remark", since null cannot be one. */
private const val ALL_PENDING = "all-pending"

/**
 * What the read action produced: the finished markdown, which remarks went into it, how many real
 * files it covers, the project's identity and the head commit.
 *
 * [root] is `projectIdentity`'s answer, the same thing the handshake file is named for — the git top
 * level, or the project base path outside a repository — not the base path on its own. [files]
 * counts only remarks that name a file: a general remark is about no file, so it is left out.
 *
 * [root] and [commit] are read inside the read action, off the EDT, the same place everything else
 * here is read. Both are null when [ids] resolves to nothing to publish, and [root] alone can be
 * null on its own when the identity does not resolve — in which case the published file is
 * never written, and only the hand check in section 12 of the phase 9 plan catches that: no unit
 * test drives the async publish pipeline (see [publishRemarks]'s own KDoc for why).
 *
 * Internal, not private, so PublishRemarksTest can check which remarks a publish takes.
 */
internal data class Prepared(
    val markdown: String,
    val ids: List<String>,
    val files: Int,
    val root: Path?,
    val commit: String?,
)

/**
 * Renders the chosen remarks into one markdown prompt, puts it on the clipboard, writes the same
 * prompt (with a header) to the published file, marks those remarks published and says so in one
 * balloon.
 *
 * [ids] null means every pending remark. A non-null list is used as given, published ones
 * included, so publishing again after a paste went to the wrong place works.
 *
 * Published remarks are not deleted. They stay listed in gray until Clear Handed Over.
 *
 * The order inside the UI callback is clipboard, then file, then mark, then balloon, and it is not
 * arbitrary:
 *  - the clipboard fails: nothing is marked and nothing is written, exactly as before the file
 *    existed. Nothing was handed over, so nothing says it was.
 *  - the file write fails after the clipboard succeeded, for any reason at all and not only an
 *    IOException: the remarks ARE still marked published,
 *    because PUBLISHED means "handed to a channel that cannot confirm a read", and the clipboard
 *    handover really happened. Refusing to mark would be a lie in the other direction. The balloon
 *    says so in the same sentence, through [publishMessage].
 *  - nothing pending: the existing early return below keeps the published file untouched. An empty
 *    published file is never written, because a reader could not tell "nothing to say" from
 *    "something went wrong".
 *
 * The async pipeline itself is not driven from a test, the same reason PublishRemarksTest's own
 * KDoc gives for the clipboard and the balloon: pumping a read action plus an EDT callback in a
 * light fixture buys a flaky test for very little. [publishMessage] is the pure part of this and is
 * what PublishRemarksTest exercises instead.
 */
fun publishRemarks(project: Project, ids: Collection<String>?) {
    ReadAction.nonBlocking<Prepared> { prepare(project, ids) }
        .expireWith(project)
        // The id set is part of the key. Without it Publish All Pending and Publish Selected
        // coalesce against each other, so pressing the second one while the first is still
        // running throws the first publish away with nothing to show for it.
        .coalesceBy(::publishRemarks, project, ids?.toSet() ?: ALL_PENDING)
        .finishOnUiThread(ModalityState.defaultModalityState()) { prepared ->
            if (prepared.ids.isEmpty()) {
                notifyRemarks(project, "No remarks to publish.")
                return@finishOnUiThread
            }
            // The payload is built here rather than inside the read action. A non-blocking read
            // action is cancelled and re-run whenever a write action asks for the lock, so a file
            // write in there runs again on every retry and leaves a temp file behind each time.
            // Below the inline limit this writes nothing at all; above it, one temp-file write.
            val clipboard = try {
                clipboardPayload(prepared.markdown)
                    .also { CopyPasteManager.copyTextToClipboard(it.text) }
            } catch (e: IOException) {
                notifyRemarks(
                    project,
                    "The remarks could not be written to a temporary file: ${e.message}",
                    NotificationType.ERROR,
                )
                return@finishOnUiThread
            } catch (e: IllegalStateException) {
                // The clipboard can be held by another process. Not marking the remarks published
                // here is the point: nothing was handed over.
                notifyRemarks(
                    project,
                    "The remarks could not be put on the clipboard: ${e.message}",
                    NotificationType.ERROR,
                )
                return@finishOnUiThread
            }

            // A null root means the project's identity did not resolve. Same shape as an
            // IOException: the publish still hands over the clipboard, but the published file is
            // not written, and the balloon says which of the two happened.
            val writeFailure = if (prepared.root == null) {
                "the project root did not resolve"
            } else {
                try {
                    // No batch is recorded yet: that is task 5's job, once PublishedBatchService
                    // exists. The review fields stay null/false here for the same reason — nothing
                    // in this task answers a waiting review yet.
                    val header = PublishedHeader(
                        nonce = UUID.randomUUID().toString(),
                        publishedAt = System.currentTimeMillis(),
                        commit = prepared.commit,
                        remarks = prepared.ids.size,
                        reviewSession = null,
                        reviewLabel = null,
                        rejected = false,
                    ).render()
                    // handshakeDir() is called here, inside the try, and is deliberately not a
                    // default argument on publishRemarks. Kotlin evaluates a default argument in
                    // the synthetic bridge, BEFORE the body runs, so anything it throws would
                    // escape every try in this function — the same trap
                    // store/RemarkEdits.kt's clearHandedOverRemarks names and rejects.
                    writePublished(prepared.root, header + "\n" + prepared.markdown, handshakeDir())
                    null
                } catch (e: ProcessCanceledException) {
                    // Never swallowed. The platform throws it to unwind, not to report a failure,
                    // and turning it into "the published file was not updated" would hide it.
                    throw e
                } catch (e: Exception) {
                    // Every failure, not only IOException. writePublished and atomicWriteString also
                    // throw unchecked ones — a SecurityException from a manager that refuses the
                    // directory, and whatever a particular filesystem raises for a name or an
                    // attribute it will not take. Any of those escaping here would skip
                    // markRemarksPublished and the balloon both: the clipboard handover already
                    // happened, so the remarks would stay pending and nothing would be said at all.
                    //
                    // The message goes into the balloon and the whole exception into the platform
                    // log. A permissions failure, a full disk and a name the filesystem refuses all
                    // arrive here, and one sentence carrying none of them leaves the person nothing
                    // to act on — store/RemarkEdits.kt's archive() reports its own write failure the
                    // same way, with the message in the balloon.
                    LOG.warn("the published file could not be written", e)
                    e.message ?: e.toString()
                }
            }

            markRemarksPublished(project, prepared.ids)

            notifyRemarks(
                project,
                publishMessage(prepared.ids.size, prepared.files, clipboard.file, writeFailure),
            )
        }
        .submit(AppExecutorUtil.getAppExecutorService())
        // Everything expensive runs inside the read action: resolving, reading Documents,
        // rendering. If any of it throws, finishOnUiThread never runs — nothing reaches the
        // clipboard, nothing is marked published, and no balloon appears. Without this the whole
        // action would look like it did nothing at all, with the reason only in the platform log.
        .onError { error ->
            // A run dropped by coalesceBy, or one expired with the project, arrives here too. That
            // is not a failure and stays quiet. Both types are checked because which of them the
            // platform throws depends on why the read action stopped.
            if (error !is ProcessCanceledException && error !is CancellationException) {
                notifyRemarks(
                    project,
                    "The remarks could not be prepared: ${error.message ?: error}",
                    NotificationType.ERROR,
                )
            }
        }
}

/**
 * Runs inside a read action, off the EDT, and does everything expensive: resolve, read the files
 * and render. It does no IO of its own, so a cancelled retry costs only the work, not a file.
 *
 * The [ids] branch here is the whole publish lifecycle. Null means pending only, which is what
 * Publish All does. A list means exactly those ids, published ones included, which is what
 * Publish Selected does.
 */
internal fun prepare(project: Project, ids: Collection<String>?): Prepared {
    val wanted = ids?.toSet()
    val rows = resolveAll(project).filter { row ->
        if (wanted == null) row.remark.status == RemarkStatus.PENDING else row.remark.id in wanted
    }
    if (rows.isEmpty()) return Prepared("", emptyList(), 0, null, null)

    val collected = collectForPrompt(project, rows)
    // projectIdentity, the one function ReviewHandshakeService and the endpoint's project matching
    // also go through, so the published file and the handshake file are named for the same thing and
    // a skill computes one hash for either. Read here, inside the read action, so the EDT callback in
    // publishRemarks does no filesystem lookup beyond the writes it already does. It answers null —
    // never throws — for a fixture-backed test's basePath that does not exist on the real
    // filesystem, and for any project whose directory vanished between opening and publishing.
    val root = projectIdentity(project)
    return Prepared(
        markdown = renderPrompt(RemarkSettings.getInstance().promptHeader, collected),
        ids = rows.mapNotNull { it.remark.id },
        // A general remark carries an empty path (collectForPrompt writes path.orEmpty()), and it is
        // about no file at all, so counting it would make the balloon say one file too many. filter
        // before distinct: publishing one general remark alone must say "across 0 files", not "1".
        files = collected.map { it.path }.filter { it.isNotEmpty() }.distinct().size,
        root = root,
        commit = root?.let { headCommit(it) },
    )
}

/**
 * The plural "s", or nothing for one. Ten balloons and dialogs across the plugin count remarks or
 * files in a sentence, and each of them had written this `if` out in full. Kept as the suffix rather
 * than a whole "N remarks" phrase, because the noun is not always "remark" ("N files") and is not
 * always the last word before it ("N published remarks").
 */
internal fun plural(n: Int): String = if (n == 1) "" else "s"

/**
 * The one balloon publishRemarks shows, in one sentence even when it has two things to say.
 *
 * [clipboardFile] is the oversized-payload temp file from [clipboardPayload], or null for the
 * common case where the prompt went straight to the clipboard. [writeFailure] is why the published
 * file was not written, or null when it was: the exception's own message for a failed write, and a
 * plain sentence when the write was skipped because the project root did not resolve. When there is
 * one, the sentence still reports the published count — the clipboard handover happened, so
 * PUBLISHED is not a lie — and adds why the published file itself was not updated, in the same
 * sentence rather than a second balloon. A reason, not just "it failed": a permissions problem, a
 * full disk and a refused name are all the same sentence without it.
 */
internal fun publishMessage(
    count: Int,
    files: Int,
    clipboardFile: Path?,
    writeFailure: String?,
): String {
    val what = "$count remark${plural(count)} across $files file${plural(files)}"
    val clipboardSentence = if (clipboardFile == null) "Published $what"
        else "$what was too large for the clipboard. Wrote $clipboardFile and copied the path"
    return if (writeFailure != null)
        "$clipboardSentence, but the published file was not updated: $writeFailure."
    else "$clipboardSentence."
}

/**
 * Internal, because the tool window's toolbar reports its Clear Handed Over count the same way.
 *
 * The type is a parameter so a failed copy can say so in red rather than in the same quiet blue as
 * a success.
 */
internal fun notifyRemarks(
    project: Project,
    message: String,
    type: NotificationType = NotificationType.INFORMATION,
) {
    NotificationGroupManager.getInstance()
        .getNotificationGroup(NOTIFICATION_GROUP)
        .createNotification(message, type)
        .notify(project)
}

/**
 * The same publish the tool window's toolbar does, reachable without the tool window: from the
 * Tools menu, from Search Everywhere, and from a keymap entry the user assigns.
 *
 * The class is renamed from CopyAllRemarksAction; the id it is registered under in plugin.xml,
 * ClaudeRemarks.CopyAll, is not — see the comment there for why.
 */
class PublishAllRemarksAction : AnAction() {

    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val project = e.project
        e.presentation.isEnabled = project != null &&
            RemarkStore.getInstance(project).all().any { it.status == RemarkStatus.PENDING }
    }

    override fun actionPerformed(e: AnActionEvent) {
        publishRemarks(e.project ?: return, null)
    }
}
