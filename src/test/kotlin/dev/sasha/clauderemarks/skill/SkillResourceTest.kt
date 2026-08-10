package dev.sasha.clauderemarks.skill

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Plain JUnit: resource resolution needs no fixture. Modeled on `ui/RemarkIconsTest.kt` and its
 * argument holds here too — a resource path with no file behind it is not a compile error, it is a
 * silent runtime miss, the first time something tries to read it.
 *
 * ⚠️ The path comes from [SkillInstall.resourcePath], the same expression production reads these
 * resources through, never from a copy of it written here. A copy would leave a typo in the
 * production template invisible: every file would still sit under the path this test checked.
 *
 * It loops [SkillInstall.SKILL_FILES] rather than naming files, so a name added there is covered the
 * moment it is added — which is what makes this the check that every entry really resolves.
 */
class SkillResourceTest {

    /**
     * The one file name the skill's markdown points at that is deliberately **not** a skill resource:
     * `docs/skill/README.md` is a repository document, and no installed copy of the skill can reach
     * it. It predates the split and is left as it is.
     */
    private val notASkillResource = setOf("README.md")

    /**
     * Reads one skill resource as text, for the pointer-guard test below.
     *
     * ⚠️ The first test deliberately does **not** go through this helper, and that is not an
     * oversight. Its whole claim is that every entry in [SkillInstall.SKILL_FILES] resolves, so a
     * missing resource has to arrive there as a failed assertion naming the file. `checkNotNull`
     * here throws instead, which reports as an error rather than as the assertion that test is
     * written to make.
     */
    private fun readSkillFile(name: String): String =
        SkillInstall::class.java.getResourceAsStream(SkillInstall.resourcePath(name))
            .let { checkNotNull(it) { "expected $name on the classpath" } }
            .use { it.readBytes().toString(Charsets.UTF_8) }

    @Test
    fun `every skill resource resolves on the classpath and is not empty`() {
        // ⚠️ The loop below is the whole body, so an emptied or over-filtered SKILL_FILES would pass
        // this test with zero assertions run while its whole claim is that every entry resolves.
        // These two lines are what fails instead. The count is deliberately not pinned: a seventh
        // file is meant to be added without editing a test.
        assertTrue("SKILL_FILES must never be empty", SkillInstall.SKILL_FILES.isNotEmpty())
        assertTrue(
            "SKILL_FILES must always carry SKILL.md, the file the install stamps",
            SkillInstall.SKILL_FILES.contains("SKILL.md"),
        )

        SkillInstall.SKILL_FILES.forEach { name ->
            val path = SkillInstall.resourcePath(name)
            val resource = SkillInstall::class.java.getResource(path)
            assertNotNull("expected $path on the classpath", resource)

            val bytes = SkillInstall::class.java.getResourceAsStream(path)?.use { it.readBytes() }
            assertNotNull("expected $path to be readable", bytes)
            assertFalse("expected $path to be non-empty", bytes!!.isEmpty())
        }
    }

    @Test
    fun `SKILL_md names each reference file, and no skill markdown points at a file not installed`() {
        // The gap this closes: SkillResourceTest loops SKILL_FILES against the classpath and
        // SkillInstallTest compares an installed listing against SKILL_FILES. Neither reads a word
        // of the skill's prose. So renaming a reference file in SKILL_FILES and in the resource
        // directory, and forgetting the sentences that send a session to it, leaves the whole suite
        // green while a live session is told to Read a file that was never installed — and nothing
        // enumerates the directory, so the session has no way to find it.
        //
        // ⚠️ The two directions are not scanned over the same set of files, and that asymmetry is
        // the whole point.
        //
        // Forward — every reference file has to be named by SKILL.md **itself**. SKILL.md is the
        // only skill file a session loads on its own; every other one is opened solely because some
        // sentence in SKILL.md sends the session there. A reference file named only by another
        // reference file has no entry route at all: the file that names it is one nobody opens
        // unless already pointed at it. That really can happen here — watcher-background.md is
        // named by listen-without-monitor.md as well as by SKILL.md, and a session in Claude Code
        // always takes the monitor branch and is told in as many words never to open
        // listen-without-monitor.md.
        //
        // Reverse — every file name pointed at is unioned over **every** markdown file in
        // SKILL_FILES, not SKILL.md alone. The reference files carry cross-references of their own —
        // listen-without-monitor.md sends a session to watcher-background.md and names both
        // scripts — and a reverse check that read only SKILL.md would leave exactly the same rot one
        // file away from where it looked.
        val markdown = SkillInstall.SKILL_FILES.filter { it.endsWith(".md") }
        assertTrue("SKILL_FILES must carry at least one markdown file", markdown.isNotEmpty())
        val prose = markdown.associateWith { readSkillFile(it) }
        val skillMd = prose["SKILL.md"]
        assertNotNull(
            "SKILL_FILES must carry SKILL.md — it is the one file a session loads by itself, so it " +
                "is the only place a pointer can start a route from",
            skillMd,
        )

        SkillInstall.SKILL_FILES.filter { it != "SKILL.md" }.forEach { name ->
            assertTrue(
                "SKILL.md never names $name, so no session has a route to it — being named by " +
                    "another reference file is not a route, because nothing opens that file either " +
                    "until SKILL.md sends a session there",
                skillMd!!.contains(name),
            )
        }

        // The other direction, over the union of every markdown file. The lookbehind drops a name
        // reached through a shell variable ($pub_name.md, $listen_name.md are published-file paths,
        // not skill files) and a match starting in the middle of a longer name; a leading directory
        // falls away on its own, because "/" is outside the character class, so
        // "docs/skill/README.md" reads as "README.md".
        val pointer = Regex("""(?<![\w${'$'}.-])[\w.-]+\.(?:md|sh)""")
        val pointedAt = prose.values.flatMap { text -> pointer.findAll(text).map { it.value } }.toSet()
        assertEquals(
            "every .md or .sh file name the skill's markdown points at has to be in SKILL_FILES, " +
                "or a session is sent to a file the install never wrote and nothing enumerates the " +
                "directory for it to find one anyway",
            emptySet<String>(),
            (pointedAt - notASkillResource - SkillInstall.SKILL_FILES.toSet()).toSortedSet(),
        )
    }
}
