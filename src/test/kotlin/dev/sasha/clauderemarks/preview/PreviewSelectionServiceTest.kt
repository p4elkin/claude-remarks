package dev.sasha.clauderemarks.preview

import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * The one-entry holder: it keeps the last selection and nothing else. Fixture-backed because a
 * project-level @Service needs a project; the arithmetic it stores is covered without one, in
 * [PreviewSelectionTest].
 */
class PreviewSelectionServiceTest : BasePlatformTestCase() {

    override fun setUp() {
        super.setUp()
        // The light fixture project is shared across test classes.
        PreviewSelectionService.getInstance(project).forget()
    }

    override fun tearDown() {
        PreviewSelectionService.getInstance(project).forget()
        super.tearDown()
    }

    fun testAStoredSelectionReadsBackWithAllItsFields() {
        val service = PreviewSelectionService.getInstance(project)

        service.remember(
            "file:///Users/sasha/dev/claude-remarks/docs/plans/a-plan.md",
            SourceRange(10, 18),
            77L,
        )

        val stored = service.current()!!
        assertEquals("file:///Users/sasha/dev/claude-remarks/docs/plans/a-plan.md", stored.fileUrl)
        assertEquals(SourceRange(10, 18), stored.range)
        assertEquals(77L, stored.sourceStamp)
    }

    fun testAfterForgetThereIsNothing() {
        val service = PreviewSelectionService.getInstance(project)
        service.remember("file:///a.md", SourceRange(10, 18), 1L)

        service.forget()

        assertNull(service.current())
    }

    /**
     * What a closing preview calls. Closing a page fires no selection change, so without this the
     * selection made there would still answer a right click somewhere else.
     */
    fun testClosingThePreviewThatOwnsTheSelectionDropsIt() {
        val service = PreviewSelectionService.getInstance(project)
        service.remember("file:///a.md", SourceRange(10, 18), 1L)

        service.forgetSelectionIn("file:///a.md")

        assertNull(service.current())
    }

    /**
     * The other half of the same rule: one preview closing must not take a selection a second, still
     * open preview has just made.
     */
    fun testClosingAnotherPreviewLeavesTheSelectionAlone() {
        val service = PreviewSelectionService.getInstance(project)
        service.remember("file:///a.md", SourceRange(10, 18), 1L)

        service.forgetSelectionIn("file:///b.md")

        assertEquals("file:///a.md", service.current()?.fileUrl)
    }

    /**
     * One holder per project, which is the whole reason this is a service at all. The browser's
     * callback thread stores through its own [PreviewSelectionService.getInstance] call and
     * `action/AddPreviewRemarkAction.kt` reads through a separate one on the EDT, so a lookup that
     * handed back a fresh instance would leave the action seeing nothing, every time.
     */
    fun testASelectionStoredThroughOneLookupIsReadBackThroughAnother() {
        PreviewSelectionService.getInstance(project).remember("file:///a.md", SourceRange(10, 18), 1L)

        assertEquals(SourceRange(10, 18), PreviewSelectionService.getInstance(project).current()?.range)
    }
}
