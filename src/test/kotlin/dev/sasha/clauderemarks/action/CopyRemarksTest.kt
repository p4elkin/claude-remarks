package dev.sasha.clauderemarks.action

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.sasha.clauderemarks.store.RemarkStore
import dev.sasha.clauderemarks.store.addRemark
import dev.sasha.clauderemarks.store.markRemarksSent

/**
 * Which remarks a copy takes. Copy All must leave out the ones already sent, and Copy Selected
 * must take exactly the ids it was given even when they are sent — that pair IS the sent
 * lifecycle, so it is the one part of this file worth an automated test. The clipboard itself and
 * the balloon are checked by hand in the next task.
 */
class CopyRemarksTest : BasePlatformTestCase() {

    override fun setUp() {
        super.setUp()
        // The light fixture project is shared across test classes, so the store is cleared here.
        RemarkStore.getInstance(project).clear()
    }

    fun testCopyAllLeavesOutRemarksThatWereAlreadySent() {
        val sent = addRemark(project, "Foo.kt", LINES, 0..0, "already handed over", null)
        val pending = addRemark(project, "Foo.kt", LINES, 1..1, "still waiting", null)
        markRemarksSent(project, listOf(sent.id!!))

        assertEquals(listOf(pending.id), prepare(project, null).ids)
    }

    fun testCopySelectedTakesTheIdsItWasGivenEvenWhenTheyAreSent() {
        val sent = addRemark(project, "Foo.kt", LINES, 0..0, "already handed over", null)
        addRemark(project, "Foo.kt", LINES, 1..1, "still waiting", null)
        markRemarksSent(project, listOf(sent.id!!))

        assertEquals(listOf(sent.id), prepare(project, listOf(sent.id!!)).ids)
    }

    fun testNothingToCopyComesBackEmptyRatherThanRenderingAnEmptyPrompt() {
        val prepared = prepare(project, null)

        assertTrue(prepared.ids.isEmpty())
        assertEquals("", prepared.markdown)
    }

    private companion object {
        val LINES = listOf("alpha", "beta")
    }
}
