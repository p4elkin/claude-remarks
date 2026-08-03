package dev.sasha.clauderemarks.review

import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.ui.UIUtil
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Plain JUnit: filterReviewPaths is pure, no fixture needed. */
class OpenReviewFilesTest {

    @Test
    fun `a path that climbs out of the project is dropped`() {
        val filtered = filterReviewPaths(listOf("../../etc/passwd", "/etc/passwd"))

        assertTrue(filtered.isEmpty())
    }

    @Test
    fun `at most twenty files survive the filter`() {
        val paths = (1..30).map { "file$it.kt" }

        val filtered = filterReviewPaths(paths)

        assertEquals(20, filtered.size)
    }
}

/**
 * openReviewFiles needs a project, so it gets its own fixture-backed class rather than living in
 * the plain JUnit one above — the same split CollectForPromptTest/ClipboardPayloadTest already
 * uses in render/PromptPayloadTest.kt.
 *
 * A light fixture has no VCS root, so ChangeListManager.getChange answers null for every file.
 * That is also the real guard here: reaching ChangeListManager.getInstance(project) in a project
 * with no VCS configured must not throw, and the null answer must fall back to opening a plain
 * editor. The diff-window branch has no automated guard — see task 10's note on that in the plan.
 */
class OpenReviewFilesFixtureTest : BasePlatformTestCase() {

    fun testAFileWithNoLocalChangeStillOpensAsAnEditor() {
        val file = fileUnderProjectRoot("A.kt", "alpha\nbeta\n")

        openReviewFiles(project, listOf("A.kt"))
        settle()

        assertTrue(FileEditorManager.getInstance(project).openFiles.any { it == file })
    }

    private fun fileUnderProjectRoot(name: String, text: String): VirtualFile {
        val onDisk = File(project.basePath!!, name)
        onDisk.parentFile.mkdirs()
        onDisk.writeText(text)
        // The light fixture project is shared by every test class in the JVM; the extra refresh
        // is what makes a freshly written file visible to VFS reads in this test, the same
        // pattern DiffRemarkTargetTest.fileUnderProjectRoot uses.
        return LocalFileSystem.getInstance().refreshAndFindFileByIoFile(onDisk)!!
            .also { it.refresh(false, false) }
    }

    // openReviewFiles hops off to invokeLater, so the queue has to drain before asserting.
    private fun settle() {
        repeat(10) {
            UIUtil.dispatchAllInvocationEvents()
            Thread.sleep(10)
        }
        UIUtil.dispatchAllInvocationEvents()
    }
}
