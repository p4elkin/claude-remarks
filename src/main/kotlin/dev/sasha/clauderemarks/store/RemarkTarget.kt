package dev.sasha.clauderemarks.store

import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VfsUtilCore

/**
 * Where a remark on this editor would be stored, or null when it cannot be stored at all.
 *
 * The file comes from the editor's document, not from CommonDataKeys.VIRTUAL_FILE: in a diff
 * viewer or an injected fragment those are different files, and the anchor comes from the
 * document, so the path has to come from the same place.
 */
fun relativePathOf(project: Project, editor: Editor): String? {
    val file = FileDocumentManager.getInstance().getFile(editor.document) ?: return null
    val root = projectRoot(project) ?: return null
    return VfsUtilCore.getRelativePath(file, root)
}

/**
 * Why a remark cannot be added here, in words a person can read, or null when it can.
 *
 * A sentence rather than a boolean, because the action shows this in the menu item's description.
 * The old debug action hid itself in these cases, which looked to the user like the plugin was
 * broken rather than like the file was out of scope.
 */
fun remarkTargetProblem(project: Project, editor: Editor): String? {
    val file = FileDocumentManager.getInstance().getFile(editor.document)
        ?: return "This editor has no file on disk, so a remark could not be pointed back at it."
    val root = projectRoot(project)
        ?: return "The project directory could not be resolved, so remarks cannot be stored."
    return if (VfsUtilCore.getRelativePath(file, root) != null) null
    else "${file.name} is outside the project directory, so a remark on it could not be found again."
}
