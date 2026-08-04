package dev.sasha.clauderemarks.review

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.util.concurrency.AppExecutorUtil
import dev.sasha.clauderemarks.store.notifyRemarksChanged
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/** What an acknowledgement did, so the endpoint can answer honestly. */
enum class AckOutcome { OK, NO_REVIEW, NOT_SENT }

/**
 * What ended a review. STALE is the deadline; the other two come from the skill.
 *
 * STALE carries no behaviour of its own: `reportReviewEnd` in review/ReviewLifecycle.kt shows it exactly
 * the same balloon as ABANDONED, because from the person's side both mean the agent is gone. It
 * exists to name the caller, so the one place that could ever want to tell them apart — a log line,
 * or a future message — has the distinction available rather than having to reconstruct it.
 */
enum class ReviewEnd { READ, ABANDONED, STALE }

/**
 * How far a review has got. A review in [Sent] has been answered by a publish and carries the ids
 * that publish wrote, so the read acknowledgement knows what to mark read. A sealed pair rather than
 * a nullable id list, so "ids exist only after a publish answered it" is not a rule a caller can
 * forget.
 */
sealed interface ReviewPhase {
    // data object, not object: a failing assertEquals in WaitingReviewServiceTest then prints
    // "Waiting" rather than an identity hash, and it matches the data class next to it.
    data object Waiting : ReviewPhase
    data class Sent(val ids: List<String>) : ReviewPhase
}

/** One waiting review: the skill's session id, the label it sent, when it started and when it dies. */
data class WaitingReviewState(
    val sessionId: String,
    val label: String,
    val startedAt: Long,
    val deadlineAt: Long,
    val phase: ReviewPhase = ReviewPhase.Waiting,
) {
    /** Past its deadline, so the agent that asked for it is presumed gone. */
    fun isStale(now: Long = System.currentTimeMillis()): Boolean = now >= deadlineAt
}

/**
 * What a start request produces. Accepted also covers an honest retry of the same session id, which
 * only has its deadline moved forward. The endpoint turns both branches straight into a response:
 * "waiting" for Accepted, "conflict" with the other label and start time for Conflict.
 *
 * [Accepted.fresh] is false for that retry, and it exists for one caller: the endpoint opens the
 * files a review names, and a retry must not open a second diff window over the same changes on top
 * of the one the person is already reading.
 */
sealed class StartResult {
    data class Accepted(val state: WaitingReviewState, val fresh: Boolean = true) : StartResult()
    data class Conflict(val waiting: WaitingReviewState) : StartResult()
}

/**
 * Pure decision over plain data, no filesystem and no service involved.
 */
internal fun startOrConflict(
    current: WaitingReviewState?,
    session: String,
    label: String,
    // No default: WaitingReviewService.start (task 4) now always forwards its own required
    // deadlineSeconds, so a default here would only let a direct test call silently inherit a
    // value nobody chose — the exact thing a required parameter exists to prevent.
    deadlineSeconds: Long,
    now: Long = System.currentTimeMillis(),
): StartResult {
    // Hoisted, so the two branches that need it cannot drift apart and the bare 1000 is written once.
    val deadlineAt = now + deadlineSeconds * 1000
    fun accept() = StartResult.Accepted(WaitingReviewState(session, label, now, deadlineAt))
    return when {
        current == null -> accept()
        // Same-session retry is checked first: a retry keeps the review it already has, even a late
        // one, rather than being answered "conflict" by itself. The deadline moves forward with it —
        // handing back a deadline already in the past would answer "waiting" for a review the
        // scheduled expiry kills in the same millisecond, which is the kind of lie this phase exists
        // to remove.
        current.sessionId == session ->
            StartResult.Accepted(current.copy(deadlineAt = deadlineAt), fresh = false)
        // A different session gets in only when the current review is really dead.
        current.isStale(now) -> accept()
        else -> StartResult.Conflict(current)
    }
}

/**
 * At most one waiting review per project, held in memory only: an IDE restart clears it, so there
 * is no persisted field and no migration.
 *
 * [state] is @Volatile because it is written from a netty IO thread (the endpoint's start()) and
 * read from the EDT (the banner, through [current]). @Synchronized on [start] and [clear] guards the
 * whole read-decide-write sequence as one unit, so two start requests arriving together cannot both
 * decide there is no review and both install one.
 *
 * [current] is deliberately left unsynchronized, and that is the point of the lock rather than an
 * exception to it: every other method here holds the lock while a netty IO thread runs it, and the
 * EDT reads this field on every repaint. A blocking read would put the screen behind whatever a
 * request is doing. A stale read is harmless instead — the banner redraws again on the next
 * REMARKS_CHANGED regardless — so this is a written-down, accepted bend of "guard every mutable
 * field", not an oversight.
 *
 * **Three things here exist for the tests and for nothing else**, listed together so a fourth is not
 * added without noticing how much of this service's surface they already are: the `now` parameter on
 * [expireIfStale], `clear()` called with no session (both production callers name one, in
 * review/ReviewLifecycle.kt), and [expiryIsLive]. Each says at its own declaration why it is there.
 * Anything new of this shape belongs in this list — or better, does not get added.
 *
 * Everything but [current] and [getInstance] is `internal`: every caller is in `review/` or `ui/`
 * in this same module, and a plugin has no outside consumer to keep a wider surface for.
 */
@Service(Service.Level.PROJECT)
class WaitingReviewService(private val project: Project) : Disposable {

    @Volatile
    private var state: WaitingReviewState? = null

    // The task scheduled for the current state's deadline. Re-scheduled on every accepted start
    // (scheduleExpiry cancels the previous one first) and cancelled by endReview() — which every
    // path that ends a review goes through — and by dispose().
    @Volatile
    private var expiry: ScheduledFuture<*>? = null

    /**
     * Masks a stale review everywhere at once: the banner, the publish that answers it, and a second
     * start request all read this. One comparison of two longs — no lock, no IO, so the EDT never
     * blocks on it.
     */
    fun current(): WaitingReviewState? = state?.takeIf { !it.isStale() }

    /**
     * [deadlineSeconds] has no default: every existing caller has to say how long it is willing to
     * wait, rather than silently inheriting a number nobody chose for that call site.
     */
    @Synchronized
    internal fun start(
        session: String,
        label: String,
        deadlineSeconds: Long,
    ): StartResult {
        val previous = state
        val result = startOrConflict(previous, session, label, deadlineSeconds)
        if (result is StartResult.Accepted) {
            // A different session only reaches this branch when startOrConflict found the current
            // review stale (see the branch order there). That review is being replaced, not
            // continued, so it has to go through the same teardown every other ending does —
            // endReview() cancels its own scheduled task — before the new state and the new
            // scheduleExpiry below take its place.
            //
            // endReview() sets state to null right here; state = result.state below does not run
            // until a few lines down, in this same @Synchronized block. current() reads state
            // without synchronization on purpose (see the class KDoc), so an unsynchronized read
            // landing in that instant sees no review at all, rather than a review being replaced.
            // The old code wrote state once, with nothing in between, so this gap is new. See
            // "Known Issues" in docs/claude/design.md for why it is left as is.
            if (previous != null && previous.sessionId != session) endReview()
            state = result.state
            scheduleExpiry(result.state)
        }
        notifyPanel()
        return result
    }

    /**
     * Records that a publish answered this review and what it carried, without deciding anything
     * about the store. Uses [state] rather than [current]: the publish already checked the review was
     * live before it wrote, and a review that turned stale in the millisecond since still has to
     * record what was written, so an abandoned acknowledgement afterwards can name the count.
     *
     * [session] is what stops it stamping the wrong review. The publish renders off the EDT, so the
     * review it snapshotted can end and a different one start while it works; marking that new
     * review Sent would claim ids written into a file whose header names the old review.
     *
     * **Returns false when there was nothing left to stamp**, and the caller has to say so rather
     * than claim a handover. The write itself cannot be done under this lock — it is a filesystem
     * call and [current] must never block the EDT behind one — so the deadline task can end the
     * review in the gap between the publish's liveness check and this call. Then the file exists but
     * no `Sent` phase does, the later `ack read` is answered `no-review`, and the remarks stay
     * pending. That is the safe direction; a balloon saying the remarks were handed over is not.
     */
    @Synchronized
    internal fun markSent(session: String, ids: List<String>): Boolean {
        val acting = state ?: return false
        if (acting.sessionId != session) return false
        state = acting.copy(phase = ReviewPhase.Sent(ids))
        notifyPanel()
        return true
    }

    /**
     * Ends the review the skill is asking about, if there is one. Returns the state it acted on,
     * because the caller (review/ReviewLifecycle.kt) needs the ids and the count for the store mutation
     * and the balloon.
     *
     * **The pair's halves are coupled, and this is the invariant: the state is non-null exactly
     * when the outcome is [AckOutcome.OK].** A pair rather than a sealed type like [StartResult]
     * next to it, because [StartResult]'s two branches each carry a different state and need
     * different names for it, while here one branch carries a state and the other two carry
     * nothing — a sealed type would be three declarations to say "or null".
     */
    @Synchronized
    internal fun acknowledge(session: String, end: ReviewEnd): Pair<AckOutcome, WaitingReviewState?> {
        val acting = state
        if (acting == null || acting.sessionId != session) return AckOutcome.NO_REVIEW to null
        // A read acknowledgement for a file that was never written is a bug on one of the two
        // sides, not a transient failure, so it must not silently clear a live review.
        if (end == ReviewEnd.READ && acting.phase == ReviewPhase.Waiting) return AckOutcome.NOT_SENT to null
        return AckOutcome.OK to endReview()
    }

    /**
     * The deadline backstop for a screen nothing else repaints: [current] already hides a stale
     * review from every reader, but the banner only repaints when something publishes. Returns the
     * state it removed, or null if there was nothing to remove — no session argument, because a
     * newer review that replaced the one this task was scheduled for is not stale, and this is then
     * correctly a no-op.
     *
     * [now] is a parameter for the tests only, the same way [WaitingReviewState.isStale] takes one:
     * a test that wants a stale review can name a later instant instead of starting a review with a
     * zero deadline, which schedules a task that races the test's own assertion.
     */
    @Synchronized
    internal fun expireIfStale(now: Long = System.currentTimeMillis()): WaitingReviewState? {
        val acting = state ?: return null
        if (!acting.isStale(now)) return null
        return endReview()
    }

    /**
     * One `schedule` per accepted review, not a repeating poll — a repeating tick would run for the
     * whole IDE session for a feature idle almost all of the time. Cancelling the previous future
     * first means re-scheduling the same deadline on a same-session retry is harmless, with no
     * branch to get wrong.
     *
     * The scheduled body calls `expireStaleReview(project)` (review/ReviewLifecycle.kt), which is
     * [expireIfStale] plus the balloon a person left to read.
     */
    private fun scheduleExpiry(accepted: WaitingReviewState) {
        expiry?.cancel(false)
        expiry = AppExecutorUtil.getAppScheduledExecutorService().schedule(
            { if (!project.isDisposed) expireStaleReview(project) },
            (accepted.deadlineAt - System.currentTimeMillis()).coerceAtLeast(0),
            TimeUnit.MILLISECONDS,
        )
    }

    /**
     * The person ending the review from the banner, and the tests clearing the shared fixture.
     *
     * [session] is optional because the tests clear whatever is there, while a Reject has to name
     * the review the person was looking at: the rejection is written off a snapshot, and clearing
     * without checking could kill a different review that started in between.
     */
    @Synchronized
    internal fun clear(session: String? = null) {
        if (session != null && state?.sessionId != session) return
        endReview()
    }

    /**
     * The one teardown every ending shares: forget the review, stop its deadline task, repaint.
     * Returns the state that was removed, which `acknowledge` and `expireIfStale` hand to their
     * caller for the balloon. Three hand-rolled copies of these four lines is how `clear()` came to
     * leave the scheduled task queued while a comment said it cancelled it.
     *
     * On the stale-replacement branch in [start], this repaint runs, and then [start]'s own
     * unconditional repaint runs again a few lines later — two `invokeLater(notifyRemarksChanged)`
     * calls for one state transition, where every other path in this file fires exactly one. Left
     * as is on purpose: the panel just redraws twice instead of once, and removing the second call
     * would mean hand-rolling this function's four lines separately for that one branch, the exact
     * duplication this function exists to remove.
     */
    private fun endReview(): WaitingReviewState? {
        val acting = state
        state = null
        expiry?.cancel(false)
        expiry = null
        notifyPanel()
        return acting
    }

    /** Test-only window onto the scheduled task: cancelling it has no other observable effect. */
    internal fun expiryIsLive(): Boolean = expiry?.isDone == false

    // cancel(false), never cancel(true): the scheduled body is a field swap, so interrupting a
    // running one buys nothing and can only surprise the shared app-wide pool.
    override fun dispose() {
        expiry?.cancel(false)
    }

    // notifyRemarksChanged uses syncPublisher, so its listener runs on whichever thread called
    // this — a netty IO thread for start(). invokeLater moves it to the EDT, which is where the
    // panel's own listener expects to run. Named notifyPanel rather than notify(): the latter
    // silently overrides java.lang.Object.notify(), the monitor wakeup method, with the same JVM
    // signature — the compiler catches it as an "accidental override" error.
    private fun notifyPanel() {
        ApplicationManager.getApplication().invokeLater { notifyRemarksChanged(project) }
    }

    companion object {
        fun getInstance(project: Project): WaitingReviewService = project.service()
    }
}
