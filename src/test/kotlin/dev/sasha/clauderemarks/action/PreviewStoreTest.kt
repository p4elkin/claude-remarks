package dev.sasha.clauderemarks.action

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.sasha.clauderemarks.model.RemarkStatus
import dev.sasha.clauderemarks.store.RemarkStore

/**
 * What each of the two preview entry points does with the typed text, which is the only place the
 * two differ at all. Everything before it — the five refusals, the line and column arithmetic, the
 * popup itself — is one shared function, `openPreviewRemarkInput`, and is behind a window no test
 * can open.
 *
 * `PreviewRemarkProblemTest` covers the refusals. Nothing covered this end until now, so deleting
 * the `asksForAnswer` half of the preview's Ask Claude, or its publish, left the second menu entry
 * quietly identical to the first with every test still green — the same half-landing
 * `AskClaudeActionTest` exists to catch for the editor gesture.
 */
class PreviewStoreTest : BasePlatformTestCase() {

    override fun setUp() {
        super.setUp()
        // The light fixture project is shared across test classes.
        RemarkStore.getInstance(project).clear()
    }

    override fun tearDown() {
        RemarkStore.getInstance(project).clear()
        super.tearDown()
    }

    fun testAddingFromThePreviewStoresAPlainRemarkAndHandsNothingOver() {
        addRemarkFromPreview(project, "Notes.md", LINES, 0..0, 2 to 5, "a note")

        val stored = RemarkStore.getInstance(project).all().single()
        assertFalse("Add Claude Remark must not mark the remark as asking", stored.asksForAnswer)
        assertEquals("a note", stored.text)
        assertEquals(2, stored.startColumn)
        assertEquals(5, stored.endColumn)
        assertEquals(
            "Add Claude Remark publishes nothing, so the remark stays pending",
            RemarkStatus.PENDING,
            stored.status,
        )
    }

    fun testAskingFromThePreviewMarksTheRemarkAsAskingAndPublishesIt() {
        val published = mutableListOf<List<String>>()

        askClaudeFromPreview(project, "Notes.md", LINES, 0..0, 2 to 5, "why this word?") { _, ids ->
            published.add(ids.toList())
        }

        val stored = RemarkStore.getInstance(project).all().single()
        assertTrue("Ask Claude must mark the remark as asking for an answer", stored.asksForAnswer)
        assertEquals("why this word?", stored.text)
        assertEquals(2, stored.startColumn)
        assertEquals(5, stored.endColumn)
        assertEquals(listOf(listOf(stored.id)), published)
    }

    /**
     * Asking from the preview is exactly as far-reaching as asking from the editor: it carries every
     * question still waiting for an answer, not only the one just typed. Without that, a second ask
     * would overwrite the first question's published file and strand it — see `openQuestionIds` in
     * `action/AskClaudeAction.kt`.
     */
    fun testAskingFromThePreviewCarriesEveryQuestionStillWaiting() {
        askClaudeFromPreview(project, "Notes.md", LINES, 0..0, 0 to 0, "first question?") { _, _ -> }
        val published = mutableListOf<List<String>>()

        askClaudeFromPreview(project, "Notes.md", LINES, 1..1, 0 to 0, "second question?") { _, ids ->
            published.add(ids.toList())
        }

        assertEquals(
            setOf(questionAsking("first question?"), questionAsking("second question?")),
            published.single().toSet(),
        )
    }

    private fun questionAsking(text: String): String =
        RemarkStore.getInstance(project).all().single { it.text == text }.id!!

    private companion object {
        val LINES = listOf("alpha", "beta")
    }
}
