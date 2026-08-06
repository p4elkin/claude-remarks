package dev.sasha.clauderemarks.skill

/**
 * The pure half of installing the skill into a detected harness: detecting harnesses, stamping and
 * reading the version, and copying the three resource files out.
 *
 * **This file is a stub as of phase 15 task 1.** Only [SKILL_FILES] and [resourcePath] exist here,
 * added early because [dev.sasha.clauderemarks.skill.SkillResourceTest] needs a path to ask the
 * classpath for, and production's path constant did not exist before this task. Task 2 grows this
 * into the rest of the pure install half: `stampVersion`, `stampedVersionOf`, `detectHarnesses`,
 * `skillPresence` and `installSkill`.
 */
object SkillInstall {

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
}
