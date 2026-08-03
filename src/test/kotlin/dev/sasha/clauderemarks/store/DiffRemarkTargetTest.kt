package dev.sasha.clauderemarks.store

import com.intellij.diff.DiffContentFactory
import com.intellij.diff.contents.DocumentContent
import com.intellij.diff.tools.util.DiffDataKeys
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.EditorKind
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.TestActionEvent
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.sasha.clauderemarks.action.AddRemarkAction
import dev.sasha.clauderemarks.action.AddRemarkIntention
import java.io.File

/**
 * Adding a remark while reading a diff.
 *
 * What this can and cannot check. A real diff viewer is not buildable in a light fixture, so there
 * is no SimpleDiffViewer here and nothing here proves that the platform fills
 * DiffDataKeys.CURRENT_CONTENT for the pane that has focus — that part was read out of
 * TwosideDiffViewer.uiDataSnapshot in the 2025.2 jars and has to be confirmed by hand in a sandbox
 * IDE. What IS built here is the exact content shape a VCS revision produces, from the platform's
 * own DiffContentFactory: a document backed by a LightVirtualFile that carries the file's name but
 * no project path, plus a highlightFile pointing at the project file the revision is a version of.
 * That is the thing RemarkTarget.kt has to get right, and every test below fails if it stops
 * getting it right.
 */
class DiffRemarkTargetTest : BasePlatformTestCase() {

    private val viewers = mutableListOf<Editor>()

    override fun tearDown() {
        try {
            viewers.forEach { EditorFactory.getInstance().releaseEditor(it) }
            viewers.clear()
        } finally {
            super.tearDown()
        }
    }

    /**
     * The case the whole change exists for: the right pane of a diff, showing an older revision of
     * a file that is in the project. The remark is stored against the real file's path, even though
     * the document being read is the revision's text.
     */
    fun testARevisionPaneIsStoredAgainstTheProjectFileItIsAVersionOf() {
        val real = fileUnderProjectRoot("Diffed.kt", WORKING_TREE)
        val revision = revisionContentOf(real)
        val editor = diffViewerOn(revision)

        // The document's own file is not the answer, and that is the whole problem: it has the
        // right name and no project path.
        val ownFile = FileDocumentManager.getInstance().getFile(editor.document)!!
        assertEquals("Diffed.kt", ownFile.name)
        assertNull(relativePathOf(project, editor))

        assertEquals("Diffed.kt", relativePathOf(project, editor, contextOf(revision)))
        val problem = remarkTargetProblem(project, editor, contextOf(revision))
        assertNotNull(problem)
        assertTrue(problem!!.contains("working copy"))

        // The pair that would have made a diff remark honest, if this pane were not refused: the
        // path is the working tree's file, the text the anchor would be captured from is the
        // revision's. Kept as a fact about the fixture, not a claim that a remark should be stored
        // here.
        assertEquals(REVISION, editor.document.text)
        assertEquals(WORKING_TREE, String(real.contentsToByteArray()))
    }

    /**
     * The guard that the refusal above did not also swallow the side people actually write on: the
     * working-copy pane of the same diff is still accepted.
     */
    fun testTheWorkingCopySideOfADiffIsStillAccepted() {
        val real = fileUnderProjectRoot("Diffed.kt", WORKING_TREE)
        // createDocument, not create(project, text, file): this is what the platform actually opens
        // on the working-copy side of a diff, and its document IS the real file's document — the
        // whole reason it needs no dataContext to resolve.
        val working = DiffContentFactory.getInstance().createDocument(project, real)!!
        val editor = diffViewerOn(working)

        assertNull(remarkTargetProblem(project, editor, contextOf(working)))
    }

    /**
     * The guard on the branch order: a revision whose highlight file is ALSO outside the project
     * keeps the existing "no matching file under the project directory" message rather than the new
     * "working copy" one. The two candidates never having resolved is what distinguishes this case
     * from the one above.
     */
    fun testARevisionWithNoMatchingProjectFileKeepsItsOwnMessage() {
        val outside = myFixture.addFileToProject("elsewhere/Outside.kt", WORKING_TREE).virtualFile
        val revision = DiffContentFactory.getInstance().create(project, REVISION, outside)
        val editor = diffViewerOn(revision)

        val problem = remarkTargetProblem(project, editor, contextOf(revision))
        assertNotNull(problem)
        assertTrue(problem!!.contains("no matching file under the project directory"))
    }

    /**
     * The same pane with nothing in the data context. Without the diff content there is no route to
     * the real file, so the refusal stays — and it says diff, because the name in the message is a
     * file the user can see in the project and "outside the project directory" would read as a lie.
     */
    fun testTheSamePaneWithNoDiffContentIsRefusedAndSaysWhy() {
        val editor = diffViewerOn(revisionContentOf(fileUnderProjectRoot("Diffed.kt", WORKING_TREE)))

        assertNull(relativePathOf(project, editor, DataContext.EMPTY_CONTEXT))
        assertEquals(
            "Diffed.kt in this diff is a revision with no matching file under the project " +
                "directory, so a remark on it could not be found again.",
            remarkTargetProblem(project, editor, DataContext.EMPTY_CONTEXT),
        )
    }

    /**
     * The path-escape guard, on the new route. A highlight file that is not under the project root
     * is refused exactly like any other file that is not under it. The fixture's own temp file
     * system is such a place: it is not the project directory and not even the same file system.
     */
    fun testAHighlightFileOutsideTheProjectRootIsRefused() {
        val outside = myFixture.addFileToProject("elsewhere/Outside.kt", WORKING_TREE).virtualFile
        val revision = DiffContentFactory.getInstance().create(project, REVISION, outside)
        val editor = diffViewerOn(revision)

        assertNull(relativePathOf(project, editor, contextOf(revision)))
        assertNotNull(remarkTargetProblem(project, editor, contextOf(revision)))
    }

    /**
     * An ordinary editor must not change at all. Even with a diff content sitting in the data
     * context and naming a different file, the document's own file wins.
     */
    fun testAnOrdinaryEditorKeepsItsOwnFileEvenWithADiffContentAround() {
        val other = fileUnderProjectRoot("Other.kt", WORKING_TREE)
        myFixture.openFileInEditor(fileUnderProjectRoot("Diffed.kt", WORKING_TREE))
        val decoy = revisionContentOf(other)

        assertEquals("Diffed.kt", relativePathOf(project, myFixture.editor, contextOf(decoy)))
    }

    /**
     * The threading, end to end: the action reads the diff content out of the event's own data
     * context. A revision pane is refused either way now, but which sentence it is refused with
     * depends on that threading: with the diff content wired through, the real file is found and
     * the description names it a revision ("working copy"); the sibling test below shows what the
     * description says when that context is missing instead.
     */
    fun testTheActionRefusesADiffPaneThreadedThroughTheEventsDataContext() {
        val revision = revisionContentOf(fileUnderProjectRoot("Diffed.kt", WORKING_TREE))
        val editor = diffViewerOn(revision)
        val action = AddRemarkAction()

        val event = TestActionEvent.createTestEvent(
            action,
            SimpleDataContext.builder()
                .add(CommonDataKeys.PROJECT, project)
                .add(CommonDataKeys.EDITOR, editor)
                .add(DiffDataKeys.CURRENT_CONTENT, revision)
                .build(),
        )
        action.update(event)

        assertTrue(event.presentation.description!!.contains("working copy"))
    }

    /**
     * The intention offers itself in a diff without deciding anything, because it cannot: the
     * daemon computes availability on a background thread and the diff content needs a DataContext,
     * which only the EDT can build. Everywhere else it still decides.
     */
    fun testTheIntentionIsOfferedInADiffPaneAndNotInAFileWithNoProjectPath() {
        val diff = diffViewerOn(revisionContentOf(fileUnderProjectRoot("Diffed.kt", WORKING_TREE)))
        assertTrue(AddRemarkIntention().isAvailable(project, diff, null))

        myFixture.configureByText("Loose.kt", WORKING_TREE)
        assertFalse(AddRemarkIntention().isAvailable(project, myFixture.editor, myFixture.file))
    }

    /** The shape a VCS revision produces: a LightVirtualFile document, plus the project file. */
    private fun revisionContentOf(real: VirtualFile): DocumentContent =
        DiffContentFactory.getInstance().create(project, REVISION, real)

    private fun diffViewerOn(content: DocumentContent): Editor =
        EditorFactory.getInstance().createViewer(content.document, project, EditorKind.DIFF)
            .also { viewers += it }

    private fun contextOf(content: DocumentContent): DataContext =
        SimpleDataContext.getSimpleContext(DiffDataKeys.CURRENT_CONTENT, content, DataContext.EMPTY_CONTEXT)

    private fun fileUnderProjectRoot(name: String, text: String): VirtualFile {
        val onDisk = File(project.basePath!!, name)
        onDisk.parentFile.mkdirs()
        onDisk.writeText(text)
        // The light fixture project is shared by every test class in the JVM, and several of them
        // write to this same directory. refreshAndFindFileByIoFile does NOT re-read a file VFS
        // already knows about, so the refresh is what makes the content assertions above honest.
        return LocalFileSystem.getInstance().refreshAndFindFileByIoFile(onDisk)!!
            .also { it.refresh(false, false) }
    }

    private companion object {
        const val WORKING_TREE = "alpha\nbeta\ngamma\ndelta\n"
        const val REVISION = "alpha\nBETA WAS DIFFERENT\ngamma\n"
    }
}
