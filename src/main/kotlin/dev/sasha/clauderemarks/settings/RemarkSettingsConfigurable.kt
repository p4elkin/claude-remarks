package dev.sasha.clauderemarks.settings

import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.ui.DialogPanel
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.Cell
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.dsl.builder.rows

class RemarkSettingsConfigurable : BoundConfigurable("Claude Remarks") {

    private val settings = RemarkSettings.getInstance()

    override fun createPanel(): DialogPanel = panel {
        lateinit var header: Cell<JBTextArea>
        row {
            label("Instructions put at the top of every copied prompt:")
        }
        row {
            // bindText is generic over <T : JTextComponent>, so it binds a text area as well as a
            // text field. AlignX.FILL is a nested object, not a companion field.
            header = textArea()
                .bindText(settings::promptHeader)
                .align(AlignX.FILL)
                .rows(16)
        }.resizableRow()
        row {
            // Writes the text area, NOT the service. An earlier version assigned
            // settings.promptHeader here, so pressing Restore Default and then Cancel threw the
            // user's own header away: Cancel calls reset(), which read back the default that had
            // already been persisted. Going through the field leaves Apply and Cancel meaning what
            // they say.
            button("Restore Default") { header.component.text = DEFAULT_PROMPT_HEADER }
        }
        row {
            comment(
                "Each remark below this text is listed with its file, line range, tag and the " +
                    "surrounding code. Leaving this blank restores the default."
            )
        }
    }
}
