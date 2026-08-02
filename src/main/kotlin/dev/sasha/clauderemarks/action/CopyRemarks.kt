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
import dev.sasha.clauderemarks.settings.RemarkSettings
import dev.sasha.clauderemarks.store.RemarkStore
import dev.sasha.clauderemarks.store.markRemarksSent
import dev.sasha.clauderemarks.store.resolveAll
import java.io.IOException
import java.util.concurrent.CancellationException

private const val NOTIFICATION_GROUP = "Claude Remarks"

/** The coalesce key standing in for "every pending remark", since null cannot be one. */
private const val ALL_PENDING = "all-pending"

/**
 * What the read action produced: the finished markdown, and which remarks went into it.
 *
 * Internal, not private, so CopyRemarksTest can check which remarks a copy takes.
 */
internal data class Prepared(val markdown: String, val ids: List<String>, val files: Int)

/**
 * Renders the chosen remarks into one markdown prompt, puts it on the clipboard, marks those
 * remarks sent and says so in a balloon.
 *
 * [ids] null means every pending remark. A non-null list is used as given, sent ones included, so
 * copying again after a paste went to the wrong place works.
 *
 * Sent remarks are not deleted. They stay listed in gray until Clear Sent.
 */
fun copyRemarks(project: Project, ids: Collection<String>?) {
    ReadAction.nonBlocking<Prepared> { prepare(project, ids) }
        .expireWith(project)
        // The id set is part of the key. Without it Copy All Pending and Copy Selected coalesce
        // against each other, so pressing the second one while the first is still running throws
        // the first copy away with nothing to show for it.
        .coalesceBy(::copyRemarks, project, ids?.toSet() ?: ALL_PENDING)
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
            markRemarksSent(project, prepared.ids)

            val what = "${prepared.ids.size} remark${if (prepared.ids.size == 1) "" else "s"} " +
                "across ${prepared.files} file${if (prepared.files == 1) "" else "s"}"
            val file = clipboard.file
            notifyRemarks(
                project,
                if (file == null) "Copied $what."
                else "$what was too large for the clipboard. Wrote $file and copied the path.",
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
 * The [ids] branch here is the whole sent lifecycle. Null means pending only, which is what Copy
 * All does. A list means exactly those ids, sent ones included, which is what Copy Selected does.
 */
internal fun prepare(project: Project, ids: Collection<String>?): Prepared {
    val wanted = ids?.toSet()
    val rows = resolveAll(project).filter { row ->
        if (wanted == null) row.remark.status == RemarkStatus.PENDING else row.remark.id in wanted
    }
    if (rows.isEmpty()) return Prepared("", emptyList(), 0)

    val collected = collectForPrompt(project, rows)
    return Prepared(
        markdown = renderPrompt(RemarkSettings.getInstance().promptHeader, collected),
        ids = rows.mapNotNull { it.remark.id },
        files = collected.map { it.path }.distinct().size,
    )
}

/**
 * Internal, because the tool window's toolbar reports its Clear Sent count the same way.
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
 * The same copy the tool window's toolbar does, reachable without the tool window: from the Tools
 * menu, from Search Everywhere, and from a keymap entry the user assigns.
 */
class CopyAllRemarksAction : AnAction() {

    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val project = e.project
        e.presentation.isEnabled = project != null &&
            RemarkStore.getInstance(project).all().any { it.status == RemarkStatus.PENDING }
    }

    override fun actionPerformed(e: AnActionEvent) {
        copyRemarks(e.project ?: return, null)
    }
}
