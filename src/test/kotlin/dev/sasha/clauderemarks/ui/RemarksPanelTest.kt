package dev.sasha.clauderemarks.ui

import com.intellij.openapi.actionSystem.ActionGroup
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileDocumentManagerListener
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.testFramework.TestActionEvent
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.ui.HyperlinkLabel
import com.intellij.ui.PopupHandler
import com.intellij.util.ui.UIUtil
import dev.sasha.clauderemarks.editor.RemarkGutter
import dev.sasha.clauderemarks.model.RemarkSeverity
import dev.sasha.clauderemarks.review.WaitingReviewService
import dev.sasha.clauderemarks.store.RemarkStore
import dev.sasha.clauderemarks.store.addRemark
import dev.sasha.clauderemarks.store.setRemarkBucket
import dev.sasha.clauderemarks.store.settleInvocationQueue
import java.io.File

/**
 * The panel, not just the nodes it builds. RemarksTreeTest only ever looks at the node model, so
 * both defects checked here were invisible to it: the tree showed one open file group out of three,
 * and every rebuild threw the selection away under the user.
 */
class RemarksPanelTest : BasePlatformTestCase() {

    override fun setUp() {
        super.setUp()
        // The light fixture project is shared across test classes. WaitingReviewService is a
        // second piece of shared project state, on top of RemarkStore: task 6's own failure-path
        // test deliberately leaves a review waiting, so without this clear here too, the banner
        // test below would pass or fail depending on which test class ran first.
        RemarkStore.getInstance(project).clear()
        WaitingReviewService.getInstance(project).clear()
    }

    override fun tearDown() {
        RemarkStore.getInstance(project).clear()
        WaitingReviewService.getInstance(project).clear()
        super.tearDown()
    }

    fun testEveryFileGroupIsExpanded() {
        listOf("A.kt", "B.kt", "C.kt").forEach { path ->
            addRemark(project, path, LINES, 0..0, "first note in $path", null)
            addRemark(project, path, LINES, 1..1, "second note in $path", null)
        }

        val panel = panel()

        // Three file nodes plus their six rows. The earlier loop read rowCount once, before any
        // expansion, so only the first file opened and this came back as 5.
        assertEquals(9, panel.tree.rowCount)
    }

    fun testTheSelectionSurvivesARefresh() {
        val remark = addRemark(project, "A.kt", LINES, 0..0, "a note", null)
        val panel = panel()
        panel.tree.setSelectionRow(1)
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
            addRemark(project, path, LINES, 0..0, "first note in $path", null)
            addRemark(project, path, LINES, 1..1, "second note in $path", null)
        }
        val panel = panel()
        assertEquals(6, panel.tree.rowCount)

        panel.tree.collapseRow(0)
        assertEquals(4, panel.tree.rowCount)

        panel.refresh()
        settleInvocationQueue()

        assertEquals(4, panel.tree.rowCount)
    }

    /**
     * The delete confirmation reads a file-node selection as more rows covered than rows picked out.
     * The refresh used to capture the selection as remark ids and restore it as leaf rows, so the
     * first refresh after selecting a file node turned it into N picked-out rows — and Delete then
     * removed a whole file's remarks with no question asked.
     */
    fun testAFileNodeStaysSelectedAsAFileNodeAcrossARefresh() {
        addRemark(project, "A.kt", LINES, 0..0, "one", null)
        addRemark(project, "A.kt", LINES, 1..1, "two", null)
        val panel = panel()
        panel.tree.setSelectionRow(0)
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
        addRemark(project, "Reloaded.kt", LINES, 0..0, "a note", null)

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
     * expandAll walks by row index and grows the range as rows appear, so it opens a third level
     * too. This pins that, and pins that a bucket node is one selection covering every row under it.
     */
    fun testABucketTreeIsFullyExpandedAndABucketNodeSelectsEveryRowUnderIt() {
        val first = addRemark(project, "A.kt", LINES, 0..0, "one", null)
        val second = addRemark(project, "B.kt", LINES, 0..0, "two", null)
        setRemarkBucket(project, listOf(first.id!!, second.id!!), "auth refactor")

        val panel = panel()

        // one bucket + two file groups + two rows
        assertEquals(5, panel.tree.rowCount)

        panel.tree.setSelectionRow(0)
        assertEquals(1, panel.tree.selectionCount)
        assertEquals(2, panel.selectedIds().size)
    }

    /**
     * The only test that reaches `groupNodes` and `recollapse` below the top level. The flat test
     * above keeps every group one step from the root, so a one-level walk over the root's children
     * would pass it; a file group inside a bucket sits one step further down, and a one-level walk
     * never finds its key, so it springs open on the next refresh.
     *
     * The bucket is left open on purpose. Collapsing a bucket is not the case to test here:
     * `com.intellij.ui.treeStructure.Tree.collapsePath` collapses the whole visible subtree by
     * default, so its file groups come back shut whether or not this panel records them, and the
     * assertion would pass either way.
     */
    fun testAFileGroupCollapsedInsideABucketStaysCollapsedAcrossARefresh() {
        val first = addRemark(project, "A.kt", LINES, 0..0, "one", null)
        val second = addRemark(project, "B.kt", LINES, 0..0, "two", null)
        setRemarkBucket(project, listOf(first.id!!, second.id!!), "auth refactor")
        val panel = panel()
        // bucket + two file groups + two rows
        assertEquals(5, panel.tree.rowCount)

        // Row 1 is the first file group under the bucket, not the bucket itself.
        panel.tree.collapseRow(1)
        assertEquals(4, panel.tree.rowCount)

        panel.refresh()
        settleInvocationQueue()

        assertEquals(
            "the file group inside the bucket should still be shut",
            4,
            panel.tree.rowCount,
        )
    }

    /**
     * Right-clicking a row that is not selected used to open the menu against the PREVIOUS selection,
     * because PopupHandler only shows the menu and BasicTreeUI moves the selection on button 1 only.
     * With nothing selected every item was a silent no-op.
     */
    fun testARightClickSelectsTheRowUnderThePointer() {
        addRemark(project, "A.kt", LINES, 0..0, "one", null)
        addRemark(project, "A.kt", LINES, 1..1, "two", null)
        val panel = panel()
        panel.tree.clearSelection()

        val bounds = panel.tree.getRowBounds(2)
        selectRowForPopup(panel.tree, bounds.x + 1, bounds.y + 1)

        assertEquals(2, panel.tree.selectionRows!!.single())
    }

    /** Adding, not replacing: right-clicking inside a multi-row selection must not collapse it. */
    fun testARightClickInsideAnExistingSelectionKeepsIt() {
        addRemark(project, "A.kt", LINES, 0..0, "one", null)
        addRemark(project, "A.kt", LINES, 1..1, "two", null)
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
    fun testTheTreeHasARightClickMenuOfferingSeverityAndBuckets() {
        val panel = panel()

        assertTrue(panel.tree.mouseListeners.any { it is PopupHandler })

        // remarkChangeActions returns a plain group, which the platform inlines where it is placed,
        // so the Severity submenu sits one level below the menu's own children.
        val groups = panel.treeMenu().getChildren(null).filterIsInstance<ActionGroup>()
        val severities = (groups + groups.flatMap { it.getChildren(null).filterIsInstance<ActionGroup>() })
            .map { group -> group.getChildren(null).map { it.templatePresentation.text } }
        assertTrue(
            severities.toString(),
            severities.contains(RemarkSeverity.entries.map { it.name.lowercase() }),
        )
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

        addRemark(project, "A.kt", LINES, 0..0, "one", null)
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

    fun testTheBannerIsHiddenWhenNoReviewIsWaiting() {
        val panel = panel()

        assertFalse(panel.banner.isVisible)
    }

    fun testTheBannerShowsTheWaitingLabel() {
        val panel = panel()
        WaitingReviewService.getInstance(project).start("s1", "a review label", 1800)

        panel.refresh()
        settleInvocationQueue()

        assertTrue(panel.banner.isVisible)
        val text = panel.banner.text.orEmpty()
        assertTrue(text, text.contains("Claude Code is waiting: a review label"))
        assertTrue(text, text.contains("Publish to answer"))
    }

    fun testTheBannerSaysTheRemarksAreWaitingToBeReadAfterASend() {
        val panel = panel()
        WaitingReviewService.getInstance(project).start("s1", "a review label", 1800)
        WaitingReviewService.getInstance(project).markSent("s1", listOf("a"))

        panel.refresh()
        settleInvocationQueue()

        assertTrue(panel.banner.isVisible)
        val text = panel.banner.text.orEmpty()
        assertTrue(text, text.contains("Published 1 remark for Claude Code"))
        assertTrue(text, text.contains("Waiting for it to read them"))
    }

    /**
     * The Send control is gone: publishing is the only way to answer a waiting review, so the
     * banner offers exactly one link now, Reject, not two.
     */
    fun testTheBannerOffersRejectAndNothingElse() {
        val panel = panel()
        WaitingReviewService.getInstance(project)
            .start("s1", "a review label", 1800)

        panel.refresh()
        settleInvocationQueue()

        val links = UIUtil.findComponentsOfType(panel.banner, HyperlinkLabel::class.java)
        assertEquals(1, links.size)
        assertEquals("Reject", links.single().text)
    }

    /**
     * Proves the staleness backstop in WaitingReviewService actually reaches the screen. The live
     * review first, because the banner starts hidden: without that half, this test also passes when
     * updateBanner never runs at all.
     */
    fun testTheBannerIsHiddenForAReviewPastItsDeadline() {
        val panel = panel()
        WaitingReviewService.getInstance(project)
            .start("s1", "a review label", 1800)
        panel.refresh()
        settleInvocationQueue()
        assertTrue(panel.banner.isVisible)

        WaitingReviewService.getInstance(project).clear()
        WaitingReviewService.getInstance(project)
            .start("s2", "a stale label", 0)
        panel.refresh()
        settleInvocationQueue()

        assertFalse(panel.banner.isVisible)
    }

    private fun panel(): RemarksPanel {
        val disposable = Disposer.newDisposable()
        Disposer.register(testRootDisposable, disposable)
        return RemarksPanel(project, disposable).also { settleInvocationQueue() }
    }

    private companion object {
        val LINES = listOf("alpha", "beta")
    }
}
