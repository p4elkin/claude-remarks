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

sealed interface AnchorResult {
    data class Exact(val startLine: Int, val endLine: Int) : AnchorResult
    data class Relocated(val startLine: Int, val endLine: Int) : AnchorResult
    data class Orphaned(val staleStartLine: Int, val staleEndLine: Int) : AnchorResult
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
        contextBefore = lines.subList(maxOf(0, start - contextLines), start),
        contextAfter = lines.subList(minOf(lines.size, end + 1), minOf(lines.size, end + 1 + contextLines)),
    )
}
