package dev.sasha.clauderemarks.skill

/**
 * The status text and button label for one harness row on the settings page. Pure so
 * [SkillRowTextTest] can cover every combination with no UI fixture — `RemarkSettingsConfigurable`
 * only wires the strings this returns onto actual Swing components, and prefixes [statusText] with
 * the harness's own display name, which is a UI concern this function deliberately knows nothing
 * about.
 */
data class SkillRowText(
    /** What the row says about what is installed, with no harness name in it. */
    val statusText: String,
    /** The button's label, or null when the row gets no button at all. */
    val buttonLabel: String?,
)

/**
 * Decides one harness row's text from what [dev.sasha.clauderemarks.skill.SkillInstall.skillPresence]
 * found there, or from [installable] alone for a harness this phase only detects.
 *
 * [presence] is only ever read when [installable] is true — Codex and Gemini have no [SkillInstall
 * .HarnessInfo.targetDir] to check at all, on purpose (see that property's own KDoc), so there is
 * nothing to ask [SkillInstall.skillPresence] about them. `null` is accepted there too, and reads the
 * same as [SkillInstall.SkillPresence.Missing], purely as a defensive fallback: production always
 * passes a real presence for an installable harness.
 *
 * [SkillInstall.SkillPresence.Symlink] gets no button, on purpose: that is the development checkout
 * on this machine, and a button offering to write through it would contradict
 * [SkillInstall.installSkill]'s own refusal to do exactly that.
 *
 * [SkillInstall.SkillPresence.Present] with a null version is "installed, version unknown" rather
 * than an error — the ordinary case for a copy installed by hand before this phase existed, or for
 * the bare checkout the development symlink points at. It still offers Reinstall, which is the
 * update path for it.
 */
fun skillRowText(
    installable: Boolean,
    presence: SkillInstall.SkillPresence?,
    bundledVersion: String,
): SkillRowText {
    if (!installable) {
        return SkillRowText(
            "found, but the plugin cannot install here yet — its own file layout has not been " +
                "read from its own documentation",
            buttonLabel = null,
        )
    }
    return when (presence) {
        null, SkillInstall.SkillPresence.Missing -> SkillRowText("not installed", "Install")
        SkillInstall.SkillPresence.Symlink -> SkillRowText(
            "a symlink (a development install) — remove it by hand before the skill can be " +
                "installed here",
            buttonLabel = null,
        )
        is SkillInstall.SkillPresence.Present -> {
            val installed = presence.version
            when {
                installed == null -> SkillRowText("installed, version unknown", "Reinstall")
                installed == bundledVersion -> SkillRowText("up to date", "Reinstall")
                else -> SkillRowText("$installed installed, $bundledVersion bundled", "Reinstall")
            }
        }
    }
}
