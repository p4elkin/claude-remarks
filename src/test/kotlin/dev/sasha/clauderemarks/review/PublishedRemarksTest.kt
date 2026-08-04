package dev.sasha.clauderemarks.review

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFileAttributeView
import java.nio.file.attribute.PosixFilePermissions
import kotlin.io.path.name
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
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

    /** A round minute in millis, so formatting to minute precision and parsing it back round-trips. */
    private val FIXED_PUBLISHED_AT = 1_700_000_000_000L - (1_700_000_000_000L % 60_000L)

    @Test
    fun `the header renders eight lines in a fixed order`() {
        val header = PublishedHeader(
            nonce = "n-1",
            publishedAt = FIXED_PUBLISHED_AT,
            commit = "0123456789abcdef0123456789abcdef01234567",
            remarks = 3,
            reviewSession = "session-1",
            reviewLabel = "review the auth change",
            rejected = false,
        )
        val lines = header.render().trimEnd('\n').split("\n")

        assertEquals(lines.toString(), 8, lines.size)
        assertEquals(PUBLISHED_MARKER, lines[0])
        assertEquals("nonce: n-1", lines[1])
        assertTrue(lines[2], Regex("""published: \d{4}-\d{2}-\d{2} \d{2}:\d{2}""").matches(lines[2]))
        assertEquals("commit: 01234567", lines[3])
        assertEquals("remarks: 3", lines[4])
        assertEquals("review: session-1", lines[5])
        assertEquals("label: review the auth change", lines[6])
        assertEquals("rejected: no", lines[7])
    }

    @Test
    fun `a header with nothing to say writes none rather than an empty field`() {
        val header = PublishedHeader(
            nonce = "n-2",
            publishedAt = FIXED_PUBLISHED_AT,
            commit = null,
            remarks = 0,
            reviewSession = null,
            reviewLabel = null,
            rejected = false,
        )
        val lines = header.render().trimEnd('\n').split("\n")

        assertEquals("commit: none", lines[3])
        assertEquals("review: none", lines[5])
        assertEquals("label: none", lines[6])
        assertEquals("rejected: no", lines[7])
    }

    @Test
    fun `a label with a newline stays on one line, and a long label is cut`() {
        val header = PublishedHeader(
            nonce = "n-3",
            publishedAt = FIXED_PUBLISHED_AT,
            commit = null,
            remarks = 0,
            reviewSession = "s",
            reviewLabel = "x".repeat(50) + "\n" + "y".repeat(200),
            rejected = false,
        )
        val lines = header.render().trimEnd('\n').split("\n")

        assertEquals(lines.toString(), 8, lines.size)
        val labelLine = lines[6]
        assertTrue(labelLine, labelLine.startsWith("label: "))
        assertTrue(labelLine, labelLine.removePrefix("label: ").length <= 120)
        assertFalse(labelLine, labelLine.contains('\n'))
    }

    @Test
    fun `a rendered header reads back as the same fields`() {
        val header = PublishedHeader(
            nonce = "n-4",
            publishedAt = FIXED_PUBLISHED_AT,
            commit = "0123456789abcdef0123456789abcdef01234567",
            remarks = 5,
            reviewSession = "session-2",
            reviewLabel = "a label",
            rejected = true,
        )

        val readBack = publishedHeaderOf(header.render())

        assertEquals(
            header.copy(commit = header.commit?.take(8), reviewLabel = "a label"),
            readBack,
        )
    }

    @Test
    fun `a body that does not start with the marker reads back as null`() {
        val body = "not the marker\n" + PublishedHeader(
            nonce = "n",
            publishedAt = FIXED_PUBLISHED_AT,
            commit = null,
            remarks = 0,
            reviewSession = null,
            reviewLabel = null,
            rejected = false,
        ).render().substringAfter("\n")

        assertNull(publishedHeaderOf(body))
    }

    @Test
    fun `a header with a line out of order reads back as null`() {
        val rendered = PublishedHeader(
            nonce = "n",
            publishedAt = FIXED_PUBLISHED_AT,
            commit = "01234567",
            remarks = 3,
            reviewSession = null,
            reviewLabel = null,
            rejected = false,
        ).render()
        val lines = rendered.trimEnd('\n').split("\n").toMutableList()
        val commitLine = lines[3]
        lines[3] = lines[4]
        lines[4] = commitLine
        val swapped = lines.joinToString("\n") + "\n"

        assertNull(publishedHeaderOf(swapped))
    }

    @Test
    fun `a header whose count is not a number reads back as null`() {
        val rendered = PublishedHeader(
            nonce = "n",
            publishedAt = FIXED_PUBLISHED_AT,
            commit = null,
            remarks = 0,
            reviewSession = null,
            reviewLabel = null,
            rejected = false,
        ).render()
        val broken = rendered.replace("remarks: 0", "remarks: not-a-number")

        assertNull(publishedHeaderOf(broken))
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
