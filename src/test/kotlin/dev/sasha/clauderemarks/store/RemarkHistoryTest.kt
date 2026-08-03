package dev.sasha.clauderemarks.store

import dev.sasha.clauderemarks.model.RemarkSeverity
import dev.sasha.clauderemarks.model.RemarkTag
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Plain JUnit: the renderer is pure and the writer only needs a temporary directory. */
class RemarkHistoryTest {

    @Test
    fun `a rendered remark carries everything that was stored about it`() {
        val out = renderHistory(
            listOf(
                remark(
                    id = "r-1",
                    path = "src/Foo.kt",
                    startLine = 9,
                    endLine = 11,
                    text = "why is this locked?",
                    tag = RemarkTag.QUESTION,
                    severity = RemarkSeverity.MUST,
                    bucket = "auth refactor",
                    commit = "0123456789abcdef0123456789abcdef01234567",
                )
            ),
            now = 0L,
        )

        assertTrue(out, out.contains("**src/Foo.kt** lines 10-12"))
        assertTrue(out, out.contains("question"))
        assertTrue(out, out.contains("must"))
        assertTrue(out, out.contains("bucket auth refactor"))
        assertTrue(out, out.contains("commit 01234567"))
        assertTrue(out, out.contains("why is this locked?"))
    }

    @Test
    fun `a remark with no tag no bucket and no commit renders without empty separators`() {
        val out = renderHistory(listOf(remark(id = "r-1", tag = null, bucket = null, commit = null)), now = 0L)

        assertFalse(out, out.contains("bucket "))
        assertFalse(out, out.contains("commit "))
        assertTrue(out, out.contains("should"))
    }

    /**
     * The heading is what separates one reading pass from the next. Without it the archive is one
     * undifferentiated bullet list, and the `now` parameter — which exists only so this is testable —
     * has nothing reading it.
     */
    @Test
    fun `each pass is written under its own dated heading`() {
        val out = renderHistory(listOf(remark(id = "r-1")), now = 0L)

        assertTrue(out, Regex("""## cleared \d{4}-\d{2}-\d{2} \d{2}:\d{2}""").containsMatchIn(out))
        // Two different moments must render differently, or the timestamp is not being read.
        assertNotEquals(out, renderHistory(listOf(remark(id = "r-1")), now = 400L * 86_400_000L))
    }

    /**
     * The remark text is indented into a block, so a heading inside it cannot restructure the file.
     * The heading LINE is not indented, and the bucket is the only free-form field on it —
     * setRemarkBucket trims the ends but leaves an inner newline alone. Not reachable from today's
     * single-line chooser; this closes the asymmetry rather than relying on the chooser staying that
     * way.
     */
    @Test
    fun `a newline inside a bucket name cannot break out of the heading line`() {
        val out = renderHistory(listOf(remark(id = "r-1", bucket = "auth\n## forged")), now = 0L)

        assertTrue(out, out.contains("bucket auth ## forged"))
        assertFalse(out, out.lines().any { it.startsWith("## forged") })
    }

    /** A project called "My App / v2" must not turn its archive's name into a path. */
    @Test
    fun `a project name is reduced to something that can be a file name`() {
        assertEquals("My_App___v2", safeName("My App / v2"))
    }

    @Test
    fun `writing twice appends rather than replacing`() {
        val file = Files.createTempDirectory("claude-remarks-history").resolve("deep/history.md")

        assertEquals(1, appendToHistory(file, listOf(remark(id = "r-1", text = "first"))))
        assertEquals(1, appendToHistory(file, listOf(remark(id = "r-2", text = "second"))))

        val written = Files.readString(file)
        assertTrue(written, written.contains("first"))
        assertTrue(written, written.contains("second"))
    }

    @Test
    fun `writing nothing writes no file at all`() {
        val file = Files.createTempDirectory("claude-remarks-history").resolve("history.md")

        assertEquals(0, appendToHistory(file, emptyList()))

        assertFalse(Files.exists(file))
    }
}
