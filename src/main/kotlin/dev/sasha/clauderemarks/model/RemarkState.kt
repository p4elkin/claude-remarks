package dev.sasha.clauderemarks.model

import com.intellij.openapi.components.BaseState

/** Picked in the input popup (`ui/RemarkInputPanel.kt`) when a remark is added or edited. */
enum class RemarkTag { BUG, QUESTION, REFACTOR, NOTE }

/** Lowercase, because this exact string is printed into the tree, the tooltip and the prompt. */
val RemarkTag.label: String get() = name.lowercase()

/** SENT is written by `markRemarksSent` once a copy reaches the clipboard, in `action/CopyRemarks.kt`. */
enum class RemarkStatus { PENDING, SENT }

/**
 * One remark, as it is written into .idea/workspace.xml.
 *
 * Extends BaseState rather than being a Kotlin data class on purpose: elements stored inside
 * a BaseState list are serialized by reflection, and BaseState's property delegates are the
 * shape the serializer is documented to handle. Context lines are joined with newlines into a
 * single string instead of a list, for the same reason.
 */
class RemarkState : BaseState() {
    var id by string()
    var path by string()
    var startLine by property(0)
    var endLine by property(0)
    var text by string()
    var tag by enum<RemarkTag>()
    var status by enum(RemarkStatus.PENDING)
    var createdAt by property(0L)
    var textHash by string()
    var contextBefore by string()
    var contextAfter by string()
}
