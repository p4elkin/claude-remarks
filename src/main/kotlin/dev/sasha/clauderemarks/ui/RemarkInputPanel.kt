package dev.sasha.clauderemarks.ui

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogPanel
import com.intellij.openapi.util.SystemInfo
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
class RemarkInputPanel(
    private val project: Project,
    initialText: String,
    initialTag: RemarkTag?,
) : JPanel(BorderLayout(0, 4)) {

    val textArea = JBTextArea(initialText, 3, 48).apply {
        lineWrap = true
        wrapStyleWord = true
        emptyText.text = "Your remark. Enter saves, Shift+Enter adds a line, Alt+1-4 picks a tag, " +
            "$CLASS_NAME_STROKE_LABEL inserts a class name, Esc cancels."
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
     * previous tag. A chip row has no open state, so that branch is gone for everyone whose chips
     * really are chips.
     *
     * One case is left, and it is recorded rather than fixed: with a screen reader active,
     * SegmentedButtonImpl.rebuildUI builds a ComboBox instead of a chip row
     * (`ScreenReader.isActive() || items.size > maxButtonsCount` — the item count is fine, the
     * screen-reader branch is not). There the old hazard is back: Enter with the drop-down open saves
     * with the previous tag. A correct guard would have to read the combo popup's highlighted item
     * and commit it, which is more code than the case is worth for a path no test can reach. See the
     * known limits in the phase 5 plan.
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

        // See CLASS_NAME_STROKE for why this is not Ctrl+Space and why the modifier differs by
        // platform.
        map.put(CLASS_NAME_STROKE, CLASS_NAME_KEY)
        textArea.actionMap.put(CLASS_NAME_KEY, object : AbstractAction() {
            override fun actionPerformed(e: ActionEvent) = chooseClassName(project, textArea)
        })

        selectedTag = initialTag

        add(JBScrollPane(textArea).apply { preferredSize = Dimension(520, 84) }, BorderLayout.CENTER)
        add(chipRow, BorderLayout.SOUTH)
    }

    fun submit() {
        val result = remarkInputResult(textArea.text, selectedTag) ?: return
        onSubmit?.invoke(result)
    }
}
