package dev.sasha.clauderemarks.review

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import dev.sasha.clauderemarks.store.notifyRemarksChanged
import java.nio.file.Files
import java.nio.file.Path

/** One waiting review: the skill's session id, the label it sent, and where the handoff file will land. */
data class WaitingReviewState(
    val sessionId: String,
    val label: String,
    val outputPath: Path,
    val startedAt: Long,
)

/**
 * What a start request produces. Accepted also covers an honest retry of the same session id,
 * which gets the state already held back unchanged rather than a fresh one. The endpoint turns
 * both branches straight into a response: "waiting" with the output path for Accepted, "conflict"
 * with the other label and start time for Conflict.
 */
sealed class StartResult {
    data class Accepted(val state: WaitingReviewState) : StartResult()
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
    outputPath: () -> Path,
): StartResult = when {
    current == null ->
        StartResult.Accepted(WaitingReviewState(session, label, outputPath(), System.currentTimeMillis()))
    current.sessionId == session -> StartResult.Accepted(current)
    else -> StartResult.Conflict(current)
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
class WaitingReviewService(private val project: Project) {

    @Volatile
    private var state: WaitingReviewState? = null

    fun current(): WaitingReviewState? = state

    /**
     * [outputPath] is null in production: the directory is created only after the decision below
     * accepts, never before, so a conflict or a retry of the same session never leaks one.
     *
     * A caller-supplied [outputPath] exists for the tests: task 6 needs a review pointed at a path
     * it controls, once to read the written file back and once at a path whose parent is a regular
     * file so the write throws. Without this parameter that failure-path test — the only guard on
     * "nothing is marked sent unless the handover succeeded" — would be impossible to write.
     */
    @Synchronized
    internal fun start(session: String, label: String, outputPath: Path? = null): StartResult {
        val result = startOrConflict(state, session, label) {
            outputPath ?: Files.createTempDirectory("claude-remarks-review-")
        }
        if (result is StartResult.Accepted) state = result.state
        notifyPanel()
        return result
    }

    @Synchronized
    fun clear() {
        state = null
        notifyPanel()
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
