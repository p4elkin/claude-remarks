package dev.sasha.clauderemarks.review

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFileAttributeView
import java.nio.file.attribute.PosixFilePermissions
import kotlin.io.path.name
import org.junit.Assert.assertEquals
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

    @Test
    fun `the header renders five lines in a fixed order`() {
        val header = PublishedHeader(
            nonce = "n-1",
            publishedAt = FIXED_PUBLISHED_AT,
            commit = "0123456789abcdef0123456789abcdef01234567",
            remarks = 3,
        )
        val lines = header.render().trimEnd('\n').split("\n")

        assertEquals(lines.toString(), 5, lines.size)
        assertEquals(PUBLISHED_MARKER, lines[0])
        assertEquals("nonce: n-1", lines[1])
        assertTrue(lines[2], Regex("""published: \d{4}-\d{2}-\d{2} \d{2}:\d{2}""").matches(lines[2]))
        assertEquals("commit: 01234567", lines[3])
        assertEquals("remarks: 3", lines[4])
    }

    @Test
    fun `a header with no commit writes none rather than an empty field`() {
        val header = PublishedHeader(
            nonce = "n-2",
            publishedAt = FIXED_PUBLISHED_AT,
            commit = null,
            remarks = 0,
        )
        val lines = header.render().trimEnd('\n').split("\n")

        assertEquals("commit: none", lines[3])
    }

    /**
     * A commit carrying a newline is a **loud** failure, on purpose, and this pins that it stays one.
     *
     * The reader finds every field by line number, so such a commit pushes `remarks:` off line 5 and
     * `publishedHeaderOf` reads back null — which `handleFetch` turns into `failed` with a detail.
     * `render()` used to replace control characters with spaces instead, and that produced a header
     * that parsed cleanly while reporting a commit nobody has: a silent wrong value in place of an
     * error. `headCommit` only ever returns a string that matched its 40-hex pattern, so this shape
     * needs a corrupt or hand-edited ref file to arise at all, and then saying so is the right answer.
     */
    @Test
    fun `a commit with a newline shifts the header, so it reads back as null rather than as a lie`() {
        val header = PublishedHeader(
            nonce = "n-3",
            publishedAt = FIXED_PUBLISHED_AT,
            commit = "01234\n67" + "x".repeat(40),
            remarks = 2,
        )
        val rendered = header.render()
        val lines = rendered.trimEnd('\n').split("\n")

        assertEquals(lines.toString(), 6, lines.size)
        assertNull(publishedHeaderOf(rendered))
    }

    /**
     * The commit render() changes on the way out is named in the expected value: it is cut to eight
     * characters. It is not written back the way it went in, so a round trip that used a shorter
     * commit would look like it accounted for that while accounting for nothing.
     */
    @Test
    fun `a rendered header reads back as the same fields, once render's own changes are counted`() {
        val header = PublishedHeader(
            nonce = "n-4",
            publishedAt = FIXED_PUBLISHED_AT,
            commit = "0123456789abcdef0123456789abcdef01234567",
            remarks = 5,
        )

        val readBack = publishedHeaderOf(header.render())

        assertEquals(header.copy(commit = "01234567"), readBack)
    }

    @Test
    fun `a body that does not start with the marker reads back as null`() {
        val body = "not the marker\n" + header().render().substringAfter("\n")

        assertNull(publishedHeaderOf(body))
    }

    /** Dropping the last of the five lines is what a truncated or half-written file would look like. */
    @Test
    fun `a text with fewer than five lines reads back as null`() {
        val lines = header().render().trimEnd('\n').split("\n")
        val fourLines = lines.dropLast(1).joinToString("\n") + "\n"

        assertNull(publishedHeaderOf(fourLines))
    }

    @Test
    fun `a missing prefix on any of lines 2 to 5 reads back as null`() {
        val lines = header().render().trimEnd('\n').split("\n")

        for (i in 1..4) {
            val corrupted = lines.toMutableList()
            corrupted[i] = "not-a-field"
            val broken = corrupted.joinToString("\n") + "\n"
            assertNull("line $i", publishedHeaderOf(broken))
        }
    }

    @Test
    fun `a header with a line out of order reads back as null`() {
        val rendered = PublishedHeader(
            nonce = "n",
            publishedAt = FIXED_PUBLISHED_AT,
            commit = "01234567",
            remarks = 3,
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
        val rendered = header().render()
        val broken = rendered.replace("remarks: 0", "remarks: not-a-number")

        assertNull(publishedHeaderOf(broken))
    }

    /**
     * The `published:` line is parsed back, not merely copied, and that parse is what validates the
     * line. Without the catch around it a date this plugin did not write would throw out of the fetch
     * handler, on a netty IO thread, instead of answering `failed`.
     */
    @Test
    fun `a header whose published date will not parse reads back as null`() {
        val rendered = header().render()
        val dateLine = rendered.split("\n")[2]
        val broken = rendered.replace(dateLine, "published: not-a-date")

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

    /** The plain header the malformed-input tests break one line of. */
    private fun header() = PublishedHeader(
        nonce = "n",
        publishedAt = FIXED_PUBLISHED_AT,
        commit = null,
        remarks = 0,
    )

    private companion object {
        /** A round minute in millis, so formatting to minute precision and parsing it back round-trips. */
        const val FIXED_PUBLISHED_AT = 1_700_000_000_000L - (1_700_000_000_000L % 60_000L)
    }
}
