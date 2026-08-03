package dev.sasha.clauderemarks.store

import com.intellij.openapi.application.PathManager
import com.intellij.openapi.project.Project
import dev.sasha.clauderemarks.model.RemarkState
import dev.sasha.clauderemarks.model.label
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
 * Appends [remarks] to [file] and returns how many were written.
 *
 * Throws IOException, and the caller must not delete anything when it does. An archive that failed
 * to write, followed by a delete that succeeded, is a remark lost silently — the one thing this
 * plugin promises never to do.
 */
fun appendToHistory(file: Path, remarks: List<RemarkState>): Int {
    if (remarks.isEmpty()) return 0
    file.parent?.let { Files.createDirectories(it) }
    Files.writeString(
        file,
        renderHistory(remarks),
        StandardCharsets.UTF_8,
        StandardOpenOption.CREATE,
        StandardOpenOption.APPEND,
    )
    return remarks.size
}

/**
 * What was STORED about each remark, not what it resolves to now. By the time somebody reads this
 * the code has moved, and the file may not exist at all.
 *
 * Internal and pure, so it is tested without touching a disk.
 */
internal fun renderHistory(
    remarks: List<RemarkState>,
    now: Long = System.currentTimeMillis(),
): String = buildString {
    append("\n## cleared ").append(WHEN.format(Instant.ofEpochMilli(now))).append("\n")
    remarks.forEach { remark ->
        append("\n- **").append(remark.path.orEmpty()).append("** lines ")
            .append(remark.startLine + 1).append("-").append(remark.endLine + 1)
        remark.tag?.let { append(" — ").append(it.label) }
        append(" — ").append(remark.severity.label)
        // Flattened, because the heading is one line and the bucket is the only free-form field on
        // it. setRemarkBucket trims the ends but does not touch an inner newline, and a newline here
        // would put whatever follows it at document level, outside the indent that protects the text
        // below. Not reachable from the current single-line chooser; this closes the asymmetry.
        remark.bucket?.let { append(" — bucket ").append(it.lines().joinToString(" ")) }
        remark.commit?.let { append(" — commit ").append(it.take(8)) }
        append("\n\n")
        // Indented, so a remark holding a markdown heading or a fence cannot restructure the
        // document around it. The same problem the prompt renderer solves with backslash escapes;
        // here nothing has to survive as prose, so an indent is enough.
        remark.text.orEmpty().lines().forEach { append("      ").append(it).append("\n") }
    }
}
