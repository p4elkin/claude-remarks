package dev.sasha.clauderemarks.anchor

/**
 * What counts as a sub-line range, and how one is written down. Both live here, together, because
 * they are the same rule read twice: whether a remark points at part of a line, and what that part
 * is called in a row a person reads.
 *
 * This file is pure Kotlin, like the rest of `anchor/`, so every caller can reach it: the phrase
 * capture next door, the markdown renderer, which may not touch the platform either, and the two
 * platform-facing files that draw a position — `ui/RemarksTree.kt` and `store/RemarkHistory.kt`.
 */

/**
 * Whether the two columns describe a real sub-line range, rather than the `0 to 0` sentinel a
 * whole-line remark carries.
 *
 * The check differs by shape, and that is the whole point of this function existing. The two
 * columns are two independent offsets, not one ordered pair: `selectedColumns` in
 * `action/AddRemarkAction.kt` measures each of them from the start of its own line.
 *
 * - Inside one line both columns bound the same line, so `endColumn > startColumn` is what a real
 *   range means there.
 * - Across lines a long first line and a short last line make `endColumn < startColumn` perfectly
 *   normal for a real selection — drag from column 25 of one line down to column 8 of another.
 *   Ordering the two against each other would throw that selection away. `selectedColumns` returns
 *   the sentinel `0 to 0` whenever the whole span was selected end to end, so `endColumn > 0` is
 *   the right "not the sentinel" check instead.
 *
 * A negative column is refused either way. That is reachable only from a hand-edited workspace.xml.
 *
 * Every caller asks here, so a stored phrase, the `⟦`/`⟧` markers drawn over it in the published
 * prompt, the tree row and the history entry can never disagree about what was selected. Before
 * this function existed the ordered check sat in two of those four places, and half of all partial
 * multi-line selections lost their phrase and their markers while the tree still promised them.
 */
fun hasSubLineRange(startLine: Int, endLine: Int, startColumn: Int, endColumn: Int): Boolean {
    if (startColumn < 0 || endColumn < 0) return false
    return if (startLine == endLine) endColumn > startColumn else endColumn > 0
}

/**
 * The 1-based position of a remark, the one shape every reader sees. A whole-line remark reads
 * "9-9". A sub-line remark inside one line reads "9:12-38". A sub-line remark across lines reads
 * "9:12-11:5". Columns are shown 1-based, the same +1 the line numbers already get.
 *
 * Lines and columns come in 0-based, the way IntelliJ's Document counts and the way they are
 * stored. Whether there are columns worth showing at all is [hasSubLineRange]'s answer, never a
 * second copy of that rule.
 *
 * A caller with a reason to show no columns passes the `0 to 0` sentinel: `ui/RemarksTree.kt` does
 * that for an orphaned row, whose line numbers no longer point at real code, so there is no current
 * line left to read a column against.
 */
fun positionLabel(startLine: Int, endLine: Int, startColumn: Int, endColumn: Int): String {
    val firstLine = startLine + 1
    val lastLine = endLine + 1
    if (!hasSubLineRange(startLine, endLine, startColumn, endColumn)) return "$firstLine-$lastLine"
    return if (startLine == endLine) {
        "$firstLine:${startColumn + 1}-${endColumn + 1}"
    } else {
        "$firstLine:${startColumn + 1}-$lastLine:${endColumn + 1}"
    }
}
