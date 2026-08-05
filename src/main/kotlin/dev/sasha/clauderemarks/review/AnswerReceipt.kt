package dev.sasha.clauderemarks.review

import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.util.concurrency.AppExecutorUtil
import dev.sasha.clauderemarks.action.notifyRemarks
import dev.sasha.clauderemarks.anchor.AnchorResult
import dev.sasha.clauderemarks.anchor.captureAnchor
import dev.sasha.clauderemarks.anchor.phraseAt
import dev.sasha.clauderemarks.model.AnswerState
import dev.sasha.clauderemarks.model.RemarkState
import dev.sasha.clauderemarks.store.RemarkStore
import dev.sasha.clauderemarks.store.StoredAnchor
import dev.sasha.clauderemarks.store.fileForStoredPath
import dev.sasha.clauderemarks.store.isAboutNoFile
import dev.sasha.clauderemarks.store.joinContext
import dev.sasha.clauderemarks.store.projectRoot
import dev.sasha.clauderemarks.store.recordAnswer
import dev.sasha.clauderemarks.store.resolveStored
import dev.sasha.clauderemarks.store.storedAnchorOf
import java.util.UUID

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
    ReadAction.nonBlocking<AnswerState> { buildAnswer(project, remarkId, markdown) }
        .expireWith(project)
        .finishOnUiThread(ModalityState.defaultModalityState()) { answer ->
            // recordAnswer goes through the store's upsert, so THIS is the moment a second answer
            // for the same remark replaces the first — with the fresh anchor buildAnswer just
            // captured, so the replacement points at where the code is now.
            recordAnswer(project, answer)
            // One sentence, not the answer itself: an answer is paragraphs and a balloon is a line.
            notifyRemarks(project, "Claude Code answered a remark.")
        }
        .submit(AppExecutorUtil.getAppExecutorService())
    return AnswerOutcome.OK
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
 */
internal fun buildAnswer(project: Project, remarkId: String, markdown: String): AnswerState {
    val answer = AnswerState().also {
        it.id = UUID.randomUUID().toString()
        it.remarkId = remarkId
        it.markdown = markdown
        it.answeredAt = System.currentTimeMillis()
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
 */
private fun freshAnchorFor(project: Project, remark: RemarkState): StoredAnchor? {
    if (isAboutNoFile(remark)) return null
    val root = projectRoot(project) ?: return null
    val file = fileForStoredPath(root, remark.path!!) ?: return null
    val document = FileDocumentManager.getInstance().getDocument(file) ?: return null
    val resolved = resolveStored(root, storedAnchorOf(remark), "remark ${remark.id}")
    if (resolved.result is AnchorResult.Orphaned) return null
    val lines = document.text.split("\n")
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
