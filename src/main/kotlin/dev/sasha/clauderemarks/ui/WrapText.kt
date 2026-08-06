package dev.sasha.clauderemarks.ui

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
 */
fun wrapToLines(text: String, maxWidth: Int, maxLines: Int, widthOf: (String) -> Int): List<String> {
    val lines = mutableListOf<String>()
    for (paragraph in text.split("\n")) {
        lines += wrapParagraph(paragraph, maxWidth, widthOf)
    }
    if (lines.size <= maxLines) return lines
    return truncateWithEllipsis(lines.take(maxLines), maxWidth, widthOf)
}

/** One `\n`-free segment, broken into as many lines as its words need. Never returns an empty list. */
private fun wrapParagraph(paragraph: String, maxWidth: Int, widthOf: (String) -> Int): List<String> {
    val words = paragraph.split(Regex("\\s+")).filter { it.isNotEmpty() }
    if (words.isEmpty()) return listOf("")

    val lines = mutableListOf<String>()
    var current = ""

    fun flush() {
        lines += current
        current = ""
    }

    for (word in words) {
        var remaining = word
        while (widthOf(remaining) > maxWidth) {
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

/** Trims the last kept line, character by character, until it plus an ellipsis fits [maxWidth]. */
private fun truncateWithEllipsis(lines: List<String>, maxWidth: Int, widthOf: (String) -> Int): List<String> {
    val ellipsis = "…"
    val kept = lines.toMutableList()
    var last = kept[kept.lastIndex]
    while (last.isNotEmpty() && widthOf(last + ellipsis) > maxWidth) {
        last = last.dropLast(1)
    }
    kept[kept.lastIndex] = last + ellipsis
    return kept
}
