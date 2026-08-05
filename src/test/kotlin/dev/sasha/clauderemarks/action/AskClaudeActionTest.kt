package dev.sasha.clauderemarks.action

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.sasha.clauderemarks.store.RemarkStore
import dev.sasha.clauderemarks.store.addRemark

/**
 * The phase's headline gesture, on the far side of the input popup.
 *
 * Nothing else in the suite drives it at all: `ActionIdsTest` checks that the action and its stroke
 * are registered, and that is registration, not behaviour. So deleting `asksForAnswer = true`, or
 * deleting the publish, both left every test green while the gesture quietly became an ordinary
 * remark — which is exactly the half-landing this class exists to catch.
 *
 * [askClaude] is called directly rather than through the popup: showing the input needs a window.
 * The publish is passed in as a lambda, which is what [askClaude]'s own KDoc explains — the real
 * pipeline is deliberately not driven from a test, so what is pinned here is that it is *called*,
 * with exactly the one id the gesture just wrote.
 */
class AskClaudeActionTest : BasePlatformTestCase() {

    override fun setUp() {
        super.setUp()
        // The light fixture project is shared across test classes.
        RemarkStore.getInstance(project).clear()
    }

    override fun tearDown() {
        RemarkStore.getInstance(project).clear()
        super.tearDown()
    }

    fun testAskingStoresTheRemarkMarkedAsAskingAndPublishesThatOneId() {
        val published = mutableListOf<List<String>>()

        askClaude(project, "Ask.kt", LINES, 0..0, 0 to 0, "why is this synchronized?") { _, ids ->
            published.add(ids.toList())
        }

        val stored = RemarkStore.getInstance(project).all().single()
        assertTrue("the remark must be stored marked as asking for an answer", stored.asksForAnswer)
        assertEquals("why is this synchronized?", stored.text)
        assertEquals(listOf(listOf(stored.id)), published)
    }

    /**
     * The publish takes the one remark just written and nothing else. A question asked while other
     * remarks are still waiting must not drag them along: the gesture is "ask this", not "publish
     * everything and also ask this".
     */
    fun testAskingPublishesOnlyTheQuestionAndNotTheRemarksAlreadyWaiting() {
        val waiting = addRemark(project, "Other.kt", LINES, 0..0, "a plain note")
        val published = mutableListOf<List<String>>()

        askClaude(project, "Ask.kt", LINES, 0..0, 0 to 0, "why is this synchronized?") { _, ids ->
            published.add(ids.toList())
        }

        val question = RemarkStore.getInstance(project).all().single { it.asksForAnswer }
        assertEquals(listOf(listOf(question.id)), published)
        assertFalse(published.single().contains(waiting.id))
    }

    /** The sub-line columns the selection carried reach the store, the same as an ordinary remark. */
    fun testTheQuestionKeepsTheColumnsTheSelectionCarried() {
        askClaude(project, "Ask.kt", LINES, 0..0, 2 to 5, "why this word?") { _, _ -> }

        val stored = RemarkStore.getInstance(project).all().single()
        assertEquals(2, stored.startColumn)
        assertEquals(5, stored.endColumn)
    }

    private companion object {
        val LINES = listOf("alpha", "beta")
    }
}
