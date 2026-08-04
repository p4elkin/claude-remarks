package dev.sasha.clauderemarks.review

import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import dev.sasha.clauderemarks.action.notifyRemarks
import dev.sasha.clauderemarks.action.plural
import dev.sasha.clauderemarks.store.markRemarksRead
import java.io.IOException

/**
 * What a publish does about a waiting review. Null when none is waiting.
 *
 * Named so the publish reads one function rather than reaching into [WaitingReviewService]
 * itself.
 */
internal fun waitingReviewForPublish(project: Project): WaitingReviewState? =
    WaitingReviewService.getInstance(project).current()

/**
 * Records that this publish answered [session]'s review with [ids], and says what to add to the
 * publish's own balloon: null when the stamp succeeded and the review is simply waiting to read
 * them, or a sentence saying the review had already ended when [WaitingReviewService.markSent]
 * found nothing to stamp — the review it was meant to answer was rejected, acknowledged, or ran
 * past its deadline in the gap between the publish snapshotting it and this call. The remarks are
 * still published either way; only whether a review is left waiting for them differs.
 */
internal fun answerWaitingReview(project: Project, session: String, ids: List<String>): String? {
    val stamped = WaitingReviewService.getInstance(project).markSent(session, ids)
    return if (stamped) null
    else "The review it was meant to answer had already ended, so it is not waiting for these."
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
 * For [ReviewEnd.READ] the remarks that were written are marked read. For anything else — the
 * agent gave up, or the deadline passed — nothing in the store changes; what was written, if
 * anything, is still pending.
 *
 * One `when`, phase first: a review that was never sent has no ids to talk about at all, so testing
 * that once decides both the message and whether `end` matters, and the smart cast carries into the
 * two branches that do count something.
 */
private fun reportReviewEnd(project: Project, state: WaitingReviewState, end: ReviewEnd) {
    val phase = state.phase
    when {
        phase !is ReviewPhase.Sent ->
            notifyRemarks(project, "Claude Code stopped waiting for your remarks.")
        end == ReviewEnd.READ -> {
            markRemarksRead(project, phase.ids)
            val count = phase.ids.size
            notifyRemarks(project, "Claude Code read $count remark${plural(count)}.")
        }
        else -> {
            val count = phase.ids.size
            notifyRemarks(
                project,
                "Claude Code left without reading the $count remark${plural(count)} " +
                    "you sent. They are still pending.",
            )
        }
    }
}

