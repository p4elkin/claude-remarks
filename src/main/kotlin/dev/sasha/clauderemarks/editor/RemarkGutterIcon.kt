package dev.sasha.clauderemarks.editor

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionGroup
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.Separator
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.markup.GutterIconRenderer
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.util.IconLoader
import com.intellij.openapi.util.text.StringUtil
import dev.sasha.clauderemarks.action.openRemarkEdit
import dev.sasha.clauderemarks.model.RemarkSeverity
import dev.sasha.clauderemarks.model.RemarkTag
import dev.sasha.clauderemarks.model.label
import dev.sasha.clauderemarks.store.RemarkStore
import dev.sasha.clauderemarks.store.deleteRemark
import dev.sasha.clauderemarks.ui.remarkChangeActions
import java.util.Objects
import javax.swing.Icon

private val PENDING_ICON: Icon = AllIcons.General.Note

/** Half transparent, so a remark you have already handed over reads as done without disappearing. */
private val SENT_ICON: Icon = IconLoader.getTransparentIcon(AllIcons.General.Note, 0.45f)

/** Everything the gutter needs about one remark, computed off the EDT. */
data class RemarkPlacement(
    val id: String,
    val text: String,
    val tag: RemarkTag?,
    val severity: RemarkSeverity,
    val commit: String?,
    val sent: Boolean,
    val startLine: Int,
    val endLine: Int,
    val orphaned: Boolean,
)

/**
 * The gutter tooltip, as HTML, because that is how the platform renders it.
 *
 * The remark text is escaped: it is user input, and an unescaped "<" swallows everything up to the
 * next ">". The newlines become <br/>, because a raw "\n" is whitespace in HTML and the orphaned
 * and sent notes would end up on the same line as the remark.
 *
 * The commit is here in full, unlike the tree, which shows it only on an orphaned row: a tooltip has
 * room and a tree row does not. It is cut to the same eight characters the tree's `writtenAt` and
 * the prompt heading use, so all three agree on what a short sha is.
 */
fun tooltipFor(placement: RemarkPlacement): String = buildString {
    append("<html>")
    append(StringUtil.escapeXmlEntities(placement.text).replace("\n", "<br/>"))
    placement.tag?.let { append("  [").append(it.label).append("]") }
    append("  ").append(placement.severity.label)
    placement.commit?.let { append("  commit ").append(it.take(8)) }
    if (placement.orphaned) append("<br/>(orphaned — these line numbers are stale)")
    if (placement.sent) append("<br/>(sent)")
    append("</html>")
}

/**
 * equals and hashCode are keyed on the remark id plus everything that changes what is painted.
 * The platform compares the old and the new renderer on every highlighting pass to decide whether
 * to repaint, so falling back to instance identity makes the icon flicker on every pass.
 *
 * getIcon is abstract, but inherited from GutterMark rather than declared here. getTooltipText and
 * getClickAction are concrete on GutterIconRenderer, so only what is needed is overridden.
 */
class RemarkGutterIconRenderer(
    private val project: Project,
    private val id: String,
    private val text: String,
    private val sent: Boolean,
) : GutterIconRenderer() {

    override fun getIcon(): Icon = if (sent) SENT_ICON else PENDING_ICON

    override fun getTooltipText(): String = text

    override fun getClickAction(): AnAction = DumbAwareAction.create { e ->
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return@create
        JBPopupFactory.getInstance()
            .createActionGroupPopup(
                "Claude Remark",
                menuFor(project, editor, id),
                e.dataContext,
                JBPopupFactory.ActionSelectionAid.SPEEDSEARCH,
                true,
            )
            .showInBestPositionFor(e.dataContext)
    }

    override fun equals(other: Any?): Boolean =
        other is RemarkGutterIconRenderer &&
            other.id == id && other.text == text && other.sent == sent

    override fun hashCode(): Int = Objects.hash(id, text, sent)
}

private fun menuFor(project: Project, editor: Editor, id: String): ActionGroup = DefaultActionGroup(
    DumbAwareAction.create("Edit Remark") {
        val stored = RemarkStore.getInstance(project).all().firstOrNull { it.id == id } ?: return@create
        openRemarkEdit(project, editor, id, stored.text.orEmpty(), stored.tag)
    },
    // The same group the tree's right-click menu uses. Here the id is fixed: it is the remark whose
    // icon was clicked.
    remarkChangeActions(project) { listOf(id) },
    Separator.getInstance(),
    DumbAwareAction.create("Delete Remark") { deleteRemark(project, id) },
)
