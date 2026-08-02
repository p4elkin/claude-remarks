package dev.sasha.clauderemarks.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionGroup
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionToolbar
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonShortcuts
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.Separator
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.PopupHandler
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.content.ContentFactory
import com.intellij.ui.treeStructure.Tree
import com.intellij.util.EditSourceOnDoubleClickHandler
import com.intellij.util.concurrency.AppExecutorUtil
import dev.sasha.clauderemarks.action.copyRemarks
import dev.sasha.clauderemarks.action.notifyRemarks
import dev.sasha.clauderemarks.model.RemarkStatus
import dev.sasha.clauderemarks.store.REMARKS_CHANGED
import dev.sasha.clauderemarks.store.RemarkStore
import dev.sasha.clauderemarks.store.RemarksListener
import dev.sasha.clauderemarks.store.ResolvedRemark
import dev.sasha.clauderemarks.store.clearAllRemarks
import dev.sasha.clauderemarks.store.clearSentRemarks
import dev.sasha.clauderemarks.store.deleteRemark
import dev.sasha.clauderemarks.store.fileForStoredPath
import dev.sasha.clauderemarks.store.notifyRemarksChanged
import dev.sasha.clauderemarks.store.projectRoot
import dev.sasha.clauderemarks.store.resolveAll
import javax.swing.Icon
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreePath

class RemarksToolWindowFactory : ToolWindowFactory {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = RemarksPanel(project, toolWindow.disposable)
        toolWindow.contentManager.addContent(
            ContentFactory.getInstance().createContent(panel, null, false)
        )
    }
}

/**
 * A tree grouped by file, rebuilt whole on each refresh. It refreshes itself on any remark change,
 * and on an editor opening or closing too, because RemarkGutter publishes REMARKS_CHANGED for
 * those as well. So the phase 2 problem of an empty-looking window after adding a remark is gone.
 * The Refresh button stays as the manual escape, because nothing refreshes while you type.
 */
class RemarksPanel(
    private val project: Project,
    private val parent: Disposable,
) : SimpleToolWindowPanel(true, true) {

    /** Internal, not private, so RemarksPanelTest can look at what the refresh left on screen. */
    internal val tree = Tree(DefaultTreeModel(DefaultMutableTreeNode("remarks")))

    init {
        tree.isRootVisible = false
        tree.showsRootHandles = true
        tree.cellRenderer = RemarkTreeRenderer()
        tree.emptyText.text = "No remarks yet. Select some lines and press Ctrl+Alt+Shift+R."

        // com.intellij.util, NOT com.intellij.ui: the class file is
        // com/intellij/util/EditSourceOnDoubleClickHandler.class. It already filters out expand
        // and collapse clicks, so a click on a handle does not navigate.
        EditSourceOnDoubleClickHandler.install(tree) { navigateToSelected() }

        // DumbAwareAction lives in openapi.project, NOT openapi.actionSystem.
        DumbAwareAction.create { deleteSelected() }
            .registerCustomShortcutSet(CommonShortcuts.getDelete(), tree, parent)

        // The same actions the gutter icon menu offers, acting on the tree selection instead of on
        // one icon. This is the only reason the tree needs a right-click menu at all: severity and
        // buckets are set after the fact, and the tree is where a whole reading pass is triaged.
        PopupHandler.installPopupMenu(tree, treeMenu(), "ClaudeRemarksTree")

        // One subscription is enough. RemarkGutter's own EditorFactoryListener already calls
        // notifyRemarksChanged when an editor opens or closes, so a second listener here would
        // refresh twice on every open — and, being unfiltered by project, would run a full
        // resolveAll for this project whenever a file opened in ANY project.
        project.messageBus.connect(parent).subscribe(REMARKS_CHANGED, RemarksListener { refresh() })

        setToolbar(buildToolbar().component)
        setContent(JBScrollPane(tree))

        refresh()
    }

    fun refresh() {
        // resolveAll reads Documents, which needs a read lock and can touch disk, so it runs off
        // the EDT. coalesceBy drops an older run so a slow result cannot overwrite a newer one.
        ReadAction.nonBlocking<List<ResolvedRemark>> { resolveAll(project) }
            .expireWith(parent)
            .coalesceBy(this)
            .finishOnUiThread(ModalityState.defaultModalityState()) { rows ->
                // setRoot throws away both what was selected and which groups were shut, and the
                // tree is rebuilt on every remark change and on every editor opening. Without this
                // a row you selected stops being selected the moment you use it, Copy Selected
                // greys itself out, and a file group you closed springs open again as soon as you
                // open any file that holds a remark.
                val wasSelected = selectionKeys()
                val wasCollapsed = collapsedGroups()
                (tree.model as DefaultTreeModel).setRoot(buildTreeRoot(rows))
                expandAll()
                recollapse(wasCollapsed)
                restoreSelection(wasSelected)
            }
            .submit(AppExecutorUtil.getAppExecutorService())
    }

    /** The ids currently selected, in the order the tree shows them. */
    fun selectedIds(): List<String> = selectedNodes().map { it.id }

    private fun treeMenu(): ActionGroup = DefaultActionGroup(
        remarkChangeActions(project) { selectedIds() },
        Separator.getInstance(),
        DumbAwareAction.create("Delete") { deleteSelected() },
    )

    /**
     * A while loop, NOT `for (row in 0 until tree.rowCount)`: that builds the range once, from the
     * row count before anything expanded, so expanding the first file pushed the other file nodes
     * past the end of the range and every file but the first stayed shut.
     */
    private fun expandAll() {
        var row = 0
        while (row < tree.rowCount) {
            tree.expandRow(row)
            row++
        }
    }

    /**
     * What is selected, keyed so that the same selection can be made again after the rebuild.
     *
     * NOT selectedIds(): that turns a selected file node into every remark id under it, and
     * restoring from it selected N rows instead of the one file. The delete guard below reads the
     * difference between "a file node" and "N rows the user picked out", so the first refresh after
     * selecting a file node used to turn a whole file's remarks into rows Delete removes without
     * asking. Capture and restore have to describe the same thing.
     */
    private fun selectionKeys(): Set<String> =
        tree.selectionPaths.orEmpty().mapNotNull { keyOf(it.lastPathComponent) }.toSet()

    /** A remark row is its id, a group row is its key. The root is invisible and never a row. */
    private fun keyOf(component: Any?): String? =
        when (val user = (component as? DefaultMutableTreeNode)?.userObject) {
            is RemarkNode -> user.id
            is GroupNode -> user.key
            else -> null
        }

    /**
     * Called after expandAll and recollapse, so it works on rows that are actually on screen. A row
     * inside a group the user shut is not restored: selecting it would re-expand the group, because
     * JTree expands the ancestors of anything you select.
     */
    private fun restoreSelection(keys: Set<String>) {
        if (keys.isEmpty()) return
        val paths = (0 until tree.rowCount)
            .map { tree.getPathForRow(it) }
            .filter { keyOf(it.lastPathComponent) in keys }
        if (paths.isNotEmpty()) tree.selectionPaths = paths.toTypedArray()
    }

    /**
     * The group rows that are shut right now, read before setRoot throws the rows away.
     *
     * isVisible is not decoration. JTree reports a node inside a collapsed ancestor as not
     * expanded, so without it every file group inside a collapsed bucket would be recorded as
     * collapsed and would stay shut after the rebuild even when the bucket is opened again. The
     * cost of the check: a file group you shut inside a bucket you then shut is forgotten. That is
     * the smaller surprise of the two.
     */
    private fun collapsedGroups(): Set<String> =
        groupNodes()
            .filter { (_, path) -> tree.isVisible(path) && !tree.isExpanded(path) }
            .map { it.first }
            .toSet()

    /** Shuts them again after expandAll, so a group you closed stays closed across a refresh. */
    private fun recollapse(groups: Set<String>) {
        if (groups.isEmpty()) return
        groupNodes().forEach { (key, treePath) -> if (key in groups) tree.collapsePath(treePath) }
    }

    /**
     * Every group row with the TreePath that reaches it, at any depth. Read from the model, not
     * from row indexes, so it does not depend on what is expanded at the time.
     */
    private fun groupNodes(): List<Pair<String, TreePath>> {
        val root = tree.model.root as? DefaultMutableTreeNode ?: return emptyList()
        val found = mutableListOf<Pair<String, TreePath>>()
        fun walk(node: DefaultMutableTreeNode, path: TreePath) {
            (node.userObject as? GroupNode)?.let { found += it.key to path }
            for (index in 0 until node.childCount) {
                val child = node.getChildAt(index) as? DefaultMutableTreeNode ?: continue
                walk(child, path.pathByAddingChild(child))
            }
        }
        walk(root, TreePath(root))
        return found
    }

    /** A selected group node counts as all its rows; see remarkNodesUnder in RemarksTree.kt. */
    private fun selectedNodes(): List<RemarkNode> =
        remarkNodesUnder(
            tree.selectionPaths.orEmpty().mapNotNull { it.lastPathComponent as? DefaultMutableTreeNode }
        )

    private fun navigateToSelected() {
        val node = selectedNodes().firstOrNull() ?: return
        val root = projectRoot(project) ?: return
        // fileForStoredPath, not findRelativeFile: it makes the isAncestor check the resolver and
        // the code slicer make. Without it a stored path of "../../../../etc/passwd" — which
        // resolveAll refuses to read, so its row shows as orphaned — still opened that file in an
        // editor on double click, because the row keeps the raw stored path.
        val file = fileForStoredPath(root, node.path) ?: return
        // The line is 0-based: OpenFileDescriptor builds a LogicalPosition straight from it, and
        // LogicalPosition shares its base with Document.getLineNumber. Checked in the bytecode.
        FileEditorManager.getInstance(project)
            .openTextEditor(OpenFileDescriptor(project, file, node.startLine, 0), true)
    }

    /**
     * Deleting the rows you picked out asks nothing: you selected them and then pressed Delete,
     * which is not silent. Selecting a group node — a file or a bucket — is the other case. It
     * stands for every row under it, and on a collapsed node that is an unknown number of remarks
     * nobody has copied yet. That one asks, the same way Clear All does.
     */
    private fun deleteSelected() {
        val nodes = selectedNodes()
        if (nodes.isEmpty()) return
        val pickedOut = tree.selectionPaths.orEmpty()
            .count { (it.lastPathComponent as? DefaultMutableTreeNode)?.userObject is RemarkNode }
        if (nodes.size > pickedOut && !confirmDelete(nodes.size)) return
        nodes.forEach { deleteRemark(project, it.id) }
    }

    private fun confirmDelete(count: Int): Boolean =
        Messages.showYesNoDialog(
            project,
            "Delete $count remark${if (count == 1) "" else "s"} under the selection? " +
                "This cannot be undone.",
            "Delete Claude Remarks",
            Messages.getWarningIcon(),
        ) == Messages.YES

    /**
     * A toolbar button that greys out when it would do nothing.
     *
     * DumbAwareAction.create returns an action whose update() cannot be overridden, so a button
     * built that way is always live. Task 3 went to real trouble to make the editor action
     * visible-but-disabled with a reason rather than silently dead; the same rule belongs here.
     * Copy Selected with nothing selected and Clear Sent with nothing sent were live buttons that
     * did nothing at all when pressed.
     *
     * ActionUpdateThread.EDT, because [enabled] reads the tree selection and the store.
     */
    private inner class ToolbarAction(
        text: String,
        icon: Icon,
        private val enabled: () -> Boolean,
        private val onPress: () -> Unit,
    ) : DumbAwareAction(text, text, icon) {
        override fun getActionUpdateThread() = ActionUpdateThread.EDT
        override fun update(e: AnActionEvent) {
            e.presentation.isEnabled = enabled()
        }
        override fun actionPerformed(e: AnActionEvent) = onPress()
    }

    private fun remarks() = RemarkStore.getInstance(project).all()

    private fun sentCount() = remarks().count { it.status == RemarkStatus.SENT }

    /**
     * Built in code, not registered as a <group> in plugin.xml: these actions are private to this
     * tool window, so a global registration would only add a name nobody can use elsewhere.
     *
     * targetComponent is effectively required. Without it the platform logs "toolbar by default
     * uses any focused component to update its actions... Please call toolbar.setTargetComponent()
     * explicitly", and the actions read whichever component happens to have focus.
     */
    private fun buildToolbar(): ActionToolbar {
        val group = DefaultActionGroup(
            ToolbarAction(
                "Copy All Pending",
                AllIcons.Actions.Copy,
                { remarks().any { it.status == RemarkStatus.PENDING } },
            ) { copyRemarks(project, null) },
            ToolbarAction(
                "Copy Selected",
                AllIcons.Actions.InSelection,
                { selectedIds().isNotEmpty() },
            ) { copyRemarks(project, selectedIds()) },
            ToolbarAction("Clear Sent", AllIcons.Actions.GC, { sentCount() > 0 }) {
                confirmClearSent()
            },
            ToolbarAction("Clear All", AllIcons.Actions.Cancel, { remarks().isNotEmpty() }) {
                confirmClearAll()
            },
            // notifyRemarksChanged, not refresh(): this panel's own subscription rebuilds the tree
            // either way, and publishing resyncs the gutter icons too. A file reload (a branch
            // switch, a VCS revert, an external edit) already publishes this on its own now — both
            // the gutter and this tree re-resolve without any button — so Refresh is left as the
            // manual catch-all for anything else that could leave either view stale.
            ToolbarAction("Refresh", AllIcons.Actions.Refresh, { true }) {
                notifyRemarksChanged(project)
            },
        )
        return ActionManager.getInstance()
            .createActionToolbar("ClaudeRemarks", group, true)
            .also { it.targetComponent = tree }
    }

    /**
     * Asks first, and says how many went. "Already copied" is not a reason to skip the question:
     * copied means one clipboard buffer that the next copy overwrites, and sent remarks are kept
     * precisely so that a paste which went to the wrong place can be copied again. The button also
     * sits next to Clear All, so a misclick between two destructive buttons is easy.
     */
    private fun confirmClearSent() {
        val sent = sentCount()
        if (sent == 0) return
        val answer = Messages.showYesNoDialog(
            project,
            "Remove $sent sent remark${if (sent == 1) "" else "s"}? They cannot be copied again.",
            "Clear Sent Claude Remarks",
            Messages.getWarningIcon(),
        )
        if (answer != Messages.YES) return
        val removed = clearSentRemarks(project)
        notifyRemarks(project, "Removed $removed sent remark${if (removed == 1) "" else "s"}.")
    }

    /** The other destructive one, and the only one that also throws away work not handed over. */
    private fun confirmClearAll() {
        val total = remarks().size
        if (total == 0) return
        val answer = Messages.showYesNoDialog(
            project,
            "Delete all $total remarks, including the ones not yet copied? This cannot be undone.",
            "Clear All Claude Remarks",
            Messages.getWarningIcon(),
        )
        if (answer == Messages.YES) clearAllRemarks(project)
    }
}
