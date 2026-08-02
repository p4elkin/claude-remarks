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
    val text: String,
    val orphaned: Boolean,
    /** The 0-based line number that code[0] came from. */
    val codeStartLine: Int,
    val code: List<String>,
)

fun renderPrompt(header: String, remarks: List<RenderedRemark>): String {
    if (remarks.isEmpty()) return header.trimEnd() + "\n"

    val out = StringBuilder(header.trimEnd()).append("\n\n---\n")
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
                if (remark.orphaned) out.append(" — orphaned, the line numbers are stale")
                out.append("\n\n").append(remark.text.trim()).append("\n\n")
                out.append(codeBlock(remark)).append("\n")
            }
        }

    return out.toString()
}

/**
 * The anchored lines, marked with ">", plus whatever context came with them. The fence is tagged
 * "text" rather than a real language: the line-number gutter breaks syntax highlighting anyway,
 * and a wrong language tag reads worse than none.
 *
 * An orphan arrives here with no code, on purpose: its stored line numbers no longer point at its
 * own code, so there is nothing honest to quote. Saying that is not the same as saying the file
 * could not be read, so the two cases get different words.
 */
private fun codeBlock(remark: RenderedRemark): String {
    val fence = "`".repeat(maxOf(3, longestBacktickRun(remark.code) + 1))
    if (remark.code.isEmpty()) {
        val why =
            if (remark.orphaned) "(the code this remark points at could not be found in the file)"
            else "(the file could not be read)"
        return "${fence}text\n$why\n$fence\n"
    }

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
 * How long the longest run of backticks inside the quoted code is, so the fence can be made longer
 * than it. Source that itself holds a fenced block — a .md file, a doc comment with an example —
 * would otherwise close the fence early and the rest of the prompt would be read as prose.
 */
private fun longestBacktickRun(code: List<String>): Int =
    code.maxOfOrNull { line -> BACKTICKS.findAll(line).maxOfOrNull { it.value.length } ?: 0 } ?: 0

private val BACKTICKS = Regex("`+")
