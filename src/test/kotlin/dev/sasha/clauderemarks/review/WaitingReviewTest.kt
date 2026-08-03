package dev.sasha.clauderemarks.review

import java.nio.file.Path
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** Plain JUnit: startOrConflict is pure data in, data out, no fixture needed. */
class WaitingReviewTest {

    private val somePath = Path.of("/tmp/claude-remarks-review-a")
    private val otherPath = Path.of("/tmp/claude-remarks-review-b")

    @Test
    fun `a start with nothing waiting is accepted`() {
        val result = startOrConflict(
            current = null,
            session = "s1",
            label = "review one",
            deadlineSeconds = 1800,
            now = 1000L,
        ) { somePath }

        assertEquals(
            StartResult.Accepted(
                WaitingReviewState("s1", "review one", somePath, startedAt = 1000L, deadlineAt = 1_801_000L),
            ),
            result,
        )
    }

    @Test
    fun `the same session starting again gets the same output path back`() {
        val waiting = WaitingReviewState(
            "s1",
            "review one",
            somePath,
            startedAt = 1000L,
            deadlineAt = Long.MAX_VALUE,
        )

        val result = startOrConflict(
            current = waiting,
            session = "s1",
            label = "a different label",
            deadlineSeconds = 1800,
            now = 2000L,
        ) {
            error("must not create a new output path when the same session retries")
        }

        assertEquals(
            StartResult.Accepted(waiting.copy(deadlineAt = 1_802_000L), fresh = false),
            result,
        )
    }

    @Test
    fun `a first accept is fresh, so the endpoint opens the files only once`() {
        val result = startOrConflict(
            current = null,
            session = "s1",
            label = "review one",
            deadlineSeconds = 1800,
            now = 1000L,
        ) { somePath }

        assertTrue((result as StartResult.Accepted).fresh)
    }

    @Test
    fun `a live review still conflicts with a different session`() {
        val waiting = WaitingReviewState(
            "s1",
            "review one",
            somePath,
            startedAt = 1000L,
            deadlineAt = Long.MAX_VALUE,
        )

        val result = startOrConflict(
            current = waiting,
            session = "s2",
            label = "review two",
            deadlineSeconds = 1800,
        ) {
            error("must not create an output path on a conflict")
        }

        assertEquals(StartResult.Conflict(waiting), result)
        assertEquals("review one", (result as StartResult.Conflict).waiting.label)
    }

    @Test
    fun `after clearing, a different session is accepted`() {
        val afterClear: WaitingReviewState? = null

        val result = startOrConflict(
            current = afterClear,
            session = "s2",
            label = "review two",
            deadlineSeconds = 1800,
            now = 2000L,
        ) { otherPath }

        assertEquals(
            StartResult.Accepted(
                WaitingReviewState("s2", "review two", otherPath, startedAt = 2000L, deadlineAt = 1_802_000L),
            ),
            result,
        )
        assertSame(otherPath, (result as StartResult.Accepted).state.outputPath)
    }

    @Test
    fun `a review is stale once its deadline has passed`() {
        val state = WaitingReviewState("s1", "review one", somePath, startedAt = 0L, deadlineAt = 1000L)

        assertFalse(state.isStale(999L))
        assertTrue(state.isStale(1000L))
        assertTrue(state.isStale(1001L))
    }

    @Test
    fun `a stale review does not block a different session`() {
        val stale = WaitingReviewState("s1", "review one", somePath, startedAt = 0L, deadlineAt = 1000L)

        val result = startOrConflict(
            current = stale,
            session = "s2",
            label = "review two",
            deadlineSeconds = 60,
            now = 2000L,
        ) { otherPath }

        assertEquals(
            StartResult.Accepted(
                WaitingReviewState("s2", "review two", otherPath, startedAt = 2000L, deadlineAt = 62_000L),
            ),
            result,
        )
    }

    @Test
    fun `a retry of the same session after the deadline keeps its path and gets a live deadline`() {
        val stale = WaitingReviewState("s1", "review one", somePath, startedAt = 0L, deadlineAt = 1000L)

        val result = startOrConflict(
            current = stale,
            session = "s1",
            label = "a different label",
            deadlineSeconds = 1800,
            now = 2000L,
        ) {
            error("must not create a new output path when the same session retries, even a late one")
        }

        val state = (result as StartResult.Accepted).state
        assertSame(somePath, state.outputPath)
        // Handing the old deadline back would answer "waiting" for a review the expiry task kills at
        // once: the skill would poll a path nothing can write to for its whole second deadline.
        assertEquals(1_802_000L, state.deadlineAt)
        assertFalse(state.isStale(2000L))
        assertFalse(result.fresh)
    }

    @Test
    fun `the deadline is computed from the same instant as the start time`() {
        val result = startOrConflict(
            current = null,
            session = "s1",
            label = "review one",
            deadlineSeconds = 60,
            now = 5000L,
        ) { somePath }

        val state = (result as StartResult.Accepted).state
        assertEquals(5000L, state.startedAt)
        assertEquals(65_000L, state.deadlineAt)
    }
}
