package dev.sasha.clauderemarks.review

import java.nio.file.Path
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/** Plain JUnit: startOrConflict is pure data in, data out, no fixture needed. */
class WaitingReviewTest {

    private val somePath = Path.of("/tmp/claude-remarks-review-a")
    private val otherPath = Path.of("/tmp/claude-remarks-review-b")

    @Test
    fun `a start with nothing waiting is accepted`() {
        val result = startOrConflict(current = null, session = "s1", label = "review one") { somePath }

        assertEquals(
            StartResult.Accepted(WaitingReviewState("s1", "review one", somePath, (result as StartResult.Accepted).state.startedAt)),
            result,
        )
    }

    @Test
    fun `the same session starting again gets the same output path back`() {
        val waiting = WaitingReviewState("s1", "review one", somePath, startedAt = 1000L)

        val result = startOrConflict(current = waiting, session = "s1", label = "a different label") {
            error("must not create a new output path when the same session retries")
        }

        assertEquals(StartResult.Accepted(waiting), result)
    }

    @Test
    fun `a different session while one is waiting is a conflict`() {
        val waiting = WaitingReviewState("s1", "review one", somePath, startedAt = 1000L)

        val result = startOrConflict(current = waiting, session = "s2", label = "review two") {
            error("must not create an output path on a conflict")
        }

        assertEquals(StartResult.Conflict(waiting), result)
        assertEquals("review one", (result as StartResult.Conflict).waiting.label)
    }

    @Test
    fun `after clearing, a different session is accepted`() {
        val afterClear: WaitingReviewState? = null

        val result = startOrConflict(current = afterClear, session = "s2", label = "review two") { otherPath }

        assertEquals(StartResult.Accepted(WaitingReviewState("s2", "review two", otherPath, (result as StartResult.Accepted).state.startedAt)), result)
        assertSame(otherPath, result.state.outputPath)
    }
}
