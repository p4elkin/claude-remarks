package dev.sasha.clauderemarks.skill

/**
 * Where the build writes this plugin's version. See `build.gradle.kts`,
 * `writePluginVersionResource`, which is the only thing that produces this file.
 *
 * ⚠️ The path sits inside the plugin's own package deliberately. `/META-INF/plugin.xml` and a bare
 * `/version.txt` are both names platform jars also carry, and which copy a classloader hands back is
 * not something to bet a version number on.
 */
const val PLUGIN_VERSION_RESOURCE: String = "/dev/sasha/clauderemarks/plugin-version.txt"

/**
 * The version this build of the plugin carries, or null when the resource is missing or empty.
 *
 * Its own file, not a method on [SkillInstall] and not private inside `RemarkSettingsConfigurable`.
 * Both `RemarkSettingsConfigurable` (the settings row) and `SkillInstallNotification` (the
 * notification) need this same lookup, and two callers computing a bundled version two different
 * ways is how two different answers show up later — see task 3's own progress-log note, which first
 * ran into this.
 *
 * ⚠️ **Do not "simplify" this back to asking the platform.** It used to be
 * `PluginManager.getPluginByClass(…)?.version`, and the Marketplace's own verification refused the
 * upload: that method is `@ApiStatus.Internal` from 262 onwards. So are the two obvious
 * replacements, `PluginManager.findEnabledPlugin` and `PluginManagerCore.getPlugin` — all three
 * checked against the exact build the Marketplace used, `idea/262.9437.65`. 262 points at
 * `PluginDetailsService` instead, which does not exist in the 252 line this plugin compiles against.
 * There is no platform call that is supported across the range `sinceBuild = "252"` with no upper
 * bound promises, which is why the build writes the answer into a resource instead.
 *
 * A happy consequence: this file now has no `com.intellij` import at all, so unlike the old lookup
 * it returns a real version inside a test fixture rather than null.
 */
fun bundledPluginVersion(): String? =
    object {}.javaClass.getResourceAsStream(PLUGIN_VERSION_RESOURCE)
        ?.use { it.readBytes().decodeToString().trim() }
        ?.takeIf { it.isNotEmpty() }
