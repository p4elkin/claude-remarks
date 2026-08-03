package dev.sasha.clauderemarks.review

import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Plain JUnit: one temporary directory, no fixture needed. */
class AtomicWriteTest {

    @Test
    fun `the temp file is created beside the target, not in the system temp directory`() {
        val dir = Files.createTempDirectory("atomic-write-test-")
        val target = dir.resolve("handoff.md")
        Files.createDirectories(target.parent)

        val temp = tempFileFor(target)

        assertEquals(target.parent, temp.parent)
    }

    @Test
    fun `writing creates the file with exactly the given content`() {
        val dir = Files.createTempDirectory("atomic-write-test-")
        val target = dir.resolve("handoff.md")

        atomicWriteString(target, "hello")

        assertEquals("hello", Files.readString(target))
    }

    @Test
    fun `writing again replaces the whole content`() {
        val dir = Files.createTempDirectory("atomic-write-test-")
        val target = dir.resolve("handoff.md")

        atomicWriteString(target, "a very long first string that is much longer than the second")
        atomicWriteString(target, "short")

        assertEquals("short", Files.readString(target))
    }

    @Test
    fun `no temp file is left behind`() {
        val dir = Files.createTempDirectory("atomic-write-test-")
        val target = dir.resolve("handoff.md")

        atomicWriteString(target, "hello")

        val entries = Files.list(target.parent).use { it.toList() }
        assertEquals(listOf(target), entries)
    }

    @Test
    fun `a missing parent directory is created`() {
        val dir = Files.createTempDirectory("atomic-write-test-")
        val target = dir.resolve("nested/deeper/handoff.md")

        atomicWriteString(target, "hello")

        assertTrue(Files.exists(target))
        assertEquals("hello", Files.readString(target))
    }
}
