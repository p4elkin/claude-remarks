package dev.sasha.clauderemarks.ui

import com.intellij.openapi.ui.ComboBox
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import dev.sasha.clauderemarks.model.RemarkTag
import dev.sasha.clauderemarks.model.label
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.event.ActionEvent
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import javax.swing.AbstractAction
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.KeyStroke

/** What the user typed, once it has passed the rules below. */
data class RemarkInput(val text: String, val tag: RemarkTag?)

const val NO_TAG_LABEL = "(no tag)"

/** Action map keys. Public so the test can look them up instead of dispatching key events. */
const val SUBMIT_KEY = "claudeRemarks.submit"
const val NEWLINE_KEY = "claudeRemarks.newline"

/** The look and feel's own Enter action on a combo box, installed by BasicComboBoxUI. */
private const val COMBO_ENTER_KEY = "enterPressed"

/** The chooser's label for a tag, or the "no tag" entry. RemarkTag.label is the lowercase name. */
fun tagLabel(tag: RemarkTag?): String = tag?.label ?: NO_TAG_LABEL

fun tagFromLabel(label: String?): RemarkTag? =
    RemarkTag.entries.firstOrNull { it.name.equals(label, ignoreCase = true) }

/**
 * A remark with no text is not a remark. Returning null rather than storing an empty one keeps
 * "nothing is silently created" true alongside "nothing is silently deleted".
 */
fun remarkInputResult(rawText: String, tag: RemarkTag?): RemarkInput? {
    val text = rawText.trim()
    return if (text.isEmpty()) null else RemarkInput(text, tag)
}

/**
 * The box that opens at the caret. Enter submits, Shift+Enter inserts a newline, Esc is handled by
 * the popup itself through setCancelKeyEnabled(true).
 *
 * The Enter override is required, not a nicety: a plain JTextArea maps bare Enter to
 * insert-newline by default, so without replacing that binding Enter would never submit.
 */
class RemarkInputPanel(initialText: String, initialTag: RemarkTag?) : JPanel(BorderLayout(0, 4)) {

    val textArea = JBTextArea(initialText, 3, 48).apply {
        lineWrap = true
        wrapStyleWord = true
        emptyText.text = "Your remark. Enter to save, Shift+Enter for a new line, Esc to cancel."
    }

    val tagBox = ComboBox(
        arrayOf(NO_TAG_LABEL) + RemarkTag.entries.map { tagLabel(it) }.toTypedArray()
    ).apply { selectedItem = tagLabel(initialTag) }

    var onSubmit: ((RemarkInput) -> Unit)? = null

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

        // Enter from the tag chooser submits too, so tabbing to it is not a dead end. What it means
        // while the drop-down is open is in enterInTagBox below.
        tagBox.getInputMap(JComponent.WHEN_FOCUSED)
            .put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), SUBMIT_KEY)
        tagBox.actionMap.put(SUBMIT_KEY, object : AbstractAction() {
            override fun actionPerformed(e: ActionEvent) = enterInTagBox(tagBox.isPopupVisible, e)
        })

        add(JBScrollPane(textArea).apply { preferredSize = Dimension(520, 84) }, BorderLayout.CENTER)
        add(
            JPanel(FlowLayout(FlowLayout.LEFT, 6, 0)).apply {
                add(JLabel("Tag:"))
                add(tagBox)
            },
            BorderLayout.SOUTH,
        )
    }

    /**
     * What Enter means inside the tag chooser.
     *
     * With the drop-down closed it saves the remark. With the drop-down OPEN it belongs to the look
     * and feel, whose Enter action commits the item the arrow keys highlighted.
     *
     * The binding above sits in the chooser's own WHEN_FOCUSED map, and Swing consults that before
     * the WHEN_ANCESTOR_OF_FOCUSED_COMPONENT map the look and feel puts its Enter action in. A
     * non-editable combo box also keeps the focus while its drop-down shows, because the popup's
     * list is not focusable. So without this branch the plugin's action always won: arrowing down to
     * "bug" and pressing Enter saved the remark with the PREVIOUS tag and closed the drop-down, with
     * no second chance.
     *
     * [popupOpen] is a parameter rather than read from tagBox here, so both branches can be tested
     * without a real drop-down, which needs a window.
     */
    internal fun enterInTagBox(popupOpen: Boolean, event: ActionEvent) {
        if (!popupOpen) {
            submit()
            return
        }
        val lookAndFeelEnter = tagBox.actionMap.get(COMBO_ENTER_KEY)
        if (lookAndFeelEnter == null) tagBox.hidePopup() else lookAndFeelEnter.actionPerformed(event)
    }

    fun submit() {
        val result = remarkInputResult(textArea.text, tagFromLabel(tagBox.selectedItem as? String))
            ?: return
        onSubmit?.invoke(result)
    }
}
