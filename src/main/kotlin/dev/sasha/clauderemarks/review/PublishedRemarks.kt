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
 * What sits above the prompt in the published file. A published file can be read hours later, or
 * twice, and nothing confirms a read on this path, so the reader has to be able to see how old it is
 * and what revision it was about. The same defect shape is already recorded in
 * docs/claude/design.md under Known Issues for a same-session review retry.
 *
 * The commit is cut to eight characters, the same way the prompt heading and the tree both cut it.
 * A missing commit says "none" rather than leaving the field empty, so a reader can tell "no git
 * repository" from a field that failed to render.
 */
fun publishedHeader(now: Long, commit: String?, count: Int): String = buildString {
    appendLine(PUBLISHED_MARKER)
    appendLine("published: ${PUBLISHED_WHEN.format(Instant.ofEpochMilli(now))}")
    appendLine("commit: ${commit?.take(8) ?: "none"}")
    appendLine("remarks: $count")
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
