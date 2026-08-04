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
 * The second entry is the diff fallback, and it is not a storage route. A diff pane showing a VCS
 * revision has a document whose file is a LightVirtualFile: it carries the right file NAME but sits
 * on no path under the project, so it never yields a relative path on its own. The revision does
 * know the project file it is a version of, as DocumentContent.getHighlightFile(), and that is what
 * this second entry finds — but the only reader that acts on it is [remarkTargetProblem], which
 * uses it to tell a revision pane apart from a file genuinely outside the project so that each gets
 * its own sentence. No remark is ever stored against it: since the revision pane is refused, the
 * refusal always runs first.
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
 *
 * In production only the first candidate can ever answer. The one caller, openNewRemarkInput in
 * action/AddRemarkAction.kt, returns before it reaches here whenever [remarkTargetProblem] is
 * non-null, and that function refuses exactly the case where the first candidate fails and a later
 * one resolves. The second candidate is kept because both functions read the same [targetFiles]
 * list, and because the refusal itself is the part `docs/ideas.md` records as overrulable. What
 * DiffRemarkTargetTest pins about it is that shared internal detail, not a route a remark can take.
 */
fun relativePathOf(project: Project, editor: Editor, dataContext: DataContext? = null): String? =
    targetFiles(editor, dataContext).firstNotNullOfOrNull { relativePathOf(project, it) }

/**
 * Where a remark on this file would be stored, or null when it cannot be stored at all.
 *
 * Split out of the Editor version so the markdown preview can ask the same question: it holds a
 * VirtualFile and no Editor at all. The Editor version is now written in terms of this one, over its
 * own candidate list, so the two can never answer differently for the same file — which matters,
 * because a remark written from the preview and a remark written from the editor half of the same
 * split have to carry the same path.
 */
fun relativePathOf(project: Project, file: VirtualFile?): String? {
    val root = projectRoot(project) ?: return null
    return file?.let { VfsUtilCore.getRelativePath(it, root) }
}

/**
 * Why a remark on this file cannot be stored, in words a person can read, or null when it can.
 *
 * Split out of [remarkTargetProblem] for the same reason as above: the markdown preview holds a
 * file and no editor. [remarkTargetProblem] keeps both of its diff sentences and calls this for
 * everything else, so there is still one place that decides whether a file can carry a remark.
 *
 * The first sentence says "here" rather than "this editor", which is what it said while an editor
 * was the only thing that could ask. The preview is not an editor, and a sentence that says it is
 * would send the person looking for a problem in the wrong window.
 */
fun fileTargetProblem(project: Project, file: VirtualFile?): String? {
    val name = file?.name
        ?: return "There is no file on disk here, so a remark could not be pointed back at it."
    projectRoot(project)
        ?: return "The project directory could not be resolved, so remarks cannot be stored."
    if (relativePathOf(project, file) != null) return null
    return "$name is outside the project directory, so a remark on it could not be found again."
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
 * wording reads as nonsense there. Those two sentences are the whole of what this adds to
 * [fileTargetProblem]: everything else is that function's answer, passed through unchanged.
 */
fun remarkTargetProblem(project: Project, editor: Editor, dataContext: DataContext? = null): String? {
    val candidates = targetFiles(editor, dataContext)
    val own = candidates.firstOrNull()
    // The ordinary editor, and the working-copy side of a diff: the document being read IS the file
    // the remark will be stored against, so the line numbers describe it.
    val problem = fileTargetProblem(project, own) ?: return null
    // The two refusals that are about neither a file nor a diff — no file at all, and no project
    // root — have nothing a diff sentence could improve on, so they stand as they are.
    val name = own?.name ?: return problem
    val root = projectRoot(project) ?: return problem
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
    // Nothing resolved at all: the two messages that were already there, unchanged. The diff one is
    // this function's own; the other is what fileTargetProblem already said.
    return if (editor.editorKind == EditorKind.DIFF) {
        "$name in this diff is a revision with no matching file under the project directory, " +
            "so a remark on it could not be found again."
    } else {
        problem
    }
}
