package dev.sasha.clauderemarks.store

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.sasha.clauderemarks.anchor.AnchorResult
import dev.sasha.clauderemarks.anchor.hashLines
import dev.sasha.clauderemarks.model.RemarkState

/**
 * The two ways resolveAll refuses to trust what is stored, plus the case that proves the
 * refusals are refusals and not a lookup that never worked at all.
 */
class ResolveAllTest : BasePlatformTestCase() {

    fun testAnUnknownProjectRootStillProducesARowPerRemark() {
        val stored = remark(path = "src/Foo.kt", startLine = 4, endLine = 6, hash = "00000000")

        val rows = resolveAll(null, listOf(stored))

        // Returning an empty list here made every remark vanish from the tool window with no
        // explanation. A remark is never dropped, only marked stale.
        assertEquals(1, rows.size)
        assertEquals(AnchorResult.Orphaned(4, 6), rows.single().result)
    }

    fun testAStoredPathThatClimbsOutOfTheProjectIsNotRead() {
        val root = rootWithFiles()
        val escaping = remark(path = "../secret.txt", hash = hashLines(listOf("secret line")))

        // Without the isAncestor check this resolves to Exact(0, 0): findRelativeFile walks
        // ".." through getParent(), so a hand-edited workspace.xml could point a remark at any
        // file on the machine and have the plugin open, read and hash it.
        assertEquals(AnchorResult.Orphaned(0, 0), resolveAll(root, listOf(escaping)).single().result)
    }

    fun testAStoredPathInsideTheProjectIsRead() {
        val root = rootWithFiles()
        val inside = remark(path = "inside.kt", hash = hashLines(listOf("inside line")))

        assertEquals(AnchorResult.Exact(0, 0), resolveAll(root, listOf(inside)).single().result)
    }

    /** A project directory holding one file, with an unrelated file sitting next to it. */
    private fun rootWithFiles() =
        myFixture.addFileToProject("root/inside.kt", "inside line\n").virtualFile.parent
            .also { myFixture.addFileToProject("secret.txt", "secret line\n") }

    private fun remark(path: String, hash: String, startLine: Int = 0, endLine: Int = 0) =
        RemarkState().also {
            it.id = "r-1"
            it.path = path
            it.startLine = startLine
            it.endLine = endLine
            it.text = "note"
            it.textHash = hash
        }
}
