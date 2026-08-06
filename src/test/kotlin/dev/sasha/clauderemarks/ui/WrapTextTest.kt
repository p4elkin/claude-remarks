package dev.sasha.clauderemarks.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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

    /**
     * A cut that lands just after a space used to render as "aaa …", with a gap that reads like a
     * missing word rather than like text continuing.
     *
     * The widths here are deliberately not one-per-character: the space has to be the last thing left
     * before the ellipsis fits, which can only happen when the ellipsis is wider than the character
     * dropped in front of it. So `i` is narrow, the ellipsis is wide, and everything else sits in
     * between — the shape of a real proportional font, written down exactly.
     */
    @Test
    fun `the space in front of an ellipsis is trimmed`() {
        val proportional: (String) -> Int = { text ->
            text.sumOf { character -> if (character == 'i') 1 else if (character == '…') 5 else 2 }
        }

        assertEquals(
            listOf("aaa…"),
            wrapToLines("aaa ii xx", maxWidth = 13, maxLines = 1, widthOf = proportional),
        )
    }

    /** A drawing helper on a paint path answers with one line rather than throwing. */
    @Test
    fun `maxLines below one still gives one line`() {
        assertEquals(
            listOf("aaa…"),
            wrapToLines("aaaa bbbb", maxWidth = 4, maxLines = 0, widthOf = widthOf),
        )
    }

    /**
     * A remark has no length cap, and this runs on the EDT for every row `JTree` needs a height for.
     * Measuring a pasted stack trace in full, for the three lines that survive, is the cost this
     * pins: the mid-word break used to re-measure the whole remaining string once per line it
     * produced, so one very long word cost time quadratic in its length.
     *
     * The bound is loose on purpose. What matters is that it does not grow with the length of the
     * text, and 20,000 characters at four per line would be five thousand measurements without the
     * early stop.
     */
    @Test
    fun `a very long word stops being measured once the cap is full`() {
        var measured = 0
        val counting: (String) -> Int = { measured++; it.length }

        val wrapped = wrapToLines("x".repeat(20_000), maxWidth = 4, maxLines = 3, widthOf = counting)

        assertEquals(3, wrapped.size)
        assertTrue("measured $measured times, which grows with the text", measured < 100)
    }

    /** The metadata line is one deliberate string, so it is never re-flowed — only cut short. */
    @Test
    fun `elideToWidth leaves text that fits exactly as it was written`() {
        assertEquals("4-6  Foo.kt", elideToWidth("4-6  Foo.kt", maxWidth = 40, widthOf = widthOf))
    }

    @Test
    fun `elideToWidth cuts text that does not fit and marks it with an ellipsis`() {
        assertEquals("4-6…", elideToWidth("4-6  Foo.kt", maxWidth = 4, widthOf = widthOf))
    }
}
