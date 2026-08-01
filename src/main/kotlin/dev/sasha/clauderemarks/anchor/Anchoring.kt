package dev.sasha.clauderemarks.anchor

import java.security.MessageDigest

/** Number of lines kept above and below the anchored range. */
const val CONTEXT_LINES = 3

/** How far from the stored position resolveAnchor looks before giving up. */
const val SEARCH_RADIUS = 200

/**
 * How many lines the marked block may have grown or shrunk by and still be recognised from
 * the context around it. Kept much smaller than [SEARCH_RADIUS] because the context pass pays
 * for it twice: every candidate start position is retried against every candidate length.
 */
const val BLOCK_DRIFT = 20

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
    // subList below throw.
    if (span < 0) return orphaned

    // Negative for a file with fewer lines than the stored range. Every range built from it
    // below is then empty, so nothing is scanned and the answer falls through to orphaned.
    val starts = 0..(lines.size - 1 - span)

    fun blockHashAt(start: Int) = hashLines(lines.subList(start, start + span + 1))

    if (anchor.startLine in starts && blockHashAt(anchor.startLine) == anchor.textHash) {
        return AnchorResult.Exact(anchor.startLine, anchor.endLine)
    }

    // First pass: the text is unchanged but sits somewhere else.
    candidatesNear(anchor.startLine, starts, radius).forEach { start ->
        if (blockHashAt(start) == anchor.textHash) {
            return AnchorResult.Relocated(start, start + span)
        }
    }

    // Second pass: the text itself was edited, but what surrounds it did not move.
    candidatesNear(anchor.startLine, starts, radius).forEach { start ->
        val end = contextMatchAt(anchor, lines, start, span)
        if (end != null) return AnchorResult.Relocated(start, end)
    }

    return orphaned
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
 * The end line of the block when the anchor's remembered context still surrounds [start],
 * or null when it does not.
 *
 * The block may be longer or shorter than it was when the anchor was captured — a line added
 * or removed inside the marked block is the main case this pass exists for — so the trailing
 * context is looked for at the stored length first and then outwards, up to [BLOCK_DRIFT]
 * lines either way, and the end line comes from wherever it is found.
 *
 * At least one matched context line must be non-blank, otherwise a run of empty lines would
 * match everywhere in the file.
 */
private fun contextMatchAt(anchor: Anchor, lines: List<String>, start: Int, span: Int): Int? {
    var matchedSomethingReal = false

    val before = anchor.contextBefore
    for (i in before.indices) {
        val at = start - before.size + i
        if (at < 0) return null
        if (lines[at].trim() != before[i].trim()) return null
        if (before[i].isNotBlank()) matchedSomethingReal = true
    }

    val after = anchor.contextAfter
    // Nothing was remembered below the block, so its length cannot be derived again. The
    // stored length is the only guess left.
    if (after.isEmpty()) return if (matchedSomethingReal) start + span else null
    if (!matchedSomethingReal && after.none { it.isNotBlank() }) return null

    val ends = start..(lines.lastIndex - after.size)
    return candidatesNear(start + span, ends, BLOCK_DRIFT).firstOrNull { end ->
        after.indices.all { lines[end + 1 + it].trim() == after[it].trim() }
    }
}
