package dev.sasha.clauderemarks.review

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Plain JUnit: filterReviewPaths is pure, no fixture needed. */
class OpenReviewFilesTest {

    @Test
    fun `a path that climbs out of the project is dropped`() {
        val filtered = filterReviewPaths(listOf("../../etc/passwd", "/etc/passwd"))

        assertTrue(filtered.isEmpty())
    }

    @Test
    fun `at most twenty files survive the filter`() {
        val paths = (1..30).map { "file$it.kt" }

        val filtered = filterReviewPaths(paths)

        assertEquals(20, filtered.size)
    }
}
