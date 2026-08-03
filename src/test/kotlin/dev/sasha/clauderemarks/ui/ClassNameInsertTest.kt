package dev.sasha.clauderemarks.ui

import com.intellij.navigation.ChooseByNameContributor
import com.intellij.navigation.NavigationItem
import com.intellij.openapi.project.Project
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.ui.components.JBTextArea

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
     * the distinct or the sort. This adds a class and asks for it back.
     */
    fun testAClassInTheProjectComesBackFromTheNameList() {
        contribute("Zebra", "Apple", "Zebra")
        contribute("Middle")

        assertEquals(listOf("Apple", "Middle", "Zebra"), projectClassNames(project))
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

        assertEquals(listOf("Apple"), projectClassNames(project))
    }

    /**
     * The real extension point, with a contributor of our own on it. A file added through myFixture
     * would not do: this fixture is the platform test framework only, so no language plugin registers
     * a gotoClassContributor and the list comes back empty whatever is in the project.
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
