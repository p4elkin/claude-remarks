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
 * The key of the group that holds every remark about no file. A file key always starts with
 * "file:" and a bucket key always starts with "bucket:", so this bare word cannot collide with
 * either, and `RemarksPanel`'s selection restore, which matches groups by key, keeps working.
 */
const val GENERAL_KEY = "general"

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
 * A remark about no file (`path.isEmpty()`, from [remarkNode]) goes into its own group first,
 * keyed [GENERAL_KEY] and labelled "General", above the bucket and file groups. Its own bucket is
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
    val nodes = rows.filter { it.remark.id != null }
        .map(::remarkNode)
        .sortedWith(compareBy({ it.bucket ?: "" }, { it.path }, { it.startLine }))

    val (general, aboutAFile) = nodes.partition { it.path.isEmpty() }
    if (general.isNotEmpty()) {
        val generalNode = DefaultMutableTreeNode(GroupNode(GENERAL_KEY, "General"))
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

            is GroupNode -> {
                append(user.label, SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
                user.detail?.let { append("  $it", SimpleTextAttributes.GRAYED_ATTRIBUTES) }
            }
        }
    }
}
