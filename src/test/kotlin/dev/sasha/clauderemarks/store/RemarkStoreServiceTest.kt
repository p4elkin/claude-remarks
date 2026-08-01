package dev.sasha.clauderemarks.store

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.sasha.clauderemarks.model.RemarkState

/**
 * Goes through the real project service, so the @Service / @State / @Storage wiring and
 * getInstance are exercised, not just the state class.
 *
 * The light fixture project is reused between tests, so the store is cleared in setUp.
 */
class RemarkStoreServiceTest : BasePlatformTestCase() {

    override fun setUp() {
        super.setUp()
        RemarkStore.getInstance(project).state.remarks.clear()
    }

    fun testAddedRemarkIsReadBackThroughTheService() {
        RemarkStore.getInstance(project).add(remark("r-1"))

        val loaded = RemarkStore.getInstance(project).all()

        assertEquals(1, loaded.size)
        assertEquals("r-1", loaded.single().id)
        assertEquals("src/Foo.kt", loaded.single().path)
        assertEquals(10, loaded.single().startLine)
    }

    fun testTheServiceIsTheSameInstanceForOneProject() {
        assertSame(RemarkStore.getInstance(project), RemarkStore.getInstance(project))
    }

    fun testAllReturnsACopy() {
        val store = RemarkStore.getInstance(project)
        store.add(remark("r-1"))
        val first = store.all()

        store.add(remark("r-2"))

        assertEquals(1, first.size)
        assertEquals(2, store.all().size)
    }

    fun testRemoveTakesTheRemarkOutOfTheService() {
        val store = RemarkStore.getInstance(project)
        store.add(remark("r-1"))

        assertTrue(store.remove("r-1"))

        assertFalse(store.remove("r-1"))
        assertTrue(RemarkStore.getInstance(project).all().isEmpty())
    }

    private fun remark(id: String) = RemarkState().also {
        it.id = id
        it.path = "src/Foo.kt"
        it.startLine = 10
        it.endLine = 12
        it.text = "why is this synchronized?"
        it.textHash = "abcdef0123456789"
    }
}
