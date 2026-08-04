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
 * Asks [hasSubLineRange] whether there is a sub-line range here at all, the one place that rule
 * lives, so a stored phrase and the `⟦`/`⟧` markers `markersValid` in `render/PromptRenderer.kt`
 * draws over it never disagree about what was selected. Note what that rule does NOT do across
 * lines: it does not order the two columns against each other, because they are offsets into two
 * different lines. A selection running from column 25 of one line to column 8 of another is real
 * and gets a phrase.
 *
 * Null, then, when there is no sub-line range — which is also how a whole-line remark's `0 to 0`
 * sentinel falls out of this, with no separate check — when [endLine] comes before [startLine], or
 * when either column falls past the end of its own line. The last two are reachable only from a
 * hand-edited workspace.xml: [selectedColumns] in `action/AddRemarkAction.kt` never produces them.
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
    if (!hasSubLineRange(startLine, endLine, startColumn, endColumn)) return null
    if (endLine < startLine) return null
    if (startLine !in lines.indices || endLine !in lines.indices) return null

    // Each column is checked against its own line's length, never against the other column: the
    // negative half of that check is already hasSubLineRange's.
    val startText = lines[startLine]
    val endText = lines[endLine]
    if (startColumn > startText.length) return null
    if (endColumn > endText.length) return null

    if (startLine == endLine) return startText.substring(startColumn, endColumn)

    val head = startText.substring(startColumn)
    val middle = lines.subList(startLine + 1, endLine)
    val tail = endText.substring(0, endColumn)
    return (listOf(head) + middle + listOf(tail)).joinToString("\n")
}

/** Where a phrase sits in a file now: the lines it spans, and the column at either end. */
data class PhraseMatch(
    val startLine: Int,
    val endLine: Int,
    val startColumn: Int,
    val endColumn: Int,
)

/**
 * A line resolve, plus the columns the remark's phrase sits at now.
 *
 * The columns are the stored ones whenever nothing better was found, so a caller can always read
 * them without asking whether the search ran.
 */
data class ResolvedAnchor(
    val result: AnchorResult,
    val startColumn: Int,
    val endColumn: Int,
)

/**
 * Looks for [phrase] in [lines], trying the line nearest [origin] first and giving up after
 * [radius] lines either side.
 *
 * The reverse of [phraseAt], and it has to read a stored phrase back the same way that function
 * wrote it. A phrase holding no newline is looked for anywhere inside one line, with `indexOf`, and
 * the first occurrence in that line wins. A phrase holding newlines was written as the tail of its
 * first line, whole lines in the middle, and the head of its last line, so it is matched that way:
 * `endsWith` on the first, equality in the middle, `startsWith` on the last.
 *
 * Nearest-first rather than top-down for the same reason [resolveAnchor] searches that way: a file
 * usually holds the same short phrase several times, and the one the remark meant is the one closest
 * to where it used to be.
 *
 * An empty [phrase] matches at column 0 of the first line tried, because `indexOf("")` is 0 and
 * nothing here special-cases it. That is not a case production reaches: `RemarkState.phrase` goes
 * through `BaseState.string()`, whose setter turns "" into null, so the one caller that holds a
 * stored phrase either has real text or has null.
 */
fun findPhrase(
    lines: List<String>,
    phrase: String,
    origin: Int,
    radius: Int = SEARCH_RADIUS,
): PhraseMatch? {
    val parts = phrase.split("\n")
    // Negative for a file with fewer lines than the phrase, which makes the range empty and the
    // search find nothing, the same way resolveAnchor's own starts range does.
    val starts = 0..(lines.size - parts.size)

    for (start in candidatesNear(origin, starts, radius)) {
        phraseAtLine(lines, parts, start)?.let { return it }
    }
    return null
}

/** The phrase, split on newlines, matched against [lines] beginning on line [start], or null. */
private fun phraseAtLine(lines: List<String>, parts: List<String>, start: Int): PhraseMatch? {
    if (parts.size == 1) {
        val column = lines[start].indexOf(parts[0])
        return if (column < 0) null
        else PhraseMatch(start, start, column, column + parts[0].length)
    }

    val end = start + parts.lastIndex
    if (!lines[start].endsWith(parts.first())) return null
    for (i in 1 until parts.lastIndex) {
        if (lines[start + i] != parts[i]) return null
    }
    if (!lines[end].startsWith(parts.last())) return null

    return PhraseMatch(start, end, lines[start].length - parts.first().length, parts.last().length)
}

/**
 * The line resolve, and then the phrase inside it. Composes [resolveAnchor] and [findPhrase] and
 * changes neither.
 *
 * Four answers, one per case:
 *
 * - **No stored phrase.** Whatever [resolveAnchor] said, with the stored columns. This is every
 *   remark written before the phrase was stored at all, and every whole-line remark ever, so it has
 *   to be identical to the line-only resolve rather than merely equivalent to it.
 * - **A phrase, and the lines were found.** The stored columns are checked first, with [phraseAt]:
 *   when the phrase is still exactly there, they are kept and nothing is searched for. That is what
 *   keeps the occurrence the person selected. A line often holds the same short phrase twice —
 *   `total = total + delta` — and [findPhrase] answers with the first one, so searching a line that
 *   did not change at all would quietly move the remark onto the wrong words, with no orphan flag
 *   and nothing to see in the tree. Only when the stored columns no longer hold the phrase is it
 *   looked for on the resolved start line, with a radius of 0. A sub-line remark's phrase spans
 *   exactly its stored lines, so a match starting anywhere else could not be described by one column
 *   pair anyway.
 * - **A phrase, the lines were found, and the phrase is not in them.** The same result with the
 *   stored columns. The line is right and the words inside it changed, so a whole-line quote is the
 *   honest fallback: `markersValid` in `render/PromptRenderer.kt` then decides on its own whether
 *   the stale columns are still in bounds, and drops the markers when they are not.
 * - **A phrase, and the lines orphaned.** The phrase is searched for near the stored line, nearest
 *   first, inside the same [radius] the line search uses. Found, the remark is Relocated onto it.
 *   This is the reflowed paragraph: the line the phrase used to be on no longer exists, so no hash
 *   and no context can find it, but the words themselves are still in the file. Not found, the
 *   remark stays orphaned exactly as before.
 */
fun resolveWithPhrase(
    anchor: Anchor,
    lines: List<String>,
    phrase: String?,
    startColumn: Int,
    endColumn: Int,
    radius: Int = SEARCH_RADIUS,
): ResolvedAnchor {
    val lineResult = resolveAnchor(anchor, lines, radius)
    val stored = ResolvedAnchor(lineResult, startColumn, endColumn)
    if (phrase == null) return stored

    if (lineResult is AnchorResult.Orphaned) {
        val moved = findPhrase(lines, phrase, anchor.startLine, radius) ?: return stored
        return ResolvedAnchor(
            AnchorResult.Relocated(moved.startLine, moved.endLine),
            moved.startColumn,
            moved.endColumn,
        )
    }

    // The stored columns win whenever they still hold the phrase. Read across the resolved lines,
    // not the stored ones: the block may have moved, and the columns are offsets inside whichever
    // lines it moved to. Only when this fails is the phrase looked for, which is what keeps a remark
    // written on the second occurrence of a repeated phrase from jumping to the first.
    val atStoredColumns =
        phraseAt(lines, lineResult.startLine, lineResult.endLine, startColumn, endColumn)
    if (atStoredColumns == phrase) return stored

    val here = findPhrase(lines, phrase, lineResult.startLine, radius = 0) ?: return stored
    return ResolvedAnchor(lineResult, here.startColumn, here.endColumn)
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
