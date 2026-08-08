package dev.sasha.clauderemarks.skill

import dev.sasha.clauderemarks.review.atomicWriteString
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path

/**
 * The pure half of installing the skill into a detected harness: detecting harnesses, stamping and
 * reading the version, and copying the skill's resource files out.
 *
 * Plain `java.nio` and plain strings throughout, the same argument `anchor/` and
 * `render/PromptRenderer.kt` already make for their own files: no fixture, tests in milliseconds.
 * [installSkill] takes its resource reader as a parameter for the same reason — see its own KDoc.
 */
object SkillInstall {

    private const val STAMP_KEY = "claude-remarks-plugin-version"
    private val STAMP_LINE_PATTERN = Regex("""^#\s*$STAMP_KEY:\s*(\S+)""")

    /** A byte-order mark, which a file edited on Windows can carry in front of its first line. */
    private const val BOM = '﻿'

    /**
     * The one file that carries the version stamp, and the one [installSkill] writes **last**. See
     * that function's own KDoc for why the order matters.
     */
    private const val SKILL_MD = "SKILL.md"

    /**
     * The files that make up the skill, in one place because **nothing enumerates the
     * directory**: listing jar entries through a classloader is not something to rely on. Adding
     * another file to the skill later means adding its name here too — forgetting is silent, because
     * the file simply does not get installed and nothing reports it.
     *
     * This is the list of names, not the order they are written in. [installSkill] deliberately
     * writes `SKILL.md` last, whatever order this list happens to be in.
     */
    val SKILL_FILES: List<String> =
        listOf(
            SKILL_MD,
            "watch-remarks.sh",
            "remote-config.sh",
            "listen-without-monitor.md",
            "watcher-background.md",
            "remote-and-trouble.md",
        )

    /**
     * The path segments this code appends below a person's home directory to reach the installed
     * skill: `.claude`, then `skills`, then `claude-remarks`.
     *
     * One list, because two things read it and they must never disagree — the target
     * [detectHarnesses] hands back, and the set of components [installSkill] refuses to write
     * through when any one of them turns out to be a symlink.
     */
    private val CLAUDE_SKILL_SEGMENTS = listOf(".claude", "skills", "claude-remarks")

    /**
     * Where Claude Code's copy of this skill goes under [home]. The one place this path is spelled
     * out — the settings row and the notification both ask for it here rather than each writing the
     * same three segments again.
     */
    fun claudeSkillDir(home: Path): Path =
        CLAUDE_SKILL_SEGMENTS.fold(home) { path, segment -> path.resolve(segment) }

    /**
     * The absolute resource path for one of the skill's files, leading slash and all.
     *
     * Internal rather than private so [SkillResourceTest] can ask the classpath for the very same
     * string production would use to read the resource — the same argument
     * `ui/RemarkIcons.kt`'s `resourcePath` makes for the same reason: a copy of the path written a
     * second time in the test would leave a typo here invisible, and every file would still sit
     * under the path the test checked.
     */
    internal fun resourcePath(name: String): String = "/dev/sasha/clauderemarks/skill/$name"

    /**
     * Inserts `# claude-remarks-plugin-version: <version>` as line 2 of [text], right after the
     * opening `---`, or as a brand new first line when [text] does not start with one.
     *
     * **Why line 2 and nowhere else.** `SKILL.md`'s frontmatter has `description:` as a `>` block
     * scalar. A `#` line placed inside a block scalar is content, not a comment — it would end up as
     * text inside the description the harness matches the skill on. Line 2 is before any scalar in
     * the frontmatter starts, which is what keeps this safe no matter what the rest of the file
     * contains. `SkillInstallTest` pins this against the real `SKILL.md`, so it fails if someone later
     * moves the insertion point further down "to tidy it up."
     *
     * ⚠️ **A byte-order mark and CRLF line endings both have to be tolerated, and both are
     * preserved.** A comparison of the first line against the literal `"---"` misses `"---\r"` and
     * `"﻿---"`, and the stamp would then be placed *in front of* line 1 — the frontmatter would
     * never open, Claude Code would silently not register the skill, and [stampedVersionOf] would
     * still read the version back, so the settings row would report it up to date. This repository
     * carries no `.gitattributes`, so a clone made with Git for Windows' default `core.autocrlf=true`
     * produces exactly that file. The mark and the line ending are put back rather than dropped:
     * rewriting a CRLF file as LF as a side effect of stamping it is not this function's business.
     */
    fun stampVersion(text: String, version: String): String {
        val stamp = "# $STAMP_KEY: $version"
        val mark = if (text.startsWith(BOM)) BOM.toString() else ""
        val body = text.removePrefix(mark)
        val firstLineEnd = body.indexOf('\n')
        val rawFirstLine = if (firstLineEnd == -1) body else body.substring(0, firstLineEnd)
        val eol = if (rawFirstLine.endsWith("\r")) "\r\n" else "\n"
        val firstLine = rawFirstLine.removeSuffix("\r")
        return if (firstLine == "---") {
            if (firstLineEnd == -1) {
                mark + body + eol + stamp
            } else {
                mark + body.substring(0, firstLineEnd + 1) + stamp + eol + body.substring(firstLineEnd + 1)
            }
        } else {
            mark + stamp + eol + body
        }
    }

    /**
     * The version stamped into [text], or null when there is none.
     *
     * Scans at most the first five lines — the stamp is always on line 2, and five is generous
     * headroom without reading a whole 96 KB file just to answer this. Never throws: a line that
     * looks like a stamp but does not parse reads exactly the same as no stamp at all, which is what
     * lets a caller tell "installed, version unknown" apart from "not installed" without ever
     * guessing a version.
     */
    fun stampedVersionOf(text: String): String? =
        text.lineSequence().take(5).firstNotNullOfOrNull { line ->
            STAMP_LINE_PATTERN.find(line)?.groupValues?.get(1)
        }

    /**
     * One detected harness: a display name, whether the plugin can install into it, and where the
     * skill would go if it can. [targetDir] is null for a harness this phase lists but does not write
     * to — Codex and Gemini, whose own layouts have not been read from their own documentation yet. A
     * guessed path would write a file nobody reads, and nothing about that failure would be visible,
     * which is worse than writing nothing at all.
     */
    data class HarnessInfo(
        val displayName: String,
        val installable: Boolean,
        val targetDir: Path?,
    )

    /**
     * Every harness actually found under [home]. Only existing directories are found — **a harness
     * directory is never created here**, because creating one would be the plugin guessing that a
     * tool is wanted. The directories the plugin does create are the skill's own target directory
     * and, when it is not there yet, the `skills/` directory above it — both **inside a `~/.claude`
     * that already exists**, which is the install itself rather than a guess. That happens in
     * [installSkill], not here.
     *
     * ⚠️ **Claude Code is keyed on `~/.claude`, not on `~/.claude/skills`.** `skills/` is created
     * when a first skill is added, so somebody who has used Claude Code but never added a personal
     * skill has `~/.claude` — settings, projects, history — and no `skills/` at all. Keying on
     * `skills/` made the whole feature invisible to exactly the person it was built for: no row, no
     * button and no balloon. The target below still points inside `skills/`, and
     * `atomicWriteString` creates the directories it needs on the way.
     */
    fun detectHarnesses(home: Path): List<HarnessInfo> {
        val found = mutableListOf<HarnessInfo>()
        if (Files.exists(home.resolve(CLAUDE_SKILL_SEGMENTS.first()))) {
            found += HarnessInfo("Claude Code", true, claudeSkillDir(home))
        }
        if (Files.exists(home.resolve(".codex"))) {
            found += HarnessInfo("Codex", false, null)
        }
        if (Files.exists(home.resolve(".gemini"))) {
            found += HarnessInfo("Gemini", false, null)
        }
        return found
    }

    /**
     * What is at a harness's skill directory. Always one of three answers, and never an exception.
     */
    sealed class SkillPresence {
        /** Nothing is at the directory: not installed. */
        data object Missing : SkillPresence()

        /**
         * The directory is a symlink. Its own answer rather than folded into [Present], because the
         * two lead to opposite behaviour: a real, installed copy can be reinstalled over, while a
         * symlink — the development checkout on this machine — must never be written through.
         */
        data object Symlink : SkillPresence()

        /**
         * A real directory holding the skill. [version] is the stamp read back from its `SKILL.md`,
         * or null when there is none — the ordinary case for a copy installed by hand before this
         * phase existed, and for the bare checkout the development symlink points at, which carries
         * no stamp because the stamp is written at install time, not at checkout time.
         */
        data class Present(val version: String?) : SkillPresence()
    }

    /**
     * Reads what is at [dir] without ever throwing. A symlink is [SkillPresence.Symlink] regardless
     * of what it points at or whether that target even exists. Otherwise this reads `SKILL.md` inside
     * [dir]: missing or unreadable is [SkillPresence.Missing], and a readable file is
     * [SkillPresence.Present], carrying whatever [stampedVersionOf] finds — which is null, not a
     * thrown exception, when the file exists but its stamp line does not parse.
     */
    fun skillPresence(dir: Path): SkillPresence {
        if (Files.isSymbolicLink(dir)) return SkillPresence.Symlink
        val text = try {
            Files.readString(dir.resolve("SKILL.md"))
        } catch (e: IOException) {
            null
        }
        return if (text == null) SkillPresence.Missing else SkillPresence.Present(stampedVersionOf(text))
    }

    /**
     * Copies the skill's [SKILL_FILES] into [targetDir], stamping the copied `SKILL.md` with
     * [version] and making every `.sh` file executable. Returns null on success, or a sentence
     * describing the problem — the same shape
     * `dev.sasha.clauderemarks.store.remarkTargetProblem`/`fileTargetProblem` already use for "why
     * not, in words a person can read, or null when it can."
     *
     * [readResource] reads one of [SKILL_FILES] by name and hands back its bytes, or null when it
     * cannot be found. It is a parameter rather than a call inside this file, which is what keeps
     * this file free of the classloader and lets a test feed fake contents without touching the
     * classpath at all. The one real call site passes
     * `SkillInstall::class.java.getResourceAsStream(resourcePath(name))`.
     *
     * **Copies, never symlinks — deliberately the opposite of the development setup.** An installed
     * plugin lives under a versioned path: a symlink into it dies on the next plugin update and
     * leaves a skill entry that points at nothing. The development symlink on this machine is right
     * for a checkout and wrong for an install; the two being opposite on purpose is exactly why this
     * reason is written here rather than only in a plan file.
     *
     * **Refuses outright when any directory this code appends below the home directory, or any of
     * the skill's files inside [targetDir], is a symlink**, before writing anything at all. On this
     * machine `~/.claude/skills/claude-remarks` is a symlink into the checkout, and writing through
     * it would silently overwrite the plugin's own source files with stamped copies.
     *
     * ⚠️ **Checking only [targetDir] is not enough, and a blanket `toRealPath()` comparison is
     * wrong.** A dotfiles checkout can make `~/.claude` or `~/.claude/skills` itself the symlink; the
     * leaf is then an ordinary directory, the leaf check passes, and `Files.createDirectories` plus
     * the rename inside `atomicWriteString` follow the ancestor link and write into the far end —
     * exactly what this refusal exists to prevent. The check is on [appendedDirectories], the three
     * components this code creates, and never on the home directory or anything above it: on macOS
     * `/tmp` is a symlink to `/private/tmp` and a temporary directory resolves under
     * `/private/var/folders/…`, so "refuse when the resolved path differs from the lexical one"
     * would refuse every temporary directory and any home directory sitting under a symlinked mount.
     *
     * **`SKILL.md` is written last, and it is the file carrying the stamp.** The two scripts and
     * every reference file go first, then the scripts' executable bits, then the stamped
     * `SKILL.md`. So a stamp is only ever on
     * disk once the install really finished. ⚠️ With `SKILL.md` written first, a later write or a
     * refused `setExecutable` left a stamped `SKILL.md` beside a missing or non-executable script:
     * [skillPresence] then read the bundled version back, the settings row said "up to date" and
     * the notification never fired again, while a real session answered that `watch-remarks.sh` was
     * not found.
     */
    fun installSkill(
        targetDir: Path,
        version: String,
        readResource: (name: String) -> InputStream?,
    ): String? {
        for (dir in appendedDirectories(targetDir)) {
            if (Files.isSymbolicLink(dir)) {
                return developmentSymlinkProblem(dir)
            }
        }
        for (name in SKILL_FILES) {
            val file = targetDir.resolve(name)
            if (Files.isSymbolicLink(file)) {
                return developmentSymlinkProblem(file)
            }
        }

        val others = SKILL_FILES.filter { it != SKILL_MD }
        try {
            for (name in others) {
                val bytes = readResource(name)?.use { it.readBytes() }
                    ?: return "$name could not be read from the plugin's own resources."
                atomicWriteString(targetDir.resolve(name), String(bytes, Charsets.UTF_8))
            }
        } catch (e: IOException) {
            return "the skill could not be written to $targetDir: ${e.message}"
        }

        for (name in others.filter { it.endsWith(".sh") }) {
            val file = targetDir.resolve(name).toFile()
            if (!file.setExecutable(true, true)) {
                return "$name at $targetDir could not be made executable."
            }
        }

        try {
            val bytes = readResource(SKILL_MD)?.use { it.readBytes() }
                ?: return "$SKILL_MD could not be read from the plugin's own resources."
            val stamped = stampVersion(String(bytes, Charsets.UTF_8), version)
            atomicWriteString(targetDir.resolve(SKILL_MD), stamped)
        } catch (e: IOException) {
            return "the skill could not be written to $targetDir: ${e.message}"
        }

        return null
    }

    /**
     * [targetDir] and every directory above it that this code may create — `claude-remarks`,
     * `skills` and `.claude`, one per segment in [CLAUDE_SKILL_SEGMENTS]. It stops there on purpose:
     * the home directory and everything above it belong to the person, and refusing an install
     * because one of *those* is a symlink would refuse a perfectly ordinary machine. See
     * [installSkill]'s own KDoc for the whole argument.
     */
    private fun appendedDirectories(targetDir: Path): List<Path> =
        generateSequence(targetDir) { it.parent }.take(CLAUDE_SKILL_SEGMENTS.size).toList()

    private fun developmentSymlinkProblem(path: Path): String =
        "$path looks like a development install (a symlink) and has to be removed before the " +
            "skill can be installed there."
}
