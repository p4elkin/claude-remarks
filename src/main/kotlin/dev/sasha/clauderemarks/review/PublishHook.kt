package dev.sasha.clauderemarks.review

import com.google.gson.JsonParser
import java.io.IOException
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.attribute.PosixFileAttributes
import java.nio.file.attribute.PosixFilePermission
import java.time.Duration
import java.util.concurrent.TimeUnit

/** A missing hook is silent; an unusable hook carries a reason for the caller's warning log. */
sealed interface HookDecision {
    data class Use(val argv: List<String>, val label: String) : HookDecision
    data class Skip(val reason: String?) : HookDecision
}

/**
 * Reads the launcher-owned hook without executing it. The real file's owner and permissions guard
 * its argv, and the bound server port keeps another IDE on this checkout from invoking it.
 * Launcher fields `owner` and `state` are deliberately not interpreted here.
 */
fun readHook(file: Path, expectedPort: Int, currentUser: String): HookDecision {
    val attributes = try {
        Files.readAttributes(file, PosixFileAttributes::class.java)
    } catch (_: NoSuchFileException) {
        return HookDecision.Skip(null)
    } catch (e: Exception) {
        return HookDecision.Skip("Cannot read hook attributes: ${e.message}")
    }
    // A FIFO with no writer would block the read below forever.
    if (!attributes.isRegularFile) return HookDecision.Skip("Hook file is not a regular file")
    if (attributes.owner().name != currentUser) {
        return HookDecision.Skip("Hook file is not owned by $currentUser")
    }
    if (PosixFilePermission.GROUP_WRITE in attributes.permissions() ||
        PosixFilePermission.OTHERS_WRITE in attributes.permissions()) {
        return HookDecision.Skip("Hook file is writable by group or others")
    }

    return try {
        val json = JsonParser.parseString(Files.readString(file))
        if (!json.isJsonObject) return HookDecision.Skip("Hook JSON must be an object")
        val fields = json.asJsonObject
        val args = fields.get("argv")
        if (args == null || !args.isJsonArray || args.asJsonArray.size() == 0) {
            return HookDecision.Skip("Hook argv must be a non-empty array of strings")
        }
        val argv = args.asJsonArray.map {
            if (!it.isJsonPrimitive || !it.asJsonPrimitive.isString) {
                return HookDecision.Skip("Hook argv must contain only strings")
            }
            it.asString
        }
        if (!Path.of(argv.first()).isAbsolute) {
            return HookDecision.Skip("Hook executable must be an absolute path")
        }
        val port = fields.get("port")
        if (port == null || !port.isJsonPrimitive || !port.asJsonPrimitive.isNumber ||
            port.asString.toIntOrNull() != expectedPort) {
            return HookDecision.Skip("Hook port must equal this IDE's port ($expectedPort)")
        }
        val label = fields.get("label")
        if (label != null && (!label.isJsonPrimitive || !label.asJsonPrimitive.isString)) {
            return HookDecision.Skip("Hook label must be a string")
        }
        HookDecision.Use(argv, label?.asString?.takeUnless { it.isEmpty() } ?: "live review")
    } catch (e: Exception) {
        HookDecision.Skip("Cannot read hook JSON: ${e.message}")
    }
}

/** The hook's result never changes the publish that already succeeded. */
sealed interface HookOutcome {
    data object Queued : HookOutcome
    data object Closed : HookOutcome
    data object TimedOut : HookOutcome
    data class Failed(val exitCode: Int, val stderrTail: String) : HookOutcome
    data class NotStarted(val reason: String) : HookOutcome
}

/**
 * Runs argv directly, with the exact published bytes on stdin. Each pipe has its own daemon thread:
 * neither an early exit nor a child inheriting a pipe may hold up the executor's next publish.
 * The deadline starts when the process starts; the total drain join after exit is at most one second.
 */
fun runHook(argv: List<String>, input: ByteArray, timeout: Duration): HookOutcome {
    val started = System.nanoTime()
    val process = try {
        ProcessBuilder(argv).start()
    } catch (e: Exception) {
        return HookOutcome.NotStarted(e.message ?: e.javaClass.simpleName)
    }
    val stderr = ArrayDeque<String>()
    fun pipeThread(name: String, action: () -> Unit): Thread = Thread({
        try {
            action()
        } catch (_: IOException) {
            // A broken pipe is expected when the hook exits without consuming all stdin.
        }
    }, "Claude Remarks hook $name").apply {
        isDaemon = true
        start()
    }
    val writer = pipeThread("stdin") {
        process.outputStream.use { it.write(input) }
    }
    val stdoutReader = pipeThread("stdout") {
        process.inputStream.use { it.transferTo(java.io.OutputStream.nullOutputStream()) }
    }
    val stderrReader = pipeThread("stderr") {
        process.errorStream.bufferedReader(Charsets.UTF_8).use { reader ->
            reader.forEachLine { line ->
                synchronized(stderr) {
                    if (stderr.size == 5) stderr.removeFirst()
                    stderr.addLast(line)
                }
            }
        }
    }
    fun terminate() {
        process.descendants().use { children -> children.forEach { it.destroyForcibly() } }
        process.destroyForcibly()
    }
    return try {
        val remaining = (timeout.toNanos() - (System.nanoTime() - started)).coerceAtLeast(0)
        val exited = process.waitFor(remaining, TimeUnit.NANOSECONDS)
        if (!exited) terminate()
        val drainDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1)
        for (thread in listOf(stdoutReader, stderrReader, writer)) {
            val joinNanos = drainDeadline - System.nanoTime()
            if (joinNanos > 0) TimeUnit.NANOSECONDS.timedJoin(thread, joinNanos)
        }
        if (!exited) HookOutcome.TimedOut
        else when (val code = process.exitValue()) {
            0 -> HookOutcome.Queued
            3 -> HookOutcome.Closed
            else -> HookOutcome.Failed(code, synchronized(stderr) { stderr.joinToString("\n") })
        }
    } catch (_: InterruptedException) {
        terminate()
        Thread.currentThread().interrupt()
        HookOutcome.TimedOut
    }
}
