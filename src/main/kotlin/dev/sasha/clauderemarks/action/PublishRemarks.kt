package dev.sasha.clauderemarks.action

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.Project
import com.intellij.util.concurrency.AppExecutorUtil
import dev.sasha.clauderemarks.model.RemarkStatus
import dev.sasha.clauderemarks.render.clipboardPayload
import dev.sasha.clauderemarks.render.collectForPrompt
import dev.sasha.clauderemarks.render.renderPrompt
import dev.sasha.clauderemarks.review.handshakeDir
import dev.sasha.clauderemarks.review.publishedHeader
import dev.sasha.clauderemarks.review.writePublished
import dev.sasha.clauderemarks.settings.RemarkSettings
import dev.sasha.clauderemarks.store.RemarkStore
import dev.sasha.clauderemarks.store.headCommit
import dev.sasha.clauderemarks.store.markRemarksPublished
import dev.sasha.clauderemarks.store.projectRoot
import dev.sasha.clauderemarks.store.resolveAll
import java.io.IOException
import java.nio.file.Path
import java.util.concurrent.CancellationException

private const val NOTIFICATION_GROUP = "Claude Remarks"

/** The coalesce key standing in for "every pending remark", since null cannot be one. */
private const val ALL_PENDING = "all-pending"

/**
 * What the read action produced: the finished markdown, which remarks went into it, the project
 * root and the head commit.
 *
 * [root] and [commit] are read inside the read action, off the EDT, the same place everything else
 * here is read. Both are null when [ids] resolves to nothing to publish, and [root] alone can be
 * null on its own when the project root does not resolve — in which case the published file is
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
fun publishRemarks(project: Project, ids: Collection<String>?, dir: Path = handshakeDir()) {
    ReadAction.nonBlocking<Prepared> { prepare(project, ids) }
        .expireWith(project)
        // The id set is part of the key. Without it Publish All Pending and Publish Selected
        // coalesce against each other, so pressing the second one while the first is still
        // running throws the first publish away with nothing to show for it.
        .coalesceBy(::publishRemarks, project, ids?.toSet() ?: ALL_PENDING)
        .finishOnUiThread(ModalityState.defaultModalityState()) { prepared ->
            if (prepared.ids.isEmpty()) {
                notifyRemarks(project, "No remarks to copy.")
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
                // The clipboard can be held by another process. Not marking the remarks sent here
                // is the point: nothing was handed over.
                notifyRemarks(
                    project,
                    "The remarks could not be put on the clipboard: ${e.message}",
                    NotificationType.ERROR,
                )
                return@finishOnUiThread
            }

            // A null root means the project root did not resolve. Same shape as an IOException:
            // the publish still hands over the clipboard, but the published file is not written.
            val writeFailed = if (prepared.root == null) {
                true
            } else {
                try {
                    val header = publishedHeader(
                        System.currentTimeMillis(),
                        prepared.commit,
                        prepared.ids.size,
                    )
                    writePublished(prepared.root, header + "\n" + prepared.markdown, dir)
                    false
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
                    true
                }
            }

            markRemarksPublished(project, prepared.ids)

            notifyRemarks(
                project,
                publishMessage(prepared.ids.size, prepared.files, clipboard.file, writeFailed),
            )
        }
        .submit(AppExecutorUtil.getAppExecutorService())
        // Everything expensive runs inside the read action: resolving, reading Documents,
        // rendering. If any of it throws, finishOnUiThread never runs — nothing reaches the
        // clipboard, nothing is marked sent, and no balloon appears. Without this the whole action
        // would look like it did nothing at all, with the reason only in the platform log.
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
    // toRealPath(), the same call ReviewHandshakeService.start makes, so the two produce the same
    // string and a skill computes one hash for either file. Read here, inside the read action, so
    // the EDT callback in publishRemarks does no filesystem lookup beyond the writes it already
    // does. runCatching, the same guard ReviewHandshakeService.dispose uses for the same call: a
    // fixture-backed test's basePath does not always exist on the real filesystem, and any project
    // whose root vanished between opening and publishing should fall back to null, the same as no
    // root resolving at all, rather than throwing out of a read action.
    val root = runCatching { projectRoot(project)?.toNioPath()?.toRealPath() }.getOrNull()
    return Prepared(
        markdown = renderPrompt(RemarkSettings.getInstance().promptHeader, collected),
        ids = rows.mapNotNull { it.remark.id },
        files = collected.map { it.path }.distinct().size,
        root = root,
        commit = root?.let { headCommit(it) },
    )
}

/**
 * The plural "s", or nothing for one. Ten balloons and dialogs across the plugin count remarks or
 * files in a sentence, and each of them had written this `if` out in full. Kept as the suffix rather
 * than a whole "N remarks" phrase, because the noun is not always "remark" ("N files") and is not
 * always the last word before it ("N sent remarks").
 */
internal fun plural(n: Int): String = if (n == 1) "" else "s"

/**
 * The one balloon publishRemarks shows, in one sentence even when it has two things to say.
 *
 * [clipboardFile] is the oversized-payload temp file from [clipboardPayload], or null for the
 * common case where the prompt went straight to the clipboard. [writeFailed] is whether the
 * published file write failed or was skipped (a null project root). When it did, the sentence
 * still reports the published count — the clipboard handover happened, so PUBLISHED is not a lie —
 * and adds why the published file itself was not updated, in the same sentence rather than a
 * second balloon.
 */
internal fun publishMessage(
    count: Int,
    files: Int,
    clipboardFile: Path?,
    writeFailed: Boolean,
): String {
    val what = "$count remark${plural(count)} across $files file${plural(files)}"
    val clipboardSentence = if (clipboardFile == null) "Published $what"
        else "$what was too large for the clipboard. Wrote $clipboardFile and copied the path"
    return if (writeFailed) "$clipboardSentence, but the published file was not updated."
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
