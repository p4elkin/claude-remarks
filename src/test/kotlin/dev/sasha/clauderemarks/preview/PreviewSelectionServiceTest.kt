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

    fun testAStoredSelectionReadsBackWithBothItsFields() {
        val service = PreviewSelectionService.getInstance(project)

        service.remember("file:///Users/sasha/dev/claude-remarks/docs/plans/a-plan.md", SourceRange(10, 18))

        val stored = service.current()!!
        assertEquals("file:///Users/sasha/dev/claude-remarks/docs/plans/a-plan.md", stored.fileUrl)
        assertEquals(SourceRange(10, 18), stored.range)
    }

    fun testAfterForgetThereIsNothing() {
        val service = PreviewSelectionService.getInstance(project)
        service.remember("file:///a.md", SourceRange(10, 18))

        service.forget()

        assertNull(service.current())
    }

    /**
     * One holder per project, which is the whole reason this is a service at all. The browser's
     * callback thread stores through its own [PreviewSelectionService.getInstance] call and
     * `action/AddPreviewRemarkAction.kt` reads through a separate one on the EDT, so a lookup that
     * handed back a fresh instance would leave the action seeing nothing, every time.
     */
    fun testASelectionStoredThroughOneLookupIsReadBackThroughAnother() {
        PreviewSelectionService.getInstance(project).remember("file:///a.md", SourceRange(10, 18))

        assertEquals(SourceRange(10, 18), PreviewSelectionService.getInstance(project).current()?.range)
    }
}
