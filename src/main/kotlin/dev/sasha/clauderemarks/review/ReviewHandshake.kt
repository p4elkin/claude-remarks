package dev.sasha.clauderemarks.review

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import dev.sasha.clauderemarks.store.gitTopLevel
import java.io.IOException
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path
import java.nio.file.attribute.PosixFileAttributeView
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest
import java.util.UUID
import kotlin.io.path.fileAttributesViewOrNull
import org.jetbrains.ide.BuiltInServerManager

/**
 * One JSON file per open project, under [handshakeDir]. It tells a skill three things: which port
 * this IDE's built-in server listens on, the secret to send with a request, and — by existing at
 * all — that this IDE has this repository open.
 */

/**
 * What the plugin hashes, compares and calls "this project": the git top level when the project sits
 * inside a git repository, and the project base path when it does not. Real path in both cases, so
 * a symlinked checkout matches what `git rev-parse --show-toplevel` prints on the skill side.
 *
 * **Every place that names a file for a project, or matches a request against one, comes through
 * here.** The handshake file's name, the published file's name and the endpoint's project matching
 * all used to hash the base path on their own. A project opened on a module below the repository
 * root then hashed the module while the skill hashed the repository, and both sides went looking for
 * files the other never wrote — with no way back, because reaching the endpoint at all needs the
 * handshake file the skill could not find. One function is what keeps the three from disagreeing
 * again.
 *
 * Null when the base path is missing, or does not exist on disk, or is not a path at all. Every
 * caller already had a "no root" branch for that, because a project directory can be deleted while
 * the project is open.
 *
 * The two catches are named rather than `runCatching`: `runCatching` catches `Throwable`, and this
 * runs inside a read action where `ProcessCanceledException` has to unwind rather than be read as a
 * missing root.
 */
fun projectIdentity(basePath: String?): Path? {
    val base = basePath ?: return null
    val real = try {
        Path.of(base).toRealPath()
    } catch (e: IOException) {
        return null
    } catch (e: InvalidPathException) {
        return null
    }
    return gitTopLevel(real) ?: real
}

/** [projectIdentity] for an open project. `basePath` is exactly the directory holding `.idea`. */
fun projectIdentity(project: Project): Path? = projectIdentity(project.basePath)

/**
 * The first 16 hex characters of sha256(realPath). A skill can compute this with one line of shell
 * (`shasum -a 256`), which is why sha256 was chosen over anything cleverer. Shared by handshakeName
 * and, since phase 9, publishedName in PublishedRemarks.kt: both name a file for the same project,
 * and a skill on the other side computes the same 16 characters for either one.
 *
 * What goes in is always [projectIdentity]'s answer, never a base path read somewhere else.
 */
fun projectHash(realPath: String): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(realPath.toByteArray(Charsets.UTF_8))
    val hex = digest.joinToString("") { "%02x".format(it) }
    return hex.take(16)
}

/** The handshake file's name: [projectHash] plus ".json". */
fun handshakeName(realPath: String): String = projectHash(realPath) + ".json"

/**
 * Not the IDE configuration directory: the skill has to find this without knowing which JetBrains
 * product is running, and the configuration directory name carries the product and the version.
 */
fun handshakeDir(): Path = Path.of(System.getProperty("user.home"), ".claude-remarks")

/**
 * One random token per IDE run, held in memory only. Not persisted: a restart mints a new one, and
 * the handshake file is rewritten the next time a project opens, which happens anyway because the
 * skill reads the file every time.
 */
object ReviewToken {
    val value: String = UUID.randomUUID().toString()
}

/**
 * Everything JSON forbids inside a string literal, in one pass: the two structural characters and
 * every control character below U+0020. A newline is a legal character in a POSIX path and an
 * illegal one inside a JSON string, so a project directory called `we"ird\` or one holding a
 * newline both have to come out as escapes. `\uXXXX` covers all of the control characters with one
 * branch instead of a short-escape table for `\n`, `\t` and the rest.
 */
private fun escapeJson(value: String): String = buildString(value.length) {
    for (c in value) when {
        c == '\\' -> append("\\\\")
        c == '"' -> append("\\\"")
        c < ' ' -> append("\\u%04x".format(c.code))
        else -> append(c)
    }
}

/**
 * Hand-built rather than run through a JSON library, because the reader on the other side is `jq`
 * in a shell script, not a Kotlin parser. Escaping is still required: a path holding a `"`, a `\`
 * or a control character would otherwise produce a file `jq` cannot read at all.
 */
fun renderHandshake(path: String, port: Int, token: String): String =
    """{"path": "${escapeJson(path)}", "port": $port, "token": "${escapeJson(token)}"}"""

// internal rather than private: PublishedRemarks.kt (same package, different file — a top-level
// `private` in Kotlin is file-private, not package-private) reuses this for the same reason
// writeHandshake needs it: a POSIX permission view that degrades quietly where there is none.
internal fun setOwnerOnly(path: Path, mode: String) {
    // Guarded: a filesystem with no POSIX view (e.g. plain FAT) degrades instead of throwing.
    // fileAttributesViewOrNull is kotlin.io.path from the Kotlin standard library, @InlineOnly, with
    // no @ExperimentalPathApi and so no @OptIn — this removes internal-API exposure entirely rather
    // than trading one internal call (com.intellij.util.io.PosixFilePermissionsUtil, marked
    // @ApiStatus.Internal) for another. The repository already carries one accepted internal-API
    // usage; a second is not free.
    path.fileAttributesViewOrNull<PosixFileAttributeView>()
        ?.setPermissions(PosixFilePermissions.fromString(mode))
}

/**
 * Writes the handshake for [realPath] into [dir]. The directory's permissions are set right after
 * it is created, before anything is written into it: a create-time-only call never runs when the
 * directory already exists from an earlier version at 755, and setting it after the file write
 * would leave the file world-readable for however short a window.
 */
fun writeHandshake(realPath: Path, port: Int, token: String, dir: Path = handshakeDir()) {
    Files.createDirectories(dir)
    setOwnerOnly(dir, "rwx------")
    val target = dir.resolve(handshakeName(realPath.toString()))
    atomicWriteString(target, renderHandshake(realPath.toString(), port, token))
    // The permission call on the file has to run after the move: atomicWriteString renames a
    // temp file onto the target, so setting permissions before that would set them on a file that
    // no longer exists at this path.
    setOwnerOnly(target, "rw-------")
}

fun deleteHandshake(realPath: Path, dir: Path = handshakeDir()) {
    Files.deleteIfExists(dir.resolve(handshakeName(realPath.toString())))
}

/**
 * Copies RemarkGutter's shape exactly: a project-level service, created by the existing
 * ProjectActivity touching it, writing in start() and deleting in dispose(). Touching the service
 * creates it; start() is what does IO, which is why the write is not in an init block — a service
 * can be constructed on any thread.
 */
@Service(Service.Level.PROJECT)
class ReviewHandshakeService(private val project: Project) : Disposable {

    fun start() {
        // In test mode BuiltInServerManagerImpl never launches its start job, so waitForStart()
        // would start a real HTTP server during ./gradlew test, and the write would land in the
        // developer's real handshake directory.
        if (ApplicationManager.getApplication().isUnitTestMode) return
        val root = projectIdentity(project) ?: return
        // Never plain .port: it falls back to the default port until the real bind finishes, which
        // happens asynchronously after project open. waitForStart() joins that job and is safe to
        // call here because a ProjectActivity runs off the EDT.
        val port = BuiltInServerManager.getInstance().waitForStart().port
        writeHandshake(root, port, ReviewToken.value)
    }

    /**
     * Two IDEs with the same project open both write this file, and the second one wins. If the
     * first closes and deletes it unconditionally, the survivor becomes silently undiscoverable.
     * So this reads the file back first and deletes only if it still carries this run's token. A
     * read that fails for any reason means delete nothing.
     */
    override fun dispose() {
        // runCatching, not a bare call: this runs while the project is being torn down, and even
        // reading basePath goes through the project's store, which can already be gone. There is
        // nothing to do about that but leave the file alone, which is this method's own rule anyway.
        val root = runCatching { projectIdentity(project) }.getOrNull() ?: return
        val file = handshakeDir().resolve(handshakeName(root.toString()))
        val content = runCatching { Files.readString(file) }.getOrNull() ?: return
        if (content.contains("\"token\": \"${ReviewToken.value}\"")) deleteHandshake(root)
    }

    companion object {
        fun getInstance(project: Project): ReviewHandshakeService = project.service()
    }
}
