package dev.sasha.clauderemarks.review

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import dev.sasha.clauderemarks.store.projectRoot
import java.nio.file.Files
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
 * The first 16 hex characters of sha256(realPath), plus ".json". A skill can compute this with one
 * line of shell (`shasum -a 256`), which is why sha256 was chosen over anything cleverer.
 */
fun handshakeName(realPath: String): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(realPath.toByteArray(Charsets.UTF_8))
    val hex = digest.joinToString("") { "%02x".format(it) }
    return hex.take(16) + ".json"
}

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

private fun escapeJson(value: String): String =
    value.replace("\\", "\\\\").replace("\"", "\\\"")

/**
 * Hand-built rather than run through a JSON library, because the reader on the other side is `jq`
 * in a shell script, not a Kotlin parser. Escaping is still required: a path holding a `"` or `\`
 * would otherwise unbalance the quotes and `jq` could not read the file at all.
 */
fun renderHandshake(path: String, port: Int, token: String): String =
    """{"path": "${escapeJson(path)}", "port": $port, "token": "${escapeJson(token)}"}"""

private fun setOwnerOnly(path: Path, mode: String) {
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
    // TODO(task 3): switch to atomicWriteString once AtomicWrite.kt lands.
    Files.writeString(target, renderHandshake(realPath.toString(), port, token))
    // The permission call on the file has to run after the write: task 3 changes this to a
    // temp-file-then-rename, and the temp file is the one that gets renamed onto the target.
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
        val root = projectRoot(project)?.toNioPath()?.toRealPath() ?: return
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
        val root = runCatching { projectRoot(project)?.toNioPath()?.toRealPath() }.getOrNull() ?: return
        val file = handshakeDir().resolve(handshakeName(root.toString()))
        val content = runCatching { Files.readString(file) }.getOrNull() ?: return
        if (content.contains("\"token\": \"${ReviewToken.value}\"")) deleteHandshake(root)
    }

    companion object {
        fun getInstance(project: Project): ReviewHandshakeService = project.service()
    }
}
