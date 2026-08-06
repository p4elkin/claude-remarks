package dev.sasha.clauderemarks.action

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.Project

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
            askClaudeFromPreview(p, path, lines, range, columns, typed)
        }
    }
}

/**
 * What this action does with the typed text, and the whole of what it does differently from
 * [AddPreviewRemarkAction]: the remark goes through [askClaude], which stores it marked as asking for
 * an answer and then publishes every question still waiting for one, this new one included.
 *
 * A named function rather than a lambda inside [AskClaudePreviewAction.actionPerformed], and with the
 * publish as a parameter, for exactly the reasons [askClaude]'s own KDoc gives: the input popup needs
 * a window, so a lambda on the far side of it is unreachable from a test, and the real publish
 * pipeline is deliberately not driven from one. What a test can then pin is that this entry point
 * calls [askClaude] at all — which is the single behaviour that makes it Ask Claude and not Add Claude
 * Remark.
 */
internal fun askClaudeFromPreview(
    project: Project,
    path: String,
    lines: List<String>,
    range: IntRange,
    columns: Pair<Int, Int>,
    typed: String,
    publish: (Project, Collection<String>) -> Unit = ::publishRemarks,
) {
    askClaude(project, path, lines, range, columns, typed, publish)
}
