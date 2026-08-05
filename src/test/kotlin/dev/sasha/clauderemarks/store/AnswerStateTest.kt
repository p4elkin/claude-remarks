package dev.sasha.clauderemarks.store

import com.intellij.openapi.util.JDOMUtil
import com.intellij.util.xmlb.XmlSerializer
import dev.sasha.clauderemarks.model.AnswerState
import org.junit.Assert.assertEquals
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

    private fun asXml(answer: AnswerState) = JDOMUtil.write(XmlSerializer.serialize(answer))

    private fun roundTrip(state: RemarkStore.RemarksState) = XmlSerializer.deserialize(
        JDOMUtil.load(JDOMUtil.write(XmlSerializer.serialize(state))),
        RemarkStore.RemarksState::class.java,
    )
}
