package dev.sasha.clauderemarks.action

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.popup.JBPopupFactory
import dev.sasha.clauderemarks.model.RemarkTag
import dev.sasha.clauderemarks.store.addRemark
import dev.sasha.clauderemarks.store.editRemark
import dev.sasha.clauderemarks.store.relativePathOf
import dev.sasha.clauderemarks.store.remarkTargetProblem
import dev.sasha.clauderemarks.ui.RemarkInput
import dev.sasha.clauderemarks.ui.RemarkInputPanel

private const val ADD_HINT = "Attach a remark to the selected lines"

class AddRemarkAction : AnAction() {

    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    /**
     * Visible always, enabled only where a remark can actually be stored, and the reason goes in
     * the description. isEnabledAndVisible would REMOVE the item from the menu instead of greying
     * it out, which is what the debug action did and what users read as a broken plugin.
     */
    override fun update(e: AnActionEvent) {
        val editor = e.getData(CommonDataKeys.EDITOR)
        val project = e.project
        val problem = when {
            project == null -> "No project is open."
            editor == null -> "No editor is focused."
            else -> remarkTargetProblem(project, editor)
        }
        e.presentation.isVisible = true
        e.presentation.isEnabled = problem == null
        e.presentation.description = problem ?: ADD_HINT
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return
        openNewRemarkInput(project, editor)
    }
}

/** Opens the input at the caret for a new remark on the current selection. EDT only. */
fun openNewRemarkInput(project: Project, editor: Editor) {
    if (remarkTargetProblem(project, editor) != null) return
    val path = relativePathOf(project, editor) ?: return
    val document = editor.document
    val selection = editor.selectionModel
    val range = selectedLines(document, selection.selectionStart, selection.selectionEnd)
    val stampWhenOpened = document.modificationStamp

    showRemarkInput(editor, "Add Claude Remark", "", null) { input ->
        // The line range was taken when the box opened; the text is read now, seconds later. If
        // the document changed in between, those line numbers point at code the user never chose,
        // and the anchor would be captured from it. Refuse rather than anchor to the wrong lines.
        if (document.modificationStamp != stampWhenOpened) {
            Messages.showWarningDialog(
                project,
                "The file changed while the remark box was open, so the remark was not added. " +
                    "Select the lines again.",
                "Claude Remark Not Added",
            )
            return@showRemarkInput
        }
        addRemark(project, path, document.text.split("\n"), range, input.text, input.tag)
    }
}

/** Opens the input on a remark that already exists. EDT only. */
fun openRemarkEdit(project: Project, editor: Editor, id: String, text: String, tag: RemarkTag?) {
    showRemarkInput(editor, "Edit Claude Remark", text, tag) { input ->
        editRemark(project, id, input.text, input.tag)
    }
}

/**
 * setCancelKeyEnabled(true) is what gives Esc for free. showInBestPositionFor(editor) puts the
 * popup at the caret in one call, without guessBestPopupLocation.
 */
private fun showRemarkInput(
    editor: Editor,
    title: String,
    text: String,
    tag: RemarkTag?,
    onSubmit: (RemarkInput) -> Unit,
) {
    val panel = RemarkInputPanel(text, tag)
    val popup = JBPopupFactory.getInstance()
        .createComponentPopupBuilder(panel, panel.textArea)
        .setTitle(title)
        .setRequestFocus(true)
        .setFocusable(true)
        .setCancelKeyEnabled(true)
        .setMovable(true)
        .setResizable(true)
        .createPopup()
    panel.onSubmit = { input ->
        popup.cancel()
        onSubmit(input)
    }
    popup.showInBestPositionFor(editor)
}

/**
 * The 0-based, inclusive line range a selection covers.
 *
 * Carried over unchanged from the deleted AddDebugRemarkAction, same package and same name, so
 * SelectedLinesTest needs no edit.
 *
 * selectionEnd is exclusive. Selecting whole lines (gutter drag, shift+down, Ctrl+A) leaves it at
 * the first offset of the following line, which would anchor one line more than the user selected.
 * With no selection at all both offsets are the caret, which gives the caret's line.
 */
fun selectedLines(document: Document, selectionStart: Int, selectionEnd: Int): IntRange {
    val startLine = document.getLineNumber(selectionStart)
    val endLine = document.getLineNumber(selectionEnd)
    val endsOnAFreshLine =
        endLine > startLine && document.getLineStartOffset(endLine) == selectionEnd
    return startLine..(if (endsOnAFreshLine) endLine - 1 else endLine)
}
