package dev.sasha.clauderemarks.ui

import dev.sasha.clauderemarks.anchor.AnchorResult
import dev.sasha.clauderemarks.model.RemarkState
import dev.sasha.clauderemarks.model.RemarkStatus
import dev.sasha.clauderemarks.model.RemarkTag
import dev.sasha.clauderemarks.store.ResolvedRemark
import javax.swing.tree.DefaultMutableTreeNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
        assertFalse(node.sent)
    }

    @Test
    fun `a moved row says so, the same way the flat list did`() {
        assertEquals("11-13 (moved)", remarkNode(row(result = AnchorResult.Relocated(10, 12))).position)
    }

    @Test
    fun `an orphaned row says so and keeps its stale line numbers`() {
        assertEquals("5-7 (orphaned)", remarkNode(row(result = AnchorResult.Orphaned(4, 6))).position)
    }

    @Test
    fun `a sent row is flagged, not dropped`() {
        assertTrue(remarkNode(row(status = RemarkStatus.SENT)).sent)
    }

    @Test
    fun `a remark with no tag has no tag on its node`() {
        assertEquals(null, remarkNode(row(tag = null)).tag)
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

    private fun fileNames(root: DefaultMutableTreeNode) =
        (0 until root.childCount).map { (root.getChildAt(it) as DefaultMutableTreeNode).userObject }

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
    ) = ResolvedRemark(
        RemarkState().also {
            it.id = id
            it.path = path
            it.startLine = 4
            it.endLine = 6
            it.text = text
            it.tag = tag
            it.status = status
        },
        result,
    )
}
