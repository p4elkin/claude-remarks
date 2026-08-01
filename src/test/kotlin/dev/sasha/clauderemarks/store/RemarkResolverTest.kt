package dev.sasha.clauderemarks.store

import dev.sasha.clauderemarks.model.RemarkState
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The parts of the resolver that need no project: the join/split pair the debug action and
 * the resolver share, and how a stored remark is read back as an anchor.
 */
class RemarkResolverTest {

    @Test
    fun `context survives a join and a split`() {
        val cases = listOf(
            emptyList(),
            listOf(""),
            listOf("a line"),
            listOf("a line", ""),
            listOf("", "a line"),
            listOf("a", "b", "c"),
        )

        for (lines in cases) {
            assertEquals(lines, splitContext(joinContext(lines)))
        }
    }

    @Test
    fun `no context is stored as null, one blank line as an empty string`() {
        assertEquals(null, joinContext(emptyList()))
        assertEquals("", joinContext(listOf("")))
    }

    @Test
    fun `a stored remark reads back as its anchor`() {
        val remark = RemarkState().also {
            it.startLine = 3
            it.endLine = 5
            it.textHash = "abcdef0123456789"
            it.contextBefore = "line a\nline b"
            it.contextAfter = "line c"
        }

        val anchor = anchorOf(remark)

        assertEquals(3, anchor.startLine)
        assertEquals(5, anchor.endLine)
        assertEquals("abcdef0123456789", anchor.textHash)
        assertEquals(listOf("line a", "line b"), anchor.contextBefore)
        assertEquals(listOf("line c"), anchor.contextAfter)
    }

    @Test
    fun `a remark with nothing stored reads back as an empty anchor`() {
        val anchor = anchorOf(RemarkState())

        assertEquals("", anchor.textHash)
        assertEquals(emptyList<String>(), anchor.contextBefore)
        assertEquals(emptyList<String>(), anchor.contextAfter)
    }
}
