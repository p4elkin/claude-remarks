package dev.sasha.clauderemarks.skill

import dev.sasha.clauderemarks.review.atomicWriteString
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path

/**
 * The pure half of installing the skill into a detected harness: detecting harnesses, stamping and
 * reading the version, and copying the three resource files out.
 *
 * Plain `java.nio` and plain strings throughout, the same argument `anchor/` and
 * `render/PromptRenderer.kt` already make for their own files: no fixture, tests in milliseconds.
 * [installSkill] takes its resource reader as a parameter for the same reason — see its own KDoc.
 */
object SkillInstall {

    private const val STAMP_KEY = "claude-remarks-plugin-version"
    private val STAMP_LINE_PATTERN = Regex("""^#\s*$STAMP_KEY:\s*(\S+)""")

    /**
     * The three files that make up the skill, in one place because **nothing enumerates the
     * directory**: listing jar entries through a classloader is not something to rely on. Adding a
     * fourth file to the skill later means adding its name here too — forgetting is silent, because
     * the file simply does not get installed and nothing reports it.
     */
    val SKILL_FILES: List<String> = listOf("SKILL.md", "watch-remarks.sh", "remote-config.sh")

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
     */
    fun stampVersion(text: String, version: String): String {
        val stamp = "# $STAMP_KEY: $version"
        val firstLineEnd = text.indexOf('\n')
        val firstLine = if (firstLineEnd == -1) text else text.substring(0, firstLineEnd)
        return if (firstLine == "---") {
            if (firstLineEnd == -1) {
                "$text\n$stamp"
            } else {
                text.substring(0, firstLineEnd + 1) + stamp + "\n" + text.substring(firstLineEnd + 1)
            }
        } else {
            "$stamp\n$text"
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
     * tool is wanted. The one directory this phase ever creates is the skill's own target directory,
     * inside a harness directory that already exists, and that happens in [installSkill], not here.
     */
    fun detectHarnesses(home: Path): List<HarnessInfo> {
        val found = mutableListOf<HarnessInfo>()
        val claudeSkills = home.resolve(".claude").resolve("skills")
        if (Files.exists(claudeSkills)) {
            found += HarnessInfo("Claude Code", true, claudeSkills.resolve("claude-remarks"))
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
     * **Refuses outright when [targetDir], or any of the three files inside it, is a symlink**,
     * before writing anything at all. On this machine `~/.claude/skills/claude-remarks` is a symlink
     * into the checkout, and writing through it would silently overwrite the plugin's own source
     * files with stamped copies — this check is what stops that.
     */
    fun installSkill(
        targetDir: Path,
        version: String,
        readResource: (name: String) -> InputStream?,
    ): String? {
        if (Files.isSymbolicLink(targetDir)) {
            return developmentSymlinkProblem(targetDir)
        }
        for (name in SKILL_FILES) {
            val file = targetDir.resolve(name)
            if (Files.isSymbolicLink(file)) {
                return developmentSymlinkProblem(file)
            }
        }

        try {
            for (name in SKILL_FILES) {
                val bytes = readResource(name)?.use { it.readBytes() }
                    ?: return "$name could not be read from the plugin's own resources."
                val text = String(bytes, Charsets.UTF_8)
                val content = if (name == "SKILL.md") stampVersion(text, version) else text
                atomicWriteString(targetDir.resolve(name), content)
            }
        } catch (e: IOException) {
            return "the skill could not be written to $targetDir: ${e.message}"
        }

        for (name in SKILL_FILES.filter { it.endsWith(".sh") }) {
            val file = targetDir.resolve(name).toFile()
            if (!file.setExecutable(true, true)) {
                return "$name at $targetDir could not be made executable."
            }
        }

        return null
    }

    private fun developmentSymlinkProblem(path: Path): String =
        "$path looks like a development install (a symlink) and has to be removed before the " +
            "skill can be installed there."
}
