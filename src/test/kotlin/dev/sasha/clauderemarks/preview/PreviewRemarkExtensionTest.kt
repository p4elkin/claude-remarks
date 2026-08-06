package dev.sasha.clauderemarks.preview

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.ui.UIUtil
import dev.sasha.clauderemarks.store.RemarkStore
import dev.sasha.clauderemarks.store.addRemark
import dev.sasha.clauderemarks.store.answer
import dev.sasha.clauderemarks.store.fileUnderProjectRoot
import dev.sasha.clauderemarks.store.recordAnswer
import dev.sasha.clauderemarks.store.settleInvocationQueue
import org.intellij.plugins.markdown.ui.preview.BrowserPipe
import org.intellij.plugins.markdown.ui.preview.MarkdownHtmlPanel
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * The push half of the preview highlight feature: that the extension pushes when the page says it is
 * ready and not before, pushes again on every REMARKS_CHANGED, pushes again after the source is
 * typed in, tells a question apart from an answered one, and never pushes again once disposed —
 * `preview/PreviewRemarkExtension.kt`'s `pushHighlights` and [PreviewRemarkExtension.dispose].
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

    /**
     * Keeps the subscriptions as well as what was sent, because half of what this class checks is
     * what happens when the PAGE posts something. [deliver] is what
     * `JcefBrowserPipeImpl.callSubscribers` does with an incoming message, minus the `takeWhile`:
     * every subscriber is called and every answer is kept, so a test can assert on the answers.
     */
    private class FakePipe : BrowserPipe {
        val sent = mutableListOf<Pair<String, String>>()
        private val handlers = mutableMapOf<String, MutableList<BrowserPipe.Handler>>()

        override fun send(type: String, data: String) {
            sent += type to data
        }

        override fun subscribe(type: String, handler: BrowserPipe.Handler) {
            handlers.getOrPut(type) { mutableListOf() }.add(handler)
        }

        override fun removeSubscription(type: String, handler: BrowserPipe.Handler) {
            handlers[type]?.remove(handler)
        }

        override fun dispose() {}

        fun deliver(type: String, data: String = ""): List<Boolean> =
            handlers[type].orEmpty().toList().map { it.processMessageReceived(data) }
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

    /**
     * The race this pins is the whole reason the first push waits for a message.
     * `MarkdownJCEFHtmlPanel.loadIndexContent` is `reloadExtensions()` and then
     * `waitForPageLoad(pageUrl)`, so this class is built before the browser starts loading the page
     * that carries the script. A push from the constructor is sent into a document with no
     * subscriber and is lost every time.
     */
    fun testNothingIsPushedBeforeThePageSaysItIsReady() {
        val file = fileUnderProjectRoot(project, "notes.md", "one\ntwo\n")
        addRemark(project, "notes.md", listOf("one", "two"), 1..1, "note")
        val pipe = FakePipe()

        val extension = PreviewRemarkExtension(fakePanel(project, file, pipe), pipe)
        try {
            settleInvocationQueue()

            assertEquals("the page does not exist yet, so nothing may be sent", 0, pipe.sent.size)
        } finally {
            extension.dispose()
        }
    }

    fun testAnAlreadyAnnotatedFileHighlightsAsSoonAsThePageIsReady() {
        val file = fileUnderProjectRoot(project, "notes.md", "one\ntwo\n")
        addRemark(project, "notes.md", listOf("one", "two"), 1..1, "note")
        val pipe = FakePipe()

        val extension = PreviewRemarkExtension(fakePanel(project, file, pipe), pipe)
        try {
            pipe.deliver(PAGE_READY)
            settleInvocationQueue()

            assertEquals(1, pipe.sent.size)
            assertEquals("claude-remarks/highlights", pipe.sent.single().first)
            assertTrue(pipe.sent.single().second.contains("\"kind\":\"remark\""))
        } finally {
            extension.dispose()
        }
    }

    /**
     * `documentReady` is the platform's own message, not this plugin's, and
     * `JcefBrowserPipeImpl.callSubscribers` walks its subscribers with `takeWhile`. A false answer
     * would silently stop every later subscriber for that type from running.
     */
    fun testThePageReadyHandlerLetsLaterSubscribersRun() {
        val file = fileUnderProjectRoot(project, "notes.md", "one\ntwo\n")
        val pipe = FakePipe()

        val extension = PreviewRemarkExtension(fakePanel(project, file, pipe), pipe)
        try {
            assertEquals(listOf(true), pipe.deliver(PAGE_READY))
        } finally {
            extension.dispose()
        }
    }

    fun testARemarkAddedAfterCreationPushesAgain() {
        val file = fileUnderProjectRoot(project, "notes.md", "one\ntwo\n")
        val pipe = FakePipe()

        val extension = PreviewRemarkExtension(fakePanel(project, file, pipe), pipe)
        try {
            pipe.deliver(PAGE_READY)
            settleInvocationQueue()
            // Pushed once for the ready page, with nothing yet in the store to highlight.
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
     * The two CSS classes the page picks between, decided in `computeHighlights` by pairing the
     * remark's own `asksForAnswer` with whether the answers list holds one for it. Nothing else in
     * the suite reaches that pairing, so deleting either half left the preview drawing every
     * question as a plain remark with every test still green.
     */
    fun testAQuestionPushesAsAQuestionUntilItIsAnswered() {
        val file = fileUnderProjectRoot(project, "notes.md", "one\ntwo\n")
        val question = addRemark(
            project, "notes.md", listOf("one", "two"), 1..1, "why is this here?",
            asksForAnswer = true,
        )
        val pipe = FakePipe()

        val extension = PreviewRemarkExtension(fakePanel(project, file, pipe), pipe)
        try {
            pipe.deliver(PAGE_READY)
            settleInvocationQueue()
            assertTrue(
                "a question still waiting must not draw like a plain remark",
                pipe.sent.last().second.contains("\"kind\":\"question\""),
            )

            recordAnswer(project, answer(id = "a-1", remarkId = question.id!!, path = "notes.md"))
            settleInvocationQueue()

            assertTrue(
                "an answered question draws like a plain remark, not as a third style",
                pipe.sent.last().second.contains("\"kind\":\"remark\""),
            )
        } finally {
            extension.dispose()
        }
    }

    /**
     * The claim `README.md` makes: the highlight survives typing in the source beside it. The preview
     * regenerates its HTML from the new source on every keystroke, so every position attribute after
     * the insertion point moves while the offsets the page holds were measured against the old text.
     * Nothing else re-pushes — typing fires no REMARKS_CHANGED.
     */
    fun testTypingInTheSourcePushesAgain() {
        val file = fileUnderProjectRoot(project, "typed.md", "one\ntwo\n")
        addRemark(project, "typed.md", listOf("one", "two"), 1..1, "note")
        val pipe = FakePipe()

        val extension = PreviewRemarkExtension(fakePanel(project, file, pipe), pipe)
        try {
            pipe.deliver(PAGE_READY)
            settleInvocationQueue()
            val before = pipe.sent.size

            myFixture.openFileInEditor(file)
            myFixture.type("x")

            assertTrue(
                "typing in the previewed source must push the highlights again",
                waitForPushAfter(pipe, before),
            )
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
        pipe.deliver(PAGE_READY)
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

    /**
     * The re-push after a source edit is debounced by a one-shot `javax.swing.Timer`, so it arrives
     * a fraction of a second later rather than inside the write action. This waits for it instead of
     * sleeping a fixed amount, and gives up well before a hung suite would.
     */
    private fun waitForPushAfter(pipe: FakePipe, before: Int): Boolean {
        repeat(200) {
            UIUtil.dispatchAllInvocationEvents()
            if (pipe.sent.size > before) return true
            Thread.sleep(10)
        }
        UIUtil.dispatchAllInvocationEvents()
        return pipe.sent.size > before
    }

    private companion object {
        /** The literal `JcefBrowserPipeImpl.WINDOW_READY_EVENT` holds, checked in the checkout. */
        const val PAGE_READY = "documentReady"
    }
}
