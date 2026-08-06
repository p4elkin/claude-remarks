package dev.sasha.clauderemarks.store

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.sasha.clauderemarks.model.RemarkStatus
import java.io.File
import java.nio.file.Files

/**
 * The thirteen functions that are the only way production code changes a remark or an answer. Each
 * one must both mutate and publish, so a caller cannot mutate without the tool window and the gutter
 * hearing about it.
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
        )

        assertEquals(1, heard)
        assertEquals("src/Foo.kt", stored.path)
        assertEquals(1, stored.startLine)
        assertEquals(2, stored.endLine)
        assertEquals("why beta?", stored.text)
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
        val stored = addGeneralRemark(project, "the whole change needs a second look")

        assertEquals(1, heard)
        assertNull(stored.path)
        assertEquals(0, stored.startLine)
        assertEquals(0, stored.endLine)
        assertNull(stored.textHash)
        assertNull(stored.contextBefore)
        assertNull(stored.contextAfter)
        assertEquals("the whole change needs a second look", stored.text)
        assertEquals(RemarkStatus.PENDING, stored.status)
        assertEquals(listOf(stored.id), RemarkStore.getInstance(project).all().map { it.id })
    }

    /** Same wiring `addRemark` already has for the head commit, reached through the other entry
     *  point: a remark about the whole change is still measured against a real revision. */
    fun testAddingAGeneralRemarkInsideAGitRepositoryStampsTheHeadCommit() {
        writeGitHead(SHA)

        assertEquals(SHA, addGeneralRemark(project, "note").commit)
    }

    fun testEditingARemarkPublishes() {
        val stored = addOne()

        editRemark(project, stored.id!!, "changed")

        assertEquals(2, heard)
        assertEquals("changed", RemarkStore.getInstance(project).all().single().text)
    }

    fun testEditingAnUnknownIdDoesNotPublish() {
        addOne()

        editRemark(project, "no-such-id", "changed")

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

    fun testMarkingReadStampsReadAt() {
        val stored = addOne()

        markRemarksRead(project, listOf(stored.id!!))

        assertTrue(RemarkStore.getInstance(project).all().single().readAt > 0)
    }

    /**
     * The behaviour that matters, not a nicety. Re-publishing and re-acknowledging the same
     * remark is ordinary in this plugin — Publish Unread takes everything not yet READ, and a
     * person may hand the same remark over twice. If the second acknowledgement moved `readAt`,
     * the remark would jump to the top of Done for no reason the person can see.
     *
     * The sleep is what gives the assertion teeth: without it, a buggy implementation that always
     * re-stamps with `System.currentTimeMillis()` could still land on the same millisecond and
     * pass by luck. Sleeping first guarantees the two stamps would differ if the second mark ever
     * wrote one.
     */
    fun testMarkingReadASecondTimeDoesNotMoveAnAlreadySetReadAt() {
        val stored = addOne()
        markRemarksRead(project, listOf(stored.id!!))
        val firstReadAt = RemarkStore.getInstance(project).all().single().readAt
        Thread.sleep(5)

        markRemarksPublished(project, listOf(stored.id!!))
        markRemarksRead(project, listOf(stored.id!!))

        assertEquals(firstReadAt, RemarkStore.getInstance(project).all().single().readAt)
    }

    fun testSettingAsksForAnswerPublishes() {
        val stored = addOne()

        setRemarkAsksForAnswer(project, listOf(stored.id!!), true)

        assertEquals(2, heard)
        assertTrue(RemarkStore.getInstance(project).all().single().asksForAnswer)
    }

    /** The same no-op rule markPublished and markRead follow: a toggle that changes nothing
     *  redraws nothing. */
    fun testSettingAsksForAnswerToWhatItAlreadyIsDoesNotPublish() {
        val stored = addOne()
        val before = heard

        setRemarkAsksForAnswer(project, listOf(stored.id!!), false)

        assertEquals(before, heard)
    }

    fun testClearingAsksForAnswerPublishesToo() {
        val stored = addOne()
        setRemarkAsksForAnswer(project, listOf(stored.id!!), true)
        val before = heard

        setRemarkAsksForAnswer(project, listOf(stored.id!!), false)

        assertEquals(before + 1, heard)
        assertFalse(RemarkStore.getInstance(project).all().single().asksForAnswer)
    }

    /** The Ask Claude gesture's half: the flag is carried by the remark `addRemark` stores, not set
     *  in a second call afterwards. */
    fun testAddingARemarkStoresTheAsksForAnswerFlagItWasGiven() {
        val stored = addRemark(
            project,
            path = "src/Foo.kt",
            lines = listOf("alpha", "beta"),
            range = 0..0,
            text = "why beta?",
            asksForAnswer = true,
        )

        assertTrue(stored.asksForAnswer)
        assertTrue(RemarkStore.getInstance(project).all().single().asksForAnswer)
    }

    /** Every ordinary entry point leaves the parameter alone, so an ordinary remark asks nothing. */
    fun testAddingARemarkTheOrdinaryWayAsksForNothing() {
        assertFalse(addOne().asksForAnswer)
    }

    fun testAddingAGeneralRemarkStoresTheAsksForAnswerFlagItWasGiven() {
        val stored = addGeneralRemark(project, "what is this change for?", asksForAnswer = true)

        assertTrue(stored.asksForAnswer)
        assertTrue(RemarkStore.getInstance(project).all().single().asksForAnswer)
    }

    fun testRecordingAnAnswerPublishes() {
        recordAnswer(project, answer(markdown = "because two threads write it"))

        assertEquals(1, heard)
        assertEquals(
            "because two threads write it",
            RemarkStore.getInstance(project).allAnswers().single().markdown,
        )
    }

    /**
     * The replacement rule reached through the function production code actually calls. `putAnswer`
     * is where the upsert lives, and this is the wiring that says `recordAnswer` really goes through
     * it — a version calling a plain add would leave two rows here.
     */
    fun testRecordingASecondAnswerForTheSameRemarkReplacesTheFirstAndPublishesAgain() {
        recordAnswer(project, answer(id = "a-1", markdown = "first"))

        recordAnswer(project, answer(id = "a-2", markdown = "second"))

        assertEquals(2, heard)
        val stored = RemarkStore.getInstance(project).allAnswers().single()
        assertEquals("a-2", stored.id)
        assertEquals("second", stored.markdown)
    }

    fun testDeletingAnAnswerPublishes() {
        recordAnswer(project, answer())

        deleteAnswer(project, "a-1")

        assertEquals(2, heard)
        assertEquals(0, RemarkStore.getInstance(project).allAnswers().size)
    }

    /** The same no-op rule every other mutator has: nothing changed, so nothing redraws. */
    fun testDeletingAnUnknownAnswerDoesNotPublish() {
        recordAnswer(project, answer())
        val before = heard

        deleteAnswer(project, "no-such-answer")

        assertEquals(before, heard)
        assertEquals(1, RemarkStore.getInstance(project).allAnswers().size)
    }

    /** Deletion is by the answer's own id, not by the remark's, so naming the remark takes nothing. */
    fun testDeletingByTheRemarkIdTakesNothing() {
        recordAnswer(project, answer(id = "a-1", remarkId = "r-1"))

        deleteAnswer(project, "r-1")

        assertEquals(1, RemarkStore.getInstance(project).allAnswers().size)
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
     * Clear All is the only thing that prunes answers, and it archives them on the way out. Both
     * lists go into one write, so the count covers both and the file holds both.
     */
    fun testClearAllArchivesAndClearsAnswersAsWellAsRemarks() {
        addOne()
        recordAnswer(project, answer(markdown = "because two threads write it"))
        val history = Files.createTempDirectory("h").resolve("history.md")

        assertEquals(2, clearAllRemarks(project, history))

        assertEquals(0, RemarkStore.getInstance(project).all().size)
        assertEquals(0, RemarkStore.getInstance(project).allAnswers().size)
        val written = Files.readString(history)
        assertTrue(written, written.contains("note"))
        assertTrue(written, written.contains("because two threads write it"))
    }

    /** A project holding answers and no remarks still has something for Clear All to take. */
    fun testClearAllTakesAnswersEvenWithNoRemarksLeft() {
        recordAnswer(project, answer())
        val history = Files.createTempDirectory("h").resolve("history.md")

        assertEquals(1, clearAllRemarks(project, history))

        assertEquals(0, RemarkStore.getInstance(project).allAnswers().size)
    }

    /**
     * An answer was never handed anywhere, so "handed over" says nothing about it: Clear Handed Over
     * archives and removes remarks alone, and the answer is still there afterwards with nothing about
     * it in the file.
     */
    fun testClearHandedOverLeavesAnswersAloneAndOutOfTheArchive() {
        val stored = addOne()
        markRemarksPublished(project, listOf(stored.id!!))
        recordAnswer(project, answer(markdown = "because two threads write it"))
        val history = Files.createTempDirectory("h").resolve("history.md")

        assertEquals(1, clearHandedOverRemarks(project, history))

        assertEquals(1, RemarkStore.getInstance(project).allAnswers().size)
        val written = Files.readString(history)
        assertFalse(written, written.contains("because two threads write it"))
        assertFalse(written, written.contains("### answers"))
    }

    /**
     * The rule that matters. A remark that could not be archived must still be in the store: an
     * archive that failed followed by a delete that succeeded is a remark lost with nothing said.
     */
    fun testNothingIsDeletedWhenTheHistoryFileCannotBeWritten() {
        addOne()
        recordAnswer(project, answer())
        val blocked = Files.createTempFile("blocked", ".txt").resolve("history.md")

        assertEquals(0, clearAllRemarks(project, blocked))

        assertEquals(1, RemarkStore.getInstance(project).all().size)
        // The same rule for the second list: one failed write leaves both exactly as they were.
        assertEquals(1, RemarkStore.getInstance(project).allAnswers().size)
    }

    private fun addOne() = addRemark(
        project,
        path = "src/Foo.kt",
        lines = listOf("alpha", "beta"),
        range = 0..0,
        text = "note",
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
