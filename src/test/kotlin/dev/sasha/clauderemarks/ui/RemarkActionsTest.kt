package dev.sasha.clauderemarks.ui

import com.intellij.openapi.actionSystem.ToggleAction
import com.intellij.testFramework.TestActionEvent
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.sasha.clauderemarks.store.RemarkStore
import dev.sasha.clauderemarks.store.addRemark
import dev.sasha.clauderemarks.store.setRemarkAsksForAnswer

/**
 * The menu itself is checked by hand. What is checked here is the part that would fail silently:
 * which items the menu offers at all, and that every item reads its ids when it is pressed rather
 * than when the menu was built.
 */
class RemarkActionsTest : BasePlatformTestCase() {

    override fun setUp() {
        super.setUp()
        // The light fixture project is shared across test classes.
        RemarkStore.getInstance(project).clear()
    }

    /**
     * Each of the two items is the only way in the UI to reach what it does from a remark that
     * already exists, so deleting one would leave that capability unreachable from here with
     * nothing else noticing. The order is asserted too, because it is the order the gutter menu and
     * the tree's right-click menu both draw.
     */
    fun testTheMenuOffersAskAndPublishInThatOrder() {
        val group = remarkChangeActions(project) { emptyList() }

        val items = group.getChildren(null).map { it.templatePresentation.text }
        assertEquals(listOf("Ask for an Answer", "Publish"), items)
    }

    /**
     * The rule the whole menu is built on: [remarkChangeActions] takes `ids` as a lambda, and every
     * item calls it when it is pressed. The tree rebuilds itself on every remark change, so a list
     * read when the menu was built is stale by the time anything in it is clicked.
     *
     * Publish is asserted through the lambda rather than through the store, because the publish
     * pipeline is asynchronous and a test that waits on it is the flaky test PublishRemarksTest's
     * own KDoc refuses to write. What is pinned here is the timing: nothing reads `ids` while the
     * menu is being built, and Publish reads it — and sees the current list — at press time. A
     * change from `() -> List<String>` to `List<String>` breaks this test.
     */
    fun testPressingPublishActsOnTheIdsAtPressTime() {
        val first = addRemark(project, "A.kt", listOf("a", "b"), 0..0, "one")
        var wanted = emptyList<String>()
        var seenAtPressTime: List<String>? = null
        val group = remarkChangeActions(project) {
            seenAtPressTime = wanted
            wanted
        }
        val publish = group.getChildren(null).single { it.templatePresentation.text == "Publish" }
        assertNull("the menu must not read the ids while it is being built", seenAtPressTime)

        wanted = listOf(first.id!!)
        publish.actionPerformed(TestActionEvent.createTestEvent(publish))

        assertEquals(listOf(first.id), seenAtPressTime)
    }

    /**
     * The toggle answers for the whole selection, not for its first row: it reads as on only when
     * every remark it would act on already asks, and one press changes all of them together.
     */
    fun testTheAskForAnAnswerToggleReportsTheStoreStateAndFlipsSeveralIdsAtOnce() {
        val one = addRemark(project, "A.kt", listOf("a", "b"), 0..0, "one")
        val two = addRemark(project, "A.kt", listOf("a", "b"), 1..1, "two")
        val ids = listOf(one.id!!, two.id!!)
        val toggle = remarkChangeActions(project) { ids }.getChildren(null)
            .single { it.templatePresentation.text == "Ask for an Answer" } as ToggleAction

        assertFalse("nothing asks yet", toggle.isSelected(TestActionEvent.createTestEvent(toggle)))

        setRemarkAsksForAnswer(project, listOf(one.id!!), true)
        assertFalse(
            "one of the two asks, which is not all of them",
            toggle.isSelected(TestActionEvent.createTestEvent(toggle)),
        )

        toggle.actionPerformed(TestActionEvent.createTestEvent(toggle))
        assertTrue(RemarkStore.getInstance(project).all().all { it.asksForAnswer })
        assertTrue(toggle.isSelected(TestActionEvent.createTestEvent(toggle)))

        toggle.actionPerformed(TestActionEvent.createTestEvent(toggle))
        assertTrue(RemarkStore.getInstance(project).all().none { it.asksForAnswer })
    }

    /**
     * With nothing selected the toggle must read as off rather than as on. `all {}` over an empty
     * list is true, so the plain reading of "every remark in ids() carries the flag" would draw a
     * checked item beside an empty selection.
     */
    fun testTheToggleIsOffWhenThereIsNothingToActOn() {
        val toggle = remarkChangeActions(project) { emptyList() }.getChildren(null)
            .single { it.templatePresentation.text == "Ask for an Answer" } as ToggleAction

        assertFalse(toggle.isSelected(TestActionEvent.createTestEvent(toggle)))
    }
}
