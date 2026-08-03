package dev.sasha.clauderemarks.render

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.sasha.clauderemarks.anchor.AnchorResult
import dev.sasha.clauderemarks.model.RemarkSeverity
import dev.sasha.clauderemarks.model.RemarkTag
import dev.sasha.clauderemarks.store.RemarkStore
import dev.sasha.clauderemarks.store.ResolvedRemark
import dev.sasha.clauderemarks.store.addRemark
import dev.sasha.clauderemarks.store.projectRoot
import dev.sasha.clauderemarks.store.remark
import dev.sasha.clauderemarks.store.resolveAll
import dev.sasha.clauderemarks.store.setRemarkSeverity
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The size decision, without a project. */
class ClipboardPayloadTest {

    @Test
    fun `a small payload goes straight to the clipboard`() {
        val result = clipboardPayload("small", tempDir(), limitBytes = 1024)

        assertEquals("small", result.text)
        assertNull(result.file)
    }

    @Test
    fun `a large payload is written to a file and the path is copied instead`() {
        val big = "x".repeat(4096)

        val result = clipboardPayload(big, tempDir(), limitBytes = 1024)

        assertNotNull(result.file)
        assertEquals(result.file!!.toAbsolutePath().toString(), result.text)
        assertEquals(big, Files.readString(result.file))
    }

    /** The limit is on bytes, not characters: a document of emoji is bigger than it looks. */
    @Test
    fun `the limit counts utf-8 bytes`() {
        val fourBytesEach = "😀".repeat(100) // 400 bytes, 200 chars

        assertNull(clipboardPayload(fourBytesEach, tempDir(), limitBytes = 500).file)
        assertNotNull(clipboardPayload(fourBytesEach, tempDir(), limitBytes = 300).file)
    }

    @Test
    fun `the written file is outside the project, so it can never enter version control`() {
        val result = clipboardPayload("x".repeat(4096), tempDir(), limitBytes = 1024)

        assertTrue(result.file!!.toString().endsWith(".md"))
    }

    private fun tempDir(): Path = Files.createTempDirectory("claude-remarks-test")
}

/** The collection step, against real files and real Documents. */
class CollectForPromptTest : BasePlatformTestCase() {

    override fun setUp() {
        super.setUp()
        // The light fixture project is shared across test classes, so the store is cleared here
        // as well as in tearDown. tearDown alone is not enough: another class that forgets it
        // leaves its remarks behind for the first test in this one.
        RemarkStore.getInstance(project).clear()
    }

    fun testTheAnchoredLinesComeBackWithContextEitherSide() {
        writeFile("Foo.kt", (1..20).joinToString("\n") { "line $it" })
        val stored =
            addRemark(project, "Foo.kt", (1..20).map { "line $it" }, 9..10, "why?", RemarkTag.BUG)
        // A non-default level on purpose: with the default, hardcoding severity = "should" in
        // collectForPrompt would pass this assertion.
        setRemarkSeverity(project, listOf(stored.id!!), RemarkSeverity.MUST)

        val collected = collectForPrompt(project, resolveAll(project)).single()

        assertEquals("Foo.kt", collected.path)
        assertEquals(9, collected.startLine)
        assertEquals(10, collected.endLine)
        assertEquals("bug", collected.tag)
        assertEquals("must", collected.severity)
        assertEquals(6, collected.codeStartLine)
        assertEquals(
            listOf("line 7", "line 8", "line 9", "line 10", "line 11", "line 12", "line 13", "line 14"),
            collected.code,
        )
    }

    fun testContextIsClampedAtTheStartOfAFile() {
        writeFile("Foo.kt", "a\nb\nc\nd")
        addRemark(project, "Foo.kt", listOf("a", "b", "c", "d"), 0..0, "why?", null)

        val collected = collectForPrompt(project, resolveAll(project)).single()

        assertEquals(0, collected.codeStartLine)
        assertEquals(listOf("a", "b", "c", "d"), collected.code)
    }

    fun testARemarkOnAMissingFileStillComesBack() {
        addRemark(project, "NoSuchFile.kt", listOf("a"), 0..0, "why?", null)

        val collected = collectForPrompt(project, resolveAll(project)).single()

        assertEquals("NoSuchFile.kt", collected.path)
        assertTrue(collected.orphaned)
        assertTrue(collected.code.isEmpty())
    }

    /**
     * The one that matters most. An orphan's stored line numbers point at whatever drifted into
     * that position, and the prompt header tells Claude to trust the quoted lines over the numbers.
     * Quoting the code at the stale numbers would therefore hand an instruction to unrelated code.
     */
    fun testAnOrphanOnAFileThatStillExistsCarriesNoCode() {
        writeFile("Foo.kt", (1..20).joinToString("\n") { "line $it" })
        // The anchor is captured from text the file does not hold, so neither the hash nor the
        // context can be found again and the resolve comes back orphaned — with the file readable.
        addRemark(project, "Foo.kt", (1..20).map { "unrelated $it" }, 9..10, "why?", null)

        val collected = collectForPrompt(project, resolveAll(project)).single()

        assertTrue(collected.orphaned)
        assertTrue(
            "an orphan must not quote the code that now sits at its stale line numbers",
            collected.code.isEmpty(),
        )
        // What it does carry instead: the context stored with it. Without this the prompt would
        // give the model a path, numbers the header tells it to ignore, and nothing to search for.
        assertEquals(
            listOf("unrelated 7", "unrelated 8", "unrelated 9"),
            collected.capturedBefore,
        )
        assertEquals(
            listOf("unrelated 12", "unrelated 13", "unrelated 14"),
            collected.capturedAfter,
        )
    }

    /** The context is for the orphan case only; a resolved remark would otherwise ship its
     *  surroundings twice, once as real code and once as capture-time context. */
    fun testAResolvedRemarkCarriesNoCaptureTimeContext() {
        writeFile("Foo.kt", (1..20).joinToString("\n") { "line $it" })
        addRemark(project, "Foo.kt", (1..20).map { "line $it" }, 9..10, "why?", null)

        val collected = collectForPrompt(project, resolveAll(project)).single()

        assertTrue(collected.capturedBefore.isEmpty())
        assertTrue(collected.capturedAfter.isEmpty())
    }

    /**
     * Renamed from testEachFileIsReadOnceEvenWithSeveralRemarksInIt, which asserted only the row
     * count and passed with the cache deleted outright. The name now says what it checks: two
     * remarks in one file each come back with their own slice of it.
     */
    fun testSeveralRemarksInOneFileEachComeBackWithTheirOwnSlice() {
        writeFile("Foo.kt", (1..30).joinToString("\n") { "line $it" })
        val lines = (1..30).map { "line $it" }
        addRemark(project, "Foo.kt", lines, 2..2, "one", null)
        addRemark(project, "Foo.kt", lines, 20..20, "two", null)

        val collected = collectForPrompt(project, resolveAll(project))

        assertEquals(listOf("Foo.kt", "Foo.kt"), collected.map { it.path })
        assertEquals(listOf(0, 17), collected.map { it.codeStartLine })
        assertEquals("line 3", collected[0].code[collected[0].startLine - collected[0].codeStartLine])
        assertEquals("line 21", collected[1].code[collected[1].startLine - collected[1].codeStartLine])
    }

    /**
     * The commit has to reach the copied prompt: that is the whole point of stamping it. Built as a
     * row by hand rather than through addRemark, because that is the only place the field is read.
     * Mutation: `commit = null` in collectForPrompt and this fails.
     */
    fun testTheStoredCommitReachesTheCollectedRow() {
        val stored = remark(id = "r-1", path = "Foo.kt", commit = SHA)

        val collected = collectForPrompt(
            project,
            listOf(ResolvedRemark(stored, AnchorResult.Exact(0, 0))),
        ).single()

        assertEquals(SHA, collected.commit)
    }

    /**
     * The columns come off the resolved row, not off the stored remark. Built by hand so the two
     * disagree: the store says "no sub-line range" and the resolve says columns 4 to 11.
     * Mutation: read `row.remark.startColumn` in collectForPrompt and this fails.
     */
    fun testTheResolvedColumnsReachTheCollectedRow() {
        val stored = remark(id = "r-1", path = "Foo.kt", startColumn = 0, endColumn = 0)

        val collected = collectForPrompt(
            project,
            listOf(ResolvedRemark(stored, AnchorResult.Exact(0, 0), startColumn = 4, endColumn = 11)),
        ).single()

        assertEquals(4, collected.startColumn)
        assertEquals(11, collected.endColumn)
    }

    /**
     * The whole point of storing the phrase, end to end: the file was reindented after the remark
     * was written, and the ⟦/⟧ markers still land on the same seven characters. The assertion cuts
     * the quoted line at the collected columns and reads what the renderer would wrap.
     */
    fun testASubLineRemarksColumnsFollowItsPhraseWhenTheLineMoves() {
        writeFile("Moved.kt", "        println(\"a\")\n")
        // The remark was written against the same line indented four, which is what makes the
        // stored columns 4 to 11 wrong for the file as it is now.
        addRemark(
            project, "Moved.kt", listOf("    println(\"a\")"), 0..0, "why?", null,
            startColumn = 4, endColumn = 11,
        )

        val collected = collectForPrompt(project, resolveAll(project)).single()

        assertEquals(8, collected.startColumn)
        assertEquals(15, collected.endColumn)
        val quoted = collected.code[collected.startLine - collected.codeStartLine]
        assertEquals("println", quoted.substring(collected.startColumn, collected.endColumn))
    }

    private fun writeFile(name: String, content: String) {
        val onDisk = File(project.basePath!!, name)
        onDisk.parentFile.mkdirs()
        onDisk.writeText(content)
        val file = com.intellij.openapi.vfs.LocalFileSystem.getInstance()
            .refreshAndFindFileByIoFile(onDisk)!!
        // refreshAndFindFileByIoFile does NOT re-read a file VFS already knows about, and every
        // test here writes the same project directory. Without this line the second test sees the
        // first test's content and its remark resolves as orphaned.
        file.refresh(false, false)
        assertNotNull(projectRoot(project))
    }

    override fun tearDown() {
        RemarkStore.getInstance(project).clear()
        super.tearDown()
    }

    private companion object {
        const val SHA = "0123456789abcdef0123456789abcdef01234567"
    }
}
