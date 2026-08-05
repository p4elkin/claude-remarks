package dev.sasha.clauderemarks.ui

import com.intellij.openapi.project.Project
import com.intellij.openapi.util.SystemInfo
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.event.ActionEvent
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import javax.swing.AbstractAction
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.KeyStroke

/** Action map keys. Public so the test can look them up instead of dispatching key events. */
const val SUBMIT_KEY = "claudeRemarks.submit"
const val NEWLINE_KEY = "claudeRemarks.newline"

/** Action map key for the class-name chooser. */
const val CLASS_NAME_KEY = "claudeRemarks.className"

/**
 * The class-name chooser's keystroke: `Cmd+Ctrl+Shift+Space` on macOS, `Ctrl+Alt+Shift+Space`
 * everywhere else.
 *
 * NOT `Ctrl+Space`. The Alt keys are safe inside this popup because the platform does not dispatch a
 * modal-context-disabled action while a modal-context popup is focused, and `ActivateToolWindowAction`
 * is one of those. Basic Completion is not: `BaseCodeCompletionAction` calls
 * `setEnabledInModalContext(true)`, so `Ctrl+Space` really is offered to it here. On macOS
 * `Ctrl+Space` is also the OS input-source shortcut, which the IDE never sees at all.
 *
 * The modifier is platform-aware because this is a hardcoded Swing input-map binding, not a
 * plugin.xml keyboard shortcut the keymap could adapt. `Cmd` is `META_DOWN_MASK`, which on Windows
 * and Linux is the Super or Windows key — usually taken by the window manager, which would leave the
 * feature silently dead there. So `Alt` stands in for `Cmd` off macOS.
 */
val CLASS_NAME_STROKE: KeyStroke = KeyStroke.getKeyStroke(
    KeyEvent.VK_SPACE,
    InputEvent.CTRL_DOWN_MASK or InputEvent.SHIFT_DOWN_MASK or
        (if (SystemInfo.isMac) InputEvent.META_DOWN_MASK else InputEvent.ALT_DOWN_MASK),
)

/** The same keystroke as words, for the placeholder text. */
val CLASS_NAME_STROKE_LABEL: String =
    if (SystemInfo.isMac) "Cmd+Ctrl+Shift+Space" else "Ctrl+Alt+Shift+Space"

/**
 * A remark with no text is not a remark. Returning null rather than storing an empty one keeps
 * "nothing is silently created" true alongside "nothing is silently deleted".
 *
 * The trimmed text is the whole result. It used to be a `RemarkInput` pair, text beside a tag, until
 * phase 11 took the tag off a remark: a tag was never once picked, so the chip row, its five Alt
 * keys and the pair that carried its answer all went with it.
 */
fun remarkInputResult(rawText: String): String? {
    val text = rawText.trim()
    return if (text.isEmpty()) null else text
}

/**
 * The box that opens at the caret. Enter submits, Shift+Enter inserts a newline, Esc is handled by
 * the popup itself through setCancelKeyEnabled(true).
 *
 * The Enter override is required, not a nicety: a plain JTextArea maps bare Enter to
 * insert-newline by default, so without replacing that binding Enter would never submit.
 */
class RemarkInputPanel(
    private val project: Project,
    initialText: String,
) : JPanel(BorderLayout(0, 4)) {

    val textArea = JBTextArea(initialText, 3, 48).apply {
        lineWrap = true
        wrapStyleWord = true
        emptyText.text = "Your remark. Enter saves, Shift+Enter adds a line, " +
            "$CLASS_NAME_STROKE_LABEL inserts a class name, Esc cancels."
    }

    var onSubmit: ((String) -> Unit)? = null

    init {
        val map = textArea.getInputMap(JComponent.WHEN_FOCUSED)
        map.put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), SUBMIT_KEY)
        map.put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, InputEvent.SHIFT_DOWN_MASK), NEWLINE_KEY)
        textArea.actionMap.put(SUBMIT_KEY, object : AbstractAction() {
            override fun actionPerformed(e: ActionEvent) = submit()
        })
        textArea.actionMap.put(NEWLINE_KEY, object : AbstractAction() {
            // replaceSelection, not insert(text, caretPosition): insert ignores a selection, so
            // Shift+Enter over selected text would keep the text and add a newline beside it.
            override fun actionPerformed(e: ActionEvent) = textArea.replaceSelection("\n")
        })

        // See CLASS_NAME_STROKE for why this is not Ctrl+Space and why the modifier differs by
        // platform.
        map.put(CLASS_NAME_STROKE, CLASS_NAME_KEY)
        textArea.actionMap.put(CLASS_NAME_KEY, object : AbstractAction() {
            override fun actionPerformed(e: ActionEvent) = chooseClassName(project, textArea)
        })

        add(JBScrollPane(textArea).apply { preferredSize = Dimension(520, 84) }, BorderLayout.CENTER)
    }

    fun submit() {
        val result = remarkInputResult(textArea.text) ?: return
        onSubmit?.invoke(result)
    }
}
