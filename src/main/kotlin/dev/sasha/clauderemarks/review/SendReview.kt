package dev.sasha.clauderemarks.review

import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
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
    if (waiting.phase is ReviewPhase.Sent) {
        notifyRemarks(project, "Already sent. Waiting for Claude Code to read them.")
        return
    }

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
            // Read again, on the EDT, right before the write. prepare() ran off the EDT, and the
            // review it started from can have been rejected, acknowledged or expired in the
            // meantime — all three happen on other threads. Writing to the snapshot's path then
            // overwrites a rejection body, and the balloon claims a send nothing recorded.
            val live = WaitingReviewService.getInstance(project).current()
            if (live == null || live.sessionId != waiting.sessionId || live.phase is ReviewPhase.Sent) {
                notifyRemarks(
                    project,
                    "The review ended before the remarks could be sent. They are still pending.",
                )
                return@finishOnUiThread
            }
            // Built here, not inside the read action above: a non-blocking read action is
            // cancelled and re-run whenever a write action asks for the lock, so a write in there
            // would run again on every retry and leave a stray file behind each time.
            try {
                atomicWriteString(handoffFile(live.outputPath), prepared.markdown)
            } catch (e: IOException) {
                // Nothing marked sent, review stays waiting: the handover did not succeed.
                notifyRemarks(
                    project,
                    "The remarks could not be sent: ${e.message}",
                    NotificationType.ERROR,
                )
                return@finishOnUiThread
            }
            WaitingReviewService.getInstance(project).markSent(live.sessionId, prepared.ids)
            val count = prepared.ids.size
            notifyRemarks(
                project,
                "Wrote $count remark${if (count == 1) "" else "s"} for Claude Code. " +
                    "Waiting for it to read them.",
            )
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
 * The skill matches this as the file's first line to tell a rejection from a set of remarks before it
 * hands the body to a model. It is a wire format shared with a shell script, so it is not reworded
 * without changing `docs/skill/claude-remarks-review/SKILL.md` in the same commit.
 */
internal const val REJECTED_MARKER = "<!-- claude-remarks: rejected -->"

internal val REJECTION_BODY = """
    $REJECTED_MARKER

    The person rejected this review in the IDE. No remarks were sent and none are coming.
    Stop waiting for this file and do not start another review unless you are asked to.
""".trimIndent()

/**
 * The person answering "not now". `clear()` alone was the bug: it ended the review inside the IDE and
 * left the waiting session polling a path nothing would ever write, for its whole timeout.
 */
fun rejectWaitingReview(project: Project) {
    val waiting = WaitingReviewService.getInstance(project).current() ?: return
    if (waiting.phase is ReviewPhase.Sent) {
        // The handoff file already holds the remarks and the agent may already have read them.
        // Overwriting it with the rejection body would destroy remarks that were never delivered,
        // silently, and the agent would read a rejection instead of the review it was handed.
        notifyRemarks(project, "The remarks were already written. There is nothing left to reject.")
        WaitingReviewService.getInstance(project).clear(waiting.sessionId)
        return
    }
    try {
        atomicWriteString(handoffFile(waiting.outputPath), REJECTION_BODY)
        notifyRemarks(project, "Rejected the review. Claude Code will stop waiting.")
    } catch (e: IOException) {
        notifyRemarks(
            project,
            "The rejection could not be written: ${e.message}. " +
                "The Claude Code session will wait for its own timeout instead.",
            NotificationType.ERROR,
        )
    }
    // Cleared either way. The person asked for this review to end, and a banner they cannot dismiss
    // is worse than a session that waits for its own deadline. Named by session: the rejection above
    // was written to this review's path, so it must not close a different one that started since.
    WaitingReviewService.getInstance(project).clear(waiting.sessionId)
}

/**
 * The endpoint's only entry point into what an acknowledgement causes. The endpoint runs on a
 * netty IO thread, so both the store mutation and the balloon go inside [ApplicationManager]'s
 * `invokeLater`, the same way [WaitingReviewService]'s own `notifyPanel` does.
 */
fun finishReview(project: Project, session: String, end: ReviewEnd): AckOutcome {
    val (outcome, acted) = WaitingReviewService.getInstance(project).acknowledge(session, end)
    if (outcome == AckOutcome.OK && acted != null) reportLater(project, acted, end)
    return outcome
}

/**
 * The deadline backstop for the scheduled task in [WaitingReviewService]: the same transition as
 * an abandoned acknowledgement, plus the balloon. [now] is a parameter for the tests only, the same
 * reason [WaitingReviewService.expireIfStale] takes one.
 */
fun expireStaleReview(project: Project, now: Long = System.currentTimeMillis()) {
    val acted = WaitingReviewService.getInstance(project).expireIfStale(now) ?: return
    reportLater(project, acted, ReviewEnd.STALE)
}

/**
 * The disposal check sits inside the queued runnable, not in front of `invokeLater`: a project can
 * close between the two, and cancellation cannot catch a task already handed to a thread. Both the
 * store mutation and the balloon would throw on a disposed project.
 */
private fun reportLater(project: Project, acted: WaitingReviewState, end: ReviewEnd) {
    ApplicationManager.getApplication().invokeLater {
        if (!project.isDisposed) reportReviewEnd(project, acted, end)
    }
}

/**
 * For [ReviewEnd.READ] the remarks that were written are marked sent. For anything else — the
 * agent gave up, or the deadline passed — nothing in the store changes; what was written, if
 * anything, is still pending.
 */
private fun reportReviewEnd(project: Project, state: WaitingReviewState, end: ReviewEnd) {
    val phase = state.phase
    if (end == ReviewEnd.READ && phase is ReviewPhase.Sent) {
        markRemarksSent(project, phase.ids)
        val count = phase.ids.size
        notifyRemarks(project, "Claude Code read $count remark${if (count == 1) "" else "s"}.")
        return
    }
    when (phase) {
        is ReviewPhase.Sent -> {
            val count = phase.ids.size
            notifyRemarks(
                project,
                "Claude Code left without reading the $count remark${if (count == 1) "" else "s"} " +
                    "you sent. They are still pending.",
            )
        }
        ReviewPhase.Waiting -> notifyRemarks(project, "Claude Code stopped waiting for your remarks.")
    }
}

/**
 * Whether Send would do anything right now: a review waiting for its first send, and something
 * pending to put in it. One function, because the condition has two readers — this action and the
 * tool window's toolbar button — and two copies of it drift apart silently.
 */
fun canSend(project: Project): Boolean =
    WaitingReviewService.getInstance(project).current()?.phase is ReviewPhase.Waiting &&
        RemarkStore.getInstance(project).all().any { it.status == RemarkStatus.PENDING }

/**
 * Reachable without the tool window: from the Tools menu, from Search Everywhere, and from a
 * keymap entry the user assigns. Enabled only while a review is waiting and something is pending
 * to send it, the same pair `CopyAllRemarksAction` checks for its own condition.
 */
class SendReviewAction : AnAction() {

    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val project = e.project
        e.presentation.isEnabled = project != null && canSend(project)
    }

    override fun actionPerformed(e: AnActionEvent) {
        sendToWaitingReview(e.project ?: return)
    }
}
