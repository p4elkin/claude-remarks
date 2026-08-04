package dev.sasha.clauderemarks.review

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFileAttributeView
import java.nio.file.attribute.PosixFilePermissions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Plain JUnit: the name and the rendering are pure, and the write only needs a temporary directory. */
class ReviewHandshakeTest {

    @Test
    fun `the file name is the same for the same path and different for a different path`() {
        val a1 = handshakeName("/a/b")
        val a2 = handshakeName("/a/b")
        val b = handshakeName("/a/c")

        assertEquals(a1, a2)
        assertNotEquals(a1, b)
        assertTrue(a1, Regex("[0-9a-f]{16}\\.json").matches(a1))
    }

    /**
     * The identity the file name is built from, for a project opened on a module below the
     * repository root. The skill hashes `git rev-parse --show-toplevel`, so anything else here
     * writes a file under a name the skill never looks for — and the review mode cannot even fall
     * back, because reaching the endpoint at all needs the handshake file it could not find.
     */
    @Test
    fun `a project opened below the repository root is identified by the repository`() {
        val repo = Files.createTempDirectory("claude-remarks-identity").toRealPath()
        Files.createDirectories(repo.resolve(".git"))
        val module = Files.createDirectories(repo.resolve("modules/api"))

        assertEquals(repo, projectIdentity(module.toString()))
    }

    /** No repository above it: the base path is the identity, and the name is still computable. */
    @Test
    fun `a project outside a git repository is identified by its own base path`() {
        val dir = Files.createTempDirectory("claude-remarks-identity").toRealPath()

        assertEquals(dir, projectIdentity(dir.toString()))
    }

    @Test
    fun `a base path that is missing, or is not a path at all, has no identity`() {
        assertNull(projectIdentity(null))
        assertNull(projectIdentity("/no/such/directory/anywhere"))
        // A NUL byte is the one thing a POSIX path name cannot hold, so this is the branch
        // that answers InvalidPathException rather than IOException. Both are caught by
        // name: catching Throwable would swallow a cancellation in the publish read action.
        assertNull(projectIdentity("no\u0000name"))
    }

    @Test
    fun `the rendered handshake carries the project path, the port and the token`() {
        val out = renderHandshake("/a/b", 63342, "s3cret")

        assertTrue(out, out.contains("/a/b"))
        assertTrue(out, out.contains("\"port\": 63342"))
        assertTrue(out, out.contains("s3cret"))
    }

    @Test
    fun `a project path holding a quote or a backslash is escaped`() {
        val out = renderHandshake("""/a/"weird"\path""", 63342, "token")

        // A hand-built JSON string that forgets to escape leaves an odd number of unescaped
        // quotes, which jq (the reader on the skill side) cannot parse.
        val withoutEscapes = out.replace("\\\\", "").replace("\\\"", "")
        assertEquals(0, withoutEscapes.count { it == '"' } % 2)
    }

    @Test
    fun `a project path holding a control character is escaped`() {
        val out = renderHandshake("/a/we\nird\tpath", 63342, "token")

        // A raw newline inside a JSON string literal is invalid JSON, so jq would refuse the whole
        // file — the same class of failure as the unescaped quote above, with nothing visible to
        // hint at the cause.
        assertFalse(out, out.any { it < ' ' })
        assertTrue(out, out.contains("we\\u000aird\\u0009path"))
    }

    @Test
    fun `writing twice replaces rather than appends`() {
        val dir = Files.createTempDirectory("claude-remarks-handshake")
        val path = Path.of("/some/project")

        writeHandshake(path, 11111, "tok", dir)
        writeHandshake(path, 22222, "tok", dir)

        val written = Files.readString(dir.resolve(handshakeName(path.toString())))
        assertTrue(written, written.contains("22222"))
        assertTrue(written, !written.contains("11111"))
    }

    /**
     * Asserts the permission set directly rather than that the directory can be listed: listing
     * needs only `r`, so a directory left at rw------- still lists fine, and the thing that
     * actually fails is creating a file inside it. Asserting the set is ordering-independent.
     */
    @Test
    fun `the handshake directory is owner-only and traversable`() {
        val base = Files.createTempDirectory("claude-remarks-handshake")
        assumeTrue(Files.getFileAttributeView(base, PosixFileAttributeView::class.java) != null)
        val dir = base.resolve("nested")

        writeHandshake(Path.of("/some/project"), 63342, "tok", dir)

        assertEquals(PosixFilePermissions.fromString("rwx------"), Files.getPosixFilePermissions(dir))
    }

    @Test
    fun `deleting a handshake that is not there does not throw`() {
        val dir = Files.createTempDirectory("claude-remarks-handshake")

        deleteHandshake(Path.of("/nope"), dir)
    }
}
