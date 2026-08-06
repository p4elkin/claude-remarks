package dev.sasha.clauderemarks.ui

/**
 * The one character that says "there is more here than the row can show". Its own constant because
 * three functions in this file append it and one of them measures it.
 */
private const val ELLIPSIS = "…"

/**
 * Wraps [text] to at most [maxLines] lines, each measured through [widthOf] rather than a fixed
 * character count. Taking a measurer function instead of a `FontMetrics` keeps this file free of
 * **`java.awt`** and **`com.intellij`** — the same argument `anchor/` and `render/PromptRenderer.kt`
 * make, for the same reason: no fixture, tests in milliseconds. The renderer passes
 * `metrics::stringWidth`; every test in `WrapTextTest` passes a fixed width per character instead,
 * so the arithmetic stays exact.
 *
 * ⚠️ The platform's own `MultiLineTodoRenderer` never wraps — it receives already-separate lines,
 * because a TODO comment is multi-line in the source. The word-break here is this plugin's own, with
 * no platform equivalent to copy.
 *
 * A `\n` in [text] always starts a new line, since a remark can be written with Shift+Enter. Inside
 * each of those segments, words are broken on runs of whitespace, which collapse to nothing at a
 * wrap point rather than opening the next line with a leading space. A single word wider than
 * [maxWidth] on its own is broken mid-word rather than left to overflow. Once wrapping produces more
 * lines than [maxLines], only the first [maxLines] are kept and the last of those is trimmed and
 * given a trailing ellipsis, so the row shows that more text follows rather than silently dropping
 * it. Empty text produces one empty line, never an empty list, so a caller building one renderer row
 * per line always has at least one row to build.
 *
 * ⚠️ **The wrapping stops as soon as more lines exist than [maxLines] can keep.** A remark has no
 * length cap — `ui/RemarkInputPanel.kt` is a plain text area and Shift+Enter is documented — so a
 * pasted stack trace can be very long, and this function runs on the EDT for every row `JTree` needs
 * a height for. Without the early stop the whole text was wrapped on every rebuild for the three
 * lines that survive, and the mid-word break re-measured the entire remaining string once per line
 * it produced, which is quadratic in the length of one very long word.
 *
 * [maxLines] below 1 is raised to 1 rather than refused: this is a drawing helper on a paint path,
 * and a row drawn with one line is a better answer there than an exception thrown from a renderer.
 */
fun wrapToLines(text: String, maxWidth: Int, maxLines: Int, widthOf: (String) -> Int): List<String> {
    val cap = maxLines.coerceAtLeast(1)
    val lines = mutableListOf<String>()
    for (paragraph in text.split("\n")) {
        lines += wrapParagraph(paragraph, maxWidth, cap - lines.size, widthOf)
        if (lines.size > cap) break
    }
    if (lines.size <= cap) return lines
    return truncateWithEllipsis(lines.take(cap), maxWidth, widthOf)
}

/**
 * [text] as it is when it fits [maxWidth], and cut short with a trailing ellipsis when it does not.
 *
 * Separate from [wrapToLines] because the caller — the renderer's grey metadata line — must not have
 * its text re-flowed: that line is one deliberate string, and word-wrapping it would collapse the
 * two-space gap it puts between a position and a file name. All this does is keep it inside the row
 * rather than letting one long "(orphaned, written at …)" push a horizontal scroll bar onto the whole
 * tree.
 */
fun elideToWidth(text: String, maxWidth: Int, widthOf: (String) -> Int): String =
    if (widthOf(text) <= maxWidth) text else ellipsize(text, maxWidth, widthOf)

/**
 * One `\n`-free segment, broken into as many lines as its words need. Never returns an empty list.
 *
 * [wanted] is how many more lines the caller can still use. Once more than that many exist the rest
 * of the segment is left unwrapped: the caller is going to drop everything past its own cap anyway,
 * and the lines already produced are final, because wrapping only ever runs left to right.
 */
private fun wrapParagraph(
    paragraph: String,
    maxWidth: Int,
    wanted: Int,
    widthOf: (String) -> Int,
): List<String> {
    val words = paragraph.split(Regex("\\s+")).filter { it.isNotEmpty() }
    if (words.isEmpty()) return listOf("")

    val lines = mutableListOf<String>()
    var current = ""

    fun flush() {
        lines += current
        current = ""
    }

    for (word in words) {
        if (lines.size > wanted) break
        var remaining = word
        while (lines.size <= wanted && widthOf(remaining) > maxWidth) {
            if (current.isNotEmpty()) flush()
            val cut = longestFittingPrefix(remaining, maxWidth, widthOf)
            lines += remaining.substring(0, cut)
            remaining = remaining.substring(cut)
        }
        val candidate = if (current.isEmpty()) remaining else "$current $remaining"
        if (widthOf(candidate) <= maxWidth) {
            current = candidate
        } else {
            flush()
            current = remaining
        }
    }
    flush()
    return lines
}

/** The widest prefix of [word] that still fits [maxWidth], at least one character so this always makes progress. */
private fun longestFittingPrefix(word: String, maxWidth: Int, widthOf: (String) -> Int): Int {
    var cut = 1
    while (cut < word.length && widthOf(word.substring(0, cut + 1)) <= maxWidth) cut++
    return cut
}

/** Trims the last kept line until it plus an ellipsis fits [maxWidth]. */
private fun truncateWithEllipsis(lines: List<String>, maxWidth: Int, widthOf: (String) -> Int): List<String> {
    val kept = lines.toMutableList()
    kept[kept.lastIndex] = ellipsize(kept.last(), maxWidth, widthOf)
    return kept
}

/**
 * [line] shortened character by character until it plus the ellipsis fits, whatever it started as.
 *
 * The trailing whitespace is trimmed before the ellipsis is appended. Without that, a line cut just
 * after a space renders as "ab …", with a gap that reads like a missing word rather than like text
 * continuing.
 *
 * ⚠️ When the ellipsis alone is wider than [maxWidth] the loop empties the line and this returns the
 * ellipsis on its own, still wider than the row. There is nothing narrower to return, and a row that
 * narrow has bigger problems than one overflowing character.
 */
private fun ellipsize(line: String, maxWidth: Int, widthOf: (String) -> Int): String {
    var kept = line
    while (kept.isNotEmpty() && widthOf(kept + ELLIPSIS) > maxWidth) {
        kept = kept.dropLast(1)
    }
    return kept.trimEnd() + ELLIPSIS
}
