package dev.sasha.clauderemarks.settings

import com.intellij.openapi.components.BaseState
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.SimplePersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service

/**
 * What is put at the top of every copied prompt. Modelled on revdiff: each remark is a directive,
 * and a remark that asks something is answered rather than turned into an edit.
 */
val DEFAULT_PROMPT_HEADER: String = """
    You are given a set of remarks left in an IDE while reading this codebase.

    Treat each remark as a directive about the code it points at.

    - A remark that asks something ("why is this...", "explain...", "is this...") is a QUESTION.
      Answer it in your reply. Do not change the code for it.
    - Any other remark is an INSTRUCTION. Carry it out.
    - A remark marked "orphaned" has stale line numbers: the code moved or changed after the
      remark was written. Find the code it means by reading the quoted lines, not by trusting the
      numbers.
    - In each code block, lines prefixed with ">" are the lines the remark points at. The other
      lines are surrounding context.

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
