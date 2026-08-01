package dev.sasha.clauderemarks.anchor

import java.security.MessageDigest

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
    return digest.digest().joinToString("") { "%02x".format(it) }.take(16)
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
    // subList below throw. An empty file needs no separate check: lastStart is negative then.
    if (span < 0) return orphaned

    val lastStart = lines.size - 1 - span
    if (lastStart < 0) return orphaned

    fun blockHashAt(start: Int) = hashLines(lines.subList(start, start + span + 1))

    if (anchor.startLine in 0..lastStart && blockHashAt(anchor.startLine) == anchor.textHash) {
        return AnchorResult.Exact(anchor.startLine, anchor.endLine)
    }

    // First pass: the text is unchanged but sits somewhere else.
    candidatesNear(anchor.startLine, lastStart, radius).forEach { start ->
        if (blockHashAt(start) == anchor.textHash) {
            return AnchorResult.Relocated(start, start + span)
        }
    }

    // Second pass: the text itself was edited, but what surrounds it did not move.
    candidatesNear(anchor.startLine, lastStart, radius).forEach { start ->
        if (contextMatchesAt(anchor, lines, start, span)) {
            return AnchorResult.Relocated(start, start + span)
        }
    }

    return orphaned
}

/** Start offsets to try, nearest to [origin] first, clamped to 0..[lastStart]. */
private fun candidatesNear(origin: Int, lastStart: Int, radius: Int): Sequence<Int> = sequence {
    if (origin in 0..lastStart) yield(origin)
    for (delta in 1..radius) {
        val up = origin - delta
        if (up in 0..lastStart) yield(up)
        val down = origin + delta
        if (down in 0..lastStart) yield(down)
    }
}

/**
 * True when the lines around [start] match the anchor's remembered context.
 * At least one matched context line must be non-blank, otherwise a run of empty
 * lines would match everywhere in the file.
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
