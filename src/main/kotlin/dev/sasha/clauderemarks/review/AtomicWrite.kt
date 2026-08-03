package dev.sasha.clauderemarks.review

import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * Creates the temp file and returns it. In the target's own directory, so the rename in
 * [atomicWriteString] is atomic: a temp file in the system temp directory is usually on a
 * different filesystem from the target's, and Files.move with ATOMIC_MOVE across filesystems
 * throws AtomicMoveNotSupportedException.
 */
internal fun tempFileFor(target: Path): Path =
    Files.createTempFile(target.parent, ".claude-remarks-", ".tmp")

/**
 * Writes the whole content to a temp file beside [target], then renames that file onto [target].
 * A same-filesystem rename is atomic on POSIX, so a reader watching [target] sees either the old
 * complete content or the new complete content, never a partial file. That is why a reader can
 * simply wait for the path to exist.
 */
fun atomicWriteString(target: Path, text: String) {
    Files.createDirectories(target.parent) // createTempFile throws if the parent is absent
    val temp = tempFileFor(target)
    try {
        Files.writeString(temp, text, StandardCharsets.UTF_8)
        Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    } catch (e: IOException) {
        Files.deleteIfExists(temp) // a failed write leaves nothing behind
        throw e
    }
}
