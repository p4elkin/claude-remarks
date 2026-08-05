package dev.sasha.clauderemarks.ui

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.sasha.clauderemarks.store.RemarkStore

/**
 * The menu itself is checked by hand. What is checked here is the part that would fail silently:
 * which items the menu offers at all.
 */
class RemarkActionsTest : BasePlatformTestCase() {

    override fun setUp() {
        super.setUp()
        // The light fixture project is shared across test classes.
        RemarkStore.getInstance(project).clear()
    }

    /**
     * The bucket item is the only way in the UI to put a remark in a bucket, so deleting it would
     * leave the whole bucket feature unreachable with nothing else noticing.
     */
    fun testThereIsABucketItem() {
        val group = remarkChangeActions(project) { emptyList() }

        val items = group.getChildren(null).map { it.templatePresentation.text }
        assertEquals(listOf("Move to Bucket…"), items)
    }
}
