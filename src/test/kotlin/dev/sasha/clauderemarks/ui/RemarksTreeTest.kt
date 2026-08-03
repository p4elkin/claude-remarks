package dev.sasha.clauderemarks.ui

import dev.sasha.clauderemarks.anchor.AnchorResult
import dev.sasha.clauderemarks.model.RemarkSeverity
import dev.sasha.clauderemarks.model.RemarkState
import dev.sasha.clauderemarks.model.RemarkStatus
import dev.sasha.clauderemarks.model.RemarkTag
import dev.sasha.clauderemarks.store.ResolvedRemark
import javax.swing.tree.DefaultMutableTreeNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RemarksTreeTest {

    @Test
    fun `rows are grouped under their file, in path order`() {
        val root = buildTreeRoot(
            listOf(
                row(path = "src/Zed.kt", id = "r-1"),
                row(path = "src/Alpha.kt", id = "r-2"),
                row(path = "src/Zed.kt", id = "r-3"),
            )
        )

        assertEquals(listOf("src/Alpha.kt", "src/Zed.kt"), fileNames(root))
        assertEquals(listOf("r-2"), idsUnder(root, 0))
        assertEquals(listOf("r-1", "r-3"), idsUnder(root, 1))
    }

    /**
     * The ids are named so that alphabetical order DISAGREES with line order. With ids "later" and
     * "earlier" the two orders happen to agree, and sorting by id would pass this test.
     */
    @Test
    fun `rows inside a file are ordered by the line they resolved to`() {
        val root = buildTreeRoot(
            listOf(
                row(id = "a-later", result = AnchorResult.Exact(20, 20)),
                row(id = "b-earlier", result = AnchorResult.Exact(3, 3)),
            )
        )

        assertEquals(listOf("b-earlier", "a-later"), idsUnder(root, 0))
    }

    @Test
    fun `a leaf carries what the row needs to draw and to navigate`() {
        val node = remarkNode(
            row(id = "r-1", text = "why?", tag = RemarkTag.BUG, result = AnchorResult.Exact(4, 6))
        )

        assertEquals("r-1", node.id)
        assertEquals("src/Foo.kt", node.path)
        assertEquals("5-7", node.position)
        assertEquals("why?", node.text)
        assertEquals("bug", node.tag)
        assertEquals(4, node.startLine)
        assertEquals(RemarkStatus.PENDING, node.status)
    }

    @Test
    fun `a moved row says so, the same way the flat list did`() {
        assertEquals("11-13 (moved)", remarkNode(row(result = AnchorResult.Relocated(10, 12))).position)
    }

    @Test
    fun `an orphaned row says so and keeps its stale line numbers`() {
        assertEquals("5-7 (orphaned)", remarkNode(row(result = AnchorResult.Orphaned(4, 6))).position)
    }

    /**
     * An orphan is exactly when the commit is worth something: the code moved, and diffing against
     * the revision the remark was written at is the fastest way to find where it went.
     */
    @Test
    fun `an orphaned row says which commit the remark was written at`() {
        val node = remarkNode(
            row(result = AnchorResult.Orphaned(4, 6), commit = "0123456789abcdef0123456789abcdef01234567")
        )

        assertEquals("5-7 (orphaned, written at 01234567)", node.position)
    }

    @Test
    fun `an orphaned row with no commit says only that it is orphaned`() {
        assertEquals(
            "5-7 (orphaned)",
            remarkNode(row(result = AnchorResult.Orphaned(4, 6), commit = null)).position,
        )
    }

    @Test
    fun `a published remark's node carries PUBLISHED and a read one carries READ`() {
        assertEquals(RemarkStatus.PUBLISHED, remarkNode(row(status = RemarkStatus.PUBLISHED)).status)
        assertEquals(RemarkStatus.READ, remarkNode(row(status = RemarkStatus.READ)).status)
    }

    @Test
    fun `a remark with no tag has no tag on its node`() {
        assertEquals(null, remarkNode(row(tag = null)).tag)
    }

    /** Shift+Enter in the input popup makes this ordinary, and a row is one line of text. */
    @Test
    fun `a multi-line remark is flattened onto one row`() {
        assertEquals("first line second line", remarkNode(row(text = "first line\nsecond line")).text)
    }

    @Test
    fun `an empty list gives a root with no children`() {
        assertEquals(0, buildTreeRoot(emptyList()).childCount)
    }

    /**
     * Selecting a file and pressing Delete used to do nothing at all — no dialog, no message,
     * nothing in the log — because only leaves carried a RemarkNode.
     */
    @Test
    fun `selecting a file node counts as selecting every row under it`() {
        val root = buildTreeRoot(
            listOf(
                row(id = "a-later", result = AnchorResult.Exact(20, 20)),
                row(id = "b-earlier", result = AnchorResult.Exact(3, 3)),
            )
        )

        val ids = remarkNodesUnder(listOf(root.getChildAt(0) as DefaultMutableTreeNode)).map { it.id }

        assertEquals(listOf("b-earlier", "a-later"), ids)
    }

    @Test
    fun `a file and one of its own rows selected together do not count that row twice`() {
        val root = buildTreeRoot(listOf(row(id = "r-1"), row(id = "r-2")))
        val file = root.getChildAt(0) as DefaultMutableTreeNode
        val firstRow = file.getChildAt(0) as DefaultMutableTreeNode

        assertEquals(2, remarkNodesUnder(listOf(file, firstRow)).size)
    }

    /** Somebody who has never used a bucket must get exactly the tree they had before. */
    @Test
    fun `with no buckets in use the tree is still root then file then remark`() {
        val root = buildTreeRoot(listOf(row(path = "src/Foo.kt", id = "r-1")))

        assertEquals(1, root.childCount)
        val file = root.getChildAt(0) as DefaultMutableTreeNode
        assertEquals("src/Foo.kt", (file.userObject as GroupNode).label)
        assertTrue((file.getChildAt(0) as DefaultMutableTreeNode).userObject is RemarkNode)
    }

    @Test
    fun `one remark in a bucket puts every remark under a bucket level`() {
        val root = buildTreeRoot(
            listOf(
                row(id = "r-1", path = "src/Foo.kt", bucket = "auth refactor"),
                row(id = "r-2", path = "src/Foo.kt", bucket = null),
            )
        )

        val labels = (0 until root.childCount)
            .map { ((root.getChildAt(it) as DefaultMutableTreeNode).userObject as GroupNode).label }
        // Unbucketed first: those are the remarks just written, and they are the ones being moved.
        assertEquals(listOf(NO_BUCKET_LABEL, "auth refactor"), labels)
    }

    /**
     * A bucket and a file can carry the same name. The panel uses a group's key to put a selection
     * back after a rebuild, so two groups sharing a key means restoring the wrong row.
     */
    @Test
    fun `a bucket and a file with the same name have different keys`() {
        val root = buildTreeRoot(listOf(row(id = "r-1", path = "src", bucket = "src")))

        val bucket = (root.getChildAt(0) as DefaultMutableTreeNode).userObject as GroupNode
        val file = ((root.getChildAt(0) as DefaultMutableTreeNode)
            .getChildAt(0) as DefaultMutableTreeNode).userObject as GroupNode

        assertEquals("src", bucket.label)
        assertEquals("src", file.label)
        assertNotEquals(bucket.key, file.key)
    }

    /**
     * The same file holds remarks in two different buckets, so it is drawn as a file group twice.
     * Those two groups are different rows and need different keys: the panel restores the selection
     * and the collapsed groups after every rebuild by matching keys, so one shared key means the
     * wrong one of the two is picked. The test above does not cover this — a bucket key already
     * starts with "bucket:", so it never collides with a bare path — which is why this one exists.
     */
    @Test
    fun `the same file under two buckets gives two different keys`() {
        val root = buildTreeRoot(
            listOf(
                row(id = "r-1", path = "src/Foo.kt", bucket = "a"),
                row(id = "r-2", path = "src/Foo.kt", bucket = "b"),
            )
        )

        val fileKeys = (0 until root.childCount).map {
            val bucket = root.getChildAt(it) as DefaultMutableTreeNode
            ((bucket.getChildAt(0) as DefaultMutableTreeNode).userObject as GroupNode).key
        }

        assertEquals(2, fileKeys.size)
        assertNotEquals(fileKeys[0], fileKeys[1])
    }

    /**
     * Selecting a bucket and pressing Copy Selected is what "Copy Bucket" means, and it is the only
     * reason no Copy Bucket button is needed. The old one-level walk found file nodes under a
     * bucket, none of which is a RemarkNode, and answered an empty list — so Copy Selected and
     * Delete would both have done nothing at all, silently.
     */
    @Test
    fun `selecting a bucket node counts as selecting every row under every file in it`() {
        val root = buildTreeRoot(
            listOf(
                row(id = "r-1", path = "src/A.kt", bucket = "b"),
                row(id = "r-2", path = "src/Z.kt", bucket = "b"),
            )
        )

        val ids = remarkNodesUnder(listOf(root.getChildAt(0) as DefaultMutableTreeNode)).map { it.id }

        assertEquals(listOf("r-1", "r-2"), ids)
    }

    /**
     * A bucket somebody actually names "(no bucket)". Keyed on the label, that bucket and the
     * null-bucket group both came out as "bucket:(no bucket)", so two sibling rows shared a key and
     * the panel's restoreSelection and recollapse treated them as one row.
     */
    @Test
    fun `a bucket named like the no-bucket label still gets its own key`() {
        val root = buildTreeRoot(
            listOf(
                row(id = "r-1", bucket = NO_BUCKET_LABEL),
                row(id = "r-2", bucket = null),
            )
        )

        val keys = (0 until root.childCount)
            .map { ((root.getChildAt(it) as DefaultMutableTreeNode).userObject as GroupNode).key }

        assertEquals(2, keys.size)
        assertNotEquals(keys[0], keys[1])
    }

    /** buildTreeRoot's own doc says "buckets in name order", and nothing checked it. */
    @Test
    fun `buckets are drawn in name order`() {
        val root = buildTreeRoot(
            listOf(row(id = "r-1", bucket = "z"), row(id = "r-2", bucket = "a"))
        )

        assertEquals(listOf("a", "z"), fileNames(root))
    }

    @Test
    fun `a leaf carries its bucket and its severity`() {
        val node = remarkNode(row(bucket = "b", severity = RemarkSeverity.MUST))

        assertEquals("b", node.bucket)
        assertEquals("must", node.severity)
    }

    private fun fileNames(root: DefaultMutableTreeNode) =
        (0 until root.childCount).map {
            ((root.getChildAt(it) as DefaultMutableTreeNode).userObject as GroupNode).label
        }

    private fun idsUnder(root: DefaultMutableTreeNode, index: Int): List<String> {
        val file = root.getChildAt(index) as DefaultMutableTreeNode
        return (0 until file.childCount).map {
            ((file.getChildAt(it) as DefaultMutableTreeNode).userObject as RemarkNode).id
        }
    }

    private fun row(
        path: String = "src/Foo.kt",
        id: String = "r-1",
        text: String = "why?",
        tag: RemarkTag? = null,
        status: RemarkStatus = RemarkStatus.PENDING,
        result: AnchorResult = AnchorResult.Exact(4, 6),
        bucket: String? = null,
        severity: RemarkSeverity = RemarkSeverity.SHOULD,
        commit: String? = null,
    ) = ResolvedRemark(
        RemarkState().also {
            it.id = id
            it.path = path
            it.startLine = 4
            it.endLine = 6
            it.text = text
            it.tag = tag
            it.status = status
            it.bucket = bucket
            it.severity = severity
            it.commit = commit
        },
        result,
    )
}
