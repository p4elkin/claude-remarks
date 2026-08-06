package dev.sasha.clauderemarks.ui

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileDocumentManagerListener
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.ui.TestDialog
import com.intellij.openapi.ui.TestDialogManager
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.testFramework.TestActionEvent
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.ui.PopupHandler
import dev.sasha.clauderemarks.anchor.hashLines
import dev.sasha.clauderemarks.editor.RemarkGutter
import dev.sasha.clauderemarks.store.RemarkStore
import dev.sasha.clauderemarks.store.addRemark
import dev.sasha.clauderemarks.store.answer
import dev.sasha.clauderemarks.store.fileUnderProjectRoot
import dev.sasha.clauderemarks.store.markRemarksPublished
import dev.sasha.clauderemarks.store.markRemarksRead
import dev.sasha.clauderemarks.store.recordAnswer
import dev.sasha.clauderemarks.store.settleInvocationQueue
import java.io.File
import javax.swing.tree.DefaultMutableTreeNode

/**
 * The panel, not just the nodes it builds. RemarksTreeTest only ever looks at the node model, so
 * both defects checked here were invisible to it: the tree showed one open file group out of three,
 * and every rebuild threw the selection away under the user.
 */
class RemarksPanelTest : BasePlatformTestCase() {

    override fun setUp() {
        super.setUp()
        // The light fixture project is shared across test classes.
        RemarkStore.getInstance(project).clear()
    }

    override fun tearDown() {
        RemarkStore.getInstance(project).clear()
        super.tearDown()
    }

    fun testEveryFileGroupIsExpanded() {
        listOf("A.kt", "B.kt", "C.kt").forEach { path ->
            addRemark(project, path, LINES, 0..0, "first note in $path")
            addRemark(project, path, LINES, 1..1, "second note in $path")
        }

        val panel = panel()

        // The Open group, three file nodes, their six rows. The earlier loop read rowCount once,
        // before any expansion, so only the first file opened and this came back as 5.
        assertEquals(10, panel.tree.rowCount)
    }

    fun testTheSelectionSurvivesARefresh() {
        val remark = addRemark(project, "A.kt", LINES, 0..0, "a note")
        val panel = panel()
        // Row 0 is the Open group, row 1 the file group, row 2 the remark.
        panel.tree.setSelectionRow(2)
        assertEquals(listOf(remark.id), panel.selectedIds())

        // What happens on every remark change and on every editor opening.
        panel.refresh()
        settleInvocationQueue()

        assertEquals(listOf(remark.id), panel.selectedIds())
    }

    /**
     * expandAll runs on every refresh, and a refresh happens whenever any editor of a remarked file
     * opens or closes. Without the collapsed groups being put back, a group you shut re-opened as
     * soon as you navigated anywhere.
     */
    fun testACollapsedFileGroupStaysCollapsedAcrossARefresh() {
        listOf("A.kt", "B.kt").forEach { path ->
            addRemark(project, path, LINES, 0..0, "first note in $path")
            addRemark(project, path, LINES, 1..1, "second note in $path")
        }
        val panel = panel()
        // The Open group, two file groups, four rows.
        assertEquals(7, panel.tree.rowCount)

        panel.tree.collapseRow(1)
        assertEquals(5, panel.tree.rowCount)

        refreshAndSettle(panel)

        assertEquals(5, panel.tree.rowCount)
    }

    /**
     * Done starts shut, and Open does not. Done holds what has already been dealt with, so a tree
     * that opens it every time buries what still needs reading under what does not.
     */
    fun testDoneStartsCollapsedWhileOpenIsExpanded() {
        addRemark(project, "A.kt", LINES, 0..0, "still to read")
        val read = addRemark(project, "B.kt", LINES, 1..1, "already read")
        markRemarksPublished(project, listOf(read.id!!))
        markRemarksRead(project, listOf(read.id!!))

        val panel = panel()

        // Open, its file group and its one row — then the Done row with nothing under it.
        assertEquals(4, panel.tree.rowCount)
        val done = panel.tree.getPathForRow(3)
        assertEquals(DONE_KEY, keyOfRow(done.lastPathComponent))
        assertFalse(panel.tree.isExpanded(done))
    }

    /**
     * And the default holds on every rebuild, not only the first. A refresh runs whenever any editor
     * of a remarked file opens or closes, so "Done starts collapsed" has to survive one — otherwise
     * the group springs open the moment a person navigates anywhere.
     */
    fun testDoneIsStillCollapsedAndOpenStillExpandedAfterARefresh() {
        addRemark(project, "A.kt", LINES, 0..0, "still to read")
        val read = addRemark(project, "B.kt", LINES, 1..1, "already read")
        markRemarksPublished(project, listOf(read.id!!))
        markRemarksRead(project, listOf(read.id!!))
        val panel = panel()
        assertEquals(4, panel.tree.rowCount)

        refreshAndSettle(panel)

        assertEquals(4, panel.tree.rowCount)
        assertTrue(panel.tree.isExpanded(panel.tree.getPathForRow(0)))
        assertFalse(panel.tree.isExpanded(panel.tree.getPathForRow(3)))
    }

    /**
     * The other half of leaving Done shut: a person who opens it must not have it slam closed on the
     * next refresh, and a refresh happens whenever any editor of a remarked file opens or closes.
     *
     * `expandAll` skips the Done row while it is shut, so this is not free — it works because the
     * walk is told whether Done was open before the rebuild.
     */
    fun testOpeningDoneSurvivesARefresh() {
        val read = addRemark(project, "A.kt", LINES, 0..0, "already read")
        markRemarksPublished(project, listOf(read.id!!))
        markRemarksRead(project, listOf(read.id!!))
        val panel = panel()
        assertEquals(1, panel.tree.rowCount)

        expandEverything(panel)
        assertEquals(3, panel.tree.rowCount)

        refreshAndSettle(panel)

        assertEquals(3, panel.tree.rowCount)
    }

    /** And shutting it again sticks, through the same collapsed-groups restore every group uses. */
    fun testShuttingDoneAgainStaysShutAcrossARefresh() {
        val read = addRemark(project, "A.kt", LINES, 0..0, "already read")
        markRemarksPublished(project, listOf(read.id!!))
        markRemarksRead(project, listOf(read.id!!))
        val panel = panel()
        expandEverything(panel)
        assertEquals(3, panel.tree.rowCount)

        panel.tree.collapseRow(0)
        refreshAndSettle(panel)

        assertEquals(1, panel.tree.rowCount)
    }

    /**
     * The delete confirmation reads a file-node selection as more rows covered than rows picked out.
     * The refresh used to capture the selection as remark ids and restore it as leaf rows, so the
     * first refresh after selecting a file node turned it into N picked-out rows — and Delete then
     * removed a whole file's remarks with no question asked.
     */
    fun testAFileNodeStaysSelectedAsAFileNodeAcrossARefresh() {
        addRemark(project, "A.kt", LINES, 0..0, "one")
        addRemark(project, "A.kt", LINES, 1..1, "two")
        val panel = panel()
        // Row 0 is the Open group, row 1 the file group.
        panel.tree.setSelectionRow(1)
        assertEquals(2, panel.selectedIds().size)

        panel.refresh()
        settleInvocationQueue()

        assertEquals(1, panel.tree.selectionCount)
        assertEquals(2, panel.selectedIds().size)
    }

    /**
     * The gutter's own fileContentReloaded handler re-resolves its icons AND republishes
     * REMARKS_CHANGED, which is the only thing this panel listens to. Before that publish
     * existed, a branch switch, a VCS revert or an external edit left this tree showing rows
     * resolved against the pre-reload text — the gutter recovered on its own and the tree did
     * not — until something else (the Refresh button, an editor open/close) rebuilt it.
     */
    fun testAFileReloadRefreshesTheTreeTooNotJustTheGutter() {
        val onDisk = File(project.basePath!!, "Reloaded.kt")
        onDisk.parentFile.mkdirs()
        onDisk.writeText(LINES.joinToString("\n"))
        val file = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(onDisk)!!
        myFixture.openFileInEditor(file)
        val document = FileDocumentManager.getInstance().getDocument(file)!!
        addRemark(project, "Reloaded.kt", LINES, 0..0, "a note")

        // Built by hand, exactly as RemarkGutterTest does, so this test gets its own tracked set
        // instead of racing the project-level instance that a postStartupActivity would create.
        val gutter = RemarkGutter(project)
        Disposer.register(testRootDisposable, gutter)
        gutter.start()
        settleInvocationQueue() // lets the startup seeding's invokeLater track the already-open editor

        val panel = panel()
        val rootBefore = panel.tree.model.root

        // The same call FileDocumentManager makes after it reloads a document, same technique as
        // RemarkGutterTest.testAFileReloadedFromDiskGetsItsIconsResolvedAgain.
        ApplicationManager.getApplication().messageBus
            .syncPublisher(FileDocumentManagerListener.TOPIC)
            .fileContentReloaded(file, document)
        settleInvocationQueue()

        // buildTreeRoot constructs a fresh node graph on every refresh, so a new root object
        // proves refresh() ran — which only happens if the reload republished REMARKS_CHANGED.
        assertNotSame(
            "the reload should have republished REMARKS_CHANGED and rebuilt the tree",
            rootBefore,
            panel.tree.model.root,
        )
    }

    /**
     * Right-clicking a row that is not selected used to open the menu against the PREVIOUS selection,
     * because PopupHandler only shows the menu and BasicTreeUI moves the selection on button 1 only.
     * With nothing selected every item was a silent no-op.
     */
    fun testARightClickSelectsTheRowUnderThePointer() {
        addRemark(project, "A.kt", LINES, 0..0, "one")
        addRemark(project, "A.kt", LINES, 1..1, "two")
        val panel = panel()
        panel.tree.clearSelection()

        val bounds = panel.tree.getRowBounds(2)
        selectRowForPopup(panel.tree, bounds.x + 1, bounds.y + 1)

        assertEquals(2, panel.tree.selectionRows!!.single())
    }

    /** Adding, not replacing: right-clicking inside a multi-row selection must not collapse it. */
    fun testARightClickInsideAnExistingSelectionKeepsIt() {
        addRemark(project, "A.kt", LINES, 0..0, "one")
        addRemark(project, "A.kt", LINES, 1..1, "two")
        val panel = panel()
        panel.tree.setSelectionRows(intArrayOf(1, 2))

        val bounds = panel.tree.getRowBounds(2)
        selectRowForPopup(panel.tree, bounds.x + 1, bounds.y + 1)

        assertEquals(listOf(1, 2), panel.tree.selectionRows!!.sorted())
    }

    /**
     * Nothing proved either side installed the shared menu. Removing the one line that installs it
     * left the tree with no right-click menu at all and the whole suite green.
     */
    fun testTheTreeHasARightClickMenu() {
        val panel = panel()

        assertTrue(panel.tree.mouseListeners.any { it is PopupHandler })
    }

    /**
     * The toolbar reads one cached snapshot instead of deep-copying the store three times per update
     * tick, so the cache has to be dropped whenever the store changes. If it is not, every toolbar
     * button keeps the enabled state it had when the panel was built: Copy All Pending stays greyed
     * out after the first remark is added, until something else rebuilds the panel.
     */
    fun testTheToolbarsCachedSnapshotIsDroppedWhenARemarkIsAdded() {
        val panel = panel()
        assertEquals(0, panel.remarks().size)

        addRemark(project, "A.kt", LINES, 0..0, "one")
        settleInvocationQueue()

        assertEquals(1, panel.remarks().size)
    }

    /**
     * A general remark is written from the tool window with no selection and, unlike every other
     * action in this panel, no editor open at all — that is the whole point of a remark about no
     * file. The button must offer itself and stay enabled with the panel completely empty, not
     * gated on a selection or `remarks()` the way Publish Selected and Clear All are.
     */
    fun testTheAddGeneralRemarkButtonIsOfferedAndEnabledWithNoSelectionAndNoEditorOpen() {
        val panel = panel()

        val action = panel.toolbarActions().getChildren(null)
            .single { it.templatePresentation.text == "Add General Remark" }
        val event = TestActionEvent.createTestEvent(action)
        action.update(event)

        assertTrue(event.presentation.isEnabled)
    }

    /**
     * Every toolbar action's description differs from its own text — not a repeat of the button name.
     * This is what the user sees when hovering over the button.
     */
    fun testEveryToolbarActionDescriptionDiffersFromItsText() {
        val panel = panel()

        val actions = panel.toolbarActions().getChildren(null)
        for (action in actions) {
            val text = action.templatePresentation.text.orEmpty()
            val description = action.templatePresentation.description.orEmpty()

            assertFalse("Action '$text' has an empty description", description.isEmpty())
            assertTrue(
                "Description should differ from the button text for '$text'",
                text != description
            )
        }
    }

    /**
     * The two publish descriptions word for word, not merely "different from the button name".
     *
     * The rule these follow is "say what the button TAKES, not what it is called", and a description
     * that paraphrases the button — "Send unread remarks to Claude Code" — passes the shape check
     * above while teaching nothing. The file sentence on Publish Selected is the whole reason the
     * descriptions were asked for: taking a whole file by selecting its node is not discoverable
     * any other way.
     */
    fun testTheTwoPublishDescriptionsSayWhatTheButtonTakes() {
        val panel = panel()

        assertEquals("Every remark not yet read", descriptionOf(panel, "Publish Unread"))
        assertEquals(
            "Only the rows you picked. Select a file node to take that whole file",
            descriptionOf(panel, "Publish Selected"),
        )
    }

    /** Both Clear descriptions say the archive happens first, which is the thing worth knowing. */
    fun testBothClearDescriptionsSayTheArchiveHappensFirst() {
        val panel = panel()

        assertTrue(
            descriptionOf(panel, "Clear Handed Over"),
            descriptionOf(panel, "Clear Handed Over").contains("Archived to the history file first"),
        )
        assertTrue(
            descriptionOf(panel, "Clear All"),
            descriptionOf(panel, "Clear All").contains("Archived to the history file first"),
        )
    }

    private fun descriptionOf(panel: RemarksPanel, text: String): String =
        panel.toolbarActions().getChildren(null)
            .single { it.templatePresentation.text == text }
            .templatePresentation.description.orEmpty()

    /**
     * Publish Unread's enablement is "not yet READ", not "still PENDING". A remark published but
     * never acknowledged is exactly the case this button exists for, and gating it on PENDING would
     * grey the button out just when a person wants to hand the remarks over again.
     */
    fun testPublishUnreadStaysEnabledForAPublishedButUnreadRemark() {
        val remark = addRemark(project, "A.kt", LINES, 0..0, "a note")
        markRemarksPublished(project, listOf(remark.id!!))
        val panel = panel()

        assertTrue(publishUnreadIsEnabled(panel))
    }

    /** The other side of the same predicate: with everything read there is nothing left to publish. */
    fun testPublishUnreadIsDisabledOnceEveryRemarkIsRead() {
        val remark = addRemark(project, "A.kt", LINES, 0..0, "a note")
        markRemarksPublished(project, listOf(remark.id!!))
        markRemarksRead(project, listOf(remark.id!!))
        val panel = panel()

        assertFalse(publishUnreadIsEnabled(panel))
    }

    private fun publishUnreadIsEnabled(panel: RemarksPanel): Boolean = isEnabled(panel, "Publish Unread")

    private fun isEnabled(panel: RemarksPanel, text: String): Boolean {
        val action = panel.toolbarActions().getChildren(null)
            .single { it.templatePresentation.text == text }
        val event = TestActionEvent.createTestEvent(action)
        action.update(event)
        return event.presentation.isEnabled
    }

    /**
     * Clear All is the only route by which an answer can ever be pruned — Clear Handed Over leaves
     * answers alone on purpose. Gating the button on remarks alone would strand every answer in a
     * project whose remarks have already been cleared, permanently, with nothing failing anywhere.
     */
    fun testClearAllIsEnabledWithAnswersAndNoRemarks() {
        recordAnswer(project, answer(id = "a-1", remarkId = "r-1", path = "A.kt"))
        val panel = panel()

        assertEquals(0, panel.remarks().size)
        assertTrue(isEnabled(panel, "Clear All"))
    }

    /**
     * The answers half of the toolbar's cached snapshot. It is dropped in the same place the remarks
     * half is, and if it were not, Clear All would keep the enabled state it had when the panel was
     * built for a project whose only content is answers.
     */
    fun testTheToolbarsCachedAnswersAreDroppedWhenAnAnswerArrives() {
        val panel = panel()
        assertEquals(0, panel.answers().size)

        recordAnswer(project, answer(id = "a-1", remarkId = "r-1", path = "A.kt"))
        settleInvocationQueue()

        assertEquals(1, panel.answers().size)
    }

    /**
     * The refresh resolves answers as well as remarks now. Nothing in RemarksTreeTest can catch the
     * panel forgetting the second half: it builds the node model directly and never runs a refresh.
     *
     * It also says the nested answer row is really *visible*, which the node-model tests cannot: a
     * child node that nothing expands is a row nobody sees. `expandAll` walks a live `rowCount`, so
     * it opens the question node with no help, and the row count here is what proves it.
     *
     * ⚠️ The answer is also what puts this question in Done, which starts shut — so the whole thing
     * is one row until Done is opened. That is the cost of counting an answer as processed, and it
     * is why the answer stays nested rather than moving anywhere.
     */
    fun testAnAnswerGetsARowNestedUnderItsQuestion() {
        val remark = addRemark(project, "A.kt", LINES, 0..0, "why is this synchronized?")
        recordAnswer(project, answer(id = "a-1", remarkId = remark.id!!, path = "A.kt"))

        val panel = panel()
        assertEquals(1, panel.tree.rowCount)
        expandEverything(panel)

        // Done, the file group, its one remark, and the answer under that remark.
        assertEquals(4, panel.tree.rowCount)
        val question = panel.tree.getPathForRow(2).lastPathComponent as DefaultMutableTreeNode
        assertEquals(remark.id, (question.userObject as RemarkNode).id)
        val nested = panel.tree.getPathForRow(3).lastPathComponent as DefaultMutableTreeNode
        assertEquals(question, nested.parent)
        assertEquals("a-1", (nested.userObject as AnswerNode).id)
    }

    /**
     * Delete on an answer row went through remarkNodesUnder, which returns remark rows only, so it
     * did nothing at all and said nothing. Exactly the failure that function's own KDoc warns about.
     *
     * **This is also the no-dialog boundary for an answer row on its own.** An AnswerNode is neither a
     * group nor a node with children, so `selectionHidesRows` answers false and nothing is asked. No
     * TestDialog is registered here, and `TestDialog.DEFAULT` throws on `show()`, so a dialog
     * appearing fails this test rather than being answered silently.
     */
    fun testDeletingASelectedAnswerRowRemovesTheAnswer() {
        recordAnswer(project, answer(id = "a-1", remarkId = "r-1", path = "A.kt"))
        val panel = panel()
        panel.tree.setSelectionRow(1)

        panel.deleteSelected()
        settleInvocationQueue()

        assertTrue(RemarkStore.getInstance(project).allAnswers().isEmpty())
    }

    /**
     * Nesting's other half for Delete: a question and its answer are two rows, but the person
     * pointed at one of them, the question. `leavesOf` now recurses into a RemarkNode, so Delete
     * takes both — otherwise the answer would survive with no question left to sit under, and
     * reappear in the no-question group instead of just disappearing.
     *
     * Neither the question nor its answer is a GroupNode, so this also proves nothing asks first:
     * no TestDialog is registered here at all, and Messages.showYesNoDialog throws if the
     * production code ever tries to show one without a test double in place.
     */
    fun testDeletingASelectedQuestionRemovesItsAnswerTooWithNoDialog() {
        val remark = addRemark(project, "A.kt", LINES, 0..0, "why is this synchronized?")
        recordAnswer(project, answer(id = "a-1", remarkId = remark.id!!, path = "A.kt"))
        val panel = panel()
        expandEverything(panel)

        // Row 0 is Done, row 1 the file group, row 2 the question, row 3 the answer under it.
        panel.tree.setSelectionRow(2)

        panel.deleteSelected()
        settleInvocationQueue()

        assertTrue(RemarkStore.getInstance(project).all().isEmpty())
        assertTrue(RemarkStore.getInstance(project).allAnswers().isEmpty())
    }

    /**
     * The other side of the same change: a group row still hides an unknown number of rows — a
     * file group here, but the same is true of the General group and the Answers group — so
     * selecting one still asks. TestDialog.NO answers the dialog with no real window, and the
     * remark surviving is what proves the dialog was actually consulted, not skipped: if
     * deleteSelected stopped asking, the remark would already be gone by the time this checks.
     */
    fun testSelectingAFileGroupStillAsksFirst() {
        addRemark(project, "A.kt", LINES, 0..0, "a note")
        val panel = panel()
        // Row 0 is the Open group, row 1 the file group.
        panel.tree.setSelectionRow(1)

        TestDialogManager.setTestDialog(TestDialog.NO, testRootDisposable)
        panel.deleteSelected()

        assertEquals(1, RemarkStore.getInstance(project).all().size)
    }

    /**
     * ⚠️ The case the confirmation rule was originally written wrong for, and the reason it is not
     * simply "does the selection contain a group row".
     *
     * A question with an answer under it draws its own expand handle, so a person can shut it by
     * hand. Then Delete on that question takes an answer that is not on screen — and `deleteAnswer`
     * archives nothing to the history file, unlike Clear All and Clear Handed Over, so it is gone for
     * good. `collapsedGroups` records group keys only, so this state does not even survive the next
     * refresh, which makes it a window rather than a mode; the answer is no less unrecoverable inside
     * that window.
     *
     * Both rows surviving is what proves the dialog was consulted: TestDialog.NO answers no.
     */
    fun testDeletingACollapsedQuestionAsksFirst() {
        val remark = addRemark(project, "A.kt", LINES, 0..0, "why is this synchronized?")
        recordAnswer(project, answer(id = "a-1", remarkId = remark.id!!, path = "A.kt"))
        val panel = panel()
        expandEverything(panel)

        // Row 0 is Done, row 1 the file group, row 2 the question, row 3 the answer under it.
        panel.tree.setSelectionRow(2)
        panel.tree.collapsePath(panel.tree.getPathForRow(2))
        assertFalse(panel.tree.isExpanded(panel.tree.getPathForRow(2)))

        TestDialogManager.setTestDialog(TestDialog.NO, testRootDisposable)
        panel.deleteSelected()

        assertEquals(1, RemarkStore.getInstance(project).all().size)
        assertEquals(1, RemarkStore.getInstance(project).allAnswers().size)
    }

    /**
     * The boundary between the two rules above: a selection holding both a question row and a group
     * row still asks. The group is enough on its own, and one leaf beside it does not buy it out.
     */
    fun testAQuestionSelectedTogetherWithAGroupStillAsks() {
        addRemark(project, "A.kt", LINES, 0..0, "a note")
        val panel = panel()

        // Row 0 is the Open group, row 1 the file group, row 2 the remark.
        panel.tree.setSelectionRows(intArrayOf(1, 2))

        TestDialogManager.setTestDialog(TestDialog.NO, testRootDisposable)
        panel.deleteSelected()

        assertEquals(1, RemarkStore.getInstance(project).all().size)
    }

    /**
     * An answer is never published, and it falls out that way rather than being special-cased:
     * selectedIds() reads remark rows only, so Publish Selected greys itself out on an answer row.
     */
    fun testSelectingAnAnswerRowLeavesPublishSelectedWithNothingToSend() {
        recordAnswer(project, answer(id = "a-1", remarkId = "r-1", path = "A.kt"))
        val panel = panel()
        panel.tree.setSelectionRow(1)

        assertEquals(emptyList<String>(), panel.selectedIds())
    }

    /**
     * The double-click decision, without a real popup: showing one needs a window, and which of the
     * two things a double click does is the part that can be wrong.
     */
    fun testADoubleClickReadsAnAnswerRowAndStillNavigatesFromARemarkRow() {
        val remark = addRemark(project, "A.kt", LINES, 0..0, "why is this synchronized?")
        recordAnswer(
            project,
            answer(id = "a-1", remarkId = remark.id!!, path = "A.kt", markdown = "because two threads write it"),
        )
        val panel = panel()
        expandEverything(panel)

        // Row 0 is Done, row 1 the file group, row 2 the remark, row 3 the answer under it.
        panel.tree.setSelectionRow(3)
        assertEquals("because two threads write it", panel.selectedAnswerRow()?.markdown)

        panel.tree.setSelectionRow(2)
        assertNull(panel.selectedAnswerRow())
    }

    /**
     * The other half of that double click, and the half that was missing: an answer row navigates to
     * the code the answer is about, then shows the popup. Reading an answer with the editor still
     * somewhere else is half an answer, and that is what a real IDE session found first.
     *
     * **What this covers:** that navigateToSelected opens the answer's file and puts the caret on the
     * line the row resolved to. Restore the early `return` that only showed the popup and this fails
     * on the very first assertion, because nothing is open at all.
     *
     * **What it does not cover:** the popup. Showing one needs a real window, and what the popup is
     * made of is pinned by AnswerPopupTest instead. So this test says the navigation happens; nothing
     * here says the answer is also shown.
     *
     * The answer's own anchor resolves exactly here — the hash is over the line it names — so the row
     * carries a live position rather than an orphaned one, and the assertion is about where the row
     * says it points now.
     */
    fun testADoubleClickOnAnAnswerRowNavigatesToTheCodeItPointsAt() {
        fileUnderProjectRoot(project, "Answered.kt", "alpha\nbeta\ngamma\ndelta\n")
        recordAnswer(
            project,
            answer(
                id = "a-1",
                remarkId = "r-1",
                path = "Answered.kt",
                startLine = 2,
                endLine = 2,
                textHash = hashLines(listOf("gamma")),
            ),
        )
        val panel = panel()
        // Row 0 is the Answers group, row 1 its one answer.
        panel.tree.setSelectionRow(1)

        panel.navigateToSelected()
        settleInvocationQueue()

        val editor = FileEditorManager.getInstance(project).selectedTextEditor
        assertNotNull("the answer row should have opened its file", editor)
        assertEquals(
            "Answered.kt",
            FileDocumentManager.getInstance().getFile(editor!!.document)?.name,
        )
        assertEquals(2, editor.caretModel.logicalPosition.line)
    }

    /**
     * An answer with no file — one to a general remark, or one whose remark was deleted before it
     * arrived — has nothing to navigate to. It must open no editor and it must not throw; the popup
     * still comes up, which is the part this cannot see.
     */
    fun testAnAnswerWithNoFileOpensNothingAndDoesNotThrow() {
        recordAnswer(project, answer(id = "a-1", remarkId = "r-1", path = ""))
        val panel = panel()
        panel.tree.setSelectionRow(1)

        panel.navigateToSelected()
        settleInvocationQueue()

        assertEquals(0, FileEditorManager.getInstance(project).openFiles.size)
    }

    /**
     * Opens every row, Done included, the way a person clicking on it would. The panel deliberately
     * leaves Done shut, so a test about anything *inside* Done — an answered question and its nested
     * answer, which are the ordinary shape now — has nothing on screen to select without this.
     *
     * The same while loop the panel's own expandAll uses, and for the same reason: expanding a row
     * pushes every row below it down, so a range built once from the starting rowCount stops short.
     */
    private fun expandEverything(panel: RemarksPanel) {
        var row = 0
        while (row < panel.tree.rowCount) {
            panel.tree.expandRow(row)
            row++
        }
    }

    /**
     * Refreshes, and proves the rebuild really happened.
     *
     * `buildTreeRoot` makes a fresh node graph every time, so a new root object is what says the
     * async read action finished — the same technique
     * [testAFileReloadRefreshesTheTreeTooNotJustTheGutter] uses. Without it, a test asserting the
     * tree looks the same after a refresh passes just as happily when the refresh never ran at all,
     * which is the one thing these tests are about.
     */
    private fun refreshAndSettle(panel: RemarksPanel) {
        val before = panel.tree.model.root
        panel.refresh()
        settleInvocationQueue()
        assertNotSame("the refresh should have rebuilt the tree", before, panel.tree.model.root)
    }

    private fun keyOfRow(component: Any?): String? =
        ((component as? DefaultMutableTreeNode)?.userObject as? GroupNode)?.key

    private fun panel(): RemarksPanel {
        val disposable = Disposer.newDisposable()
        Disposer.register(testRootDisposable, disposable)
        return RemarksPanel(project, disposable).also { settleInvocationQueue() }
    }

    private companion object {
        val LINES = listOf("alpha", "beta")
    }
}
