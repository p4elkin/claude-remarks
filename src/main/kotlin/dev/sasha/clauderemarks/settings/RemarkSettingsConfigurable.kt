package dev.sasha.clauderemarks.settings

import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.ui.DialogPanel
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.dsl.builder.rows

class RemarkSettingsConfigurable : BoundConfigurable("Claude Remarks") {

    private val settings = RemarkSettings.getInstance()

    override fun createPanel(): DialogPanel = panel {
        row {
            label("Instructions put at the top of every copied prompt:")
        }
        row {
            // bindText is generic over <T : JTextComponent>, so it binds a text area as well as a
            // text field. AlignX.FILL is a nested object, not a companion field.
            textArea()
                .bindText(settings::promptHeader)
                .align(AlignX.FILL)
                .rows(16)
        }.resizableRow()
        row {
            button("Restore Default") {
                settings.promptHeader = DEFAULT_PROMPT_HEADER
                reset()
            }
        }
        row {
            comment(
                "Each remark below this text is listed with its file, line range, tag and the " +
                    "surrounding code. Leaving this blank restores the default."
            )
        }
    }
}
