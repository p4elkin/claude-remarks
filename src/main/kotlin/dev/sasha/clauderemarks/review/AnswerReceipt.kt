package dev.sasha.clauderemarks.review

import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.intellij.util.concurrency.AppExecutorUtil
import dev.sasha.clauderemarks.action.notifyRemarks
import dev.sasha.clauderemarks.action.plural
import dev.sasha.clauderemarks.anchor.AnchorResult
import dev.sasha.clauderemarks.anchor.captureAnchor
import dev.sasha.clauderemarks.anchor.phraseAt
import dev.sasha.clauderemarks.anchor.resolveWithPhrase
import dev.sasha.clauderemarks.model.AnswerState
import dev.sasha.clauderemarks.model.RemarkState
import dev.sasha.clauderemarks.store.RemarkStore
import dev.sasha.clauderemarks.store.StoredAnchor
import dev.sasha.clauderemarks.store.anchorOf
import dev.sasha.clauderemarks.store.fileForStoredPath
import dev.sasha.clauderemarks.store.isAboutNoFile
import dev.sasha.clauderemarks.store.joinContext
import dev.sasha.clauderemarks.store.projectRoot
import dev.sasha.clauderemarks.store.recordAnswer
import dev.sasha.clauderemarks.store.storedAnchorOf
import java.util.UUID
import java.util.concurrent.CancellationException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * What the `answer` action found before it accepted anything. OK is the ordinary case: the nonce
 * names a remembered batch, that batch carried the remark, and the store write is queued.
 * UNKNOWN_BATCH and UNKNOWN_REMARK are [BatchLookup]'s two refusals, carried through unchanged —
 * this enum exists rather than reusing [BatchLookup] because the endpoint's answer is about the
 * whole request, and a later refusal that has nothing to do with a batch lookup would have nowhere
 * to go in a type named after one.
 */
internal enum class AnswerOutcome { OK, UNKNOWN_BATCH, UNKNOWN_REMARK }

/**
 * The endpoint's only entry point into what an answer causes, the same shape
 * [reportPublishedRead] has for `published-read` and `finishReview` has for `ack`. Rule 5 in
 * `CLAUDE.md` is why this lives here rather than in the file holding the endpoint: that file runs on
 * a netty IO thread and may not reach the VFS at all, and building an answer does.
 *
 * The outcome comes back synchronously, because that is what the response body carries. Everything
 * else is queued, so the netty thread is never the thread that resolves a file.
 *
 * **The queued half is a read action off the EDT, not a plain `invokeLater`.** [buildAnswer] resolves
 * the remark against its file, and `FileDocumentManager.getDocument` on a file with no editor open
 * loads it from disk — on the EDT that is a stall a person can feel on a large file. The publish
 * pipeline, the gutter sync and the tool window refresh are all this same shape.
 *
 * **The ordering race `reportPublishedRead` guards against does not apply here**, which is why this
 * does not have to queue on the EDT for correctness the way that one does. An answer touches no
 * `status` field and no review phase; it writes only to a list nothing else writes. There is nothing
 * for it to race with a publish that is still finishing.
 *
 * **It can still race another answer to the same question**, and that one is guarded here. Two
 * `answer` POSTs for the same remark, close together, are two of these pipelines in flight at once,
 * and the store's upsert replaces on `remarkId` — so whichever pipeline finishes last would win, no
 * matter which request arrived first. That would silently overwrite a newer answer with an older
 * one, and replacement on `remarkId` is exactly the mechanism re-asking rests on. The stamp is
 * therefore taken **here, before anything is scheduled** ([nextReceivedAt]), and carried into
 * [buildAnswer] as the answer's `answeredAt`; `RemarkStore.RemarksState.putAnswer` then refuses a
 * write whose stamp is older than the one already stored. Ordering is decided by arrival, and
 * completion order stops mattering.
 *
 * **This never touches [WaitingReviewService].** The action is keyed to a published batch's nonce
 * exactly the way `published-read` is, so it works with no review ever started — which is the
 * ordinary case for a question asked through the Ask Claude gesture.
 *
 * The lookup is [PublishedBatchService.batchCarries] and not `acknowledge`: answering must not
 * consume the batch, because the batch still has to be acknowledgeable afterwards and because
 * several marked remarks in one batch each get their own answer.
 */
internal fun reportAnswer(
    project: Project,
    nonce: String,
    remarkId: String,
    markdown: String,
): AnswerOutcome {
    when (PublishedBatchService.getInstance(project).batchCarries(nonce, remarkId)) {
        BatchLookup.UNKNOWN_BATCH -> return AnswerOutcome.UNKNOWN_BATCH
        BatchLookup.UNKNOWN_REMARK -> return AnswerOutcome.UNKNOWN_REMARK
        BatchLookup.OK -> Unit
    }
    // Before anything is scheduled: this is the request's place in line, and the whole point is
    // that it is fixed now rather than whenever the read action happens to finish.
    val receivedAt = nextReceivedAt()
    ReadAction.nonBlocking<AnswerState> { buildAnswer(project, remarkId, markdown, receivedAt) }
        .expireWith(project)
        .finishOnUiThread(ModalityState.defaultModalityState()) { answer ->
            // recordAnswer goes through the store's upsert, so THIS is the moment a second answer
            // for the same remark replaces the first — with the fresh anchor buildAnswer just
            // captured, so the replacement points at where the code is now. The upsert compares
            // `answeredAt`, so an answer that gets here after a newer one already landed is
            // dropped there rather than overwriting it.
            recordAnswer(project, answer)
            // One sentence, not the answer itself: an answer is paragraphs and a balloon is a line.
            announceAnswer(project)
        }
        .submit(AppExecutorUtil.getAppExecutorService())
        // The outcome went back to the caller synchronously, and the skill reads `ok` as "stored,
        // nothing to report". Without this the answer would be discarded with the reason only in the
        // platform log, after the endpoint already said it was fine. `action/PublishRemarks.kt` adds
        // the same handler to the same pipeline shape for the same reason, and losing an answer is
        // worse than losing a publish: a publish can simply be pressed again.
        .onError { error ->
            // A read action expired with the project is not a failure and stays quiet. Both types
            // are checked because which of them the platform throws depends on why it stopped.
            if (error !is ProcessCanceledException && error !is CancellationException) {
                notifyRemarks(
                    project,
                    "An answer from Claude Code could not be stored: ${error.message ?: error}",
                    NotificationType.ERROR,
                )
            }
        }
    return AnswerOutcome.OK
}

private val LAST_RECEIVED_AT = AtomicLong(0L)

/**
 * The stamp that fixes one answer request's place in line, taken the moment the request is accepted.
 *
 * Wall-clock milliseconds, because it is also the answer's `answeredAt` — the field the Answers group
 * sorts newest-first on and the field the history archive prints. Reusing it is why this fix adds no
 * property to [AnswerState]: a second field ordering the same records would be a second thing to
 * serialize, to migrate and to keep in step with the first.
 *
 * **Strictly increasing, not merely non-decreasing**, and that is the part worth reading twice. Two
 * requests can land inside the same millisecond, and `System.currentTimeMillis()` would then hand both
 * the same number — leaving the tie to be settled by whichever read action finished first, which is
 * the race this whole guard exists to remove. So the value is bumped past the last one handed out when
 * the clock has not moved: every accepted request gets a number nothing else has, in arrival order.
 * The same bump is what keeps ordering sane when the system clock steps backwards.
 *
 * One counter for the whole application rather than one per project. The numbers are only ever
 * compared between answers to the same remark, which is inside one project by construction, so a
 * shared counter costs nothing and needs no per-project service to live in.
 */
internal fun nextReceivedAt(): Long {
    while (true) {
        val previous = LAST_RECEIVED_AT.get()
        val now = System.currentTimeMillis()
        val next = if (now > previous) now else previous + 1
        if (LAST_RECEIVED_AT.compareAndSet(previous, next)) return next
    }
}

/**
 * How long a burst of answers is collected before the balloon for it is shown.
 *
 * A batch carrying several marked remarks is answered request by request, seconds apart at most, and
 * one balloon per answer stacks a column of identical sentences over the editor. `published-read`
 * already aggregates its own acknowledgement into one counted sentence
 * ([reportPublishedRead]); this is the same idea across requests rather than inside one.
 */
private const val ANSWER_BALLOON_WINDOW_MS = 1_000L

private val PENDING_ANSWER_BALLOONS =
    Key.create<AtomicInteger>("dev.sasha.clauderemarks.pendingAnswerBalloons")

/**
 * Counts one answer and, if no balloon is already pending for this project, schedules the one
 * balloon that will report the whole burst.
 *
 * **EDT only.** Every caller is inside `finishOnUiThread`, which is what makes the counter's
 * creation safe without a lock; the count itself is an [AtomicInteger] because the scheduled task
 * reads and resets it from a pooled thread.
 */
private fun announceAnswer(project: Project) {
    val pending = project.getUserData(PENDING_ANSWER_BALLOONS)
        ?: AtomicInteger().also { project.putUserData(PENDING_ANSWER_BALLOONS, it) }
    // Not the first one in this window: the balloon already scheduled will count this too.
    if (pending.getAndIncrement() > 0) return
    AppExecutorUtil.getAppScheduledExecutorService().schedule(
        {
            val count = pending.getAndSet(0)
            if (count <= 0) return@schedule
            ApplicationManager.getApplication().invokeLater(
                {
                    if (!project.isDisposed) {
                        notifyRemarks(project, "Claude Code answered $count remark${plural(count)}.")
                    }
                },
                ModalityState.defaultModalityState(),
            )
        },
        ANSWER_BALLOON_WINDOW_MS,
        TimeUnit.MILLISECONDS,
    )
}

/**
 * The answer record, built inside a read action.
 *
 * **A remark that is gone is not a reason to drop the answer.** The answer is the thing worth
 * keeping, and losing it because the person deleted the question in the seconds in between would be
 * exactly the silent loss this plugin refuses everywhere else. Such an answer is stored with no
 * question and no anchor, so it still gets its row in the tree — it simply has no code to point at.
 *
 * **The anchor is captured fresh, not copied field for field.** An answer usually outlives its
 * remark, so it starts from where the code is *now* and has the whole search radius ahead of it
 * rather than whatever its remark has already spent. [freshAnchorFor] returns null for the two cases
 * that have nothing fresh to capture — a general remark, and one whose code is gone — and then the
 * remark's stored fields are copied as they are.
 *
 * **[receivedAt] is a parameter and has no default**, so nobody can build an answer without saying
 * where its place in line came from. It is taken by [reportAnswer] through [nextReceivedAt] when the
 * request arrives, not read from the clock here: this function runs on a pooled thread whenever the
 * read action gets scheduled, and a stamp taken at that moment would order two answers by completion
 * rather than by arrival — which is the whole defect.
 */
internal fun buildAnswer(
    project: Project,
    remarkId: String,
    markdown: String,
    receivedAt: Long,
): AnswerState {
    val answer = AnswerState().also {
        it.id = UUID.randomUUID().toString()
        it.remarkId = remarkId
        it.markdown = markdown
        it.answeredAt = receivedAt
    }
    // all(), not a mutator: guard 3 in CLAUDE.md exempts it by name as a read-only accessor.
    val remark = RemarkStore.getInstance(project).all().firstOrNull { it.id == remarkId }
        ?: return answer
    answer.question = remark.text
    answer.commit = remark.commit
    val stored = freshAnchorFor(project, remark) ?: storedAnchorOf(remark)
    answer.path = stored.path
    answer.startLine = stored.startLine
    answer.endLine = stored.endLine
    answer.startColumn = stored.startColumn
    answer.endColumn = stored.endColumn
    answer.textHash = stored.textHash
    answer.contextBefore = stored.contextBefore
    answer.contextAfter = stored.contextAfter
    answer.phrase = stored.phrase
    return answer
}

/**
 * Where the remark's code sits now, captured as a position of its own, or null when there is nothing
 * fresh to capture: a remark about no file, a project root that does not resolve, a path that names
 * no file under it, a file with no `Document`, or a resolve that orphaned.
 *
 * Must be called inside a read action, for the same reason `resolveAll` must be: the `Document`
 * lookup can load the file from disk.
 *
 * The columns come from the resolve rather than from the stored record, because a sub-line remark
 * whose line was reindented keeps its phrase and moves its columns — the same reason
 * [dev.sasha.clauderemarks.store.ResolvedRemark] carries them. The phrase is then read back out of
 * the file at those columns, so the answer's own phrase describes the text it is actually anchored
 * to rather than the text the remark was written against.
 *
 * `resolveWithPhrase` directly rather than `resolveStored`: the file, the `Document` and its split
 * lines are all already in hand here, and `resolveStored` would look the file and the `Document` up
 * again and split the text a second time. What it decides on top of that — the no-file case and the
 * two lookup refusals — is what the three lines above already decided.
 */
private fun freshAnchorFor(project: Project, remark: RemarkState): StoredAnchor? {
    if (isAboutNoFile(remark)) return null
    val root = projectRoot(project) ?: return null
    val file = fileForStoredPath(root, remark.path!!) ?: return null
    val document = FileDocumentManager.getInstance().getDocument(file) ?: return null
    val lines = document.text.split("\n")
    val resolved = resolveWithPhrase(
        anchorOf(remark),
        lines,
        remark.phrase,
        remark.startColumn,
        remark.endColumn,
    )
    if (resolved.result is AnchorResult.Orphaned) return null
    val anchor = captureAnchor(lines, resolved.result.startLine, resolved.result.endLine)
    return StoredAnchor(
        path = remark.path,
        startLine = anchor.startLine,
        endLine = anchor.endLine,
        startColumn = resolved.startColumn,
        endColumn = resolved.endColumn,
        textHash = anchor.textHash,
        contextBefore = joinContext(anchor.contextBefore),
        contextAfter = joinContext(anchor.contextAfter),
        phrase = phraseAt(lines, anchor.startLine, anchor.endLine, resolved.startColumn, resolved.endColumn),
    )
}
