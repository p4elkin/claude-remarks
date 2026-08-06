package dev.sasha.clauderemarks.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.ui.DialogPanel
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.Cell
import com.intellij.ui.dsl.builder.Panel
import com.intellij.ui.dsl.builder.Row
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.dsl.builder.rows
import com.intellij.util.concurrency.AppExecutorUtil
import dev.sasha.clauderemarks.skill.SkillInstall
import dev.sasha.clauderemarks.skill.bundledPluginVersion
import dev.sasha.clauderemarks.skill.skillRowText
import java.nio.file.Path
import javax.swing.JButton
import javax.swing.JLabel

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
            //
            // The getter reads the STORED string, not RemarkSettings.promptHeader, which falls back
            // to the default when the stored one is blank. Binding to the falling-back getter left
            // Apply lit for ever once the box was cleared: the box said "" and the getter said the
            // default, so the page counted as modified no matter how often Apply was pressed. The
            // fallback still happens where it matters, when a prompt is rendered.
            header = textArea()
                .bindText({ settings.state.promptHeader.orEmpty() }, { settings.promptHeader = it })
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
                "Each remark below this text is listed with its file, line range and the " +
                    "surrounding code. Leaving this blank restores the default."
            )
        }

        group("Claude Remarks skill") {
            buildSkillRows()
        }
    }
}

/**
 * The skill-installation group: one row per detected harness, filled in asynchronously.
 *
 * **Threading.** This runs from `createPanel()`, on the EDT. Detection and the install both touch
 * only the filesystem — plain `java.nio`, never PSI, a `Document` or the VFS — so both run on
 * [AppExecutorUtil.getAppExecutorService] and report back through `invokeLater`. Deliberately no
 * `ReadAction` anywhere in this function: one would buy nothing here and would say something untrue
 * about what this code touches.
 *
 * ⚠️ **Both hops back pass [ModalityState.any], and a bare `invokeLater` is broken here.** Off the
 * EDT the default modality is `nonModal()`, the settings dialog is modal, and the platform's event
 * queue skips a runnable whose modality the current one does not accept — so with a bare
 * `invokeLater` the whole group stays on "Checking for Claude Code, Codex and Gemini…" for as long
 * as the page is open, and the Install button never appears. `any()` is the right answer rather than
 * a workaround precisely because these two blocks only set Swing text, enablement and visibility:
 * they touch no PSI, no `Document`, no VFS and no project model, so there is no state they could
 * read half-changed under a modal dialog.
 *
 * **No dialog and no balloon.** This configurable is application level and has no
 * [com.intellij.openapi.project.Project] to notify through; the row's own status label is the only
 * report there is.
 *
 * **Declaration order matters here and is deliberate.** Every `Cell`/`Row` this function later
 * mutates is declared `lateinit`/assigned before [refresh] and [installClaudeCode] are defined, and
 * both of those are defined before the row that wires the button to [installClaudeCode]. Kotlin
 * resolves a name at the point it is written, not at the point the closure that uses it finally
 * runs, so building this any other order risks an "unresolved reference" the compiler — not a
 * runtime check — would have to catch.
 */
private fun Panel.buildSkillRows() {
    row {
        comment(
            "Installs the skill this build of the plugin carries into a detected coding agent, so " +
                "it can read published remarks on its own schedule. This button only reaches this " +
                "machine — a Claude Code session on the far side of an SSH tunnel needs the skill " +
                "installed there separately."
        )
    }

    lateinit var claudeStatus: Cell<JLabel>
    lateinit var claudeButton: Cell<JButton>
    lateinit var codexStatus: Cell<JLabel>
    lateinit var geminiStatus: Cell<JLabel>
    lateinit var loadingRow: Row
    lateinit var noneRow: Row
    lateinit var claudeRow: Row
    lateinit var codexRow: Row
    lateinit var geminiRow: Row

    fun refresh() {
        AppExecutorUtil.getAppExecutorService().execute {
            val home = Path.of(System.getProperty("user.home"))
            val harnesses = SkillInstall.detectHarnesses(home)
            val bundledVersion = bundledPluginVersion() ?: "unknown"
            val claude = harnesses.find { it.displayName == "Claude Code" }
            val codex = harnesses.find { it.displayName == "Codex" }
            val gemini = harnesses.find { it.displayName == "Gemini" }
            val claudeText = claude?.let {
                skillRowText(true, SkillInstall.skillPresence(SkillInstall.claudeSkillDir(home)), bundledVersion)
            }
            val codexText = codex?.let { skillRowText(false, null, bundledVersion) }
            val geminiText = gemini?.let { skillRowText(false, null, bundledVersion) }

            ApplicationManager.getApplication().invokeLater({
                loadingRow.visible(false)
                noneRow.visible(harnesses.isEmpty())

                claudeRow.visible(claudeText != null)
                if (claudeText != null) {
                    claudeStatus.component.text = "Claude Code: ${claudeText.statusText}"
                    claudeButton.visible(claudeText.buttonLabel != null)
                    if (claudeText.buttonLabel != null) {
                        claudeButton.component.text = claudeText.buttonLabel
                        claudeButton.component.isEnabled = true
                    }
                }

                codexRow.visible(codexText != null)
                if (codexText != null) codexStatus.component.text = "Codex: ${codexText.statusText}"

                geminiRow.visible(geminiText != null)
                if (geminiText != null) geminiStatus.component.text = "Gemini: ${geminiText.statusText}"
            }, ModalityState.any())
        }
    }

    // The install itself. Runs on the pooled executor like refresh() above, and re-detects Claude
    // Code's target directory itself rather than trusting whatever the last refresh() found, so the
    // write always targets a fresh answer to "is it still there."
    fun installClaudeCode() {
        claudeButton.component.isEnabled = false
        AppExecutorUtil.getAppExecutorService().execute {
            val home = Path.of(System.getProperty("user.home"))
            val targetDir = SkillInstall.claudeSkillDir(home)
            val version = bundledPluginVersion()
            val problem = if (version == null) {
                "the bundled version could not be resolved, so nothing was installed."
            } else {
                SkillInstall.installSkill(targetDir, version) { name ->
                    SkillInstall::class.java.getResourceAsStream(SkillInstall.resourcePath(name))
                }
            }
            if (problem != null) {
                ApplicationManager.getApplication().invokeLater({
                    claudeStatus.component.text = "Claude Code: $problem"
                    claudeButton.component.isEnabled = true
                }, ModalityState.any())
            } else {
                refresh()
            }
        }
    }

    loadingRow = row { label("Checking for Claude Code, Codex and Gemini…") }
    noneRow = row {
        comment("No supported coding agent was found on this machine.")
    }.visible(false)
    claudeRow = row {
        claudeStatus = label("Claude Code")
        claudeButton = button("Install") { installClaudeCode() }
    }.visible(false)
    codexRow = row {
        codexStatus = label("Codex")
    }.visible(false)
    geminiRow = row {
        geminiStatus = label("Gemini")
    }.visible(false)

    refresh()
}
