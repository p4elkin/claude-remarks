package dev.sasha.clauderemarks.ui

import com.intellij.markdown.utils.doc.DocMarkdownToHtmlConverter
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.util.Disposer
import com.intellij.ui.components.JBHtmlPane
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.concurrency.AppExecutorUtil
import dev.sasha.clauderemarks.action.notifyRemarks
import java.awt.Dimension
import java.util.concurrent.CancellationException

/**
 * The popup a person reads an answer in, opened by double-clicking an answer row in the tree and by
 * clicking the answer's gutter icon.
 *
 * An answer is headings, bullet lists, code fences and tables, so it is rendered rather than shown as
 * the markdown a model wrote. `DocMarkdownToHtmlConverter` is the platform's own converter for this
 * exact case — its KDoc names the Quick Doc popup — and it does three things a raw parser would not:
 * it colours code fences, it swaps tags the Swing HTML renderer handles badly, and it turns a
 * markdown table into a real table.
 *
 * **The conversion runs off the EDT.** `convert` is `@RequiresReadLock` and builds an intermediate
 * `PsiFile` for every fence it highlights, so on a long answer with several fences it is a stall a
 * person can feel. This is the same `ReadAction.nonBlocking` shape the publish pipeline, the gutter
 * sync and the tool window refresh all use.
 *
 * `expireWith(project)` is what stops a conversion still running when the project closes from
 * reaching the EDT afterwards.
 */
fun showAnswerPopup(project: Project, markdown: String) {
    ReadAction.nonBlocking<String> { DocMarkdownToHtmlConverter.convert(project, markdown) }
        .expireWith(project)
        .finishOnUiThread(ModalityState.defaultModalityState()) { html -> showRenderedAnswer(project, html) }
        .submit(AppExecutorUtil.getAppExecutorService())
        // Without this a converter that throws on one particular answer makes both the gutter-icon
        // click and the tree double-click do nothing at all, with no message — the dead gesture
        // `answerTooltipFor`'s "(click to read the whole answer)" promises against.
        .onError { error ->
            if (error !is ProcessCanceledException && error !is CancellationException) {
                notifyRemarks(
                    project,
                    "The answer could not be rendered: ${error.message ?: error}",
                    NotificationType.ERROR,
                )
            }
        }
}

/**
 * EDT. The popup itself, around HTML that is already converted.
 *
 * Three things here are load-bearing rather than decoration.
 *
 * `Disposer.register(popup, pane)`: [JBHtmlPane] implements `Disposable`, and nothing else in this
 * plugin builds a `Disposable` Swing component, so this is the one place the habit does not exist
 * yet. Without it the pane leaks quietly every time an answer is read.
 *
 * The scroll pane and `setResizable(true)`: Swing's HTML renderer does not wrap a long line inside a
 * code block, so a fence wider than the popup would be clipped with no way to reach the rest. With
 * both, the person can widen the popup or scroll it.
 *
 * `isEditable = false`: a `JEditorPane` is editable by default, and a caret blinking in an answer
 * invites typing into something that is not a text field.
 */
private fun showRenderedAnswer(project: Project, html: String) {
    val pane = answerPane(html)
    val scroll = JBScrollPane(pane).apply { preferredSize = Dimension(640, 420) }
    JBPopupFactory.getInstance()
        .createComponentPopupBuilder(scroll, pane)
        .setTitle("Claude Code answered")
        .setResizable(true)
        .setMovable(true)
        .setRequestFocus(true)
        .setCancelKeyEnabled(true)
        .createPopup()
        .also { Disposer.register(it, pane) }
        .showCenteredInCurrentWindow(project)
}

/**
 * The component the popup wraps, split out so it can be checked without a window: showing the popup
 * itself needs one, and what the popup is *made of* is the part that can silently regress — back to
 * a `JBTextArea` showing raw markdown, or to an editable pane with a caret blinking in it.
 *
 * Internal rather than private for exactly that reason; nothing else in `src/main` calls it.
 */
internal fun answerPane(html: String): JBHtmlPane = JBHtmlPane().apply {
    isEditable = false
    text = html
}
