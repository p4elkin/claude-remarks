package dev.sasha.clauderemarks.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * `wrapToLines` on its own, with a `widthOf` that counts one unit per character, so a test can say
 * "this wraps at 4 characters" and mean it exactly. A test built on real font metrics would be
 * unreadable and would break on any font change; this file and `WrapText.kt` both stay free of
 * `java.awt` and `com.intellij`, so no fixture is needed and every test here runs in milliseconds.
 */
class WrapTextTest {

    private val widthOf: (String) -> Int = { it.length }

    @Test
    fun `text shorter than one line stays one line`() {
        assertEquals(listOf("hello"), wrapToLines("hello", maxWidth = 20, maxLines = 3, widthOf = widthOf))
    }

    @Test
    fun `text breaks on a space`() {
        assertEquals(
            listOf("aaaa", "bbbb"),
            wrapToLines("aaaa bbbb", maxWidth = 4, maxLines = 3, widthOf = widthOf),
        )
    }

    @Test
    fun `a run of spaces collapses rather than starting a line`() {
        assertEquals(
            listOf("aaaa", "bbbb"),
            wrapToLines("aaaa     bbbb", maxWidth = 4, maxLines = 3, widthOf = widthOf),
        )
    }

    @Test
    fun `a single word longer than maxWidth breaks mid-word`() {
        assertEquals(
            listOf("abcd", "efgh", "ij"),
            wrapToLines("abcdefghij", maxWidth = 4, maxLines = 3, widthOf = widthOf),
        )
    }

    @Test
    fun `text needing more than maxLines truncates with an ellipsis on the last line`() {
        assertEquals(
            listOf("aaaa", "bbb…"),
            wrapToLines("aaaa bbbb cccc", maxWidth = 4, maxLines = 2, widthOf = widthOf),
        )
    }

    @Test
    fun `empty text gives one empty line rather than none`() {
        assertEquals(listOf(""), wrapToLines("", maxWidth = 20, maxLines = 3, widthOf = widthOf))
    }

    /** A remark can be written with Shift+Enter, and the tree stops flattening that break in task 7. */
    @Test
    fun `a newline inside the text starts a new line`() {
        assertEquals(
            listOf("foo", "bar"),
            wrapToLines("foo\nbar", maxWidth = 20, maxLines = 3, widthOf = widthOf),
        )
    }

    @Test
    fun `each side of a newline still wraps on its own`() {
        assertEquals(
            listOf("aaaa", "bbbb", "cccc"),
            wrapToLines("aaaa bbbb\ncccc", maxWidth = 4, maxLines = 3, widthOf = widthOf),
        )
    }
}
