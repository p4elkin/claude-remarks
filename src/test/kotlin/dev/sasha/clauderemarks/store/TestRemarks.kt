package dev.sasha.clauderemarks.store

import dev.sasha.clauderemarks.model.RemarkState
import dev.sasha.clauderemarks.model.RemarkStatus
import dev.sasha.clauderemarks.model.RemarkTag

/**
 * One builder for every store test, so each test only spells out the fields it cares about.
 * The defaults are a valid, boring remark: everything set, nothing interesting.
 */
internal fun remark(
    id: String = "r-1",
    path: String = "src/Foo.kt",
    startLine: Int = 0,
    endLine: Int = 0,
    text: String = "note",
    tag: RemarkTag? = null,
    status: RemarkStatus = RemarkStatus.PENDING,
    createdAt: Long = 0L,
    textHash: String = "0000000000000000",
    contextBefore: String? = "",
    contextAfter: String? = "",
) = RemarkState().also {
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
