package dev.sasha.clauderemarks.review

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.changes.Change
import com.intellij.openapi.vcs.changes.ChangeListManager
import com.intellij.openapi.vcs.changes.actions.diff.ShowDiffAction
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
 *
 * The paths arrive over HTTP, so `Path.of` can throw: a NUL byte in a name is enough, and on Windows
 * so is `?` or `*`. An unparseable path is dropped exactly like an absolute one. Letting it throw
 * would leave the netty thread's own catch turning it into a 500 with a stack trace for a body,
 * after the review had already been accepted and the answer written.
 */
internal fun filterReviewPaths(paths: List<String>?): List<String> =
    (paths ?: emptyList())
        .filterNot {
            runCatching { Path.of(it).isAbsolute }.getOrDefault(true) || it.split("/").contains("..")
        }
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
        // The project can close between the HTTP request and this hop to the EDT, and every call
        // below throws AlreadyDisposedException on a disposed project.
        if (project.isDisposed) return@invokeLater
        val root = projectRoot(project) ?: return@invokeLater
        val changes = mutableListOf<Change>()
        filtered.forEach { path ->
            val file = fileForStoredPath(root, path) ?: return@forEach
            // No local change: today's behaviour, and the right answer for a file the person
            // should read but has not touched.
            val change = ChangeListManager.getInstance(project).getChange(file)
            if (change == null) FileEditorManager.getInstance(project).openFile(file, false)
            else changes += change
        }
        // One window for every changed file, so the person gets next-file and previous-file
        // navigation inside it instead of a window per file.
        if (changes.isNotEmpty()) ShowDiffAction.showDiffForChange(project, changes)
    }
}
