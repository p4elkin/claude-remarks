package dev.sasha.clauderemarks.action

import com.intellij.openapi.actionSystem.Presentation
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.testFramework.TestActionEvent
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.sasha.clauderemarks.review.PublishedBatchService
import dev.sasha.clauderemarks.review.HookDecision
import dev.sasha.clauderemarks.review.HookOutcome
import dev.sasha.clauderemarks.review.handshakeDir
import dev.sasha.clauderemarks.review.hookName
import dev.sasha.clauderemarks.review.projectIdentity
import dev.sasha.clauderemarks.review.publishedName
import dev.sasha.clauderemarks.review.writePublished
import dev.sasha.clauderemarks.store.RemarkStore
import dev.sasha.clauderemarks.store.addGeneralRemark
import dev.sasha.clauderemarks.store.addRemark
import dev.sasha.clauderemarks.store.markRemarksPublished
import dev.sasha.clauderemarks.store.markRemarksRead
import java.nio.file.Path
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions

/**
 * Which remarks a publish takes, and how many files it says they cover. Publish All must leave out
 * the ones already handed over, and Publish Selected must take exactly the ids it was given even
 * when they are published — that pair IS the publish lifecycle, so it is the one part of this file
 * covered here, together with the file count and [publishMessage], the pure part of the
 * balloon text. Hook dispatch has coverage through [startPublishHook], including snapshot bytes
 * and call order. The remaining async pipeline — the clipboard and the balloon
 * actually shown — is checked by hand, per this file's own KDoc above and section 12 of the phase 9
 * plan: pumping a read action plus an EDT callback in a light fixture buys a flaky test for very
 * little.
 */
class PublishRemarksTest : BasePlatformTestCase() {

    override fun setUp() {
        super.setUp()
        // The light fixture project is shared across test classes, so the store is cleared here.
        // PublishedBatchService is shared the same way, and this file's own enablement test would
        // otherwise depend on what an earlier class left in it.
        RemarkStore.getInstance(project).clear()
        PublishedBatchService.getInstance(project).clear()
        Files.createDirectories(Path.of(project.basePath!!))
        deletePublishFiles()
    }

    override fun tearDown() {
        RemarkStore.getInstance(project).clear()
        PublishedBatchService.getInstance(project).clear()
        deletePublishFiles()
        super.tearDown()
    }

    private fun deletePublishFiles() {
        val root = projectIdentity(project) ?: return
        Files.deleteIfExists(handshakeDir().resolve(hookName(root.toString())))
        Files.deleteIfExists(handshakeDir().resolve(publishedName(root.toString())))
    }

    private fun writeHook(root: Path, port: Int, script: Path) {
        val dir = handshakeDir()
        Files.createDirectories(dir)
        val hook = dir.resolve(hookName(root.toString()))
        Files.writeString(hook, """{"argv":["/bin/sh","$script"],"label":"my review","port":$port}""")
        Files.setPosixFilePermissions(hook, PosixFilePermissions.fromString("rw-------"))
    }

    fun testHookReceivesEachPublishSnapshotInCallOrder() {
        val root = projectIdentity(project)!!
        val script = Files.createTempFile("publish-hook-", ".sh")
        val output = Files.createTempFile("publish-hook-", ".out")
        try {
            Files.writeString(script, "cat >> '$output'")
            writeHook(root, 8999, script)
            val tasks = mutableListOf<Runnable>()
            val outcomes = mutableListOf<Pair<HookOutcome, String>>()
            val first = "first publish: hyvä\n".toByteArray(Charsets.UTF_8)
            val second = "second publish: 日本語\n".toByteArray(Charsets.UTF_8)
            writePublished(root, first.toString(Charsets.UTF_8))
            assertEquals(HookDecision.Use(listOf("/bin/sh", script.toString()), "my review"),
                startPublishHook(root, first, 8999, handshakeDir(), { tasks.add(it) }, { outcome, label -> outcomes.add(outcome to label) }))
            writePublished(root, second.toString(Charsets.UTF_8))
            startPublishHook(root, second, 8999, handshakeDir(), { tasks.add(it) }, { outcome, label -> outcomes.add(outcome to label) })

            assertEquals(2, tasks.size)
            assertTrue(outcomes.isEmpty())
            tasks.forEach { it.run() }

            assertEquals((first + second).toString(Charsets.UTF_8), Files.readString(output))
            assertEquals(listOf(HookOutcome.Queued to "my review", HookOutcome.Queued to "my review"), outcomes)
        } finally {
            Files.deleteIfExists(script)
            Files.deleteIfExists(output)
        }
    }

    fun testAHookForAnotherIDEPortSkipsWithoutScheduling() {
        val root = projectIdentity(project)!!
        writeHook(root, 8999, Path.of("/unused-script"))
        val decision = startPublishHook(root, byteArrayOf(), 9000, handshakeDir(),
            { fail("A hook belonging to another IDE must not run") },
            { _, _ -> fail("A skipped hook has no outcome") })
        assertTrue(decision is HookDecision.Skip)
    }

    /**
     * Publish Unread's whole point: a remark already handed over through the clipboard or the
     * published file, but never acknowledged as read, is still unread, and a publish with no ids
     * takes it again. Only a remark a review has actually acknowledged is left out.
     */
    fun testAPublishWithNoIdsTakesEveryRemarkThatIsNotRead() {
        val pending = addRemark(project, "Foo.kt", LINES, 0..0, "still pending")
        val published = addRemark(project, "Foo.kt", LINES, 1..1, "published but not read")
        markRemarksPublished(project, listOf(published.id!!))
        val read = addRemark(project, "Foo.kt", LINES, 2..2, "already read")
        markRemarksPublished(project, listOf(read.id!!))
        markRemarksRead(project, listOf(read.id!!))

        assertEquals(setOf(pending.id, published.id), prepare(project, null).ids.toSet())
    }

    fun testAPublishWithIdsTakesExactlyThoseReadOnesIncluded() {
        val read = addRemark(project, "Foo.kt", LINES, 0..0, "already read")
        markRemarksPublished(project, listOf(read.id!!))
        markRemarksRead(project, listOf(read.id!!))
        addRemark(project, "Foo.kt", LINES, 1..1, "not selected")

        assertEquals(listOf(read.id), prepare(project, listOf(read.id!!)).ids)
    }

    fun testPublishSelectedTakesTheIdsItWasGivenEvenWhenTheyArePublished() {
        val published = addRemark(project, "Foo.kt", LINES, 0..0, "already handed over")
        addRemark(project, "Foo.kt", LINES, 1..1, "still waiting")
        markRemarksPublished(project, listOf(published.id!!))

        assertEquals(listOf(published.id), prepare(project, listOf(published.id!!)).ids)
    }

    /**
     * A general remark is about the whole change, so it carries no path — an empty string once
     * `collectForPrompt` has run. Counting it made the balloon say one file more than there were,
     * and publishing a single general remark said "across 1 file" with no file in sight.
     */
    fun testAGeneralRemarkIsNotCountedAsAFile() {
        addGeneralRemark(project, "about the whole change")
        addRemark(project, "Foo.kt", LINES, 0..0, "about one file")

        val prepared = prepare(project, null)

        assertEquals(2, prepared.ids.size)
        assertEquals(1, prepared.files)
    }

    fun testAPublishOfGeneralRemarksAloneCountsNoFilesAtAll() {
        addGeneralRemark(project, "about the whole change")

        assertEquals(0, prepare(project, null).files)
    }

    fun testNothingToPublishComesBackEmptyRatherThanRenderingAnEmptyPrompt() {
        val prepared = prepare(project, null)

        assertTrue(prepared.ids.isEmpty())
        assertEquals("", prepared.markdown)
    }

    fun testMessageSaysHowManyRemarksAndFilesWerePublished() {
        assertEquals(
            "Published 3 remarks across 2 files.",
            publishMessage(count = 3, files = 2, clipboardFile = null, writeFailure = null),
        )
    }

    fun testMessageNamesTheTempFileWhenThePayloadWasTooLargeForTheClipboard() {
        val file = Path.of("/tmp/claude-remarks-abc.md")

        assertEquals(
            "1 remark across 1 file was too large for the clipboard. Wrote $file and copied the path.",
            publishMessage(count = 1, files = 1, clipboardFile = file, writeFailure = null),
        )
    }

    /**
     * The reason is in the sentence, not only the fact. A permissions failure, a full disk and a
     * name the filesystem refuses all reach this balloon, and without the reason they read alike.
     */
    fun testMessageSaysWhyThePublishedFileWasNotUpdated() {
        assertEquals(
            "Published 2 remarks across 1 file, but the published file was not updated: " +
                "/Users/x/.claude-remarks: Permission denied.",
            publishMessage(
                count = 2,
                files = 1,
                clipboardFile = null,
                writeFailure = "/Users/x/.claude-remarks: Permission denied",
            ),
        )
    }

    /**
     * Publish Unread's enablement, on the Tools-menu action. The predicate is "not yet READ", not
     * "still PENDING": a remark published but never acknowledged is exactly what this action exists
     * to hand over again, and gating on PENDING would grey the action out for it.
     */
    fun testTheToolsMenuPublishStaysEnabledForAPublishedButUnreadRemark() {
        val published = addRemark(project, "Foo.kt", LINES, 0..0, "published but not read")
        markRemarksPublished(project, listOf(published.id!!))

        assertTrue(updatedPresentation().isEnabled)
    }

    /** The other side of the same predicate: with everything read there is nothing to publish. */
    fun testTheToolsMenuPublishIsDisabledOnceEveryRemarkIsRead() {
        val read = addRemark(project, "Foo.kt", LINES, 0..0, "already read")
        markRemarksPublished(project, listOf(read.id!!))
        markRemarksRead(project, listOf(read.id!!))

        assertFalse(updatedPresentation().isEnabled)
    }

    /** The Tools-menu action's own update(), run against a data context that names this project. */
    private fun updatedPresentation(): Presentation {
        val action = PublishUnreadRemarksAction()
        val event = TestActionEvent.createTestEvent(
            action,
            SimpleDataContext.getProjectContext(project),
        )
        action.update(event)
        return event.presentation
    }

    private companion object {
        val LINES = listOf("alpha", "beta")
    }
}
