package dev.sasha.clauderemarks.store

import com.intellij.openapi.diagnostic.Logger
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

private val LOG = Logger.getInstance("dev.sasha.clauderemarks.store.RemarkResolver")

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
fun resolveAll(project: Project): List<ResolvedRemark> =
    resolveAll(projectRoot(project), RemarkStore.getInstance(project).all())

/**
 * The part of [resolveAll] that does not need a project, so the two ways it refuses to trust
 * what is stored can be tested: a null [root] and a path that climbs out of the project.
 */
fun resolveAll(root: VirtualFile?, remarks: List<RemarkState>): List<ResolvedRemark> =
    remarks.map { remark ->
        // One cancellation point per remark, so a pending write does not wait for the whole
        // sweep: each remark can cost a SHA-256 over every candidate position in the radius.
        ProgressManager.checkCanceled()
        ResolvedRemark(
            remark,
            if (root == null) refuse(remark, "the project root did not resolve")
            else resolveOne(root, remark),
        )
    }

/**
 * Marks a remark stale, keeping its stored line numbers, and says in the log why.
 *
 * Five different refusals all end as the same orphaned row, so the reason exists nowhere else.
 * Debug level: nothing is printed until someone turns the category on.
 */
private fun refuse(remark: RemarkState, why: String): AnchorResult {
    LOG.debug("remark ${remark.id} (${remark.path}): $why")
    return AnchorResult.Orphaned(remark.startLine, remark.endLine)
}

/**
 * The file a stored path points at, or null when there is no such file or the path leaves the
 * project.
 *
 * Every reader of a stored path comes through here, and that is the point. findRelativeFile takes
 * the root FIRST, then each path segment as its own vararg: findRelativeFile(VirtualFile, String...).
 * Passing "a/b/Foo.kt" as a single element finds nothing, and passing (path, root) does not compile.
 * It also walks ".." through getParent(), so a hand-edited or committed workspace.xml holding
 * `path="../../../../etc/passwd"` would otherwise reach any file on the machine. The isAncestor
 * check lived in three separate places and one of them — the tool window's double-click navigation —
 * did not have it.
 */
fun fileForStoredPath(root: VirtualFile, path: String): VirtualFile? =
    VfsUtil.findRelativeFile(root, *path.split('/').toTypedArray())
        ?.takeIf { VfsUtilCore.isAncestor(root, it, false) }

private fun resolveOne(root: VirtualFile, remark: RemarkState): AnchorResult {
    val path = remark.path ?: return refuse(remark, "no path stored")

    val file = fileForStoredPath(root, path)
        ?: return refuse(remark, "no file under the project root at that path")

    val document = FileDocumentManager.getInstance().getDocument(file)
        ?: return refuse(remark, "the file has no Document (binary, or too large)")
    return resolveAnchor(anchorOf(remark), document.text.split("\n"))
}

/** The stored fields of a remark, read back as the anchor they were captured from. Context is
 *  decoded with splitContext, from ContextFormat.kt. */
fun anchorOf(remark: RemarkState) = Anchor(
    startLine = remark.startLine,
    endLine = remark.endLine,
    textHash = remark.textHash.orEmpty(),
    contextBefore = splitContext(remark.contextBefore),
    contextAfter = splitContext(remark.contextAfter),
)
