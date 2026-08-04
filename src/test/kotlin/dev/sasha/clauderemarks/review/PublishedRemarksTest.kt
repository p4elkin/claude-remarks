package dev.sasha.clauderemarks.review

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFileAttributeView
import java.nio.file.attribute.PosixFilePermissions
import kotlin.io.path.name
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Plain JUnit: the name and the header are pure, and the write only needs a temporary directory. */
class PublishedRemarksTest {

    /**
     * Pinned against the literal 16 characters, not against `handshakeName` with its suffix taken
     * off. Both sides of that comparison were `projectHash(realPath)` one delegation hop away, so it
     * held for whatever the hash returned, a constant included. The skill computes this name itself
     * with `shasum -a 256 | cut -c1-16`, so the exact bytes are the contract.
     */
    @Test
    fun `the published name is the first 16 hex of the sha256, with a markdown suffix`() {
        val realPath = "/a/b"

        assertEquals("662b7b62a798bb2d.md", publishedName(realPath))
        // The handshake file for the same project differs in the suffix and in nothing else.
        assertEquals("662b7b62a798bb2d.json", handshakeName(realPath))
        assertNotEquals(publishedName(realPath), publishedName("/a/c"))
    }

    @Test
    fun `the header starts with the marker and carries the time, the commit and the count`() {
        val out = publishedHeader(now = 0L, commit = "0123456789abcdef0123456789abcdef01234567", count = 3)
        val lines = out.trimEnd('\n').split("\n")

        assertEquals(out, 4, lines.size)
        assertEquals(PUBLISHED_MARKER, lines[0])
        assertTrue(lines[1], Regex("""published: \d{4}-\d{2}-\d{2} \d{2}:\d{2}""").matches(lines[1]))
        assertEquals("commit: 01234567", lines[2])
        assertEquals("remarks: 3", lines[3])
    }

    @Test
    fun `a header with no commit says so rather than printing an empty field`() {
        val out = publishedHeader(now = 0L, commit = null, count = 0)

        assertTrue(out, out.contains("commit: none"))
        assertFalse(out, out.contains("commit: \n"))
        assertFalse(out, out.contains("commit:\n"))
    }

    /**
     * A temp directory, then list it and assert exactly one file: atomicWriteString's own temp file
     * is created beside the target and renamed onto it, so a leftover temp file here would mean the
     * rename never ran.
     */
    @Test
    fun `the write lands in the given directory, owner readable only, and leaves no temp file behind`() {
        val base = Files.createTempDirectory("claude-remarks-published")
        assumeTrue(Files.getFileAttributeView(base, PosixFileAttributeView::class.java) != null)
        val dir = base.resolve("nested")

        val target = writePublished(Path.of("/some/project"), "body text", dir)

        val entries = Files.list(dir).use { it.toList() }
        assertEquals(entries.map { it.name }.toString(), 1, entries.size)
        assertEquals(target, entries.single())
        // The name, not only "some file landed here". The skill finds this file by computing
        // publishedName itself from the repository path, so a write under any other name — the
        // handshake name, say — is a file nothing can ever find.
        assertEquals("a0317725f24b01df.md", target.name)
        assertEquals("body text", Files.readString(target))
        assertEquals(PosixFilePermissions.fromString("rw-------"), Files.getPosixFilePermissions(target))
        assertEquals(PosixFilePermissions.fromString("rwx------"), Files.getPosixFilePermissions(dir))
    }
}
