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
import dev.sasha.clauderemarks.model.RemarkStatus
import dev.sasha.clauderemarks.store.RemarkStore
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
 * Publishing here is the point rather than a convenience: asking is one motion. It writes the
 * clipboard and the published file, the same as every other publish — this is the ordinary publish
 * and not a second kind of one.
 *
 * The batch it publishes is every question still waiting for an answer, not only the one just
 * written — see [askClaude] and `openQuestionIds` for why a narrower batch loses questions.
 */
class AskClaudeAction : AnAction() {

    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    /**
     * Enabled on the same terms [AddRemarkAction] is, and refusing in the same place — literally the
     * same rules, through [updateRemarkEntryPoint]. Only the hint differs.
     */
    override fun update(e: AnActionEvent) = updateRemarkEntryPoint(e, ASK_HINT)

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
 * Opens the input at the caret for a question about the current selection, then publishes the
 * question it wrote along with every other question still waiting for an answer. EDT only.
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
        askClaude(project, path, document.text.split("\n"), range, columns, typed)
    }
}

/**
 * What the gesture does once the question is typed: store the remark marked as asking for an answer,
 * then publish every question still waiting for an answer, this new one included. The two halves
 * together are the gesture — a question that is stored but not handed over is an ordinary remark, and
 * a remark handed over without the flag is a note nobody will answer.
 *
 * Internal, and split out of the popup's callback, so both halves can be pinned without a window:
 * showing the input needs one. [publish] is a parameter with the real publish as its default for the
 * same reason — `action/PublishRemarks.kt`'s own KDoc says the async pipeline is deliberately not
 * driven from a test, so the test asserts that this function *calls* it with the right ids rather
 * than pumping a read action, an EDT callback and the real clipboard.
 */
internal fun askClaude(
    project: Project,
    path: String,
    lines: List<String>,
    range: IntRange,
    columns: Pair<Int, Int>,
    typed: String,
    publish: (Project, Collection<String>) -> Unit = ::publishRemarks,
) {
    addRemark(
        project, path, lines, range, typed,
        startColumn = columns.first, endColumn = columns.second,
        asksForAnswer = true,
    )
    // The same publish the gutter's and the tree's Publish item calls. No second pipeline: asking is
    // Add Remark plus Publish, in that order, with the flag set. What differs from Publish Selected
    // is only which ids go in — see openQuestionIds for why this is not just the id just written.
    publish(project, openQuestionIds(project))
}

/**
 * Every remark that asks for an answer, is not yet `READ`, and has no answer stored against it. This
 * is the batch the Ask Claude gesture hands over, and it is deliberately wider than the one question
 * that was just typed.
 *
 * **Why not just the new remark.** `review/PublishedRemarks.kt`'s `writePublished` rewrites the whole
 * published file, and a watcher only looks every two seconds in file mode, five in fetch mode. So a
 * second question asked before the watcher looked would overwrite the first question's file, and the
 * first question would never be seen by any session and never answered — its row saying "asks"
 * forever, because the remark is already `PUBLISHED` and only a later Publish Unread or a hand
 * re-select would carry it again. Asking twice in a row is the ordinary way this gesture is used, not
 * an edge case, so the batch heals itself instead: every ask carries every question still open, and
 * an overwritten batch is republished by the next ask. It costs nothing in the ordinary one-question
 * case, where there is exactly one such remark.
 *
 * **Why an answered question is left out.** An answered remark is still `PUBLISHED`, not `READ` —
 * only an acknowledgement moves a remark to `READ`, and answering is not one. Without this filter,
 * every question ever asked would ride along with every later ask forever, and each one would be
 * answered again, replacing an answer the person may already have read (`recordAnswer` upserts on
 * `remarkId`). Having an answer is the closest thing to "this question is finished" the store holds,
 * so that is what ends a question's membership here. Re-asking an answered question is still possible
 * — it is Publish Selected on that row, which is what deliberately re-asking should look like.
 *
 * **What this does not fix: the ordering of two publishes.** Two gestures still run two independent
 * read actions, and whichever finishes last writes the file, which can be the earlier one. That race
 * survives, but it stops losing a question outright: the sets now nest, so the worst case is the
 * larger batch being replaced by the smaller one it contains, and the next ask carries the leftover
 * again. It also needs both publishes in flight at the same instant, not merely inside one watcher
 * poll, and a person typing a question into a popup cannot easily produce that.
 *
 * Plain Publish Selected is untouched: it still publishes exactly the rows that were picked.
 *
 * Read off the store on the EDT this whole gesture runs on. Both calls are snapshots, and both are
 * the read-only accessors CLAUDE.md's guard 3 exempts by name.
 */
private fun openQuestionIds(project: Project): List<String> {
    val answered = RemarkStore.getInstance(project).allAnswers().mapNotNull { it.remarkId }.toSet()
    return RemarkStore.getInstance(project).all()
        .filter { it.asksForAnswer && it.status != RemarkStatus.READ && it.id !in answered }
        .mapNotNull { it.id }
}
