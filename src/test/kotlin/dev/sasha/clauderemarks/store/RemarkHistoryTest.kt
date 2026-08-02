package dev.sasha.clauderemarks.store

import dev.sasha.clauderemarks.model.RemarkSeverity
import dev.sasha.clauderemarks.model.RemarkTag
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
