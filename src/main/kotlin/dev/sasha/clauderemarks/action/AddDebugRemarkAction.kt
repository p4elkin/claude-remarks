package dev.sasha.clauderemarks.action

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.vfs.VfsUtilCore
import dev.sasha.clauderemarks.anchor.captureAnchor
import dev.sasha.clauderemarks.model.RemarkState
import dev.sasha.clauderemarks.store.RemarkStore
import dev.sasha.clauderemarks.store.joinContext
import dev.sasha.clauderemarks.store.projectRoot
import java.util.UUID

/** Throwaway. Phase 3 replaces this with the real inline input. */
class AddDebugRemarkAction : AnAction() {

    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        // The same condition actionPerformed needs, not a weaker one. A file outside the
        // project root (a library source, a decompiled class, a scratch file) has no
        // project-relative path, so no remark can be stored for it. Enabling the item there
        // would give the user a menu entry that silently does nothing.
        e.presentation.isEnabledAndVisible = relativePathOf(e) != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return
        val relative = relativePathOf(e) ?: return

        val document = editor.document
        val selection = editor.selectionModel
        val lines = selectedLines(document, selection.selectionStart, selection.selectionEnd)
        val anchor = captureAnchor(document.text.split("\n"), lines.first, lines.last)

        val remark = RemarkState().apply {
            id = UUID.randomUUID().toString()
            path = relative
            this.startLine = anchor.startLine
            this.endLine = anchor.endLine
            text = "debug remark"
            createdAt = System.currentTimeMillis()
            textHash = anchor.textHash
            contextBefore = joinContext(anchor.contextBefore)
            contextAfter = joinContext(anchor.contextAfter)
        }
        RemarkStore.getInstance(project).add(remark)
    }

    /**
     * Where a remark from this event would be stored, or null when it cannot be stored at all.
     *
     * The file comes from the editor's document, not from CommonDataKeys.VIRTUAL_FILE: in a
     * diff viewer or an injected fragment those two are different files, and the anchor comes
     * from the document, so the path has to come from the same place.
     */
    private fun relativePathOf(e: AnActionEvent): String? {
        val project = e.project ?: return null
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return null
        val file = FileDocumentManager.getInstance().getFile(editor.document) ?: return null
        val root = projectRoot(project) ?: return null
        return VfsUtilCore.getRelativePath(file, root)
    }
}

/**
 * The 0-based, inclusive line range a selection covers.
 *
 * selectionEnd is exclusive. Selecting whole lines (gutter drag, shift+down, Ctrl+A) leaves it
 * at the first offset of the following line, which would anchor one line more than the user
 * selected. With no selection at all both offsets are the caret, which gives the caret's line.
 */
fun selectedLines(document: Document, selectionStart: Int, selectionEnd: Int): IntRange {
    val startLine = document.getLineNumber(selectionStart)
    val endLine = document.getLineNumber(selectionEnd)
    val endsOnAFreshLine =
        endLine > startLine && document.getLineStartOffset(endLine) == selectionEnd
    return startLine..(if (endsOnAFreshLine) endLine - 1 else endLine)
}
