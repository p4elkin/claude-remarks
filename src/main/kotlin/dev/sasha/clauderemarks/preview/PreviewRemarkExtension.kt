package dev.sasha.clauderemarks.preview

import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.application.invokeLater
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.util.concurrency.AppExecutorUtil
import com.intellij.util.messages.MessageBusConnection
import dev.sasha.clauderemarks.anchor.AnchorResult
import dev.sasha.clauderemarks.store.REMARKS_CHANGED
import dev.sasha.clauderemarks.store.RemarkStore
import dev.sasha.clauderemarks.store.RemarksListener
import dev.sasha.clauderemarks.store.relativePathOf
import dev.sasha.clauderemarks.store.resolveAll
import org.intellij.plugins.markdown.extensions.MarkdownBrowserPreviewExtension
import org.intellij.plugins.markdown.ui.preview.BrowserPipe
import org.intellij.plugins.markdown.ui.preview.MarkdownHtmlPanel
import org.intellij.plugins.markdown.ui.preview.ResourceProvider
import javax.swing.Timer

/** The message type both halves agree on. The script posts it, this file subscribes to it. This
 *  one travels UP: the page's own selectionchange listener posts it. */
private const val SELECTION_MESSAGE_TYPE = "claude-remarks/selection"

/**
 * The message type [pushHighlights] sends. This one travels DOWN, the opposite direction from
 * [SELECTION_MESSAGE_TYPE]: from this class to the page, whenever the remarks for the previewed
 * file might have changed. The page's own subscribe side lives in `claude-remarks-preview.js`.
 */
private const val HIGHLIGHTS_MESSAGE_TYPE = "claude-remarks/highlights"

/**
 * The message the **page itself** posts once it is ready to receive anything, and the only safe
 * moment to send the first [HIGHLIGHTS_MESSAGE_TYPE] push.
 *
 * The literal is the platform's own, checked in the checkout rather than recalled: it is the value of
 * `JcefBrowserPipeImpl.WINDOW_READY_EVENT` (`~/dev/oss/intellij-community/plugins/markdown/core/src/
 * org/intellij/plugins/markdown/ui/preview/jcef/impl/JcefBrowserPipeImpl.kt`), and
 * `ui/preview/jcef/BrowserPipe.js` posts it from a listener on the `IdeReady` event the same class
 * dispatches at the end of its own injection. The constant itself is not visible from here — it is a
 * `private`-module member of the markdown plugin — so the string is written out.
 *
 * **Why this exists at all.** `MarkdownJCEFHtmlPanel.loadIndexContent` is `reloadExtensions()` and
 * then `waitForPageLoad(pageUrl)`, and `waitForPageLoad` is what actually navigates the browser. So
 * this class — and anything its constructor does — runs strictly *before* the page carrying
 * `claude-remarks-preview.js` starts loading. `BrowserPipe.send` queues nothing: it runs
 * `executeJavaScript` against whatever document is current, which at that moment has no subscriber
 * for [HIGHLIGHTS_MESSAGE_TYPE], and often no `__IntelliJTools` at all. A push from `init` is
 * therefore lost every time, and nothing recovers it — the page's own `MutationObserver` can only
 * re-apply a list it was already given. Waiting for this message is what makes a preview opened on an
 * already-annotated file highlight at all.
 */
private const val PAGE_READY_MESSAGE_TYPE = "documentReady"

/**
 * How long typing has to stop before the highlights are computed and pushed again.
 *
 * A re-push per keystroke would resolve every remark in the project on every character, and resolving
 * can cost a SHA-256 over every candidate position inside the 200-line search radius — the very cost
 * `editor/RemarkGutter.kt` avoids by never syncing on a keystroke at all. A quarter of a second of
 * quiet is far below what a person notices on a highlight and far above the gap between two
 * keystrokes.
 */
private const val REPUSH_DELAY_MS = 250

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
 * The stylesheet's name, following the exact same three-way convention [SCRIPT_NAME] documents:
 * resource in this plugin's own jar, last path segment of the URL the preview's static server serves
 * it from, and the name asked for in [PreviewRemarkExtension.canProvide].
 *
 * Served through [styles] rather than injected by the script as a `<style>` element, because
 * `MarkdownBrowserPreviewExtension` (`~/dev/oss/intellij-community/plugins/markdown/core/src/org/
 * intellij/plugins/markdown/extensions/MarkdownBrowserPreviewExtension.kt`) declares a `styles: List
 * <String>` alongside `scripts`, defaulting to `emptyList()` — checked directly in the platform
 * checkout, not assumed, since the plan itself did not know. `MarkdownJCEFHtmlPanel.buildIndexContent`
 * turns every name in it into a `<link rel="stylesheet">` in the page's own `<head>` the same way it
 * turns [scripts] into `<script src>` tags, through the platform's own static server and the same
 * [resourceProvider]. So overriding [styles] here needs no JS of our own to insert anything.
 */
private const val CSS_NAME = "claude-remarks-preview.css"

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
    panel: MarkdownHtmlPanel,
    private val browserPipe: BrowserPipe,
) : MarkdownBrowserPreviewExtension, ResourceProvider {

    /**
     * The project and the file this preview is showing, read once instead of on every use.
     *
     * `MarkdownJCEFHtmlPanel` takes both in its own constructor and holds them for its whole life, so
     * neither can change under this extension. Reading them once also keeps the number of calls into
     * `MarkdownHtmlPanel`'s three `@ApiStatus.Experimental` getters down to one each, which is what
     * `build.gradle.kts` subtracts `EXPERIMENTAL_API_USAGES` for.
     *
     * Both are null in the Compose preview renderer, whose panel carries neither. There is then
     * nothing to push and nothing to listen to.
     */
    private val previewedProject: Project? = panel.project
    private val previewedFile: VirtualFile? = panel.virtualFile

    private val handler = object : BrowserPipe.Handler {
        override fun processMessageReceived(data: String): Boolean {
            receive(data)
            // False stops the pipe from offering this message to any later subscriber. Nothing else
            // subscribes to this type — it names this plugin — so the value only says "handled".
            return false
        }
    }

    /**
     * Pushes the first list of highlights the moment the page says it can receive one. See
     * [PAGE_READY_MESSAGE_TYPE] for why nothing sent before this point ever arrives.
     */
    private val pageReadyHandler = object : BrowserPipe.Handler {
        override fun processMessageReceived(data: String): Boolean {
            pushHighlights()
            // True, unlike [handler] above, and the difference matters.
            // `JcefBrowserPipeImpl.callSubscribers` walks its subscribers with `takeWhile`, so a
            // false here would stop every later subscriber for this type. This type belongs to the
            // platform, not to this plugin, so it is not this plugin's to swallow.
            return true
        }
    }

    /**
     * Kept so [dispose] can disconnect it by hand, beside [browserPipe]'s own
     * `removeSubscription`. Deliberately not `project.messageBus.connect(this)`: that would tie
     * teardown to how the platform disposes this extension rather than to this file's own
     * [dispose], and every other subscription this class owns is already torn down explicitly
     * rather than left to a parent disposable. Null when [MarkdownHtmlPanel.getProject] was null
     * at construction time, in which case there is nothing to push and nothing to subscribe.
     */
    private var messageBusConnection: MessageBusConnection? = null

    /**
     * The debounce in front of [pushHighlights] for a source edit, the same one-shot
     * `javax.swing.Timer` restarted per event that `ui/RemarksToolWindowFactory.kt`'s `rewrapTimer`
     * uses for a resize. Typing produces a burst of events and only the last one is worth acting on.
     */
    private val repushTimer = Timer(REPUSH_DELAY_MS) { pushHighlights() }.apply { isRepeats = false }

    /**
     * Owns the document listener below, so [dispose] can drop it with one call. Unparented, and
     * disposed by hand, for the same reason [messageBusConnection] is: this class tears every
     * subscription down itself rather than resting on a `Disposer` parent it does not control.
     *
     * The listener is registered on `EditorFactory`'s multicaster rather than on the previewed
     * file's own `Document`, because getting that `Document` needs a read action and this
     * constructor has no guarantee of one. The multicaster's disposable-taking overload is the only
     * one that is not deprecated.
     */
    private val documentListenerOwner = Disposer.newDisposable("claude-remarks preview highlights")

    /**
     * Re-pushes the highlights after an edit to the previewed source, debounced by [repushTimer].
     *
     * Without this the highlights are silently wrong the moment a person types above an annotated
     * block. The preview regenerates its HTML from the new source on every keystroke, so every
     * `md-src-pos` after the insertion point shifts, while the offsets the page was last given were
     * measured against the old text. A whole-line remark stores column 0, so its offset is exactly
     * the block's own start: insert one character above it and the stale offset points at the
     * newline between two blocks, which no element covers, and the highlight disappears until some
     * unrelated `REMARKS_CHANGED` fires.
     */
    private val documentListener = object : DocumentListener {
        override fun documentChanged(event: DocumentEvent) {
            if (previewedFile == null) return
            if (FileDocumentManager.getInstance().getFile(event.document) != previewedFile) return
            repushTimer.restart()
        }
    }

    init {
        browserPipe.subscribe(SELECTION_MESSAGE_TYPE, handler)
        browserPipe.subscribe(PAGE_READY_MESSAGE_TYPE, pageReadyHandler)
        messageBusConnection = previewedProject?.messageBus?.connect()?.also {
            it.subscribe(REMARKS_CHANGED, RemarksListener { pushHighlights() })
        }
        EditorFactory.getInstance().eventMulticaster
            .addDocumentListener(documentListener, documentListenerOwner)
        // Nothing is pushed from here. The page does not exist yet — see PAGE_READY_MESSAGE_TYPE —
        // so a push here would be sent into a document with no subscriber and simply vanish.
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
        val project = previewedProject ?: return
        val file = previewedFile ?: return
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

    /**
     * Computes this file's highlights off the EDT, then sends them down the pipe once the read
     * action finishes on the UI thread — the same [ReadAction.nonBlocking] shape
     * `editor/RemarkGutter.kt`'s `scheduleSync` already uses, and for the same reason:
     * [computeHighlights] resolves remarks against a real `Document`, which
     * `store/RemarkResolver.kt` requires a read action for.
     *
     * Called from three places: the page saying it is ready ([PAGE_READY_MESSAGE_TYPE]), every
     * [REMARKS_CHANGED], and [repushTimer] after the source stopped changing. A project already
     * disposed by the time the read action finishes sends nothing, rather than pushing into a
     * pipe whose panel may already be gone.
     */
    private fun pushHighlights() {
        val project = previewedProject ?: return
        val file = previewedFile ?: return
        ReadAction.nonBlocking<List<PreviewHighlight>> { computeHighlights(project, file) }
            .expireWith(this)
            .coalesceBy(this, file)
            .finishOnUiThread(ModalityState.defaultModalityState()) { highlights ->
                if (!project.isDisposed) browserPipe.send(HIGHLIGHTS_MESSAGE_TYPE, toJson(highlights))
            }
            .submit(AppExecutorUtil.getAppExecutorService())
    }

    /**
     * Runs inside a read action, off the EDT. Turns the store's remarks for [file] into
     * [HighlightCandidate]s and hands them to [highlightsFor], the pure half in
     * `preview/PreviewHighlights.kt`.
     *
     * [HighlightCandidate.hasAnswer] is read straight off `RemarkStore.allAnswers()`, the same
     * call `editor/RemarkGutter.kt`'s `placementsFor` already makes for the same fact, rather than
     * through [dev.sasha.clauderemarks.store.resolveAnswers]'s anchoring: only whether an answer
     * exists is needed here, never where it resolves to.
     */
    private fun computeHighlights(project: Project, file: VirtualFile): List<PreviewHighlight> {
        val previewedPath = relativePathOf(project, file) ?: return emptyList()
        val document = FileDocumentManager.getInstance().getDocument(file) ?: return emptyList()
        val answeredIds = RemarkStore.getInstance(project).allAnswers().mapNotNull { it.remarkId }.toSet()
        val candidates = resolveAll(project).map { resolved ->
            val id = resolved.remark.id
            HighlightCandidate(
                path = resolved.remark.path,
                isOrphaned = resolved.result is AnchorResult.Orphaned,
                startLine = resolved.result.startLine,
                startColumn = resolved.startColumn,
                asksForAnswer = resolved.remark.asksForAnswer,
                hasAnswer = id != null && id in answeredIds,
            )
        }
        return highlightsFor(candidates, previewedPath, document.text)
    }

    override val resourceProvider: ResourceProvider = this

    override val scripts: List<String> = listOf(SCRIPT_NAME)

    override val styles: List<String> = listOf(CSS_NAME)

    override fun canProvide(resourceName: String): Boolean = resourceName == SCRIPT_NAME || resourceName == CSS_NAME

    override fun loadResource(resourceName: String): ResourceProvider.Resource? = when (resourceName) {
        SCRIPT_NAME -> ResourceProvider.loadInternalResource<PreviewRemarkExtension>(SCRIPT_NAME)
        CSS_NAME -> ResourceProvider.loadInternalResource<PreviewRemarkExtension>(CSS_NAME)
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
        browserPipe.removeSubscription(PAGE_READY_MESSAGE_TYPE, pageReadyHandler)
        // Beside the removeSubscription above, on purpose: this extension is created and
        // destroyed as often as a preview is, and a subscription left behind here would
        // accumulate one dead REMARKS_CHANGED listener per reload, each still trying to push into
        // a pipe whose page may already be gone — the leak this checkbox exists to close. The
        // document listener and its timer are the same leak in a second shape: the multicaster is
        // application-wide, so one left behind would keep firing for every edit of the file in
        // every later preview of it.
        messageBusConnection?.disconnect()
        repushTimer.stop()
        Disposer.dispose(documentListenerOwner)
        val project = previewedProject ?: return
        if (project.isDisposed) return
        val file = previewedFile ?: return
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
