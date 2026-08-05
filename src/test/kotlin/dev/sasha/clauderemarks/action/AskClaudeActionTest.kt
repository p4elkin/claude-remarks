package dev.sasha.clauderemarks.action

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.sasha.clauderemarks.store.RemarkStore
import dev.sasha.clauderemarks.store.addRemark
import dev.sasha.clauderemarks.store.answer
import dev.sasha.clauderemarks.store.markRemarksRead
import dev.sasha.clauderemarks.store.recordAnswer

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
     * The publish takes questions and nothing else. An ordinary remark waiting to be published must
     * not be dragged along: the gesture is "ask what is unanswered", not "publish everything and also
     * ask this". Questions still open do come along — see the overwrite test below for why.
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

    /**
     * The batch heals itself. `writePublished` rewrites the whole published file and a watcher only
     * looks every couple of seconds, so a second ask landing first would otherwise overwrite the
     * first question's file and strand it: already `PUBLISHED`, so no later ask would carry it, and
     * its row would say "asks" forever. Carrying every open question makes the second ask republish
     * the first one, which is the whole point of the wider batch.
     */
    fun testASecondAskCarriesTheFirstQuestionAgainSoAnOverwriteCannotStrandIt() {
        val published = mutableListOf<List<String>>()

        askClaude(project, "Ask.kt", LINES, 0..0, 0 to 0, "first question?") { _, ids ->
            published.add(ids.toList())
        }
        askClaude(project, "Ask.kt", LINES, 1..1, 0 to 0, "second question?") { _, ids ->
            published.add(ids.toList())
        }

        val first = questionAsking("first question?")
        val second = questionAsking("second question?")
        assertEquals(listOf(first), published.first())
        assertEquals(
            "the second ask must carry the first question again, not only the new one",
            setOf(first, second),
            published.last().toSet(),
        )
    }

    /**
     * A question that already has an answer is finished, so it is not asked again. Without this it
     * would ride along with every later ask forever and be answered again each time, replacing an
     * answer the person may already have read. Answering does not move a remark to `READ` — only an
     * acknowledgement does — so the status alone cannot tell these apart.
     */
    fun testAQuestionThatAlreadyHasAnAnswerIsNotAskedAgain() {
        val published = mutableListOf<List<String>>()
        askClaude(project, "Ask.kt", LINES, 0..0, 0 to 0, "first question?") { _, _ -> }
        val first = questionAsking("first question?")
        recordAnswer(project, answer(id = "a-1", remarkId = first, markdown = "because of X"))

        askClaude(project, "Ask.kt", LINES, 1..1, 0 to 0, "second question?") { _, ids ->
            published.add(ids.toList())
        }

        assertEquals(listOf(listOf(questionAsking("second question?"))), published)
    }

    /** A question an agent has acknowledged reading is done with too, answer or no answer. */
    fun testAQuestionAlreadyReadIsNotAskedAgain() {
        val published = mutableListOf<List<String>>()
        askClaude(project, "Ask.kt", LINES, 0..0, 0 to 0, "first question?") { _, _ -> }
        markRemarksRead(project, listOf(questionAsking("first question?")))

        askClaude(project, "Ask.kt", LINES, 1..1, 0 to 0, "second question?") { _, ids ->
            published.add(ids.toList())
        }

        assertEquals(listOf(listOf(questionAsking("second question?"))), published)
    }

    private fun questionAsking(text: String): String =
        RemarkStore.getInstance(project).all().single { it.text == text }.id!!

    private companion object {
        val LINES = listOf("alpha", "beta")
    }
}
