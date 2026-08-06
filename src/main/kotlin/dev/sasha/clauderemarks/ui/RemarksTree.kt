package dev.sasha.clauderemarks.ui

import com.intellij.icons.AllIcons
import com.intellij.ui.ColoredTreeCellRenderer
import com.intellij.ui.SimpleTextAttributes
import dev.sasha.clauderemarks.anchor.AnchorResult
import dev.sasha.clauderemarks.anchor.positionLabel
import dev.sasha.clauderemarks.model.RemarkStatus
import dev.sasha.clauderemarks.store.ResolvedAnswer
import dev.sasha.clauderemarks.store.ResolvedRemark
import dev.sasha.clauderemarks.store.isAboutNoFile
import javax.swing.JTree
import javax.swing.tree.DefaultMutableTreeNode

/** The label shown for remarks that are in no bucket, once any bucket exists. */
const val NO_BUCKET_LABEL = "(no bucket)"

/**
 * The key of the group that holds every remark about no file. A file key always starts with
 * "file:" and a bucket key always starts with "bucket:", so this bare word cannot collide with
 * either, and `RemarksPanel`'s selection restore, which matches groups by key, keeps working.
 */
const val GENERAL_KEY = "general"

/** The label drawn on that group, beside [NO_BUCKET_LABEL] rather than written inline at the one
 *  place the node is built. */
const val GENERAL_LABEL = "General"

/**
 * The key of the group that holds every answer with no question left in the tree. A file key always
 * starts with "file:" and a bucket key with "bucket:", and [GENERAL_KEY] is the bare word "general",
 * so this second bare word cannot collide with any of them — the same argument [GENERAL_KEY] makes.
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

/** What every bucket group's key starts with. See [buildTreeRoot] for why the key is not the label. */
const val BUCKET_KEY_PREFIX = "bucket:"

/**
 * The key of the group holding the remarks that are in no bucket. The leading space is what keeps it
 * apart from a bucket a person literally named "(no bucket)": `setRemarkBucket` trims a bucket name,
 * so no real bucket key can ever start with a space.
 */
const val NO_BUCKET_KEY = BUCKET_KEY_PREFIX + " none"

/**
 * A group row: a bucket or a file.
 *
 * The key and the label are separate on purpose. A bucket can be called "src" and so can a
 * directory, and the panel puts a selection back after every rebuild by matching keys. Two groups
 * sharing a key means restoring the wrong one. The key is the whole path from the root; the label
 * is what is drawn.
 *
 * [detail] is a second, optional piece of text drawn in grey after the label — a file's directory,
 * shortened by [shortDirectory]. Null for a bucket group, and for a file with no directory to show.
 * It is its own field rather than folded into [label] so the label can change (file name first,
 * directory second) without touching [key]: `RemarksPanel`'s selection restore matches on key
 * alone, and the key stays the whole path exactly as it always has.
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
    val bucket: String?,
    val status: RemarkStatus,
    val startLine: Int,
    val asksForAnswer: Boolean = false,
    val hasAnswer: Boolean = false,
)

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
     * ⚠️ The row deliberately shows this rather than [question], and does not show both. The Answers
     * group is sorted newest first and is read to see *what came back*; the question is already on
     * screen twice — on the remark's own row in its file group, which says `answered`, and in the
     * answer's gutter tooltip, which puts the question first — and since this change it is in the
     * popup as well, which is where an answer is actually read. A row already carrying a position,
     * a preview and a file name would have to give up the preview to fit the question, which trades
     * the one thing only this row shows for a third copy of something shown elsewhere.
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
 * This is now the only place that rule lives; describe() held a second copy and is gone.
 */
fun remarkNode(row: ResolvedRemark, hasAnswer: Boolean = false): RemarkNode {
    val result = row.result
    val label = movedOrOrphanedLabel(result, row.remark.startLine, row.remark.endLine, row.remark.commit)
    return RemarkNode(
        id = row.remark.id.orEmpty(),
        path = row.remark.path.orEmpty(),
        position = rowPosition(result, row.startColumn, row.endColumn) + label,
        // On one line, whatever was typed. The row is drawn by SimpleColoredComponent, which has no
        // idea what to do with a newline, and Shift+Enter in the input popup makes multi-line remark
        // text ordinary rather than exotic. The stored text keeps its newlines; only the row is
        // flattened, and the copied prompt still gets the remark as written.
        text = row.remark.text.orEmpty().replace('\n', ' '),
        bucket = row.remark.bucket,
        status = row.remark.status,
        startLine = result.startLine,
        asksForAnswer = row.remark.asksForAnswer,
        hasAnswer = hasAnswer,
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
 * bucket together with a file inside it would otherwise count that file's rows twice.
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
 * Recursive, not one level down. A bucket node's children are file nodes, and a one-level walk over
 * them finds GroupNodes and answers nothing at all — so selecting a bucket and pressing Copy
 * Selected or Delete would do nothing, with no message.
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
 * The whole tree, rebuilt from scratch. Files in path order, rows inside a file in resolved line
 * order, and buckets in name order with the unbucketed ones first — those are the remarks just
 * written, and the ones about to be moved.
 *
 * A remark about no file goes into its own group first, keyed [GENERAL_KEY] and labelled "General",
 * above the bucket and file groups. Which remarks those are is asked of `isAboutNoFile` in
 * `store/RemarkResolver.kt`, the one place that decides it, rather than re-read off [RemarkNode]'s
 * flattened path here. Its own bucket is
 * ignored for this: a general remark is about the whole change, so the top of the tree is where it
 * belongs, whatever bucket it also carries. That is a real cost, not an oversight — put a general
 * remark in a bucket and the bucket does not gather it — accepted because a remark reachable from
 * two places in the tree would be worse than one field being ignored.
 *
 * The bucket level below it appears only once some remark that is about a real file is actually in
 * a bucket. Without that check, anyone who never uses buckets would get a "(no bucket)" node
 * wrapped around their whole tree for a feature they never asked for.
 *
 * A remark with no id is left out. Its node would draw normally and then do nothing: Delete and
 * Copy Selected both match on the id, and an empty id matches no stored remark. RemarkGutter drops
 * the same rows for the same reason.
 *
 * An answer is a **child of the question it answers**, wherever that question sits — in the General
 * group or in a file group, in a bucket or not. So it is next to the thing it is about, and it takes
 * that question's place in bucket, file and line order rather than any order of its own. Being a
 * child here is a view and nothing else: the store has two independent records, and Delete calls
 * `deleteAnswer` for the answer row in its own right.
 *
 * An answer whose question is **not** in the tree — one carrying no `remarkId`, and one naming a
 * remark that is gone or that got no node — has no parent to sit under, and goes into a flat
 * top-level group keyed [ANSWERS_KEY] above the General group, sorted **newest first**, a different
 * order from every other group in the tree. That group appears only when at least one such answer
 * exists, which is not the ordinary case: normally every answer nests and the group is absent.
 */
fun buildTreeRoot(
    rows: List<ResolvedRemark>,
    answers: List<ResolvedAnswer> = emptyList(),
): DefaultMutableTreeNode {
    val root = DefaultMutableTreeNode("remarks")

    // Filtered once, then used three times. An answer with no id draws no row — the same rule a
    // remark with no id follows below — so it must not flip its remark's suffix from "asks" to
    // "answered" either: a row saying the question was answered, with no answer anywhere to open, is
    // worse than the row saying nothing.
    val withIds = answers.filter { it.answer.id != null }

    // The remarks that will really get a node, and so the ids an answer can attach to. Taken from
    // this same filtered list rather than asked of the store again: a remark with no id produces no
    // node, so an answer naming one has no parent and has to be treated as having none at all.
    val remarkRows = rows.filter { it.remark.id != null }
    val questionIds = remarkRows.mapNotNull { it.remark.id }.toSet()

    val (nestedAnswers, looseAnswers) = withIds.partition { it.answer.remarkId in questionIds }
    val nestedByQuestion = nestedAnswers.groupBy(
        { it.answer.remarkId.orEmpty() },
        { answerNode(it, nested = true) },
    )

    val looseRows = looseAnswers.map(::answerNode).sortedByDescending { it.answeredAt }
    if (looseRows.isNotEmpty()) {
        val answersNode = DefaultMutableTreeNode(GroupNode(ANSWERS_KEY, ANSWERS_LABEL))
        looseRows.forEach { answersNode.add(DefaultMutableTreeNode(it)) }
        root.add(answersNode)
    }

    // Which remarks already have an answer, so a marked row can say "answered" rather than "asks".
    // Read off the answers this same rebuild resolved, not from the store again: the two views must
    // agree, and the answer's own remarkId is the only link between them.
    val answered = withIds.mapNotNull { it.answer.remarkId }.toSet()

    // Split on the stored remark, then map and sort each side, rather than mapping first and asking
    // the node. Same rows in the same order either way — partition keeps order and the sort is
    // stable — but this way the question "is this about no file" is asked of isAboutNoFile once.
    val (generalRows, fileRows) = remarkRows.partition { isAboutNoFile(it.remark) }
    val order = compareBy<RemarkNode>({ it.bucket ?: "" }, { it.path }, { it.startLine })
    val general = generalRows.map { remarkNode(it, it.remark.id in answered) }.sortedWith(order)
    val aboutAFile = fileRows.map { remarkNode(it, it.remark.id in answered) }.sortedWith(order)

    if (general.isNotEmpty()) {
        val generalNode = DefaultMutableTreeNode(GroupNode(GENERAL_KEY, GENERAL_LABEL))
        general.forEach { generalNode.add(questionTreeNode(it, nestedByQuestion)) }
        root.add(generalNode)
    }

    if (aboutAFile.none { it.bucket != null }) {
        addFileGroups(root, "", aboutAFile, nestedByQuestion)
        return root
    }

    aboutAFile.groupBy { it.bucket }.forEach { (bucket, inBucket) ->
        val label = bucket ?: NO_BUCKET_LABEL
        // Keyed on the raw bucket, not on the label: a bucket literally named "(no bucket)" would
        // otherwise share a key with the null-bucket group, and the panel would restore the selection
        // and the collapsed state of the wrong one of two sibling rows. A leading space cannot occur
        // in a real bucket name, because setRemarkBucket trims it.
        val key = bucket?.let { BUCKET_KEY_PREFIX + it } ?: NO_BUCKET_KEY
        val bucketNode = DefaultMutableTreeNode(GroupNode(key, label))
        addFileGroups(bucketNode, "$key/", inBucket, nestedByQuestion)
        root.add(bucketNode)
    }
    return root
}

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
    keyPrefix: String,
    nodes: List<RemarkNode>,
    answersByQuestion: Map<String, List<AnswerNode>>,
) {
    nodes.groupBy { it.path }.forEach { (path, inFile) ->
        val fileNode = DefaultMutableTreeNode(
            GroupNode("${keyPrefix}file:$path", path.substringAfterLast('/'), shortDirectory(path))
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
 * What a drop on some tree row would do: put the dragged remarks in [bucket], or, when [bucket] is
 * null, take them out of whatever bucket they are in.
 *
 * A wrapper rather than a bare `String?`, because "no target here" and "the target is: no bucket"
 * are two different answers and a plain null could only say one of them.
 */
data class BucketDrop(val bucket: String?)

/**
 * The bucket a drop on [node] means, or null when [node] is not a drop target at all.
 *
 * This is the whole of the drag-and-drop logic, and it is pure, because a real drag cannot be
 * driven from a unit test: the platform's own DnD machinery needs a window, a pointer and a running
 * event loop. What is left for the wiring in `RemarksToolWindowFactory` is finding the node under
 * the pointer and calling `setRemarkBucket`.
 *
 * The answers, in the order they matter: a bucket group is a target for its own name; the
 * "(no bucket)" group is a target that clears the bucket, because `setRemarkBucket` already takes
 * null and clearing is the natural inverse of setting; a file group or a remark row inside a bucket
 * gives that bucket, so dropping anywhere inside a bucket's subtree means the same thing as dropping
 * on its header; and everything else is not a target — the General group, the Answers group, a file
 * group with no bucket level above it, and the tree root.
 *
 * The bucket is read from the top-level group's **key**, not its label. A bucket a person named
 * "(no bucket)" draws the same label as the group for remarks in no bucket, and only the key tells
 * the two apart. See [buildTreeRoot] for the same argument on the other side.
 */
fun bucketDropTarget(node: DefaultMutableTreeNode?): BucketDrop? {
    val top = (topLevelAncestor(node)?.userObject as? GroupNode) ?: return null
    return when {
        top.key == NO_BUCKET_KEY -> BucketDrop(null)
        top.key.startsWith(BUCKET_KEY_PREFIX) -> BucketDrop(top.key.removePrefix(BUCKET_KEY_PREFIX))
        // The General group, the group for answers with no question, and a file group at the top
        // level all land here. A general remark is about the whole change, so there is no bucket to
        // read off it; an answer with no question is under no bucket at all; and a tree with no
        // bucket level has nothing to drop onto. An answer nested under a question inside a bucket
        // does resolve to that bucket, which is harmless: a drag carries `selectedIds()`, which is
        // remark ids only, so such a row drags nothing.
        else -> null
    }
}

/**
 * The ancestor of [node] whose own parent is the invisible root, or [node] itself when it already
 * is one. Null for the root and for any node not attached to a tree.
 *
 * Walking to the top rather than reading [node]'s own key is what keeps this independent of the key
 * format: a bucket named with a slash builds file keys like "bucket:a/b/file:src/Foo.kt", which no
 * amount of string splitting can take apart safely.
 */
private fun topLevelAncestor(node: DefaultMutableTreeNode?): DefaultMutableTreeNode? {
    var current = node ?: return null
    while (true) {
        val parent = current.parent as? DefaultMutableTreeNode ?: return null
        if (parent.parent == null) return current
        current = parent
    }
}

/**
 * ColoredTreeCellRenderer, not NodeRenderer: NodeRenderer is wired to ItemPresentation and
 * NodeDescriptor, and these nodes carry plain user objects.
 *
 * Two colours in one row means two append calls; there is no way to colour part of one string.
 */
class RemarkTreeRenderer : ColoredTreeCellRenderer() {
    override fun customizeCellRenderer(
        tree: JTree,
        value: Any?,
        selected: Boolean,
        expanded: Boolean,
        leaf: Boolean,
        row: Int,
        hasFocus: Boolean,
    ) {
        when (val user = (value as? DefaultMutableTreeNode)?.userObject) {
            is RemarkNode -> {
                icon = RemarkStatusLook.icon(
                    status = user.status,
                    asksForAnswer = user.asksForAnswer,
                    hasAnswer = user.hasAnswer,
                )
                val body = RemarkStatusLook.textAttributes(user.status)
                append("${user.position}  ", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                append(user.text, body)
                when (user.status) {
                    RemarkStatus.PUBLISHED -> append("  published", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                    RemarkStatus.READ -> append("  read", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                    RemarkStatus.PENDING -> {}
                }
            }

            is AnswerNode -> {
                icon = AllIcons.General.Balloon
                if (user.position.isNotEmpty()) {
                    append("${user.position}  ", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                }
                append(user.firstLine, SimpleTextAttributes.REGULAR_ATTRIBUTES)
                if (user.fileName.isNotEmpty()) {
                    append("  ${user.fileName}", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                }
            }

            is GroupNode -> {
                append(user.label, SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
                user.detail?.let { append("  $it", SimpleTextAttributes.GRAYED_ATTRIBUTES) }
            }
        }
    }
}
