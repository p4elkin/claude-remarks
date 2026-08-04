package dev.sasha.clauderemarks.action

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.sasha.clauderemarks.store.RemarkStore
import dev.sasha.clauderemarks.store.addGeneralRemark
import dev.sasha.clauderemarks.store.addRemark
import dev.sasha.clauderemarks.store.markRemarksPublished
import dev.sasha.clauderemarks.store.markRemarksRead
import java.nio.file.Path

/**
 * Which remarks a publish takes, and how many files it says they cover. Publish All must leave out
 * the ones already handed over, and Publish Selected must take exactly the ids it was given even
 * when they are published — that pair IS the publish lifecycle, so it is the one part of this file
 * worth an automated test, together with the file count and [publishMessage], the pure part of the
 * balloon text. The async pipeline itself — the clipboard, the published file write, the balloon
 * actually shown — is checked by hand, per this file's own KDoc above and section 12 of the phase 9
 * plan: pumping a read action plus an EDT callback in a light fixture buys a flaky test for very
 * little.
 */
class PublishRemarksTest : BasePlatformTestCase() {

    override fun setUp() {
        super.setUp()
        // The light fixture project is shared across test classes, so the store is cleared here.
        RemarkStore.getInstance(project).clear()
    }

    /**
     * Publish Unread's whole point: a remark already handed over through the clipboard or the
     * published file, but never acknowledged as read, is still unread, and a publish with no ids
     * takes it again. Only a remark a review has actually acknowledged is left out.
     */
    fun testAPublishWithNoIdsTakesEveryRemarkThatIsNotRead() {
        val pending = addRemark(project, "Foo.kt", LINES, 0..0, "still pending", null)
        val published = addRemark(project, "Foo.kt", LINES, 1..1, "published but not read", null)
        markRemarksPublished(project, listOf(published.id!!))
        val read = addRemark(project, "Foo.kt", LINES, 2..2, "already read", null)
        markRemarksPublished(project, listOf(read.id!!))
        markRemarksRead(project, listOf(read.id!!))

        assertEquals(setOf(pending.id, published.id), prepare(project, null).ids.toSet())
    }

    fun testAPublishWithIdsTakesExactlyThoseReadOnesIncluded() {
        val read = addRemark(project, "Foo.kt", LINES, 0..0, "already read", null)
        markRemarksPublished(project, listOf(read.id!!))
        markRemarksRead(project, listOf(read.id!!))
        addRemark(project, "Foo.kt", LINES, 1..1, "not selected", null)

        assertEquals(listOf(read.id), prepare(project, listOf(read.id!!)).ids)
    }

    fun testPublishSelectedTakesTheIdsItWasGivenEvenWhenTheyArePublished() {
        val published = addRemark(project, "Foo.kt", LINES, 0..0, "already handed over", null)
        addRemark(project, "Foo.kt", LINES, 1..1, "still waiting", null)
        markRemarksPublished(project, listOf(published.id!!))

        assertEquals(listOf(published.id), prepare(project, listOf(published.id!!)).ids)
    }

    /**
     * A general remark is about the whole change, so it carries no path — an empty string once
     * `collectForPrompt` has run. Counting it made the balloon say one file more than there were,
     * and publishing a single general remark said "across 1 file" with no file in sight.
     */
    fun testAGeneralRemarkIsNotCountedAsAFile() {
        addGeneralRemark(project, "about the whole change", null)
        addRemark(project, "Foo.kt", LINES, 0..0, "about one file", null)

        val prepared = prepare(project, null)

        assertEquals(2, prepared.ids.size)
        assertEquals(1, prepared.files)
    }

    fun testAPublishOfGeneralRemarksAloneCountsNoFilesAtAll() {
        addGeneralRemark(project, "about the whole change", null)

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

    private companion object {
        val LINES = listOf("alpha", "beta")
    }
}
