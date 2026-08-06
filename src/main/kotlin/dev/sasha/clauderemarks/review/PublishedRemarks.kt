package dev.sasha.clauderemarks.review

import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * The published file: what Publish Unread and Publish Selected write to disk, under handshakeDir(),
 * so a Claude Code skill can read published remarks whenever it likes.
 * See docs/claude/design.md, "The published file".
 */

/** The first line, so a reader can tell this file from anything else in that directory before it
 *  hands the body to a model. There is one kind of batch now: every publish writes the same shape.
 *  A wire format shared with SKILL.md: not reworded without editing it too. */
const val PUBLISHED_MARKER = "<!-- claude-remarks: published -->"

private val PUBLISHED_WHEN =
    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault())

/**
 * The same 16 hex characters handshakeName uses, with .md instead of .json, so a skill running in
 * the repository computes this name with the shasum one-liner it already runs for the handshake.
 */
fun publishedName(realPath: String): String = projectHash(realPath) + ".md"

/**
 * What sits above the prompt in the published file: what batch it is (the nonce, for an
 * acknowledgement to name back), how old it is and what revision it was about. A published
 * file can be read hours later, or twice, and nothing confirms a read on this path except the
 * batch's own nonce, so the reader has to be able to see all of this without guessing.
 *
 * The five lines are always written in this fixed order, one field always present, "none" when
 * there is nothing to say — never an empty field. The skill reads them by line number, and
 * grepping for them is unsafe because a remark's own text can start a line with, say, "commit:".
 */
internal data class PublishedHeader(
    val nonce: String,
    val publishedAt: Long,
    val commit: String?,
    val remarks: Int,
) {
    /**
     * The five lines, in order, ending with a newline. The caller adds the blank line.
     *
     * Nothing here is sanitised, deliberately. [commit] used to be run through a control-character
     * replacement, and that turned the one failure this file cares about into a quiet wrong answer: a
     * newline inside a commit pushes `remarks:` off line 5, [publishedHeaderOf] then reads back null,
     * and `handleFetch` answers `failed` with a detail — this file's own stated policy that a lie is
     * not a better answer than an error. Replacing the newline instead produced a header that parsed
     * cleanly and reported a commit nobody has. Nothing reaching this class arrives over HTTP any
     * more; [commit] comes from reading `.git` directly, through `headCommit`, which only ever returns
     * a string that matched its 40-hex pattern.
     */
    fun render(): String = buildString {
        appendLine(PUBLISHED_MARKER)
        appendLine("nonce: $nonce")
        appendLine("published: ${PUBLISHED_WHEN.format(Instant.ofEpochMilli(publishedAt))}")
        appendLine("commit: ${commit?.take(8) ?: "none"}")
        appendLine("remarks: $remarks")
    }
}

private fun fieldOrNull(line: String, prefix: String): String? =
    if (line.startsWith(prefix)) line.removePrefix(prefix) else null

/**
 * The four fields back, or null when the first line is not the marker or a line is malformed. A
 * missing prefix on any of lines 2 to 5, or a `remarks:` value that is not an integer, returns
 * null. A lie is not a better answer than an error: the fetch turns this null into `failed` with
 * a detail rather than guessing.
 */
internal fun publishedHeaderOf(text: String): PublishedHeader? {
    val lines = text.split("\n")
    if (lines.size < 5 || lines[0] != PUBLISHED_MARKER) return null

    val nonce = fieldOrNull(lines[1], "nonce: ") ?: return null
    val publishedRaw = fieldOrNull(lines[2], "published: ") ?: return null
    val commitRaw = fieldOrNull(lines[3], "commit: ") ?: return null
    val remarksRaw = fieldOrNull(lines[4], "remarks: ") ?: return null

    val publishedAt = try {
        PUBLISHED_WHEN.parse(publishedRaw, Instant::from).toEpochMilli()
    } catch (e: java.time.format.DateTimeParseException) {
        return null
    }
    val remarks = remarksRaw.toIntOrNull() ?: return null

    return PublishedHeader(
        nonce = nonce,
        publishedAt = publishedAt,
        commit = commitRaw.takeUnless { it == "none" },
        remarks = remarks,
    )
}

/**
 * Writes [body] — the header plus the rendered prompt — to the published file for [root], inside
 * [dir]. Creates [dir] and sets it owner-only first, then writes through atomicWriteString, then
 * sets the written file owner-only. That order, not the reverse: atomicWriteString renames a temp
 * file onto the target, so a permission call made before the rename would apply to a file the
 * rename then replaces. writeHandshake argues the same order for the same reason.
 */
fun writePublished(root: Path, body: String, dir: Path = handshakeDir()): Path {
    Files.createDirectories(dir)
    setOwnerOnly(dir, "rwx------")
    val target = dir.resolve(publishedName(root.toString()))
    atomicWriteString(target, body)
    setOwnerOnly(target, "rw-------")
    return target
}
