package dev.sasha.clauderemarks.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.ui.SimpleColoredComponent
import com.intellij.ui.SimpleTextAttributes
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import dev.sasha.clauderemarks.anchor.AnchorResult
import dev.sasha.clauderemarks.anchor.positionLabel
import dev.sasha.clauderemarks.model.RemarkStatus
import dev.sasha.clauderemarks.store.ResolvedAnswer
import dev.sasha.clauderemarks.store.ResolvedRemark
import dev.sasha.clauderemarks.store.isAboutNoFile
import java.awt.Component
import java.awt.FontMetrics
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import javax.accessibility.AccessibleContext
import javax.swing.Icon
import javax.swing.JPanel
import javax.swing.JTree
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.TreeCellRenderer

/**
 * The key of the group that holds every remark about no file. A file key always starts with
 * "file:", so this bare word cannot collide with one, and `RemarksPanel`'s selection restore,
 * which matches groups by key, keeps working.
 */
const val GENERAL_KEY = "general"

/** The label drawn on that group, its own constant rather than written inline at the one place the
 *  node is built. */
const val GENERAL_LABEL = "General"

/**
 * The key of the group that holds every answer with no question left in the tree. A file key always
 * starts with "file:", and [GENERAL_KEY] is the bare word "general", so this second bare word cannot
 * collide with either — the same argument [GENERAL_KEY] makes.
 *
 * The group used to hold every answer, and the key is deliberately unchanged now that it holds only
 * some of them: `RemarksPanel` records collapsed groups by key, so keeping the word means a person
 * who had this group shut keeps it shut across the upgrade.
 */
const val ANSWERS_KEY = "answers"

/**
 * The label drawn on that group, beside [GENERAL_LABEL] for the same reason.
 *
 * ⚠️ The word "orphaned" is deliberately avoided. The tree already writes "(orphaned…)" on a row
 * whose *code* could not be found, which is a different state entirely — such a row's question may
 * be perfectly alive, and an answer in this group may point at code that resolves exactly. One word
 * for two states in one tree is how a person learns to distrust both.
 */
const val ANSWERS_LABEL = "Answers with no question"

/**
 * The key of the top-level group holding every remark still waiting: not `READ`, and with no answer
 * under it. A bare word, the same shape as [GENERAL_KEY] and [ANSWERS_KEY] and safe for the same
 * reason.
 *
 * ⚠️ Every group *inside* Open or Done carries its own side's key as a prefix — "open/general",
 * "done/file:src/Foo.kt". One file can hold an open remark and a processed one at the same time, and
 * then that file gets a group on each side. `RemarksPanel` matches groups by key alone, both to put
 * a selection back after a rebuild and to shut again what was shut, so two groups sharing one key
 * would collapse together and select together — and a selected group is what Delete acts on.
 */
const val OPEN_KEY = "open"

/** The label drawn on that group, its own constant beside [GENERAL_LABEL] for the same reason. */
const val OPEN_LABEL = "Open"

/**
 * The key of the top-level group holding every remark already processed: `READ`, **or** carrying an
 * answer.
 *
 * ⚠️ An answer alone is enough, so an answered question leaves Open the moment the answer lands,
 * even if nothing ever acknowledged it. That was decided knowing the cost — the question moves out
 * of the list a person is working through while its answer is still worth reading. Two things make
 * it acceptable: the answer stays nested under its question wherever that question sits, expanded
 * already, so opening Done is the one click that reaches it; and inside each file group Done is
 * ordered newest-processed first, so what just arrived sits at the top of its file group rather than
 * buried in it. Do not soften this to "READ only" because it reads as friendlier; it was decided
 * against.
 *
 * ⚠️ **A row shown here can still be picked up by Publish Unread, and that is deliberate.** This
 * group's test is "`READ`, or has an answer"; Publish Unread's is "not `READ`". A question answered
 * by a session that never acknowledged the batch satisfies the first and not the second, so it sits
 * under Done and is handed over again by the next Publish Unread. Do not narrow Publish Unread's
 * filter to match this one: "not `READ`" is what makes a batch nobody acknowledged get re-sent, and
 * that is the only thing standing between a missed batch and remarks lost for good.
 */
const val DONE_KEY = "done"

/** The label drawn on that group, beside [OPEN_LABEL]. */
const val DONE_LABEL = "Done"

/**
 * A group row: a file, one of the two sides (Open, Done), or one of the special groups (General,
 * Answers with no question).
 *
 * The key and the label are separate on purpose. Two files can share a name in different
 * directories, and the panel puts a selection back after every rebuild by matching keys. The key is
 * the whole path from the root; the label is what is drawn.
 *
 * [detail] is a second, optional piece of text drawn in grey after the label — a file's directory,
 * shortened by [shortDirectory]. Null for the General and Answers groups, and for a file with no
 * directory to show. It is its own field rather than folded into [label] so the label can change
 * (file name first, directory second) without touching [key]: `RemarksPanel`'s selection restore
 * matches on key alone, and the key stays the whole path exactly as it always has.
 */
data class GroupNode(val key: String, val label: String, val detail: String? = null)

/**
 * One leaf. Everything a row needs to draw itself and to navigate.
 *
 * [asksForAnswer] and [hasAnswer] together decide the icon `RemarkStatusLook.icon` draws — the
 * question track first branches on [asksForAnswer], then on [hasAnswer] for its colour. [hasAnswer]
 * is not a field on the stored remark: it is looked up while the tree is built, from the answers the
 * same rebuild already resolved.
 */
data class RemarkNode(
    val id: String,
    val path: String,
    val position: String,
    val text: String,
    val status: RemarkStatus,
    val startLine: Int,
    val asksForAnswer: Boolean = false,
    val hasAnswer: Boolean = false,
    /** When the remark was written. What Open is ordered by, oldest first. */
    val createdAt: Long = 0L,
    /**
     * When an agent's acknowledgement marked this remark read, or 0 when nothing ever did — which
     * includes every remark stored before the field existed. See `readAt` in `model/RemarkState.kt`.
     */
    val readAt: Long = 0L,
    /**
     * When the answer nested under this question came back, or 0 when there is none. Filled while
     * the tree is built, from the same map that attaches the answer row — not a stored field.
     */
    val answeredAt: Long = 0L,
) {
    /**
     * The moment this row last changed hands, which is what Done is ordered by inside its file
     * group, newest first.
     *
     * Whichever of [readAt] and [answeredAt] is later, because either one on its own is enough to put
     * the row in Done and the row should sort by the thing that actually put it there. An answered
     * question that nothing ever acknowledged has only [answeredAt]; without it such a row sorted by
     * when it was *written* and could land at the bottom of a long Done group, which is exactly the
     * case the immediate move to Done creates. See [DONE_KEY].
     *
     * The fallback to [createdAt] is what keeps old data readable. Every remark read before [readAt]
     * existed carries 0, so without it the whole backlog would sort as one lump at the epoch, in
     * whatever order the store happened to hand it over.
     */
    val processedAt: Long get() = maxOf(readAt, answeredAt).takeIf { it != 0L } ?: createdAt
}

/**
 * One answer row. Everything the row needs to draw itself, to navigate, and to open its popup.
 *
 * [position] and [fileName] are both empty for an answer to a general remark: such an answer has no
 * file, so there is no position to print and no file name to print beside it. Every other answer
 * carries the same position string a remark row does, resolved through the same [rowPosition].
 * [fileName] is also empty for an answer nested under its question; see the field's own KDoc.
 *
 * [markdown] is carried on the node rather than looked up again when the popup opens, so a double
 * click reads the body the row was built from and not whatever the store holds a moment later.
 */
data class AnswerNode(
    val id: String,
    /**
     * The stored path of the file this answer points at, or empty for an answer with no file — one
     * to a general remark, or one whose remark was already gone when it arrived.
     *
     * ⚠️ Read by `RemarksPanel.navigateToSelected` in `RemarksToolWindowFactory.kt`, and by nothing
     * else. A simplification review in phase 11 deleted this field as write-only, which was true
     * then: a double click on an answer row only opened the popup. It navigates now. Do not delete
     * this again for looking unused — check that one caller first.
     */
    val path: String,
    /**
     * The 0-based line this answer RESOLVED to on this rebuild, not the line it was stored at. The
     * same field [RemarkNode.startLine] carries, filled the same way, so a double click lands where
     * the row's own position label says it points.
     *
     * ⚠️ Read by `RemarksPanel.navigateToSelected` in `RemarksToolWindowFactory.kt`, and by nothing
     * else. Deleted as write-only by the same phase 11 review, for the same then-correct reason as
     * [path] above. Do not delete it again without checking that caller.
     */
    val startLine: Int,
    val position: String,
    /**
     * The file name drawn in grey at the end of the row, or empty when the row must not draw one:
     * an answer nested under its question, which already sits inside that question's file group, and
     * an answer with no file at all.
     *
     * ⚠️ [position] is deliberately **not** dropped on a nested row the same way. An answer carries
     * its own anchor, so it can drift away from the line its question resolved to, and then the two
     * rows really do point at different lines — which is the one thing only this row can say. A file
     * name can never differ from its question's that way, so there it is a third copy of something
     * already on screen.
     */
    val fileName: String,
    /**
     * The remark this answers, copied off the stored answer, or empty when the answer carries none —
     * an answer whose remark was already gone when it arrived.
     *
     * ⚠️ Read by `RemarksPanel.navigateToSelected` in `RemarksToolWindowFactory.kt`, which hands it
     * to `showAnswerPopup` so the popup can draw the question above the answer. Read by nothing
     * else. A simplification review in phase 11 deleted this field as write-only, which was true
     * then: the popup showed the answer alone. Three of the four fields that review removed —
     * [path], [startLine] and this one — have since turned out to be wanted, so treat "nothing reads
     * it" here as a claim to check against that one caller, not as a fact about the field.
     */
    val question: String,
    /**
     * The answer's first non-blank line, and what the row draws as its text.
     *
     * ⚠️ The row deliberately shows this rather than [question], and does not show both. The row is
     * read to see *what came back*, and since phase 12 the nesting already answers "to what": the
     * question is the row directly above it. It is also in the answer's gutter tooltip, which puts the
     * question first, and in the popup, which is where an answer is actually read. A row already
     * carrying a position and a preview would have to give up the preview to fit the question, which
     * trades the one thing only this row shows for another copy of something shown elsewhere.
     */
    val firstLine: String,
    val markdown: String,
    val answeredAt: Long,
)

/**
 * The 1-based position with its label, the same string the old flat list showed. A Relocated
 * result that came back at exactly the stored range is not called moved, because that is the case
 * where the block was edited where it stands.
 *
 * Empty for a general remark — one `isAboutNoFile` says is about no file — the same way [answerNode]
 * already leaves an answer's position empty for an answer to one. A general remark's stored line
 * numbers are 0 and 0, never a real anchor, so without this check the row would print "1-1" on its
 * metadata line: a line nobody selected, that points at nothing.
 *
 * This is now the only place that rule lives; describe() held a second copy and is gone.
 */
fun remarkNode(row: ResolvedRemark, hasAnswer: Boolean = false, answeredAt: Long = 0L): RemarkNode {
    val result = row.result
    val label = movedOrOrphanedLabel(result, row.remark.startLine, row.remark.endLine, row.remark.commit)
    val general = isAboutNoFile(row.remark)
    return RemarkNode(
        id = row.remark.id.orEmpty(),
        path = row.remark.path.orEmpty(),
        position = if (general) "" else rowPosition(result, row.startColumn, row.endColumn) + label,
        // Whatever was typed, newlines and all. A row used to be flattened onto one line, because one
        // SimpleColoredComponent cannot draw a newline; since phase 13 a row is a stack of them and
        // `wrapToLines` splits on '\n' itself, so a remark written with Shift+Enter keeps the breaks
        // the person put in it.
        text = row.remark.text.orEmpty(),
        status = row.remark.status,
        startLine = result.startLine,
        asksForAnswer = row.remark.asksForAnswer,
        hasAnswer = hasAnswer,
        createdAt = row.remark.createdAt,
        readAt = row.remark.readAt,
        answeredAt = answeredAt,
    )
}

/**
 * The same, for an answer. The position rules are shared with [remarkNode] rather than copied.
 *
 * [nested] says the row is being built as a child of its own question, which is the ordinary case.
 * The only thing it changes is [AnswerNode.fileName]; see that field's KDoc for why the position
 * stays either way.
 */
fun answerNode(row: ResolvedAnswer, nested: Boolean = false): AnswerNode {
    val answer = row.answer
    val general = isAboutNoFile(answer)
    val path = answer.path.orEmpty()
    val label = movedOrOrphanedLabel(row.result, answer.startLine, answer.endLine, answer.commit)
    return AnswerNode(
        id = answer.id.orEmpty(),
        path = path,
        // row.result, not answer.startLine: the same source the position label beside it is built
        // from, so the row cannot say one line and navigate to another.
        startLine = row.result.startLine,
        position = if (general) "" else rowPosition(row.result, row.startColumn, row.endColumn) + label,
        fileName = if (general || nested) "" else path.substringAfterLast('/'),
        question = answer.question.orEmpty(),
        firstLine = firstLineOf(answer.markdown),
        markdown = answer.markdown.orEmpty(),
        answeredAt = answer.answeredAt,
    )
}

/** The " (moved)"/" (orphaned…)" suffix, shared by a remark row and an answer row. */
private fun movedOrOrphanedLabel(
    result: AnchorResult,
    storedStartLine: Int,
    storedEndLine: Int,
    commit: String?,
): String {
    val movedFromStored = result.startLine != storedStartLine || result.endLine != storedEndLine
    return when {
        result is AnchorResult.Orphaned -> " (orphaned${writtenAt(commit)})"
        result is AnchorResult.Relocated && movedFromStored -> " (moved)"
        else -> ""
    }
}

/**
 * The first line of an answer worth showing on one row: the first line that is not blank, trimmed.
 * An answer that opens with a markdown heading therefore shows that heading, which reads well.
 *
 * Public rather than private to this file, because the answer's gutter tooltip shows the same
 * preview and the two must not drift: a person who sees one line on the row and a different one on
 * the icon would have no way to tell which is the answer.
 */
fun firstLineOf(markdown: String?): String =
    markdown.orEmpty().lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty()

/**
 * The 1-based position of a resolved row, before any "(moved)"/"(orphaned...)" suffix. The shape
 * itself — "9-9", "9:12-38", "9:12-11:5" — is `positionLabel` in `anchor/SubLineRange.kt`, shared
 * with the history file so the two can never drift apart, and so is the rule about which of the
 * three shapes a column pair earns.
 *
 * The one thing decided here is the orphan. An [AnchorResult.Orphaned] result never gets columns:
 * its line numbers no longer point at real code, so there is no current line left to check a column
 * against, matching `markersValid`'s own `remark.orphaned` check in `render/PromptRenderer.kt`.
 * Such a row is asked for the whole-line shape by passing the `0 to 0` sentinel, rather than by a
 * second copy of the shape rule.
 */
private fun rowPosition(result: AnchorResult, startColumn: Int, endColumn: Int): String =
    if (result is AnchorResult.Orphaned) positionLabel(result.startLine, result.endLine, 0, 0)
    else positionLabel(result.startLine, result.endLine, startColumn, endColumn)

/** Short, because a tree row is already carrying a position, a text and a status word. */
private fun writtenAt(commit: String?): String =
    commit?.let { ", written at ${it.take(8)}" }.orEmpty()

/**
 * The remark rows a set of selected tree nodes covers, at any depth. Distinct, because selecting a
 * group together with one of its own rows would otherwise count that row twice.
 */
fun remarkNodesUnder(selected: List<DefaultMutableTreeNode>): List<RemarkNode> =
    selected.flatMap(::leavesOf).filterIsInstance<RemarkNode>().distinct()

/**
 * The answer rows a set of selected tree nodes covers, the same way [remarkNodesUnder] does for
 * remark rows. Two functions rather than one returning both, because every caller wants exactly one
 * of the two kinds: Delete acts on both but through two different store functions, and Publish
 * Selected and the Ask for an Answer toggle act on remark ids only — an answer is never published.
 */
fun answerNodesUnder(selected: List<DefaultMutableTreeNode>): List<AnswerNode> =
    selected.flatMap(::leavesOf).filterIsInstance<AnswerNode>().distinct()

/**
 * Recursive, so that selecting a question also reaches the answer nested under it.
 *
 * A RemarkNode is not a leaf in the same sense any more: since an answer nests under the question
 * it answers, a RemarkNode's own children can hold one AnswerNode. Stopping there the way an
 * AnswerNode does would leave a selected question's answer behind — Delete would take the question
 * and the answer would survive with no question left to sit under, which is a delete that makes a
 * different row jump to the no-question group rather than just disappearing. So a RemarkNode
 * contributes itself AND recurses into its children; it is the only user object that does both.
 */
private fun leavesOf(node: DefaultMutableTreeNode): List<Any> =
    when (val user = node.userObject) {
        is AnswerNode -> listOf(user)
        is RemarkNode -> listOf(user) + childLeavesOf(node)
        else -> childLeavesOf(node)
    }

private fun childLeavesOf(node: DefaultMutableTreeNode): List<Any> =
    (0 until node.childCount).flatMap { index ->
        (node.getChildAt(index) as? DefaultMutableTreeNode)?.let(::leavesOf).orEmpty()
    }

/**
 * The whole tree, rebuilt from scratch.
 *
 * **Two top-level groups carry the remarks**: [OPEN_KEY] for the ones still waiting, [DONE_KEY] for
 * the ones already processed — `READ`, or carrying an answer. A side with no rows is not drawn at
 * all, so a project where nothing has been handed over yet has one Open group and no Done group.
 *
 * Inside a side the structure is the one that was there before. A remark about no file goes into its
 * own group first, labelled "General", above the file groups; files follow in path order. Which
 * remarks are general is asked of `isAboutNoFile` in `store/RemarkResolver.kt`, the one place that
 * decides it, rather than re-read off [RemarkNode]'s flattened path here.
 *
 * **Rows inside a file are ordered by the time they last changed hands**, not by the line they point
 * at: [RemarkNode.createdAt] in Open, oldest first, so a new remark lands at the bottom of its file
 * group and nothing above it moves; [RemarkNode.processedAt] in Done, newest first, so whatever was
 * just picked up sits at the top of its own file group. ⚠️ **The file groups themselves stay in path
 * order on both sides**, so Done is not one newest-first list — it is a list of files, each ordered
 * newest-processed first inside itself. That is what was asked for, and it is why both comparators
 * take the path as their first key. Two rows carrying the same time fall back to the resolved line,
 * which is what keeps the order steady for remarks written in the same millisecond — and for every
 * remark stored before either stamp meant anything.
 *
 * A remark with no id is left out. Its node would draw normally and then do nothing: Delete and
 * Copy Selected both match on the id, and an empty id matches no stored remark. RemarkGutter drops
 * the same rows for the same reason.
 *
 * An answer is a **child of the question it answers**, wherever that question sits — in the General
 * group or in a file group, on either side. So it is next to the thing it is about, and it takes
 * that question's place in file and row order rather than any order of its own. Being a child here
 * is a view and nothing else: the store has two independent records, and Delete calls `deleteAnswer`
 * for the answer row in its own right.
 *
 * An answer whose question is **not** in the tree — one carrying no `remarkId`, and one naming a
 * remark that is gone or that got no node — has no parent to sit under, and goes into a flat
 * top-level group keyed [ANSWERS_KEY] **above Open**, sorted **newest first**. It is deliberately
 * not folded into Done: an answer with no question left is a loose end, not finished work. That
 * group appears only when at least one such answer exists, which is not the ordinary case: normally
 * every answer nests and the group is absent.
 */
fun buildTreeRoot(
    rows: List<ResolvedRemark>,
    answers: List<ResolvedAnswer> = emptyList(),
): DefaultMutableTreeNode {
    val root = DefaultMutableTreeNode("remarks")

    // Filtered once, then used twice: to work out which answers nest and under which question, and
    // for the top-level group of the rest. An answer with no id draws no row — the same rule a remark
    // with no id follows below — so it must not turn its question's icon green either: a green
    // question mark with no answer anywhere to open is worse than the yellow one it replaces.
    // `editor/RemarkGutter.kt` filters its own answers list the same way, so the gutter icon and the
    // tree row cannot disagree about which questions have been answered.
    val withIds = answers.filter { it.answer.id != null }

    // The remarks that will really get a node, and so the ids an answer can attach to. Taken from
    // this same filtered list rather than asked of the store again: a remark with no id produces no
    // node, so an answer naming one has no parent and has to be treated as having none at all.
    val remarkRows = rows.filter { it.remark.id != null }
    val questionIds = remarkRows.mapNotNull { it.remark.id }.toSet()

    val (nestedAnswers, looseAnswers) = withIds.partition { it.answer.remarkId in questionIds }
    val nestedByQuestion = nestedAnswers
        .groupBy({ it.answer.remarkId.orEmpty() }, { answerNode(it, nested = true) })
        // Newest first, the order the top-level group already uses. `recordAnswer` upserts on the
        // remark id, so the store cannot hold two answers to one question and this is defensive —
        // but if two ever do appear, the one that just came back is the one to read.
        .mapValues { (_, underOneQuestion) -> underOneQuestion.sortedByDescending { it.answeredAt } }

    val looseRows = looseAnswers.map(::answerNode).sortedByDescending { it.answeredAt }
    if (looseRows.isNotEmpty()) {
        val answersNode = DefaultMutableTreeNode(GroupNode(ANSWERS_KEY, ANSWERS_LABEL))
        looseRows.forEach { answersNode.add(DefaultMutableTreeNode(it)) }
        root.add(answersNode)
    }

    // hasAnswer, and the Open/Done split with it, are read straight off nestedByQuestion, which is
    // the same map that attaches the child rows: a question shows the green question mark, and moves
    // to Done, exactly when an answer nested under it. A separate set of answered ids would state
    // that fact twice, and then a change to the nesting rule not copied across would leave a row
    // with a green question mark and nothing nested under it.
    val answered = nestedByQuestion.keys
    val (doneRows, openRows) = remarkRows.partition {
        it.remark.status == RemarkStatus.READ || it.remark.id in answered
    }

    addSide(root, OPEN_KEY, OPEN_LABEL, openRows, answered, nestedByQuestion, OPEN_ORDER)
    addSide(root, DONE_KEY, DONE_LABEL, doneRows, answered, nestedByQuestion, DONE_ORDER)
    return root
}

/**
 * Open: the file first, so file groups stay in path order, then oldest first, then the resolved line
 * as the tie-break.
 */
private val OPEN_ORDER = compareBy<RemarkNode>({ it.path }, { it.createdAt }, { it.startLine })

/**
 * Done: the same, but newest-processed first *inside each file*. See [RemarkNode.processedAt] for
 * what that reads.
 *
 * ⚠️ The path stays the first key on purpose, so file groups are in path order and only the rows
 * within one file are newest-first. Done is deliberately **not** one newest-processed-first list
 * across every file. Do not "fix" this by moving [RemarkNode.processedAt] to the front; it is what
 * was asked for.
 */
private val DONE_ORDER = compareBy<RemarkNode> { it.path }
    .thenByDescending { it.processedAt }
    .thenBy { it.startLine }

/**
 * One of the two sides, with its General group and its file groups inside it — or nothing at all
 * when the side has no rows, since an empty "Done" heading above an empty "Open" heading would be
 * two rows saying there is nothing to say.
 *
 * Every key built here is prefixed with [sideKey]; see [OPEN_KEY] for why a file group's key can no
 * longer be the path on its own.
 */
private fun addSide(
    root: DefaultMutableTreeNode,
    sideKey: String,
    sideLabel: String,
    rows: List<ResolvedRemark>,
    answered: Set<String>,
    answersByQuestion: Map<String, List<AnswerNode>>,
    order: Comparator<RemarkNode>,
) {
    if (rows.isEmpty()) return

    // Split on the stored remark, then map and sort each half, rather than mapping first and asking
    // the node. Same rows in the same order either way — partition keeps order and the sort is
    // stable — but this way the question "is this about no file" is asked of isAboutNoFile once.
    val (generalRows, fileRows) = rows.partition { isAboutNoFile(it.remark) }
    val general = generalRows.map { leafNode(it, answered, answersByQuestion) }.sortedWith(order)
    val aboutAFile = fileRows.map { leafNode(it, answered, answersByQuestion) }.sortedWith(order)

    val side = DefaultMutableTreeNode(GroupNode(sideKey, sideLabel))
    if (general.isNotEmpty()) {
        val generalNode = DefaultMutableTreeNode(GroupNode("$sideKey/$GENERAL_KEY", GENERAL_LABEL))
        general.forEach { generalNode.add(questionTreeNode(it, answersByQuestion)) }
        side.add(generalNode)
    }
    addFileGroups(side, sideKey, aboutAFile, answersByQuestion)
    root.add(side)
}

/**
 * One remark's leaf node, with both facts its answer contributes filled in from the same map that
 * will attach the answer row: whether there is one at all, which decides the icon and the side, and
 * when it came back, which [RemarkNode.processedAt] sorts Done by. Read from one place, so a question
 * cannot end up in Done sorted by a time no answer under it agrees with.
 */
private fun leafNode(
    row: ResolvedRemark,
    answered: Set<String>,
    answersByQuestion: Map<String, List<AnswerNode>>,
): RemarkNode = remarkNode(
    row,
    row.remark.id in answered,
    answersByQuestion[row.remark.id.orEmpty()]?.maxOfOrNull { it.answeredAt } ?: 0L,
)

/**
 * The directory shown next to a file's name, or null when the file sits in the project root and
 * there is nothing to show. Shortened to the last two segments, with a leading ellipsis when the
 * directory has more than two, so a deep path costs the row one short grey word instead of
 * crowding out the file name it is helping to identify.
 *
 * A node per directory segment, with single-child chains collapsed back down, was considered and
 * rejected: that is real logic with its own tests, worth building only if this plain string still
 * reads badly once it is in front of the tree.
 */
fun shortDirectory(path: String): String? {
    val slash = path.lastIndexOf('/')
    if (slash < 0) return null
    val segments = path.substring(0, slash).split('/')
    val shown = if (segments.size > 2) segments.takeLast(2) else segments
    val prefix = if (segments.size > 2) "…/" else ""
    return prefix + shown.joinToString("/")
}

private fun addFileGroups(
    parent: DefaultMutableTreeNode,
    sideKey: String,
    nodes: List<RemarkNode>,
    answersByQuestion: Map<String, List<AnswerNode>>,
) {
    nodes.groupBy { it.path }.forEach { (path, inFile) ->
        val fileNode = DefaultMutableTreeNode(
            GroupNode("$sideKey/file:$path", path.substringAfterLast('/'), shortDirectory(path))
        )
        inFile.forEach { fileNode.add(questionTreeNode(it, answersByQuestion)) }
        parent.add(fileNode)
    }
}

/**
 * One remark's tree node, with the answer to it already attached as a child. The one place a remark
 * node is built, so the General group and the file groups cannot end up nesting differently.
 *
 * A list of children rather than one: `recordAnswer` upserts on the remark id, so the store cannot
 * hold two answers to one question and this is expected to hold zero or one. If two ever do appear,
 * both are drawn, which is visible rather than silently hidden.
 */
private fun questionTreeNode(
    node: RemarkNode,
    answersByQuestion: Map<String, List<AnswerNode>>,
): DefaultMutableTreeNode {
    val questionNode = DefaultMutableTreeNode(node)
    answersByQuestion[node.id]?.forEach { questionNode.add(DefaultMutableTreeNode(it)) }
    return questionNode
}

/**
 * How many lines of **text** one row may draw — the wrapped body only. A fourth line of remark text
 * is elided with an ellipsis rather than drawn, so one long remark cannot push the whole tree off
 * screen.
 *
 * ⚠️ Not the row's total height. Since task 8, every row that has one carries a fourth
 * `SimpleColoredComponent`, [RemarkTreeRenderer.metadataLine] at `gridy = MAX_TEXT_LINES`, that this
 * constant does not count: it caps the wrapped body, not the row.
 */
const val MAX_TEXT_LINES = 3

/**
 * Room taken off the tree's own width before the text is wrapped: the icon, the gap after it, and
 * enough slack that a vertical scroll bar appearing does not push the last word of every row onto a
 * line of its own.
 *
 * Internal rather than private so `RemarkTreeRendererTest` can do the same arithmetic the renderer
 * does, instead of asserting on a number copied out of here.
 */
internal val ROW_MARGIN get() = JBUI.scale(36)

/**
 * The narrowest the text may ever be wrapped to. A tree that has not been laid out yet reports a
 * width of zero, and without a floor every row would come back as three one-character lines and stay
 * that way until something invalidated the row heights.
 *
 * Internal for the same reason as [ROW_MARGIN].
 */
internal val MIN_WRAP_WIDTH get() = JBUI.scale(120)

/** Logged from a renderer, so it is a top-level `val` rather than a field on a reused component. */
private val LOG = Logger.getInstance("dev.sasha.clauderemarks.ui.RemarkTreeRenderer")

/**
 * One line of a row.
 *
 * A plain `SimpleColoredComponent` fires a property change, revalidates and repaints itself on every
 * `clear()`, every `append(...)` and every `icon =`, through its own `revalidateAndRepaint`. A row
 * resets four of these components and then appends to as many of them as it needs, and that happens
 * once per paint *and* once per height computation. `ColoredTreeCellRenderer`, the class this
 * renderer replaced, overrides that method to nothing with the comment "no need for this in a
 * renderer", and the reason is that a renderer lives outside the tree's own component hierarchy:
 * there is nothing there to invalidate and nothing there to repaint.
 *
 * ⚠️ `SimpleColoredComponent` skips only the `revalidate()` half on its own, and only when it is
 * itself a `TreeCellRenderer` (`myAutoInvalidate = !(this instanceof TreeCellRenderer)`). A line
 * component is not one — the panel around it is — so nothing was skipped before this class existed.
 */
private class RowLine : SimpleColoredComponent() {
    override fun revalidateAndRepaint() = Unit
}

/**
 * The attributes a fragment is really drawn with once the row's selection state is taken into
 * account: the given [attributes] normally, and the platform's forced selection foreground when the
 * row is selected, the tree is focused, and the current theme asks for it.
 *
 * ⚠️ This is the substitution `ColoredTreeCellRenderer.append` used to make for free, and dropping it
 * with that class was a real regression. `SimpleColoredComponent` draws a fragment in the attribute's
 * own colour whenever it has one, ignoring the component's foreground — and `GRAYED_ATTRIBUTES`
 * carries one. So a `READ` remark's grey body, and every row's grey metadata line, stayed grey on top
 * of the selection band, which the default dark theme sets
 * `Tree.forceFocusedSelectionForeground` precisely to prevent.
 *
 * Internal rather than private to the renderer so a test can drive it directly: a tree inside a test
 * fixture never has focus, so the substitution can never be reached through a real render.
 */
internal fun selectionAdjusted(
    attributes: SimpleTextAttributes,
    selected: Boolean,
    focused: Boolean,
): SimpleTextAttributes =
    if (selected && focused && JBUI.CurrentTheme.Tree.Selection.forceFocusedSelectionForeground()) {
        SimpleTextAttributes(attributes.style, UIUtil.getTreeSelectionForeground(true))
    } else {
        attributes
    }

/**
 * A row is a **stack of lines**, not one line, so a remark long enough to be worth writing is worth
 * reading in the tree.
 *
 * `ColoredTreeCellRenderer`, what this was until phase 13, is a `SimpleColoredComponent`, and one of
 * those paints a single line by construction. So the renderer is a `JPanel` on a `GridBagLayout`
 * holding [MAX_TEXT_LINES] pre-built `SimpleColoredComponent` rows at `gridy = 0..2`, each with
 * `weightx = 1` and `fill = HORIZONTAL`. That structure is copied from the platform's own
 * `MultiLineTodoRenderer` (`platform/todo/src/com/intellij/ide/todo/MultiLineTodoRenderer.java`),
 * including the detail that a line not needed by this row is hidden with `isVisible = false` rather
 * than removed: `GridBagLayout` skips a hidden child when it measures, so a one-line row really is
 * one line tall, and the components survive to be reused by the next row.
 *
 * **A fourth row, at `gridy = [MAX_TEXT_LINES]`, carries the metadata line**: the position, its
 * "(moved)"/"(orphaned…)" suffix, and, for an answer with no question left in the tree, the file
 * name after it. It is its own `SimpleColoredComponent`, [metadataLine], not a fourth entry in
 * [lines] — grey by construction and hidden the same way an unused text line is when there is
 * nothing to put in it. Moving these below the text rather than in front of it, where they used to
 * sit on the first line, is what task 8 does; see [drawWrappedRow] for how.
 *
 * ⚠️ **Stacking `SimpleColoredComponent` rather than the `HighlightableCellRenderer` the TODO tree
 * stacks is deliberate.** That one takes highlights as `TextAttributes`, which would mean translating
 * every style in this file. `SimpleColoredComponent` takes `append(text, SimpleTextAttributes)` — the
 * exact call the old renderer already made — so the three existing styles carry over untouched and
 * cannot drift.
 *
 * ⚠️ **The platform's renderer never wraps.** It is handed already-separate lines, because a TODO
 * comment is multi-line in the source. The line breaking here is this plugin's own: [wrapToLines] in
 * `ui/WrapText.kt`, measured through the line component's own `FontMetrics`.
 *
 * **Selection is painted by hand, and that is the one thing `ColoredTreeCellRenderer` gave for
 * free.** A plain `JPanel` paints nothing, so a selected row would look unselected while still being
 * selected — and the selection is what Publish Selected, Delete and the whole right-click menu act
 * on. So the panel takes the tree's selection background and each line takes the matching foreground.
 *
 * Two colours in one line still means two `append` calls; there is no way to colour part of one
 * string.
 */
class RemarkTreeRenderer : JPanel(GridBagLayout()), TreeCellRenderer {

    /**
     * The stacked line components, built once and reused on every row.
     *
     * Internal rather than private so `RemarkTreeRendererTest` can ask what a row actually drew.
     * Painting is not testable without a screen; which lines came back visible, and what text and
     * attributes each of them carries, is.
     */
    internal val lines: List<SimpleColoredComponent> = List(MAX_TEXT_LINES) { RowLine() }

    /**
     * The fourth row, below the text: the position, its "(moved)"/"(orphaned…)" suffix, and, for an
     * answer with no question in the tree, the file name after it. Grey by construction, and hidden
     * whenever there is nothing to put in it — see [drawWrappedRow].
     *
     * A field of its own rather than a fourth entry in [lines]: every existing reader of [lines] —
     * [drawWrappedRow] itself and `RemarkTreeRendererTest` — means "one of the wrapped text lines" by
     * that name, and folding the metadata line in would make every one of them count to four when
     * they mean three.
     */
    internal val metadataLine: SimpleColoredComponent = RowLine()

    /**
     * Whether the row being drawn is selected, and whether the tree it is in has focus. Fields rather
     * than parameters carried down, because every `append` on this row needs both — see
     * [selectionAdjusted] for what they decide.
     */
    private var rowSelected = false
    private var treeFocused = false

    init {
        val constraints = GridBagConstraints().apply {
            gridx = 0
            weightx = 1.0
            fill = GridBagConstraints.HORIZONTAL
            anchor = GridBagConstraints.WEST
        }
        (lines + metadataLine).forEachIndexed { index, line ->
            // SimpleColoredComponent's own constructor makes itself opaque. Left that way, every line
            // would paint the plain tree background over the selection band this panel draws.
            line.isOpaque = false
            constraints.gridy = index
            add(line, constraints)
        }
    }

    /**
     * ⚠️ The whole body is wrapped, and only `ProcessCanceledException` is let out.
     *
     * `ColoredTreeCellRenderer` makes this method `final` and does exactly this, for a reason worth
     * repeating here: an exception thrown from a renderer escapes into `BasicTreeUI.paintRow` on the
     * EDT, and since painting repeats, so does the exception — one bad row makes the whole tool
     * window unusable rather than drawing one bad row. A cancellation is rethrown because swallowing
     * one breaks the platform's own cancellation, and everything else is logged so it is still found.
     */
    override fun getTreeCellRendererComponent(
        tree: JTree,
        value: Any?,
        selected: Boolean,
        expanded: Boolean,
        leaf: Boolean,
        row: Int,
        hasFocus: Boolean,
    ): Component {
        try {
            drawRow(tree, value, selected)
        } catch (cancelled: ProcessCanceledException) {
            throw cancelled
        } catch (failed: Exception) {
            // The platform guards its own logging call the same way: a logger that throws while a
            // row is painting would put the tree back exactly where this catch block took it from.
            try {
                LOG.error(failed)
            } catch (ignored: Exception) {
                // Nothing left to do with it.
            }
        }
        return this
    }

    private fun drawRow(tree: JTree, value: Any?, selected: Boolean) {
        // tree.hasFocus(), not the hasFocus parameter: that one says the ROW has focus, while
        // UIUtil's argument is whether the TREE does — an unfocused tree draws a paler selection.
        val focused = tree.hasFocus()
        rowSelected = selected
        treeFocused = focused
        background = if (selected) UIUtil.getTreeSelectionBackground(focused) else UIUtil.getTreeBackground()
        isOpaque = selected
        val rowForeground = UIUtil.getTreeForeground(selected, focused)

        (lines + metadataLine).forEach {
            it.clear()
            it.icon = null
            it.foreground = rowForeground
            it.isVisible = false
        }

        val node = value as? DefaultMutableTreeNode
        when (val user = node?.userObject) {
            is RemarkNode -> drawWrappedRow(
                icon = RemarkStatusLook.icon(
                    status = user.status,
                    asksForAnswer = user.asksForAnswer,
                    hasAnswer = user.hasAnswer,
                ),
                body = user.text,
                bodyAttributes = RemarkStatusLook.textAttributes(user.status),
                metadata = user.position,
                width = wrapWidth(tree, node),
            )

            is AnswerNode -> drawWrappedRow(
                icon = AllIcons.General.Balloon,
                body = user.firstLine,
                bodyAttributes = SimpleTextAttributes.REGULAR_ATTRIBUTES,
                metadata = metadataOf(user.position, user.fileName),
                width = wrapWidth(tree, node),
            )

            // Not wrapped, and drawn on one line whatever its width. A group's label is a file name
            // or one of four fixed words, so there is nothing here a second line would rescue, and a
            // heading that grew taller than the rows under it would read as the more important thing.
            is GroupNode -> lines[0].let { line ->
                line.isVisible = true
                append(line, user.label, SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
                user.detail?.let { append(line, "  $it", SimpleTextAttributes.GRAYED_ATTRIBUTES) }
            }
        }
    }

    /**
     * Every fragment this renderer draws goes through here, so the forced selection foreground can
     * never be applied to some of a row and not the rest. See [selectionAdjusted].
     */
    private fun append(line: SimpleColoredComponent, text: String, attributes: SimpleTextAttributes) {
        line.append(text, selectionAdjusted(attributes, rowSelected, treeFocused))
    }

    /**
     * The row's own accessible name: the text of every line it left visible, joined.
     *
     * `JTree`'s `AccessibleJTree` builds a node's accessible context out of whatever the renderer
     * hands back, so this is what a screen reader announces for a row. `SimpleColoredComponent`
     * supplies one carrying its fragments' text and `ColoredTreeCellRenderer` inherited it; a plain
     * `JPanel`'s context has a null name, so every row went silent when the renderer became one.
     */
    override fun getAccessibleContext(): AccessibleContext =
        super.getAccessibleContext().also { it.accessibleName = visibleRowText() }

    private fun visibleRowText(): String = (lines + metadataLine)
        .filter { it.isVisible }
        .joinToString(" ") { it.getCharSequence(false).toString() }
        .trim()

    /**
     * One remark or answer row: [icon] on the first line, [body] wrapped across as many of the
     * [MAX_TEXT_LINES] as it needs, and [metadata] on [metadataLine] below all of them, grey and on
     * its own row rather than sharing the first line of text the way it did before task 8.
     *
     * The icon goes on the first line only, so every line under it, [metadataLine] included, starts
     * at the panel's left edge rather than under a second copy of it.
     *
     * [metadata] costs [body] nothing of [width] any more. Before task 8 this function measured a
     * prefix and a suffix and took both off [width] before wrapping, which is why the position used
     * to narrow all three text lines whether or not a given line drew it. Now that the position has
     * its own row, [wrapToLines] gets the row's whole width.
     *
     * [metadataLine] is hidden outright when [metadata] is empty — every general remark, and every
     * nested answer with no move or orphan to report. `GridBagLayout` skips a hidden child when it
     * measures, so such a row is exactly as tall as its text, not one blank line taller.
     *
     * ⚠️ [metadata] is **elided**, never wrapped. It goes through [elideToWidth] rather than
     * [wrapToLines] because it is one deliberate string: wrapping it would re-flow it and collapse
     * the two-space gap [metadataOf] puts between a position and a file name. Eliding is still needed
     * — a sub-line range plus an "(orphaned, written at …)" suffix plus a long file name can be wider
     * than the row, and one row wider than the viewport puts a horizontal scroll bar under the whole
     * tree.
     *
     * ⚠️ It is drawn in `GRAYED_SMALL_ATTRIBUTES`, not `GRAYED_ATTRIBUTES`. Every row in Done is
     * `READ` or answered, and `RemarkStatusLook.textAttributes` gives a `READ` body the *same* grey —
     * so with the plain grey the body and the line under it were the same colour and the metadata
     * stopped reading as subordinate to the text it belongs to. The smaller size is what tells them
     * apart on those rows. Checked with `javap` against the 2025.2 jars: the constant exists there.
     */
    private fun drawWrappedRow(
        icon: Icon,
        body: String,
        bodyAttributes: SimpleTextAttributes,
        metadata: String,
        width: Int,
    ) {
        val metrics = textMetrics()
        val wrapped = wrapToLines(body, width, MAX_TEXT_LINES) { metrics.stringWidth(it) }

        wrapped.forEachIndexed { index, text ->
            val line = lines[index]
            line.isVisible = true
            if (index == 0) line.icon = icon
            append(line, text, bodyAttributes)
        }

        if (metadata.isNotEmpty()) {
            metadataLine.isVisible = true
            val shown = elideToWidth(metadata, width) { metrics.stringWidth(it) }
            append(metadataLine, shown, SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES)
        }
    }

    /**
     * The metadata line's text for an answer row: the position, then the file name after it when
     * there is one, with the same two-space gap the row used to draw between its prefix and suffix
     * before task 8. Empty when both are, which is a nested answer to a general remark.
     *
     * A remark row needs no equivalent function: [RemarkNode.position] is the whole metadata line on
     * its own, since a remark carries no second grey fact the way an orphan-group answer's file name
     * is.
     */
    private fun metadataOf(position: String, fileName: String): String = when {
        position.isEmpty() -> fileName
        fileName.isEmpty() -> position
        else -> "$position  $fileName"
    }

    /**
     * The metrics the text is measured with, taken from the line component that will draw it.
     *
     * The fallback matters: `SimpleColoredComponent`'s constructor never sets a font, and a component
     * with no font and no parent answers `getFont()` with null, which `getFontMetrics` will not take.
     * That is the ordinary state of a renderer, which lives outside the tree's own hierarchy.
     */
    private fun textMetrics(): FontMetrics {
        val line = lines[0]
        return line.getFontMetrics(line.font ?: UIUtil.getLabelFont())
    }

    /**
     * How wide the text on this row may be.
     *
     * The tree's visible width is the starting point rather than its own width: inside a scroll pane
     * the tree is as wide as its widest row, which is the thing being computed here.
     *
     * ⚠️ The indent is worked out from the node's depth times the platform's own per-level indent,
     * **not** read back from `tree.getRowBounds`. Those bounds are produced by asking this very
     * renderer for its preferred size, so reading them here would be a renderer asking the layout
     * cache a question only the renderer can answer.
     *
     * Internal rather than private so `RemarkTreeRendererTest` can check the indent arithmetic
     * exactly. Every node in a real tree sits at level 2 or deeper — side, then file group, then the
     * row — while a test that renders a bare node renders one at level 0, so nothing that goes
     * through a rendered row exercises the subtraction at all.
     */
    internal fun wrapWidth(tree: JTree, node: DefaultMutableTreeNode?): Int {
        val available = tree.visibleRect.width.takeIf { it > 0 } ?: tree.width
        val perLevel = UIUtil.getTreeLeftChildIndent() + UIUtil.getTreeRightChildIndent()
        val indent = (node?.level ?: 0) * perLevel
        return (available - indent - ROW_MARGIN).coerceAtLeast(MIN_WRAP_WIDTH)
    }
}
