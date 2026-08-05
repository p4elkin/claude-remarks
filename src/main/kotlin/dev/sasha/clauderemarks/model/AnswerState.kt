package dev.sasha.clauderemarks.model

import com.intellij.openapi.components.BaseState

/**
 * One answer a Claude Code session sent back, as it is written into .idea/workspace.xml.
 *
 * An answer is its own record with its own anchor, not a field on [RemarkState], and both halves of
 * that matter. Its own anchor means it re-resolves as the code moves, reusing `anchor/` with no new
 * machinery. Its own record means it survives its question being cleared: a reading pass can be
 * cleared while what was learned stays.
 *
 * ## Why it does not share a superclass with [RemarkState]
 *
 * The nine anchor properties below are declared again here rather than being pulled up into a common
 * `AnchoredState : BaseState()`. Inheritance would work — BaseState builds its property list in the
 * constructors, so a subclass registers the parent's properties too — but it would change the
 * serialization shape of [RemarkState], and [RemarkState] is the record every remark already stored
 * on disk lives in. Silent data loss on a BaseState list is the one failure mode this project has
 * already paid for once. Nine duplicated declarations is a cheap price for not touching the shape of
 * data that already exists.
 *
 * The logic is shared even though the fields are not: `store/RemarkResolver.kt` holds the pure
 * `StoredAnchor` value type and one `resolveStored`, which both a remark and an answer are resolved
 * through, so the file lookup, the Document lookup, the no-file case and the refusals are written
 * once.
 *
 * ## What the fields mean
 *
 * [remarkId] is a plain reference and nothing enforces it. The remark it names may have been deleted
 * or cleared, and nothing in the plugin resolves an answer *through* its remark. The field is the key
 * replacement is done on, and the way a person tells which question this answers — not a foreign key.
 *
 * [question] is the remark's text, copied at answer time rather than looked up, for the same reason
 * the anchor is captured rather than followed: an answer whose remark has been cleared would
 * otherwise read as a paragraph with no subject.
 *
 * The anchor fields are captured fresh when the answer is stored, at the position the remark resolves
 * to *then*, so the answer starts with the full search radius ahead of it rather than whatever its
 * remark has already spent. Two cases fall back to the remark's stored fields as they are: a remark
 * that orphans has no resolved position worth capturing, and a general remark has no file at all. A
 * general remark's answer therefore carries an empty [path] and resolves as itself, exactly the way
 * `isAboutNoFile` already handles a general remark.
 */
class AnswerState : BaseState() {
    var id by string()

    /** The remark this answers. A plain reference; see the class KDoc for why nothing enforces it. */
    var remarkId by string()

    /** The remark's text, copied when the answer was stored. Empty when the remark was already gone. */
    var question by string()

    /** The answer body, as markdown. Rendered to HTML only when the popup opens. */
    var markdown by string()

    var answeredAt by property(0L)

    /** Empty for an answer to a general remark, and for an answer whose remark was already gone. */
    var path by string()

    var startLine by property(0)
    var endLine by property(0)

    /**
     * The sub-line range inside [startLine]/[endLine], or "no sub-line range" when [endColumn] is 0 —
     * the same convention [RemarkState.startColumn] and [RemarkState.endColumn] use, so the shared
     * resolve reads both records the same way.
     */
    var startColumn by property(0)
    var endColumn by property(0)

    var textHash by string()
    var contextBefore by string()
    var contextAfter by string()

    /** The exact text between [startColumn] and [endColumn], for a sub-line anchor only, or null. */
    var phrase by string()

    /** The repository HEAD when the answer was stored, or null with no readable git repository. */
    var commit by string()
}
