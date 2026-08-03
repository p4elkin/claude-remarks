package dev.sasha.clauderemarks.anchor

import java.security.MessageDigest
import java.util.HexFormat

/** Number of lines kept above and below the anchored range. */
const val CONTEXT_LINES = 3

/** How far from the stored position resolveAnchor looks before giving up. */
const val SEARCH_RADIUS = 200

/** Line numbers are 0-based and inclusive, matching IntelliJ's Document. */
data class Anchor(
    val startLine: Int,
    val endLine: Int,
    val textHash: String,
    val contextBefore: List<String>,
    val contextAfter: List<String>,
)

/**
 * Every result carries line numbers, so a caller can always show the remark somewhere.
 * For [Orphaned] those numbers are the stale ones the remark was stored with.
 */
sealed interface AnchorResult {
    val startLine: Int
    val endLine: Int

    data class Exact(override val startLine: Int, override val endLine: Int) : AnchorResult
    data class Relocated(override val startLine: Int, override val endLine: Int) : AnchorResult
    data class Orphaned(override val startLine: Int, override val endLine: Int) : AnchorResult
}

/**
 * Hashes lines after trimming each one, so that reindenting a block still resolves.
 * Truncated to 16 hex chars to keep workspace.xml small. A collision relocates a remark
 * to a visibly wrong place rather than losing it.
 */
fun hashLines(lines: List<String>): String {
    val digest = MessageDigest.getInstance("SHA-256")
    for (line in lines) {
        digest.update(line.trim().toByteArray(Charsets.UTF_8))
        digest.update('\n'.code.toByte())
    }
    return HexFormat.of().formatHex(digest.digest()).take(16)
}

fun captureAnchor(
    lines: List<String>,
    startLine: Int,
    endLine: Int,
    contextLines: Int = CONTEXT_LINES,
): Anchor {
    // An empty file has no line to sublist; without this the coerce below still
    // produces the range 0..0 and subList(0, 1) throws.
    if (lines.isEmpty()) return Anchor(0, 0, hashLines(emptyList()), emptyList(), emptyList())

    val start = startLine.coerceIn(0, lines.lastIndex)
    val end = endLine.coerceIn(start, lines.lastIndex)
    return Anchor(
        startLine = start,
        endLine = end,
        textHash = hashLines(lines.subList(start, end + 1)),
        // toList() on both: subList returns a view of the caller's list, which would keep the
        // whole file alive and change under the anchor if the caller edits it.
        contextBefore = lines.subList(maxOf(0, start - contextLines), start).toList(),
        contextAfter = lines.subList(end + 1, minOf(lines.size, end + 1 + contextLines)).toList(),
    )
}

/**
 * Checks whether the stored line numbers still point at the anchored text, and if not,
 * looks nearby. Never returns a result that silently changes meaning: a move is reported
 * as Relocated and a failure as Orphaned, both carrying line numbers the caller can show.
 */
fun resolveAnchor(
    anchor: Anchor,
    lines: List<String>,
    radius: Int = SEARCH_RADIUS,
): AnchorResult {
    val span = anchor.endLine - anchor.startLine
    val orphaned = AnchorResult.Orphaned(anchor.startLine, anchor.endLine)
    // A hand-edited workspace.xml can hold endLine < startLine, which would make every
    // subList below throw.
    if (span < 0) return orphaned

    // Negative for a file with fewer lines than the stored range. Every range built from it
    // below is then empty, so nothing is scanned and the answer falls through to orphaned.
    val starts = 0..(lines.size - 1 - span)

    fun blockHashAt(start: Int) = hashLines(lines.subList(start, start + span + 1))

    if (anchor.startLine in starts && blockHashAt(anchor.startLine) == anchor.textHash) {
        return AnchorResult.Exact(anchor.startLine, anchor.endLine)
    }

    // First pass: the text is unchanged but sits somewhere else. The stored position is left
    // out, because candidatesNear yields it first and the check above just hashed it.
    candidatesNear(anchor.startLine, starts, radius).filter { it != anchor.startLine }
        .forEach { start ->
            if (blockHashAt(start) == anchor.textHash) {
                return AnchorResult.Relocated(start, start + span)
            }
        }

    // Second pass: the text itself was edited, but what surrounds it did not move. This one does
    // start at the stored position: that is where an edited-in-place block is found.
    candidatesNear(anchor.startLine, starts, radius).forEach { start ->
        if (contextMatchesAt(anchor, lines, start, span)) {
            return AnchorResult.Relocated(start, start + span)
        }
    }

    return orphaned
}

/**
 * The exact text a sub-line remark points at, for storing as `RemarkState.phrase`, or null.
 *
 * Uses the same validity rule `markersValid` in `render/PromptRenderer.kt` uses for deciding
 * whether to draw the `⟦`/`⟧` markers, so a stored phrase and the markers drawn over it never
 * disagree about what was selected: null when [endColumn] is not strictly after [startColumn] —
 * which is also how a whole-line remark's `0 to 0` sentinel falls out of this, with no separate
 * check — when [endLine] comes before [startLine], or when either column falls outside its own
 * line. The last two are reachable only from a hand-edited workspace.xml: [selectedColumns] in
 * `action/AddRemarkAction.kt` never produces them.
 *
 * For a range inside one line: the substring between the two columns. For a range across lines:
 * the tail of [startLine] from [startColumn], every whole line strictly between, and the head of
 * [endLine] up to [endColumn], joined with newlines — the same shape `withSelectionMarkers`
 * already assumes when it draws the two markers on separate quoted lines.
 */
fun phraseAt(
    lines: List<String>,
    startLine: Int,
    endLine: Int,
    startColumn: Int,
    endColumn: Int,
): String? {
    if (endColumn <= startColumn) return null
    if (endLine < startLine) return null
    if (startLine !in lines.indices || endLine !in lines.indices) return null

    val startText = lines[startLine]
    val endText = lines[endLine]
    if (startColumn < 0 || startColumn > startText.length) return null
    if (endColumn < 0 || endColumn > endText.length) return null

    if (startLine == endLine) return startText.substring(startColumn, endColumn)

    val head = startText.substring(startColumn)
    val middle = lines.subList(startLine + 1, endLine)
    val tail = endText.substring(0, endColumn)
    return (listOf(head) + middle + listOf(tail)).joinToString("\n")
}

/** Line numbers to try, nearest to [origin] first, restricted to [range]. */
private fun candidatesNear(origin: Int, range: IntRange, radius: Int): Sequence<Int> = sequence {
    if (origin in range) yield(origin)
    for (delta in 1..radius) {
        val up = origin - delta
        if (up in range) yield(up)
        val down = origin + delta
        if (down in range) yield(down)
    }
}

/**
 * Whether the anchor's remembered context still surrounds a block of the stored length that
 * begins at [start]: `contextBefore` ends at [start], `contextAfter` begins at
 * `start + span + 1`.
 *
 * The block is pinned to the length it had when the anchor was captured. A block that gained
 * or lost a line is NOT found by this pass, and the remark orphans instead. That is on
 * purpose. To find a block of a new length, this pass would have to look for `contextAfter`
 * at several offsets — and context lines are compared trimmed, so an ordinary trailing
 * context like `}` / blank / `@Test` also occurs a few lines below, at the next method. The
 * search then lands on that second occurrence and reports a range covering unrelated code.
 * An orphan is visible and correctable; a silently wrong range is not.
 *
 * At least one matched context line must be non-blank, otherwise a run of empty lines would
 * match everywhere in the file.
 */
private fun contextMatchesAt(anchor: Anchor, lines: List<String>, start: Int, span: Int): Boolean {
    var matchedSomethingReal = false

    val before = anchor.contextBefore
    for (i in before.indices) {
        val at = start - before.size + i
        if (at < 0) return false
        if (lines[at].trim() != before[i].trim()) return false
        if (before[i].isNotBlank()) matchedSomethingReal = true
    }

    val after = anchor.contextAfter
    for (i in after.indices) {
        val at = start + span + 1 + i
        if (at > lines.lastIndex) return false
        if (lines[at].trim() != after[i].trim()) return false
        if (after[i].isNotBlank()) matchedSomethingReal = true
    }

    return matchedSomethingReal
}
