package dev.sasha.clauderemarks.store

import com.intellij.openapi.util.JDOMUtil
import com.intellij.util.xmlb.XmlSerializer
import dev.sasha.clauderemarks.model.RemarkStatus
import dev.sasha.clauderemarks.model.RemarkTag
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RemarkStoreSerializationTest {

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

    private fun roundTrip(state: RemarkStore.RemarksState) = XmlSerializer.deserialize(
        JDOMUtil.load(JDOMUtil.write(XmlSerializer.serialize(state))),
        RemarkStore.RemarksState::class.java,
    )

    private fun remark(
        id: String,
        path: String = "src/Foo.kt",
        startLine: Int = 0,
        endLine: Int = 0,
        text: String = "note",
        tag: RemarkTag? = null,
        status: RemarkStatus = RemarkStatus.PENDING,
        createdAt: Long = 0L,
        textHash: String = "0000000000000000",
        contextBefore: String = "",
        contextAfter: String = "",
    ) = dev.sasha.clauderemarks.model.RemarkState().also {
        it.id = id
        it.path = path
        it.startLine = startLine
        it.endLine = endLine
        it.text = text
        it.tag = tag
        it.status = status
        it.createdAt = createdAt
        it.textHash = textHash
        it.contextBefore = contextBefore
        it.contextAfter = contextAfter
    }
}
