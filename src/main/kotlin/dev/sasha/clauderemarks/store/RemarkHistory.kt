package dev.sasha.clauderemarks.store

import com.intellij.openapi.application.PathManager
import com.intellij.openapi.project.Project
import dev.sasha.clauderemarks.anchor.positionLabel
import dev.sasha.clauderemarks.model.AnswerState
import dev.sasha.clauderemarks.model.RemarkState
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Where cleared remarks go instead of being deleted.
 *
 * A markdown file in the IDE configuration directory. Not a second list in workspace.xml, which
 * would grow without bound in a file the tool window resolves on every change. Not a file beside
 * the project, which is exactly what the rule against remarks entering version control exists to
 * prevent. The configuration directory cannot be committed by accident, which is the whole point.
 *
 * Not a persisted collection either, and that is a deliberate trade. A collection would be
 * structured enough to restore a remark from, and would cost a second service copying RemarkStore's
 * whole thread-safety shape, another @get:XCollection with its silent-data-loss trap, and a browse
 * window before anybody could read one archived remark. A text file is readable, greppable and
 * pasteable today. What is given up: putting a cleared remark back is copy and paste, not a button.
 *
 * The file does not travel with the project and is lost if the IDE configuration is wiped. That is
 * accepted: it is a record of past reading passes, not project data.
 */

private val WHEN = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault())

/**
 * One file per project, named so a person can find it, keyed so two projects cannot collide.
 *
 * safeName wraps the WHOLE basename, not just the project name. Both halves come from the project
 * rather than from us: `locationHash` is plain hex for a directory-based project, but a file-based
 * `.ipr` project prefixes it with the project name, so sanitizing one half and interpolating the
 * other raw would leave the same untrusted characters in the path by a different route.
 */
fun historyFile(project: Project): Path =
    PathManager.getConfigDir()
        .resolve("claude-remarks")
        .resolve(safeName("${project.name}-${project.locationHash}") + ".md")

/** Internal, so one assertion can cover it: a project called "My App / v2" must not become a path. */
internal fun safeName(name: String): String = name.replace(Regex("[^A-Za-z0-9._-]"), "_")

/**
 * Appends [remarks] and [answers] to [file] and returns how many records were written in all.
 *
 * Throws IOException, and the caller must not delete anything when it does. An archive that failed
 * to write, followed by a delete that succeeded, is a remark lost silently — the one thing this
 * plugin promises never to do.
 *
 * [answers] defaults to none, because only one of the two callers has any: Clear All takes remarks
 * and answers together, Clear Handed Over takes remarks alone. See `store/RemarkEdits.kt`'s
 * `clearHandedOverRemarks` for why an answer is never "handed over".
 */
fun appendToHistory(
    file: Path,
    remarks: List<RemarkState>,
    answers: List<AnswerState> = emptyList(),
): Int {
    if (remarks.isEmpty() && answers.isEmpty()) return 0
    file.parent?.let { Files.createDirectories(it) }
    Files.writeString(
        file,
        renderHistory(remarks, answers),
        StandardCharsets.UTF_8,
        StandardOpenOption.CREATE,
        StandardOpenOption.APPEND,
    )
    return remarks.size + answers.size
}

/**
 * What was STORED about each remark, not what it resolves to now. By the time somebody reads this
 * the code has moved, and the file may not exist at all.
 *
 * Internal and pure, so it is tested without touching a disk.
 */
internal fun renderHistory(
    remarks: List<RemarkState>,
    answers: List<AnswerState> = emptyList(),
    now: Long = System.currentTimeMillis(),
): String = buildString {
    append("\n## cleared ").append(WHEN.format(Instant.ofEpochMilli(now))).append("\n")
    remarks.forEach { remark ->
        append("\n- ")
        appendPosition(
            isAboutNoFile(remark),
            remark.path,
            remark.startLine,
            remark.endLine,
            remark.startColumn,
            remark.endColumn,
        )
        // Flattened, because the heading is one line and the bucket is the only free-form field on
        // it. setRemarkBucket trims the ends but does not touch an inner newline, and a newline here
        // would put whatever follows it at document level, outside the indent that protects the text
        // below. Not reachable from the current single-line chooser; this closes the asymmetry.
        remark.bucket?.let { append(" — bucket ").append(it.lines().joinToString(" ")) }
        remark.commit?.let { append(" — commit ").append(it.take(8)) }
        append("\n\n")
        // The phrase, when there is one, is source text too and gets the same indent as the remark
        // text, ahead of it.
        remark.phrase?.let { appendIndented(it) }
        appendIndented(remark.text.orEmpty())
    }
    // Under the same dated heading, in a subsection of its own, because a reading pass being cleared
    // is one event and the archive should read as one entry for it. Only Clear All gets here — see
    // appendToHistory above — so an empty list is the ordinary case and prints no subsection at all.
    if (answers.isNotEmpty()) {
        append("\n### answers\n")
        answers.forEach { answer ->
            append("\n- ")
            appendPosition(
                isAboutNoFile(answer),
                answer.path,
                answer.startLine,
                answer.endLine,
                answer.startColumn,
                answer.endColumn,
            )
            answer.commit?.let { append(" — commit ").append(it.take(8)) }
            append("\n\n")
            // The question first, then the body, both indented, with a plain label line between them
            // so the two blocks can be told apart. The label is our own literal text and the two
            // blocks are not, which is the whole reason the label is the only thing written flat.
            appendIndented(answer.question.orEmpty())
            append("\n  answered:\n\n")
            appendIndented(answer.markdown.orEmpty())
        }
    }
}

/**
 * The heading's position, written the same way for a remark and for an answer.
 *
 * "(general)" is the same word `render/PromptRenderer.kt`'s "## General" heading and
 * `ui/RemarksTree.kt`'s GENERAL_KEY group use for a record about no file: there is no file and no
 * line range, so [positionLabel] has nothing to describe.
 *
 * Otherwise the same [positionLabel] the tree row draws, shared rather than copied: a history entry
 * and a tree row describing one record must read the same way. Read straight off what was STORED,
 * not off a resolve — `renderHistory` never resolves anything, by its own KDoc above — so unlike the
 * tree there is no AnchorResult and no orphaned case to skip. A history entry's stored columns are
 * the only columns it has ever had.
 */
private fun StringBuilder.appendPosition(
    aboutNoFile: Boolean,
    path: String?,
    startLine: Int,
    endLine: Int,
    startColumn: Int,
    endColumn: Int,
) {
    if (aboutNoFile) {
        append("**(general)**")
    } else {
        append("**").append(path).append("** lines ")
            .append(positionLabel(startLine, endLine, startColumn, endColumn))
    }
}

/**
 * Indented, so a record holding a markdown heading or a fence cannot restructure the document around
 * it. The same problem the prompt renderer solves with backslash escapes; here nothing has to survive
 * as prose, so an indent is enough. An answer holds a heading or a fence far more often than a remark
 * does, which is why this is the one rule both halves of the file share.
 */
private fun StringBuilder.appendIndented(text: String) {
    text.lines().forEach { append("      ").append(it).append("\n") }
}

