package dev.sasha.clauderemarks.ui

import com.intellij.navigation.ChooseByNameContributor
import com.intellij.navigation.NavigationItem
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.Project
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.ui.components.JBTextArea
import org.junit.Assert.assertThrows

/**
 * The chooser popup needs a window and is checked by hand. What is tested here is the part that
 * would be wrong silently: where the chosen name lands in the text, and that a missing extension
 * point answers an empty list instead of throwing.
 */
class ClassNameInsertTest : BasePlatformTestCase() {

    fun testTheChosenNameIsInsertedAtTheCaret() {
        val area = JBTextArea("see  for why")
        area.caretPosition = 4

        insertAtCaret(area, "JcrSessionProvider")

        assertEquals("see JcrSessionProvider for why", area.text)
    }

    fun testInsertingOverASelectionReplacesIt() {
        val area = JBTextArea("see WrongName for why")
        area.select(4, 13)

        insertAtCaret(area, "JcrSessionProvider")

        assertEquals("see JcrSessionProvider for why", area.text)
    }

    /**
     * `assertNotNull(projectClassNames(project))` used to stand here, on a function returning a
     * non-nullable List — an assertion that passes against `fun projectClassNames(...) =
     * emptyList<String>()`, so nothing covered the extension point, the per-extension runCatching,
     * the distinct or the sort. This adds classes and asks for them back.
     *
     * The three properties are asserted one at a time rather than by comparing the whole list to
     * `listOf("Apple", "Middle", "Zebra")`. That comparison assumed the fixture contributes nothing
     * of its own, and it no longer does: this fixture now answers the Kotlin builtin names (`Any`,
     * `Int`, `String` and about a hundred more) alongside ours. Those names are not this test's
     * business, and a test that breaks when the platform indexes one more class is testing the
     * platform rather than `projectClassNames`.
     */
    fun testAClassInTheProjectComesBackFromTheNameList() {
        contribute("Zebra", "Apple", "Zebra")
        contribute("Middle")

        val names = projectClassNames(project)

        assertTrue("every contributor is read: $names", names.containsAll(listOf("Apple", "Middle", "Zebra")))
        assertEquals("distinct() drops the repeated name", 1, names.count { it == "Zebra" })
        assertEquals("the list comes back sorted", names.sorted(), names)
    }

    /**
     * One contributor throwing must not take the others down with it. That is what the inner
     * runCatching is for, and nothing exercised it.
     */
    fun testAContributorThatThrowsIsSkippedRatherThanKillingTheKeystroke() {
        contribute("Apple")
        ChooseByNameContributor.CLASS_EP_NAME.point.registerExtension(
            object : ChooseByNameContributor {
                override fun getNames(project: Project, includeNonProjectItems: Boolean): Array<String> =
                    throw UnsupportedOperationException("this contributor is broken")

                override fun getItemsByName(
                    name: String,
                    pattern: String,
                    project: Project,
                    includeNonProjectItems: Boolean,
                ): Array<NavigationItem> = emptyArray()
            },
            testRootDisposable,
        )

        assertTrue(
            "the broken contributor took the working one down with it",
            projectClassNames(project).contains("Apple"),
        )
    }

    /**
     * A cancellation must escape, where any other failure must not.
     *
     * projectClassNames runs inside ReadAction.nonBlocking, and getNames walks stub indexes, which
     * call ProgressManager.checkCanceled() constantly: when a write action asks for the lock, PCE is
     * thrown and the read action has to unwind so the lock goes back. Swallowing it means the walk
     * carries on over every remaining contributor, and the keystroke that wanted the lock waits it
     * out — the EDT stall a non-blocking read action exists to prevent.
     */
    fun testACancellationIsNotSwallowedTheWayABrokenContributorIs() {
        contribute("Apple")
        ChooseByNameContributor.CLASS_EP_NAME.point.registerExtension(
            object : ChooseByNameContributor {
                override fun getNames(project: Project, includeNonProjectItems: Boolean): Array<String> =
                    throw ProcessCanceledException()

                override fun getItemsByName(
                    name: String,
                    pattern: String,
                    project: Project,
                    includeNonProjectItems: Boolean,
                ): Array<NavigationItem> = emptyArray()
            },
            testRootDisposable,
        )

        assertThrows(ProcessCanceledException::class.java) { projectClassNames(project) }
    }

    /**
     * The real extension point, with a contributor of our own on it. A file added through myFixture
     * would not do: no language plugin in this fixture indexes a file we write, so a class added that
     * way never reaches the name list. Registering a contributor is what puts a known name on the
     * point. What comes back is our names plus whatever the fixture itself contributes, so every
     * assertion above looks for our names rather than for the whole list.
     */
    private fun contribute(vararg names: String) {
        ChooseByNameContributor.CLASS_EP_NAME.point.registerExtension(
            object : ChooseByNameContributor {
                override fun getNames(project: Project, includeNonProjectItems: Boolean): Array<String> =
                    arrayOf(*names)

                override fun getItemsByName(
                    name: String,
                    pattern: String,
                    project: Project,
                    includeNonProjectItems: Boolean,
                ): Array<NavigationItem> = emptyArray()
            },
            testRootDisposable,
        )
    }
}
