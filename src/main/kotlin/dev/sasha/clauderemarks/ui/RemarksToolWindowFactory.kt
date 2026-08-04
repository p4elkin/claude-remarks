package dev.sasha.clauderemarks.ui

import com.intellij.icons.AllIcons
import com.intellij.ide.dnd.aware.DnDAwareTree
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
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.EditorNotificationPanel
import com.intellij.ui.PopupHandler
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.content.ContentFactory
import com.intellij.util.EditSourceOnDoubleClickHandler
import com.intellij.util.concurrency.AppExecutorUtil
import dev.sasha.clauderemarks.action.notifyRemarks
import dev.sasha.clauderemarks.action.openGeneralRemarkInput
import dev.sasha.clauderemarks.action.plural
import dev.sasha.clauderemarks.action.publishRemarks
import dev.sasha.clauderemarks.model.RemarkState
import dev.sasha.clauderemarks.model.RemarkStatus
import dev.sasha.clauderemarks.review.ReviewPhase
import dev.sasha.clauderemarks.review.WaitingReviewService
import dev.sasha.clauderemarks.review.canSend
import dev.sasha.clauderemarks.review.rejectWaitingReview
import dev.sasha.clauderemarks.review.sendToWaitingReview
import dev.sasha.clauderemarks.store.REMARKS_CHANGED
import dev.sasha.clauderemarks.store.RemarkStore
import dev.sasha.clauderemarks.store.RemarksListener
import dev.sasha.clauderemarks.store.ResolvedRemark
import dev.sasha.clauderemarks.store.clearAllRemarks
import dev.sasha.clauderemarks.store.clearHandedOverRemarks
import dev.sasha.clauderemarks.store.deleteRemark
import dev.sasha.clauderemarks.store.fileForStoredPath
import dev.sasha.clauderemarks.store.notifyRemarksChanged
import dev.sasha.clauderemarks.store.projectRoot
import dev.sasha.clauderemarks.store.resolveAll
import java.awt.BorderLayout
import java.awt.Component
import javax.swing.Icon
import javax.swing.JPanel
import javax.swing.JTree
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreePath

/**
 * Adds the tree row at [x], [y] to the selection, unless it is already there.
 *
 * addSelectionPath, not setSelectionPath: right-clicking inside a selection of several rows must not
 * collapse it to the one row under the pointer, because moving a whole reading pass into a bucket is
 * exactly what that selection is for.
 *
 * A top-level function, so RemarksPanelTest can drive the rule without a window: showing a real
 * popup menu needs one, and this is the part that was wrong.
 */
internal fun selectRowForPopup(tree: JTree, x: Int, y: Int) {
    val path = tree.getPathForLocation(x, y) ?: return
    if (!tree.isPathSelected(path)) tree.addSelectionPath(path)
}

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

    /**
     * Internal, not private, so RemarksPanelTest can look at what the refresh left on screen.
     *
     * DnDAwareTree, not Tree: it extends `com.intellij.ui.treeStructure.Tree` and adds the mouse
     * handling a drag needs — a press inside a multi-row selection must not collapse the selection
     * until the button comes back up, or dragging a whole reading pass into a bucket would drag one
     * row. Everything else about the tree is unchanged, and the constructor taking a TreeModel is
     * the same one this line always used.
     */
    internal val tree = DnDAwareTree(DefaultTreeModel(DefaultMutableTreeNode("remarks")))

    /**
     * Internal, not private, so RemarksPanelTest can look at what refresh() left on screen.
     * Hidden by default; refresh() below is what turns it on, once it has actually asked the
     * service whether a review is waiting.
     */
    internal val banner = EditorNotificationPanel().apply {
        text = "Claude Code is waiting"
        createActionLabel("Send remarks") { sendToWaitingReview(project) }
        // Reject, not Cancel: this writes the decision to the handoff file so the waiting session
        // stops at once. "Cancel" read as "close this banner", which is exactly the behaviour that
        // was wrong.
        createActionLabel("Reject") { rejectWaitingReview(project) }
        isVisible = false
    }

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
        tree.addMouseListener(TreePopupHandler())

        // The wiring lives in RemarksTreeDnd.kt, beside the pure bucketDropTarget it drives. The
        // selection rule comes from here so that a drag and Publish Selected always cover the same
        // rows.
        installDragToBucket(tree, project, parent) { selectedIds() }

        // One subscription is enough. RemarkGutter's own EditorFactoryListener already calls
        // notifyRemarksChanged when an editor opens or closes, so a second listener here would
        // refresh twice on every open — and, being unfiltered by project, would run a full
        // resolveAll for this project whenever a file opened in ANY project.
        project.messageBus.connect(parent).subscribe(
            REMARKS_CHANGED,
            RemarksListener {
                remarksCache = null
                refresh()
            },
        )

        setToolbar(buildToolbar().component)
        // A plain BorderLayout panel, not setContent(tree) directly: SimpleToolWindowPanel's own
        // layout only has room for one centre component, and this is the wrapper task 7 adds so
        // the banner has a place to sit above the tree without disturbing the toolbar or the tree.
        setContent(
            JPanel(BorderLayout()).apply {
                add(banner, BorderLayout.NORTH)
                add(JBScrollPane(tree), BorderLayout.CENTER)
            }
        )

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
                // a row you selected stops being selected the moment you use it, Publish Selected
                // greys itself out, and a file group you closed springs open again as soon as you
                // open any file that holds a remark.
                val wasSelected = selectionKeys()
                val wasCollapsed = collapsedGroups()
                (tree.model as DefaultTreeModel).setRoot(buildTreeRoot(rows))
                expandAll()
                recollapse(wasCollapsed)
                restoreSelection(wasSelected)
                updateBanner()
            }
            .submit(AppExecutorUtil.getAppExecutorService())
    }

    /**
     * The label is caller-supplied text that arrived over HTTP, not something this plugin wrote.
     * EditorNotificationPanel.setText feeds a JLabel, and Swing renders a string starting with
     * "<html>" as markup, so an unescaped label could inject arbitrary Swing markup into the tool
     * window. escapeXmlEntities turns a leading "<html>" into inert text. Cut to 120 characters
     * first, so a very long label costs one truncated escape rather than an escape of the whole
     * thing. The Sent text below carries no caller-supplied content, only a count, so escaping it
     * would be cargo cult.
     */
    private fun updateBanner() {
        val waiting = WaitingReviewService.getInstance(project).current()
        if (waiting == null) {
            banner.isVisible = false
            return
        }
        banner.text = when (val phase = waiting.phase) {
            ReviewPhase.Waiting ->
                "Claude Code is waiting: " + StringUtil.escapeXmlEntities(waiting.label.take(120))
            is ReviewPhase.Sent ->
                "Sent ${phase.ids.size} remark${plural(phase.ids.size)}. " +
                    "Waiting for Claude Code to read them."
        }
        banner.isVisible = true
    }

    /** The ids currently selected, in the order the tree shows them. */
    fun selectedIds(): List<String> = selectedNodes().map { it.id }

    /** Internal, not private, so RemarksPanelTest can check what the right-click menu offers. */
    internal fun treeMenu(): ActionGroup = DefaultActionGroup(
        remarkChangeActions(project) { selectedIds() },
        Separator.getInstance(),
        DumbAwareAction.create("Delete") { deleteSelected() },
    )

    /**
     * A right-click first selects the row under the pointer, then opens the menu.
     *
     * PopupHandler.installPopupMenu only shows the menu, and BasicTreeUI moves the tree selection on
     * button 1 only. So right-clicking a row that was not selected opened the menu against the
     * PREVIOUS selection, and with nothing selected all three items were silent no-ops:
     * setRemarkSeverity on an empty list changes nothing and publishes nothing, and chooseBucket and
     * deleteSelected both return on their own empty checks.
     *
     * The menu is built here rather than through installPopupMenu because that helper offers no way
     * in before the menu is shown. It is the same three calls the helper makes.
     */
    private inner class TreePopupHandler : PopupHandler() {
        override fun invokePopup(comp: Component, x: Int, y: Int) {
            selectRowForPopup(tree, x, y)
            ActionManager.getInstance()
                .createActionPopupMenu("ClaudeRemarksTree", treeMenu())
                .also { it.setTargetComponent(tree) }
                .component
                .show(comp, x, y)
        }
    }

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
     * isVisible is there because JTree reports a node inside a collapsed ancestor as not expanded,
     * so without it every file group inside a collapsed bucket would be recorded as collapsed and
     * put back shut. How much that matters depends on an advanced setting:
     * `com.intellij.ui.treeStructure.Tree.collapsePath` collapses the whole visible subtree when
     * `ide.tree.collapse.recursively` is on, which is the default — and then shutting a bucket shuts
     * its file groups whatever this method records. With that setting off, this check is what keeps a
     * file group open after its bucket is closed and opened again.
     *
     * The cost either way: a file group you shut inside a bucket you then shut is forgotten. That is
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
     * nobody has published yet. That one asks, the same way Clear All does.
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
            "Delete $count remark${plural(count)} under the selection? " +
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
     * Publish Selected with nothing selected and Clear Handed Over with nothing handed over were
     * live buttons that did nothing at all when pressed.
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

    /**
     * One snapshot per change, not three per toolbar tick.
     *
     * `all()` is a deep copy — a fresh RemarkState per remark, taken under the store's lock — and
     * ToolbarAction.update runs on the EDT for every button on every tick, so Publish All Pending,
     * Clear Handed Over and Clear All each took their own copy of the whole store several times a
     * second.
     *
     * Cleared by the REMARKS_CHANGED subscription above, which is the only thing that can change the
     * store as far as this panel is concerned: all ten mutation functions publish it, and rule 3 in
     * CLAUDE.md is what keeps anything else from mutating without publishing. EDT only.
     */
    private var remarksCache: List<RemarkState>? = null

    /** Internal, not private, so RemarksPanelTest can prove the cache is dropped on a change. */
    internal fun remarks(): List<RemarkState> =
        remarksCache ?: RemarkStore.getInstance(project).all().also { remarksCache = it }

    private fun handedOverCount() = remarks().count { it.status != RemarkStatus.PENDING }

    /**
     * Internal, not private, so RemarksPanelTest can check what each toolbar button offers and
     * whether it is enabled, the same way treeMenu() lets it check the right-click menu.
     *
     * Built in code, not registered as a <group> in plugin.xml: these actions are private to this
     * tool window, so a global registration would only add a name nobody can use elsewhere.
     *
     * "Add General Remark" is deliberately the only entry point for a remark about no file: no
     * plugin.xml action, no Tools menu entry, no keystroke. The tool window is the one place a
     * person is looking at remarks rather than at code, which is where a thought about the whole
     * change gets written. It stays enabled with nothing selected and no editor open — that is the
     * whole point of a remark about no file.
     */
    internal fun toolbarActions(): ActionGroup = DefaultActionGroup(
        ToolbarAction("Add General Remark", AllIcons.General.Add, { true }) {
            openGeneralRemarkInput(project, tree)
        },
        ToolbarAction(
            "Publish All Pending",
            AllIcons.Actions.Copy,
            { remarks().any { it.status == RemarkStatus.PENDING } },
        ) { publishRemarks(project, null) },
        ToolbarAction(
            "Publish Selected",
            AllIcons.Actions.InSelection,
            { selectedIds().isNotEmpty() },
        ) { publishRemarks(project, selectedIds()) },
        ToolbarAction("Clear Handed Over", AllIcons.Actions.GC, { handedOverCount() > 0 }) {
            confirmClearHandedOver()
        },
        ToolbarAction("Clear All", AllIcons.Actions.Cancel, { remarks().isNotEmpty() }) {
            confirmClearAll()
        },
        // The condition itself lives in review/SendReview.kt as canSend, so this button and the
        // Tools-menu action read one function rather than two copies of the same pair of checks.
        ToolbarAction("Send to Claude Code", AllIcons.Actions.Upload, { canSend(project) }) {
            sendToWaitingReview(project)
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

    /**
     * targetComponent is effectively required. Without it the platform logs "toolbar by default
     * uses any focused component to update its actions... Please call toolbar.setTargetComponent()
     * explicitly", and the actions read whichever component happens to have focus.
     */
    private fun buildToolbar(): ActionToolbar =
        ActionManager.getInstance()
            .createActionToolbar("ClaudeRemarks", toolbarActions(), true)
            .also { it.targetComponent = tree }

    /**
     * Asks first, and says how many went. "Already handed over" is not a reason to skip the
     * question: published means one clipboard buffer or one file that the next publish overwrites,
     * and handed-over remarks are kept precisely so that a paste or a fetch that went to the wrong
     * place can be handed over again. The button also sits next to Clear All, so a misclick between
     * two destructive buttons is easy.
     */
    private fun confirmClearHandedOver() {
        val handedOver = handedOverCount()
        if (handedOver == 0) return
        val answer = Messages.showYesNoDialog(
            project,
            "Remove $handedOver handed-over remark${plural(handedOver)}? They cannot be published again.",
            "Clear Handed Over Claude Remarks",
            Messages.getWarningIcon(),
        )
        if (answer != Messages.YES) return
        val removed = clearHandedOverRemarks(project)
        // 0 here can only mean the archive write failed: the handed-over count was checked non-zero
        // just above, and clearHandedOverRemarks returns 0 in that case having already shown its own
        // error balloon. Saying "Removed 0 handed-over remarks." beside that error was the wrong
        // half of the truth.
        if (removed > 0) {
            notifyRemarks(project, "Removed $removed handed-over remark${plural(removed)}.")
        }
    }

    /** The other destructive one, and the only one that also throws away work not handed over. */
    private fun confirmClearAll() {
        val total = remarks().size
        if (total == 0) return
        val answer = Messages.showYesNoDialog(
            project,
            "Delete all $total remarks, including the ones not yet published? This cannot be undone.",
            "Clear All Claude Remarks",
            Messages.getWarningIcon(),
        )
        if (answer == Messages.YES) clearAllRemarks(project)
    }
}
