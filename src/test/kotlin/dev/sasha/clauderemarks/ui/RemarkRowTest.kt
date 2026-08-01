package dev.sasha.clauderemarks.ui

import dev.sasha.clauderemarks.anchor.AnchorResult
import dev.sasha.clauderemarks.model.RemarkState
import dev.sasha.clauderemarks.store.ResolvedRemark
import org.junit.Assert.assertEquals
import org.junit.Test

class RemarkRowTest {

    @Test
    fun `an exact match shows 1-based line numbers with no label`() {
        assertEquals("src/Foo.kt:5-7  why?  [PENDING]", describe(row(AnchorResult.Exact(4, 6))))
    }

    @Test
    fun `a block found somewhere else is labelled as moved`() {
        assertEquals(
            "src/Foo.kt:11-13 (moved)  why?  [PENDING]",
            describe(row(AnchorResult.Relocated(10, 12))),
        )
    }

    @Test
    fun `a block edited where it stands is not labelled as moved`() {
        assertEquals("src/Foo.kt:5-7  why?  [PENDING]", describe(row(AnchorResult.Relocated(4, 6))))
    }

    /** Stored 4..6, resolved 4..7: the start line alone would call this unmoved. */
    @Test
    fun `a block that kept its start line but not its end is labelled as moved`() {
        assertEquals(
            "src/Foo.kt:5-8 (moved)  why?  [PENDING]",
            describe(row(AnchorResult.Relocated(4, 7))),
        )
    }

    @Test
    fun `an orphan is labelled and keeps its stale line numbers`() {
        assertEquals(
            "src/Foo.kt:5-7 (orphaned)  why?  [PENDING]",
            describe(row(AnchorResult.Orphaned(4, 6))),
        )
    }

    @Test
    fun `a remark with no path and no text never renders the word null`() {
        val bare = ResolvedRemark(RemarkState(), AnchorResult.Exact(0, 0))

        assertEquals(":1-1    [PENDING]", describe(bare))
    }

    private fun row(result: AnchorResult) = ResolvedRemark(
        RemarkState().also {
            it.path = "src/Foo.kt"
            it.startLine = 4
            it.endLine = 6
            it.text = "why?"
        },
        result,
    )
}
