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
import com.intellij.openapi.util.text.StringUtil
import dev.sasha.clauderemarks.action.openRemarkEdit
import dev.sasha.clauderemarks.model.RemarkStatus
import dev.sasha.clauderemarks.store.RemarkStore
import dev.sasha.clauderemarks.store.deleteRemark
import dev.sasha.clauderemarks.ui.RemarkStatusLook
import dev.sasha.clauderemarks.ui.remarkChangeActions
import dev.sasha.clauderemarks.ui.showAnswerPopup
import java.util.Objects
import javax.swing.Icon

/** Everything the gutter needs about one remark, computed off the EDT. */
data class RemarkPlacement(
    val id: String,
    val text: String,
    val commit: String?,
    val status: RemarkStatus,
    /** Whether this remark asks Claude Code for an answer. Straight out of
     *  `RemarkState.asksForAnswer`, and shown as its own line in the tooltip. */
    val asksForAnswer: Boolean,
    val startLine: Int,
    val endLine: Int,
    val orphaned: Boolean,
    /** The exact source text a sub-line remark points at, straight out of `RemarkState.phrase`,
     *  or null for a whole-line remark. Shown in the tooltip, never in the tree row: the row
     *  already crops on the right, and the tooltip has room.
     *
     *  No default, unlike every other nullable field here would allow: both callers pass it, and a
     *  default would only make it possible to build a placement that quietly loses the phrase. */
    val phrase: String?,
)

/**
 * The gutter tooltip, as HTML, because that is how the platform renders it.
 *
 * The remark text is escaped: it is user input, and an unescaped "<" swallows everything up to the
 * next ">". The newlines become <br/>, because a raw "\n" is whitespace in HTML and the orphaned
 * note and the published or read note would end up on the same line as the remark.
 *
 * The commit is here in full, unlike the tree, which shows it only on an orphaned row: a tooltip has
 * room and a tree row does not. It is cut to the same eight characters the tree's `writtenAt` and
 * the prompt heading use, so all three agree on what a short sha is.
 *
 * The phrase, when there is one, gets its own line under the remark text, escaped and newline-broken
 * the same way the text itself is: it is arbitrary source text, so it can hold "<" or "&" as easily
 * as the remark text can. A whole-line remark has no phrase, and adds nothing here.
 *
 * The asks line comes last, the same place the tree row draws its own grey `asks` word. It says only
 * that the remark asks, never that it has been answered: an answer draws its own icon on the same
 * lines, so "answered" is already on screen as a second balloon rather than as a word here.
 */
fun tooltipFor(placement: RemarkPlacement): String = buildString {
    append("<html>")
    append(asHtml(placement.text))
    placement.phrase?.let { append("<br/>").append(asHtml(it)) }
    placement.commit?.let { append("  commit ").append(it.take(8)) }
    if (placement.orphaned) append("<br/>(orphaned — these line numbers are stale)")
    when (placement.status) {
        RemarkStatus.PUBLISHED -> append("<br/>(published)")
        RemarkStatus.READ -> append("<br/>(read)")
        RemarkStatus.PENDING -> {}
    }
    if (placement.asksForAnswer) append("<br/>(asks for an answer)")
    append("</html>")
}

/**
 * Everything the gutter needs about one answer, computed off the EDT — the same shape
 * [RemarkPlacement] has, for the same reason.
 *
 * [markdown] is carried here rather than looked up when the icon is clicked, so the popup opens the
 * body the icon was painted from and not whatever the store holds a moment later. That is the same
 * rule `ui/RemarksTree.kt`'s `AnswerNode` follows for a double click on an answer row.
 */
data class AnswerPlacement(
    val id: String,
    /** The remark's text, copied when the answer was stored. Empty when the remark was already gone. */
    val question: String,
    /** The first non-blank line of [markdown], the same preview the tree's answer row draws. */
    val firstLine: String,
    val markdown: String,
    val startLine: Int,
    val endLine: Int,
    val orphaned: Boolean,
)

/**
 * The answer's gutter tooltip, as HTML, for the same reason [tooltipFor] is.
 *
 * It carries the question first and the answer's first line under it, so hovering says both what was
 * asked and roughly what came back without opening anything. The question is skipped when it is
 * blank, which is what an answer to a remark that was already gone carries — printing an empty line
 * above the preview would only look broken.
 *
 * Both pieces are escaped: a question is whatever was typed, and an answer body is markdown a model
 * wrote, so either can hold "<" or "&".
 *
 * The last line says the icon can be clicked, because nothing else on screen does. A gutter icon
 * that opens something is not a convention a reader can assume.
 */
fun answerTooltipFor(placement: AnswerPlacement): String = buildString {
    append("<html>")
    if (placement.question.isNotBlank()) append(asHtml(placement.question)).append("<br/>")
    append(asHtml(placement.firstLine))
    if (placement.orphaned) append("<br/>(orphaned — these line numbers are stale)")
    append("<br/>(click to read the whole answer)")
    append("</html>")
}

/**
 * Arbitrary text as it has to appear inside a tooltip: escaped, because an unescaped "<" swallows
 * everything up to the next ">", and with newlines turned into breaks, because a raw "\n" is only
 * whitespace in HTML.
 */
private fun asHtml(text: String): String =
    StringUtil.escapeXmlEntities(text).replace("\n", "<br/>")

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
    private val status: RemarkStatus,
) : GutterIconRenderer() {

    override fun getIcon(): Icon = RemarkStatusLook.icon(status)

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
            other.id == id && other.text == text && other.status == status

    override fun hashCode(): Int = Objects.hash(id, text, status)
}

/**
 * The answer's own icon, on the lines the answer's own anchor resolves to.
 *
 * The answer is not hung off the remark's icon as a "Show Answer" menu item, and it does not wait
 * for its remark to be cleared before it draws. An answer outliving its question is the ordinary
 * case, and an icon that only sometimes appears makes the gutter conditional on something invisible.
 * The cost is two icons on the same lines while both exist, which IntelliJ stacks.
 *
 * `AllIcons.General.Balloon` is the same icon the tree's answer row draws, so the two places a
 * person meets an answer look like the same thing.
 *
 * equals and hashCode carry the same job they do on [RemarkGutterIconRenderer]: the platform
 * compares the old and the new renderer on every highlighting pass, so identity equality would make
 * the icon flicker. [markdown] is part of the key even though it is not drawn — it is what the click
 * opens, and an answer replaced in place has to open the new body.
 */
class AnswerGutterIconRenderer(
    private val project: Project,
    private val id: String,
    private val tooltip: String,
    /** The question the popup draws above the answer. Not part of equals and hashCode below, and it
     *  does not need to be: [tooltip] is built by [answerTooltipFor] from this very string, so two
     *  renderers with the same tooltip cannot carry different questions. */
    private val question: String,
    private val markdown: String,
) : GutterIconRenderer() {

    override fun getIcon(): Icon = AllIcons.General.Balloon

    override fun getTooltipText(): String = tooltip

    override fun getClickAction(): AnAction =
        DumbAwareAction.create { showAnswerPopup(project, question, markdown) }

    override fun equals(other: Any?): Boolean =
        other is AnswerGutterIconRenderer &&
            other.id == id && other.tooltip == tooltip && other.markdown == markdown

    override fun hashCode(): Int = Objects.hash(id, tooltip, markdown)
}

private fun menuFor(project: Project, editor: Editor, id: String): ActionGroup = DefaultActionGroup(
    DumbAwareAction.create("Edit Remark") {
        val stored = RemarkStore.getInstance(project).all().firstOrNull { it.id == id } ?: return@create
        openRemarkEdit(project, editor, id, stored.text.orEmpty())
    },
    // The same group the tree's right-click menu uses. Here the id is fixed: it is the remark whose
    // icon was clicked.
    remarkChangeActions(project) { listOf(id) },
    Separator.getInstance(),
    DumbAwareAction.create("Delete Remark") { deleteRemark(project, id) },
)
