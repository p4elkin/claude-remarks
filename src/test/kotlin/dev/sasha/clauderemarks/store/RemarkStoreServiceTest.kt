package dev.sasha.clauderemarks.store

import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * Goes through the real project service, so the @Service / @State / @Storage wiring and
 * getInstance are exercised, not just the state class.
 *
 * The light fixture project is reused between tests, so the store is cleared in setUp.
 */
class RemarkStoreServiceTest : BasePlatformTestCase() {

    override fun setUp() {
        super.setUp()
        // Through the service's own API, not state.remarks.clear(): that would reach past the
        // @Synchronized methods the state class exists to funnel every mutation through.
        val store = RemarkStore.getInstance(project)
        store.all().forEach { store.remove(it.id.orEmpty()) }
    }

    fun testAddedRemarkIsReadBackThroughTheService() {
        RemarkStore.getInstance(project).add(remark(id = "r-1", startLine = 10))

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
        store.add(remark(id = "r-1"))
        val first = store.all()

        store.add(remark(id = "r-2"))

        assertEquals(1, first.size)
        assertEquals(2, store.all().size)
    }

    fun testRemoveTakesTheRemarkOutOfTheService() {
        val store = RemarkStore.getInstance(project)
        store.add(remark(id = "r-1"))

        assertTrue(store.remove("r-1"))

        assertFalse(store.remove("r-1"))
        assertTrue(RemarkStore.getInstance(project).all().isEmpty())
    }
}
