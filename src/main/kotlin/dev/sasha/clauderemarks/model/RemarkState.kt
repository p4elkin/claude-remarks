package dev.sasha.clauderemarks.model

import com.intellij.openapi.components.BaseState

/**
 * What has happened to a remark, in order.
 *
 * PENDING is written, handed nowhere. PUBLISHED means handed to a channel that cannot confirm a
 * read: the clipboard is one, the published file is another. READ means an agent said it read them,
 * over the one acknowledgement route there is: POST /api/claude-remarks/published-read, keyed to a
 * published batch's nonce. Only an agent's own acknowledgement produces READ — a publish, however
 * many times it runs, only ever produces PUBLISHED. That is the whole reason there are two words for
 * "handed over" rather than one. Guard 6 in CLAUDE.md is what keeps the route single, and
 * `store/RemarkEdits.kt` makes the same argument beside `markRemarksRead` itself.
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

    /**
     * True when this remark asks Claude Code for an answer rather than for work to do.
     *
     * Set in two places, both of them the person's own choice: the Ask Claude gesture, which writes a
     * remark already carrying it, and the Ask for an Answer toggle in the shared menu, which turns an
     * ordinary remark into a question afterwards and back again. Read by the prompt renderer, which
     * marks the heading, by the tree row, by the gutter tooltip, and by the skill, which answers only
     * the remarks carrying it.
     *
     * There is no one-writer guard on this the way there is on RemarkStatus.READ. A person saying "I
     * want an answer to this" is not a claim about what an agent did, so there is nothing for such a
     * rule to protect.
     *
     * Defaults to false, so BaseState omits it when it serializes and every remark stored before this
     * field existed loads as an ordinary remark — the same no-migration shape [startColumn],
     * [endColumn] and [phrase] already use.
     */
    var asksForAnswer by property(false)
    var status by enum(RemarkStatus.PENDING)
    var createdAt by property(0L)

    /**
     * When this remark was marked read, or 0 when it never has been — including every remark
     * stored before this field existed, which has no `readAt` attribute in its XML at all and
     * loads at the property's default, the same no-migration shape [startColumn]/[endColumn]/
     * [phrase] already use.
     *
     * Stamped in exactly one place: `RemarkStore.RemarksState.markRead`, reached only through
     * `store/RemarkEdits.kt`'s `markRemarksRead`. CLAUDE.md's guard 6 already restricts who may
     * call that function to two files, so this field has exactly one writer by construction —
     * there is no second path into the store that could stamp it early or move it once set. That
     * write also only ever happens once: a remark re-published and re-acknowledged keeps the
     * stamp from the first time it was marked read, since re-publishing and re-acknowledging the
     * same remark is ordinary in this plugin, and a second acknowledgement must not jump the row
     * to the top of Done for a reason the person handed it over twice, not because it was handled
     * twice.
     */
    var readAt by property(0L)
    var textHash by string()
    var contextBefore by string()
    var contextAfter by string()

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
