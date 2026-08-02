package dev.sasha.clauderemarks.model

import com.intellij.openapi.components.BaseState

/** Picked in the input popup (`ui/RemarkInputPanel.kt`) when a remark is added or edited. */
enum class RemarkTag { BUG, QUESTION, REFACTOR, NOTE }

/** Lowercase, because this exact string is printed into the tree, the tooltip and the prompt. */
val RemarkTag.label: String get() = name.lowercase()

/** SENT is written by `markRemarksSent` once a copy reaches the clipboard, in `action/CopyRemarks.kt`. */
enum class RemarkStatus { PENDING, SENT }

/**
 * How strongly a remark should be acted on. Declared low to high.
 *
 * A second axis next to the tag, and a different question: the tag says what kind of remark it is,
 * this says how much it matters. Without it a `refactor` remark reads the same in the prompt
 * whether it was an idle thought or the whole point of the reading pass, so the model either does
 * everything or guesses.
 */
enum class RemarkSeverity { VIBE, SUGGESTION, SHOULD, MUST }

/** Lowercase, for the same reason RemarkTag.label is: this exact string is printed into the tree,
 *  the tooltip, the history file and the prompt. */
val RemarkSeverity.label: String get() = name.lowercase()

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

    /**
     * Defaults to SHOULD rather than being nullable. A remark you bothered to write is usually
     * something you want done; the two ends of the scale are the ones worth choosing on purpose.
     * Non-null also means the renderer and the tree can print it with no null check, and a remark
     * stored before this field existed loads with the default instead of a null.
     */
    var severity by enum(RemarkSeverity.SHOULD)

    /** Null means no bucket. Set from the tree or the gutter menu, never in the input popup: the
     *  popup has to stay fast, and a second chooser in it makes it a form. */
    var bucket by string()
}
