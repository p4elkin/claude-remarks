package dev.sasha.clauderemarks.store

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import dev.sasha.clauderemarks.anchor.Anchor
import dev.sasha.clauderemarks.anchor.AnchorResult
import dev.sasha.clauderemarks.anchor.ResolvedAnchor
import dev.sasha.clauderemarks.anchor.resolveWithPhrase
import dev.sasha.clauderemarks.model.AnswerState
import dev.sasha.clauderemarks.model.RemarkState

private val LOG = Logger.getInstance("dev.sasha.clauderemarks.store.RemarkResolver")

/**
 * One remark, resolved against the file it points at.
 *
 * [startColumn] and [endColumn] are where the remark's phrase sits *now*, which is not always where
 * it was stored: a sub-line remark whose line was reindented keeps its phrase and moves its columns.
 * Both default to 0 so that a caller building a row by hand — every test that does, and any future
 * one — keeps compiling and gets the same "no sub-line range" the stored fields use.
 */
data class ResolvedRemark(
    val remark: RemarkState,
    val result: AnchorResult,
    val startColumn: Int = 0,
    val endColumn: Int = 0,
)

/**
 * One answer, resolved against the file it points at, the same shape [ResolvedRemark] has.
 *
 * An answer carries its own anchor rather than being resolved through the remark it answers, so it
 * still finds its code after the remark has been cleared. The two records are resolved by the same
 * [resolveStored], so an answer follows the code exactly the way a remark does.
 */
data class ResolvedAnswer(
    val answer: AnswerState,
    val result: AnchorResult,
    val startColumn: Int = 0,
    val endColumn: Int = 0,
)

/**
 * The project directory, used as the base for every stored remark path.
 *
 * Not ProjectUtil.guessProjectDir, which is what the deprecation note on Project.getBaseDir
 * points at: in 2025.2 that class is Kotlin-internal, so it resolves from Java but NOT from
 * Kotlin, and the Kotlin compiler reports "Unresolved reference 'ProjectUtil'" even though
 * the jar holding it is on the compile classpath. Verified by compiling against the 2025.2
 * jars. basePath is what remains, and it is exactly the directory holding .idea, which is
 * what "project-relative" should mean here.
 */
fun projectRoot(project: Project): VirtualFile? =
    project.basePath?.let { LocalFileSystem.getInstance().findFileByPath(it) }

/**
 * Turns every stored remark into a row for the tool window.
 *
 * Must be called inside a read action, off the EDT. A remark is never dropped: when anything
 * cannot be resolved the row still carries the stored line numbers, marked as orphaned.
 */
fun resolveAll(project: Project): List<ResolvedRemark> =
    resolveAll(projectRoot(project), RemarkStore.getInstance(project).all())

/**
 * The part of [resolveAll] that does not need a project, so the two ways it refuses to trust
 * what is stored can be tested: a null [root] and a path that climbs out of the project.
 */
fun resolveAll(root: VirtualFile?, remarks: List<RemarkState>): List<ResolvedRemark> =
    remarks.map { remark ->
        // One cancellation point per remark, so a pending write does not wait for the whole
        // sweep: each remark can cost a SHA-256 over every candidate position in the radius.
        ProgressManager.checkCanceled()
        if (root == null) stale(remark, "the project root did not resolve")
        else resolveOne(root, remark)
    }

/**
 * Turns every stored answer into a row for the tool window and the gutter, the same way
 * [resolveAll] does for remarks.
 *
 * Must be called inside a read action, off the EDT, for the same reason. An answer is never
 * dropped either: an answer whose code is gone comes back orphaned at its stored line numbers.
 */
fun resolveAnswers(project: Project): List<ResolvedAnswer> =
    resolveAnswers(projectRoot(project), RemarkStore.getInstance(project).allAnswers())

/** The part of [resolveAnswers] that does not need a project, so it can be tested the same way. */
fun resolveAnswers(root: VirtualFile?, answers: List<AnswerState>): List<ResolvedAnswer> =
    answers.map { answer ->
        // One cancellation point per answer, the same reason resolveAll has one per remark.
        ProgressManager.checkCanceled()
        val stored = storedAnchorOf(answer)
        val resolved =
            if (root == null) refuse(stored, labelOf(answer), "the project root did not resolve")
            else resolveStored(root, stored, labelOf(answer))
        ResolvedAnswer(answer, resolved.result, resolved.startColumn, resolved.endColumn)
    }

/**
 * A row that could not be resolved: orphaned at its stored line numbers, and carrying the columns
 * it was stored with. Nothing was read, so there is nothing better to say about either.
 */
private fun stale(remark: RemarkState, why: String): ResolvedRemark {
    val refused = refuse(storedAnchorOf(remark), labelOf(remark), why)
    return ResolvedRemark(remark, refused.result, refused.startColumn, refused.endColumn)
}

/**
 * Marks a stored anchor stale, keeping its stored line numbers and columns, and says in the log why.
 *
 * Five different refusals all end as the same orphaned row, so the reason exists nowhere else.
 * Debug level: nothing is printed until someone turns the category on. [label] is what names the
 * record in that log line, because a [StoredAnchor] is a position and carries no id of its own.
 */
private fun refuse(stored: StoredAnchor, label: String, why: String): ResolvedAnchor {
    LOG.debug("$label (${stored.path}): $why")
    return ResolvedAnchor(
        AnchorResult.Orphaned(stored.startLine, stored.endLine),
        stored.startColumn,
        stored.endColumn,
    )
}

private fun labelOf(remark: RemarkState) = "remark ${remark.id}"

private fun labelOf(answer: AnswerState) = "answer ${answer.id}"

/**
 * The file a stored path points at, or null when there is no such file or the path leaves the
 * project.
 *
 * Every reader of a stored path comes through here, and that is the point. findRelativeFile takes
 * the root FIRST, then each path segment as its own vararg: findRelativeFile(VirtualFile, String...).
 * Passing "a/b/Foo.kt" as a single element finds nothing, and passing (path, root) does not compile.
 * It also walks ".." through getParent(), so a hand-edited or committed workspace.xml holding
 * `path="../../../../etc/passwd"` would otherwise reach any file on the machine. The isAncestor
 * check lived in three separate places and one of them — the tool window's double-click navigation —
 * did not have it.
 */
fun fileForStoredPath(root: VirtualFile, path: String): VirtualFile? =
    VfsUtil.findRelativeFile(root, *path.split('/').toTypedArray())
        ?.takeIf { VfsUtilCore.isAncestor(root, it, false) }

/**
 * A remark that names no file: never had a path, or was stored with an empty one. The same
 * convention `render/PromptRenderer.kt`'s `RenderedRemark.path` already partitions on — an empty
 * string means "about no file" — so this reads `RemarkState.path` the same way rather than
 * inventing a second convention. Every reader that needs to tell a general remark apart asks this,
 * instead of a fourth `AnchorResult` case that would say the same thing again in the sealed
 * interface.
 */
fun isAboutNoFile(remark: RemarkState): Boolean = namesNoFile(remark.path)

/**
 * The same question for an answer. A general remark's answer is stored with an empty path, so it has
 * no position, no gutter icon and no file group — and it reaches all of that through this one rule
 * rather than through a second convention of its own.
 */
fun isAboutNoFile(answer: AnswerState): Boolean = namesNoFile(answer.path)

/** The one place the "about no file" rule lives, so a remark and an answer can never disagree. */
private fun namesNoFile(path: String?): Boolean = path.isNullOrEmpty()

/**
 * The stored position of a remark or an answer, lifted out of whichever record holds it.
 *
 * A plain value type with no platform import, so the shared resolve below reads both records the
 * same way. [AnswerState] declares its own copy of these nine fields rather than sharing a
 * superclass with [RemarkState] — its KDoc says why — and this is what shares the *logic* the two
 * would otherwise have to copy: the file lookup with its isAncestor check, the Document lookup, the
 * general case and the refusals.
 */
data class StoredAnchor(
    val path: String?,
    val startLine: Int,
    val endLine: Int,
    val startColumn: Int,
    val endColumn: Int,
    val textHash: String?,
    val contextBefore: String?,
    val contextAfter: String?,
    val phrase: String?,
)

/** The stored fields of a remark, read back as the position they were captured at. */
fun storedAnchorOf(remark: RemarkState) = StoredAnchor(
    path = remark.path,
    startLine = remark.startLine,
    endLine = remark.endLine,
    startColumn = remark.startColumn,
    endColumn = remark.endColumn,
    textHash = remark.textHash,
    contextBefore = remark.contextBefore,
    contextAfter = remark.contextAfter,
    phrase = remark.phrase,
)

/** The same for an answer, whose nine anchor fields carry the same meanings. */
fun storedAnchorOf(answer: AnswerState) = StoredAnchor(
    path = answer.path,
    startLine = answer.startLine,
    endLine = answer.endLine,
    startColumn = answer.startColumn,
    endColumn = answer.endColumn,
    textHash = answer.textHash,
    contextBefore = answer.contextBefore,
    contextAfter = answer.contextAfter,
    phrase = answer.phrase,
)

/**
 * Resolves one stored position against the file it names, whether it came from a remark or from an
 * answer. [label] only names the record in the debug log.
 *
 * A record that names no file is not a record whose file disappeared: it resolves as itself, at
 * Exact(0, 0), the same "no sub-line range" pair a whole-line remark already carries. A record
 * with a REAL path pointing at a file that genuinely is not there still falls through to the
 * refusal below — this check must never widen to cover that case.
 */
fun resolveStored(root: VirtualFile, stored: StoredAnchor, label: String): ResolvedAnchor {
    if (namesNoFile(stored.path)) {
        return ResolvedAnchor(AnchorResult.Exact(0, 0), stored.startColumn, stored.endColumn)
    }

    val file = fileForStoredPath(root, stored.path!!)
        ?: return refuse(stored, label, "no file under the project root at that path")

    val document = FileDocumentManager.getInstance().getDocument(file)
        ?: return refuse(stored, label, "the file has no Document (binary, or too large)")

    // resolveWithPhrase, not resolveAnchor: a record with no stored phrase — every whole-line
    // remark, and everything written before the phrase was stored — comes back from it unchanged.
    return resolveWithPhrase(
        anchorOf(stored),
        document.text.split("\n"),
        stored.phrase,
        stored.startColumn,
        stored.endColumn,
    )
}

private fun resolveOne(root: VirtualFile, remark: RemarkState): ResolvedRemark {
    val resolved = resolveStored(root, storedAnchorOf(remark), labelOf(remark))
    return ResolvedRemark(remark, resolved.result, resolved.startColumn, resolved.endColumn)
}

/** The stored fields of a remark, read back as the anchor they were captured from. Context is
 *  decoded with splitContext, from ContextFormat.kt. */
fun anchorOf(remark: RemarkState) = anchorOf(storedAnchorOf(remark))

/** The same, for a position already lifted out of whichever record held it. */
fun anchorOf(stored: StoredAnchor) = Anchor(
    startLine = stored.startLine,
    endLine = stored.endLine,
    textHash = stored.textHash.orEmpty(),
    contextBefore = splitContext(stored.contextBefore),
    contextAfter = splitContext(stored.contextAfter),
)
