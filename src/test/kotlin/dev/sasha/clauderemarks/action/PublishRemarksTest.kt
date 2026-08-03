package dev.sasha.clauderemarks.action

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.sasha.clauderemarks.store.RemarkStore
import dev.sasha.clauderemarks.store.addRemark
import dev.sasha.clauderemarks.store.markRemarksPublished
import java.nio.file.Path

/**
 * Which remarks a publish takes. Publish All must leave out the ones already sent, and Publish
 * Selected must take exactly the ids it was given even when they are sent — that pair IS the
 * publish lifecycle, so it is the one part of this file worth an automated test, together with
 * [publishMessage], the pure part of the balloon text. The async pipeline itself — the clipboard,
 * the published file write, the balloon actually shown — is checked by hand, per this file's own
 * KDoc above and section 12 of the phase 9 plan: pumping a read action plus an EDT callback in a
 * light fixture buys a flaky test for very little.
 */
class PublishRemarksTest : BasePlatformTestCase() {

    override fun setUp() {
        super.setUp()
        // The light fixture project is shared across test classes, so the store is cleared here.
        RemarkStore.getInstance(project).clear()
    }

    fun testPublishAllLeavesOutRemarksThatWereAlreadySent() {
        val sent = addRemark(project, "Foo.kt", LINES, 0..0, "already handed over", null)
        val pending = addRemark(project, "Foo.kt", LINES, 1..1, "still waiting", null)
        markRemarksPublished(project, listOf(sent.id!!))

        assertEquals(listOf(pending.id), prepare(project, null).ids)
    }

    fun testPublishSelectedTakesTheIdsItWasGivenEvenWhenTheyAreSent() {
        val sent = addRemark(project, "Foo.kt", LINES, 0..0, "already handed over", null)
        addRemark(project, "Foo.kt", LINES, 1..1, "still waiting", null)
        markRemarksPublished(project, listOf(sent.id!!))

        assertEquals(listOf(sent.id), prepare(project, listOf(sent.id!!)).ids)
    }

    fun testNothingToPublishComesBackEmptyRatherThanRenderingAnEmptyPrompt() {
        val prepared = prepare(project, null)

        assertTrue(prepared.ids.isEmpty())
        assertEquals("", prepared.markdown)
    }

    fun testMessageSaysHowManyRemarksAndFilesWerePublished() {
        assertEquals(
            "Published 3 remarks across 2 files.",
            publishMessage(count = 3, files = 2, clipboardFile = null, writeFailed = false),
        )
    }

    fun testMessageNamesTheTempFileWhenThePayloadWasTooLargeForTheClipboard() {
        val file = Path.of("/tmp/claude-remarks-abc.md")

        assertEquals(
            "1 remark across 1 file was too large for the clipboard. Wrote $file and copied the path.",
            publishMessage(count = 1, files = 1, clipboardFile = file, writeFailed = false),
        )
    }

    fun testMessageSaysThePublishedFileWasNotUpdatedWhenTheWriteFailed() {
        assertEquals(
            "Published 2 remarks across 1 file, but the published file was not updated.",
            publishMessage(count = 2, files = 1, clipboardFile = null, writeFailed = true),
        )
    }

    private companion object {
        val LINES = listOf("alpha", "beta")
    }
}
