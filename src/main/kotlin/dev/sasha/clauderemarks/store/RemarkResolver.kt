package dev.sasha.clauderemarks.store

import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import dev.sasha.clauderemarks.anchor.Anchor
import dev.sasha.clauderemarks.anchor.AnchorResult
import dev.sasha.clauderemarks.anchor.resolveAnchor
import dev.sasha.clauderemarks.model.RemarkState

data class ResolvedRemark(val remark: RemarkState, val result: AnchorResult)

object RemarkResolver {

    /** Must be called inside a read action, off the EDT. */
    fun resolveAll(project: Project): List<ResolvedRemark> {
        val root = projectRoot(project) ?: return emptyList()
        return RemarkStore.getInstance(project).all().map { remark ->
            ResolvedRemark(remark, resolveOne(root, remark))
        }
    }

    private fun resolveOne(root: VirtualFile, remark: RemarkState): AnchorResult {
        val stale = AnchorResult.Orphaned(remark.startLine, remark.endLine)
        val path = remark.path ?: return stale

        // findRelativeFile takes the root FIRST, then each path segment as its own vararg:
        // findRelativeFile(VirtualFile, String...). Passing "a/b/Foo.kt" as a single element
        // finds nothing, and passing (path, root) does not compile.
        val file = VfsUtil.findRelativeFile(root, *path.split('/').toTypedArray()) ?: return stale

        val document = FileDocumentManager.getInstance().getDocument(file) ?: return stale
        val anchor = Anchor(
            startLine = remark.startLine,
            endLine = remark.endLine,
            textHash = remark.textHash.orEmpty(),
            contextBefore = splitContext(remark.contextBefore),
            contextAfter = splitContext(remark.contextAfter),
        )
        return resolveAnchor(anchor, document.text.split("\n"))
    }

    /** An empty stored string means no context, not a list holding one empty line. */
    private fun splitContext(stored: String?): List<String> =
        if (stored.isNullOrEmpty()) emptyList() else stored.split("\n")
}
