package dev.sasha.clauderemarks.render

/**
 * Turns pending remarks into one markdown document.
 *
 * No platform imports, on purpose: this is where the only real logic in phase 4 lives, and
 * keeping it free of the platform is what makes its tests run in milliseconds.
 */

/** One remark, with the code already sliced out of its file. Line numbers are 0-based. */
data class RenderedRemark(
    val path: String,
    val startLine: Int,
    val endLine: Int,
    /** "bug" | "question" | "refactor" | "note", already lowercase, or null. */
    val tag: String?,
    /** "vibe" | "suggestion" | "should" | "must", already lowercase. Never null: every remark has
     *  a level, defaulted when it was written. */
    val severity: String,
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
        .append(SEVERITY_SCALE_NOTE.trim())
        .append("\n\n---\n")
    var number = 0

    remarks
        .sortedWith(compareBy({ it.path }, { it.startLine }))
        .groupBy { it.path }
        .forEach { (path, group) ->
            out.append("\n## ").append(path).append("\n")
            group.forEach { remark ->
                number++
                out.append("\n### ").append(number).append(". ")
                    .append("lines ").append(remark.startLine + 1).append("-").append(remark.endLine + 1)
                remark.tag?.let { out.append(" — ").append(it) }
                out.append(" — ").append(remark.severity)
                if (remark.orphaned) out.append(" — orphaned, the line numbers are stale")
                out.append("\n\n").append(escapeMarkdown(remark.text.trim())).append("\n\n")
                out.append(codeBlock(remark)).append("\n")
            }
        }

    return out.toString()
}

/**
 * Appended under the header on every copy.
 *
 * Not part of DEFAULT_PROMPT_HEADER, and that is the whole point. The header is editable in
 * settings, so anything living only inside it is gone the moment somebody rewrites it — and the
 * levels would keep being printed with nothing left to say what they mean. This is part of the
 * rendered document instead, so it survives any header.
 */
const val SEVERITY_SCALE_NOTE: String = """
Each remark carries one of four levels, saying how strongly to act on it:

- must — do it, whatever it costs.
- should — do it unless there is a concrete reason not to. If you skip it, say why.
- suggestion — do it if it is cheap and does not fight the surrounding code.
- vibe — an idle thought. You may decline it. Say in one line whether you took it.

A remark may also carry "commit <sha>". That is the revision the author was reading when they wrote
it. For a remark marked orphaned, comparing the file against that revision is the fastest way to
find where its code went.
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

private val STRUCTURE_LINE = Regex("""^ {0,3}(`{3,}|~{3,}|#{1,6}(\s|$)|-{3,}\s*$)""")

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
    val body = remark.code.mapIndexed { index, line ->
        val number = remark.codeStartLine + index
        val marker = if (number in remark.startLine..remark.endLine) ">" else " "
        "$marker ${number.plus(1).toString().padStart(width)} | $line"
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
