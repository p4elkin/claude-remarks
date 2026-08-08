package dev.sasha.clauderemarks.skill

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Plain JUnit: reading one resource needs no fixture.
 *
 * ⚠️ [bundledPluginVersion] is what the settings row and the skill-install notification both read.
 * When it returns null the row says "version unknown" and the notification returns early and never
 * fires — neither logs anything, so a broken lookup is invisible until somebody notices the balloon
 * stopped appearing. The resource behind it is produced by one Gradle task,
 * `writePluginVersionResource`, and nothing else would fail if that task stopped running.
 *
 * The path comes from [PLUGIN_VERSION_RESOURCE], the same constant production reads, never from a
 * copy written here — the argument [SkillResourceTest] makes about the skill resources. A copy would
 * agree with a typo rather than catch it.
 */
class PluginVersionResourceTest {

    @Test
    fun `the build writes a plugin version resource and the lookup reads it back`() {
        val raw = PluginVersionResourceTest::class.java.getResourceAsStream(PLUGIN_VERSION_RESOURCE)
            ?.use { it.readBytes().decodeToString() }
        assertNotNull(
            "expected $PLUGIN_VERSION_RESOURCE on the classpath — writePluginVersionResource in " +
                "build.gradle.kts is the only thing that writes it",
            raw,
        )

        val version = bundledPluginVersion()
        assertNotNull("expected bundledPluginVersion() to resolve from that resource", version)
        assertEquals("expected the lookup to return exactly the file's trimmed contents", raw!!.trim(), version)
    }

    @Test
    fun `the version reads as a version rather than as whatever happened to be in the file`() {
        val version = bundledPluginVersion()
        assertNotNull(version)
        assertTrue(
            "expected something shaped like 1.2.3, got '$version'",
            Regex("""\d+\.\d+(\.\d+)?([-.][0-9A-Za-z-]+)*""").matches(version!!),
        )
    }
}
