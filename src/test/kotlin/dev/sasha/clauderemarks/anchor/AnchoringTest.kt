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
}
