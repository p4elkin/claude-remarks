package dev.sasha.clauderemarks.action

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.vfs.VfsUtilCore
import dev.sasha.clauderemarks.anchor.captureAnchor
import dev.sasha.clauderemarks.model.RemarkState
import dev.sasha.clauderemarks.store.RemarkStore
import dev.sasha.clauderemarks.store.projectRoot
import java.util.UUID

/** Throwaway. Phase 3 replaces this with the real inline input. */
class AddDebugRemarkAction : AnAction() {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return
        val file = e.getData(CommonDataKeys.VIRTUAL_FILE) ?: return
        val root = projectRoot(project) ?: return
        val relative = VfsUtilCore.getRelativePath(file, root) ?: return

        val document = editor.document
        val selection = editor.selectionModel
        val startLine = document.getLineNumber(selection.selectionStart)
        val endLine = document.getLineNumber(selection.selectionEnd)
        val lines = document.text.split("\n")
        val anchor = captureAnchor(lines, startLine, endLine)

        val remark = RemarkState().apply {
            id = UUID.randomUUID().toString()
            path = relative
            this.startLine = anchor.startLine
            this.endLine = anchor.endLine
            text = "debug remark"
            createdAt = System.currentTimeMillis()
            textHash = anchor.textHash
            contextBefore = anchor.contextBefore.joinToString("\n")
            contextAfter = anchor.contextAfter.joinToString("\n")
        }
        RemarkStore.getInstance(project).add(remark)
    }
}
