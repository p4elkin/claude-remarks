package dev.sasha.clauderemarks.store

import com.intellij.notification.NotificationType
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.Project
import com.intellij.util.messages.Topic
import dev.sasha.clauderemarks.action.notifyRemarks
import dev.sasha.clauderemarks.anchor.captureAnchor
import dev.sasha.clauderemarks.anchor.phraseAt
import dev.sasha.clauderemarks.model.AnswerState
import dev.sasha.clauderemarks.model.RemarkState
import dev.sasha.clauderemarks.model.RemarkStatus
import java.nio.file.Path
import java.util.UUID

/**
 * Told when the remark list changed, or when something changed that makes the current view of it
 * out of date.
 */
fun interface RemarksListener {
    fun remarksChanged()
}

/**
 * Project level, and not broadcast. The default direction is TO_CHILDREN; NONE says plainly that
 * this event belongs to one project's bus and goes nowhere else.
 */
@Topic.ProjectLevel
val REMARKS_CHANGED: Topic<RemarksListener> =
    Topic.create("Claude remarks changed", RemarksListener::class.java, Topic.BroadcastDirection.NONE)

/**
 * These twelve functions are the whole way production code reaches a remark or an answer. Eleven
 * of them change one — nothing calls the store's own mutators directly any more, and CLAUDE.md's
 * rule 3 greps to keep that true — and the twelfth, notifyRemarksChanged, changes nothing
 * itself: it is what every one of the eleven calls to announce the change.
 *
 * Nine of the eleven change a remark. The two added in phase 11, recordAnswer and deleteAnswer,
 * change an answer instead, and they live here for exactly the same reason: the tool window draws
 * both lists and the gutter paints both, so both have to redraw after either changes.
 *
 * The reason is not tidiness. The tool window and the gutter both have to redraw after any change,
 * and pairing the mutation with the notification in one function is what stops a caller doing one
 * without the other. The store itself stays out of it so that RemarkStoreStateTest can keep
 * building RemarkStore() directly, with no fixture, in fourteen places.
 */

/**
 * Captures the anchor for [range] out of [lines] and stores a new remark. Returns what was stored.
 *
 * [asksForAnswer] defaults to false, so the ordinary entry points — the shortcut, the intention, the
 * preview's menu item — read exactly as they did before. Only the Ask Claude gesture passes true.
 */
fun addRemark(
    project: Project,
    path: String,
    lines: List<String>,
    range: IntRange,
    text: String,
    startColumn: Int = 0,
    endColumn: Int = 0,
    asksForAnswer: Boolean = false,
): RemarkState {
    val anchor = captureAnchor(lines, range.first, range.last)
    val remark = RemarkState().apply {
        this.id = UUID.randomUUID().toString()
        this.path = path
        this.startLine = anchor.startLine
        this.endLine = anchor.endLine
        this.startColumn = startColumn
        this.endColumn = endColumn
        this.text = text
        this.asksForAnswer = asksForAnswer
        this.createdAt = System.currentTimeMillis()
        this.textHash = anchor.textHash
        this.contextBefore = joinContext(anchor.contextBefore)
        this.contextAfter = joinContext(anchor.contextAfter)
        this.phrase = phraseAt(lines, anchor.startLine, anchor.endLine, startColumn, endColumn)
        // Two small file reads on the EDT, once per remark, at human pace — for a loose ref, which
        // is the normal case. The ceiling is higher: after `git gc` there is no loose ref file and
        // the third read is all of `packed-refs`, read into a String and split, which on a large
        // repository can be megabytes. Still once per remark at typing speed, so it stays here
        // rather than moving off the EDT, but the ceiling is written down rather than implied.
        // No cache either: one keyed on the HEAD file's timestamp would be more code than it saves.
        this.commit = project.basePath?.let { headCommit(Path.of(it)) }
    }
    RemarkStore.getInstance(project).add(remark)
    notifyRemarksChanged(project)
    return remark
}

/**
 * A remark about the whole change, not about any one file: no path, no line range beyond the `0`/`0`
 * whole-line sentinel, no `textHash` and no context, because there is no code behind it to hash or
 * quote. `store/RemarkResolver.kt`'s `isAboutNoFile` is what every reader asks to recognize one
 * again. The commit stamp is still captured, the same as [addRemark], because it says what the
 * whole change was measured against even when no single file is named.
 */
fun addGeneralRemark(project: Project, text: String, asksForAnswer: Boolean = false): RemarkState {
    val remark = RemarkState().apply {
        this.id = UUID.randomUUID().toString()
        this.text = text
        this.asksForAnswer = asksForAnswer
        this.createdAt = System.currentTimeMillis()
        this.commit = project.basePath?.let { headCommit(Path.of(it)) }
    }
    RemarkStore.getInstance(project).add(remark)
    notifyRemarksChanged(project)
    return remark
}

fun editRemark(project: Project, id: String, text: String) {
    if (RemarkStore.getInstance(project).edit(id, text)) notifyRemarksChanged(project)
}

fun deleteRemark(project: Project, id: String) {
    if (RemarkStore.getInstance(project).remove(id)) notifyRemarksChanged(project)
}

fun markRemarksPublished(project: Project, ids: Collection<String>) {
    if (RemarkStore.getInstance(project).markPublished(ids.toSet()) > 0) notifyRemarksChanged(project)
}

/**
 * Only an agent's own acknowledgement may call this, and there is one route that can be one: a
 * `published-read` acknowledgement over POST /api/claude-remarks/published-read, keyed to a
 * published batch's nonce, in `review/PublishedAck.kt`'s `reportPublishedRead`. A publish is not
 * that, however many times it runs. So two files may call this — that one and this one — and
 * CLAUDE.md's guard 6 keeps every other call site out.
 *
 * Guard 6 restricting this function to two callers is also what makes [RemarkState.readAt] a
 * single-writer field: `RemarkStore.markRead`, underneath this call, is the only place that
 * stamps it, and this function is the only way anything reaches `RemarkStore.markRead` at all.
 */
fun markRemarksRead(project: Project, ids: Collection<String>) {
    if (RemarkStore.getInstance(project).markRead(ids.toSet()) > 0) notifyRemarksChanged(project)
}

/**
 * Marks remarks as asking Claude Code for an answer, or stops them asking.
 *
 * Two places set this, and both are the person's own choice: the Ask Claude gesture, which writes a
 * remark already carrying the flag, and the Ask for an Answer toggle in the shared menu, which turns
 * remarks already written into questions and back. There is deliberately no one-writer rule on this
 * the way there is on [markRemarksRead] — READ is a claim about what an agent did, and this is not.
 */
fun setRemarkAsksForAnswer(project: Project, ids: Collection<String>, asksForAnswer: Boolean) {
    if (RemarkStore.getInstance(project).setAsksForAnswer(ids.toSet(), asksForAnswer) > 0) {
        notifyRemarksChanged(project)
    }
}

/**
 * Stores one answer a Claude Code session sent back, replacing any answer already held for the same
 * remark, and tells the tool window and the gutter to redraw.
 *
 * It is called recordAnswer and not addAnswer, and the name is load-bearing. It goes through the
 * store's upsert, keyed on the answer's remark, so a question answered twice ends with one answer
 * carrying the second body and its own fresh anchor. A function called addAnswer that silently
 * replaced would be a lie in the one file the answer guard points at.
 *
 * Only `review/AnswerReceipt.kt` may call this, and the argument is guard 6's argument about
 * [markRemarksRead] with one route instead of two: an answer is a record that an agent answered a
 * question, and the only thing that can say so is a `POST /api/claude-remarks/answer` carrying the
 * nonce of a batch the IDE itself minted. Letting anything else in would let the IDE manufacture an
 * answer nobody wrote.
 *
 * A replaced answer is not archived, the same way [editRemark] overwrites a remark's text without
 * archiving it. The archive covers a deletion the person asked for, not a rewrite.
 *
 * The upsert can also decline: an answer whose `answeredAt` is older than the one already stored for
 * that remark is dropped, so two requests in flight at once cannot end with the older body winning
 * because it finished last. Nothing is reported when that happens — the newer answer is already
 * stored and is the one the person wants. See `RemarkStore.RemarksState.putAnswer`.
 */
fun recordAnswer(project: Project, answer: AnswerState) {
    RemarkStore.getInstance(project).putAnswer(answer)
    notifyRemarksChanged(project)
}

/**
 * Removes one answer by its own id — the answer's own, not the remark's — because the person doing
 * this is looking at a row in the Answers group and naming that row. Nothing is archived, the same
 * rule [deleteRemark] follows: a single Delete is an explicit "this one was a mistake", and
 * archiving those makes the history file useless.
 */
fun deleteAnswer(project: Project, id: String) {
    if (RemarkStore.getInstance(project).removeAnswer(id)) notifyRemarksChanged(project)
}

/**
 * Writes every handed-over remark — PUBLISHED or READ — to the history file, then removes them.
 * Returns how many were removed.
 *
 * The history file is a nullable parameter rather than one defaulted to `historyFile(project)`.
 * Kotlin evaluates a default argument in the synthetic bridge, BEFORE the body runs, so a default of
 * `historyFile(project)` is resolved outside `archive`'s try — and anything it throws (a malformed
 * configuration path, an unrepresentable name) escapes this function entirely, arriving as an
 * unhandled exception out of the toolbar action instead of the balloon written for exactly that case.
 * Nothing is deleted either way, so nothing is lost; the failure just arrives as a crash. Null means
 * "the real one", resolved inside the try. Only the tests pass a path.
 */
fun clearHandedOverRemarks(project: Project, historyFile: Path? = null): Int {
    val going = RemarkStore.getInstance(project).all().filter { it.status != RemarkStatus.PENDING }
    if (!archive(project, historyFile, going)) return 0
    val removed = RemarkStore.getInstance(project).removeHandedOver()
    if (removed > 0) notifyRemarksChanged(project)
    return removed
}

/**
 * Writes every remark AND every answer to the history file, then removes both. Returns how many
 * records went in all.
 *
 * Both lists go in one [appendToHistory] call, not two, so a person clearing once finds one
 * `## cleared` entry rather than two carrying half the pass each. The all-or-nothing rule [archive]
 * enforces then covers both: if that single write fails, neither list is touched.
 *
 * [clearHandedOverRemarks] deliberately does not do this. An answer was never handed anywhere, so
 * "handed over" says nothing about it, and the button that takes handed-over remarks keeps answers.
 */
fun clearAllRemarks(project: Project, historyFile: Path? = null): Int {
    val store = RemarkStore.getInstance(project)
    if (!archive(project, historyFile, store.all(), store.allAnswers())) return 0
    val removed = store.clear()
    if (removed > 0) notifyRemarksChanged(project)
    return removed
}

/**
 * False when the archive could not be written, and then nothing may be deleted. A single Delete on
 * a row is not routed through here on purpose: that is an explicit "this one was a mistake", and
 * archiving every typo makes the history file useless.
 *
 * Catches Exception rather than IOException, because working out WHERE to write is inside the try
 * now and can fail on its own. ProcessCanceledException is rethrown: it is a RuntimeException, and
 * turning a cancellation into a red balloon would be a lie.
 */
private fun archive(
    project: Project,
    file: Path?,
    remarks: List<RemarkState>,
    answers: List<AnswerState> = emptyList(),
): Boolean {
    var target = file?.toString() ?: "the remark history file"
    return try {
        val path = file ?: historyFile(project)
        target = path.toString()
        appendToHistory(path, remarks, answers)
        true
    } catch (e: ProcessCanceledException) {
        throw e
    } catch (e: Exception) {
        notifyRemarks(
            project,
            "Nothing was cleared: the remark history could not be written to $target (${e.message}).",
            NotificationType.ERROR,
        )
        false
    }
}

/**
 * Also published when an editor opens or closes, because that changes which remarks can be
 * resolved at all, not only when the list itself changed.
 */
fun notifyRemarksChanged(project: Project) {
    if (!project.isDisposed) project.messageBus.syncPublisher(REMARKS_CHANGED).remarksChanged()
}
