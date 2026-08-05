package dev.sasha.clauderemarks.editor

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.impl.DocumentMarkupModel
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileDocumentManagerListener
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.sasha.clauderemarks.store.RemarkStore
import dev.sasha.clauderemarks.store.addRemark
import dev.sasha.clauderemarks.store.deleteRemark
import dev.sasha.clauderemarks.store.markRemarksPublished
import dev.sasha.clauderemarks.store.notifyRemarksChanged
import dev.sasha.clauderemarks.store.remark
import dev.sasha.clauderemarks.store.settleInvocationQueue
import java.io.File

/**
 * The service, driven against a real markup model. The renderer already has its own tests in
 * RemarkGutterIconTest; everything here is about the four rules the service has to keep, because
 * that is where every defect in the earlier draft lived.
 *
 * The service under test is built by hand rather than taken from project.service(), so each test
 * gets its own listeners and its own tracked set. The light fixture project is shared for the
 * whole run, so the project-level instance would carry state from the previous test method.
 */
class RemarkGutterTest : BasePlatformTestCase() {

    private lateinit var gutter: RemarkGutter

    override fun setUp() {
        super.setUp()
        // The light fixture project is shared across test classes, so the store is cleared here.
        RemarkStore.getInstance(project).clear()
        gutter = RemarkGutter(project)
        Disposer.register(testRootDisposable, gutter)
    }

    fun testAddingTheFirstRemarkToAnOpenFilePutsAnIconInTheGutter() {
        openFoo()
        gutter.start()
        settleInvocationQueue()

        addRemark(project, "Foo.kt", LINES, 1..1, "why?", null)
        settleInvocationQueue()

        assertEquals(1, iconCount())
    }

    fun testDeletingTheLastRemarkTakesItsIconAway() {
        openFoo()
        gutter.start()
        val stored = addRemark(project, "Foo.kt", LINES, 1..1, "why?", null)
        settleInvocationQueue()

        deleteRemark(project, stored.id!!)
        settleInvocationQueue()

        assertEquals(0, iconCount())
    }

    /**
     * What reopening the IDE looks like: the editor exists before the service starts.
     *
     * This one counts raw highlighters before and after start(), not distinct renderers. The
     * project-level RemarkGutter that the postStartupActivity creates is running in this same
     * fixture, and it paints a renderer equal to ours on the same markup model — so iconCount()
     * would read 1 whether or not the gutter under test painted anything, and the seeding block
     * could be deleted with this test still green.
     */
    fun testAnEditorAlreadyOpenWhenTheServiceStartsIsSeeded() {
        openFoo()
        addRemark(project, "Foo.kt", LINES, 1..1, "why?", null)
        settleInvocationQueue()
        val paintedByAnyoneElse = rawIconCount()

        gutter.start()
        settleInvocationQueue()

        assertEquals(paintedByAnyoneElse + 1, rawIconCount())
    }

    fun testOpeningAFileAfterTheServiceStartedAlsoGetsIcons() {
        addRemark(project, "Foo.kt", LINES, 1..1, "why?", null)
        gutter.start()
        settleInvocationQueue()

        openFoo()
        settleInvocationQueue()

        assertEquals(1, iconCount())
    }

    /** An orphan keeps an icon at its stale lines. Losing it would lose the remark on screen. */
    fun testARemarkWhoseCodeIsGoneStillHasAnIcon() {
        openFoo()
        addRemark(project, "Foo.kt", listOf("nothing", "like", "this", "file"), 1..1, "why?", null)
        gutter.start()
        settleInvocationQueue()

        assertEquals(1, iconCount())
    }

    /**
     * The commit travels from the store into the placement, and the placement is what the tooltip
     * is built from. Nothing asserted on it before this: `commit = remark.commit` could be
     * hardcoded to null with the whole suite still green and every gutter tooltip showing no commit
     * for ever.
     */
    fun testTheTooltipCarriesTheCommitFromTheStore() {
        writeGitHead(SHA)
        openFoo()
        addRemark(project, "Foo.kt", LINES, 1..1, "why?", null)
        gutter.start()
        settleInvocationQueue()

        val tooltip = tooltips().single()
        assertTrue(tooltip, tooltip.contains("commit ${SHA.take(8)}"))
    }

    fun testAPublishedRemarkKeepsItsIcon() {
        openFoo()
        val stored = addRemark(project, "Foo.kt", LINES, 1..1, "why?", null)
        gutter.start()
        settleInvocationQueue()

        markRemarksPublished(project, listOf(stored.id!!))
        settleInvocationQueue()

        assertEquals(1, iconCount())
    }

    /**
     * A general remark, about no file, is not stored under a document at all: placementsFor
     * filters on `it.path == path` against a real document's relative path, which is never null
     * or empty, so a general remark is already skipped there with no change needed. This proves
     * it rather than leaving it as a comment's claim.
     */
    fun testAGeneralRemarkProducesNoPlacementAnywhere() {
        openFoo()
        gutter.start()
        settleInvocationQueue()

        RemarkStore.getInstance(project).add(remark(id = "general-1", path = null))
        notifyRemarksChanged(project)
        settleInvocationQueue()

        assertEquals(0, iconCount())
    }

    /**
     * A git checkout, a branch switch, a VCS revert or an external edit replaces the whole document
     * text in one write action. Nothing else in the service re-resolves after that, so the icons
     * kept whatever offsets the platform left them at — which after a branch switch can be
     * unrelated code, with no orphan marking.
     *
     * The assertion is that the highlighters are REBUILT, not merely that an icon is still there.
     * A highlighter that survived carries a position the platform kept across a full text
     * replacement, which means nothing, and rule 3 would then keep it for ever.
     */
    fun testAFileReloadedFromDiskGetsItsIconsResolvedAgain() {
        openFoo()
        addRemark(project, "Foo.kt", LINES, 1..1, "why?", null)
        gutter.start()
        settleInvocationQueue()
        val before = remarkHighlighters()
        assertEquals(1, iconCount())

        // The same call FileDocumentManager makes after it reloads a document. Publishing it
        // directly avoids the write action that the CLAUDE.md grep forbids anywhere under src/.
        ApplicationManager.getApplication().messageBus
            .syncPublisher(FileDocumentManagerListener.TOPIC)
            .fileContentReloaded(
                FileDocumentManager.getInstance().getFile(myFixture.editor.document)!!,
                myFixture.editor.document,
            )
        settleInvocationQueue()

        assertEquals(1, iconCount())
        assertTrue("the icons should have been rebuilt", remarkHighlighters().none { it in before })
    }

    /**
     * Rule 3, the one that matters most. A line typed inside the marked block stretches the
     * highlighter, which is right. A fresh resolve can only orphan, because the search keeps the
     * block pinned to its stored length. The icon must stay where the platform put it.
     *
     * myFixture.type is how the document is changed here. A write action would be the direct way,
     * but the write-action helpers are banned across src/ by the grep in CLAUDE.md — and that
     * grep is a plain text search, so it would match one of those names even inside this comment.
     */
    fun testALineTypedInsideTheBlockDoesNotMoveTheIconBack() {
        openFoo()
        addRemark(project, "Foo.kt", LINES, 1..2, "why?", null)
        gutter.start()
        settleInvocationQueue()
        val beforeTyping = iconLines()

        myFixture.editor.caretModel.moveToOffset(myFixture.editor.document.getLineStartOffset(2))
        myFixture.type("new\n")
        val afterTyping = iconLines()
        assertTrue("the platform should have stretched the highlighter", afterTyping.last > beforeTyping.last)

        // Any unrelated remark change re-resolves every tracked document.
        notifyRemarksChanged(project)
        settleInvocationQueue()

        assertEquals(afterTyping, iconLines())
    }

    /**
     * Enough of a repository for `headCommit`, which reads HEAD and the loose ref and nothing else —
     * the same three lines GitHeadTest writes. Removed again in tearDown: the light fixture project
     * is shared, and a `.git` left behind would silently stamp every remark the next class adds.
     */
    private fun writeGitHead(sha: String) {
        val gitDir = File(project.basePath!!, ".git")
        File(gitDir, "refs/heads").mkdirs()
        File(gitDir, "HEAD").writeText("ref: refs/heads/main\n")
        File(gitDir, "refs/heads/main").writeText("$sha\n")
    }

    override fun tearDown() {
        try {
            File(project.basePath!!, ".git").deleteRecursively()
        } finally {
            super.tearDown()
        }
    }

    private fun openFoo() {
        val onDisk = File(project.basePath!!, "Foo.kt")
        onDisk.parentFile.mkdirs()
        onDisk.writeText(LINES.joinToString("\n"))
        val file = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(onDisk)!!
        // Every test here writes the same path, and refreshAndFindFileByIoFile does not re-read a
        // file VFS already knows about.
        file.refresh(false, false)
        myFixture.openFileInEditor(file)
    }

    /**
     * How many distinct remarks have an icon. Distinct, and not a raw highlighter count, because
     * two renderers for the same remark are equal by design — so if the real project-level service
     * happens to be running as well, it paints the same thing and this still reads 1.
     */
    private fun iconCount(): Int =
        highlighters().mapNotNull { it.gutterIconRenderer as? RemarkGutterIconRenderer }.distinct().size

    /** The distinct tooltips on the document. Distinct for the same reason iconCount is. */
    private fun tooltips(): List<String> =
        highlighters()
            .mapNotNull { it.gutterIconRenderer as? RemarkGutterIconRenderer }
            .map { it.tooltipText }
            .distinct()

    /** Every remark icon on the document, counting one per highlighter rather than per remark. */
    private fun rawIconCount(): Int =
        highlighters().count { it.gutterIconRenderer is RemarkGutterIconRenderer }

    /** The same highlighters as objects, so a test can tell a rebuild from a survivor. */
    private fun remarkHighlighters() =
        highlighters().filter { it.gutterIconRenderer is RemarkGutterIconRenderer }

    /** The line range the icons in this file cover, again collapsed to the distinct answer. */
    private fun iconLines(): IntRange {
        val document = myFixture.editor.document
        return highlighters()
            .filter { it.gutterIconRenderer is RemarkGutterIconRenderer }
            .map { document.getLineNumber(it.startOffset)..document.getLineNumber(it.endOffset) }
            .distinct()
            .single()
    }

    private fun highlighters() =
        DocumentMarkupModel.forDocument(myFixture.editor.document, project, false)
            ?.allHighlighters?.toList() ?: emptyList()

    private companion object {
        val LINES = listOf("alpha", "beta", "gamma", "delta")
        const val SHA = "0123456789abcdef0123456789abcdef01234567"
    }
}
