package dev.sasha.clauderemarks.ui

import com.intellij.openapi.actionSystem.ActionGroup
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.sasha.clauderemarks.model.RemarkSeverity
import dev.sasha.clauderemarks.store.RemarkStore
import dev.sasha.clauderemarks.store.addRemark

/**
 * The menu itself is checked by hand. What is checked here is the part that would fail silently:
 * that every severity has an item, and that pressing one changes exactly the remarks the lambda
 * names at the moment it is pressed, not the ones it named when the menu was built.
 */
class RemarkActionsTest : BasePlatformTestCase() {

    override fun setUp() {
        super.setUp()
        // The light fixture project is shared across test classes.
        RemarkStore.getInstance(project).clear()
    }

    fun testThereIsOneItemForEverySeverity() {
        val group = remarkChangeActions(project) { emptyList() }
        val severity = group.getChildren(null).filterIsInstance<ActionGroup>().single()

        assertEquals(
            RemarkSeverity.entries.map { it.name.lowercase() },
            severity.getChildren(null).map { it.templatePresentation.text },
        )
    }

    /**
     * The ids are read when the item is pressed, not when the menu is built. The tree rebuilds
     * itself on every remark change, so a list captured at build time is stale by the time anybody
     * clicks — it would set the severity on rows that are no longer selected.
     */
    fun testPressingASeverityItemActsOnTheIdsAtPressTime() {
        val first = addRemark(project, "A.kt", listOf("a", "b"), 0..0, "one", null)
        var wanted = emptyList<String>()
        val group = remarkChangeActions(project) { wanted }
        val must = (group.getChildren(null).filterIsInstance<ActionGroup>().single())
            .getChildren(null).single { it.templatePresentation.text == "must" }

        wanted = listOf(first.id!!)
        must.actionPerformed(com.intellij.testFramework.TestActionEvent.createTestEvent(must))

        assertEquals(RemarkSeverity.MUST, RemarkStore.getInstance(project).all().single().severity)
    }
}
