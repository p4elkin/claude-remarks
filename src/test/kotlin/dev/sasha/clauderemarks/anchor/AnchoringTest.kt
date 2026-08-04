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

    /**
     * The two columns are offsets into two different lines, so an end column that comes before the
     * start column is an ordinary partial selection, not a broken range. Drag from column 25 of a
     * long line down to column 5 of a short one and the words in between must still be stored.
     *
     * An ordered check answers null here. The remark then has no phrase at all, so it never gets
     * the reflow rescue in resolveWithPhrase and never gets the ⟦/⟧ markers in the published
     * prompt, while the tree row goes on showing "1:26-3:6".
     */
    @Test
    fun `a phrase across lines survives an end column before its start column`() {
        val lines = listOf(
            "val greeting = \"hello there\" + name", // 35 characters, so column 25 is inside it
            "middle",
            "short()",
        )

        assertEquals("re\" + name\nmiddle\nshort", phraseAt(lines, 0, 2, 25, 5))
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

    /**
     * The path every remark stored today takes, pinned by calling both functions rather than by
     * being careful. Three files, one per answer resolveAnchor can give: unchanged, shifted down,
     * and rewritten past recognition. The columns must come back exactly as they went in.
     */
    @Test
    fun `a null phrase resolves exactly as the line-only resolve does`() {
        val anchor = captureAnchor(file, 2, 4)
        val files = listOf(
            file,
            listOf("// new header", "// another") + file,
            listOf("something", "entirely", "different", "here", "now", "ok"),
        )

        for (lines in files) {
            val resolved = resolveWithPhrase(anchor, lines, phrase = null, startColumn = 4, endColumn = 11)

            assertEquals(resolveAnchor(anchor, lines), resolved.result)
            assertEquals(4, resolved.startColumn)
            assertEquals(11, resolved.endColumn)
        }
    }

    @Test
    fun `a phrase found at a new column inside the same line refreshes the columns`() {
        // line 3 is "    println(\"a\")", so "println" was stored at columns 4..11
        val anchor = captureAnchor(file, 3, 3)
        val edited = file.toMutableList().apply { this[3] = "        println(\"a\")" }

        val resolved = resolveWithPhrase(anchor, edited, "println", startColumn = 4, endColumn = 11)

        // The hash trims each line, so reindenting still resolves exactly. Only the columns moved.
        assertEquals(AnchorResult.Exact(3, 3), resolved.result)
        assertEquals(8, resolved.startColumn)
        assertEquals(15, resolved.endColumn)
    }

    /**
     * The occurrence the person actually selected, not the first one on the line. `total` sits
     * twice on the same line, the remark was written on the second, and nothing about the file
     * changed — so the stored columns are still right and nothing may look the words up again.
     *
     * Searching unconditionally moves the remark to the first `total` with no orphan flag and no
     * error, and the `⟦`/`⟧` markers in the published prompt then bracket the wrong words. It needs
     * no edit and no time to pass, only a phrase that repeats earlier on its own line.
     */
    @Test
    fun `a phrase that repeats on its line keeps the occurrence it was written on`() {
        val lines = listOf("fun add() {", "    total = total + delta", "}")
        val anchor = captureAnchor(lines, 1, 1)
        // The second "total" on that line is columns 12..17.
        assertEquals("total", phraseAt(lines, 1, 1, 12, 17))

        val resolved = resolveWithPhrase(anchor, lines, "total", startColumn = 12, endColumn = 17)

        assertEquals(AnchorResult.Exact(1, 1), resolved.result)
        assertEquals(12, resolved.startColumn)
        assertEquals(17, resolved.endColumn)
    }

    /** The same repeated phrase after the block moved down. The line resolve found it, the stored
     *  columns still hold the phrase on the line it moved to, so they are kept as they are. */
    @Test
    fun `a repeated phrase keeps its columns when the block only moved`() {
        val lines = listOf("fun add() {", "    total = total + delta", "}")
        val anchor = captureAnchor(lines, 1, 1)
        val edited = listOf("// new header", "// another") + lines

        val resolved = resolveWithPhrase(anchor, edited, "total", startColumn = 12, endColumn = 17)

        assertEquals(AnchorResult.Relocated(3, 3), resolved.result)
        assertEquals(12, resolved.startColumn)
        assertEquals(17, resolved.endColumn)
    }

    @Test
    fun `a phrase that no longer exists inside its resolved line keeps the stored columns`() {
        val anchor = captureAnchor(file, 3, 3)
        // The line was edited but its surroundings were not, so the context pass still finds it.
        val edited = file.toMutableList().apply { this[3] = "    printf(\"a\")" }

        val resolved = resolveWithPhrase(anchor, edited, "println", startColumn = 4, endColumn = 11)

        assertEquals(AnchorResult.Relocated(3, 3), resolved.result)
        assertEquals(4, resolved.startColumn)
        assertEquals(11, resolved.endColumn)
    }

    /**
     * The reflowed paragraph, and the one thing the phrase buys that the line resolve cannot do at
     * all. The remark's line was rewrapped into two, so neither its hash nor its context can be
     * found again — but the words themselves are still in the file, one line further down.
     */
    @Test
    fun `a phrase found on another line after the block orphaned relocates the remark to it`() {
        val anchor = captureAnchor(paragraph, 1, 1)

        val resolved = resolveWithPhrase(anchor, reflowed, "brown fox", startColumn = 10, endColumn = 19)

        assertEquals(AnchorResult.Orphaned(1, 1), resolveAnchor(anchor, reflowed))
        assertEquals(AnchorResult.Relocated(2, 2), resolved.result)
        assertEquals(0, resolved.startColumn)
        assertEquals(9, resolved.endColumn)
    }

    /** A phrase written across two lines is stored joined with a newline, and has to be found the
     *  same way: the tail of one line, then the head of the next. */
    @Test
    fun `a phrase spanning two lines is found again on the lines it moved to`() {
        val original = listOf("intro", "call one(", "    two)", "outro")
        val anchor = captureAnchor(original, 1, 2)
        // The block moved, its second line gained a character, and both context lines are gone, so
        // neither the hash pass nor the context pass can find it. The phrase still spans two lines.
        val edited = listOf("a", "b", "c", "call one(", "    two);", "d")

        val resolved = resolveWithPhrase(anchor, edited, "one(\n    two", startColumn = 5, endColumn = 7)

        assertEquals(AnchorResult.Relocated(3, 4), resolved.result)
        assertEquals(5, resolved.startColumn)
        assertEquals(7, resolved.endColumn)
    }

    @Test
    fun `a phrase nowhere in the file stays orphaned`() {
        val anchor = captureAnchor(paragraph, 1, 1)
        val edited = listOf("nothing", "of", "the", "sort", "here")

        val resolved = resolveWithPhrase(anchor, edited, "brown fox", startColumn = 10, endColumn = 19)

        assertEquals(AnchorResult.Orphaned(1, 1), resolved.result)
        assertEquals(10, resolved.startColumn)
        assertEquals(19, resolved.endColumn)
    }

    /** The same radius the line search uses, for the same reason: a phrase found sixty lines away
     *  is a different occurrence of the same words, not the one the remark was written about. */
    @Test
    fun `the search does not look past the radius`() {
        val anchor = captureAnchor(paragraph, 1, 1)
        // Nothing here matches the hash or the stored context — the sentence at the end was
        // rewritten too — so the phrase search is the only thing that can find "brown fox".
        val far = listOf("head", "gone", "tail") +
            List(40) { "filler $it" } +
            listOf("the quick brown fox jumps over the lazy dog")

        assertEquals(
            AnchorResult.Relocated(43, 43),
            resolveWithPhrase(anchor, far, "brown fox", 10, 19, radius = 60).result,
        )
        assertEquals(
            AnchorResult.Orphaned(1, 1),
            resolveWithPhrase(anchor, far, "brown fox", 10, 19, radius = 10).result,
        )
    }

    /**
     * Nearest first is the documented reason findPhrase exists at all rather than a scan from the
     * top of the file. The same words sit on two lines well inside the radius, and which one the
     * search returns depends only on where it was told to start.
     */
    @Test
    fun `findPhrase takes the occurrence nearest the origin, not the first in the file`() {
        val lines = listOf("brown fox") + filler(1, 6) + listOf("brown fox") + filler(7, 9)

        assertEquals(PhraseMatch(0, 0, 0, 9), findPhrase(lines, "brown fox", origin = 0))
        assertEquals(PhraseMatch(6, 6, 0, 9), findPhrase(lines, "brown fox", origin = 6))
        // Two lines above the second one and four below the first: a top-down scan answers 0 here.
        assertEquals(PhraseMatch(6, 6, 0, 9), findPhrase(lines, "brown fox", origin = 4))
    }

    /** A one-line paragraph with a phrase in the middle of it, and the same text rewrapped into
     *  two lines. Shared by the orphan-then-search tests. */
    private val paragraph = listOf(
        "intro",                              // 0
        "the quick brown fox jumps over it",  // 1  <- the remark, on "brown fox"
        "outro",                              // 2
    )

    private val reflowed = listOf(
        "intro",                    // 0
        "the quick",                // 1
        "brown fox jumps over it",  // 2
        "outro",                    // 3
    )
}
