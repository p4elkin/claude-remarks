package dev.sasha.clauderemarks.review

import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * The published file: what Publish All Pending and Publish Selected write to disk, under
 * handshakeDir(), so a Claude Code skill can read published remarks whenever it likes, with no
 * review ever having been started. See docs/claude/design.md, "The published file".
 */

/** The first line, so a reader can tell this file from a handoff file or a rejection before it hands
 *  the body to a model. A wire format shared with SKILL.md: not reworded without editing it too. */
const val PUBLISHED_MARKER = "<!-- claude-remarks: published -->"

private val PUBLISHED_WHEN =
    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault())

/**
 * The same 16 hex characters handshakeName uses, with .md instead of .json, so a skill running in
 * the repository computes this name with the shasum one-liner it already runs for the handshake.
 */
fun publishedName(realPath: String): String = projectHash(realPath) + ".md"

/**
 * Every character below U+0020 becomes a space, then the result is cut to 120 characters. The
 * label arrives over HTTP from the skill, and one newline in it would split the header and move
 * every line after it. 120 is the same cut ui/RemarksToolWindowFactory.kt's updateBanner already
 * makes on the same label.
 */
private fun sanitizeLabel(label: String): String =
    buildString(label.length) {
        for (c in label) append(if (c < ' ') ' ' else c)
    }.take(120)

/**
 * What sits above the prompt in the published file: what batch it is (the nonce, for an
 * acknowledgement to name back), how old it is and what revision it was about, and, since phase
 * 10, whether it answers a waiting review and whether that review was a rejection. A published
 * file can be read hours later, or twice, and nothing confirms a read on this path except the
 * batch's own nonce, so the reader has to be able to see all of this without guessing. The same
 * defect shape is already recorded in docs/claude/design.md under Known Issues for a same-session
 * review retry.
 *
 * The eight lines are always written in this fixed order, one field always present, "none" when
 * there is nothing to say — never an empty field. Section 4 of the phase 10 spec says why fixed
 * lines matter: the skill reads them by line number, and grepping for them is unsafe because a
 * remark's own text can start a line with, say, "commit:".
 */
data class PublishedHeader(
    val nonce: String,
    val publishedAt: Long,
    val commit: String?,
    val remarks: Int,
    val reviewSession: String?,
    val reviewLabel: String?,
    val rejected: Boolean,
) {
    /** The eight lines, in order, ending with a newline. The caller adds the blank line. */
    fun render(): String = buildString {
        appendLine(PUBLISHED_MARKER)
        appendLine("nonce: $nonce")
        appendLine("published: ${PUBLISHED_WHEN.format(Instant.ofEpochMilli(publishedAt))}")
        appendLine("commit: ${commit?.take(8) ?: "none"}")
        appendLine("remarks: $remarks")
        appendLine("review: ${reviewSession ?: "none"}")
        appendLine("label: ${reviewLabel?.let { sanitizeLabel(it) } ?: "none"}")
        appendLine("rejected: ${if (rejected) "yes" else "no"}")
    }
}

private fun fieldOrNull(line: String, prefix: String): String? =
    if (line.startsWith(prefix)) line.removePrefix(prefix) else null

/**
 * The eight fields back, or null when the first line is not the marker or a line is malformed. A
 * missing prefix on any of lines 2 to 8, or a `remarks:` value that is not an integer, or a
 * `rejected:` value that is neither `yes` nor `no`, returns null. A lie is not a better answer
 * than an error: the fetch turns this null into `failed` with a detail rather than guessing.
 */
fun publishedHeaderOf(text: String): PublishedHeader? {
    val lines = text.split("\n")
    if (lines.size < 8 || lines[0] != PUBLISHED_MARKER) return null

    val nonce = fieldOrNull(lines[1], "nonce: ") ?: return null
    val publishedRaw = fieldOrNull(lines[2], "published: ") ?: return null
    val commitRaw = fieldOrNull(lines[3], "commit: ") ?: return null
    val remarksRaw = fieldOrNull(lines[4], "remarks: ") ?: return null
    val reviewRaw = fieldOrNull(lines[5], "review: ") ?: return null
    val labelRaw = fieldOrNull(lines[6], "label: ") ?: return null
    val rejectedRaw = fieldOrNull(lines[7], "rejected: ") ?: return null

    val publishedAt = try {
        PUBLISHED_WHEN.parse(publishedRaw, Instant::from).toEpochMilli()
    } catch (e: java.time.format.DateTimeParseException) {
        return null
    }
    val remarks = remarksRaw.toIntOrNull() ?: return null
    val rejected = when (rejectedRaw) {
        "yes" -> true
        "no" -> false
        else -> return null
    }

    return PublishedHeader(
        nonce = nonce,
        publishedAt = publishedAt,
        commit = commitRaw.takeUnless { it == "none" },
        remarks = remarks,
        reviewSession = reviewRaw.takeUnless { it == "none" },
        reviewLabel = labelRaw.takeUnless { it == "none" },
        rejected = rejected,
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
