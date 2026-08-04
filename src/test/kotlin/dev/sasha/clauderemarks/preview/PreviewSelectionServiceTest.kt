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

    fun testAStoredSelectionReadsBack() {
        val service = PreviewSelectionService.getInstance(project)

        service.remember("file:///Users/sasha/dev/claude-remarks/docs/plans/a-plan.md", SourceRange(10, 18))

        assertEquals(SourceRange(10, 18), service.current()!!.range)
    }

    fun testASecondStoreReplacesTheFirst() {
        val service = PreviewSelectionService.getInstance(project)

        service.remember("file:///a.md", SourceRange(10, 18))
        service.remember("file:///b.md", SourceRange(200, 240))

        val stored = service.current()!!
        assertEquals("file:///b.md", stored.fileUrl)
        assertEquals(SourceRange(200, 240), stored.range)
    }

    fun testAfterForgetThereIsNothing() {
        val service = PreviewSelectionService.getInstance(project)
        service.remember("file:///a.md", SourceRange(10, 18))

        service.forget()

        assertNull(service.current())
    }

    fun testTheStoredFileUrlIsTheOneThatWasPassedIn() {
        val service = PreviewSelectionService.getInstance(project)

        service.remember("file:///Users/sasha/dev/claude-remarks/README.md", SourceRange(0, 5))

        assertEquals("file:///Users/sasha/dev/claude-remarks/README.md", service.current()!!.fileUrl)
    }
}
