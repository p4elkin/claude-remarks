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
import dev.sasha.clauderemarks.store.RemarkStore
import dev.sasha.clauderemarks.store.projectRoot
import dev.sasha.clauderemarks.store.resolveAll
import dev.sasha.clauderemarks.ui.describe
import java.io.File
import java.nio.file.Files
import java.nio.file.Path

/**
 * The debug action driven the way the editor popup drives it: a data context carrying PROJECT,
 * EDITOR and VIRTUAL_FILE, then update() and actionPerformed().
 *
 * Three setups, because the whole action hinges on one question — is the edited file under
 * project.basePath. A real file under the project directory works. The two setups that do not
 * are here to name the failure: the item disappears from the menu instead of showing greyed out,
 * which looks exactly like "the action did nothing".
 */
class AddDebugRemarkActionTest : BasePlatformTestCase() {

    fun testARealFileUnderTheProjectRootEnablesTheActionAndStoresARemark() {
        val editor = editorFor(fileUnderProjectRoot())
        val event = TestActionEvent.createTestEvent(action, contextFor(editor))
        val before = RemarkStore.getInstance(project).all().size

        action.update(event)
        action.actionPerformed(event)

        assertTrue(event.presentation.isEnabledAndVisible)
        val stored = RemarkStore.getInstance(project).all()
        assertEquals(before + 1, stored.size)
        assertEquals("Foo.kt", stored.last().path)
        assertEquals(1, stored.last().startLine)
        assertEquals(2, stored.last().endLine)

        // The row the tool window's Refresh would build from it, so the whole path from popup
        // click to list row is covered: add, resolve against the file, format.
        assertEquals("Foo.kt:2-3  debug remark  [PENDING]", describe(resolveAll(project).last()))
    }

    /**
     * myFixture.configureByText puts the file in the in-memory temp:// file system, while
     * project.basePath is a real directory on disk, so no fixture file configured that way has
     * a project-relative path. Not a bug in the action; a limit on what a light fixture can
     * test. Kept as a test so the next person does not spend an hour on it.
     */
    fun testAnInMemoryFixtureFileHasNoProjectRelativePathSoTheItemIsHidden() {
        myFixture.configureByText("Foo.kt", CONTENT)
        val documentFile = FileDocumentManager.getInstance().getFile(myFixture.editor.document)!!
        val root = projectRoot(project)!!

        assertEquals("temp", documentFile.fileSystem.protocol)
        assertNull(VfsUtilCore.getRelativePath(documentFile, root))

        val event = TestActionEvent.createTestEvent(action, contextFor(myFixture.editor))
        action.update(event)
        assertFalse(event.presentation.isEnabledAndVisible)
    }

    /**
     * The same file, reached through a symlink to the project directory. VFS keeps the symlink
     * as its own node, so the project root is not an ancestor of it and getRelativePath returns
     * null. On macOS this is not exotic: /tmp and /var are symlinks into /private.
     */
    fun testAFileReachedThroughASymlinkToTheProjectRootHasNoRelativePath() {
        val real = fileUnderProjectRoot()
        val root = Path.of(project.basePath!!)
        val link = root.resolveSibling(root.fileName.toString() + "_link")
        Files.createSymbolicLink(link, root)
        try {
            // The link sits next to the project directory, so it is a VFS root the fixture does
            // not whitelist on its own.
            VfsRootAccess.allowRootAccess(testRootDisposable, link.toString())
            val throughLink = LocalFileSystem.getInstance()
                .refreshAndFindFileByPath(link.resolve(real.name).toString())!!

            assertNull(VfsUtilCore.getRelativePath(throughLink, projectRoot(project)!!))

            val event = TestActionEvent.createTestEvent(action, contextFor(editorFor(throughLink)))
            action.update(event)
            assertFalse(event.presentation.isEnabledAndVisible)
        } finally {
            Files.deleteIfExists(link)
        }
    }

    private val action = AddDebugRemarkAction()

    /** A file written to disk in the project directory, the way a real project holds its files. */
    private fun fileUnderProjectRoot(): VirtualFile {
        val onDisk = File(project.basePath!!, "Foo.kt")
        // The light fixture's project directory is a real path that nothing has created yet.
        onDisk.parentFile.mkdirs()
        onDisk.writeText(CONTENT)
        return LocalFileSystem.getInstance().refreshAndFindFileByIoFile(onDisk)!!
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
            .add(
                CommonDataKeys.VIRTUAL_FILE,
                FileDocumentManager.getInstance().getFile(editor.document),
            )
            .build()

    private companion object {
        const val CONTENT = "alpha\nbeta\ngamma\ndelta\n"
    }
}
