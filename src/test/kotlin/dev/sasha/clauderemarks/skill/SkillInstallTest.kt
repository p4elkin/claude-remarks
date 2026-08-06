package dev.sasha.clauderemarks.skill

import java.io.InputStream
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Plain JUnit: every one of `SkillInstall`'s pure functions is plain `java.nio` and plain strings,
 * so this needs no fixture. Every test writes into a temporary directory it makes itself and never
 * touches the real `~/.claude` — the rule in `.claude/rules/planning-rules.md` applies to this class
 * more than to any other in the repository, because its whole subject is writing into a person's home
 * directory.
 */
class SkillInstallTest {

    private fun fakeResource(name: String): InputStream? = when (name) {
        "SKILL.md" -> "---\nname: claude-remarks\ndescription: >\n  hi\n".byteInputStream()
        "watch-remarks.sh" -> "#!/bin/sh\necho fake watch script\n".byteInputStream()
        "remote-config.sh" -> "#!/bin/sh\necho fake remote config\n".byteInputStream()
        else -> null
    }

    // --- stampVersion -------------------------------------------------------------------------

    @Test
    fun `stampVersion inserts the stamp as line 2 when the first line is a frontmatter opener`() {
        val text = "---\nname: claude-remarks\ndescription: >\n  hi\n---\nbody\n"

        val stamped = SkillInstall.stampVersion(text, "0.12.0")
        val lines = stamped.lines()

        assertEquals("---", lines[0])
        assertEquals("# claude-remarks-plugin-version: 0.12.0", lines[1])
        assertEquals("name: claude-remarks", lines[2])
    }

    @Test
    fun `stampVersion inserts the stamp as a new first line when there is no frontmatter opener`() {
        val text = "not frontmatter\nsecond line\n"

        val stamped = SkillInstall.stampVersion(text, "0.12.0")
        val lines = stamped.lines()

        assertEquals("# claude-remarks-plugin-version: 0.12.0", lines[0])
        assertEquals("not frontmatter", lines[1])
    }

    @Test
    fun `stamping the real SKILL_md never puts the stamp inside the description block scalar`() {
        val original = SkillInstall::class.java
            .getResourceAsStream(SkillInstall.resourcePath("SKILL.md"))!!
            .use { it.readBytes().toString(Charsets.UTF_8) }

        val stamped = SkillInstall.stampVersion(original, "0.12.0")
        val lines = stamped.lines()

        assertEquals("---", lines[0])
        assertEquals("# claude-remarks-plugin-version: 0.12.0", lines[1])

        // description: is a `>` block scalar. Every line indented under it, up to the next
        // unindented line, is content — a `#` line placed inside it would become part of the
        // description text a harness matches the skill on, not a comment. Collect that block on its
        // own and confirm the stamp is nowhere inside it, which is what stops the stamp being
        // "tidied" further down later.
        val descriptionStart = lines.indexOfFirst { it.startsWith("description:") }
        assertTrue("expected a description: line in SKILL.md", descriptionStart >= 0)
        val descriptionBlock = lines.drop(descriptionStart + 1)
            .takeWhile { it.startsWith(" ") || it.isBlank() }
            .joinToString("\n")

        assertFalse(descriptionBlock.contains("claude-remarks-plugin-version"))
    }

    // --- stampedVersionOf ----------------------------------------------------------------------

    @Test
    fun `stampedVersionOf finds the version on a stamped file`() {
        val text = "---\n# claude-remarks-plugin-version: 0.12.0\nname: claude-remarks\n"

        assertEquals("0.12.0", SkillInstall.stampedVersionOf(text))
    }

    @Test
    fun `stampedVersionOf returns null for an unstamped file`() {
        val text = "---\nname: claude-remarks\ndescription: >\n  hi\n---\n"

        assertNull(SkillInstall.stampedVersionOf(text))
    }

    @Test
    fun `stampedVersionOf returns null rather than throwing on a malformed stamp line`() {
        val text = "---\n# claude-remarks-plugin-version 0.12.0\nname: claude-remarks\n"

        assertNull(SkillInstall.stampedVersionOf(text))
    }

    @Test
    fun `stampedVersionOf only looks at the first five lines`() {
        val text = (1..6).joinToString("\n") { "line $it" } +
            "\n# claude-remarks-plugin-version: 9.9.9\n"

        assertNull(SkillInstall.stampedVersionOf(text))
    }

    // --- detectHarnesses -------------------------------------------------------------------------

    @Test
    fun `no harness directories present, none are found`() {
        val home = Files.createTempDirectory("claude-remarks-home-").toRealPath()

        assertEquals(emptyList<SkillInstall.HarnessInfo>(), SkillInstall.detectHarnesses(home))
    }

    @Test
    fun `only Claude Code's skills directory present, is found and installable`() {
        val home = Files.createTempDirectory("claude-remarks-home-").toRealPath()
        Files.createDirectories(home.resolve(".claude").resolve("skills"))

        val harnesses = SkillInstall.detectHarnesses(home)

        assertEquals(1, harnesses.size)
        val claude = harnesses.single()
        assertEquals("Claude Code", claude.displayName)
        assertTrue(claude.installable)
        assertEquals(home.resolve(".claude/skills/claude-remarks"), claude.targetDir)
    }

    @Test
    fun `only Codex's directory present, is found and not installable with no target`() {
        val home = Files.createTempDirectory("claude-remarks-home-").toRealPath()
        Files.createDirectories(home.resolve(".codex"))

        val harnesses = SkillInstall.detectHarnesses(home)

        assertEquals(1, harnesses.size)
        val codex = harnesses.single()
        assertEquals("Codex", codex.displayName)
        assertFalse(codex.installable)
        assertNull(codex.targetDir)
    }

    @Test
    fun `only Gemini's directory present, is found and not installable with no target`() {
        val home = Files.createTempDirectory("claude-remarks-home-").toRealPath()
        Files.createDirectories(home.resolve(".gemini"))

        val harnesses = SkillInstall.detectHarnesses(home)

        assertEquals(1, harnesses.size)
        val gemini = harnesses.single()
        assertEquals("Gemini", gemini.displayName)
        assertFalse(gemini.installable)
        assertNull(gemini.targetDir)
    }

    @Test
    fun `all three present, all three are found`() {
        val home = Files.createTempDirectory("claude-remarks-home-").toRealPath()
        Files.createDirectories(home.resolve(".claude").resolve("skills"))
        Files.createDirectories(home.resolve(".codex"))
        Files.createDirectories(home.resolve(".gemini"))

        val harnesses = SkillInstall.detectHarnesses(home)

        assertEquals(setOf("Claude Code", "Codex", "Gemini"), harnesses.map { it.displayName }.toSet())
    }

    @Test
    fun `detectHarnesses never creates a harness directory`() {
        val home = Files.createTempDirectory("claude-remarks-home-").toRealPath()

        SkillInstall.detectHarnesses(home)

        assertFalse(Files.exists(home.resolve(".claude")))
        assertFalse(Files.exists(home.resolve(".codex")))
        assertFalse(Files.exists(home.resolve(".gemini")))
    }

    // --- skillPresence ---------------------------------------------------------------------------

    @Test
    fun `skillPresence is Missing when nothing is at the directory`() {
        val home = Files.createTempDirectory("claude-remarks-home-").toRealPath()
        val dir = home.resolve("claude-remarks")

        assertEquals(SkillInstall.SkillPresence.Missing, SkillInstall.skillPresence(dir))
    }

    @Test
    fun `skillPresence is Missing when the directory exists but has no SKILL_md`() {
        val dir = Files.createTempDirectory("claude-remarks-dir-").toRealPath()

        assertEquals(SkillInstall.SkillPresence.Missing, SkillInstall.skillPresence(dir))
    }

    @Test
    fun `skillPresence is Symlink when the directory is a symlink`() {
        val real = Files.createTempDirectory("claude-remarks-real-").toRealPath()
        val parent = Files.createTempDirectory("claude-remarks-parent-").toRealPath()
        val link = parent.resolve("claude-remarks")
        Files.createSymbolicLink(link, real)

        assertEquals(SkillInstall.SkillPresence.Symlink, SkillInstall.skillPresence(link))
    }

    @Test
    fun `skillPresence is Present with the stamped version when SKILL_md is stamped`() {
        val dir = Files.createTempDirectory("claude-remarks-dir-").toRealPath()
        Files.writeString(dir.resolve("SKILL.md"), "---\n# claude-remarks-plugin-version: 0.11.0\nname: x\n")

        assertEquals(SkillInstall.SkillPresence.Present("0.11.0"), SkillInstall.skillPresence(dir))
    }

    @Test
    fun `skillPresence is Present with a null version when SKILL_md carries no stamp`() {
        val dir = Files.createTempDirectory("claude-remarks-dir-").toRealPath()
        Files.writeString(dir.resolve("SKILL.md"), "---\nname: x\n")

        assertEquals(SkillInstall.SkillPresence.Present(null), SkillInstall.skillPresence(dir))
    }

    // --- installSkill ----------------------------------------------------------------------------

    @Test
    fun `installs all three files, stamps SKILL_md on line 2, and makes both scripts executable`() {
        val targetDir = Files.createTempDirectory("claude-remarks-target-").toRealPath()
            .resolve("claude-remarks")

        val result = SkillInstall.installSkill(targetDir, "0.12.0", ::fakeResource)

        assertNull(result)
        val skillMdLines = Files.readString(targetDir.resolve("SKILL.md")).lines()
        assertEquals("---", skillMdLines[0])
        assertEquals("# claude-remarks-plugin-version: 0.12.0", skillMdLines[1])
        assertTrue(Files.readString(targetDir.resolve("watch-remarks.sh")).contains("fake watch script"))
        assertTrue(Files.readString(targetDir.resolve("remote-config.sh")).contains("fake remote config"))
        assertTrue(Files.isExecutable(targetDir.resolve("watch-remarks.sh")))
        assertTrue(Files.isExecutable(targetDir.resolve("remote-config.sh")))
    }

    @Test
    fun `after a successful install, skillPresence reads the version back`() {
        val targetDir = Files.createTempDirectory("claude-remarks-target-").toRealPath()
            .resolve("claude-remarks")

        val result = SkillInstall.installSkill(targetDir, "0.12.0", ::fakeResource)

        assertNull(result)
        assertEquals(SkillInstall.SkillPresence.Present("0.12.0"), SkillInstall.skillPresence(targetDir))
    }

    @Test
    fun `a resource that cannot be read produces a failure sentence rather than a partial install`() {
        val targetDir = Files.createTempDirectory("claude-remarks-target-").toRealPath()
            .resolve("claude-remarks")

        val result = SkillInstall.installSkill(targetDir, "0.12.0") { name ->
            if (name == "SKILL.md") null else fakeResource(name)
        }

        assertNotNull(result)
    }

    @Test
    fun `refuses when the target directory is a symlink, and writes nothing at the far end`() {
        val realDir = Files.createTempDirectory("claude-remarks-real-").toRealPath()
        val marker = realDir.resolve("marker.txt")
        Files.writeString(marker, "original")

        val parent = Files.createTempDirectory("claude-remarks-parent-").toRealPath()
        val symlinkTarget = parent.resolve("claude-remarks")
        Files.createSymbolicLink(symlinkTarget, realDir)

        val result = SkillInstall.installSkill(symlinkTarget, "0.12.0", ::fakeResource)

        assertNotNull(result)
        assertTrue(result!!.contains("symlink"))
        assertEquals(listOf(marker), Files.list(realDir).use { it.toList() })
        assertEquals("original", Files.readString(marker))
    }

    @Test
    fun `refuses when one of the three target files is a symlink, before writing anything`() {
        val elsewhere = Files.createTempDirectory("claude-remarks-elsewhere-").toRealPath()
        val elsewhereFile = elsewhere.resolve("elsewhere.md")
        Files.writeString(elsewhereFile, "not the skill")

        val targetDir = Files.createTempDirectory("claude-remarks-target-").toRealPath()
        Files.createSymbolicLink(targetDir.resolve("SKILL.md"), elsewhereFile)

        val result = SkillInstall.installSkill(targetDir, "0.12.0", ::fakeResource)

        assertNotNull(result)
        assertTrue(result!!.contains("symlink"))
        assertEquals("not the skill", Files.readString(elsewhereFile))
        assertFalse(Files.exists(targetDir.resolve("watch-remarks.sh")))
        assertFalse(Files.exists(targetDir.resolve("remote-config.sh")))
    }
}
