package dev.sasha.clauderemarks.skill

import com.intellij.ide.plugins.PluginManager

/**
 * The version this build of the plugin carries, or null when it cannot be resolved — the ordinary
 * case inside a unit-test fixture, per [SkillInstall.installSkill]'s own KDoc on why that function
 * takes its version as a parameter rather than looking it up itself.
 *
 * Its own file, not a method on [SkillInstall] and not private inside `RemarkSettingsConfigurable`.
 * Both `RemarkSettingsConfigurable` (the settings row) and `SkillInstallNotification` (the
 * notification) need this same lookup, and two callers computing a bundled version two different
 * ways is how two different answers show up later — see task 3's own progress-log note, which first
 * ran into this. ⚠️ It cannot live in [SkillInstall] itself: that file is verified to carry no
 * `com.intellij` import at all, and [PluginManager] is one.
 */
fun bundledPluginVersion(): String? =
    PluginManager.getPluginByClass(SkillInstall::class.java)?.version
