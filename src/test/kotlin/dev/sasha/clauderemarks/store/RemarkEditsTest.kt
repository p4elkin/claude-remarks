package dev.sasha.clauderemarks.store

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.sasha.clauderemarks.model.RemarkSeverity
import dev.sasha.clauderemarks.model.RemarkStatus
import dev.sasha.clauderemarks.model.RemarkTag
import java.io.File
import java.nio.file.Files

/**
 * The ten functions that are the only way production code changes a remark. Each one must both
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

    /**
     * The one line in `addRemark` that wires `project.basePath` into `headCommit`. It was written off
     * as untestable because the light fixture has no git repository — but the fixture project has a
     * real base directory that these tests already write files into, so the same three files
     * `GitHeadTest` builds make it one. Mutation: drop the `commit =` line and this fails.
     */
    fun testAddingARemarkInsideAGitRepositoryStampsTheHeadCommit() {
        writeGitHead(SHA)

        assertEquals(SHA, addOne().commit)
    }

    /** No repository, no stamp — and still a remark. A missing commit is never a refusal to add. */
    fun testAddingARemarkOutsideAGitRepositoryStampsNothing() {
        assertNull(addOne().commit)
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

    /** The wiring half of "a sub-line remark stores the words it points at": the pure `phraseAt`
     *  reached through `addRemark` with real columns. */
    fun testAddingARemarkWithRealColumnsStoresThePhrase() {
        val stored = addRemark(
            project,
            path = "src/Foo.kt",
            lines = listOf("alpha", "beta", "gamma", "delta"),
            range = 1..1,
            text = "why beta?",
            tag = null,
            startColumn = 1,
            endColumn = 3,
        )

        assertEquals("et", stored.phrase)
    }

    /** `0 to 0` is the "no sub-line range" sentinel, so a whole-line remark stores no phrase. */
    fun testAddingARemarkWithNoColumnsStoresNoPhrase() {
        assertNull(addOne().phrase)
    }

    /** The wiring half of "a remark can be written about the whole change": no path, no line range
     *  beyond the whole-line sentinel, no textHash, no context — and it still publishes. */
    fun testAddingAGeneralRemarkStoresItWithNoPathAndPublishes() {
        val stored = addGeneralRemark(project, "the whole change needs a second look", RemarkTag.QUESTION)

        assertEquals(1, heard)
        assertNull(stored.path)
        assertEquals(0, stored.startLine)
        assertEquals(0, stored.endLine)
        assertNull(stored.textHash)
        assertNull(stored.contextBefore)
        assertNull(stored.contextAfter)
        assertEquals("the whole change needs a second look", stored.text)
        assertEquals(RemarkTag.QUESTION, stored.tag)
        assertEquals(RemarkStatus.PENDING, stored.status)
        assertEquals(listOf(stored.id), RemarkStore.getInstance(project).all().map { it.id })
    }

    /** Same wiring `addRemark` already has for the head commit, reached through the other entry
     *  point: a remark about the whole change is still measured against a real revision. */
    fun testAddingAGeneralRemarkInsideAGitRepositoryStampsTheHeadCommit() {
        writeGitHead(SHA)

        assertEquals(SHA, addGeneralRemark(project, "note", null).commit)
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

    fun testMarkingPublishedPublishesAndKeepsTheRemark() {
        val stored = addOne()

        markRemarksPublished(project, listOf(stored.id!!))

        assertEquals(2, heard)
        assertEquals(RemarkStatus.PUBLISHED, RemarkStore.getInstance(project).all().single().status)
    }

    fun testMarkingPublishedTwiceDoesNotPublishTheSecondTime() {
        val stored = addOne()
        markRemarksPublished(project, listOf(stored.id!!))
        val before = heard

        markRemarksPublished(project, listOf(stored.id!!))

        assertEquals(before, heard)
    }

    fun testMarkingReadPublishes() {
        val stored = addOne()

        markRemarksRead(project, listOf(stored.id!!))

        assertEquals(2, heard)
        assertEquals(RemarkStatus.READ, RemarkStore.getInstance(project).all().single().status)
    }

    fun testMarkingReadTwiceDoesNotPublishTheSecondTime() {
        val stored = addOne()
        markRemarksRead(project, listOf(stored.id!!))
        val before = heard

        markRemarksRead(project, listOf(stored.id!!))

        assertEquals(before, heard)
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

    fun testClearHandedOverPublishesOnlyWhenSomethingWentAway() {
        addOne()
        val history = tempHistory()

        clearHandedOverRemarks(project, history)
        assertEquals(1, heard)

        markRemarksPublished(project, RemarkStore.getInstance(project).all().map { it.id!! })
        clearHandedOverRemarks(project, history)

        assertEquals(3, heard)
        assertEquals(0, RemarkStore.getInstance(project).all().size)
    }

    fun testClearAllRemovesPendingOnesToo() {
        addOne()
        addOne()

        clearAllRemarks(project, tempHistory())

        assertEquals(3, heard)
        assertEquals(0, RemarkStore.getInstance(project).all().size)
    }

    /**
     * One archive file per project, not one shared by all of them. A version that dropped the
     * location hash would put two projects of the same name into the same file, and reading either
     * would mean reading both.
     */
    fun testTheHistoryFileIsPerProjectAndUnderItsOwnDirectory() {
        val file = historyFile(project)

        assertEquals("claude-remarks", file.parent.fileName.toString())
        assertTrue(file.fileName.toString(), file.fileName.toString().contains(project.locationHash))
    }

    fun testClearHandedOverWritesTheRemarksToTheHistoryFileFirst() {
        val stored = addOne()
        markRemarksPublished(project, listOf(stored.id!!))
        val history = Files.createTempDirectory("h").resolve("history.md")

        assertEquals(1, clearHandedOverRemarks(project, history))

        assertEquals(0, RemarkStore.getInstance(project).all().size)
        assertTrue(Files.readString(history).contains("note"))
    }

    fun testClearAllWritesEveryRemarkToTheHistoryFileFirst() {
        addOne()
        addOne()
        val history = Files.createTempDirectory("h").resolve("history.md")

        assertEquals(2, clearAllRemarks(project, history))

        assertEquals(0, RemarkStore.getInstance(project).all().size)
    }

    /**
     * The rule that matters. A remark that could not be archived must still be in the store: an
     * archive that failed followed by a delete that succeeded is a remark lost with nothing said.
     */
    fun testNothingIsDeletedWhenTheHistoryFileCannotBeWritten() {
        addOne()
        val blocked = Files.createTempFile("blocked", ".txt").resolve("history.md")

        assertEquals(0, clearAllRemarks(project, blocked))

        assertEquals(1, RemarkStore.getInstance(project).all().size)
    }

    private fun addOne() = addRemark(
        project,
        path = "src/Foo.kt",
        lines = listOf("alpha", "beta"),
        range = 0..0,
        text = "note",
        tag = null,
    )

    /**
     * Every archive test passes an explicit path. Left to the default, these tests would append to
     * the real `historyFile(project)` under the IDE configuration directory — which under Gradle is
     * the project's own test sandbox, so nothing of the developer's is touched, but the file is never
     * cleaned up and grows on every run.
     */
    private fun tempHistory() = Files.createTempDirectory("h").resolve("history.md")

    /** The same three files GitHeadTest writes: HEAD, and the loose ref it names. */
    private fun writeGitHead(sha: String) {
        val gitDir = File(project.basePath!!, ".git")
        File(gitDir, "refs/heads").mkdirs()
        File(gitDir, "HEAD").writeText("ref: refs/heads/main\n")
        File(gitDir, "refs/heads/main").writeText("$sha\n")
    }

    /** The light fixture project is shared, so a `.git` left behind would stamp the next class too. */
    override fun tearDown() {
        try {
            File(project.basePath!!, ".git").deleteRecursively()
        } finally {
            super.tearDown()
        }
    }

    private companion object {
        const val SHA = "0123456789abcdef0123456789abcdef01234567"
    }
}
