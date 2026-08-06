package dev.sasha.clauderemarks.preview

/**
 * Which resolved remarks the preview page should highlight, and where each one starts in the .md
 * source — the arithmetic half of the highlight feature described in the phase 14 plan's Technical
 * Details.
 *
 * **No `com.intellij` import**, for the same reason `anchor/`, `render/PromptRenderer.kt` and
 * `preview/PreviewSelection.kt` have none: [highlightsFor] takes plain values through
 * [HighlightCandidate] rather than `RemarkState`, `ResolvedRemark` or `AnchorResult`, so nothing here
 * needs a project, a Document or a fixture to test. `preview/PreviewRemarkExtension.kt` is where those
 * platform types get resolved and turned into [HighlightCandidate]s before this file ever sees them.
 */

/**
 * What the page needs about one remark, once it is known that this remark belongs on the page at
 * all: where its highlight starts in the .md source, and whether it should draw as a question still
 * waiting for an answer rather than as a plain remark. `preview/PreviewRemarkExtension.kt` pushes a
 * list of these down the pipe, and `claude-remarks-preview.js` reads them back through [toJson].
 */
data class PreviewHighlight(
    val sourceStartOffset: Int,
    val isUnansweredQuestion: Boolean,
)

/**
 * The facts [highlightsFor] needs about one resolved remark, restated as plain values instead of
 * `RemarkState`/`ResolvedRemark`/`AnchorResult` so this file takes no platform import at all.
 *
 * [path] is the remark's stored, project-relative path — the same string `store/RemarkTarget.kt`'s
 * `relativePathOf` produces — or null/empty for a remark about no file, the same convention
 * `store/RemarkResolver.kt`'s `isAboutNoFile` already reads. [isOrphaned] is whether the resolve found
 * nothing, in which case [startLine]/[startColumn] are the stale stored ones and mean nothing.
 * [hasAnswer] is whatever the caller already knows from the answers list — this file has no way to
 * look that up itself, since that would mean walking `RemarkStore`.
 */
data class HighlightCandidate(
    val path: String?,
    val isOrphaned: Boolean,
    val startLine: Int,
    val startColumn: Int,
    val asksForAnswer: Boolean,
    val hasAnswer: Boolean,
)

/**
 * Turns every resolved remark into the highlight the page should draw, or drops it — three
 * exclusions, each its own reason:
 *
 * - **Orphaned.** Its code could not be found, so [HighlightCandidate.startLine]/[HighlightCandidate.startColumn]
 *   are stale and mean nothing in this render.
 * - **About no file.** A general remark has no position to highlight at all.
 * - **A different file than [previewedPath].** Only the file actually open in this preview gets
 *   highlights; every other remark in the project is silently not this page's concern.
 *
 * What survives is turned into a raw character offset in [source] by summing line lengths up to
 * [HighlightCandidate.startLine] and adding [HighlightCandidate.startColumn] — the same offset
 * `md-src-pos` is written in, which is why the page can search for it directly with no further
 * translation. A [HighlightCandidate.startLine] or resulting offset outside [source] is dropped
 * rather than thrown: the source may have moved since the remark was resolved, and a highlight
 * pointing nowhere is the ordinary case here, not a bug — the caller does not need to tell it apart
 * from any other exclusion.
 */
fun highlightsFor(
    candidates: List<HighlightCandidate>,
    previewedPath: String,
    source: String,
): List<PreviewHighlight> {
    val lineStarts = lineStartOffsets(source)
    return candidates.mapNotNull { highlightFor(it, previewedPath, lineStarts, source.length) }
}

private fun highlightFor(
    candidate: HighlightCandidate,
    previewedPath: String,
    lineStarts: List<Int>,
    sourceLength: Int,
): PreviewHighlight? {
    if (candidate.isOrphaned) return null
    if (candidate.path.isNullOrEmpty()) return null
    if (candidate.path != previewedPath) return null
    if (candidate.startLine !in lineStarts.indices) return null

    val offset = lineStarts[candidate.startLine] + candidate.startColumn
    if (offset !in 0..sourceLength) return null

    return PreviewHighlight(offset, candidate.asksForAnswer && !candidate.hasAnswer)
}

/** The character offset each line of [source] starts at, index 0 first. Always at least one entry. */
private fun lineStartOffsets(source: String): List<Int> {
    val starts = mutableListOf(0)
    for (i in source.indices) if (source[i] == '\n') starts += i + 1
    return starts
}

/**
 * [highlights] as the JSON array the page parses: `[{"offset":10,"kind":"remark"}, ...]`.
 *
 * Hand-written rather than through Gson, which `preview/PreviewSelection.kt` already uses in this
 * same package: only two fields ever cross, an `Int` and one of two fixed literal strings this file
 * chooses itself, so there is no free text here that needs escaping and pulling in a library for a
 * shape this small buys nothing.
 */
fun toJson(highlights: List<PreviewHighlight>): String =
    highlights.joinToString(separator = ",", prefix = "[", postfix = "]") { highlight ->
        val kind = if (highlight.isUnansweredQuestion) "question" else "remark"
        """{"offset":${highlight.sourceStartOffset},"kind":"$kind"}"""
    }
