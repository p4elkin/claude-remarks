package dev.sasha.clauderemarks.skill

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * Plain JUnit: resource resolution needs no fixture. Modeled on `ui/RemarkIconsTest.kt` and its
 * argument holds here too — a resource path with no file behind it is not a compile error, it is a
 * silent runtime miss, the first time something tries to read it.
 *
 * ⚠️ The path comes from [SkillInstall.resourcePath], the same expression production will use once
 * task 2 reads these resources, never from a copy of it written here. A copy would leave a typo in
 * the production template invisible: all three files would still sit under the path this test
 * checked.
 */
class SkillResourceTest {

    @Test
    fun `all three skill resources resolve on the classpath and are not empty`() {
        SkillInstall.SKILL_FILES.forEach { name ->
            val path = SkillInstall.resourcePath(name)
            val resource = SkillInstall::class.java.getResource(path)
            assertNotNull("expected $path on the classpath", resource)

            val bytes = SkillInstall::class.java.getResourceAsStream(path)?.use { it.readBytes() }
            assertNotNull("expected $path to be readable", bytes)
            assertFalse("expected $path to be non-empty", bytes!!.isEmpty())
        }
    }
}
