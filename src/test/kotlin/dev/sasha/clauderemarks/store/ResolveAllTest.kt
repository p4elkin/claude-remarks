package dev.sasha.clauderemarks.store

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.sasha.clauderemarks.anchor.AnchorResult
import dev.sasha.clauderemarks.anchor.hashLines

/**
 * The two ways resolveAll refuses to trust what is stored, plus the case that proves the
 * refusals are refusals and not a lookup that never worked at all.
 *
 * This is the half of RemarkResolver.kt that needs a fixture: real files, a real project root,
 * real Documents. The half that needs none — anchorOf and the join/split pair — is in
 * RemarkResolverTest.
 */
class ResolveAllTest : BasePlatformTestCase() {

    fun testAnUnknownProjectRootStillProducesARowPerRemark() {
        val stored = remark(path = "src/Foo.kt", startLine = 4, endLine = 6, textHash = "00000000")

        val rows = resolveAll(null, listOf(stored))

        // Returning an empty list here made every remark vanish from the tool window with no
        // explanation. A remark is never dropped, only marked stale.
        assertEquals(1, rows.size)
        assertEquals(AnchorResult.Orphaned(4, 6), rows.single().result)
    }

    fun testAStoredPathThatClimbsOutOfTheProjectIsNotRead() {
        val root = rootWithFiles()
        val escaping = remark(path = "../secret.txt", textHash = hashLines(listOf("secret line")))

        // Without the isAncestor check this resolves to Exact(0, 0): findRelativeFile walks
        // ".." through getParent(), so a hand-edited workspace.xml could point a remark at any
        // file on the machine and have the plugin open, read and hash it.
        assertEquals(AnchorResult.Orphaned(0, 0), resolveAll(root, listOf(escaping)).single().result)
    }

    fun testAStoredPathInsideTheProjectIsRead() {
        val root = rootWithFiles()
        val inside = remark(path = "inside.kt", textHash = hashLines(listOf("inside line")))

        assertEquals(AnchorResult.Exact(0, 0), resolveAll(root, listOf(inside)).single().result)
    }

    /**
     * A general remark, no path ever stored, is not a remark whose file disappeared. It resolves
     * as itself rather than as an orphan.
     */
    fun testAGeneralRemarkWithNoPathResolvesAsItselfNotOrphaned() {
        val root = rootWithFiles()
        val general = remark(path = null)

        assertEquals(AnchorResult.Exact(0, 0), resolveAll(root, listOf(general)).single().result)
    }

    /** The same convention RenderedRemark.path already uses: an empty string also means "about no
     *  file", not a real path that happens to be empty. */
    fun testAGeneralRemarkWithABlankPathResolvesAsItselfNotOrphaned() {
        val root = rootWithFiles()
        val general = remark(path = "")

        assertEquals(AnchorResult.Exact(0, 0), resolveAll(root, listOf(general)).single().result)
    }

    /**
     * The change above must not widen. A remark with a REAL path pointing at a file that is
     * genuinely missing is still the one case that must stay orphaned.
     */
    fun testARemarkWithARealPathThatIsMissingIsStillOrphaned() {
        val root = rootWithFiles()
        val missing = remark(path = "nowhere.kt")

        assertEquals(AnchorResult.Orphaned(0, 0), resolveAll(root, listOf(missing)).single().result)
    }

    /**
     * A row carries the columns the phrase sits at *now*, not the pair the store holds. The file
     * on disk is indented four characters further than the remark was written against, so the
     * line still resolves exactly — the hash trims — and only the columns move.
     */
    fun testAResolvedRowCarriesTheColumnsThePhraseSitsAtNow() {
        val root = reindentedFile()
        val stored = remark(
            path = "inside.kt",
            startColumn = 4,
            endColumn = 11,
            phrase = "println",
            textHash = hashLines(listOf("    println(\"a\")")),
        )

        val row = resolveAll(root, listOf(stored)).single()

        assertEquals(AnchorResult.Exact(0, 0), row.result)
        assertEquals(8, row.startColumn)
        assertEquals(15, row.endColumn)
    }

    /** Every remark written before the phrase was stored has none, and its columns must come back
     *  untouched — including the 0 to 0 pair a whole-line remark carries. */
    fun testARowWithNoPhraseKeepsTheColumnsItWasStoredWith() {
        val root = reindentedFile()
        val stored = remark(
            path = "inside.kt",
            startColumn = 4,
            endColumn = 11,
            phrase = null,
            textHash = hashLines(listOf("    println(\"a\")")),
        )

        val row = resolveAll(root, listOf(stored)).single()

        assertEquals(4, row.startColumn)
        assertEquals(11, row.endColumn)
    }

    /** A project directory holding one file whose only line is indented eight characters. */
    private fun reindentedFile() =
        myFixture.addFileToProject("reindented/inside.kt", "        println(\"a\")\n").virtualFile.parent

    /** A project directory holding one file, with an unrelated file sitting next to it. */
    private fun rootWithFiles() =
        myFixture.addFileToProject("root/inside.kt", "inside line\n").virtualFile.parent
            .also { myFixture.addFileToProject("secret.txt", "secret line\n") }
}
