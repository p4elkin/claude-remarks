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
     * A revision pane whose text is NOT what is on disk. The real file is found —
     * `relativePathOf` answers its path — and the remark is nonetheless refused, because the line
     * numbers describe the revision's text rather than the file the remark would be stored against.
     */
    fun testARevisionPaneWhoseTextDiffersFromTheWorkingCopyIsRefused() {
        val real = fileUnderProjectRoot(project, "Diffed.kt", WORKING_TREE)
        val revision = revisionContentOf(real)
        val editor = diffViewerOn(revision)

        // The document's own file is not the answer, and that is the whole problem: it has the
        // right name and no project path.
        val ownFile = FileDocumentManager.getInstance().getFile(editor.document)!!
        assertEquals("Diffed.kt", ownFile.name)
        assertNull(relativePathOf(project, editor))

        // The diff route does find the working tree's file. Finding it is not permission to store
        // against it — that is remarkTargetProblem's decision, and here it says no.
        assertEquals("Diffed.kt", relativePathOf(project, editor, contextOf(revision)))
        assertEquals(
            "Diffed.kt here is a revision whose text is not what is on disk, so a remark's line " +
                "numbers would not describe the file. Add it on the working copy — the " +
                "working-tree side of this diff, or the file itself.",
            remarkTargetProblem(project, editor, contextOf(revision)),
        )

        // The pair that makes this pane dishonest: the path is the working tree's file, the text
        // the anchor would be captured from is the revision's, and the two are not the same text.
        assertEquals(REVISION, editor.document.text)
        assertEquals(WORKING_TREE, String(real.contentsToByteArray()))
    }

    /**
     * The case this narrowing exists for: comparing a branch's HEAD commit against an older one, to
     * read the whole change at once. The newer pane still holds a revision — its document's file is
     * a LightVirtualFile with no project path, exactly as above — but the branch is checked out, so
     * that revision's text IS the working copy's text, character for character. The line numbers
     * therefore describe the file the remark is stored against, and the remark is accepted.
     */
    fun testARevisionPaneShowingTheWorkingCopysTextIsAccepted() {
        val real = fileUnderProjectRoot(project, "Diffed.kt", WORKING_TREE)
        val head = DiffContentFactory.getInstance().create(project, WORKING_TREE, real)
        val editor = diffViewerOn(head)

        // Still the revision shape, which is what makes this a narrowing rather than a special case
        // for some other kind of pane: without the diff content there is no route to the file.
        assertNull(relativePathOf(project, editor))

        assertNull(remarkTargetProblem(project, editor, contextOf(head)))
        assertEquals("Diffed.kt", relativePathOf(project, editor, contextOf(head)))
    }

    /**
     * A revision whose working copy has no readable text at all — a binary file. The comparison
     * cannot be made, so the refusal stands. Accepting a pane because the check could not be run is
     * the one way this narrowing could store a remark against text nobody has.
     *
     * ⚠️ This is also the shape that caught the candidate list dropping its nulls. The platform
     * builds this pane's document with no file behind it at all — `createPsiDocument` refuses a
     * binary file type — so a `listOfNotNull` promoted the working copy into the position that
     * means "this is the document being shown", and the pane was accepted outright. Both
     * assertions below failed before the list was made positional.
     */
    fun testARevisionWhoseWorkingCopyHasNoDocumentIsRefused() {
        val blob = binaryFileUnderProjectRoot("Blob.bin")
        assertNull(FileDocumentManager.getInstance().getDocument(blob))
        val revision = DiffContentFactory.getInstance().create(project, REVISION, blob)
        val editor = diffViewerOn(revision)

        // The pane's document belongs to nothing, which is what the positional list has to survive.
        assertNull(FileDocumentManager.getInstance().getFile(editor.document))

        val problem = remarkTargetProblem(project, editor, contextOf(revision))
        assertNotNull(problem)
        assertTrue(problem!!.startsWith("Blob.bin here is a revision"))
        assertTrue(problem.contains("working copy"))
    }

    /**
     * The guard that the refusal above did not also swallow the side people actually write on: the
     * working-copy pane of the same diff is still accepted.
     */
    fun testTheWorkingCopySideOfADiffIsStillAccepted() {
        val real = fileUnderProjectRoot(project, "Diffed.kt", WORKING_TREE)
        // createDocument, not create(project, text, file): this is what the platform actually opens
        // on the working-copy side of a diff, and its document IS the real file's document — the
        // whole reason it needs no dataContext to resolve.
        val working = DiffContentFactory.getInstance().createDocument(project, real)!!
        val editor = diffViewerOn(working)

        assertNull(remarkTargetProblem(project, editor, contextOf(working)))
    }

    /**
     * The path-escape guard, on the new route, and the third branch's own message. Neither candidate
     * resolves here — the highlight file is in the fixture's in-memory file system, which is not the
     * project directory on disk — so the refusal must be the existing "no matching file under the
     * project directory" sentence, not the new "working copy" one.
     */
    fun testARevisionWithNoMatchingProjectFileKeepsItsOwnMessage() {
        val outside = myFixture.addFileToProject("elsewhere/Outside.kt", WORKING_TREE).virtualFile
        val revision = DiffContentFactory.getInstance().create(project, REVISION, outside)
        val editor = diffViewerOn(revision)

        assertNull(relativePathOf(project, editor, contextOf(revision)))
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
        val editor = diffViewerOn(revisionContentOf(fileUnderProjectRoot(project, "Diffed.kt", WORKING_TREE)))

        assertNull(relativePathOf(project, editor, DataContext.EMPTY_CONTEXT))
        assertEquals(
            "Diffed.kt in this diff is a revision with no matching file under the project " +
                "directory, so a remark on it could not be found again.",
            remarkTargetProblem(project, editor, DataContext.EMPTY_CONTEXT),
        )
    }

    /**
     * An ordinary editor must not change at all. Even with a diff content sitting in the data
     * context and naming a different file, the document's own file wins.
     */
    fun testAnOrdinaryEditorKeepsItsOwnFileEvenWithADiffContentAround() {
        val other = fileUnderProjectRoot(project, "Other.kt", WORKING_TREE)
        myFixture.openFileInEditor(fileUnderProjectRoot(project, "Diffed.kt", WORKING_TREE))
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
        val revision = revisionContentOf(fileUnderProjectRoot(project, "Diffed.kt", WORKING_TREE))
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
        val diff = diffViewerOn(revisionContentOf(fileUnderProjectRoot(project, "Diffed.kt", WORKING_TREE)))
        assertTrue(AddRemarkIntention().isAvailable(project, diff, null))

        myFixture.configureByText("Loose.kt", WORKING_TREE)
        assertFalse(AddRemarkIntention().isAvailable(project, myFixture.editor, myFixture.file))
    }

    /** The shape a VCS revision produces: a LightVirtualFile document, plus the project file. */
    private fun revisionContentOf(real: VirtualFile): DocumentContent =
        DiffContentFactory.getInstance().create(project, REVISION, real)

    /**
     * A file under the project root that FileDocumentManager will not hand back a Document for.
     * Bytes rather than text, and an extension no file type in the fixture claims, so the platform
     * reads it as binary. Written here rather than beside `fileUnderProjectRoot` in TestRemarks.kt:
     * one test needs it, and a shared helper that produces an unreadable file invites use by tests
     * that meant to produce a readable one.
     */
    private fun binaryFileUnderProjectRoot(name: String): VirtualFile {
        val onDisk = File(project.basePath!!, name)
        onDisk.parentFile.mkdirs()
        onDisk.writeBytes(byteArrayOf(0, 1, 2, 0, 3))
        return LocalFileSystem.getInstance().refreshAndFindFileByIoFile(onDisk)!!
            .also { it.refresh(false, false) }
    }

    private fun diffViewerOn(content: DocumentContent): Editor =
        EditorFactory.getInstance().createViewer(content.document, project, EditorKind.DIFF)
            .also { viewers += it }

    private fun contextOf(content: DocumentContent): DataContext =
        SimpleDataContext.getSimpleContext(DiffDataKeys.CURRENT_CONTENT, content, DataContext.EMPTY_CONTEXT)

    private companion object {
        const val WORKING_TREE = "alpha\nbeta\ngamma\ndelta\n"
        const val REVISION = "alpha\nBETA WAS DIFFERENT\ngamma\n"
    }
}
