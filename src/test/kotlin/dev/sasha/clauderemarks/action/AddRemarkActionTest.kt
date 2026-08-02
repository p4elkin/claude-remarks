package dev.sasha.clauderemarks.action

import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.newvfs.impl.VfsRootAccess
import com.intellij.testFramework.TestActionEvent
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.sasha.clauderemarks.model.RemarkTag
import dev.sasha.clauderemarks.store.RemarkStore
import dev.sasha.clauderemarks.store.addRemark
import dev.sasha.clauderemarks.store.projectRoot
import dev.sasha.clauderemarks.store.remarkTargetProblem
import dev.sasha.clauderemarks.store.resolveAll
import dev.sasha.clauderemarks.ui.remarkNode
import java.io.File
import java.nio.file.Files
import java.nio.file.Path

/**
 * The real action driven the way the editor popup drives it.
 *
 * The three setups are carried over from the debug action's tests, because the question they ask
 * has not changed: is the edited file under project.basePath. What HAS changed is the answer when
 * it is not. The old action removed itself from the menu, which reads as "the plugin is broken".
 * This one stays visible, greys out, and says why.
 */
class AddRemarkActionTest : BasePlatformTestCase() {

    override fun setUp() {
        super.setUp()
        // The light fixture project is shared across test classes, not only across the methods of
        // one class, so a remark left by RemarkEditsTest would still be here. Clear in setUp.
        RemarkStore.getInstance(project).clear()
    }

    fun testARealFileUnderTheProjectRootEnablesTheAction() {
        val event = TestActionEvent.createTestEvent(action, contextFor(editorFor(fileUnderProjectRoot())))

        action.update(event)

        assertTrue(event.presentation.isVisible)
        assertTrue(event.presentation.isEnabled)
    }

    fun testAnInMemoryFixtureFileLeavesTheItemVisibleButDisabled() {
        myFixture.configureByText("Foo.kt", CONTENT)
        val documentFile = FileDocumentManager.getInstance().getFile(myFixture.editor.document)!!

        assertEquals("temp", documentFile.fileSystem.protocol)
        assertNull(VfsUtilCore.getRelativePath(documentFile, projectRoot(project)!!))

        val event = TestActionEvent.createTestEvent(action, contextFor(myFixture.editor))
        action.update(event)

        assertTrue("the item must not vanish", event.presentation.isVisible)
        assertFalse(event.presentation.isEnabled)
        assertNotNull(event.presentation.description)
    }

    /**
     * The same file through a symlink to the project directory. VFS keeps the symlink as its own
     * node, so the root is not an ancestor and getRelativePath returns null. On macOS this is
     * ordinary: /tmp and /var are symlinks into /private.
     */
    fun testAFileReachedThroughASymlinkLeavesTheItemVisibleButDisabled() {
        val real = fileUnderProjectRoot()
        val root = Path.of(project.basePath!!)
        val link = root.resolveSibling(root.fileName.toString() + "_link")
        Files.createSymbolicLink(link, root)
        try {
            VfsRootAccess.allowRootAccess(testRootDisposable, link.toString())
            val throughLink = LocalFileSystem.getInstance()
                .refreshAndFindFileByPath(link.resolve(real.name).toString())!!

            val event = TestActionEvent.createTestEvent(action, contextFor(editorFor(throughLink)))
            action.update(event)

            assertTrue("the item must not vanish", event.presentation.isVisible)
            assertFalse(event.presentation.isEnabled)
        } finally {
            Files.deleteIfExists(link)
        }
    }

    fun testTheReasonNamesTheProblemInPlainWords() {
        myFixture.configureByText("Foo.kt", CONTENT)

        val problem = remarkTargetProblem(project, myFixture.editor)

        assertNotNull(problem)
        assertTrue(problem!!.contains("project"))
    }

    /**
     * The whole path from a submitted input to a tool window row, without opening a popup:
     * the popup's only job is to produce the text and the tag.
     */
    fun testASubmittedRemarkLandsOnTheRightLinesWithItsTag() {
        val editor = editorFor(fileUnderProjectRoot())

        val lines = selectedLines(
            editor.document,
            editor.selectionModel.selectionStart,
            editor.selectionModel.selectionEnd,
        )
        addRemark(project, "Foo.kt", editor.document.text.split("\n"), lines, "why beta?", RemarkTag.BUG)

        val stored = RemarkStore.getInstance(project).all().single()
        assertEquals(1, stored.startLine)
        assertEquals(2, stored.endLine)
        assertEquals(RemarkTag.BUG, stored.tag)
        assertEquals("2-3", remarkNode(resolveAll(project).single()).position)
    }

    private val action = AddRemarkAction()

    private fun fileUnderProjectRoot(): VirtualFile {
        val onDisk = File(project.basePath!!, "Foo.kt")
        onDisk.parentFile.mkdirs()
        onDisk.writeText(CONTENT)
        val file = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(onDisk)!!
        // The light fixture project is shared by every test class in the JVM, and several of them
        // write their own content to this same path. refreshAndFindFileByIoFile does NOT re-read a
        // file VFS already knows about, so without this the Document here can hold another class's
        // text and the line assertions below depend on which class ran first.
        file.refresh(false, false)
        return file
    }

    /** "beta" and "gamma" selected as whole lines, the way a gutter drag leaves a selection. */
    private fun editorFor(file: VirtualFile): Editor {
        myFixture.openFileInEditor(file)
        return myFixture.editor.also {
            it.selectionModel.setSelection(
                it.document.getLineStartOffset(1),
                it.document.getLineStartOffset(3),
            )
        }
    }

    private fun contextFor(editor: Editor): DataContext =
        SimpleDataContext.builder()
            .add(CommonDataKeys.PROJECT, project)
            .add(CommonDataKeys.EDITOR, editor)
            .add(CommonDataKeys.VIRTUAL_FILE, FileDocumentManager.getInstance().getFile(editor.document))
            .build()

    private companion object {
        const val CONTENT = "alpha\nbeta\ngamma\ndelta\n"
    }
}
