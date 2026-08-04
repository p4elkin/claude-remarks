package dev.sasha.clauderemarks.ui

import com.intellij.ide.dnd.DnDDragStartBean
import com.intellij.ide.dnd.DnDEvent
import com.intellij.ide.dnd.DnDSupport
import com.intellij.ide.dnd.aware.DnDAwareTree
import com.intellij.openapi.Disposable
import com.intellij.openapi.project.Project
import com.intellij.util.ui.tree.TreeUtil
import dev.sasha.clauderemarks.store.setRemarkBucket
import javax.swing.tree.DefaultMutableTreeNode

/**
 * The drag half of the remarks tree: dragging rows onto a bucket row moves them into it.
 *
 * Its own file, beside `RemarksTree.kt`, which holds the pure node building and the pure
 * [bucketDropTarget] this wiring calls. `RemarksToolWindowFactory.kt` is already the largest file in
 * the package and owns the tree, the banner, the toolbar, the menu, navigation and the deletes; the
 * drag is a separate subject with its own platform dependency on `com.intellij.ide.dnd`, so it reads
 * better next to the decision it drives than inside the panel.
 *
 * Nothing here decides anything: the decision is [bucketDropTarget], which is pure and tested,
 * because a real drag cannot be driven from a unit test — the platform's DnD machinery needs a
 * window, a pointer and a running event loop.
 */

/**
 * What a drag carries: the remark ids that were under the selection when it started.
 *
 * Its own type, rather than a bare list, so a drag that started somewhere else — a file from the
 * project view, a change from the commit tool window — is refused instead of being read as a list of
 * ids that happens to arrive at the right component.
 */
private data class DraggedRemarks(val ids: List<String>)

/**
 * Dragging rows onto a bucket moves them into it, and dropping them on "(no bucket)" takes them out
 * of the one they are in. This function only finds the node under the pointer and calls the mutation
 * function; [bucketDropTarget] is the whole decision.
 *
 * `setRemarkBucket` is the same function the right-click menu's Move to Bucket… calls, so the drag
 * adds no second way to change a bucket — it publishes REMARKS_CHANGED, and the panel's own
 * subscription rebuilds the tree.
 *
 * [parent] becomes the support's `setDisposableParent`, so the support goes away with the tool
 * window rather than outliving it as a registered source on a dead component.
 *
 * [selectedIds] is passed in rather than read off [tree] here, so the drag and Publish Selected can
 * never disagree about what a selection covers. Both go through the panel's one selection rule, in
 * which a group node stands for every row under it — which is what makes dragging a file group move
 * every remark in that file.
 *
 * There is no "New bucket…" row to drop onto, on purpose: a bucket exists only because some remark
 * carries its name, so an empty one would vanish on the next refresh, and Move to Bucket… in the
 * right-click menu already creates a bucket by name.
 */
internal fun installDragToBucket(
    tree: DnDAwareTree,
    project: Project,
    parent: Disposable,
    selectedIds: () -> List<String>,
) {
    DnDSupport.createBuilder(tree)
        .setBeanProvider {
            selectedIds().takeIf { it.isNotEmpty() }?.let { DnDDragStartBean(DraggedRemarks(it)) }
        }
        .setTargetChecker { event ->
            val drop = dropTargetOf(tree, event)
            if (drop == null || event.attachedObject !is DraggedRemarks) {
                event.setDropPossible(false, "")
                // true means "not mine, ask the parent component", which is what a drag from
                // somewhere else and a drop on a row that is not a bucket both are.
                return@setTargetChecker true
            }
            event.setDropPossible(true)
            false
        }
        .setDropHandler { event ->
            val dragged = event.attachedObject as? DraggedRemarks ?: return@setDropHandler
            val drop = dropTargetOf(tree, event) ?: return@setDropHandler
            setRemarkBucket(project, dragged.ids, drop.bucket)
        }
        .setDisposableParent(parent)
        .install()
}

/**
 * The node under the pointer, turned into what a drop there would mean.
 *
 * The point arrives in screen-relative form and has to be converted to the tree's own coordinates
 * before any row lookup, which is what `RelativePoint.getPoint(tree)` does. This is the same two-step
 * the platform's own change-tree drag support uses.
 */
private fun dropTargetOf(tree: DnDAwareTree, event: DnDEvent): BucketDrop? {
    val onTree = event.relativePoint.getPoint(tree)
    val path = TreeUtil.getPathForLocation(tree, onTree.x, onTree.y) ?: return null
    return bucketDropTarget(path.lastPathComponent as? DefaultMutableTreeNode)
}
