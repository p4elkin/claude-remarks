package dev.sasha.clauderemarks.action

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.PlatformDataKeys
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import dev.sasha.clauderemarks.preview.PreviewSelectionService
import dev.sasha.clauderemarks.preview.StoredSelection
import dev.sasha.clauderemarks.store.addRemark
import dev.sasha.clauderemarks.store.fileTargetProblem
import dev.sasha.clauderemarks.store.relativePathOf

private const val PREVIEW_HINT = "Attach a remark to the words selected in the rendered preview"

/** Said by [previewRemarkProblem] and by the action, so the two cannot drift into two sentences. */
internal const val NOTHING_SELECTED = "Select something in the rendered preview first, then try again."

private const val NO_DOCUMENT =
    "The file behind this preview has no text open in the IDE, so a remark could not be stored."

/** Internal for the same reason as [NOTHING_SELECTED]: [previewStampProblem]'s test reads it. */
internal const val MOVED_ON =
    "The file changed after that selection was made, so the remark was not added. Select the " +
        "words in the preview again."

/**
 * The impossible pair, said once. Past [fileTargetProblem] the file is there and it resolves to a
 * path under the project root, so nothing below can take this branch. It refuses with a dialog all
 * the same, because a silent no-op here would look exactly like a broken plugin — the same argument
 * the class KDoc below makes about a greyed-out menu item.
 */
private const val NO_TARGET =
    "The file behind this preview could not be found under the project directory, so the remark " +
        "was not added."

private const val NOT_ADDED_TITLE = "Claude Remark Not Added"

/**
 * Whether the selection the browser stored is the one this right click is about, in words a person
 * can read, or null when it is.
 *
 * Pure, and that is deliberate: it is the only part of the preview entry point a test can reach.
 * Everything around it needs a JCEF panel, a rendered page and a real browser selection.
 *
 * [fileUrl] is the file the action's own data context names, and it may be absent. A null there is
 * not a refusal: the stored url is then the only thing naming a file, and it names the preview the
 * person made the selection in, which is the right file to act on. A url that is present and
 * different is a refusal, because that is two previews side by side, the selection made in one and
 * the right click done in the other.
 */
fun previewRemarkProblem(stored: StoredSelection?, fileUrl: String?): String? {
    if (stored == null) return NOTHING_SELECTED
    if (fileUrl != null && fileUrl != stored.fileUrl) {
        return "The current selection is in another preview, so a remark here would be about a " +
            "file you were not looking at. Select the words again in this preview."
    }
    return null
}

/**
 * Whether the source has changed since the page reported that selection, in words a person can read,
 * or null when it has not.
 *
 * The window this closes is the one between the browser posting a selection and the person opening
 * the menu. Nothing else covers it. The stamp the action takes when the popup opens covers only the
 * seconds the input box is on screen, and the length check covers only a file that got shorter —
 * an edit that leaves the file the same length, or longer, moves the words under those offsets while
 * they still fit, and the remark then lands on text the person never chose.
 *
 * A stamp comparison rather than a diff of the text: `Document.getModificationStamp()` changes on
 * every edit, so this refuses after an edit that put the words back exactly where they were. The
 * cost is one selection made again in a case nobody will notice.
 *
 * Pure, like [previewRemarkProblem] and for the same reason: it is the part of this the tests can
 * reach.
 */
fun previewStampProblem(stored: StoredSelection, documentStamp: Long): String? =
    if (stored.sourceStamp == documentStamp) null else MOVED_ON

/**
 * The entry point in the rendered markdown preview's right-click menu.
 *
 * Registered in `META-INF/claude-remarks-markdown.xml`, into the markdown plugin's own
 * `Markdown.PreviewGroup`, so it exists only where a markdown preview does. Its id is not pinned by
 * `ActionIdsTest` for exactly that reason: the test fixture does not load the markdown plugin, so an
 * assertion about this action would fail on a correct build. `README.md` names it instead.
 *
 * **This action asks the page nothing.** The script injected into the preview posts every selection
 * as it happens and `PreviewSelectionService` keeps the last one, so by the time the menu opens the
 * answer is already in memory. See `preview/PreviewRemarkExtension.kt` for why the push direction
 * was chosen over a request and a response.
 *
 * **Enabled even when it will refuse**, the same choice [AddRemarkAction] makes and for the same
 * reason: this is a popup menu, and a greyed-out item's description is shown nowhere a person can
 * see, so a refusal there is indistinguishable from a broken plugin. The refusal is a dialog rather
 * than a hint at the caret, because there is no editor here to put a hint in.
 */
class AddPreviewRemarkAction : AnAction() {

    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val project = e.project
        e.presentation.isVisible = true
        e.presentation.isEnabled = project != null
        e.presentation.description = when (project) {
            null -> "No project is open."
            else -> refusal(project, e) ?: PREVIEW_HINT
        }
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val stored = PreviewSelectionService.getInstance(project).current()
            ?: return refuse(project, NOTHING_SELECTED)
        previewRemarkProblem(stored, e.getData(CommonDataKeys.VIRTUAL_FILE)?.url)
            ?.let { return refuse(project, it) }

        val file = selectionFile(stored)
        fileTargetProblem(project, file)?.let { return refuse(project, it) }
        // One check for the pair fileTargetProblem has already ruled out, rather than a silent
        // return for one of them and a "!!" for the other two lines apart. Past this the compiler
        // knows the file is there, so the rest of the method needs neither.
        val path = file?.let { relativePathOf(project, it) }
        if (file == null || path == null) return refuse(project, NO_TARGET)
        val document = FileDocumentManager.getInstance().getDocument(file)
            ?: return refuse(project, NO_DOCUMENT)

        // The source may have been edited since the page reported this selection, which moves the
        // words under those offsets while the offsets themselves stay valid.
        previewStampProblem(stored, document.modificationStamp)?.let { return refuse(project, it) }

        // The offsets were checked against the source when the page reported them, and the file may
        // have got shorter since. selectedLines would throw on an offset past the end rather than
        // refuse, so the check happens here. The stamp check above already covers every edit this
        // could catch; this stays as the guard on the throw itself, which is not something to leave
        // resting on another check being correct.
        val range = stored.range
        if (range.endOffsetExclusive > document.textLength) return refuse(project, MOVED_ON)

        val lines = selectedLines(document, range.startOffset, range.endOffsetExclusive)
        val columns = selectedColumns(document, range.startOffset, range.endOffsetExclusive)
        val stampWhenOpened = document.modificationStamp

        val popup = buildInputPopup(project, "Add Claude Remark", "") { typed ->
            // The same guard openNewRemarkInput makes, for the same reason: the range was taken
            // when the box opened and the text is read now, seconds later. If the file changed in
            // between, those offsets describe characters the person never chose.
            if (document.modificationStamp != stampWhenOpened) {
                Messages.showWarningDialog(project, MOVED_ON, NOT_ADDED_TITLE)
                return@buildInputPopup
            }
            addRemark(
                project, path, document.text.split("\n"), lines, typed,
                startColumn = columns.first, endColumn = columns.second,
            )
        }
        // The preview is a browser component with no caret to position against, so the popup is
        // centred over it, the same placement openGeneralRemarkInput uses over the tool window's
        // tree. showInFocusCenter is the fallback for a context that names no component at all.
        val component = e.getData(PlatformDataKeys.CONTEXT_COMPONENT)
        if (component == null) popup.showInFocusCenter() else popup.showInCenterOf(component)
    }

    /** The whole refusal, selection first and then the file it names. Used by update's description. */
    private fun refusal(project: Project, e: AnActionEvent): String? {
        val stored = PreviewSelectionService.getInstance(project).current() ?: return NOTHING_SELECTED
        return previewRemarkProblem(stored, e.getData(CommonDataKeys.VIRTUAL_FILE)?.url)
            ?: fileTargetProblem(project, selectionFile(stored))
    }

    /**
     * The file the selection was made in, which is the file the remark is stored against. The stored
     * url is the authority here rather than the data context: [previewRemarkProblem] has already
     * refused the case where the two disagree, and the context may name no file at all.
     */
    private fun selectionFile(stored: StoredSelection): VirtualFile? =
        VirtualFileManager.getInstance().findFileByUrl(stored.fileUrl)

    private fun refuse(project: Project, problem: String) =
        Messages.showWarningDialog(project, problem, NOT_ADDED_TITLE)
}
