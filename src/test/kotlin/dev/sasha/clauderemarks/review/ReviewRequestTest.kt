package dev.sasha.clauderemarks.review

import java.nio.file.Path
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The two pure halves of the endpoint: who is allowed to call it, and which open project a request
 * is about. Both take plain values rather than an HttpRequest, so these run in milliseconds with no
 * fixture. The endpoint's own wiring is covered by [ReviewEndpointSmokeTest].
 */
class ReviewRequestTest {

    private val secret = "5f2b1a90-token"

    @Test
    fun `a request with the right token and no browser headers is allowed`() {
        assertTrue(requestIsAllowed(token = secret, expected = secret, origin = null, referer = null))
    }

    @Test
    fun `a wrong token is refused`() {
        assertFalse(requestIsAllowed(token = "not-the-token", expected = secret, origin = null, referer = null))
    }

    @Test
    fun `a missing token is refused`() {
        assertFalse(requestIsAllowed(token = null, expected = secret, origin = null, referer = null))
    }

    @Test
    fun `a request carrying an Origin header is refused even with the right token`() {
        // A local origin is the dangerous case, not a far-fetched one: the built-in server serves
        // files out of open projects on 127.0.0.1, so an .html committed into a repository the
        // person opens is served from exactly this origin.
        assertFalse(
            requestIsAllowed(token = secret, expected = secret, origin = "http://127.0.0.1:63342", referer = null)
        )
    }

    @Test
    fun `a request carrying a Referer header is refused even with the right token`() {
        assertFalse(
            requestIsAllowed(token = secret, expected = secret, origin = null, referer = "http://127.0.0.1:63342/page.html")
        )
    }

    @Test
    fun `the project is matched by its real path`() {
        val open = listOf(
            Path.of("/Users/sasha/dev/claude-remarks") to "claude-remarks",
            Path.of("/Users/sasha/dev/other-repo") to "other-repo",
        )

        assertEquals("claude-remarks", projectForPath("/Users/sasha/dev/claude-remarks/", open))
    }

    @Test
    fun `an unknown project path matches nothing`() {
        val open = listOf(Path.of("/Users/sasha/dev/claude-remarks") to "claude-remarks")

        assertNull(projectForPath("/Users/sasha/dev/not-open", open))
    }
}
