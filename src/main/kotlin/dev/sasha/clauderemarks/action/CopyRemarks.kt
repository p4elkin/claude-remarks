package dev.sasha.clauderemarks.action

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.Project
import com.intellij.util.concurrency.AppExecutorUtil
import dev.sasha.clauderemarks.model.RemarkStatus
import dev.sasha.clauderemarks.render.Clipboard
import dev.sasha.clauderemarks.render.clipboardPayload
import dev.sasha.clauderemarks.render.collectForPrompt
import dev.sasha.clauderemarks.render.renderPrompt
import dev.sasha.clauderemarks.settings.RemarkSettings
import dev.sasha.clauderemarks.store.markRemarksSent
import dev.sasha.clauderemarks.store.resolveAll

private const val NOTIFICATION_GROUP = "Claude Remarks"

/**
 * What the read action produced: the finished clipboard payload, and which remarks went into it.
 *
 * Internal, not private, so CopyRemarksTest can check which remarks a copy takes.
 */
internal data class Prepared(val clipboard: Clipboard, val ids: List<String>, val files: Int)

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
        .coalesceBy(::copyRemarks, project)
        .finishOnUiThread(ModalityState.defaultModalityState()) { prepared ->
            if (prepared.ids.isEmpty()) {
                notifyRemarks(project, "No remarks to copy.")
                return@finishOnUiThread
            }
            // Three cheap things on the EDT. The rendering and any file write already happened.
            CopyPasteManager.copyTextToClipboard(prepared.clipboard.text)
            markRemarksSent(project, prepared.ids)

            val what = "${prepared.ids.size} remark${if (prepared.ids.size == 1) "" else "s"} " +
                "across ${prepared.files} file${if (prepared.files == 1) "" else "s"}"
            val file = prepared.clipboard.file
            notifyRemarks(
                project,
                if (file == null) "Copied $what."
                else "$what was too large for the clipboard. Wrote $file and copied the path.",
            )
        }
        .submit(AppExecutorUtil.getAppExecutorService())
}

/**
 * Runs inside a read action, off the EDT, and does everything expensive: resolve, read the files,
 * render, and write the temp file if the payload is large.
 *
 * The [ids] branch here is the whole sent lifecycle. Null means pending only, which is what Copy
 * All does. A list means exactly those ids, sent ones included, which is what Copy Selected does.
 */
internal fun prepare(project: Project, ids: Collection<String>?): Prepared {
    val wanted = ids?.toSet()
    val rows = resolveAll(project).filter { row ->
        if (wanted == null) row.remark.status == RemarkStatus.PENDING else row.remark.id in wanted
    }
    if (rows.isEmpty()) return Prepared(Clipboard("", null), emptyList(), 0)

    val collected = collectForPrompt(project, rows)
    val markdown = renderPrompt(RemarkSettings.getInstance().promptHeader, collected)
    return Prepared(
        clipboard = clipboardPayload(markdown),
        ids = rows.mapNotNull { it.remark.id },
        files = collected.map { it.path }.distinct().size,
    )
}

/** Internal, because the toolbar in the next task reports its Clear Sent count the same way. */
internal fun notifyRemarks(project: Project, message: String) {
    NotificationGroupManager.getInstance()
        .getNotificationGroup(NOTIFICATION_GROUP)
        .createNotification(message, NotificationType.INFORMATION)
        .notify(project)
}
