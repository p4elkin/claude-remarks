package dev.sasha.clauderemarks.store

import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import dev.sasha.clauderemarks.anchor.Anchor
import dev.sasha.clauderemarks.anchor.AnchorResult
import dev.sasha.clauderemarks.anchor.resolveAnchor
import dev.sasha.clauderemarks.model.RemarkState

data class ResolvedRemark(val remark: RemarkState, val result: AnchorResult)

/**
 * The project directory, used as the base for every stored remark path.
 *
 * Not ProjectUtil.guessProjectDir, which is what the deprecation note on Project.getBaseDir
 * points at: in 2025.2 that class is Kotlin-internal, so it resolves from Java but NOT from
 * Kotlin, and the Kotlin compiler reports "Unresolved reference 'ProjectUtil'" even though
 * the jar holding it is on the compile classpath. Verified by compiling against the 2025.2
 * jars. basePath is what remains, and it is exactly the directory holding .idea, which is
 * what "project-relative" should mean here.
 */
fun projectRoot(project: Project): VirtualFile? =
    project.basePath?.let { LocalFileSystem.getInstance().findFileByPath(it) }

/**
 * Turns every stored remark into a row for the tool window.
 *
 * Must be called inside a read action, off the EDT. A remark is never dropped: when anything
 * cannot be resolved the row still carries the stored line numbers, marked as orphaned.
 */
fun resolveAll(project: Project): List<ResolvedRemark> {
    val root = projectRoot(project)
    return RemarkStore.getInstance(project).all().map { remark ->
        // One cancellation point per remark, so a pending write does not wait for the whole
        // sweep: each remark can cost a SHA-256 over every candidate position in the radius.
        ProgressManager.checkCanceled()
        ResolvedRemark(remark, if (root == null) staleOf(remark) else resolveOne(root, remark))
    }
}

private fun staleOf(remark: RemarkState) =
    AnchorResult.Orphaned(remark.startLine, remark.endLine)

private fun resolveOne(root: VirtualFile, remark: RemarkState): AnchorResult {
    val stale = staleOf(remark)
    val path = remark.path ?: return stale

    // findRelativeFile takes the root FIRST, then each path segment as its own vararg:
    // findRelativeFile(VirtualFile, String...). Passing "a/b/Foo.kt" as a single element
    // finds nothing, and passing (path, root) does not compile.
    val file = VfsUtil.findRelativeFile(root, *path.split('/').toTypedArray()) ?: return stale

    // findRelativeFile walks ".." through getParent(), so a hand-edited workspace.xml could
    // otherwise point a remark at any file on the machine.
    if (!VfsUtilCore.isAncestor(root, file, false)) return stale

    val document = FileDocumentManager.getInstance().getDocument(file) ?: return stale
    return resolveAnchor(anchorOf(remark), document.text.split("\n"))
}

/** The stored fields of a remark, read back as the anchor they were captured from. */
fun anchorOf(remark: RemarkState) = Anchor(
    startLine = remark.startLine,
    endLine = remark.endLine,
    textHash = remark.textHash.orEmpty(),
    contextBefore = splitContext(remark.contextBefore),
    contextAfter = splitContext(remark.contextAfter),
)

/**
 * Context is stored as one newline-joined string. Null means no context at all, which is what
 * keeps one blank line of context (the empty string) from reading back as no context.
 */
fun joinContext(lines: List<String>): String? =
    if (lines.isEmpty()) null else lines.joinToString("\n")

fun splitContext(stored: String?): List<String> =
    if (stored == null) emptyList() else stored.split("\n")
