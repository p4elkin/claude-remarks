package dev.sasha.clauderemarks.review

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import dev.sasha.clauderemarks.store.fileForStoredPath
import dev.sasha.clauderemarks.store.projectRoot
import java.nio.file.Path

/**
 * The only file in `review/` that touches the VFS or the editor. `ReviewRestService.execute` runs
 * on a netty IO thread and CLAUDE.md's rule 5 greps that file for exactly those names, so the file
 * opening lives here instead, and does its own invokeLater rather than invokeAndWait — the HTTP
 * response must not wait for editors to appear.
 */

/**
 * The string-only half of the check, safe to run on any thread: no VFS access yet. Drops a path
 * that is absolute or that climbs out of the project with a ".." segment, and keeps at most
 * twenty — a request naming a thousand paths must not lock the IDE up opening them. The
 * VFS-and-ancestor half of the check happens later, in [openReviewFiles], because it needs the
 * project root.
 */
internal fun filterReviewPaths(paths: List<String>?): List<String> =
    (paths ?: emptyList())
        .filterNot { Path.of(it).isAbsolute || it.split("/").contains("..") }
        .take(20)

/**
 * Opens each surviving path in an editor. Reuses `fileForStoredPath` from
 * `store/RemarkResolver.kt` rather than writing a second ancestor check — that function already
 * resolves relative to the project root and re-checks with VfsUtilCore.isAncestor.
 *
 * invokeLater, never invokeAndWait: this is called from `ReviewRestService.execute`, which runs on
 * a netty IO thread, and the HTTP response must not wait for the editors to open.
 */
fun openReviewFiles(project: Project, paths: List<String>?) {
    val filtered = filterReviewPaths(paths)
    if (filtered.isEmpty()) return
    ApplicationManager.getApplication().invokeLater {
        val root = projectRoot(project) ?: return@invokeLater
        filtered.forEach { path ->
            val file = fileForStoredPath(root, path) ?: return@forEach
            FileEditorManager.getInstance(project).openFile(file, false)
        }
    }
}
