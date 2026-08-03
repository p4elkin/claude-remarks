package dev.sasha.clauderemarks.ui

import com.intellij.ui.ColoredTreeCellRenderer
import com.intellij.ui.SimpleTextAttributes
import dev.sasha.clauderemarks.anchor.AnchorResult
import dev.sasha.clauderemarks.model.RemarkStatus
import dev.sasha.clauderemarks.model.label
import dev.sasha.clauderemarks.store.ResolvedRemark
import javax.swing.JTree
import javax.swing.tree.DefaultMutableTreeNode

/** The label shown for remarks that are in no bucket, once any bucket exists. */
const val NO_BUCKET_LABEL = "(no bucket)"

/**
 * A group row: a bucket or a file.
 *
 * The key and the label are separate on purpose. A bucket can be called "src" and so can a
 * directory, and the panel puts a selection back after every rebuild by matching keys. Two groups
 * sharing a key means restoring the wrong one. The key is the whole path from the root; the label
 * is what is drawn.
 */
data class GroupNode(val key: String, val label: String)

/** One leaf. Everything a row needs to draw itself and to navigate. */
data class RemarkNode(
    val id: String,
    val path: String,
    val position: String,
    val text: String,
    val tag: String?,
    val severity: String,
    val bucket: String?,
    val status: RemarkStatus,
    val startLine: Int,
)

/**
 * The 1-based position with its label, the same string the old flat list showed. A Relocated
 * result that came back at exactly the stored range is not called moved, because that is the case
 * where the block was edited where it stands.
 *
 * This is now the only place that rule lives; describe() held a second copy and is gone.
 */
fun remarkNode(row: ResolvedRemark): RemarkNode {
    val result = row.result
    val movedFromStored =
        result.startLine != row.remark.startLine || result.endLine != row.remark.endLine
    val label = when {
        result is AnchorResult.Orphaned -> " (orphaned${writtenAt(row.remark.commit)})"
        result is AnchorResult.Relocated && movedFromStored -> " (moved)"
        else -> ""
    }
    return RemarkNode(
        id = row.remark.id.orEmpty(),
        path = row.remark.path.orEmpty(),
        position = positionLabel(result, row.startColumn, row.endColumn) + label,
        // On one line, whatever was typed. The row is drawn by SimpleColoredComponent, which has no
        // idea what to do with a newline, and Shift+Enter in the input popup makes multi-line remark
        // text ordinary rather than exotic. The stored text keeps its newlines; only the row is
        // flattened, and the copied prompt still gets the remark as written.
        text = row.remark.text.orEmpty().replace('\n', ' '),
        tag = row.remark.tag?.label,
        severity = row.remark.severity.label,
        bucket = row.remark.bucket,
        status = row.remark.status,
        startLine = result.startLine,
    )
}

/**
 * The 1-based position, before any "(moved)"/"(orphaned...)" suffix. A whole-line remark reads
 * "9-9". A sub-line remark inside one line reads "9:12-38". A sub-line remark across lines reads
 * "9:12-11:5". Columns are shown 1-based, the same +1 the line numbers already get.
 *
 * Whether there is a real sub-line range to show is decided differently on one line than across
 * several, because `startColumn`/`endColumn` are two independent per-line offsets, not a single
 * ordered pair — `action/AddRemarkAction.kt`'s `selectedColumns` measures each from its own
 * line's start, so a long first line and a short last line make `endColumn < startColumn`
 * perfectly normal for a real multi-line selection. On one line the two columns bound the same
 * line, so `endColumn > startColumn` is what "a real range" means there — the same comparison
 * `markersValid` makes in `render/PromptRenderer.kt`. Across lines, `selectedColumns` returns the
 * sentinel `0 to 0` only when the whole span was selected end to end (including a multi-line
 * whole-line selection), so `endColumn > 0` is the right "not the sentinel" check there; ordering
 * the two columns against each other would wrongly reject a real, valid selection.
 *
 * Either way, an [AnchorResult.Orphaned] result never gets columns: its line numbers no longer
 * point at real code, so there is no current line left to check a column against, matching
 * `markersValid`'s own `remark.orphaned` check. A negative column, reachable only from a
 * hand-edited workspace.xml, is rejected the same way.
 */
private fun positionLabel(result: AnchorResult, startColumn: Int, endColumn: Int): String {
    val startLine = result.startLine + 1
    val endLine = result.endLine + 1
    val sameLine = result.startLine == result.endLine
    val hasColumns = result !is AnchorResult.Orphaned && startColumn >= 0 && endColumn >= 0 &&
        (if (sameLine) endColumn > startColumn else endColumn > 0)
    if (!hasColumns) return "$startLine-$endLine"
    return if (sameLine) {
        "$startLine:${startColumn + 1}-${endColumn + 1}"
    } else {
        "$startLine:${startColumn + 1}-$endLine:${endColumn + 1}"
    }
}

/** Short, because a tree row is already carrying a position, a text, a tag and a level. */
private fun writtenAt(commit: String?): String =
    commit?.let { ", written at ${it.take(8)}" }.orEmpty()

/**
 * The remark rows a set of selected tree nodes covers, at any depth. Distinct, because selecting a
 * bucket together with a file inside it would otherwise count that file's rows twice.
 */
fun remarkNodesUnder(selected: List<DefaultMutableTreeNode>): List<RemarkNode> =
    selected.flatMap(::leavesOf).distinct()

/**
 * Recursive, not one level down. A bucket node's children are file nodes, and a one-level walk over
 * them finds GroupNodes and answers nothing at all — so selecting a bucket and pressing Copy
 * Selected or Delete would do nothing, with no message.
 */
private fun leavesOf(node: DefaultMutableTreeNode): List<RemarkNode> =
    when (val user = node.userObject) {
        is RemarkNode -> listOf(user)
        else -> (0 until node.childCount).flatMap { index ->
            (node.getChildAt(index) as? DefaultMutableTreeNode)?.let(::leavesOf).orEmpty()
        }
    }

/**
 * The whole tree, rebuilt from scratch. Files in path order, rows inside a file in resolved line
 * order, and buckets in name order with the unbucketed ones first — those are the remarks just
 * written, and the ones about to be moved.
 *
 * The bucket level appears only once some remark is actually in a bucket. Without that check,
 * anyone who never uses buckets would get a "(no bucket)" node wrapped around their whole tree for
 * a feature they never asked for.
 *
 * A remark with no id is left out. Its node would draw normally and then do nothing: Delete and
 * Copy Selected both match on the id, and an empty id matches no stored remark. RemarkGutter drops
 * the same rows for the same reason.
 */
fun buildTreeRoot(rows: List<ResolvedRemark>): DefaultMutableTreeNode {
    val root = DefaultMutableTreeNode("remarks")
    val nodes = rows.filter { it.remark.id != null }
        .map(::remarkNode)
        .sortedWith(compareBy({ it.bucket ?: "" }, { it.path }, { it.startLine }))

    if (nodes.none { it.bucket != null }) {
        addFileGroups(root, "", nodes)
        return root
    }

    nodes.groupBy { it.bucket }.forEach { (bucket, inBucket) ->
        val label = bucket ?: NO_BUCKET_LABEL
        // Keyed on the raw bucket, not on the label: a bucket literally named "(no bucket)" would
        // otherwise share a key with the null-bucket group, and the panel would restore the selection
        // and the collapsed state of the wrong one of two sibling rows. A leading space cannot occur
        // in a real bucket name, because setRemarkBucket trims it.
        val key = "bucket:" + (bucket ?: " none")
        val bucketNode = DefaultMutableTreeNode(GroupNode(key, label))
        addFileGroups(bucketNode, "$key/", inBucket)
        root.add(bucketNode)
    }
    return root
}

private fun addFileGroups(
    parent: DefaultMutableTreeNode,
    keyPrefix: String,
    nodes: List<RemarkNode>,
) {
    nodes.groupBy { it.path }.forEach { (path, inFile) ->
        val fileNode = DefaultMutableTreeNode(GroupNode("${keyPrefix}file:$path", path))
        inFile.forEach { fileNode.add(DefaultMutableTreeNode(it)) }
        parent.add(fileNode)
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
                val body =
                    if (user.status == RemarkStatus.PENDING) SimpleTextAttributes.REGULAR_ATTRIBUTES
                    else SimpleTextAttributes.GRAYED_ATTRIBUTES
                append("${user.position}  ", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                append(user.text, body)
                user.tag?.let { append("  [$it]", SimpleTextAttributes.GRAYED_ATTRIBUTES) }
                append("  ${user.severity}", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                when (user.status) {
                    RemarkStatus.PUBLISHED -> append("  published", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                    RemarkStatus.READ -> append("  read", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                    RemarkStatus.PENDING -> {}
                }
            }

            is GroupNode -> append(user.label, SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
        }
    }
}
