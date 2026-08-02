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
    val sent: Boolean,
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
        result is AnchorResult.Orphaned -> " (orphaned)"
        result is AnchorResult.Relocated && movedFromStored -> " (moved)"
        else -> ""
    }
    return RemarkNode(
        id = row.remark.id.orEmpty(),
        path = row.remark.path.orEmpty(),
        position = "${result.startLine + 1}-${result.endLine + 1}$label",
        // On one line, whatever was typed. The row is drawn by SimpleColoredComponent, which has no
        // idea what to do with a newline, and Shift+Enter in the input popup makes multi-line remark
        // text ordinary rather than exotic. The stored text keeps its newlines; only the row is
        // flattened, and the copied prompt still gets the remark as written.
        text = row.remark.text.orEmpty().replace('\n', ' '),
        tag = row.remark.tag?.label,
        severity = row.remark.severity.label,
        bucket = row.remark.bucket,
        sent = row.remark.status == RemarkStatus.SENT,
        startLine = result.startLine,
    )
}

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
        val key = "bucket:$label"
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
                    if (user.sent) SimpleTextAttributes.GRAYED_ATTRIBUTES
                    else SimpleTextAttributes.REGULAR_ATTRIBUTES
                append("${user.position}  ", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                append(user.text, body)
                user.tag?.let { append("  [$it]", SimpleTextAttributes.GRAYED_ATTRIBUTES) }
                append("  ${user.severity}", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                if (user.sent) append("  sent", SimpleTextAttributes.GRAYED_ATTRIBUTES)
            }

            is GroupNode -> append(user.label, SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
        }
    }
}
