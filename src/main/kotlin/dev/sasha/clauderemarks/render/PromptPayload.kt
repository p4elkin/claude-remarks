package dev.sasha.clauderemarks.render

import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VfsUtilCore
import dev.sasha.clauderemarks.anchor.AnchorResult
import dev.sasha.clauderemarks.model.label
import dev.sasha.clauderemarks.store.ResolvedRemark
import dev.sasha.clauderemarks.store.projectRoot
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
    val file = tempDir.resolve("claude-remarks-${System.currentTimeMillis()}.md")
    Files.writeString(file, markdown, StandardCharsets.UTF_8)
    // It holds remark text and slices of source, and on Linux /tmp is world-readable, so it does
    // not outlive the IDE. The paste has already happened by then.
    file.toFile().deleteOnExit()
    return Clipboard(file.toAbsolutePath().toString(), file)
}

/**
 * Reads the code behind each resolved remark. Must be called inside a read action, off the EDT.
 *
 * A remark is never dropped. When the file is gone or has no Document, the remark still comes back,
 * marked orphaned with no code, and the renderer says so.
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
        val file = VfsUtil.findRelativeFile(base, *path.split('/').toTypedArray()) ?: return null
        if (!VfsUtilCore.isAncestor(base, file, false)) return null
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
        val start = row.result.startLine
        val end = row.result.endLine
        val from = (start - contextLines).coerceAtLeast(0)
        val to = if (lines == null) from else (end + contextLines + 1).coerceAtMost(lines.size)

        RenderedRemark(
            path = path,
            startLine = start,
            endLine = end,
            tag = row.remark.tag?.label,
            text = row.remark.text.orEmpty(),
            orphaned = row.result is AnchorResult.Orphaned,
            codeStartLine = from,
            code = if (lines == null || from >= to) emptyList() else lines.subList(from, to),
        )
    }
}
