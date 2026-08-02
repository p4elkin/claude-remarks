package dev.sasha.clauderemarks.store

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.sasha.clauderemarks.model.RemarkSeverity
import dev.sasha.clauderemarks.model.RemarkStatus
import dev.sasha.clauderemarks.model.RemarkTag

/**
 * The six functions that are the only way production code changes a remark. Each one must both
 * mutate and publish, so a caller cannot mutate without the tool window and the gutter hearing
 * about it.
 */
class RemarkEditsTest : BasePlatformTestCase() {

    private var heard = 0

    override fun setUp() {
        super.setUp()
        // The light fixture project is shared across test classes, so the store is cleared here.
        // Without this line three of the tests below fail on remarks left by an earlier method.
        RemarkStore.getInstance(project).clear()
        heard = 0
        project.messageBus.connect(testRootDisposable)
            .subscribe(REMARKS_CHANGED, RemarksListener { heard++ })
    }

    fun testAddingARemarkStoresItAndPublishes() {
        val stored = addRemark(
            project,
            path = "src/Foo.kt",
            lines = listOf("alpha", "beta", "gamma", "delta"),
            range = 1..2,
            text = "why beta?",
            tag = RemarkTag.QUESTION,
        )

        assertEquals(1, heard)
        assertEquals("src/Foo.kt", stored.path)
        assertEquals(1, stored.startLine)
        assertEquals(2, stored.endLine)
        assertEquals("why beta?", stored.text)
        assertEquals(RemarkTag.QUESTION, stored.tag)
        assertEquals(RemarkStatus.PENDING, stored.status)
        assertNotNull(stored.id)
        assertNotNull(stored.textHash)
        assertEquals(listOf(stored.id), RemarkStore.getInstance(project).all().map { it.id })
    }

    fun testAddingARemarkCapturesTheContextAroundIt() {
        val stored = addRemark(
            project,
            path = "src/Foo.kt",
            lines = listOf("a", "b", "c", "d", "e", "f", "g"),
            range = 3..3,
            text = "note",
            tag = null,
        )

        assertEquals(listOf("a", "b", "c"), splitContext(stored.contextBefore))
        assertEquals(listOf("e", "f", "g"), splitContext(stored.contextAfter))
    }

    fun testEditingARemarkPublishes() {
        val stored = addOne()

        editRemark(project, stored.id!!, "changed", RemarkTag.BUG)

        assertEquals(2, heard)
        assertEquals("changed", RemarkStore.getInstance(project).all().single().text)
    }

    fun testEditingAnUnknownIdDoesNotPublish() {
        addOne()

        editRemark(project, "no-such-id", "changed", null)

        assertEquals(1, heard)
    }

    fun testDeletingARemarkPublishes() {
        val stored = addOne()

        deleteRemark(project, stored.id!!)

        assertEquals(2, heard)
        assertEquals(0, RemarkStore.getInstance(project).all().size)
    }

    fun testMarkingSentPublishesAndKeepsTheRemark() {
        val stored = addOne()

        markRemarksSent(project, listOf(stored.id!!))

        assertEquals(2, heard)
        assertEquals(RemarkStatus.SENT, RemarkStore.getInstance(project).all().single().status)
    }

    fun testSettingTheSeverityPublishes() {
        val stored = addOne()

        setRemarkSeverity(project, listOf(stored.id!!), RemarkSeverity.MUST)

        assertEquals(2, heard)
        assertEquals(RemarkSeverity.MUST, RemarkStore.getInstance(project).all().single().severity)
    }

    fun testSettingTheSeverityToWhatItAlreadyIsDoesNotPublish() {
        val stored = addOne()

        setRemarkSeverity(project, listOf(stored.id!!), RemarkSeverity.SHOULD)

        assertEquals(1, heard)
    }

    fun testSettingTheBucketPublishes() {
        val stored = addOne()

        setRemarkBucket(project, listOf(stored.id!!), "auth refactor")

        assertEquals(2, heard)
        assertEquals("auth refactor", RemarkStore.getInstance(project).all().single().bucket)
    }

    /**
     * A bucket typed as "  " is not a bucket. Without the trim it becomes a group in the tree whose
     * label is invisible, and a second one every time somebody types a different amount of
     * whitespace.
     */
    fun testABlankBucketMeansNoBucket() {
        val stored = addOne()
        setRemarkBucket(project, listOf(stored.id!!), "  ")

        assertNull(RemarkStore.getInstance(project).all().single().bucket)
    }

    fun testABucketIsTrimmedBeforeItIsStored() {
        val stored = addOne()
        setRemarkBucket(project, listOf(stored.id!!), "  auth refactor  ")

        assertEquals("auth refactor", RemarkStore.getInstance(project).all().single().bucket)
    }

    fun testClearSentPublishesOnlyWhenSomethingWentAway() {
        addOne()

        clearSentRemarks(project)
        assertEquals(1, heard)

        markRemarksSent(project, RemarkStore.getInstance(project).all().map { it.id!! })
        clearSentRemarks(project)

        assertEquals(3, heard)
        assertEquals(0, RemarkStore.getInstance(project).all().size)
    }

    fun testClearAllRemovesPendingOnesToo() {
        addOne()
        addOne()

        clearAllRemarks(project)

        assertEquals(3, heard)
        assertEquals(0, RemarkStore.getInstance(project).all().size)
    }

    private fun addOne() = addRemark(
        project,
        path = "src/Foo.kt",
        lines = listOf("alpha", "beta"),
        range = 0..0,
        text = "note",
        tag = null,
    )
}
