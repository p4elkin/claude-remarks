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
        // The whole line, not its shape. The newline is a space now and the rest is cut at 120, so
        // the 50 x's, one space and 69 y's are exactly what is left.
        assertEquals("label: " + "x".repeat(50) + " " + "y".repeat(69), lines[6])
    }

    /**
     * The session id gets the same treatment as the label, and for a worse reason. Both the skill and
     * watch-remarks.sh read this header by line number, so a session id holding a newline moves every
     * line after it: "s\nrejected: yes" would make line 8 say a batch was rejected when it was not,
     * and a real batch of remarks would be thrown away by the agent reading it.
     */
    @Test
    fun `a session id with a newline stays on one line`() {
        val header = PublishedHeader(
            nonce = "n-3b",
            publishedAt = FIXED_PUBLISHED_AT,
            commit = null,
            remarks = 2,
            reviewSession = "s1\nrejected: yes\nlabel: ",
            reviewLabel = "a label",
            rejected = false,
        )
        val lines = header.render().trimEnd('\n').split("\n")

        assertEquals(lines.toString(), 8, lines.size)
        assertEquals("review: s1 rejected: yes label: ", lines[5])
        assertEquals("label: a label", lines[6])
        assertEquals("rejected: no", lines[7])
    }

    /**
     * Both fields render() changes on the way out are named in the expected value: the commit is cut
     * to eight characters and the label loses its newline. Neither is written back the way it went
     * in, so a round trip that used a plain commit and a plain label would look like it accounted for
     * that while accounting for nothing.
     */
    @Test
    fun `a rendered header reads back as the same fields, once render's own changes are counted`() {
        val header = PublishedHeader(
            nonce = "n-4",
            publishedAt = FIXED_PUBLISHED_AT,
            commit = "0123456789abcdef0123456789abcdef01234567",
            remarks = 5,
            reviewSession = "session-2",
            reviewLabel = "a\nlabel",
            rejected = true,
        )

        val readBack = publishedHeaderOf(header.render())

        assertEquals(
            header.copy(commit = "01234567", reviewLabel = "a label"),
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

    /** Neither yes nor no: a lie about a rejection is worse than an error, so it reads back as null. */
    @Test
    fun `a header whose rejected value is neither yes nor no reads back as null`() {
        val broken = header().render().replace("rejected: no", "rejected: maybe")

        assertNull(publishedHeaderOf(broken))
    }

    /** The plain header the malformed-input tests break one line of. */
    private fun header() = PublishedHeader(
        nonce = "n",
        publishedAt = FIXED_PUBLISHED_AT,
        commit = null,
        remarks = 0,
        reviewSession = null,
        reviewLabel = null,
        rejected = false,
    )

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
