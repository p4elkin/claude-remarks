package dev.sasha.clauderemarks.store

import com.intellij.openapi.util.JDOMUtil
import com.intellij.util.xmlb.XmlSerializer
import dev.sasha.clauderemarks.model.RemarkStatus
import dev.sasha.clauderemarks.model.RemarkTag
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RemarkStoreSerializationTest {

    @Test
    fun `every field survives a write and read cycle`() {
        val original = RemarkStore.RemarksState()
        original.remarks.add(
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

        val element = XmlSerializer.serialize(original)
        val restored = XmlSerializer.deserialize(
            JDOMUtil.load(JDOMUtil.write(element)),
            RemarkStore.RemarksState::class.java,
        )

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
        original.remarks.add(remark(id = "r-2", tag = null))

        val restored = XmlSerializer.deserialize(
            JDOMUtil.load(JDOMUtil.write(XmlSerializer.serialize(original))),
            RemarkStore.RemarksState::class.java,
        )

        assertNull(restored.remarks.single().tag)
        assertEquals(RemarkStatus.PENDING, restored.remarks.single().status)
    }

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
