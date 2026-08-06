package dev.sasha.clauderemarks.action

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent

private const val ASK_PREVIEW_HINT =
    "Ask Claude about the words selected in the rendered preview and get the answer back on the line"

/**
 * The preview's second entry point, beside [AddPreviewRemarkAction]. Same pair, one level down: this
 * is to [AddPreviewRemarkAction] what `action/AskClaudeAction.kt`'s `AskClaudeAction` is to
 * [AddRemarkAction] in the editor. Every check on whether a remark can be stored here — is there a
 * stored selection, does it belong to the preview the click happened in, does the file resolve under
 * the project root, is there an open Document, has the source moved since the browser reported the
 * selection — lives once, in [openPreviewRemarkInput], and this action supplies only the two things
 * that differ: the popup's title, and that the typed text is stored through [askClaude] rather than
 * [dev.sasha.clauderemarks.store.addRemark] directly. [askClaude] is the same function the editor
 * gesture calls: it sets `asksForAnswer = true` and publishes every question still waiting for an
 * answer, this one included, so asking from the preview is exactly as far-reaching as asking from the
 * editor.
 *
 * Registered in `META-INF/claude-remarks-markdown.xml`, into the markdown plugin's own
 * `Markdown.PreviewGroup`, beside `ClaudeRemarks.AddPreviewRemark`. Its id is not pinned by
 * `ActionIdsTest` for the same reason [AddPreviewRemarkAction]'s is not: the test fixture does not
 * load the markdown plugin, so an assertion about either action would fail on a correct build.
 * `README.md` names it instead.
 */
class AskClaudePreviewAction : AnAction() {

    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) = updatePreviewRemarkEntryPoint(e, ASK_PREVIEW_HINT)

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        openPreviewRemarkInput(project, e, "Ask Claude About These Words") { p, path, lines, range, columns, typed ->
            askClaude(p, path, lines, range, columns, typed)
        }
    }
}
