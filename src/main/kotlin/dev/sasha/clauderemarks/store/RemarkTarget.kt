package dev.sasha.clauderemarks.store

import com.intellij.diff.contents.DocumentContent
import com.intellij.diff.tools.util.DiffDataKeys
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorKind
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile

/**
 * The files a remark on this editor could be stored against, best first.
 *
 * The document's own file comes first, and for every ordinary editor it is the only entry and the
 * answer. It is taken from the document rather than from CommonDataKeys.VIRTUAL_FILE because in a
 * diff viewer or an injected fragment those are different files, and the anchor comes from the
 * document, so the path has to come from the same place.
 *
 * The second entry is the diff fallback. A diff pane showing a VCS revision has a document whose
 * file is a LightVirtualFile: it carries the right file NAME but sits on no path under the project,
 * so it never yields a relative path on its own. The revision knows the project file it is a
 * version of, as DocumentContent.getHighlightFile(), and that is what a remark should be stored
 * against.
 *
 * [dataContext] is what makes the second entry reachable. There is no route from an Editor alone:
 * DiffUtil.configureEditor sets the diff editor's own file to
 * FileDocumentManager.getFile(content.document), which is the same LightVirtualFile the first entry
 * already found, so editor.virtualFile is no help here. DiffDataKeys.CURRENT_CONTENT is filled in
 * by TwosideDiffViewer.uiDataSnapshot with the content of the pane that has focus, so in a
 * two-revision diff this is the side the user is actually reading.
 */
private fun targetFiles(editor: Editor, dataContext: DataContext?): List<VirtualFile> =
    listOfNotNull(
        FileDocumentManager.getInstance().getFile(editor.document),
        (dataContext?.getData(DiffDataKeys.CURRENT_CONTENT) as? DocumentContent)?.highlightFile,
    )

/**
 * Where a remark on this editor would be stored, or null when it cannot be stored at all.
 *
 * Every candidate goes through the same VfsUtilCore.getRelativePath(file, root), which returns null
 * unless root really is an ancestor. The diff fallback is not a second route around that check: a
 * highlight file outside the project root is refused exactly like any other file outside it.
 */
fun relativePathOf(project: Project, editor: Editor, dataContext: DataContext? = null): String? {
    val root = projectRoot(project) ?: return null
    return targetFiles(editor, dataContext)
        .firstNotNullOfOrNull { VfsUtilCore.getRelativePath(it, root) }
}

/**
 * Why a remark cannot be added here, in words a person can read, or null when it can.
 *
 * A sentence rather than a boolean, because the action shows this in the menu item's description
 * and openNewRemarkInput shows it at the caret. The old debug action hid itself in these cases,
 * which looked to the user like the plugin was broken rather than like the file was out of scope.
 *
 * The diff case gets its own sentence. The name in the message is the revision's name, which is the
 * name of a file the user can see in the project, so the plain "outside the project directory"
 * wording reads as nonsense there.
 */
fun remarkTargetProblem(project: Project, editor: Editor, dataContext: DataContext? = null): String? {
    val candidates = targetFiles(editor, dataContext)
    val own = candidates.firstOrNull()
    val name = own?.name
        ?: return "This editor has no file on disk, so a remark could not be pointed back at it."
    val root = projectRoot(project)
        ?: return "The project directory could not be resolved, so remarks cannot be stored."
    // The ordinary editor, and the working-copy side of a diff: the document being read IS the file
    // the remark will be stored against, so the line numbers describe it.
    if (VfsUtilCore.getRelativePath(own, root) != null) return null
    // Only a later candidate resolved, which means this pane holds a revision and the file was found
    // through DocumentContent.getHighlightFile. Right file, wrong line numbers. drop(1), not the
    // whole list: the first candidate was just refused one line above.
    //
    // The advice names the working copy rather than "the other side of the diff": in a diff between
    // two commits neither pane is the working copy, so the other side refuses the same way and the
    // person is sent in a circle.
    if (candidates.drop(1).any { VfsUtilCore.getRelativePath(it, root) != null }) {
        return "$name here is a revision, not the working copy, so a remark's line numbers would " +
            "not describe the file on disk. Add it on the working copy — the working-tree side of " +
            "this diff, or the file itself."
    }
    // Nothing resolved at all: the two messages that were already there, unchanged.
    return if (editor.editorKind == EditorKind.DIFF) {
        "$name in this diff is a revision with no matching file under the project directory, " +
            "so a remark on it could not be found again."
    } else {
        "$name is outside the project directory, so a remark on it could not be found again."
    }
}
