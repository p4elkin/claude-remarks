package dev.sasha.clauderemarks.ui

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import java.awt.Dimension

/**
 * The popup a person reads an answer in, opened by double-clicking an answer row in the tree and,
 * once the answer's gutter icon exists, by clicking that icon.
 *
 * The answer is paragraphs, headings and code fences, so it gets a resizable popup with a scroll
 * pane rather than a balloon: Swing's own HTML renderer does not wrap a long line inside a code
 * block, and without both the person would be left with a clipped block and no way to see the rest.
 *
 * **This body is deliberately the plain-text step.** It shows the answer's raw markdown, which is
 * readable, and it is the fallback the specification names for the case where the platform's own
 * markdown conversion turns out not to be good enough. The markdown rendering replaces the text area
 * with a `JBHtmlPane` fed by `DocMarkdownToHtmlConverter`, converted off the EDT, and nothing but
 * the two lines that build the component changes — the popup around them is already the right shape.
 */
fun showAnswerPopup(project: Project, markdown: String) {
    val area = JBTextArea(markdown).apply {
        isEditable = false
        lineWrap = true
        wrapStyleWord = true
    }
    val scroll = JBScrollPane(area).apply { preferredSize = Dimension(640, 420) }
    JBPopupFactory.getInstance()
        .createComponentPopupBuilder(scroll, area)
        .setTitle("Claude Code answered")
        .setResizable(true)
        .setMovable(true)
        .setRequestFocus(true)
        .setCancelKeyEnabled(true)
        .createPopup()
        .showCenteredInCurrentWindow(project)
}
