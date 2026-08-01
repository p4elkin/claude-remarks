package dev.sasha.clauderemarks.store

import dev.sasha.clauderemarks.model.RemarkState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
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
    fun `only no context at all is stored as null`() {
        assertEquals(null, joinContext(emptyList()))
        assertNotNull(joinContext(listOf("")))
        assertNotEquals("", joinContext(listOf("")))
    }

    /**
     * The one that matters. contextBefore/contextAfter go through BaseState.string(), whose
     * setter turns "" into null on assignment, so a join that produced "" for one blank line
     * would read back as no context at all. Joining and splitting in one breath never sees it.
     */
    @Test
    fun `one blank line of context survives being assigned to a remark`() {
        val remark = RemarkState().also {
            it.contextBefore = joinContext(listOf(""))
            it.contextAfter = joinContext(listOf("", "tail"))
        }

        val anchor = anchorOf(remark)

        assertEquals(listOf(""), anchor.contextBefore)
        assertEquals(listOf("", "tail"), anchor.contextAfter)
    }

    /**
     * A stored string without the leading-newline marker is not ours — an older workspace.xml,
     * or one someone edited. Splitting it must not eat its first line, and a single line must
     * not come back as "no context at all".
     */
    @Test
    fun `a context stored without the marker keeps every line`() {
        assertEquals(listOf("line a", "line b"), splitContext("line a\nline b"))
        assertEquals(listOf("only line"), splitContext("only line"))
    }

    @Test
    fun `a stored remark reads back as its anchor`() {
        val remark = RemarkState().also {
            it.startLine = 3
            it.endLine = 5
            it.textHash = "abcdef0123456789"
            it.contextBefore = joinContext(listOf("line a", "line b"))
            it.contextAfter = joinContext(listOf("line c"))
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
