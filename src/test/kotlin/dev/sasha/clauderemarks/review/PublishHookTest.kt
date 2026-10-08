package dev.sasha.clauderemarks.review

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
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
}
