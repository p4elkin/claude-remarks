package dev.sasha.clauderemarks.render

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PromptRendererTest {

    @Test
    fun `one remark renders header, file heading, note and code`() {
        val out = renderPrompt(
            "HEADER",
            listOf(
                RenderedRemark(
                    path = "src/Foo.kt",
                    startLine = 2,
                    endLine = 3,
                    tag = "bug",
                    text = "why is this here?",
                    orphaned = false,
                    codeStartLine = 1,
                    code = listOf("beta", "gamma", "delta", "epsilon"),
                )
            ),
        )

        assertEquals(
            """
            HEADER

            ---

            ## src/Foo.kt

            ### 1. lines 3-4 — bug

            why is this here?

            ```text
              2 | beta
            > 3 | gamma
            > 4 | delta
              5 | epsilon
            ```
            """.trimIndent(),
            out.trimEnd(),
        )
    }

    @Test
    fun `remarks are grouped under their file and numbered straight through`() {
        val out = renderPrompt("H", listOf(remark("b/Two.kt", 0), remark("a/One.kt", 5), remark("a/One.kt", 0)))

        assertEquals(
            listOf("## a/One.kt", "### 1.", "### 2.", "## b/Two.kt", "### 3."),
            out.lines().filter { it.startsWith("##") }.map { it.take(if (it.startsWith("###")) 6 else it.length) },
        )
    }

    @Test
    fun `an orphan is labelled so the reader does not trust the line numbers`() {
        val out = renderPrompt("H", listOf(remark("a.kt", 4, orphaned = true)))

        assertTrue(out.contains("orphaned"))
        assertTrue(out.contains("stale"))
    }

    @Test
    fun `a remark with no tag has no tag on its heading`() {
        val out = renderPrompt("H", listOf(remark("a.kt", 0, tag = null)))

        assertTrue(out.contains("### 1. lines 1-1\n"))
    }

    @Test
    fun `line numbers in the gutter are padded to a common width`() {
        val out = renderPrompt(
            "H",
            listOf(
                RenderedRemark(
                    path = "a.kt", startLine = 99, endLine = 99, tag = null, text = "t",
                    orphaned = false, codeStartLine = 97,
                    code = listOf("a", "b", "c", "d", "e"),
                )
            ),
        )

        // The marker takes the first column and the number is padded after it, so a marked line and
        // an unmarked one line up on the "|".
        assertTrue(out.contains("   98 | a"))
        assertTrue(out.contains("> 100 | c"))
    }

    @Test
    fun `a remark whose code could not be read still appears`() {
        val out = renderPrompt("H", listOf(remark("gone.kt", 3, code = emptyList())))

        assertTrue(out.contains("## gone.kt"))
        assertTrue(out.contains("### 1."))
        assertTrue(out.contains("(the file could not be read)"))
    }

    @Test
    fun `an orphan with no code says so, and does not blame the file`() {
        val out = renderPrompt("H", listOf(remark("a.kt", 4, orphaned = true, code = emptyList())))

        assertTrue(out.contains("could not be found in the file"))
        assertFalse(out.contains("could not be read"))
    }

    /** A remark on a .md file, or on any code holding an example fence. */
    @Test
    fun `code holding a fence gets a longer fence, so the block cannot close early`() {
        val out = renderPrompt(
            "H",
            listOf(remark("doc.md", 1, code = listOf("```kotlin", "val a = 1", "```"))),
        )

        assertTrue(out.contains("````text"))
        assertTrue(out.trimEnd().endsWith("````"))
    }

    @Test
    fun `an empty list renders the header alone`() {
        assertEquals("HEADER", renderPrompt("HEADER", emptyList()).trim())
    }

    @Test
    fun `the renderer never emits the word null`() {
        val out = renderPrompt("H", listOf(remark("a.kt", 0, tag = null)))

        assertFalse(out.contains("null"))
    }

    private fun remark(
        path: String,
        startLine: Int,
        tag: String? = "note",
        orphaned: Boolean = false,
        code: List<String> = listOf("one", "two", "three"),
    ) = RenderedRemark(
        path = path,
        startLine = startLine,
        endLine = startLine,
        tag = tag,
        text = "a note",
        orphaned = orphaned,
        codeStartLine = maxOf(0, startLine - 1),
        code = code,
    )
}
