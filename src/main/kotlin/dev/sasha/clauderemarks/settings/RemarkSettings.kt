package dev.sasha.clauderemarks.settings

import com.intellij.openapi.components.BaseState
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.SimplePersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service

/**
 * What is put at the top of every copied prompt. Modelled on revdiff: each remark is a directive
 * about the code it points at.
 *
 * It deliberately says nothing about which remarks are questions. It used to, in two bullets that
 * asked the model to sort each remark into QUESTION or INSTRUCTION by reading it. A remark that
 * asks for an answer now says so in its own heading, set when the remark was written, so there is
 * nothing left to guess. The marker's meaning is explained in `PROMPT_NOTES` rather than here,
 * because this text is editable in settings and a rewritten header would take the explanation with
 * it while the renderer kept printing the marker.
 */
val DEFAULT_PROMPT_HEADER: String = """
    You are given a set of remarks left in an IDE while reading this codebase.

    Treat each remark as a directive about the code it points at.

    - A remark marked "orphaned" has stale line numbers: the code moved or changed after the
      remark was written, so its own lines are not quoted. What is quoted for it instead, when
      anything is, are the lines that sat just above and just below it at the time. Search the
      file for those to find what the remark means. Do not trust the numbers.
    - In each code block, lines prefixed with ">" are the lines the remark points at. The other
      lines are surrounding context. An orphan's block has no ">" lines, for the reason above.

    Work through the remarks in the order they are listed. When you are done, say briefly what you
    changed and what you answered.
""".trimIndent()

/**
 * Application level, and roamed on purpose.
 *
 * No roamingType means RoamingType.DEFAULT, so this travels through JetBrains Settings Sync. That
 * is right for a prompt template you write once, and it is deliberately the opposite of the remark
 * data, which is stored with RoamingType.DISABLED because project-relative paths do not resolve on
 * another machine.
 *
 * SimplePersistentStateComponent is fine here. The reason RemarkStore does not use it is that its
 * state holds a list three threads reach, so the serializer must be handed a copy. One string read
 * on the EDT has no such problem.
 */
@Service(Service.Level.APP)
@State(name = "ClaudeRemarksSettings", storages = [Storage("remarksPluginSettings.xml")])
class RemarkSettings : SimplePersistentStateComponent<RemarkSettings.SettingsState>(SettingsState()) {

    class SettingsState : BaseState() {
        var promptHeader by string(DEFAULT_PROMPT_HEADER)
    }

    /**
     * Non-null on the way out, and the blank check lives in ONE place. A blank header would send
     * Claude a prompt with no instructions at all, which is worse than ignoring the edit.
     *
     * The setter stores what it is given. An earlier draft checked in both directions, which read
     * as belt and braces but was worse: with two checks, removing either one leaves the other
     * covering for it, so no test can pin either. One check, in the getter, is a fact a test can
     * hold on to.
     */
    var promptHeader: String
        get() = state.promptHeader?.takeIf { it.isNotBlank() } ?: DEFAULT_PROMPT_HEADER
        set(value) {
            state.promptHeader = value
        }

    companion object {
        fun getInstance(): RemarkSettings = service()
    }
}
