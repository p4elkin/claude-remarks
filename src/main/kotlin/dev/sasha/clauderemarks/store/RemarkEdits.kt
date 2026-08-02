package dev.sasha.clauderemarks.store

import com.intellij.notification.NotificationType
import com.intellij.openapi.project.Project
import com.intellij.util.messages.Topic
import dev.sasha.clauderemarks.action.notifyRemarks
import dev.sasha.clauderemarks.anchor.captureAnchor
import dev.sasha.clauderemarks.model.RemarkSeverity
import dev.sasha.clauderemarks.model.RemarkState
import dev.sasha.clauderemarks.model.RemarkStatus
import dev.sasha.clauderemarks.model.RemarkTag
import java.io.IOException
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
 * These eight functions are the only way production code changes a remark. Nothing calls
 * RemarkStore.add / RemarkStore.remove directly any more, and task 12 greps to keep that true.
 *
 * The reason is not tidiness. The tool window and the gutter both have to redraw after any change,
 * and pairing the mutation with the notification in one function is what stops a caller doing one
 * without the other. The store itself stays out of it so that RemarkStoreStateTest can keep
 * building RemarkStore() directly, with no fixture, in fourteen places.
 */

/** Captures the anchor for [range] out of [lines] and stores a new remark. Returns what was stored. */
fun addRemark(
    project: Project,
    path: String,
    lines: List<String>,
    range: IntRange,
    text: String,
    tag: RemarkTag?,
): RemarkState {
    val anchor = captureAnchor(lines, range.first, range.last)
    val remark = RemarkState().apply {
        this.id = UUID.randomUUID().toString()
        this.path = path
        this.startLine = anchor.startLine
        this.endLine = anchor.endLine
        this.text = text
        this.tag = tag
        this.createdAt = System.currentTimeMillis()
        this.textHash = anchor.textHash
        this.contextBefore = joinContext(anchor.contextBefore)
        this.contextAfter = joinContext(anchor.contextAfter)
        // Two small file reads on the EDT, once per remark, at human pace. No cache: one keyed on
        // the HEAD file's timestamp would be more code than the read it saves.
        this.commit = project.basePath?.let { headCommit(Path.of(it)) }
    }
    RemarkStore.getInstance(project).add(remark)
    notifyRemarksChanged(project)
    return remark
}

fun editRemark(project: Project, id: String, text: String, tag: RemarkTag?) {
    if (RemarkStore.getInstance(project).edit(id, text, tag)) notifyRemarksChanged(project)
}

fun deleteRemark(project: Project, id: String) {
    if (RemarkStore.getInstance(project).remove(id)) notifyRemarksChanged(project)
}

fun markRemarksSent(project: Project, ids: Collection<String>) {
    if (RemarkStore.getInstance(project).markSent(ids.toSet()) > 0) notifyRemarksChanged(project)
}

fun setRemarkSeverity(project: Project, ids: Collection<String>, severity: RemarkSeverity) {
    if (RemarkStore.getInstance(project).setSeverity(ids.toSet(), severity) > 0) {
        notifyRemarksChanged(project)
    }
}

/**
 * Blank means no bucket, and the name is trimmed. Both live here rather than at the call site,
 * because there are two call sites — the gutter icon menu and the tree — and a bucket name that
 * differs only by whitespace is a second group in the tree that looks identical to the first.
 */
fun setRemarkBucket(project: Project, ids: Collection<String>, bucket: String?) {
    val clean = bucket?.trim()?.takeIf { it.isNotEmpty() }
    if (RemarkStore.getInstance(project).setBucket(ids.toSet(), clean) > 0) {
        notifyRemarksChanged(project)
    }
}

/**
 * Writes the sent remarks to the history file, then removes them. Returns how many were removed.
 *
 * The history file is a parameter with a default so the failure path can be tested. Nothing else
 * passes it.
 */
fun clearSentRemarks(project: Project, historyFile: Path = historyFile(project)): Int {
    val going = RemarkStore.getInstance(project).all().filter { it.status == RemarkStatus.SENT }
    if (!archive(project, historyFile, going)) return 0
    val removed = RemarkStore.getInstance(project).removeSent()
    if (removed > 0) notifyRemarksChanged(project)
    return removed
}

fun clearAllRemarks(project: Project, historyFile: Path = historyFile(project)): Int {
    if (!archive(project, historyFile, RemarkStore.getInstance(project).all())) return 0
    val removed = RemarkStore.getInstance(project).clear()
    if (removed > 0) notifyRemarksChanged(project)
    return removed
}

/**
 * False when the archive could not be written, and then nothing may be deleted. A single Delete on
 * a row is not routed through here on purpose: that is an explicit "this one was a mistake", and
 * archiving every typo makes the history file useless.
 */
private fun archive(project: Project, file: Path, remarks: List<RemarkState>): Boolean =
    try {
        appendToHistory(file, remarks)
        true
    } catch (e: IOException) {
        notifyRemarks(
            project,
            "Nothing was cleared: the remark history could not be written to $file (${e.message}).",
            NotificationType.ERROR,
        )
        false
    }

/**
 * Also published when an editor opens or closes, because that changes which remarks can be
 * resolved at all, not only when the list itself changed.
 */
fun notifyRemarksChanged(project: Project) {
    if (!project.isDisposed) project.messageBus.syncPublisher(REMARKS_CHANGED).remarksChanged()
}
