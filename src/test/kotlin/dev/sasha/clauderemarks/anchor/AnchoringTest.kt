package dev.sasha.clauderemarks.anchor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class AnchoringTest {

    private val file = listOf(
        "package demo",          // 0
        "",                      // 1
        "fun alpha() {",         // 2
        "    println(\"a\")",    // 3
        "}",                     // 4
        "",                      // 5
        "fun beta() {",          // 6
        "    println(\"b\")",    // 7
        "}",                     // 8
    )

    @Test
    fun `hash ignores leading and trailing whitespace`() {
        assertEquals(
            hashLines(listOf("fun alpha() {", "    println(\"a\")")),
            hashLines(listOf("   fun alpha() {   ", "\tprintln(\"a\")")),
        )
    }

    @Test
    fun `hash distinguishes different text`() {
        assertNotEquals(hashLines(listOf("fun alpha()")), hashLines(listOf("fun beta()")))
    }

    @Test
    fun `hash keeps the line boundaries apart`() {
        // Without the newline fed between lines these two would hash the same.
        assertNotEquals(hashLines(listOf("ab", "c")), hashLines(listOf("a", "bc")))
    }

    @Test
    fun `capture with no context lines records only the range`() {
        val anchor = captureAnchor(file, startLine = 2, endLine = 4, contextLines = 0)

        assertEquals(emptyList<String>(), anchor.contextBefore)
        assertEquals(emptyList<String>(), anchor.contextAfter)
        assertEquals(hashLines(file.subList(2, 5)), anchor.textHash)
    }

    @Test
    fun `capture clamps negative line numbers to the start of the file`() {
        val anchor = captureAnchor(file, startLine = -5, endLine = -2, contextLines = 2)

        assertEquals(0, anchor.startLine)
        assertEquals(0, anchor.endLine)
        assertEquals(emptyList<String>(), anchor.contextBefore)
    }

    @Test
    fun `capture records the range and its context`() {
        val anchor = captureAnchor(file, startLine = 2, endLine = 4, contextLines = 2)

        assertEquals(2, anchor.startLine)
        assertEquals(4, anchor.endLine)
        assertEquals(listOf("package demo", ""), anchor.contextBefore)
        assertEquals(listOf("", "fun beta() {"), anchor.contextAfter)
        assertEquals(hashLines(file.subList(2, 5)), anchor.textHash)
    }

    @Test
    fun `capture at the start of a file has empty leading context`() {
        val anchor = captureAnchor(file, startLine = 0, endLine = 0, contextLines = 3)

        assertEquals(emptyList<String>(), anchor.contextBefore)
        assertEquals(listOf("", "fun alpha() {", "    println(\"a\")"), anchor.contextAfter)
    }

    @Test
    fun `capture at the end of a file has empty trailing context`() {
        val anchor = captureAnchor(file, startLine = 8, endLine = 8, contextLines = 3)

        assertEquals(emptyList<String>(), anchor.contextAfter)
    }

    @Test
    fun `capture on an empty file does not throw`() {
        val anchor = captureAnchor(emptyList(), startLine = 0, endLine = 0)

        assertEquals(0, anchor.startLine)
        assertEquals(0, anchor.endLine)
        assertEquals(emptyList<String>(), anchor.contextBefore)
        assertEquals(emptyList<String>(), anchor.contextAfter)
    }

    @Test
    fun `capture clamps a range that runs past the end of the file`() {
        val anchor = captureAnchor(file, startLine = 7, endLine = 99, contextLines = 1)

        assertEquals(7, anchor.startLine)
        assertEquals(8, anchor.endLine)
        assertEquals(emptyList<String>(), anchor.contextAfter)
    }

    @Test
    fun `unchanged file resolves exactly`() {
        val anchor = captureAnchor(file, 2, 4)

        assertEquals(AnchorResult.Exact(2, 4), resolveAnchor(anchor, file))
    }

    @Test
    fun `lines inserted above relocate the anchor downwards`() {
        val anchor = captureAnchor(file, 2, 4)
        val edited = listOf("// new header", "// another") + file

        assertEquals(AnchorResult.Relocated(4, 6), resolveAnchor(anchor, edited))
    }

    @Test
    fun `lines removed above relocate the anchor upwards`() {
        val anchor = captureAnchor(file, 6, 8)
        val edited = file.toMutableList().apply { removeAt(1) }

        assertEquals(AnchorResult.Relocated(5, 7), resolveAnchor(anchor, edited))
    }

    @Test
    fun `reindenting the block still resolves exactly`() {
        val anchor = captureAnchor(file, 2, 4)
        val edited = file.toMutableList().apply {
            this[2] = "        fun alpha() {"
            this[3] = "                println(\"a\")"
        }

        assertEquals(AnchorResult.Exact(2, 4), resolveAnchor(anchor, edited))
    }

    @Test
    fun `editing the block but not its surroundings relocates via context`() {
        val anchor = captureAnchor(file, 2, 4)
        val edited = file.toMutableList().apply {
            this[3] = "    println(\"a changed\")"
        }

        assertEquals(AnchorResult.Relocated(2, 4), resolveAnchor(anchor, edited))
    }

    /**
     * The pinned limitation, asserted three times over because it is easy to "improve" away.
     * The context pass only finds a block that kept its line count. When a line is added or
     * removed inside the marked block, the trailing context no longer sits at the stored
     * length, and the remark orphans.
     *
     * Searching for the trailing context at other lengths is what the test below,
     * `a repeating trailing context never relocates onto the code after it`, rules out.
     */
    @Test
    fun `a line added inside the block orphans the remark`() {
        val anchor = captureAnchor(file, 2, 4)
        val edited = file.toMutableList().apply { add(3, "    println(\"extra\")") }

        assertEquals(AnchorResult.Orphaned(2, 4), resolveAnchor(anchor, edited))
    }

    @Test
    fun `a line removed from inside the block orphans the remark`() {
        val anchor = captureAnchor(file, 2, 4)
        val edited = file.toMutableList().apply { removeAt(3) }

        assertEquals(AnchorResult.Orphaned(2, 4), resolveAnchor(anchor, edited))
    }

    @Test
    fun `a block that grew while it also moved is orphaned`() {
        val anchor = captureAnchor(file, 2, 4)
        val edited = listOf("// new header") +
            file.subList(0, 3) +
            listOf("    println(\"extra\")") +
            file.subList(3, file.size)

        assertEquals(AnchorResult.Orphaned(2, 4), resolveAnchor(anchor, edited))
    }

    /**
     * The reason the block is pinned to its stored length. Context lines are compared
     * trimmed, so `}` / blank / `@Test` below one test method is the same three lines as
     * below the next one. A search that accepted the trailing context at any other length
     * would answer Relocated(4, 8) here — a range starting at the closing brace of testA and
     * running into the body of testB. The remark must orphan instead.
     */
    @Test
    fun `a repeating trailing context never relocates onto the code after it`() {
        val tests = listOf(
            "class DemoTest {",      // 0
            "",                      // 1
            "    @Test",             // 2
            "    fun testA() {",     // 3
            "        assertA()",     // 4  <- the remark
            "    }",                 // 5
            "",                      // 6
            "    @Test",             // 7
            "    fun testB() {",     // 8
            "        assertB()",     // 9
            "    }",                 // 10
            "",                      // 11
            "    @Test",             // 12
            "    fun testC() {",     // 13
            "        assertC()",     // 14
            "    }",                 // 15
            "}",                     // 16
        )
        val anchor = captureAnchor(tests, 4, 4)
        // contextAfter is ["}", "", "@Test"], which repeats four lines further down.
        val edited = tests.toMutableList().apply { removeAt(4) }

        assertEquals(AnchorResult.Orphaned(4, 4), resolveAnchor(anchor, edited))
    }

    @Test
    fun `block and context both gone leaves the remark orphaned at its stale lines`() {
        val anchor = captureAnchor(file, 2, 4)
        val edited = listOf("something", "entirely", "different", "here", "now", "ok")

        assertEquals(AnchorResult.Orphaned(2, 4), resolveAnchor(anchor, edited))
    }

    @Test
    fun `a block moved beyond the search radius is orphaned`() {
        val anchor = captureAnchor(file, 2, 4)
        val edited = List(50) { "filler $it" } + file.subList(2, 5)

        assertEquals(AnchorResult.Orphaned(2, 4), resolveAnchor(anchor, edited, radius = 10))
    }

    @Test
    fun `blank context alone never matches`() {
        val blankish = listOf("", "", "target line", "", "")
        val anchor = captureAnchor(blankish, 2, 2)
        val edited = listOf("", "", "different line", "", "", "", "")

        assertEquals(AnchorResult.Orphaned(2, 2), resolveAnchor(anchor, edited))
    }

    @Test
    fun `an empty file orphans without throwing`() {
        val anchor = captureAnchor(file, 2, 4)

        assertEquals(AnchorResult.Orphaned(2, 4), resolveAnchor(anchor, emptyList()))
    }

    @Test
    fun `a file shorter than the stored range orphans without throwing`() {
        val anchor = captureAnchor(file, 6, 8)
        val edited = listOf("package demo", "")

        assertEquals(AnchorResult.Orphaned(6, 8), resolveAnchor(anchor, edited))
    }

    @Test
    fun `a stored range that runs backwards orphans instead of throwing`() {
        val anchor = Anchor(5, 2, hashLines(listOf("anything")), emptyList(), emptyList())

        assertEquals(AnchorResult.Orphaned(5, 2), resolveAnchor(anchor, file))
    }

    @Test
    fun `a moved block wins over unchanged context at the old position`() {
        val original = listOf(
            "ctx a", "ctx b", "ctx c",       // 0..2
            "block one", "block two",        // 3..4
            "ctx d", "ctx e", "ctx f",       // 5..7
        )
        val anchor = captureAnchor(original, 3, 4, contextLines = 3)

        // The context still sits at line 3, so the second pass would answer 3. The block
        // itself moved 30 lines down, which the first pass has to find first.
        val edited = listOf("ctx a", "ctx b", "ctx c", "edited one", "edited two") +
            listOf("ctx d", "ctx e", "ctx f") +
            List(30) { "filler $it" } +
            listOf("block one", "block two")

        assertEquals(AnchorResult.Relocated(38, 39), resolveAnchor(anchor, edited))
    }

    @Test
    fun `the nearer of two identical blocks wins even when it sits below`() {
        val block = listOf("target one", "target two")
        // Copies at 5 and at 25, with the anchor stored at 24. A top-down scan would answer 5.
        val anchor = Anchor(24, 25, hashLines(block), emptyList(), emptyList())
        val edited = filler(0, 5) + block + filler(7, 25) + block + filler(27, 40)

        assertEquals(AnchorResult.Relocated(25, 26), resolveAnchor(anchor, edited))
    }

    @Test
    fun `a tie between two identical blocks resolves upwards`() {
        val block = listOf("target one", "target two")
        val anchor = Anchor(20, 21, hashLines(block), emptyList(), emptyList())
        val edited = filler(0, 15) + block + filler(17, 25) + block + filler(27, 40)

        assertEquals(AnchorResult.Relocated(15, 16), resolveAnchor(anchor, edited))
    }

    /** Distinct filler lines for indices [from] until [until], so nothing matches by accident. */
    private fun filler(from: Int, until: Int) = (from until until).map { "filler $it" }

    @Test
    fun `a phrase inside one line is the substring between its columns`() {
        // line 3 is "    println(\"a\")": columns 4..11 are "println"
        assertEquals("println", phraseAt(file, 3, 3, 4, 11))
    }

    @Test
    fun `a phrase across three lines is joined with newlines`() {
        val lines = listOf("first line here", "middle", "last line text")

        // tail of line 0 from column 6, all of line 1, head of line 2 up to column 9
        assertEquals("line here\nmiddle\nlast line", phraseAt(lines, 0, 2, 6, 9))
    }

    @Test
    fun `a whole-line range has no phrase`() {
        assertEquals(null, phraseAt(file, 2, 4, 0, 0))
    }

    @Test
    fun `a column past the end of its line has no phrase`() {
        // line 3, "    println(\"a\")", is 17 characters long
        assertEquals(null, phraseAt(file, 3, 3, 2, 999))
    }
}
