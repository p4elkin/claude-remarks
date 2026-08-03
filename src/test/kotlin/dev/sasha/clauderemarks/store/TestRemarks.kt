package dev.sasha.clauderemarks.store

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.util.ui.UIUtil
import dev.sasha.clauderemarks.model.RemarkSeverity
import dev.sasha.clauderemarks.model.RemarkState
import dev.sasha.clauderemarks.model.RemarkStatus
import dev.sasha.clauderemarks.model.RemarkTag
import java.io.File
import java.nio.file.Files
import java.nio.file.Path

/*
 * The shared test helpers. Everything here was copied between test classes before it moved in:
 * [settleInvocationQueue] was four identical private functions, [fileUnderProjectRoot] two, and
 * [TempPaths] replaces the temp directory each review test was leaving behind.
 */

/**
 * One builder for every store test, so each test only spells out the fields it cares about.
 * The defaults are a valid, boring remark: everything set, nothing interesting.
 */
internal fun remark(
    id: String = "r-1",
    path: String? = "src/Foo.kt",
    startLine: Int = 0,
    endLine: Int = 0,
    startColumn: Int = 0,
    endColumn: Int = 0,
    text: String = "note",
    tag: RemarkTag? = null,
    status: RemarkStatus = RemarkStatus.PENDING,
    createdAt: Long = 0L,
    textHash: String = "0000000000000000",
    contextBefore: String? = "",
    contextAfter: String? = "",
    severity: RemarkSeverity = RemarkSeverity.SHOULD,
    bucket: String? = null,
    commit: String? = null,
    phrase: String? = null,
) = RemarkState().also {
    it.id = id
    it.path = path
    it.startLine = startLine
    it.endLine = endLine
    it.startColumn = startColumn
    it.endColumn = endColumn
    it.text = text
    it.tag = tag
    it.status = status
    it.createdAt = createdAt
    it.textHash = textHash
    it.contextBefore = contextBefore
    it.contextAfter = contextAfter
    it.severity = severity
    it.bucket = bucket
    it.commit = commit
    it.phrase = phrase
}

/**
 * Drains the EDT's invocation queue, for anything that hops off the EDT and back — a read action
 * finishing on the UI thread, a service's own invokeLater. Ten short rounds rather than one, because
 * the runnable being waited for is often queued by a pooled thread that has not run yet. If a test
 * proves flaky, raise the repeat count — do not drop the assertion.
 */
internal fun settleInvocationQueue() {
    repeat(10) {
        UIUtil.dispatchAllInvocationEvents()
        Thread.sleep(10)
    }
    UIUtil.dispatchAllInvocationEvents()
}

/**
 * A real file on disk under the light fixture's project directory, which is what a test needs when
 * the code under test resolves paths against the project root.
 *
 * The light fixture project is shared by every test class in the JVM, and several of them write to
 * this same directory. refreshAndFindFileByIoFile does NOT re-read a file VFS already knows about,
 * so the second refresh is what makes an assertion on the content honest.
 */
internal fun fileUnderProjectRoot(project: Project, name: String, text: String): VirtualFile {
    val onDisk = File(project.basePath!!, name)
    onDisk.parentFile.mkdirs()
    onDisk.writeText(text)
    return LocalFileSystem.getInstance().refreshAndFindFileByIoFile(onDisk)!!
        .also { it.refresh(false, false) }
}

/**
 * Temp paths a test asks for and gets rid of again: one instance per test class, [deleteAll] in
 * tearDown. The review tests were each leaving a temp directory behind per test method, while
 * AtomicWriteTest in the same suite asserts this project leaves no temp file behind at all.
 */
internal class TempPaths {

    private val paths = mutableListOf<Path>()

    // paths.add, not paths += it: a Path is itself an Iterable<Path>, so the += would be ambiguous
    // between adding one element and adding all of its name elements.
    fun dir(prefix: String): Path = Files.createTempDirectory(prefix).also { paths.add(it) }

    fun file(prefix: String, suffix: String): Path =
        Files.createTempFile(prefix, suffix).also { paths.add(it) }

    fun deleteAll() {
        paths.forEach { it.toFile().deleteRecursively() }
        paths.clear()
    }
}
