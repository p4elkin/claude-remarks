package dev.sasha.clauderemarks.review

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import dev.sasha.clauderemarks.action.notifyRemarks
import dev.sasha.clauderemarks.action.plural
import dev.sasha.clauderemarks.store.markRemarksRead
import java.util.UUID

/**
 * What an acknowledgement of a published batch found. OK is the ordinary case: this is the first
 * acknowledgement this plugin has seen for the batch. ALREADY_READ is not a failure — a lost HTTP
 * response makes a session retry, and the retry has to look the same whether it names its own
 * session (a plain retry) or a different one (a real anomaly the skill acts on). UNKNOWN_BATCH
 * covers both a nonce this plugin never recorded and one that fell off the remembered sixteen.
 */
internal enum class PublishedAckOutcome { OK, ALREADY_READ, UNKNOWN_BATCH }

/**
 * What looking a remark up inside a published batch found. OK means the nonce names a remembered
 * batch and that batch carried the remark. UNKNOWN_REMARK means the batch is real but never carried
 * that id — a session answering a question it invented, or one from an older batch. UNKNOWN_BATCH
 * covers both a nonce this plugin never recorded and one that fell off the remembered sixteen, the
 * same two cases [PublishedAckOutcome.UNKNOWN_BATCH] covers.
 *
 * This is a separate enum from [PublishedAckOutcome] rather than a shared one because the two
 * answers have different shapes: an acknowledgement can be ALREADY_READ, which a lookup can never
 * be, and a lookup can be UNKNOWN_REMARK, which an acknowledgement can never be.
 */
internal enum class BatchLookup { OK, UNKNOWN_REMARK, UNKNOWN_BATCH }

/**
 * One published batch: what it carried and who said they read it. Kept after it is acknowledged, not
 * removed — that is what lets a second session be told who got there first instead of being told the
 * batch is unknown.
 *
 * There is no "written at" field. Retention is by list position — the oldest of the sixteen is
 * dropped — so an age was written on every batch and read by nothing.
 */
internal data class PublishedBatch(
    val nonce: String,
    val ids: List<String>,
    val readBy: String? = null,
    val readAt: Long = 0,
)

/** What the endpoint needs to write its answer. [readBy] and [readAt] are set for ALREADY_READ. */
internal data class PublishedAckAnswer(
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
internal class PublishedBatchService {

    private val batches = mutableListOf<PublishedBatch>()

    /**
     * Mints the nonce and remembers the batch, and hands the nonce back for the header. Called
     * before the published file is written, so a fast acknowledgement never races this.
     *
     * The nonce is minted here rather than by the caller on purpose. Both call sites used to mint
     * one and pass it in, and "mint it, then record it, then write the file, in that order" was a
     * rule a third call site could get wrong silently. Now there is nothing to get wrong: a caller
     * cannot hold a nonce this service has not recorded.
     */
    @Synchronized
    internal fun record(ids: List<String>): String {
        val nonce = UUID.randomUUID().toString()
        batches.add(PublishedBatch(nonce, ids))
        while (batches.size > MAX_REMEMBERED_BATCHES) batches.removeAt(0)
        return nonce
    }

    /**
     * Drops a batch again, for the one case where the write it was recorded for never happened.
     * Recording before the write is what closes the race with a fast agent, and the cost of that
     * order is this: a failed write leaves a batch no file carries. Without this it would sit in the
     * remembered sixteen forever and push a real, readable batch out of them.
     */
    @Synchronized
    internal fun forget(nonce: String) {
        batches.removeAll { it.nonce == nonce }
    }

    /**
     * The batch to act on travels back only on [PublishedAckOutcome.OK]: [PublishedAckOutcome.ALREADY_READ]
     * and [PublishedAckOutcome.UNKNOWN_BATCH] both carry null, so a caller cannot mark a batch read
     * twice by accident. The whole batch rather than its ids alone, because the caller also needs the
     * review it answered.
     */
    @Synchronized
    internal fun acknowledge(nonce: String, session: String): Pair<PublishedAckAnswer, PublishedBatch?> {
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
        return PublishedAckAnswer(PublishedAckOutcome.OK, batch.ids.size) to batch
    }

    /**
     * Whether a remembered batch carried this remark, for the `answer` action to check before it
     * stores anything.
     *
     * **This reads and returns, and changes nothing.** It never stamps `readBy` the way
     * [acknowledge] does, and it never drops the batch. Answering a question must not consume the
     * batch, for two reasons: the batch still has to be acknowledgeable through `published-read`
     * afterwards, and several marked remarks in one batch each get their own answer, so the same
     * nonce is looked up once per answer.
     */
    @Synchronized
    internal fun batchCarries(nonce: String, remarkId: String): BatchLookup {
        val batch = batches.firstOrNull { it.nonce == nonce } ?: return BatchLookup.UNKNOWN_BATCH
        return if (remarkId in batch.ids) BatchLookup.OK else BatchLookup.UNKNOWN_REMARK
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
 * [ApplicationManager]'s `invokeLater`, the same way `review/ReviewLifecycle.kt`'s `reportLater` does,
 * checking `project.isDisposed` inside the queued runnable rather than in front of it, because a
 * project can close in the gap between the two.
 *
 * **The `invokeLater` is load-bearing and not decoration, for one reason.** It stops a fast
 * acknowledgement from landing between a publish's file write and the rest of that publish, which is
 * still running on the EDT: an acknowledgement landing before `markRemarksPublished` would set `READ`
 * and then have it immediately overwritten back to `PUBLISHED`. The window is only as wide as the few
 * statements a publish runs between its file write and that call, but a batch is recorded before the
 * write and the file carries its nonce, so a fast agent really can be acknowledging inside it.
 * Queueing on the EDT closes it: the acknowledgement runs behind the publish that is still finishing,
 * whichever order the two threads reached this point in.
 */
internal fun reportPublishedRead(project: Project, nonce: String, session: String): PublishedAckAnswer {
    val (answer, batch) = PublishedBatchService.getInstance(project).acknowledge(nonce, session)
    if (batch != null && batch.ids.isNotEmpty()) {
        val ids = batch.ids
        ApplicationManager.getApplication().invokeLater {
            if (project.isDisposed) return@invokeLater
            markRemarksRead(project, ids)
            val count = ids.size
            notifyRemarks(project, "Claude Code read $count remark${plural(count)}.")
        }
    }
    return answer
}
