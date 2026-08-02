package dev.sasha.clauderemarks.action

import com.intellij.codeInsight.intention.IntentionAction
import com.intellij.ide.DataManager
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorKind
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiFile
import dev.sasha.clauderemarks.store.remarkTargetProblem

/**
 * The Alt+Enter way in. Same destination as the Ctrl+Alt+Shift+R action and the editor popup menu.
 *
 * startInWriteAction() is false: this opens a popup and stores nothing itself. Returning true
 * would hold a write lock open across a modal popup.
 */
class AddRemarkIntention : IntentionAction {

    override fun getText() = "Add Claude remark"

    override fun getFamilyName() = "Claude remarks"

    /**
     * A diff pane is offered without checking whether the remark can really be stored, and that is
     * deliberate. In a diff the answer needs DiffDataKeys.CURRENT_CONTENT, which needs a
     * DataContext, and DataManager.getDataContext(component) asserts it is on the EDT — while the
     * daemon computes intention availability on a background thread. So the check moves to invoke,
     * which does run on the EDT: it either opens the input or shows the reason at the caret. The
     * same trade the action already makes, for the same reason.
     */
    override fun isAvailable(project: Project, editor: Editor?, file: PsiFile?): Boolean =
        editor != null &&
            (editor.editorKind == EditorKind.DIFF || remarkTargetProblem(project, editor) == null)

    override fun invoke(project: Project, editor: Editor?, file: PsiFile?) {
        if (editor == null) return
        // EDT here, so a DataContext can be built. For an ordinary editor it carries no diff
        // content and changes nothing.
        openNewRemarkInput(
            project,
            editor,
            DataManager.getInstance().getDataContext(editor.contentComponent),
        )
    }

    override fun startInWriteAction() = false
}
