package dev.sasha.clauderemarks.preview

import com.intellij.openapi.application.invokeLater
import com.intellij.openapi.fileEditor.FileDocumentManager
import org.intellij.plugins.markdown.extensions.MarkdownBrowserPreviewExtension
import org.intellij.plugins.markdown.ui.preview.BrowserPipe
import org.intellij.plugins.markdown.ui.preview.MarkdownHtmlPanel
import org.intellij.plugins.markdown.ui.preview.ResourceProvider

/** The message type both halves agree on. The script posts it, this file subscribes to it. */
private const val SELECTION_MESSAGE_TYPE = "claude-remarks/selection"

/**
 * The script's name, which is three things at once: the resource loaded from this plugin's own jar,
 * the last path segment of the URL the preview's static server serves it from, and the name asked
 * for in [PreviewRemarkExtension.canProvide]. The file has to sit beside this class in the resource
 * tree, under `dev/sasha/clauderemarks/preview/`, because
 * `ResourceProvider.loadInternalResource` resolves a relative name against this class's own loader
 * and package.
 */
private const val SCRIPT_NAME = "claude-remarks-preview.js"

/**
 * The browser half of a remark written on the rendered markdown preview: a script injected into the
 * preview page, and the handler that receives what it posts.
 *
 * **The page pushes, the IDE never asks.** The script listens for `selectionchange` and posts one
 * message; this class keeps the last one in [PreviewSelectionService], and the action in the
 * preview's right-click menu reads it. The rejected alternative was a request and a response — the
 * action asks the page what is selected and waits for the answer. Two things go wrong with it. The
 * action can never grey itself out, because enablement is decided before any answer could arrive.
 * And the code that opens the remark input box would have to live inside a pipe handler, on whatever
 * thread that turns out to be, instead of in an action on the EDT where every other entry point in
 * this plugin lives.
 *
 * **A pipe handler does not run on the EDT.** `JBCefJSQuery.addHandler` wraps the handler in a
 * `CefMessageRouterHandlerAdapter` and calls it straight from `onQuery`, which native code reaches
 * with no Java-side dispatch loop in between. So this handler parses the string it was handed —
 * [parseSelectionMessage] is pure — and hops to the EDT for everything else. Every caller in the
 * platform agrees: `CodeFenceCopyButtonBrowserExtension` and `CommandRunnerExtension` both wrap
 * their work in `invokeLater`, and the images plugin's `JCefImageViewer` uses
 * `SwingUtilities.invokeLater` inside its own handler.
 *
 * This class is one shape copied from `CodeFenceCopyButtonBrowserExtension` in the markdown plugin:
 * a provider is handed the panel, takes its pipe, subscribes to one message type, declares one
 * script by name, and serves the bytes from its own `ResourceProvider`.
 */
internal class PreviewRemarkExtension(
    private val panel: MarkdownHtmlPanel,
    private val browserPipe: BrowserPipe,
) : MarkdownBrowserPreviewExtension, ResourceProvider {

    private val handler = object : BrowserPipe.Handler {
        override fun processMessageReceived(data: String): Boolean {
            receive(data)
            // False stops the pipe from offering this message to any later subscriber. Nothing else
            // subscribes to this type — it names this plugin — so the value only says "handled".
            return false
        }
    }

    init {
        browserPipe.subscribe(SELECTION_MESSAGE_TYPE, handler)
    }

    /**
     * Turns one message from the page into the stored selection, or clears whatever was stored.
     *
     * Everything after the parse runs on the EDT, because the narrowing needs the `.md` source and
     * the source lives in a `Document`. The `Document` is the truth here rather than the file on
     * disk: the offsets came from a render of the text as the editor currently holds it, unsaved
     * edits included.
     *
     * A null range clears rather than keeps the old entry. Three things produce one: a message the
     * parser refuses, a file with no document, and offsets that no longer fit the source because the
     * file got shorter after the render. In all three the last thing the person selected is gone, and
     * a remark written from a stale entry would point at characters they never chose.
     *
     * The document's modification stamp is stored beside the range, read here rather than by the
     * action, because here is the one moment where the offsets and the text they were measured
     * against are both in hand. The action compares it again before it writes anything, which is what
     * catches a source edited between the page reporting a selection and the person right-clicking.
     */
    private fun receive(data: String) {
        val project = panel.project ?: return
        val file = panel.virtualFile ?: return
        val selection = parseSelectionMessage(data)
        invokeLater {
            if (project.isDisposed) return@invokeLater
            val service = PreviewSelectionService.getInstance(project)
            val document = FileDocumentManager.getInstance().getDocument(file)
            val source = document?.text
            val range = if (selection == null || source == null) null else narrowToSelection(source, selection)
            if (range == null || document == null) service.forget()
            else service.remember(file.url, range, document.modificationStamp)
        }
    }

    override val resourceProvider: ResourceProvider = this

    override val scripts: List<String> = listOf(SCRIPT_NAME)

    override fun canProvide(resourceName: String): Boolean = resourceName == SCRIPT_NAME

    override fun loadResource(resourceName: String): ResourceProvider.Resource? = when (resourceName) {
        SCRIPT_NAME -> ResourceProvider.loadInternalResource<PreviewRemarkExtension>(SCRIPT_NAME)
        else -> null
    }

    /**
     * The pipe outlives this extension. `MarkdownJCEFHtmlPanel.reloadExtensions` disposes every
     * extension and builds new ones while keeping the same pipe, so a subscription left behind would
     * accumulate one dead handler per reload, each still writing to the service.
     *
     * The stored selection goes too, because a closed page reports nothing. Closing a preview fires
     * no `selectionchange`, so the only other route to `forget` — the page saying the selection is
     * gone — never runs, and a selection made in a preview that is no longer on screen would still
     * answer the next right click somewhere else.
     *
     * [PreviewSelectionService.forgetSelectionIn], not `forget`: only the selection this preview's
     * own file owns is dropped. A plain clear would take a live selection another preview had just
     * stored.
     *
     * A reload disposes and rebuilds this extension while the page stays up, so a selection made
     * before a reload is dropped too. That costs one selection the person makes again, and the
     * alternative — keeping it — is the stale case this whole method is closing.
     */
    override fun dispose() {
        browserPipe.removeSubscription(SELECTION_MESSAGE_TYPE, handler)
        val project = panel.project ?: return
        if (project.isDisposed) return
        val file = panel.virtualFile ?: return
        PreviewSelectionService.getInstance(project).forgetSelectionIn(file.url)
    }

    /**
     * Registered in `META-INF/claude-remarks-markdown.xml`, which only loads when the markdown plugin
     * is there.
     *
     * A null pipe is the Compose preview renderer, whose panel returns none. The extension is then
     * simply never created and the right-click action finds nothing to act on, which is a documented
     * limit rather than a failure.
     */
    internal class Provider : MarkdownBrowserPreviewExtension.Provider {
        override fun createBrowserExtension(panel: MarkdownHtmlPanel): MarkdownBrowserPreviewExtension? {
            return panel.browserPipe?.let { PreviewRemarkExtension(panel, it) }
        }
    }
}
