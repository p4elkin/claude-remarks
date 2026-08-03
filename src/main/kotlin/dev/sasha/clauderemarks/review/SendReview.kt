package dev.sasha.clauderemarks.review

import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.Project
import com.intellij.util.concurrency.AppExecutorUtil
import dev.sasha.clauderemarks.action.Prepared
import dev.sasha.clauderemarks.action.notifyRemarks
import dev.sasha.clauderemarks.action.prepare
import dev.sasha.clauderemarks.model.RemarkStatus
import dev.sasha.clauderemarks.store.RemarkStore
import dev.sasha.clauderemarks.store.markRemarksSent
import java.io.IOException
import java.util.concurrent.CancellationException

/**
 * The same pipeline as Copy All Pending — [prepare] is not re-rendered — with a different
 * destination: the file the waiting session is polling for instead of the clipboard.
 *
 * Does nothing if no review is waiting. The toolbar button and the banner's link only appear
 * while one is, but the Tools-menu action and any keymap entry a user assigns can still be
 * pressed with none waiting.
 */
fun sendToWaitingReview(project: Project) {
    val waiting = WaitingReviewService.getInstance(project).current() ?: return

    ReadAction.nonBlocking<Prepared> { prepare(project, null) }
        .expireWith(project)
        // Named explicitly, not CopyRemarks.kt's ALL_PENDING: a shared key would make Send and
        // Copy All coalesce against each other, so pressing one while the other is still running
        // would throw the first away with nothing to show for it.
        .coalesceBy(::sendToWaitingReview, project)
        .finishOnUiThread(ModalityState.defaultModalityState()) { prepared ->
            if (prepared.ids.isEmpty()) {
                // The agent is still waiting on purpose. Sending an empty file would tell it the
                // person had finished.
                notifyRemarks(project, "No remarks to send. The review stays waiting.")
                return@finishOnUiThread
            }
            // Built here, not inside the read action above: a non-blocking read action is
            // cancelled and re-run whenever a write action asks for the lock, so a write in there
            // would run again on every retry and leave a stray file behind each time.
            try {
                atomicWriteString(handoffFile(waiting.outputPath), prepared.markdown)
            } catch (e: IOException) {
                // Nothing marked sent, review stays waiting: the handover did not succeed.
                notifyRemarks(
                    project,
                    "The remarks could not be sent: ${e.message}",
                    NotificationType.ERROR,
                )
                return@finishOnUiThread
            }
            markRemarksSent(project, prepared.ids)
            WaitingReviewService.getInstance(project).clear()
            val count = prepared.ids.size
            notifyRemarks(project, "Sent $count remark${if (count == 1) "" else "s"} to Claude Code.")
        }
        .submit(AppExecutorUtil.getAppExecutorService())
        .onError { error ->
            // A run dropped by coalesceBy, or one expired with the project, arrives here too, and
            // is not a failure.
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
 * Reachable without the tool window: from the Tools menu, from Search Everywhere, and from a
 * keymap entry the user assigns. Enabled only while a review is waiting and something is pending
 * to send it, the same pair `CopyAllRemarksAction` checks for its own condition.
 */
class SendReviewAction : AnAction() {

    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val project = e.project
        e.presentation.isEnabled = project != null &&
            WaitingReviewService.getInstance(project).current() != null &&
            RemarkStore.getInstance(project).all().any { it.status == RemarkStatus.PENDING }
    }

    override fun actionPerformed(e: AnActionEvent) {
        sendToWaitingReview(e.project ?: return)
    }
}
