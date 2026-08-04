package dev.sasha.clauderemarks.review

import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.Project
import dev.sasha.clauderemarks.action.notifyRemarks
import dev.sasha.clauderemarks.action.plural
import dev.sasha.clauderemarks.store.markRemarksRead
import java.nio.file.Path

// A review's whole life outside the endpoint: a publish answering it, a rejection, what the read
// acknowledgement causes, and the deadline that ends an abandoned one. This file was SendReview.kt
// until phase 10 deleted sendToWaitingReview, canSend and SendReviewAction — nothing in it sends any
// more, so the name follows what is left. Rule 5 in CLAUDE.md is why the acknowledgement's
// consequences live here rather than in ReviewRestService.kt.
//
// Written as a line comment rather than a KDoc block on purpose: a KDoc here would attach itself to
// the LOG below instead of to the file.
private val LOG = Logger.getInstance("dev.sasha.clauderemarks.review.ReviewLifecycle")

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
 * The body of a rejection batch, once the header above it already says `rejected: yes` — that field
 * is what a reader matches now, not a marker line of its own. Before phase 10 this began with its own
 * `REJECTED_MARKER` line; the header's `rejected:` field replaced it, so the marker and the constant
 * that held it are gone.
 */
internal val REJECTION_BODY = """
    The person rejected this review in the IDE. No remarks were sent and none are coming.
    Stop waiting for this file and do not start another review unless you are asked to.
""".trimIndent()

/**
 * The person answering "not now". `clear()` alone was the bug: it ended the review inside the IDE and
 * left the waiting session polling a path nothing would ever write, for its whole timeout.
 *
 * A rejection is now a batch like any other: a fresh nonce, `rejected = true`, `remarks = 0`, and the
 * waiting review's session and label, written to the one published file through [writePublished] —
 * the same file a publish writes and the same file the fetch action reads. [PublishedBatchService]
 * records it with an empty id list too, so that "every write to the published file records a batch"
 * holds without exception, even though there is nothing here for an acknowledgement to mark read.
 * That is why [reportPublishedRead] answers `ok` to this nonce and marks nothing: the batch is real,
 * it simply carries no remarks.
 *
 * [dir] is a parameter, not a default argument resolving [handshakeDir]. Kotlin evaluates a default
 * argument in the synthetic bridge, before this function's body runs, so anything it throws would
 * escape the try below — the same trap `store/RemarkEdits.kt`'s `clearHandedOverRemarks` already
 * names. Null means the real directory, resolved inside the try. Only the tests pass a path.
 */
internal fun rejectWaitingReview(project: Project, dir: Path? = null) {
    val waiting = WaitingReviewService.getInstance(project).current() ?: return
    if (waiting.phase is ReviewPhase.Sent) {
        // The published file already holds this batch and the agent may already have read it.
        // Overwriting it with the rejection would take that batch away from any session that has
        // not read it yet, and pressing Reject after publishing does not clearly mean "take it back".
        notifyRemarks(project, "The remarks were already published. There is nothing left to reject.")
        WaitingReviewService.getInstance(project).clear(waiting.sessionId)
        return
    }
    val root = projectIdentity(project)
    if (root == null) {
        // Same shape as a publish whose project root does not resolve: nothing is written, and the
        // review is still cleared rather than left as a banner the person cannot dismiss.
        notifyRemarks(
            project,
            "The rejection could not be written: the project root did not resolve. " +
                "The Claude Code session will wait for its own timeout instead.",
            NotificationType.ERROR,
        )
        WaitingReviewService.getInstance(project).clear(waiting.sessionId)
        return
    }
    // Recorded before the write, same reason a publish's batch is: a fast acknowledgement must
    // never race a batch this service does not know about yet. The nonce comes back from record()
    // rather than being minted here, so a recorded batch and the header's nonce cannot drift apart.
    val nonce = PublishedBatchService.getInstance(project).record(emptyList(), waiting.sessionId)
    try {
        val header = PublishedHeader(
            nonce = nonce,
            publishedAt = System.currentTimeMillis(),
            commit = null,
            remarks = 0,
            reviewSession = waiting.sessionId,
            reviewLabel = waiting.label,
            rejected = true,
        ).render()
        writePublished(root, header + "\n" + REJECTION_BODY, dir ?: handshakeDir())
        notifyRemarks(project, "Rejected the review. Claude Code will stop waiting.")
    } catch (e: ProcessCanceledException) {
        // Never swallowed. The platform throws it to unwind, not to report a failure, and turning it
        // into "the rejection could not be written" would hide it.
        throw e
    } catch (e: Exception) {
        // Every failure, not only IOException, for the reason action/PublishRemarks.kt's own write
        // gives: writePublished and atomicWriteString also throw unchecked ones — a SecurityException
        // from a manager that refuses the directory, and whatever a particular filesystem raises for
        // a name or an attribute it will not take. The consequences here are worse than there. An
        // unchecked throw escaping this function would skip both the forget below and the clear at
        // the end of it, so the batch would burn one of the sixteen remembered slots and the banner
        // would stay on screen with no way to dismiss it.
        //
        // The batch above names a file that does not exist, so it is dropped again. Left in place it
        // would burn one of the sixteen remembered slots and could push a real, readable batch out.
        PublishedBatchService.getInstance(project).forget(nonce)
        LOG.warn("the rejection could not be written", e)
        notifyRemarks(
            project,
            "The rejection could not be written: ${e.message ?: e}. " +
                "The Claude Code session will wait for its own timeout instead.",
            NotificationType.ERROR,
        )
    }
    // Cleared either way. The person asked for this review to end, and a banner they cannot dismiss
    // is worse than a session that waits for its own deadline. Named by session: the rejection above
    // was written for this review, so it must not close a different one that started since.
    WaitingReviewService.getInstance(project).clear(waiting.sessionId)
}

/**
 * The endpoint's only entry point into what an acknowledgement causes. The endpoint runs on a
 * netty IO thread, so both the store mutation and the balloon go inside [ApplicationManager]'s
 * `invokeLater`, the same way [WaitingReviewService]'s own `notifyPanel` does.
 */
internal fun finishReview(project: Project, session: String, end: ReviewEnd): AckOutcome {
    val (outcome, acted) = WaitingReviewService.getInstance(project).acknowledge(session, end)
    if (outcome == AckOutcome.OK && acted != null) reportLater(project, acted, end)
    return outcome
}

/**
 * The deadline backstop for the scheduled task in [WaitingReviewService]: the same transition as
 * an abandoned acknowledgement, plus the balloon. [now] is a parameter for the tests only, the same
 * reason [WaitingReviewService.expireIfStale] takes one.
 */
internal fun expireStaleReview(project: Project, now: Long = System.currentTimeMillis()) {
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
