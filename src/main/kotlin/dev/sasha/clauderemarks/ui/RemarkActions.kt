package dev.sasha.clauderemarks.ui

import com.intellij.openapi.actionSystem.ActionGroup
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.ToggleAction
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import dev.sasha.clauderemarks.action.publishRemarks
import dev.sasha.clauderemarks.store.RemarkStore
import dev.sasha.clauderemarks.store.setRemarkAsksForAnswer

/**
 * Everything a person can do to a remark that already exists, built once and used from two places:
 * the gutter icon's click menu, which acts on the one remark under the icon, and the tool window
 * tree's right-click menu, which acts on whatever is selected.
 *
 * [ids] is a lambda, not a list. The tree rebuilds itself on every remark change, so a list read
 * when the menu was built is stale by the time anything in it is pressed.
 *
 * This is where a remark is changed after it was written. The input popup deliberately stays a
 * text box and nothing else: it is the action that has to stay fast, and a second chooser in it
 * turns it into a form. Anything that is not the remark's own text is picked here instead.
 *
 * Publish is in this menu rather than only on the toolbar because asking one question used to mean
 * five steps: write the remark, leave the editor, open the tool window, find the row, press Publish
 * Selected. It is called "Publish" in both places, one word, and it takes exactly `ids()`, which is
 * the one remark under the icon in the gutter and the whole selection in the tree. Naming it
 * differently per call site, or letting one item act on a different set from the item beside it, is
 * the kind of difference nobody notices until it does the wrong thing.
 */
fun remarkChangeActions(project: Project, ids: () -> List<String>): ActionGroup {
    // A plain DefaultActionGroup(vararg) is not a popup, so its children are inlined where it is
    // placed rather than becoming a nested submenu. That is what this menu wants: every item shows
    // up directly in the gutter menu and the tree's right-click menu.
    return DefaultActionGroup(
        AskForAnAnswerToggle(project, ids),
        DumbAwareAction.create("Publish") { publish(project, ids()) },
    )
}

/**
 * Turns remarks that already exist into questions, and back. Selected means every remark it would
 * act on already asks, so a mixed selection reads as off and one press turns the whole selection on.
 *
 * Writing a remark and only then deciding you want an answer to it is ordinary, which is why this is
 * a toggle rather than something fixed when the remark was written.
 *
 * It only sets the flag. It never publishes, even though [publish] sits right beside it: publishing
 * on the spot belongs to the gesture that writes the remark, and a right-click that hands remarks to
 * an agent nobody asked to hand them to is exactly the surprise worth avoiding.
 *
 * The update thread is the EDT on purpose. [isSelected] calls `ids()`, and in the tool window that
 * lambda reads the tree's current selection, which is Swing state and may not be touched from a
 * background thread.
 */
private class AskForAnAnswerToggle(
    private val project: Project,
    private val ids: () -> List<String>,
) : ToggleAction("Ask for an Answer"), DumbAware {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

    override fun isSelected(e: AnActionEvent): Boolean {
        val wanted = ids().toSet()
        if (wanted.isEmpty()) return false
        // Not `all {}` over the ids themselves: the tree can hand over a row that is not a remark,
        // and an id nothing matches would otherwise read as "yes, it asks".
        val matched = RemarkStore.getInstance(project).all().filter { it.id in wanted }
        return matched.isNotEmpty() && matched.all { it.asksForAnswer }
    }

    override fun setSelected(e: AnActionEvent, state: Boolean) {
        setRemarkAsksForAnswer(project, ids(), state)
    }
}

/**
 * Publishes exactly what the menu is acting on: the one remark under the gutter icon, or the whole
 * tree selection, which is the same set the tool window's own Publish Selected button takes.
 *
 * The empty check is here rather than left to the pipeline: an empty selection means the person
 * reached the menu without a row, and doing nothing is the honest answer. [publishRemarks] reads a
 * null id collection as "every unread remark", so handing it an empty list must not be confused with
 * handing it nothing.
 */
private fun publish(project: Project, ids: List<String>) {
    if (ids.isEmpty()) return
    publishRemarks(project, ids)
}
