package dev.sasha.clauderemarks.review

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.util.concurrency.AppExecutorUtil
import dev.sasha.clauderemarks.store.notifyRemarksChanged
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/** What an acknowledgement did, so the endpoint can answer honestly. */
enum class AckOutcome { OK, NO_REVIEW, NOT_SENT }

/** What ended a review. STALE is the deadline; the other two come from the skill. */
enum class ReviewEnd { READ, ABANDONED, STALE }

/**
 * How far a review has got. A review in [Sent] has had its handoff file written and carries the ids
 * that were written, so the read acknowledgement knows what to mark sent. A sealed pair rather than
 * a nullable id list, so "ids exist only after a send" is not a rule a caller can forget.
 */
sealed interface ReviewPhase {
    object Waiting : ReviewPhase
    data class Sent(val ids: List<String>) : ReviewPhase
}

/** One waiting review: the skill's session id, the label it sent, and where the handoff file will land. */
data class WaitingReviewState(
    val sessionId: String,
    val label: String,
    val outputPath: Path,
    val startedAt: Long,
    val deadlineAt: Long,
    val phase: ReviewPhase = ReviewPhase.Waiting,
) {
    /** Past its deadline, so the agent that asked for it is presumed gone. */
    fun isStale(now: Long = System.currentTimeMillis()): Boolean = now >= deadlineAt
}

/**
 * What a start request produces. Accepted also covers an honest retry of the same session id, which
 * keeps the output path it was already given and only has its deadline moved forward. The endpoint
 * turns both branches straight into a response: "waiting" with the output path for Accepted,
 * "conflict" with the other label and start time for Conflict.
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
 * Pure decision over plain data, no filesystem and no service involved. [outputPath] is a supplier
 * rather than a plain value so that the expensive call it wraps — Files.createTempDirectory in
 * production — runs only on the branch that actually accepts. The reuse and conflict branches
 * below never call it, which is what stops a retried or refused request from leaking a directory.
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
    outputPath: () -> Path,
): StartResult {
    fun accept() =
        StartResult.Accepted(WaitingReviewState(session, label, outputPath(), now, now + deadlineSeconds * 1000))
    return when {
        current == null -> accept()
        // Same-session retry is checked first: a retry keeps its own output path, even a late one, so
        // a slow skill never starts polling a directory the plugin has just replaced. The deadline
        // moves forward with it — handing back a deadline already in the past would answer "waiting"
        // for a review the scheduled expiry kills in the same millisecond, which is the kind of lie
        // this phase exists to remove.
        current.sessionId == session ->
            StartResult.Accepted(current.copy(deadlineAt = now + deadlineSeconds * 1000), fresh = false)
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
 * read from the EDT (the toolbar's update(), through [current]). @Synchronized on [start] and
 * [clear] guards the whole read-decide-create-write sequence as one unit. An AtomicReference was
 * rejected on purpose: its updateAndGet re-runs the update lambda on contention, and that lambda
 * creates a temp directory, so a retried compare-and-set would create two.
 *
 * [current] is deliberately left unsynchronized. [start] holds the lock across
 * Files.createTempDirectory, and current() must never block the EDT on that filesystem call. A
 * stale read here is harmless — the toolbar redraws again on the next REMARKS_CHANGED regardless —
 * so this is a written-down, accepted bend of "guard every mutable field", not an oversight.
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
     * Masks a stale review everywhere at once: the Send button, the banner, and a second start
     * request all read this. One comparison of two longs — no lock, no IO, so the EDT never
     * blocks on it.
     */
    fun current(): WaitingReviewState? = state?.takeIf { !it.isStale() }

    /**
     * [outputPath] is null in production: the directory is created only after the decision below
     * accepts, never before, so a conflict or a retry of the same session never leaks one.
     *
     * A caller-supplied [outputPath] exists for the tests: task 6 needs a review pointed at a path
     * it controls, once to read the written file back and once at a path whose parent is a regular
     * file so the write throws. Without this parameter that failure-path test — the only guard on
     * "nothing is marked sent unless the handover succeeded" — would be impossible to write.
     *
     * [deadlineSeconds] has no default: every existing caller has to say how long it is willing to
     * wait, rather than silently inheriting a number nobody chose for that call site.
     */
    @Synchronized
    internal fun start(
        session: String,
        label: String,
        deadlineSeconds: Long,
        outputPath: Path? = null,
    ): StartResult {
        val result = startOrConflict(state, session, label, deadlineSeconds) {
            outputPath ?: Files.createTempDirectory("claude-remarks-review-")
        }
        if (result is StartResult.Accepted) {
            state = result.state
            scheduleExpiry(result.state)
        }
        notifyPanel()
        return result
    }

    /**
     * Records that the handoff file was written and what it carries, without deciding anything
     * about the store. Uses [state] rather than [current]: the send already checked the review was
     * live before it wrote, and a review that turned stale in the millisecond since still has to
     * record what was written, so an abandoned acknowledgement afterwards can name the count.
     *
     * [session] is what stops it stamping the wrong review. The send renders off the EDT, so the
     * review it snapshotted can end and a different one start while it works; marking that new
     * review Sent would claim ids whose file was written into the old review's directory.
     */
    @Synchronized
    fun markSent(session: String, ids: List<String>) {
        val acting = state ?: return
        if (acting.sessionId != session) return
        state = acting.copy(phase = ReviewPhase.Sent(ids))
        notifyPanel()
    }

    /**
     * Ends the review the skill is asking about, if there is one. Returns the state it acted on,
     * because the caller (review/SendReview.kt) needs the ids and the count for the store mutation
     * and the balloon.
     */
    @Synchronized
    fun acknowledge(session: String, end: ReviewEnd): Pair<AckOutcome, WaitingReviewState?> {
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
    fun expireIfStale(now: Long = System.currentTimeMillis()): WaitingReviewState? {
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
     * The scheduled body calls `expireStaleReview(project)` (review/SendReview.kt), which is
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
    fun clear(session: String? = null) {
        if (session != null && state?.sessionId != session) return
        endReview()
    }

    /**
     * The one teardown every ending shares: forget the review, stop its deadline task, repaint.
     * Returns the state that was removed, which `acknowledge` and `expireIfStale` hand to their
     * caller for the balloon. Three hand-rolled copies of these four lines is how `clear()` came to
     * leave the scheduled task queued while a comment said it cancelled it.
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
