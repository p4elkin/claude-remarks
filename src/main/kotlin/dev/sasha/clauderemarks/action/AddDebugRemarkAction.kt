package dev.sasha.clauderemarks.action

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
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
        // Without this the item shows up in every editor popup and does nothing at all when
        // one of the guards in actionPerformed trips.
        e.presentation.isEnabledAndVisible = e.project != null && fileOf(e) != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return
        val file = fileOf(e) ?: return
        val root = projectRoot(project) ?: return
        val relative = VfsUtilCore.getRelativePath(file, root) ?: return

        val document = editor.document
        val selection = editor.selectionModel
        val startLine = document.getLineNumber(selection.selectionStart)
        var endLine = document.getLineNumber(selection.selectionEnd)
        // selectionEnd is exclusive. Selecting whole lines (gutter drag, shift+down, Ctrl+A)
        // leaves it at the first offset of the following line, which would anchor one line
        // more than the user selected.
        if (endLine > startLine && document.getLineStartOffset(endLine) == selection.selectionEnd) {
            endLine--
        }

        val anchor = captureAnchor(document.text.split("\n"), startLine, endLine)

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
     * The file behind the editor's document, not CommonDataKeys.VIRTUAL_FILE: in a diff viewer
     * or an injected fragment those two are different files, and the anchor comes from the
     * document, so the path has to come from the same place.
     */
    private fun fileOf(e: AnActionEvent) = e.getData(CommonDataKeys.EDITOR)
        ?.let { FileDocumentManager.getInstance().getFile(it.document) }
}
