package dev.sasha.clauderemarks.ui

import com.intellij.openapi.actionSystem.ActionGroup
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import dev.sasha.clauderemarks.action.plural
import dev.sasha.clauderemarks.store.RemarkStore
import dev.sasha.clauderemarks.store.setRemarkBucket

/**
 * The actions that change remarks that already exist, built once and used from two places: the
 * gutter icon's click menu, which acts on the one remark under the icon, and the tool window
 * tree's right-click menu, which acts on whatever is selected.
 *
 * [ids] is a lambda, not a list. The tree rebuilds itself on every remark change, so a list read
 * when the menu was built is stale by the time anything in it is pressed.
 *
 * This is where a remark is changed after it was written. The input popup deliberately stays a
 * text box and nothing else: it is the action that has to stay fast, and a second chooser in it
 * turns it into a form. Anything that is not the remark's own text is picked here instead.
 */
fun remarkChangeActions(project: Project, ids: () -> List<String>): ActionGroup {
    // A plain DefaultActionGroup(vararg) is not a popup, so its children are inlined where it is
    // placed rather than becoming a nested submenu. That is what this menu wants: every item shows
    // up directly in the gutter menu and the tree's right-click menu.
    return DefaultActionGroup(
        DumbAwareAction.create("Move to Bucket…") { chooseBucket(project, ids()) },
    )
}

/**
 * An editable chooser, not a plain text prompt. Picking an existing bucket by name is the common
 * case, and re-typing it is exactly how "auth refactor" and "auth-refactor" become two buckets that
 * look like one from across the tree. An empty answer means no bucket, which is how a remark comes
 * back out of one. Cancel returns null and changes nothing.
 *
 * The dialog is not parented to the project window, and cannot be: `Messages` has exactly one
 * `showEditableChooseDialog` overload in 2025.2 and it takes no `Project`. The parented alternative,
 * `showChooseDialog(Project, ...)`, returns an index into a fixed list, so it cannot express "type a
 * name that is not in the list yet" — which is the whole reason this dialog is the editable one.
 */
private fun chooseBucket(project: Project, ids: List<String>) {
    if (ids.isEmpty()) return
    val stored = RemarkStore.getInstance(project).all()
    val existing = stored.mapNotNull { it.bucket }.distinct().sorted()
    val current = stored.firstOrNull { it.id == ids.first() }?.bucket.orEmpty()
    val chosen = Messages.showEditableChooseDialog(
        "Bucket for ${ids.size} remark${plural(ids.size)} (leave empty for none):",
        "Move to Bucket",
        null,
        existing.toTypedArray(),
        current,
        null,
    ) ?: return
    setRemarkBucket(project, ids, chosen)
}
