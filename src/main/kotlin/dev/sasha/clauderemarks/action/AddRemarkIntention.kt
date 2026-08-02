package dev.sasha.clauderemarks.action

import com.intellij.codeInsight.intention.IntentionAction
import com.intellij.openapi.editor.Editor
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

    override fun isAvailable(project: Project, editor: Editor?, file: PsiFile?): Boolean =
        editor != null && remarkTargetProblem(project, editor) == null

    override fun invoke(project: Project, editor: Editor?, file: PsiFile?) {
        if (editor != null) openNewRemarkInput(project, editor)
    }

    override fun startInWriteAction() = false
}
