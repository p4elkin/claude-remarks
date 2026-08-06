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
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.PopupHandler
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.content.ContentFactory
import com.intellij.util.EditSourceOnDoubleClickHandler
import com.intellij.util.concurrency.AppExecutorUtil
import dev.sasha.clauderemarks.action.notifyRemarks
import dev.sasha.clauderemarks.action.openGeneralRemarkInput
import dev.sasha.clauderemarks.action.plural
import dev.sasha.clauderemarks.action.publishRemarks
import dev.sasha.clauderemarks.model.AnswerState
import dev.sasha.clauderemarks.model.RemarkState
import dev.sasha.clauderemarks.model.RemarkStatus
import dev.sasha.clauderemarks.store.REMARKS_CHANGED
import dev.sasha.clauderemarks.store.RemarkStore
import dev.sasha.clauderemarks.store.RemarksListener
import dev.sasha.clauderemarks.store.ResolvedAnswer
import dev.sasha.clauderemarks.store.ResolvedRemark
import dev.sasha.clauderemarks.store.clearAllRemarks
import dev.sasha.clauderemarks.store.clearHandedOverRemarks
import dev.sasha.clauderemarks.store.deleteAnswer
import dev.sasha.clauderemarks.store.deleteRemark
import dev.sasha.clauderemarks.store.fileForStoredPath
import dev.sasha.clauderemarks.store.notifyRemarksChanged
import dev.sasha.clauderemarks.store.projectRoot
import dev.sasha.clauderemarks.store.resolveAll
import dev.sasha.clauderemarks.store.resolveAnswers
import java.awt.Component
import javax.swing.Icon
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

/**
 * Everything one refresh resolved, carried back from the read action as one value.
 *
 * A pair of lists rather than two read actions, because the two views have to agree: a remark row
 * says "answered" exactly when the Answers group holds an answer naming it, and two separate
 * resolves could land either side of a store change and disagree for one frame.
 */
private data class ResolvedRows(
    val remarks: List<ResolvedRemark>,
    val answers: List<ResolvedAnswer>,
)

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
        // one icon. This is the only reason the tree needs a right-click menu at all: buckets are
        // set after the fact, and the tree is where a whole reading pass is triaged.
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
                answersCache = null
                refresh()
            },
        )

        setToolbar(buildToolbar().component)
        setContent(JBScrollPane(tree))

        refresh()
    }

    fun refresh() {
        // resolveAll reads Documents, which needs a read lock and can touch disk, so it runs off
        // the EDT. coalesceBy drops an older run so a slow result cannot overwrite a newer one.
        ReadAction.nonBlocking<ResolvedRows> {
            ResolvedRows(resolveAll(project), resolveAnswers(project))
        }
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
                (tree.model as DefaultTreeModel).setRoot(buildTreeRoot(rows.remarks, rows.answers))
                expandAll()
                recollapse(wasCollapsed)
                restoreSelection(wasSelected)
            }
            .submit(AppExecutorUtil.getAppExecutorService())
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
     * PREVIOUS selection, and with nothing selected the menu items would be silent no-ops:
     * setRemarkBucket and deleteSelected both return on their own empty checks.
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

    /**
     * A remark row is its id, an answer row is its own id, a group row is its key. The root is
     * invisible and never a row.
     *
     * A remark id and an answer id are both random uuids, so the two cannot collide, and a selection
     * of either kind survives a rebuild the same way.
     */
    private fun keyOf(component: Any?): String? =
        when (val user = (component as? DefaultMutableTreeNode)?.userObject) {
            is RemarkNode -> user.id
            is AnswerNode -> user.id
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
    private fun selectedNodes(): List<RemarkNode> = remarkNodesUnder(selectedTreeNodes())

    /** The same for answer rows; selecting the Answers group counts as selecting every answer. */
    private fun selectedAnswerNodes(): List<AnswerNode> = answerNodesUnder(selectedTreeNodes())

    private fun selectedTreeNodes(): List<DefaultMutableTreeNode> =
        tree.selectionPaths.orEmpty().mapNotNull { it.lastPathComponent as? DefaultMutableTreeNode }

    /**
     * The answer row the selection is *on*, or null when it is on anything else.
     *
     * Not selectedAnswerNodes().firstOrNull(): that also answers for a selected Answers group, and
     * a double click on a group row is an expand, not a request to open one of its children.
     *
     * The one selected node, not the first answer among several. A double click leaves exactly one
     * row selected, so a selection holding more than one node was built some other way — and picking
     * an answer out of it opened the popup instead of navigating to the row that was actually
     * double-clicked.
     *
     * Internal, not private, so RemarksPanelTest can drive the double-click decision without a real
     * popup appearing: showing one needs a window, and the decision is the part that can be wrong.
     */
    internal fun selectedAnswerRow(): AnswerNode? =
        selectedTreeNodes().singleOrNull()?.userObject as? AnswerNode

    /**
     * Double click. A remark row opens its file at the line the row resolved to. An answer row does
     * the same and *then* shows the answer, because an answer is about a piece of code and reading it
     * with no idea what it is about is half an answer. Until this changed, an answer row only opened
     * the popup and never moved the editor, which is the first thing that felt wrong when the feature
     * was used in a real IDE.
     *
     * Navigate first, popup second, and the order is load-bearing. A component popup built by
     * `showAnswerPopup` cancels itself when its window is deactivated, so opening an editor while the
     * popup is up would shut the popup again. (It is also async — the markdown conversion runs off the
     * EDT — so the popup appears after the editor either way. The order here is what makes that true
     * on purpose rather than by luck.)
     *
     * **An answer with no file shows the popup and navigates nowhere.** An answer to a general
     * remark, and an answer whose remark was deleted before it arrived, both carry an empty path.
     * There is nothing to open, so [navigateTo] returns and the popup still comes up.
     *
     * **An orphaned answer navigates to the stale line anyway.** The row is already labelled
     * "(orphaned…)", so nobody is being told the position is current, and the file is still the right
     * file — the stale line is the best starting point anyone has for finding where the code went. It
     * is also exactly what a remark row has always done for its own orphans, and two rows in one tree
     * behaving differently on the same gesture would need a better reason than this one has.
     */
    internal fun navigateToSelected() {
        selectedAnswerRow()?.let { answer ->
            navigateTo(answer.path, answer.startLine)
            showAnswerPopup(project, answer.question, answer.markdown)
            return
        }
        val node = selectedNodes().firstOrNull() ?: return
        navigateTo(node.path, node.startLine)
    }

    /**
     * Opens [path] at [line], or does nothing at all when there is nothing to open.
     *
     * The empty-path check is first, and it is what a row with no file needs: a general remark and a
     * general remark's answer both store an empty path, and asking the VFS for the file at "" is a
     * question with no sensible answer.
     *
     * fileForStoredPath, not findRelativeFile: it makes the isAncestor check the resolver and the
     * code slicer make. Without it a stored path of "../../../../etc/passwd" — which resolveAll
     * refuses to read, so its row shows as orphaned — still opened that file in an editor on double
     * click, because the row keeps the raw stored path.
     *
     * [line] is 0-based: OpenFileDescriptor builds a LogicalPosition straight from it, and
     * LogicalPosition shares its base with Document.getLineNumber. Checked in the bytecode, and
     * pinned by NavigationLineBaseTest.
     */
    private fun navigateTo(path: String, line: Int) {
        if (path.isEmpty()) return
        val root = projectRoot(project) ?: return
        val file = fileForStoredPath(root, path) ?: return
        FileEditorManager.getInstance(project)
            .openTextEditor(OpenFileDescriptor(project, file, line, 0), true)
    }

    /**
     * Deleting the rows you picked out asks nothing: you selected them and then pressed Delete,
     * which is not silent. Selecting a group node — a file, a bucket, or the Answers group — is the
     * other case. It stands for every row under it, and on a collapsed node that is an unknown
     * number of rows nobody has published yet. That one asks, the same way Clear All does.
     *
     * Answer rows go through deleteAnswer and remark rows through deleteRemark, and a selection can
     * hold both. Without the answer half, Delete on an answer row would do nothing at all, with no
     * message — remarkNodesUnder returns remark rows only.
     *
     * Internal, not private, so RemarksPanelTest can press Delete without a keystroke.
     */
    internal fun deleteSelected() {
        val nodes = selectedNodes()
        val answers = selectedAnswerNodes()
        val total = nodes.size + answers.size
        if (total == 0) return
        val pickedOut = tree.selectionPaths.orEmpty().count {
            val user = (it.lastPathComponent as? DefaultMutableTreeNode)?.userObject
            user is RemarkNode || user is AnswerNode
        }
        if (total > pickedOut && !confirmDelete(total)) return
        nodes.forEach { deleteRemark(project, it.id) }
        answers.forEach { deleteAnswer(project, it.id) }
    }

    /**
     * "row", not "remark": the selection can cover answer rows as well now, and a dialog that
     * counted both while naming only one would be telling the person something untrue about what
     * is about to go.
     */
    private fun confirmDelete(count: Int): Boolean =
        Messages.showYesNoDialog(
            project,
            "Delete $count row${plural(count)} under the selection? " +
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
        description: String,
        icon: Icon,
        private val enabled: () -> Boolean,
        private val onPress: () -> Unit,
    ) : DumbAwareAction(text, description, icon) {
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
     * ToolbarAction.update runs on the EDT for every button on every tick, so Publish Unread,
     * Clear Handed Over and Clear All each took their own copy of the whole store several times a
     * second.
     *
     * Cleared by the REMARKS_CHANGED subscription above, which is the only thing that can change the
     * store as far as this panel is concerned: all twelve mutation functions publish it, and rule 3 in
     * CLAUDE.md is what keeps anything else from mutating without publishing. EDT only.
     */
    private var remarksCache: List<RemarkState>? = null

    /**
     * The answers half of [remarksCache], cached for the same reason and dropped in the same place:
     * a toolbar predicate runs on every update tick, and allAnswers() is a deep copy of every answer.
     */
    private var answersCache: List<AnswerState>? = null

    /** Internal, not private, so RemarksPanelTest can prove the cache is dropped on a change. */
    internal fun remarks(): List<RemarkState> =
        remarksCache ?: RemarkStore.getInstance(project).all().also { remarksCache = it }

    /** The answers half of [remarks], read by Clear All so it can say how many it is about to take. */
    internal fun answers(): List<AnswerState> =
        answersCache ?: RemarkStore.getInstance(project).allAnswers().also { answersCache = it }

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
        ToolbarAction(
            "Add General Remark",
            "A remark about the whole change, with no file and no lines",
            AllIcons.General.Add,
            { true },
        ) {
            openGeneralRemarkInput(project, tree)
        },
        // Upload, not Copy: the button stopped being a copy in phase 9, when publishing gained the
        // published file, and stopped being only that in phase 10, when it also became how a waiting
        // review is answered. Copy was left over from "Copy All Pending" and understated all three.
        // Upload is the icon the deleted Send to Claude Code button carried, so the picture follows
        // the meaning rather than the control that went away.
        ToolbarAction(
            "Publish Unread",
            "Every remark not yet read",
            AllIcons.Actions.Upload,
            { remarks().any { it.status != RemarkStatus.READ } },
        ) { publishRemarks(project, null) },
        ToolbarAction(
            "Publish Selected",
            "Only the rows you picked. Select a bucket node to take that whole bucket",
            AllIcons.Actions.InSelection,
            { selectedIds().isNotEmpty() },
        ) { publishRemarks(project, selectedIds()) },
        ToolbarAction(
            "Clear Handed Over",
            "Every remark already published or read. Answers are kept. " +
                "Archived to the history file first",
            AllIcons.Actions.GC,
            { handedOverCount() > 0 },
        ) {
            confirmClearHandedOver()
        },
        // Enabled on either list, not on remarks alone: Clear All is the only thing that prunes
        // answers, so a project whose remarks have all been cleared must still be able to take them.
        ToolbarAction(
            "Clear All",
            "Every remark and every answer, published or not. Archived to the history file first",
            AllIcons.Actions.Cancel,
            { remarks().isNotEmpty() || answers().isNotEmpty() },
        ) { confirmClearAll() },
        // notifyRemarksChanged, not refresh(): this panel's own subscription rebuilds the tree
        // either way, and publishing resyncs the gutter icons too. A file reload (a branch
        // switch, a VCS revert, an external edit) already publishes this on its own now — both
        // the gutter and this tree re-resolve without any button — so Refresh is left as the
        // manual catch-all for anything else that could leave either view stale.
        ToolbarAction(
            "Refresh",
            "Re-resolves every remark against the files as they are now",
            AllIcons.Actions.Refresh,
            { true },
        ) {
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
        val remarkCount = remarks().size
        val answerCount = answers().size
        if (remarkCount == 0 && answerCount == 0) return
        // Both counts, because Clear All takes both. A message naming only remarks while the button
        // also throws away every answer is exactly the quiet loss the history file exists to prevent.
        val chose = Messages.showYesNoDialog(
            project,
            "Delete all $remarkCount remark${plural(remarkCount)} and " +
                "$answerCount answer${plural(answerCount)}, including the remarks " +
                "not yet published? This cannot be undone.",
            "Clear All Claude Remarks",
            Messages.getWarningIcon(),
        )
        if (chose == Messages.YES) clearAllRemarks(project)
    }
}
