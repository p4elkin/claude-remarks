package dev.sasha.clauderemarks.review

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import dev.sasha.clauderemarks.action.notifyRemarks
import dev.sasha.clauderemarks.action.plural
import dev.sasha.clauderemarks.store.markRemarksRead

/**
 * What an acknowledgement of a published batch found. OK is the ordinary case: this is the first
 * acknowledgement this plugin has seen for the batch. ALREADY_READ is not a failure — a lost HTTP
 * response makes a session retry, and the retry has to look the same whether it names its own
 * session (a plain retry) or a different one (a real anomaly the skill acts on). UNKNOWN_BATCH
 * covers both a nonce this plugin never recorded and one that fell off the remembered sixteen.
 */
enum class PublishedAckOutcome { OK, ALREADY_READ, UNKNOWN_BATCH }

/**
 * One published batch: what it carried, and who said they read it. Kept after it is acknowledged,
 * not removed — that is what lets a second session be told who got there first instead of being
 * told the batch is unknown.
 */
data class PublishedBatch(
    val nonce: String,
    val ids: List<String>,
    val writtenAt: Long,
    val readBy: String? = null,
    val readAt: Long = 0,
)

/** What the endpoint needs to write its answer. [readBy] and [readAt] are set for ALREADY_READ. */
data class PublishedAckAnswer(
    val outcome: PublishedAckOutcome,
    val remarks: Int = 0,
    val readBy: String? = null,
    val readAt: Long = 0,
)

/**
 * The plugin's memory of what it published, in memory only: an IDE restart clears it, so there is
 * no persisted field and no migration. [record] is meant to be called from the EDT, before the
 * published file is written, so a fast acknowledgement can never race a batch this service does not
 * know about yet. [acknowledge] is called from the endpoint's netty IO thread. Both are
 * `@Synchronized`; there is no unsynchronized reader here the way `WaitingReviewService.current()`
 * needs one, because nothing here is read from the EDT at a pace that cannot afford a lock.
 *
 * At most [MAX_REMEMBERED_BATCHES] batches are kept, oldest dropped first. A person who publishes
 * many times with nothing ever acknowledging eventually gets `unknown-batch` for the oldest ones,
 * which is the same shape as an IDE restart: the remarks stay published, and publishing again fixes
 * it.
 */
@Service(Service.Level.PROJECT)
class PublishedBatchService {

    private val batches = mutableListOf<PublishedBatch>()

    /** Called before the published file is written, so a fast acknowledgement never races this. */
    @Synchronized
    internal fun record(nonce: String, ids: List<String>) {
        batches.add(PublishedBatch(nonce, ids, System.currentTimeMillis()))
        while (batches.size > MAX_REMEMBERED_BATCHES) batches.removeAt(0)
    }

    /**
     * The ids to mark read travel back only on [PublishedAckOutcome.OK]: [PublishedAckOutcome.ALREADY_READ]
     * and [PublishedAckOutcome.UNKNOWN_BATCH] both carry null, so a caller cannot mark a batch read
     * twice by accident.
     */
    @Synchronized
    internal fun acknowledge(nonce: String, session: String): Pair<PublishedAckAnswer, List<String>?> {
        val index = batches.indexOfFirst { it.nonce == nonce }
        if (index < 0) return PublishedAckAnswer(PublishedAckOutcome.UNKNOWN_BATCH) to null
        val batch = batches[index]
        if (batch.readBy != null) {
            val answer = PublishedAckAnswer(
                PublishedAckOutcome.ALREADY_READ,
                batch.ids.size,
                batch.readBy,
                batch.readAt,
            )
            return answer to null
        }
        val now = System.currentTimeMillis()
        batches[index] = batch.copy(readBy = session, readAt = now)
        return PublishedAckAnswer(PublishedAckOutcome.OK, batch.ids.size) to batch.ids
    }

    /**
     * Test cleanup only, the same shape as `RemarkStore.clear()` and `WaitingReviewService.clear()`:
     * the light fixture project is shared across test methods and test classes, so a batch recorded
     * by one test is still remembered when the next one starts.
     */
    @Synchronized
    internal fun clear() {
        batches.clear()
    }

    companion object {
        private const val MAX_REMEMBERED_BATCHES = 16
        fun getInstance(project: Project): PublishedBatchService = project.service()
    }
}

/**
 * The endpoint's only entry point into what an acknowledgement of a published batch causes. The
 * endpoint runs on a netty IO thread, so both the store mutation and the balloon go inside
 * [ApplicationManager]'s `invokeLater`, the same way `review/SendReview.kt`'s `reportLater` does,
 * checking `project.isDisposed` inside the queued runnable rather than in front of it, because a
 * project can close in the gap between the two.
 *
 * **The `invokeLater` is load-bearing and not decoration.** It is what stops a fast acknowledgement
 * from landing between a publish's file write and its `markRemarksPublished` call, which would set
 * `READ` and then have it immediately overwritten back to `PUBLISHED`. Both run on the EDT, so the
 * acknowledgement queues behind the publish that is still finishing.
 */
fun reportPublishedRead(project: Project, nonce: String, session: String): PublishedAckAnswer {
    val (answer, ids) = PublishedBatchService.getInstance(project).acknowledge(nonce, session)
    if (ids != null) {
        ApplicationManager.getApplication().invokeLater {
            if (project.isDisposed) return@invokeLater
            markRemarksRead(project, ids)
            val count = ids.size
            notifyRemarks(project, "Claude Code read $count remark${plural(count)}.")
        }
    }
    return answer
}
