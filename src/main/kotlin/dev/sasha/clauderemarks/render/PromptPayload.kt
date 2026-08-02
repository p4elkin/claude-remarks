package dev.sasha.clauderemarks.render

import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import dev.sasha.clauderemarks.anchor.AnchorResult
import dev.sasha.clauderemarks.model.label
import dev.sasha.clauderemarks.store.ResolvedRemark
import dev.sasha.clauderemarks.store.fileForStoredPath
import dev.sasha.clauderemarks.store.projectRoot
import dev.sasha.clauderemarks.store.splitContext
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

/** Lines of context kept either side of the anchored block in the copied prompt. */
const val PROMPT_CONTEXT_LINES = 3

/** Above this many UTF-8 bytes the payload goes to a file and the path is copied instead. */
const val INLINE_LIMIT_BYTES = 100 * 1024

/** What lands on the clipboard, and the file behind it when there is one. */
data class Clipboard(val text: String, val file: Path?)

/**
 * One code path with a size check, not two implementations.
 *
 * A very large clipboard transfer is slow, and some terminals truncate a paste that big. Writing
 * the document to the system temp directory and copying the path avoids both. The temp directory
 * is outside the project, so nothing remark-related can reach version control by this route.
 */
fun clipboardPayload(
    markdown: String,
    tempDir: Path = Path.of(System.getProperty("java.io.tmpdir")),
    limitBytes: Int = INLINE_LIMIT_BYTES,
): Clipboard {
    if (markdown.toByteArray(StandardCharsets.UTF_8).size < limitBytes) {
        return Clipboard(markdown, null)
    }
    Files.createDirectories(tempDir)
    // createTempFile, not resolve("claude-remarks-<millis>.md"): the temp directory is shared and
    // world-writable, so a predictable name can be pre-created as a symlink by anyone on the
    // machine and the write would then land on whatever it points at. createTempFile picks an
    // unpredictable name, refuses to follow an existing entry, and on POSIX creates the file
    // rw------- instead of leaving remark text and source slices readable by every local user.
    val file = Files.createTempFile(tempDir, "claude-remarks-", ".md")
    Files.writeString(file, markdown, StandardCharsets.UTF_8)
    // It holds remark text and slices of source, so it does not outlive the IDE. The paste has
    // already happened by then.
    file.toFile().deleteOnExit()
    return Clipboard(file.toAbsolutePath().toString(), file)
}

/**
 * Reads the code behind each resolved remark. Must be called inside a read action, off the EDT.
 *
 * A remark is never dropped. An orphaned remark still comes back, marked orphaned and with no code,
 * and the renderer says so.
 *
 * No code for an orphan is the whole point of the orphan label. Orphaned means the hash did not
 * match and the context did not match, so whatever now sits at the stored line numbers is not the
 * remark's code. Quoting it would be worse than quoting nothing: the prompt header tells the model
 * to find the code by reading the quoted lines rather than by trusting the numbers, so unrelated
 * code shipped under a ">" marker is an instruction to act on the wrong lines.
 *
 * What an orphan does carry out of here is the context stored with it: the lines that sat just
 * above and just below it when it was written. Nothing else is left to look for, and those lines
 * are already persisted, so the renderer quotes them under words of their own. Without them a
 * renamed file — which orphans every remark in it — would ship a whole file's remarks with a path,
 * line numbers the header says to ignore, and no way to find the code at all.
 *
 * Each file is read once, even with several remarks in it: a Document read is cheap, but
 * document.text.split on a large file is not, and a marked-up file usually holds several remarks.
 * A file that cannot be read counts as read too — see the note on the cache below.
 */
fun collectForPrompt(
    project: Project,
    rows: List<ResolvedRemark>,
    contextLines: Int = PROMPT_CONTEXT_LINES,
): List<RenderedRemark> {
    val root = projectRoot(project)
    val cache = mutableMapOf<String, List<String>?>()

    fun readLines(path: String): List<String>? {
        val base = root ?: return null
        // fileForStoredPath makes the isAncestor check, so a stored path full of ".." cannot pull
        // a file from outside the project into the copied prompt.
        val file = fileForStoredPath(base, path) ?: return null
        return FileDocumentManager.getInstance().getDocument(file)?.text?.split("\n")
    }

    // containsKey, not cache.getOrPut: getOrPut treats a stored null as absent, so a file that
    // cannot be read would be looked up again for every remark pointing at it — which is the one
    // case where the lookup is a full VFS miss.
    fun linesOf(path: String): List<String>? =
        if (cache.containsKey(path)) cache[path] else readLines(path).also { cache[path] = it }

    return rows.map { row ->
        val path = row.remark.path.orEmpty()
        val lines = linesOf(path)
        val orphaned = row.result is AnchorResult.Orphaned
        val start = row.result.startLine
        val end = row.result.endLine
        val from = (start - contextLines).coerceAtLeast(0)
        val to = if (lines == null) from else (end + contextLines + 1).coerceAtMost(lines.size)

        RenderedRemark(
            path = path,
            startLine = start,
            endLine = end,
            tag = row.remark.tag?.label,
            severity = row.remark.severity.label,
            text = row.remark.text.orEmpty(),
            orphaned = orphaned,
            codeStartLine = from,
            code = if (orphaned || lines == null || from >= to) emptyList()
            else lines.subList(from, to),
            // Only for an orphan. Every other remark quotes its real code, so shipping the
            // capture-time context as well would be the same lines twice in one prompt.
            capturedBefore = if (orphaned) splitContext(row.remark.contextBefore) else emptyList(),
            capturedAfter = if (orphaned) splitContext(row.remark.contextAfter) else emptyList(),
        )
    }
}
