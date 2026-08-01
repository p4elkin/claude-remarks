package dev.sasha.clauderemarks.store

import com.intellij.configurationStore.ComponentSerializationUtil
import com.intellij.openapi.util.JDOMUtil
import com.intellij.util.xmlb.XmlSerializer
import dev.sasha.clauderemarks.model.RemarkStatus
import dev.sasha.clauderemarks.model.RemarkTag
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The nested state class RemarkStore.RemarksState, on its own: what survives a write and read
 * cycle through XML, and what its three mutators do. Then the store's own contract with the
 * platform — getState, loadState and the state class the platform resolves — which needs no
 * project, so a plain RemarkStore() is enough. The service wiring is RemarkStoreServiceTest.
 */
class RemarkStoreStateTest {

    @Test
    fun `every field survives a write and read cycle`() {
        val original = RemarkStore.RemarksState()
        original.addRemark(
            remark(
                id = "r-1",
                path = "src/main/kotlin/Foo.kt",
                startLine = 10,
                endLine = 12,
                text = "why is this synchronized?",
                tag = RemarkTag.QUESTION,
                status = RemarkStatus.SENT,
                createdAt = 1_700_000_000_000L,
                textHash = "abcdef0123456789",
                contextBefore = "line a\nline b",
                contextAfter = "line c\nline d",
            )
        )

        val restored = roundTrip(original)

        assertEquals(1, restored.remarks.size)
        val r = restored.remarks.single()
        assertEquals("r-1", r.id)
        assertEquals("src/main/kotlin/Foo.kt", r.path)
        assertEquals(10, r.startLine)
        assertEquals(12, r.endLine)
        assertEquals("why is this synchronized?", r.text)
        assertEquals(RemarkTag.QUESTION, r.tag)
        assertEquals(RemarkStatus.SENT, r.status)
        assertEquals(1_700_000_000_000L, r.createdAt)
        assertEquals("abcdef0123456789", r.textHash)
        assertEquals("line a\nline b", r.contextBefore)
        assertEquals("line c\nline d", r.contextAfter)
    }

    @Test
    fun `a remark with no tag round-trips as null`() {
        val original = RemarkStore.RemarksState()
        original.addRemark(remark(id = "r-2", tag = null))

        val restored = roundTrip(original)

        assertNull(restored.remarks.single().tag)
        assertEquals(RemarkStatus.PENDING, restored.remarks.single().status)
    }

    /**
     * The joined context carries a leading newline as its marker, and XML attribute values are
     * whitespace-normalized by the parser unless the writer escapes them. This is the check
     * that JDOM writes it as a character reference, so the marker comes back.
     */
    @Test
    fun `one blank line of context survives the round trip through xml`() {
        val original = RemarkStore.RemarksState()
        original.addRemark(
            remark(id = "r-1").also {
                it.contextBefore = joinContext(listOf(""))
                it.contextAfter = joinContext(listOf("", "tail"))
            }
        )

        val restored = roundTrip(original).remarks.single()

        assertEquals(listOf(""), splitContext(restored.contextBefore))
        assertEquals(listOf("", "tail"), splitContext(restored.contextAfter))
    }

    @Test
    fun `several remarks survive in the order they were added`() {
        val original = RemarkStore.RemarksState()
        original.addRemark(remark(id = "r-1"))
        original.addRemark(remark(id = "r-2"))
        original.addRemark(remark(id = "r-3"))

        assertEquals(listOf("r-1", "r-2", "r-3"), roundTrip(original).remarks.map { it.id })
    }

    @Test
    fun `an empty list round-trips as an empty list`() {
        assertEquals(0, roundTrip(RemarkStore.RemarksState()).remarks.size)
    }

    @Test
    fun `adding a remark marks the state as changed`() {
        val state = RemarkStore.RemarksState()
        val before = state.modificationCount

        state.addRemark(remark(id = "r-1"))

        assertTrue(state.modificationCount > before)
    }

    @Test
    fun `removing a remark marks the state as changed`() {
        val state = RemarkStore.RemarksState()
        state.addRemark(remark(id = "r-1"))
        val before = state.modificationCount

        assertTrue(state.removeRemark("r-1"))

        assertTrue(state.modificationCount > before)
    }

    @Test
    fun `removing an id that is not there changes nothing`() {
        val state = RemarkStore.RemarksState()
        state.addRemark(remark(id = "r-1"))
        val before = state.modificationCount

        assertFalse(state.removeRemark("no-such-id"))

        assertEquals(before, state.modificationCount)
        assertEquals(1, state.remarks.size)
    }

    @Test
    fun `removing an id takes out every remark carrying it`() {
        val state = RemarkStore.RemarksState()
        state.addRemark(remark(id = "dup"))
        state.addRemark(remark(id = "dup"))

        assertTrue(state.removeRemark("dup"))

        assertEquals(0, state.remarks.size)
    }

    @Test
    fun `a snapshot does not change when a remark is added afterwards`() {
        val state = RemarkStore.RemarksState()
        state.addRemark(remark(id = "r-1"))
        val snapshot = state.snapshot()

        state.addRemark(remark(id = "r-2"))

        assertEquals(1, snapshot.size)
        assertEquals(2, state.snapshot().size)
    }

    /**
     * The guard for the whole reason RemarkStore implements PersistentStateComponent by hand.
     * The platform's serializer reads the object getState() returns, off the EDT and without
     * taking the store's lock, so that object must not be reachable by anything that mutates.
     */
    @Test
    fun `the state handed to the serializer does not change when a remark is added afterwards`() {
        val store = RemarkStore()
        store.add(remark(id = "r-1"))
        val handedOut = store.getState()

        store.add(remark(id = "r-2"))

        assertEquals(listOf("r-1"), handedOut.remarks.map { it.id })
        assertEquals(listOf("r-1", "r-2"), store.getState().remarks.map { it.id })
    }

    @Test
    fun `every call to getState hands out its own list instance`() {
        val store = RemarkStore()
        store.add(remark(id = "r-1"))

        assertNotSame(store.getState().remarks, store.getState().remarks)
    }

    @Test
    fun `what getState hands the serializer comes back through loadState`() {
        val store = RemarkStore()
        store.add(remark(id = "r-1"))
        store.add(remark(id = "r-2"))

        val restored = RemarkStore()
        restored.loadState(roundTrip(store.getState()))

        assertEquals(listOf("r-1", "r-2"), restored.all().map { it.id })
    }

    /**
     * The platform digs the state class out of the component's generic signature to know what to
     * deserialize workspace.xml into. It used to find it on the SimplePersistentStateComponent
     * superclass; now it has to find it on an implemented interface. If that ever stops working
     * the symptom is every stored remark vanishing on restart, with nothing logged.
     */
    @Test
    fun `the platform resolves RemarksState as the state class of the store`() {
        assertEquals(
            RemarkStore.RemarksState::class.java,
            ComponentSerializationUtil.getStateClass<RemarkStore.RemarksState>(RemarkStore::class.java),
        )
    }

    private fun roundTrip(state: RemarkStore.RemarksState) = XmlSerializer.deserialize(
        JDOMUtil.load(JDOMUtil.write(XmlSerializer.serialize(state))),
        RemarkStore.RemarksState::class.java,
    )
}
