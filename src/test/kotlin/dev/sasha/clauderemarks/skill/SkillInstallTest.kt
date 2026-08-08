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
        "listen-without-monitor.md" -> "# The exit-per-batch branch\nfake reference content\n".byteInputStream()
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
    fun `stampVersion still opens the frontmatter on a CRLF file, and keeps the CRLF`() {
        // A clone made with Git for Windows' default core.autocrlf=true produces exactly this file,
        // and this repository carries no .gitattributes to stop it. Comparing the first line against
        // the literal "---" misses "---\r", the stamp lands in FRONT of line 1, the frontmatter
        // never opens and Claude Code silently does not register the skill — while stampedVersionOf
        // still reads the version back, so the settings row reports it up to date.
        val text = "---\r\nname: claude-remarks\r\n---\r\nbody\r\n"

        val stamped = SkillInstall.stampVersion(text, "0.12.0")

        assertTrue(stamped.startsWith("---\r\n# claude-remarks-plugin-version: 0.12.0\r\n"))
        assertFalse(
            "stamping must not rewrite a CRLF file as LF as a side effect",
            stamped.replace("\r\n", "").contains("\n"),
        )
        assertEquals("0.12.0", SkillInstall.stampedVersionOf(stamped))
    }

    @Test
    fun `stampVersion still opens the frontmatter on a file with a byte-order mark, and keeps it`() {
        val text = "﻿---\nname: claude-remarks\n---\nbody\n"

        val stamped = SkillInstall.stampVersion(text, "0.12.0")

        assertEquals("﻿---\n# claude-remarks-plugin-version: 0.12.0\nname: claude-remarks\n---\nbody\n", stamped)
        assertEquals("0.12.0", SkillInstall.stampedVersionOf(stamped))
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
    fun `a bare dot-claude with no skills directory is still Claude Code, found and installable`() {
        // The person this feature was built for: they use Claude Code, so ~/.claude holds their
        // settings, projects and history — but skills/ is created when a first skill is added, and
        // they have never added one. Keying detection on ~/.claude/skills made the whole feature
        // invisible to exactly them: no row, no button and no balloon.
        val home = Files.createTempDirectory("claude-remarks-home-").toRealPath()
        Files.createDirectories(home.resolve(".claude"))

        val harnesses = SkillInstall.detectHarnesses(home)

        assertEquals(1, harnesses.size)
        val claude = harnesses.single()
        assertEquals("Claude Code", claude.displayName)
        assertTrue(claude.installable)
        assertEquals(home.resolve(".claude/skills/claude-remarks"), claude.targetDir)
    }

    @Test
    fun `a dot-claude that already holds a skills directory is found the same way`() {
        val home = Files.createTempDirectory("claude-remarks-home-").toRealPath()
        Files.createDirectories(home.resolve(".claude").resolve("skills"))

        val claude = SkillInstall.detectHarnesses(home).single()

        assertEquals("Claude Code", claude.displayName)
        assertTrue(claude.installable)
        assertEquals(SkillInstall.claudeSkillDir(home), claude.targetDir)
    }

    @Test
    fun `installing into a bare dot-claude creates the skills directory on the way`() {
        val home = Files.createTempDirectory("claude-remarks-home-").toRealPath()
        Files.createDirectories(home.resolve(".claude"))
        val targetDir = SkillInstall.detectHarnesses(home).single().targetDir!!

        val result = SkillInstall.installSkill(targetDir, "0.12.0", ::fakeResource)

        assertNull(result)
        assertEquals(SkillInstall.SkillPresence.Present("0.12.0"), SkillInstall.skillPresence(targetDir))
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
        Files.createDirectories(home.resolve(".claude"))
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
    fun `installs every skill file, stamps SKILL_md on line 2, and makes both scripts executable`() {
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
    fun `a reference file lands in the target directory with its content intact`() {
        val targetDir = Files.createTempDirectory("claude-remarks-target-").toRealPath()
            .resolve("claude-remarks")

        val result = SkillInstall.installSkill(targetDir, "0.12.0", ::fakeResource)

        assertNull(result)
        assertEquals(
            "# The exit-per-batch branch\nfake reference content\n",
            Files.readString(targetDir.resolve("listen-without-monitor.md")),
        )
    }

    @Test
    fun `a reference file is not executable after an install, unlike the two scripts`() {
        val targetDir = Files.createTempDirectory("claude-remarks-target-").toRealPath()
            .resolve("claude-remarks")

        val result = SkillInstall.installSkill(targetDir, "0.12.0", ::fakeResource)

        assertNull(result)
        assertFalse(Files.isExecutable(targetDir.resolve("listen-without-monitor.md")))
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
    fun `a later file failing leaves no stamped SKILL_md behind, so the install still reads as missing`() {
        // The failure that matters is a LATER file, not the first one. With SKILL.md written first,
        // a failed script write left the stamp on disk: skillPresence read the bundled version back,
        // the settings row said "up to date" and the balloon never fired again, while a real session
        // answered that watch-remarks.sh was not found. So SKILL.md is written last, and this fails
        // remote-config.sh — one of the names in SKILL_FILES that is not SKILL.md — to prove it.
        val targetDir = Files.createTempDirectory("claude-remarks-target-").toRealPath()
            .resolve("claude-remarks")

        val result = SkillInstall.installSkill(targetDir, "0.12.0") { name ->
            if (name == "remote-config.sh") null else fakeResource(name)
        }

        assertNotNull(result)
        assertFalse(
            "a stamped SKILL.md left behind would report the install up to date for ever",
            Files.exists(targetDir.resolve("SKILL.md")),
        )
        assertEquals(SkillInstall.SkillPresence.Missing, SkillInstall.skillPresence(targetDir))
    }

    @Test
    fun `SKILL_md is written last, so the two scripts are on disk before any stamp is`() {
        // The order itself, pinned directly rather than only through its consequence above: a
        // reordering that puts SKILL.md back at the front would revive the stale "up to date" bug
        // and this is what fails when it does.
        val targetDir = Files.createTempDirectory("claude-remarks-target-").toRealPath()
            .resolve("claude-remarks")
        val order = mutableListOf<String>()

        val result = SkillInstall.installSkill(targetDir, "0.12.0") { name ->
            order += name
            fakeResource(name)
        }

        assertNull(result)
        assertEquals("SKILL.md", order.last())
        assertEquals(
            setOf("watch-remarks.sh", "remote-config.sh", "listen-without-monitor.md"),
            order.dropLast(1).toSet(),
        )
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
    fun `refuses when dot-claude itself is a symlink, and writes nothing at the far end`() {
        // A dotfiles checkout pointing ~/.claude into a repository. The leaf, ~/.claude/skills/
        // claude-remarks, is then an ordinary directory that does not exist yet, so a check on the
        // leaf alone passes and Files.createDirectories plus the rename inside atomicWriteString
        // follow the ancestor link and write into the far end. That is precisely the case this
        // refusal exists for.
        val checkout = Files.createTempDirectory("claude-remarks-checkout-").toRealPath()
        val marker = checkout.resolve("marker.txt")
        Files.writeString(marker, "original")

        val home = Files.createTempDirectory("claude-remarks-home-").toRealPath()
        Files.createSymbolicLink(home.resolve(".claude"), checkout)

        val result = SkillInstall.installSkill(SkillInstall.claudeSkillDir(home), "0.12.0", ::fakeResource)

        assertNotNull(result)
        assertTrue(result!!.contains("symlink"))
        assertEquals(listOf(marker), Files.list(checkout).use { it.toList() })
        assertEquals("original", Files.readString(marker))
    }

    @Test
    fun `refuses when the skills directory is a symlink, and writes nothing at the far end`() {
        val checkout = Files.createTempDirectory("claude-remarks-checkout-").toRealPath()
        val marker = checkout.resolve("marker.txt")
        Files.writeString(marker, "original")

        val home = Files.createTempDirectory("claude-remarks-home-").toRealPath()
        Files.createDirectories(home.resolve(".claude"))
        Files.createSymbolicLink(home.resolve(".claude").resolve("skills"), checkout)

        val result = SkillInstall.installSkill(SkillInstall.claudeSkillDir(home), "0.12.0", ::fakeResource)

        assertNotNull(result)
        assertTrue(result!!.contains("symlink"))
        assertEquals(listOf(marker), Files.list(checkout).use { it.toList() })
        assertEquals("original", Files.readString(marker))
    }

    @Test
    fun `an ordinary temporary directory is not refused, whatever its real path resolves to`() {
        // ⚠️ The refusal must never be "the resolved path differs from the lexical one". On macOS
        // /tmp is a symlink to /private/tmp and a temporary directory resolves under
        // /private/var/folders/…, so that rule would refuse every temporary directory these tests
        // use and every home directory sitting under a symlinked mount. This deliberately does NOT
        // call toRealPath(), so the path carries whatever indirection the platform gives it.
        val home = Files.createTempDirectory("claude-remarks-home-")
        Files.createDirectories(home.resolve(".claude"))

        val result = SkillInstall.installSkill(SkillInstall.claudeSkillDir(home), "0.12.0", ::fakeResource)

        assertNull(result)
    }

    @Test
    fun `refuses when one of the target files is a symlink, before writing anything`() {
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
