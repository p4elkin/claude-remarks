package dev.sasha.clauderemarks.review

import com.intellij.notification.NotificationType
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.time.Duration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Plain JUnit: hook validation uses only temporary files and POSIX attributes. */
class PublishHookTest {
    @get:Rule
    val temporary = TemporaryFolder()

    private val port = 63342
    private val currentUser = System.getProperty("user.name")

    private fun hook(json: String = """{"argv":["/bin/echo","hello"],"label":"my review","port":63342}"""): Path {
        val file = temporary.newFile().toPath()
        Files.writeString(file, json)
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"))
        return file
    }

    private fun assertSkipped(file: Path, user: String = currentUser, expectedPort: Int = port) {
        val decision = readHook(file, expectedPort, user)
        assertTrue(decision.toString(), decision is HookDecision.Skip)
        assertNotNull((decision as HookDecision.Skip).reason)
    }

    @Test
    fun `the hook name shares the project's hash`() {
        assertEquals("662b7b62a798bb2d.hook.json", hookName("/a/b"))
    }

    @Test
    fun `an absent file skips silently`() {
        assertEquals(HookDecision.Skip(null), readHook(temporary.root.toPath().resolve("absent"), port, currentUser))
    }

    @Test
    fun `malformed JSON skips with a reason`() {
        assertSkipped(hook("{broken"))
    }

    @Test
    fun `empty argv skips with a reason`() {
        assertSkipped(hook("""{"argv":[],"port":63342}"""))
    }

    @Test
    fun `argv must be an array of strings`() {
        for (argv in listOf("null", "\"/bin/echo\"", "[\"/bin/echo\",3]", "[\"/bin/echo\",null]", "[true]")) {
            assertSkipped(hook("""{"argv":$argv,"port":63342}"""))
        }
    }

    @Test
    fun `a relative executable skips with a reason`() {
        assertSkipped(hook("""{"argv":["echo"],"port":63342}"""))
    }

    @Test
    fun `a group writable file skips with a reason`() {
        val file = hook()
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw--w----"))
        assertSkipped(file)
    }

    @Test
    fun `an others writable file skips with a reason`() {
        val file = hook()
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-----w-"))
        assertSkipped(file)
    }

    @Test
    fun `a different owner skips with a reason`() {
        val file = hook()
        assertSkipped(file, user = Files.getOwner(file).name + "-another-user")
    }

    @Test
    fun `a different IDE port skips with a reason`() {
        assertSkipped(hook(), expectedPort = port + 1)
    }

    @Test
    fun `a port must be an integer`() {
        for (value in listOf("null", "\"63342\"", "63342.5", "4295030638")) {
            assertSkipped(hook("""{"argv":["/bin/echo"],"port":$value}"""))
        }
    }

    @Test
    fun `missing and empty labels use live review`() {
        for (label in listOf("", ",\"label\":\"\"")) {
            assertEquals(HookDecision.Use(listOf("/bin/echo"), "live review"),
                readHook(hook("""{"argv":["/bin/echo"],"port":63342$label}"""), port, currentUser))
        }
    }

    @Test
    fun `a valid hook retains argv and label and ignores launcher fields`() {
        val file = hook("""{"argv":["/bin/echo","hello"],"label":"my review","port":63342,"owner":"launcher","state":"opening"}""")
        assertEquals(HookDecision.Use(listOf("/bin/echo", "hello"), "my review"), readHook(file, port, currentUser))
    }

    private fun script(body: String): List<String> {
        val file = temporary.newFile().toPath()
        Files.writeString(file, body)
        return listOf("/bin/sh", file.toString())
    }

    @Test
    fun `stdin arrives byte for byte including UTF8 and a large batch`() {
        val output = temporary.newFile().toPath()
        for (text in listOf("A remark: hyvä 日本語 🐈\n", "ø".repeat(100_000))) {
            val input = text.toByteArray(Charsets.UTF_8)
            assertEquals(HookOutcome.Queued, runHook(script("cat > '${output}'"), input, Duration.ofSeconds(10)))
            org.junit.Assert.assertArrayEquals(input, Files.readAllBytes(output))
        }
    }

    @Test
    fun `exit zero queues and exit three closes`() {
        assertEquals(HookOutcome.Queued, runHook(script("exit 0"), byteArrayOf(), Duration.ofSeconds(10)))
        assertEquals(HookOutcome.Closed, runHook(script("exit 3"), byteArrayOf(), Duration.ofSeconds(10)))
    }

    @Test
    fun `a failed exit retains the last five stderr lines and drains stdout`() {
        val argv = script("""
            i=1
            while [ "${'$'}i" -le 10000 ]; do
                echo "stdout line ${'$'}i"
                i=${'$'}((i + 1))
            done
            printf 'one\ntwo\nthree\nfour\nfive\nsix\nseven\n' >&2
            exit 7
        """.trimIndent())
        assertEquals(HookOutcome.Failed(7, "three\nfour\nfive\nsix\nseven"),
            runHook(argv, byteArrayOf(), Duration.ofSeconds(10)))
    }

    @Test
    fun `an early exit three ignores broken stdin`() {
        assertEquals(HookOutcome.Closed,
            runHook(script("exit 3"), ByteArray(200_000), Duration.ofSeconds(10)))
    }

    @Test
    fun `a timeout also bounds a blocked stdin writer`() {
        val start = System.nanoTime()
        assertEquals(HookOutcome.TimedOut,
            runHook(script("sleep 30"), ByteArray(200_000), Duration.ofSeconds(1)))
        assertTrue("Timeout must return within three seconds", System.nanoTime() - start < Duration.ofSeconds(3).toNanos())
    }

    @Test
    fun `a detached child retaining pipes does not block a successful result`() {
        val childPid = temporary.newFile().toPath()
        try {
            val start = System.nanoTime()
            assertEquals(HookOutcome.Queued,
                runHook(script("sleep 30 &\necho ${'$'}! > '$childPid'\nexit 0"), byteArrayOf(), Duration.ofSeconds(10)))
            assertTrue("Inherited pipes must not block the result", System.nanoTime() - start < Duration.ofSeconds(3).toNanos())
        } finally {
            Files.readString(childPid).trim().toLongOrNull()?.let { ProcessHandle.of(it).ifPresent { child -> child.destroyForcibly() } }
        }
    }

    @Test
    fun `a missing executable does not start`() {
        val outcome = runHook(listOf(temporary.root.toPath().resolve("absent").toString()), byteArrayOf(), Duration.ofSeconds(10))
        assertTrue(outcome.toString(), outcome is HookOutcome.NotStarted)
    }

    @Test
    fun `each outcome has its balloon text and severity`() {
        assertEquals("Queued for my review" to NotificationType.INFORMATION, outcomeMessage(HookOutcome.Queued, "my review"))
        assertEquals("The live review is closed" to NotificationType.WARNING, outcomeMessage(HookOutcome.Closed, "my review"))
        assertEquals("The live review hook timed out" to NotificationType.WARNING, outcomeMessage(HookOutcome.TimedOut, "my review"))
        assertEquals("The live review hook failed (exit 7)\nlast error" to NotificationType.WARNING,
            outcomeMessage(HookOutcome.Failed(7, "last error"), "my review"))
        assertEquals("The live review hook could not start\nmissing executable" to NotificationType.WARNING,
            outcomeMessage(HookOutcome.NotStarted("missing executable"), "my review"))
    }
}
