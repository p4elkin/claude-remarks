package dev.sasha.clauderemarks.model

import com.intellij.openapi.components.BaseState

/**
 * What has happened to a remark, in order.
 *
 * PENDING is written, handed nowhere. PUBLISHED means handed to a channel that cannot confirm a
 * read: the clipboard is one, the published file is another. READ means an agent said it read them,
 * over one of the two acknowledgement routes: POST /api/claude-remarks/ack, keyed to a review's own
 * session, or POST /api/claude-remarks/published-read, keyed to a published batch's nonce. Only an
 * agent's own acknowledgement produces READ — a publish, however many times it runs, only ever
 * produces PUBLISHED. That is the whole reason there are two words for "handed over" rather than
 * one.
 *
 * PENDING stays the first value and the default, so BaseState keeps omitting it and nothing has to
 * migrate. A remark stored by an older build as "SENT" does not parse and loads as PENDING. That is
 * accepted: those remarks had already been handed over, and nothing about them is lost but a colour.
 */
enum class RemarkStatus { PENDING, PUBLISHED, READ }

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

    /**
     * The sub-line range inside [startLine]/[endLine], or "no sub-line range" when [endColumn] is 0.
     *
     * `endColumn == 0` means this remark is about whole lines, not -1 and not null: BaseState omits
     * a property still at its default when it serializes, so every remark stored before this field
     * existed has neither column in its XML and loads with both at 0 — which is exactly what it
     * meant, a whole-line remark, with no migration needed. A real sub-line range always has
     * `endColumn > startColumn >= 0`.
     */
    var startColumn by property(0)
    var endColumn by property(0)
    var text by string()
    var status by enum(RemarkStatus.PENDING)
    var createdAt by property(0L)
    var textHash by string()
    var contextBefore by string()
    var contextAfter by string()

    /** Null means no bucket. Set from the tree or the gutter menu, never in the input popup: the
     *  popup has to stay fast, and a second chooser in it makes it a form. */
    var bucket by string()

    /**
     * The repository HEAD when this remark was written, or null when there was no readable git
     * repository. Captured once and never refreshed: it records what the author was looking at.
     * The stored line numbers and the textHash only mean anything against one revision, and this
     * is the one.
     */
    var commit by string()

    /**
     * The exact text between [startColumn] and [endColumn], for a sub-line remark only, or null.
     *
     * Null for a whole-line remark, which is every remark stored before this field existed: BaseState
     * omits a property still at its default, so nothing migrates and the anchor for those remarks is
     * unchanged. Stored as text rather than as a hash because a hash can only confirm a guess, and
     * finding a phrase that moved needs something to search for. The context lines beside it already
     * store six lines of real source, so this is not a new kind of data in workspace.xml.
     */
    var phrase by string()
}
