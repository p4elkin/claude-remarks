package dev.sasha.clauderemarks.ui

import com.intellij.ui.ColoredTreeCellRenderer
import com.intellij.ui.SimpleTextAttributes
import dev.sasha.clauderemarks.anchor.AnchorResult
import dev.sasha.clauderemarks.anchor.positionLabel
import dev.sasha.clauderemarks.model.RemarkStatus
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

/** One leaf. Everything a row needs to draw itself and to navigate. */
data class RemarkNode(
    val id: String,
    val path: String,
    val position: String,
    val text: String,
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
        position = rowPosition(result, row.startColumn, row.endColumn) + label,
        // On one line, whatever was typed. The row is drawn by SimpleColoredComponent, which has no
        // idea what to do with a newline, and Shift+Enter in the input popup makes multi-line remark
        // text ordinary rather than exotic. The stored text keeps its newlines; only the row is
        // flattened, and the copied prompt still gets the remark as written.
        text = row.remark.text.orEmpty().replace('\n', ' '),
        bucket = row.remark.bucket,
        status = row.remark.status,
        startLine = result.startLine,
    )
}

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
 */
fun buildTreeRoot(rows: List<ResolvedRemark>): DefaultMutableTreeNode {
    val root = DefaultMutableTreeNode("remarks")
    // Split on the stored remark, then map and sort each side, rather than mapping first and asking
    // the node. Same rows in the same order either way — partition keeps order and the sort is
    // stable — but this way the question "is this about no file" is asked of isAboutNoFile once.
    val (generalRows, fileRows) = rows.filter { it.remark.id != null }
        .partition { isAboutNoFile(it.remark) }
    val order = compareBy<RemarkNode>({ it.bucket ?: "" }, { it.path }, { it.startLine })
    val general = generalRows.map(::remarkNode).sortedWith(order)
    val aboutAFile = fileRows.map(::remarkNode).sortedWith(order)

    if (general.isNotEmpty()) {
        val generalNode = DefaultMutableTreeNode(GroupNode(GENERAL_KEY, GENERAL_LABEL))
        general.forEach { generalNode.add(DefaultMutableTreeNode(it)) }
        root.add(generalNode)
    }

    if (aboutAFile.none { it.bucket != null }) {
        addFileGroups(root, "", aboutAFile)
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
        addFileGroups(bucketNode, "$key/", inBucket)
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
) {
    nodes.groupBy { it.path }.forEach { (path, inFile) ->
        val fileNode = DefaultMutableTreeNode(
            GroupNode("${keyPrefix}file:$path", path.substringAfterLast('/'), shortDirectory(path))
        )
        inFile.forEach { fileNode.add(DefaultMutableTreeNode(it)) }
        parent.add(fileNode)
    }
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
 * on its header; and everything else is not a target — the General group, a file group with no
 * bucket level above it, and the tree root.
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
        // The General group and a file group at the top level both land here. A general remark is
        // about the whole change, so there is no bucket to read off it, and a tree with no bucket
        // level has nothing to drop onto at all.
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
                icon = RemarkStatusLook.icon(user.status)
                val body = RemarkStatusLook.textAttributes(user.status)
                append("${user.position}  ", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                append(user.text, body)
                when (user.status) {
                    RemarkStatus.PUBLISHED -> append("  published", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                    RemarkStatus.READ -> append("  read", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                    RemarkStatus.PENDING -> {}
                }
            }

            is GroupNode -> {
                append(user.label, SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
                user.detail?.let { append("  $it", SimpleTextAttributes.GRAYED_ATTRIBUTES) }
            }
        }
    }
}
