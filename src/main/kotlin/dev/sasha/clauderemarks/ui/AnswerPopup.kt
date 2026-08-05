package dev.sasha.clauderemarks.ui

import com.intellij.markdown.utils.doc.DocMarkdownToHtmlConverter
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.text.StringUtil
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
 *
 * [question] is the remark the answer replies to, drawn above the answer by [answerBodyHtml]. It is
 * built into the same string in the background, so the EDT is left with nothing but the popup.
 */
fun showAnswerPopup(project: Project, question: String, markdown: String) {
    ReadAction.nonBlocking<String> {
        answerBodyHtml(question, DocMarkdownToHtmlConverter.convert(project, markdown))
    }
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

/**
 * The whole popup body: the question that was asked, then the answer that came back.
 *
 * Without this the popup showed the answer alone, and a person reading one had no way to see what
 * they had asked. The order is the gutter tooltip's order — question first, answer under it — so the
 * hover and the popup cannot say opposite things about which is which.
 *
 * **The question is a quote block with a label, not a paragraph.** The answer is markdown a model
 * wrote and it may itself open with a heading, or with a blockquote of its own, so two plain
 * paragraphs would blur into one piece of text. `<blockquote>` draws a grey rule down the left in
 * `JBHtmlPane`'s own stylesheet and `<hr/>` draws a line under it, which is separation the answer
 * cannot accidentally imitate. The label is what settles the remaining ambiguity, because an answer
 * that genuinely begins with a quote would otherwise look like this block.
 *
 * ⚠️ **The question is escaped, never converted.** It is the person's own text, so "<" or "&" in it
 * is a character and not markup, and "*" or "#" is a character and not markdown. `RemarkGutterIcon`'s
 * own `asHtml` is the precedent every other place this plugin puts typed text into HTML follows.
 *
 * A blank question — what an answer whose remark was already gone carries — produces the answer
 * alone: no label, no empty quote block, no rule. Blank rather than empty, matching
 * `answerTooltipFor`, which skips a whitespace-only question for the same reason.
 *
 * Internal for the same reason [answerPane] is: it is what the popup is made of, and it can be
 * checked without a window.
 */
internal fun answerBodyHtml(question: String, answerHtml: String): String =
    if (question.isBlank()) answerHtml
    else "<blockquote><b>You asked</b><br/>${asHtml(question)}</blockquote><hr/>$answerHtml"

/**
 * Typed text as it has to appear inside HTML: escaped, and with newlines turned into breaks, because
 * a raw "\n" is only whitespace in HTML and a multi-line question would run together on one line.
 *
 * A second copy of `RemarkGutterIcon`'s private `asHtml` rather than a shared function: that one
 * belongs to the tooltip and this one to the popup, they are three lines, and pulling them together
 * would put a UI helper in a third file that neither side owns.
 */
private fun asHtml(text: String): String =
    StringUtil.escapeXmlEntities(text).replace("\n", "<br/>")
