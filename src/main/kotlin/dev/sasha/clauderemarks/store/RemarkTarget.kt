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
 * The file the pane's own document belongs to, or null when the document belongs to no file.
 *
 * Taken from the document rather than from CommonDataKeys.VIRTUAL_FILE because in a diff viewer or
 * an injected fragment those are different files, and the anchor comes from the document, so the
 * path has to come from the same place.
 *
 * Null is a real answer, not a can't-happen. DiffContentFactoryImpl builds a diff pane's document
 * through createPsiDocument, which returns null for a binary file type and falls back to a plain
 * EditorFactory document belonging to nothing at all. See [targetFiles] for why that nullability
 * has to survive into the candidate list rather than being filtered out of it.
 */
private fun ownFileOf(editor: Editor): VirtualFile? =
    FileDocumentManager.getInstance().getFile(editor.document)

/**
 * The project file a diff pane's revision is a version of, or null when this is not a diff pane.
 *
 * A diff pane showing a VCS revision has a document whose file is a LightVirtualFile: it carries the
 * right file NAME but sits on no path under the project, so it never yields a relative path on its
 * own. The revision does know the project file it is a version of, as
 * DocumentContent.getHighlightFile(), and that is what this finds.
 *
 * [dataContext] is what makes it reachable. There is no route from an Editor alone:
 * DiffUtil.configureEditor sets the diff editor's own file to
 * FileDocumentManager.getFile(content.document), which is the same LightVirtualFile [ownFileOf]
 * already found, so editor.virtualFile is no help here. DiffDataKeys.CURRENT_CONTENT is filled in
 * by TwosideDiffViewer.uiDataSnapshot with the content of the pane that has focus, so in a
 * two-revision diff this is the side the user is actually reading.
 */
private fun diffFileOf(dataContext: DataContext?): VirtualFile? =
    (dataContext?.getData(DiffDataKeys.CURRENT_CONTENT) as? DocumentContent)?.highlightFile

/**
 * The files a remark on this editor could be stored against, best first.
 *
 * Two entries, always, in fixed positions: the document's own file, then the diff route. For every
 * ordinary editor the first entry is the answer and the second is null.
 *
 * ⚠️ The list holds nulls rather than dropping them, and that is the whole point of it being written
 * this way. It used to be a listOfNotNull, which silently promoted the diff route into the first
 * position whenever the document belonged to no file — and [remarkTargetProblem] reads the first
 * position as "the document being shown IS this file" and accepts it. So a revision pane with a
 * binary highlight file was accepted, and the revision's line numbers would have been stored against
 * the working copy: the exact mis-anchoring the refusal below exists to prevent. Positions have to
 * mean what they say.
 */
private fun targetFiles(editor: Editor, dataContext: DataContext?): List<VirtualFile?> =
    listOf(ownFileOf(editor), diffFileOf(dataContext))

/**
 * Where a remark on this editor would be stored, or null when it cannot be stored at all.
 *
 * Every candidate goes through the same VfsUtilCore.getRelativePath(file, root), which returns null
 * unless root really is an ancestor. The diff route is not a second way around that check: a
 * highlight file outside the project root is refused exactly like any other file outside it.
 *
 * ⚠️ This says where a remark WOULD go. It does not say whether one may be written — that is
 * [remarkTargetProblem]'s decision, and it refuses a revision pane whose text is not the working
 * copy's. Both callers ask that question first and return before they reach here:
 * openNewRemarkInput in action/AddRemarkAction.kt and askClaude in action/AskClaudeAction.kt. A
 * third caller that skipped it would store a remark whose line numbers describe a revision nobody
 * has on disk, which is the whole failure the refusal exists to stop.
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
 * True when this pane is showing exactly what [workingCopy] holds, character for character.
 *
 * This is what decides whether a revision pane may carry a remark. A remark's line numbers are
 * taken from the document being read and its anchor is captured from that document's text, while
 * both are later resolved against the working copy. When the two texts are the same string those
 * line numbers describe the working copy too, and the anchor resolves onto exactly the lines the
 * person chose. When they are not, the remark describes text that is not there.
 *
 * A whole-text comparison rather than a comparison of the selected lines: the lines only line up if
 * everything above them does. The length check in front makes the differing case cost nothing,
 * which is the case this runs in most often.
 *
 * Null from getDocument — a binary file, a file too large, a file that has been deleted — is a
 * refusal, never a pass. Accepting a pane because the check could not be run is the one way this
 * could store a remark against text nobody has.
 *
 * ⚠️ getDocument is @RequiresReadLock. Both callers already hold read access: the action's update
 * runs on ActionUpdateThread.BGT, which the platform documents as holding application-wide read
 * access, and openNewRemarkInput runs on the EDT, which holds it implicitly. So there is no
 * ReadAction here — adding one on the EDT would buy nothing, and a caller that ran anywhere else
 * would be breaking that contract before it ever reached this line.
 */
private fun showsWorkingCopyText(editor: Editor, workingCopy: VirtualFile): Boolean {
    val onDisk = FileDocumentManager.getInstance().getDocument(workingCopy) ?: return false
    val shown = editor.document
    return shown.textLength == onDisk.textLength && shown.charsSequence.contentEquals(onDisk.charsSequence)
}

/**
 * Why a remark cannot be added here, in words a person can read, or null when it can.
 *
 * A sentence rather than a boolean, because the action shows this in the menu item's description
 * and openNewRemarkInput shows it at the caret. The old debug action hid itself in these cases,
 * which looked to the user like the plugin was broken rather than like the file was out of scope.
 *
 * The diff case gets its own two sentences. The name in each is the name of a file the user can see
 * in the project, so the plain "outside the project directory" wording reads as nonsense there.
 * Those two sentences are the whole of what this adds to [fileTargetProblem]: everything else is
 * that function's answer, passed through unchanged.
 */
fun remarkTargetProblem(project: Project, editor: Editor, dataContext: DataContext? = null): String? {
    val own = ownFileOf(editor)
    // The ordinary editor, and the working-copy side of a diff: the document being read IS the file
    // the remark will be stored against, so the line numbers describe it.
    val problem = fileTargetProblem(project, own) ?: return null
    // No project root is a refusal about neither a file nor a diff, so it stands as it is.
    val root = projectRoot(project) ?: return problem

    // Past here the pane's own document is not a file under the project. A diff pane still knows the
    // project file its revision is a version of, and the name comes from THAT file rather than from
    // the pane's own: they are the same string for an ordinary revision, and only the project file
    // has a name at all when the pane's document belongs to nothing.
    val revisionOf = diffFileOf(dataContext)?.takeIf { VfsUtilCore.getRelativePath(it, root) != null }
    if (revisionOf != null) {
        // A revision whose text is the working copy's text is the checked-out branch's own HEAD,
        // which is what a person reading "this commit against one from last week" has on the newer
        // side. Its line numbers describe the file on disk, so the remark is honest and allowed.
        if (showsWorkingCopyText(editor, revisionOf)) return null
        // The advice names the working copy rather than "the other side of the diff": in a diff
        // between two commits neither pane is the working copy, so the other side may refuse the
        // same way and the person is sent in a circle.
        return "${revisionOf.name} here is a revision whose text is not what is on disk, so a " +
            "remark's line numbers would not describe the file. Add it on the working copy — the " +
            "working-tree side of this diff, or the file itself."
    }

    // Nothing resolved at all: the two messages that were already there, unchanged. The diff one is
    // this function's own; the other is what fileTargetProblem already said.
    val name = own?.name ?: return problem
    return if (editor.editorKind == EditorKind.DIFF) {
        "$name in this diff is a revision with no matching file under the project directory, " +
            "so a remark on it could not be found again."
    } else {
        problem
    }
}
