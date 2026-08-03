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
                    severity = "should",
                    text = "why is this here?",
                    orphaned = false,
                    codeStartLine = 1,
                    code = listOf("beta", "gamma", "delta", "epsilon"),
                )
            ),
        )

        // Built from the constant rather than hand-copied, so a wording change to the scale note
        // does not also require editing this expected string.
        val expected = "HEADER\n\n" + SEVERITY_SCALE_NOTE.trim() + "\n\n---\n" +
            "\n## src/Foo.kt\n" +
            "\n### 1. lines 3-4 — bug — should\n\n" +
            "why is this here?\n\n" +
            "```text\n" +
            "  2 | beta\n" +
            "> 3 | gamma\n" +
            "> 4 | delta\n" +
            "  5 | epsilon\n" +
            "```"

        assertEquals(expected, out.trimEnd())
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

        // Severity is now always printed, so "no tag" means the heading goes straight from the
        // line range to the severity with nothing in between.
        assertTrue(out.contains("### 1. lines 1-1 — should\n"))
    }

    @Test
    fun `line numbers in the gutter are padded to a common width`() {
        val out = renderPrompt(
            "H",
            listOf(
                RenderedRemark(
                    path = "a.kt", startLine = 99, endLine = 99, tag = null, severity = "should", text = "t",
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

    /**
     * The remark text is prose in the document, outside every fence, and Shift+Enter in the input
     * popup makes a pasted snippet an ordinary thing to have in it. An unescaped fence there opened
     * a block that never closed, and every remark after it was read as code.
     */
    @Test
    fun `a fence or a heading in the remark text cannot break the document`() {
        val out = renderPrompt(
            "H",
            listOf(
                RenderedRemark(
                    path = "a.kt", startLine = 0, endLine = 0, tag = null, severity = "should",
                    text = "like this:\n```\nval a = 1\n```\n## not a heading\n---",
                    orphaned = false, codeStartLine = 0, code = listOf("one"),
                )
            ),
        )

        // Two fence lines in the whole document: the pair around the quoted code.
        assertEquals(2, out.lines().count { it.startsWith("```") })
        assertTrue(out.contains("\\```"))
        assertTrue(out.contains("\\## not a heading"))
        assertTrue(out.contains("\\---"))
    }

    /**
     * A setext underline is the other way to forge a heading, and it needs only ONE character: "=" a
     * line below a paragraph makes an H1, "-" makes an H2. The remark text is prose in the document,
     * so the line above the underline is always a paragraph — the exact setup setext needs.
     */
    @Test
    fun `a single dash or equals under a line in the remark text cannot forge a heading`() {
        val out = renderPrompt(
            "H",
            listOf(
                RenderedRemark(
                    path = "a.kt", startLine = 0, endLine = 0, tag = null, severity = "should",
                    text = "this is important\n-\nand this\n===",
                    orphaned = false, codeStartLine = 0, code = listOf("one"),
                )
            ),
        )

        assertTrue(out, out.contains("\\-\n"))
        assertTrue(out, out.contains("\\===\n"))
    }

    /**
     * An orphan has no code to quote, but it still carries the lines that surrounded it when it was
     * written. Those are the only thing left to search the file for, and a renamed file orphans
     * every remark in it, so without them a whole file's remarks arrive unactionable.
     */
    @Test
    fun `an orphan quotes the lines that surrounded it when it was written`() {
        val out = renderPrompt(
            "H",
            listOf(
                RenderedRemark(
                    path = "a.kt", startLine = 4, endLine = 4, tag = null, severity = "should", text = "why?",
                    orphaned = true, codeStartLine = 4, code = emptyList(),
                    capturedBefore = listOf("fun foo() {"),
                    capturedAfter = listOf("} // end"),
                )
            ),
        )

        assertTrue(out.contains("fun foo() {"))
        assertTrue(out.contains("} // end"))
        assertTrue(out.contains("search for them"))
        // No ">" marker anywhere: these are not the lines the remark points at, and ">" means
        // exactly that everywhere else in the document.
        assertFalse(out.lines().any { it.startsWith(">") })
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

    @Test
    fun `the heading carries the short commit when there is one`() {
        val out = renderPrompt(
            "H",
            listOf(remark(severity = "must", commit = "0123456789abcdef0123456789abcdef01234567")),
        )

        assertTrue(out, out.contains("— must — commit 01234567"))
    }

    @Test
    fun `the heading carries the severity after the tag`() {
        val out = renderPrompt("H", listOf(remark(tag = "bug", severity = "must")))

        assertTrue(out, out.contains("### 1. lines 1-1 — bug — must"))
    }

    @Test
    fun `a remark with no tag still carries its severity`() {
        val out = renderPrompt("H", listOf(remark(tag = null, severity = "vibe")))

        assertTrue(out, out.contains("### 1. lines 1-1 — vibe"))
    }

    /**
     * The scale is appended by the renderer, not stored in the editable header. Somebody who
     * rewrites the header in settings must not silently lose the meaning of the levels while the
     * levels keep being printed.
     */
    @Test
    fun `the scale is explained under whatever header the user wrote`() {
        val out = renderPrompt("my own header", listOf(remark()))

        assertTrue(out.startsWith("my own header"))
        assertTrue(out.contains(SEVERITY_SCALE_NOTE.trim()))
        assertTrue(
            "the scale belongs above the remarks, not after them",
            out.indexOf(SEVERITY_SCALE_NOTE.trim()) < out.indexOf("### 1."),
        )
    }

    @Test
    fun `an empty remark list gets the header and nothing else`() {
        assertEquals("H\n", renderPrompt("H", emptyList()))
    }

    private fun remark(
        path: String = "a.kt",
        startLine: Int = 0,
        tag: String? = "note",
        severity: String = "should",
        commit: String? = null,
        orphaned: Boolean = false,
        code: List<String> = listOf("one", "two", "three"),
    ) = RenderedRemark(
        path = path,
        startLine = startLine,
        endLine = startLine,
        tag = tag,
        severity = severity,
        commit = commit,
        text = "a note",
        orphaned = orphaned,
        codeStartLine = maxOf(0, startLine - 1),
        code = code,
    )
}
