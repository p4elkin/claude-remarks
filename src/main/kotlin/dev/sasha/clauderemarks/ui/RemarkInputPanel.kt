package dev.sasha.clauderemarks.ui

import com.intellij.openapi.ui.DialogPanel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.dsl.builder.SegmentedButton
import com.intellij.ui.dsl.builder.panel
import dev.sasha.clauderemarks.model.RemarkTag
import dev.sasha.clauderemarks.model.label
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.event.ActionEvent
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import javax.swing.AbstractAction
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.KeyStroke

/** What the user typed, once it has passed the rules below. */
data class RemarkInput(val text: String, val tag: RemarkTag?)

const val NO_TAG_LABEL = "(no tag)"

/** Action map keys. Public so the test can look them up instead of dispatching key events. */
const val SUBMIT_KEY = "claudeRemarks.submit"
const val NEWLINE_KEY = "claudeRemarks.newline"

/** Action map keys for the tag chips, one per entry in TAG_CHOICES. */
const val TAG_KEY_PREFIX = "claudeRemarks.tag."

/** The chips, "(no tag)" first, then the four tags in enum order. The Alt keys in the next task
 *  index into this list, so the order here is the order there. */
val TAG_CHOICES: List<String> = listOf(NO_TAG_LABEL) + RemarkTag.entries.map { tagLabel(it) }

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
        emptyText.text = "Your remark. Enter saves, Shift+Enter adds a line, Alt+1-4 picks a tag, Esc cancels."
    }

    // Assigned by the panel { } builder below, which runs eagerly, so it is set before the init
    // block. Declared first, because Kotlin initializes properties in declaration order.
    private lateinit var chips: SegmentedButton<String>

    /**
     * A row of chips instead of a drop-down. Adding a remark is the action that has to stay fast,
     * and reaching for a drop-down breaks the flow of typing a sentence and pressing Enter.
     *
     * It also removes a special case rather than adding one. With a drop-down, Enter meant "save"
     * or "commit the highlighted item" depending on whether the list was open, and the plugin's own
     * binding won both times — so arrowing to "bug" and pressing Enter saved the remark with the
     * previous tag. There is no open state on a chip row, so that whole branch is gone.
     */
    private val chipRow: DialogPanel = panel {
        row("Tag:") {
            // The lambda's receiver is the ItemPresentation and its argument is the item, checked
            // in the bytecode: the first parameter is named "$this$segmentedButton".
            chips = segmentedButton(TAG_CHOICES) { text = it }
        }
    }

    /** What the chips say, as a tag. The one place the label strings are converted back. */
    var selectedTag: RemarkTag?
        get() = tagFromLabel(chips.selectedItem)
        set(value) {
            chips.selectedItem = tagLabel(value)
        }

    /** The chip row's own focusable Swing component, exposed the same way [textArea] is: so a test
     *  can look up its key bindings directly instead of dispatching key events. */
    val tagChipsComponent: JComponent
        get() = chips.component ?: error("the chip row has no component yet")

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

        // The promise this popup makes is "type your remark, press Enter", and that has to hold
        // wherever focus lands inside it. The old drop-down carried its own Enter-submits binding
        // for the same reason (enterInTagBox); the chip row needs the same one, wired the same way,
        // because choosing a chip is immediate and leaves nothing else for Enter to do.
        tagChipsComponent.getInputMap(JComponent.WHEN_FOCUSED)
            .put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), SUBMIT_KEY)
        tagChipsComponent.actionMap.put(SUBMIT_KEY, object : AbstractAction() {
            override fun actionPerformed(e: ActionEvent) = submit()
        })

        // Alt+0 clears the tag, Alt+1..Alt+4 pick the four tags, in the order the chips show them.
        // The index into TAG_CHOICES is what ties the keys to the chips, so the two cannot drift.
        // VK_0 through VK_4 are contiguous key codes.
        TAG_CHOICES.forEachIndexed { index, label ->
            val key = "$TAG_KEY_PREFIX$index"
            map.put(KeyStroke.getKeyStroke(KeyEvent.VK_0 + index, InputEvent.ALT_DOWN_MASK), key)
            textArea.actionMap.put(key, object : AbstractAction() {
                override fun actionPerformed(e: ActionEvent) {
                    chips.selectedItem = label
                }
            })
        }

        selectedTag = initialTag

        add(JBScrollPane(textArea).apply { preferredSize = Dimension(520, 84) }, BorderLayout.CENTER)
        add(chipRow, BorderLayout.SOUTH)
    }

    fun submit() {
        val result = remarkInputResult(textArea.text, selectedTag) ?: return
        onSubmit?.invoke(result)
    }
}
