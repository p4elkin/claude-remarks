package dev.sasha.clauderemarks.action

import com.intellij.codeInsight.hint.HintManager
import com.intellij.codeInsight.intention.IntentionAction
import com.intellij.ide.DataManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorKind
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.psi.PsiFile
import dev.sasha.clauderemarks.store.addRemark
import dev.sasha.clauderemarks.store.relativePathOf
import dev.sasha.clauderemarks.store.remarkTargetProblem

private const val ASK_HINT = "Ask Claude about the selected lines and get the answer back on the line"

/**
 * The second entry point beside [AddRemarkAction]. It writes a remark that asks for an answer, and
 * publishes it on the spot.
 *
 * Ctrl+Alt+Shift+R writes an ordinary remark: work to do, or a topic to raise, travelling with the
 * next publish. Ctrl+Alt+Shift+A writes a question the agent answers, and the answer comes back onto
 * the line. The gesture carries the intent, so nothing has to guess which kind a remark is.
 *
 * Publishing here is the point rather than a convenience: asking is one motion. Two side effects
 * come with that, because this is the ordinary publish and not a second kind of one. It writes the
 * clipboard, as every publish does. And it answers a waiting review if one is waiting, which
 * consumes that review's single answer.
 */
class AskClaudeAction : AnAction() {

    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    /** Enabled on the same terms [AddRemarkAction] is, and refusing in the same place. */
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
        e.presentation.description = problem ?: ASK_HINT
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return
        openAskClaudeInput(project, editor, e.dataContext)
    }
}

/**
 * The Alt+Enter way in, beside [AddRemarkIntention]. Same destination as the Ctrl+Alt+Shift+A
 * action and the editor popup menu, so the gesture has the same three entry points the ordinary
 * remark has.
 */
class AskClaudeIntention : IntentionAction {

    override fun getText() = "Ask Claude about these lines"

    override fun getFamilyName() = "Claude remarks"

    /** Offered on the same terms [AddRemarkIntention] is, and for the same reason. */
    override fun isAvailable(project: Project, editor: Editor?, file: PsiFile?): Boolean =
        editor != null &&
            (editor.editorKind == EditorKind.DIFF || remarkTargetProblem(project, editor) == null)

    override fun invoke(project: Project, editor: Editor?, file: PsiFile?) {
        if (editor == null) return
        openAskClaudeInput(
            project,
            editor,
            DataManager.getInstance().getDataContext(editor.contentComponent),
        )
    }

    override fun startInWriteAction() = false
}

/**
 * Opens the input at the caret for a question about the current selection, then publishes the one
 * remark it wrote. EDT only.
 *
 * This is deliberately a sibling of [openNewRemarkInput] rather than a flag on it. The two differ in
 * the title they show and in what happens after the remark is stored, and a boolean threaded through
 * the ordinary path would put "and then publish" inside the function every other entry point calls.
 * Everything the two genuinely share — the refusal, the line and column math, the popup itself — is
 * shared code already: [remarkTargetProblem], [selectedLines], [selectedColumns], [showRemarkInput].
 */
fun openAskClaudeInput(project: Project, editor: Editor, dataContext: DataContext? = null) {
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

    showRemarkInput(project, editor, "Ask Claude About These Lines", "") { typed ->
        // Same refusal openNewRemarkInput makes, for the same reason: the line range was taken when
        // the box opened and the text is read seconds later, so a document that changed in between
        // would have the question anchored to code the person never chose.
        if (document.modificationStamp != stampWhenOpened) {
            Messages.showWarningDialog(
                project,
                "The file changed while the question box was open, so nothing was asked. " +
                    "Select the lines again.",
                "Claude Remark Not Added",
            )
            return@showRemarkInput
        }
        val remark = addRemark(
            project, path, document.text.split("\n"), range, typed,
            startColumn = columns.first, endColumn = columns.second,
            asksForAnswer = true,
        )
        // The same publish the gutter's and the tree's Publish item calls, on this one remark. No
        // second pipeline: asking is Add Remark plus Publish, in that order, with the flag set.
        publishRemarks(project, listOf(remark.id ?: return@showRemarkInput))
    }
}
