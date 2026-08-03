package dev.sasha.clauderemarks.action

import com.intellij.codeInsight.hint.HintManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.ui.popup.JBPopupFactory
import dev.sasha.clauderemarks.model.RemarkTag
import dev.sasha.clauderemarks.store.addGeneralRemark
import dev.sasha.clauderemarks.store.addRemark
import dev.sasha.clauderemarks.store.editRemark
import dev.sasha.clauderemarks.store.relativePathOf
import dev.sasha.clauderemarks.store.remarkTargetProblem
import dev.sasha.clauderemarks.ui.RemarkInput
import dev.sasha.clauderemarks.ui.RemarkInputPanel
import java.awt.Component

private const val ADD_HINT = "Attach a remark to the selected lines"

class AddRemarkAction : AnAction() {

    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    /**
     * Visible and enabled whenever there is a project and an editor to act on. Whether a remark
     * can actually be stored here (relativePathOf, projectRoot...) is NOT part of enablement any
     * more: a diff viewer popup shows a greyed-out item's description nowhere the user can see, so
     * a refusal there used to be indistinguishable from a bug. actionPerformed shows the same
     * reason at the caret instead, where it is guaranteed visible. The description is still set,
     * because the main menu and Search Everywhere DO show it.
     */
    override fun update(e: AnActionEvent) {
        val editor = e.getData(CommonDataKeys.EDITOR)
        val project = e.project
        val problem = when {
            project == null -> "No project is open."
            editor == null -> "No editor is focused."
            else -> remarkTargetProblem(project, editor, e.dataContext)
        }
        e.presentation.isVisible = true
        e.presentation.isEnabled = project != null && editor != null
        e.presentation.description = problem ?: ADD_HINT
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return
        openNewRemarkInput(project, editor, e.dataContext)
    }
}

/**
 * Opens the input at the caret for a new remark on the current selection. EDT only.
 *
 * Both callers (the action and the Alt+Enter intention) funnel through here, so the refusal
 * reason is shown in exactly one place: showErrorHint puts it at the caret, which works in every
 * context an editor exists in, including a diff viewer popup where a presentation description is
 * never shown to the user.
 *
 * [dataContext] is only used to reach the diff's real file (see store/RemarkTarget.kt). The action
 * passes the event's context; the intention builds one from the editor component, which it can only
 * do here because invoke runs on the EDT.
 */
fun openNewRemarkInput(project: Project, editor: Editor, dataContext: DataContext? = null) {
    val problem = remarkTargetProblem(project, editor, dataContext)
    if (problem != null) {
        HintManager.getInstance().showErrorHint(editor, problem)
        return
    }
    val path = relativePathOf(project, editor, dataContext) ?: return
    val document = editor.document
    val selection = editor.selectionModel
    val range = selectedLines(document, selection.selectionStart, selection.selectionEnd)
    val columns = selectedColumns(document, selection.selectionStart, selection.selectionEnd)
    val stampWhenOpened = document.modificationStamp

    showRemarkInput(project, editor, "Add Claude Remark", "", null) { input ->
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
        addRemark(
            project, path, document.text.split("\n"), range, input.text, input.tag,
            startColumn = columns.first, endColumn = columns.second,
        )
    }
}

/** Opens the input on a remark that already exists. EDT only. */
fun openRemarkEdit(project: Project, editor: Editor, id: String, text: String, tag: RemarkTag?) {
    showRemarkInput(project, editor, "Edit Claude Remark", text, tag) { input ->
        editRemark(project, id, input.text, input.tag)
    }
}

/**
 * Opens the input for a remark about the whole change, not about any one file. There is no editor
 * to position the popup against here, so `showInBestPositionFor(editor)` — what every other opener
 * of this popup uses — is not available; this shows it centred over [component] instead, which is
 * the tool window's tree. RemarkInputPanel itself needs no change for this: it already only knows
 * about text and a tag, never a file.
 */
fun openGeneralRemarkInput(project: Project, component: Component) {
    buildInputPopup(project, "Add General Claude Remark", "", null) { input ->
        addGeneralRemark(project, input.text, input.tag)
    }.showInCenterOf(component)
}

/**
 * setCancelKeyEnabled(true) is what gives Esc for free. Positioning is the caller's job:
 * showRemarkInput puts it at the caret with showInBestPositionFor(editor), the only placement an
 * editor allows; openGeneralRemarkInput, which has no editor, centres it over a component instead.
 *
 * setCancelOnWindowDeactivation(false) is there because the class-name chooser is a second JBPopup
 * opened from inside this one. Deactivating this popup's window must not throw away a half-typed
 * remark. Clicking elsewhere in the IDE still abandons it: cancelOnClickOutside is left at its
 * default true, and it is safe to leave there, because StackingPopupDispatcherImpl only ever cancels
 * the TOP of the popup stack — which while the chooser is up is the chooser, not this popup.
 */
private fun buildInputPopup(
    project: Project,
    title: String,
    text: String,
    tag: RemarkTag?,
    onSubmit: (RemarkInput) -> Unit,
): JBPopup {
    val panel = RemarkInputPanel(project, text, tag)
    val popup = JBPopupFactory.getInstance()
        .createComponentPopupBuilder(panel, panel.textArea)
        .setTitle(title)
        .setRequestFocus(true)
        .setFocusable(true)
        .setCancelKeyEnabled(true)
        .setCancelOnWindowDeactivation(false)
        .setMovable(true)
        .setResizable(true)
        .createPopup()
    panel.onSubmit = { input ->
        popup.cancel()
        onSubmit(input)
    }
    return popup
}

private fun showRemarkInput(
    project: Project,
    editor: Editor,
    title: String,
    text: String,
    tag: RemarkTag?,
    onSubmit: (RemarkInput) -> Unit,
) {
    buildInputPopup(project, title, text, tag, onSubmit).showInBestPositionFor(editor)
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

/**
 * The sub-line columns a selection covers, or `0 to 0` — "no sub-line range" — when the selection
 * does not describe one:
 * - empty (caret only, no selection)
 * - covers whole lines, from the first character of the first line to the last character of the
 *   last line (gutter drag, shift+down, Ctrl+A)
 *
 * Otherwise the first of the pair is the column [selectedLines]'s start line begins at, and the
 * second is the column its end line stops at — the same [IntRange] this pair rides alongside, so a
 * selection spanning several lines is expressed the same way a one-line selection is: a start
 * column on the first line, an end column on the last, nothing in between. The renderer draws the
 * two boundary markers on whichever quoted lines those turn out to be.
 *
 * Shares [selectedLines]'s exclusive-selectionEnd correction: selecting whole lines by dragging the
 * gutter leaves selectionEnd at the first offset of the following line, which is not part of what
 * was selected, so the effective end line is stepped back one and its end column taken as that
 * line's own length.
 */
fun selectedColumns(document: Document, selectionStart: Int, selectionEnd: Int): Pair<Int, Int> {
    if (selectionStart == selectionEnd) return 0 to 0

    val startLine = document.getLineNumber(selectionStart)
    val rawEndLine = document.getLineNumber(selectionEnd)
    val endsOnAFreshLine =
        rawEndLine > startLine && document.getLineStartOffset(rawEndLine) == selectionEnd
    val endLine = if (endsOnAFreshLine) rawEndLine - 1 else rawEndLine
    val endOffset = if (endsOnAFreshLine) document.getLineEndOffset(endLine) else selectionEnd

    val startColumn = selectionStart - document.getLineStartOffset(startLine)
    val endColumn = endOffset - document.getLineStartOffset(endLine)
    val endLineLength = document.getLineEndOffset(endLine) - document.getLineStartOffset(endLine)

    val wholeLines = startColumn <= 0 && endColumn >= endLineLength
    if (wholeLines) return 0 to 0
    return startColumn to endColumn
}
