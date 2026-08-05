package dev.sasha.clauderemarks.render

import dev.sasha.clauderemarks.anchor.hasSubLineRange

/**
 * Turns pending remarks into one markdown document.
 *
 * No platform imports, on purpose: this is where the only real logic in phase 4 lives, and
 * keeping it free of the platform is what makes its tests run in milliseconds. The one import is
 * `anchor/SubLineRange.kt`, which is pure Kotlin for the same reason, so it costs this file
 * nothing.
 */

/** One remark, with the code already sliced out of its file. Line numbers are 0-based. */
data class RenderedRemark(
    /**
     * The file this remark is about, or "" for a remark about no file at all — a general remark,
     * written from the tool window rather than from an editor selection. A nullable path would
     * touch every construction site and every test that builds one, for the same expressiveness a
     * non-null empty string already gives: [renderPrompt] partitions on `path.isEmpty()`.
     *
     * `isAboutNoFile` in `store/RemarkResolver.kt` asks the same question everywhere else, and this
     * file deliberately does not call it. It takes a `RemarkState`, which is a platform class, and
     * it lives in a file full of platform imports — either one would end this file's independence
     * from the platform, which is what keeps its tests running without a fixture. The two are kept
     * in step by naming each other here and there, not by sharing code.
     */
    val path: String,
    val startLine: Int,
    val endLine: Int,
    /**
     * The sub-line selection inside [startLine], or "none" when [endColumn] is 0 — see
     * `RemarkState` for the same convention on the stored field. `startColumn` is unused when
     * [endColumn] is 0.
     */
    val startColumn: Int = 0,
    val endColumn: Int = 0,
    /**
     * The repository HEAD when the remark was written, short-formed, or null. A remark genuinely
     * may not have one, so a caller that omits it is not necessarily a caller that forgot.
     */
    val commit: String? = null,
    val text: String,
    val orphaned: Boolean,
    /** The 0-based line number that code[0] came from. */
    val codeStartLine: Int,
    val code: List<String>,
    /**
     * What sat just above and just below the remark when it was written, straight out of the stored
     * anchor. Filled for an orphan only: that is the one case with no code to quote, and these lines
     * are then the only thing left to search the file for. Empty for every other remark.
     */
    val capturedBefore: List<String> = emptyList(),
    val capturedAfter: List<String> = emptyList(),
)

fun renderPrompt(header: String, remarks: List<RenderedRemark>): String {
    if (remarks.isEmpty()) return header.trimEnd() + "\n"

    val out = StringBuilder(header.trimEnd())
        .append("\n\n")
        .append(PROMPT_NOTES.trim())
        .append("\n\n---\n")
    var number = 0

    val (general, aboutAFile) = remarks.partition { it.path.isEmpty() }

    if (general.isNotEmpty()) {
        out.append("\n## General\n")
        general.forEach { remark ->
            number++
            out.append("\n### ").append(number).append(".")
            out.appendRemarkTail(remark)
        }
    }

    aboutAFile
        .sortedWith(compareBy({ it.path }, { it.startLine }))
        .groupBy { it.path }
        .forEach { (path, group) ->
            out.append("\n## ").append(path).append("\n")
            group.forEach { remark ->
                number++
                out.append("\n### ").append(number).append(". ")
                    .append("lines ").append(remark.startLine + 1).append("-").append(remark.endLine + 1)
                out.appendRemarkTail(
                    remark,
                    suffix = if (remark.orphaned) " — orphaned, the line numbers are stale" else "",
                )
                out.append(codeBlock(remark)).append("\n")
            }
        }

    return out.toString()
}

/**
 * The rest of a remark's heading and its text, the part both sections write the same way: the
 * eight-character commit, then the remark text, escaped. Written once so a new field in a heading
 * is added in one place rather than two.
 *
 * What comes before differs and stays at each call site: a general remark's heading names no lines,
 * because it is about no file. [suffix] is what goes between the commit and the text, and it is a
 * parameter for the same reason: only a remark about a file can be orphaned, and the General
 * section deliberately never says that word — a general remark is a considered thought, not a
 * broken one. The quoted code block a file remark ends with stays at its call site too.
 */
private fun StringBuilder.appendRemarkTail(remark: RenderedRemark, suffix: String = "") {
    remark.commit?.let { append(" — commit ").append(it.take(8)) }
    append(suffix)
    append("\n\n").append(escapeMarkdown(remark.text.trim())).append("\n\n")
}

/**
 * Appended under the header on every copy.
 *
 * Not part of DEFAULT_PROMPT_HEADER, and that is the whole point. The header is editable in
 * settings, so anything living only inside it is gone the moment somebody rewrites it — and the
 * document would keep printing things with nothing left to say what they mean. This is part of the
 * rendered document instead, so it survives any header.
 */
const val PROMPT_NOTES: String = """
A remark may carry "commit <sha>". That is the revision the author was reading when they wrote
it. For a remark marked orphaned, comparing the file against that revision is the fastest way to
find where its code went.

Inside a quoted code block, ⟦ and ⟧ mark exactly where a remark was written about only part of a
line, or about a range that starts or ends partway through a line. Everything between ⟦ and ⟧ is
the selection; a marker may fall on a different quoted line than its pair when the selection spans
several lines. Neither character is ever part of the file: they exist only in this prompt to show
you the selection, and must never appear in any edit you write back.
"""

/**
 * The remark text is written into the document as prose, outside any fence, and it is free-form:
 * Shift+Enter in the input popup makes several lines ordinary, so pasting a snippet into it is the
 * obvious thing to do. A line of three backticks there would open a fence that never closes, and
 * every remark listed after it — headings, line numbers, ">" markers and all — would be read as
 * code. A line starting with "#" forges a file or remark heading and can move a remark under the
 * wrong file. A line of three dashes turns the text above it into a heading.
 *
 * A backslash rather than four spaces of indent: "\```", "\#" and "\---" are plain CommonMark
 * escapes, and the raw text a model reads keeps its characters where they were written.
 */
private fun escapeMarkdown(text: String): String =
    text.lines().joinToString("\n") { line ->
        if (!STRUCTURE_LINE.containsMatchIn(line)) line
        else line.takeWhile { it == ' ' }.let { indent -> "$indent\\${line.removePrefix(indent)}" }
    }

/**
 * A thematic break needs three dashes, but a SETEXT heading underline needs only one character: a
 * line of "=" under a paragraph makes an H1 and a line of "-" makes an H2. The remark text is prose
 * in the document, so the line above is always a paragraph — which is exactly the setup a setext
 * underline needs. Hence "one or more" and both characters, not "three or more dashes".
 *
 * A bullet like "- item" does not match: the whole line after the optional indent has to be dashes.
 */
private val STRUCTURE_LINE = Regex("""^ {0,3}(`{3,}|~{3,}|#{1,6}(\s|$)|[-=]+\s*$)""")

/**
 * Wrap the exact sub-line selection inside a quoted line, in place. U+27E6/U+27E7 — mathematical
 * white square brackets — rather than plain "[" and "]" or quotes: both of those turn up in real
 * code and prose constantly, which would make an escaped ⟦ or ⟧ indistinguishable from a real one
 * the moment a remark's own line happened to contain either. These two do not occur in ordinary
 * source or English text, so a model reading the prompt can tell the marker apart from the file's
 * own content without needing to escape anything — and PROMPT_NOTES tells it, explicitly,
 * that the markers are prompt furniture and must never be copied into an edit.
 */
private const val SELECTION_START = "⟦"
private const val SELECTION_END = "⟧"

/**
 * True when [remark]'s columns describe a real, in-bounds sub-line range: a real range, by
 * [hasSubLineRange], which is the one place that rule lives — across lines it deliberately does not
 * order the two columns against each other, since each is an offset into its own line; not orphaned
 * (an orphan's line numbers no longer point at real code, so there is nothing to mark inside); and
 * both the start line and the end line are present in [RenderedRemark.code] with the stored columns
 * still inside their current length.
 *
 * The column pair is stored once and never refreshed, so it can go as stale as the anchor itself:
 * the line may have been edited since. Checked here, once, rather than separately in every line
 * [withSelectionMarkers] touches, so a stale column falls back to plain lines everywhere instead of
 * marking one boundary and silently dropping the other.
 */
private fun markersValid(remark: RenderedRemark): Boolean {
    if (remark.orphaned) return false
    if (!hasSubLineRange(remark.startLine, remark.endLine, remark.startColumn, remark.endColumn)) {
        return false
    }
    val startText = remark.code.getOrNull(remark.startLine - remark.codeStartLine) ?: return false
    val endText = remark.code.getOrNull(remark.endLine - remark.codeStartLine) ?: return false
    if (remark.startColumn > startText.length) return false
    if (remark.endColumn > endText.length) return false
    return true
}

/**
 * Inserts ⟦/⟧ into [line] (the quoted text for line [number]) at the stored columns, when [number]
 * is the selection's start line, end line, or both. Any other line comes back unchanged. Only
 * called once [markersValid] has confirmed both columns are in bounds for their own line.
 */
private fun withSelectionMarkers(remark: RenderedRemark, number: Int, line: String): String = when {
    number == remark.startLine && number == remark.endLine ->
        line.substring(0, remark.startColumn) + SELECTION_START +
            line.substring(remark.startColumn, remark.endColumn) + SELECTION_END +
            line.substring(remark.endColumn)
    number == remark.startLine -> line.substring(0, remark.startColumn) + SELECTION_START + line.substring(remark.startColumn)
    number == remark.endLine -> line.substring(0, remark.endColumn) + SELECTION_END + line.substring(remark.endColumn)
    else -> line
}

/**
 * The anchored lines, marked with ">", plus whatever context came with them. The fence is tagged
 * "text" rather than a real language: the line-number gutter breaks syntax highlighting anyway,
 * and a wrong language tag reads worse than none.
 */
private fun codeBlock(remark: RenderedRemark): String {
    if (remark.code.isEmpty()) return blockWithoutCode(remark)

    val fence = fenceFor(remark.code)
    val lastNumber = remark.codeStartLine + remark.code.size
    val width = lastNumber.toString().length
    val markers = markersValid(remark)
    val body = remark.code.mapIndexed { index, line ->
        val number = remark.codeStartLine + index
        val marker = if (number in remark.startLine..remark.endLine) ">" else " "
        val text = if (markers) withSelectionMarkers(remark, number, line) else line
        "$marker ${number.plus(1).toString().padStart(width)} | $text"
    }
    return "${fence}text\n" + body.joinToString("\n") + "\n$fence\n"
}

/**
 * An orphan has no code of its own to quote: its stored line numbers no longer point at it, and
 * whatever drifted into that position is not the remark's code.
 *
 * What it does still carry is the context captured when it was written, the lines just above and
 * just below. Those are quoted instead, under words of their own. The prompt header sends the
 * reader looking for the code by reading rather than by trusting the numbers, so without them an
 * orphan would arrive with a path, numbers it is told to ignore, and nothing to search for. No ">"
 * markers and no line numbers on these lines: neither would be true, and ">" means "the lines the
 * remark points at" everywhere else in the document.
 *
 * A file that could not be read is a different case and gets different words. So is an orphan
 * stored without any context, which older remarks and a one-line file can both be.
 */
private fun blockWithoutCode(remark: RenderedRemark): String {
    val captured = remark.capturedBefore + remark.capturedAfter
    if (!remark.orphaned || captured.isEmpty()) {
        val why =
            if (remark.orphaned) "(the code this remark points at could not be found in the file)"
            else "(the file could not be read)"
        val fence = fenceFor(emptyList())
        return "${fence}text\n$why\n$fence\n"
    }

    val fence = fenceFor(captured)
    val body = remark.capturedBefore + MISSING_LINES + remark.capturedAfter
    return "The code this remark points at could not be found in the file. These are the lines " +
        "that sat around it when the remark was written — search for them to find the code:\n\n" +
        "${fence}text\n" + body.joinToString("\n") + "\n$fence\n"
}

/** Stands in for the remark's own lines, which are not stored: only their hash and their context. */
private const val MISSING_LINES = "... the lines this remark points at were here ..."

private fun fenceFor(lines: List<String>): String =
    "`".repeat(maxOf(3, longestBacktickRun(lines) + 1))

/**
 * How long the longest run of backticks inside the quoted code is, so the fence can be made longer
 * than it. Source that itself holds a fenced block — a .md file, a doc comment with an example —
 * would otherwise close the fence early and the rest of the prompt would be read as prose.
 */
private fun longestBacktickRun(code: List<String>): Int =
    code.maxOfOrNull { line -> BACKTICKS.findAll(line).maxOfOrNull { it.value.length } ?: 0 } ?: 0

private val BACKTICKS = Regex("`+")
