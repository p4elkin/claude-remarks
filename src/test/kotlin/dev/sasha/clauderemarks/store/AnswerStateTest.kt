package dev.sasha.clauderemarks.store

import com.intellij.openapi.util.JDOMUtil
import com.intellij.util.xmlb.XmlSerializer
import dev.sasha.clauderemarks.model.AnswerState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The answers list on RemarkStore.RemarksState, on its own: what an answer is, and what survives a
 * write and read cycle through XML. Needs no project, so a plain RemarksState is enough. The store's
 * own contract with the platform — snapshot() and getState() — is in RemarkStoreStateTest, beside the
 * same two guards for the remarks list.
 *
 * ⚠️ This class is the regression guard for one specific trap, and it was written before the feature
 * it guards. A BaseState list needs `@get:XCollection(style = XCollection.Style.v2)` on its getter.
 * Without it the whole list serializes to an empty element: every answer is lost on restart, nothing
 * is logged anywhere, and no test that only writes and reads through the live object would notice.
 * This project has already paid for that once, on the remarks list. So the first test below does not
 * only round-trip — it reads the written XML and asserts the answer's own values are really in the
 * document, which is the only assertion the missing annotation cannot pass.
 */
class AnswerStateTest {

    /**
     * The guard itself. The round trip alone is not enough: with the annotation missing, serialize
     * writes an element with no answers in it at all, so both the write and the read are consistent
     * with each other and consistently empty. Reading the document for the answer's own id and its
     * body is what makes the emptiness visible.
     */
    @Test
    fun `an answer written into the state actually reaches the xml`() {
        val state = RemarkStore.RemarksState()
        state.putAnswer(answer(id = "a-1", remarkId = "r-1", markdown = "because two threads write it"))

        val xml = JDOMUtil.write(XmlSerializer.serialize(state))

        assertTrue(xml, xml.contains("a-1"))
        assertTrue(xml, xml.contains("because two threads write it"))

        val restored = roundTrip(state).answers
        assertEquals(1, restored.size)
        assertEquals("a-1", restored.single().id)
        assertEquals("because two threads write it", restored.single().markdown)
    }

    /**
     * Compared as serialized XML rather than field by field, the same way the remark twin
     * `every field survives a write and read cycle` is, and for the same reason: a field added to
     * AnswerState later is covered without editing this test.
     *
     * Every field is set to something other than its default, and that is load-bearing. BaseState
     * omits a property still at its default when it serializes, so a field left alone would compare
     * equal even if the round trip dropped it.
     */
    @Test
    fun `every field an answer is stored with survives the round trip`() {
        val original = answer(
            id = "a-1",
            remarkId = "r-7",
            question = "why is this synchronized?",
            markdown = "# Because\n\ntwo threads write it.",
            answeredAt = 1_700_000_000_000L,
            path = "src/main/kotlin/Foo.kt",
            startLine = 10,
            endLine = 12,
            startColumn = 5,
            endColumn = 9,
            textHash = "abcdef0123456789",
            contextBefore = "line a\nline b",
            contextAfter = "line c\nline d",
            phrase = "is this synchronized",
            commit = "0123456789abcdef0123456789abcdef01234567",
        )
        val state = RemarkStore.RemarksState()
        state.putAnswer(original)

        val restored = roundTrip(state).answers.single()

        assertEquals(asXml(original), asXml(restored))
    }

    /** Two lists in one element, so neither may cost the other. */
    @Test
    fun `remarks and answers both come back from one round trip`() {
        val state = RemarkStore.RemarksState()
        state.addRemark(remark(id = "r-1"))
        state.putAnswer(answer(id = "a-1", remarkId = "r-1"))

        val restored = roundTrip(state)

        assertEquals(listOf("r-1"), restored.remarks.map { it.id })
        assertEquals(listOf("a-1"), restored.answers.map { it.id })
    }

    @Test
    fun `several answers survive in the order they were added`() {
        val state = RemarkStore.RemarksState()
        state.putAnswer(answer(id = "a-1", remarkId = "r-1"))
        state.putAnswer(answer(id = "a-2", remarkId = "r-2"))
        state.putAnswer(answer(id = "a-3", remarkId = "r-3"))

        assertEquals(listOf("a-1", "a-2", "a-3"), roundTrip(state).answers.map { it.id })
    }

    @Test
    fun `an empty answers list round-trips as an empty list`() {
        assertEquals(0, roundTrip(RemarkStore.RemarksState()).answers.size)
    }

    /**
     * A workspace.xml written before this list existed has no answers element at all. It must load as
     * a state with no answers rather than as a failure, which is what makes the new list need no
     * migration of its own.
     */
    @Test
    fun `a state stored before answers existed loads with none`() {
        val restored = XmlSerializer.deserialize(
            JDOMUtil.load("""<RemarksState><remarks><RemarkState id="r-1" path="src/Foo.kt" /></remarks></RemarksState>"""),
            RemarkStore.RemarksState::class.java,
        )

        assertEquals(1, restored.remarks.size)
        assertEquals(0, restored.answers.size)
    }

    /**
     * The upsert. A question answered twice must end with one answer carrying the second body, not
     * two rows in the Answers group saying different things about the same remark.
     *
     * This is not a rare case and the store is where it is settled. Every publish mints a fresh
     * nonce, and a watcher compares nonces rather than content, so re-publishing the same question
     * looks like new work to a listening session — one extra press of Publish is enough to produce a
     * second answer.
     */
    @Test
    fun `a second answer for the same remark replaces the first`() {
        val state = RemarkStore.RemarksState()
        state.putAnswer(answer(id = "a-1", remarkId = "r-1", markdown = "the first try"))

        state.putAnswer(answer(id = "a-2", remarkId = "r-1", markdown = "the second try"))

        val stored = state.answersSnapshot()
        assertEquals(1, stored.size)
        assertEquals("a-2", stored.single().id)
        assertEquals("the second try", stored.single().markdown)
    }

    /**
     * The race, from the store's side. Two `answer` requests for the same remark are two asynchronous
     * pipelines in flight at once, and they can finish in either order — so the store is handed the
     * newer answer first and the older one second. Without the stamp comparison in `putAnswer` the
     * second write would win simply for arriving second, and the person would read the stale body
     * with nothing to show it had happened.
     *
     * The two stamps are the arrival stamps, not build times: `answeredAt` is taken when the request
     * was accepted, which is what makes "older" mean "asked for earlier" here.
     */
    @Test
    fun `an answer that arrived earlier does not overwrite one that arrived later`() {
        val state = RemarkStore.RemarksState()
        state.putAnswer(answer(id = "a-2", remarkId = "r-1", markdown = "the second try", answeredAt = 200L))

        state.putAnswer(answer(id = "a-1", remarkId = "r-1", markdown = "the first try", answeredAt = 100L))

        val stored = state.answersSnapshot()
        assertEquals(1, stored.size)
        assertEquals("a-2", stored.single().id)
        assertEquals("the second try", stored.single().markdown)
    }

    /**
     * The other direction, and the promise the guard must not break: re-asking a question still
     * replaces its answer. A refusal that also turned this case away would quietly kill the whole
     * re-ask story, which is the reason replacement is keyed on the remark in the first place.
     */
    @Test
    fun `an answer that arrived later still replaces the one already stored`() {
        val state = RemarkStore.RemarksState()
        state.putAnswer(answer(id = "a-1", remarkId = "r-1", markdown = "the first try", answeredAt = 100L))

        state.putAnswer(answer(id = "a-2", remarkId = "r-1", markdown = "the second try", answeredAt = 200L))

        assertEquals("the second try", state.answersSnapshot().single().markdown)
    }

    /** The other half: the key is the remark, so two questions keep two answers. */
    @Test
    fun `an answer for a different remark sits beside the first`() {
        val state = RemarkStore.RemarksState()
        state.putAnswer(answer(id = "a-1", remarkId = "r-1"))

        state.putAnswer(answer(id = "a-2", remarkId = "r-2"))

        assertEquals(listOf("a-1", "a-2"), state.answersSnapshot().map { it.id })
    }

    /** Replacement survives the round trip too, so what is on disk is one answer and not two. */
    @Test
    fun `a replaced answer round-trips as one answer`() {
        val state = RemarkStore.RemarksState()
        state.putAnswer(answer(id = "a-1", remarkId = "r-1", markdown = "the first try"))
        state.putAnswer(answer(id = "a-2", remarkId = "r-1", markdown = "the second try"))

        val restored = roundTrip(state).answers

        assertEquals(1, restored.size)
        assertEquals("the second try", restored.single().markdown)
    }

    /** By the answer's own id, not by the remark's — deleting a row in the tree names the answer. */
    @Test
    fun `removeAnswer takes the answer named and leaves the rest`() {
        val state = RemarkStore.RemarksState()
        state.putAnswer(answer(id = "a-1", remarkId = "r-1"))
        state.putAnswer(answer(id = "a-2", remarkId = "r-2"))

        assertTrue(state.removeAnswer("a-1"))
        assertEquals(listOf("a-2"), state.answersSnapshot().map { it.id })
        assertFalse(state.removeAnswer("a-1"))
    }

    /** Clear All takes both lists; Clear Handed Over does not, and that is [RemarkStore.RemarksState.removeHandedOver]'s business. */
    @Test
    fun `clear takes remarks and answers together and counts both`() {
        val state = RemarkStore.RemarksState()
        state.addRemark(remark(id = "r-1"))
        state.putAnswer(answer(id = "a-1", remarkId = "r-1"))

        assertEquals(2, state.clear())
        assertEquals(0, state.snapshot().size)
        assertEquals(0, state.answersSnapshot().size)
    }

    /** Answers are not "handed over" to anything, so the clear that keeps pending work keeps them. */
    @Test
    fun `clearing what was handed over leaves answers alone`() {
        val state = RemarkStore.RemarksState()
        state.addRemark(remark(id = "r-1"))
        state.markPublished(setOf("r-1"))
        state.putAnswer(answer(id = "a-1", remarkId = "r-1"))

        assertEquals(1, state.removeHandedOver())
        assertEquals(listOf("a-1"), state.answersSnapshot().map { it.id })
    }

    private fun asXml(answer: AnswerState) = JDOMUtil.write(XmlSerializer.serialize(answer))

    private fun roundTrip(state: RemarkStore.RemarksState) = XmlSerializer.deserialize(
        JDOMUtil.load(JDOMUtil.write(XmlSerializer.serialize(state))),
        RemarkStore.RemarksState::class.java,
    )
}
