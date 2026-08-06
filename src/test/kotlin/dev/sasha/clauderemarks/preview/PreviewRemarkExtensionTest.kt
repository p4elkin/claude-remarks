package dev.sasha.clauderemarks.preview

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.sasha.clauderemarks.store.RemarkStore
import dev.sasha.clauderemarks.store.addRemark
import dev.sasha.clauderemarks.store.fileUnderProjectRoot
import dev.sasha.clauderemarks.store.settleInvocationQueue
import org.intellij.plugins.markdown.ui.preview.BrowserPipe
import org.intellij.plugins.markdown.ui.preview.MarkdownHtmlPanel
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * The push half of the preview highlight feature: that the extension pushes highlights when it
 * is created, pushes again on every REMARKS_CHANGED, and never pushes again once disposed —
 * `preview/PreviewRemarkExtension.kt`'s [PreviewRemarkExtension.pushHighlights] and
 * [PreviewRemarkExtension.dispose].
 *
 * Fixture-backed, because computing a highlight resolves a remark against a real Document, the
 * same reason `editor/RemarkGutterTest.kt` needs one. Both [BrowserPipe] and [MarkdownHtmlPanel]
 * are plain interfaces with only a handful of members not already defaulted by the platform, so
 * both are faked directly here rather than left uncovered.
 */
class PreviewRemarkExtensionTest : BasePlatformTestCase() {

    override fun setUp() {
        super.setUp()
        // The light fixture project is shared across test classes.
        RemarkStore.getInstance(project).clear()
    }

    override fun tearDown() {
        RemarkStore.getInstance(project).clear()
        super.tearDown()
    }

    private class FakePipe : BrowserPipe {
        val sent = mutableListOf<Pair<String, String>>()
        override fun send(type: String, data: String) {
            sent += type to data
        }
        override fun subscribe(type: String, handler: BrowserPipe.Handler) {}
        override fun removeSubscription(type: String, handler: BrowserPipe.Handler) {}
        override fun dispose() {}
    }

    /**
     * Only the three members [PreviewRemarkExtension] actually reads — project, virtualFile,
     * browserPipe — are wired to real values. Everything else is a platform member this class
     * never calls, so a no-op body is honest rather than a stand-in for missing behaviour.
     */
    private fun fakePanel(testProject: Project, file: VirtualFile, pipe: BrowserPipe): MarkdownHtmlPanel =
        object : MarkdownHtmlPanel {
            override fun getComponent(): JComponent = JPanel()
            override fun setHtml(html: String, initialScrollOffset: Int, document: VirtualFile?) {}
            override fun reloadWithOffset(offset: Int) {}
            override fun addScrollListener(listener: MarkdownHtmlPanel.ScrollListener) {}
            override fun removeScrollListener(listener: MarkdownHtmlPanel.ScrollListener) {}
            override fun getBrowserPipe(): BrowserPipe = pipe
            override fun getProject(): Project = testProject
            override fun getVirtualFile(): VirtualFile = file
            override fun dispose() {}
        }

    fun testAnAlreadyAnnotatedFileHighlightsAsSoonAsThePreviewIsCreated() {
        val file = fileUnderProjectRoot(project, "notes.md", "one\ntwo\n")
        addRemark(project, "notes.md", listOf("one", "two"), 1..1, "note")
        val pipe = FakePipe()

        val extension = PreviewRemarkExtension(fakePanel(project, file, pipe), pipe)
        try {
            settleInvocationQueue()

            assertEquals(1, pipe.sent.size)
            assertEquals("claude-remarks/highlights", pipe.sent.single().first)
            assertTrue(pipe.sent.single().second.contains("\"kind\":\"remark\""))
        } finally {
            extension.dispose()
        }
    }

    fun testARemarkAddedAfterCreationPushesAgain() {
        val file = fileUnderProjectRoot(project, "notes.md", "one\ntwo\n")
        val pipe = FakePipe()

        val extension = PreviewRemarkExtension(fakePanel(project, file, pipe), pipe)
        try {
            settleInvocationQueue()
            // Pushed once on creation, with nothing yet in the store to highlight.
            assertEquals(1, pipe.sent.size)
            assertEquals("[]", pipe.sent.single().second)

            addRemark(project, "notes.md", listOf("one", "two"), 1..1, "note")
            settleInvocationQueue()

            assertEquals(2, pipe.sent.size)
            assertTrue(pipe.sent.last().second.contains("\"kind\":\"remark\""))
        } finally {
            extension.dispose()
        }
    }

    /**
     * The leak the plan calls out by name: a subscription left behind after dispose would keep
     * pushing into a pipe whose panel is already gone.
     */
    fun testDisposeStopsFurtherPushes() {
        val file = fileUnderProjectRoot(project, "notes.md", "one\ntwo\n")
        val pipe = FakePipe()
        val extension = PreviewRemarkExtension(fakePanel(project, file, pipe), pipe)
        settleInvocationQueue()
        val before = pipe.sent.size

        extension.dispose()
        addRemark(project, "notes.md", listOf("one", "two"), 1..1, "note")
        settleInvocationQueue()

        assertEquals(
            "a disposed extension must not still be listening for REMARKS_CHANGED",
            before,
            pipe.sent.size,
        )
    }
}
