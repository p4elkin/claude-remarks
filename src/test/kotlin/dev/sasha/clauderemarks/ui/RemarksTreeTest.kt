package dev.sasha.clauderemarks.ui

import dev.sasha.clauderemarks.anchor.AnchorResult
import dev.sasha.clauderemarks.model.AnswerState
import dev.sasha.clauderemarks.model.RemarkState
import dev.sasha.clauderemarks.model.RemarkStatus
import dev.sasha.clauderemarks.store.ResolvedAnswer
import dev.sasha.clauderemarks.store.ResolvedRemark
import javax.swing.tree.DefaultMutableTreeNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
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

        assertEquals(listOf("Alpha.kt", "Zed.kt"), fileNames(root))
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
            row(id = "r-1", text = "why?", result = AnchorResult.Exact(4, 6))
        )

        assertEquals("r-1", node.id)
        assertEquals("src/Foo.kt", node.path)
        assertEquals("5-7", node.position)
        assertEquals("why?", node.text)
        assertEquals(4, node.startLine)
        assertEquals(RemarkStatus.PENDING, node.status)
    }

    @Test
    fun `a whole-line remark's position has no columns`() {
        assertEquals("9-9", remarkNode(row(result = AnchorResult.Exact(8, 8))).position)
    }

    @Test
    fun `a sub-line remark inside one line shows the line and both columns`() {
        val node = remarkNode(
            row(result = AnchorResult.Exact(8, 8), startColumn = 11, endColumn = 37)
        )

        assertEquals("9:12-38", node.position)
    }

    @Test
    fun `a sub-line remark across lines shows a line-column pair at each end`() {
        val node = remarkNode(
            row(result = AnchorResult.Exact(8, 10), startColumn = 11, endColumn = 4)
        )

        assertEquals("9:12-11:5", node.position)
    }

    /**
     * The same guard `markersValid` makes in `render/PromptRenderer.kt`: an orphaned remark's
     * line numbers no longer point at real code, so there is no current line to check a stale
     * column pair against, and none is printed. Reachable only from a hand-edited workspace.xml,
     * since a resolved orphan never carries a phrase-matched column pair.
     */
    @Test
    fun `an orphaned row does not print its stale columns`() {
        val node = remarkNode(
            row(result = AnchorResult.Orphaned(4, 6), startColumn = 2, endColumn = 9)
        )

        assertEquals("5-7 (orphaned)", node.position)
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
        assertEquals("Foo.kt", (file.userObject as GroupNode).label)
        assertTrue((file.getChildAt(0) as DefaultMutableTreeNode).userObject is RemarkNode)
    }

    @Test
    fun `a file group's label is the file name and its detail is the directory`() {
        val root = buildTreeRoot(listOf(row(path = "src/main/Foo.kt", id = "r-1")))

        val group = (root.getChildAt(0) as DefaultMutableTreeNode).userObject as GroupNode
        assertEquals("Foo.kt", group.label)
        assertEquals("src/main", group.detail)
    }

    @Test
    fun `a file in the project root has no detail`() {
        val root = buildTreeRoot(listOf(row(path = "Foo.kt", id = "r-1")))

        val group = (root.getChildAt(0) as DefaultMutableTreeNode).userObject as GroupNode
        assertEquals("Foo.kt", group.label)
        assertEquals(null, group.detail)
    }

    @Test
    fun `a deep path's detail is shortened to the last two segments with an ellipsis`() {
        val root = buildTreeRoot(
            listOf(row(path = "src/main/kotlin/dev/sasha/clauderemarks/ui/Foo.kt", id = "r-1"))
        )

        val group = (root.getChildAt(0) as DefaultMutableTreeNode).userObject as GroupNode
        assertEquals("Foo.kt", group.label)
        assertEquals("…/clauderemarks/ui", group.detail)
    }

    /**
     * The label now shows the file name only, but the key is still the whole path — that is what
     * lets `RemarksPanel`'s selection restore, which matches groups by key, keep working across
     * this change.
     */
    @Test
    fun `a file group's key is unchanged from what the existing tests assert`() {
        val root = buildTreeRoot(listOf(row(path = "src/main/Foo.kt", id = "r-1")))

        val group = (root.getChildAt(0) as DefaultMutableTreeNode).userObject as GroupNode
        assertEquals("file:src/main/Foo.kt", group.key)
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
    fun `a general remark is in a group keyed general, placed first`() {
        val root = buildTreeRoot(
            listOf(
                row(path = "src/Foo.kt", id = "r-1"),
                row(path = "", id = "r-2"),
            )
        )

        val group = (root.getChildAt(0) as DefaultMutableTreeNode).userObject as GroupNode
        assertEquals(GENERAL_KEY, group.key)
        assertEquals("General", group.label)
        assertEquals(listOf("r-2"), idsUnder(root, 0))
    }

    /**
     * A general remark's bucket is ignored for grouping. Put one in a bucket and the bucket does
     * not gather it: the top of the tree is where a remark about the whole change should be read,
     * even at the cost of ignoring a field it carries.
     */
    @Test
    fun `a general remark stays in the General group even when it carries a bucket`() {
        val root = buildTreeRoot(
            listOf(
                row(path = "", id = "r-1", bucket = "auth refactor"),
                row(path = "src/Foo.kt", id = "r-2", bucket = "other"),
            )
        )

        val firstGroup = (root.getChildAt(0) as DefaultMutableTreeNode).userObject as GroupNode
        assertEquals(GENERAL_KEY, firstGroup.key)
        assertEquals(listOf("r-1"), idsUnder(root, 0))
    }

    @Test
    fun `a tree of general remarks only has no bucket level`() {
        val root = buildTreeRoot(
            listOf(
                row(path = "", id = "r-1", bucket = "x"),
                row(path = "", id = "r-2", bucket = "y"),
            )
        )

        assertEquals(1, root.childCount)
        val group = (root.getChildAt(0) as DefaultMutableTreeNode).userObject as GroupNode
        assertEquals(GENERAL_KEY, group.key)
        assertEquals(setOf("r-1", "r-2"), idsUnder(root, 0).toSet())
    }

    @Test
    fun `a leaf carries its bucket`() {
        assertEquals("b", remarkNode(row(bucket = "b")).bucket)
    }

    @Test
    fun `a bucket group is a drop target for its own name`() {
        val root = bucketedTree()

        assertEquals(BucketDrop("auth refactor"), bucketDropTarget(child(root, 1)))
    }

    @Test
    fun `the no-bucket group is a drop target that clears the bucket`() {
        val root = bucketedTree()

        assertEquals(BucketDrop(null), bucketDropTarget(child(root, 0)))
    }

    /**
     * A bucket somebody actually named "(no bucket)" is a bucket like any other, and dropping on it
     * must set that name rather than clear. Only the key tells it apart from the group for remarks
     * in no bucket, which is why `bucketDropTarget` reads the key and not the label.
     */
    @Test
    fun `a bucket named like the no-bucket label is a target for that name`() {
        val root = buildTreeRoot(
            listOf(
                row(id = "r-1", bucket = NO_BUCKET_LABEL),
                row(id = "r-2", bucket = null),
            )
        )
        val targets = (0 until root.childCount).map { bucketDropTarget(child(root, it)) }

        assertTrue(BucketDrop(NO_BUCKET_LABEL) in targets)
        assertTrue(BucketDrop(null) in targets)
    }

    @Test
    fun `a file group inside a bucket targets that bucket`() {
        val root = bucketedTree()
        val file = child(child(root, 1), 0)

        assertEquals(BucketDrop("auth refactor"), bucketDropTarget(file))
    }

    @Test
    fun `a file group with no bucket level above it is not a drop target`() {
        val root = buildTreeRoot(listOf(row(id = "r-1", path = "src/Foo.kt")))

        assertEquals(null, bucketDropTarget(child(root, 0)))
    }

    @Test
    fun `the General group is not a drop target`() {
        val root = buildTreeRoot(
            listOf(
                row(id = "r-1", path = ""),
                row(id = "r-2", path = "src/Foo.kt", bucket = "auth refactor"),
            )
        )
        val general = child(root, 0)

        assertEquals(GENERAL_KEY, (general.userObject as GroupNode).key)
        assertEquals(null, bucketDropTarget(general))
        // Its rows are not targets either: a general remark has no bucket to read off it.
        assertEquals(null, bucketDropTarget(child(general, 0)))
    }

    @Test
    fun `a remark row targets the bucket it is in`() {
        val root = bucketedTree()
        val inBucket = child(child(child(root, 1), 0), 0)
        val unbucketed = child(child(child(root, 0), 0), 0)

        assertEquals(BucketDrop("auth refactor"), bucketDropTarget(inBucket))
        assertEquals(BucketDrop(null), bucketDropTarget(unbucketed))
    }

    /** The root is invisible, but a drop can still land below the last row, which reports it. */
    @Test
    fun `the tree root is not a drop target`() {
        assertEquals(null, bucketDropTarget(bucketedTree()))
        assertEquals(null, bucketDropTarget(null))
    }

    // ---- an answer nested under its question ----

    /**
     * The ordinary case, and the whole point of the nesting: the answer is drawn next to the thing
     * it is about, inside that question's own file group, instead of in a flat group at the top.
     */
    @Test
    fun `an answer is a child of the question it answers`() {
        val root = buildTreeRoot(
            listOf(row(id = "r-1", path = "src/Foo.kt")),
            listOf(answerRow(id = "a-1", remarkId = "r-1")),
        )

        assertEquals(1, root.childCount)
        val file = child(root, 0)
        assertEquals("file:src/Foo.kt", (file.userObject as GroupNode).key)
        val question = child(file, 0)
        assertEquals("r-1", (question.userObject as RemarkNode).id)
        assertEquals(1, question.childCount)
        assertEquals("a-1", (child(question, 0).userObject as AnswerNode).id)
    }

    /**
     * The General group nests too, and it is built by its own code rather than by `addFileGroups`,
     * so an answer to a general remark is the one case a file-group test cannot cover.
     */
    @Test
    fun `an answer to a general remark is a child of it in the General group`() {
        val root = buildTreeRoot(
            listOf(row(id = "r-1", path = "")),
            listOf(answerRow(id = "a-1", remarkId = "r-1", path = "")),
        )

        val general = child(root, 0)
        assertEquals(GENERAL_KEY, (general.userObject as GroupNode).key)
        val question = child(general, 0)
        assertEquals(1, question.childCount)
        assertEquals("a-1", (child(question, 0).userObject as AnswerNode).id)
    }

    /**
     * The ordinary tree has one fewer top-level group than it used to: with every answer nested,
     * there is nothing left for the group at the top to hold, and it is not drawn at all.
     */
    @Test
    fun `the group for answers with no question is absent when every answer has one`() {
        val root = buildTreeRoot(
            listOf(row(id = "r-1", path = "src/Foo.kt")),
            listOf(answerRow(id = "a-1", remarkId = "r-1")),
        )

        val keys = (0 until root.childCount)
            .map { ((root.getChildAt(it) as DefaultMutableTreeNode).userObject as GroupNode).key }

        assertEquals(listOf("file:src/Foo.kt"), keys)
    }

    // ---- the group for an answer whose question is gone ----

    /**
     * An answer outlives the question it answers on purpose — `clearHandedOverRemarks` keeps answers
     * and `deleteRemark` does not touch them — so this is an ordinary state and needs a home. Both
     * shapes land here: an answer naming a remark nothing knows about, and one naming nothing at all.
     */
    @Test
    fun `an answer whose question is not in the tree goes to the top-level group`() {
        val root = buildTreeRoot(
            listOf(row(id = "r-1", path = "src/Foo.kt")),
            listOf(answerRow(id = "a-1", remarkId = "r-gone"), answerRow(id = "a-2", remarkId = null)),
        )

        val group = (child(root, 0).userObject as GroupNode)
        assertEquals(ANSWERS_KEY, group.key)
        assertEquals("Answers with no question", group.label)
        assertEquals(setOf("a-1", "a-2"), answerIdsUnder(root, 0).toSet())
        // And the live question keeps no child of its own.
        assertEquals(0, child(child(root, 1), 0).childCount)
    }

    /**
     * Above General, above the buckets, above the files — where the old Answers group sat, and with
     * the same key, so a person who had it collapsed keeps it collapsed across the upgrade.
     */
    @Test
    fun `the group for answers with no question is first, above General`() {
        val root = buildTreeRoot(
            listOf(row(path = "", id = "r-1"), row(path = "src/Foo.kt", id = "r-2")),
            listOf(answerRow(id = "a-1", remarkId = "r-gone")),
        )

        val keys = (0 until root.childCount)
            .map { ((root.getChildAt(it) as DefaultMutableTreeNode).userObject as GroupNode).key }

        assertEquals(listOf(ANSWERS_KEY, GENERAL_KEY, "file:src/Foo.kt"), keys)
    }

    /** Nobody who has never received an answer gets an empty group wrapped around their tree. */
    @Test
    fun `the Answers group appears only when an answer exists`() {
        val root = buildTreeRoot(listOf(row(id = "r-1")), emptyList())

        assertEquals(1, root.childCount)
        assertEquals("file:src/Foo.kt", (child(root, 0).userObject as GroupNode).key)
    }

    /**
     * Newest first, which is a different order from every other group in the tree. Deliberate: of
     * the answers with nothing left to sit under, the one that just arrived is the one to read. A
     * nested answer gets no order of its own — it sits where its question sits.
     */
    @Test
    fun `the rows in that group are sorted newest first`() {
        val root = buildTreeRoot(
            emptyList(),
            listOf(
                answerRow(id = "a-old", answeredAt = 100L),
                answerRow(id = "a-new", answeredAt = 300L),
                answerRow(id = "a-middle", answeredAt = 200L),
            ),
        )

        assertEquals(listOf("a-new", "a-middle", "a-old"), answerIdsUnder(root, 0))
    }

    /**
     * A remark with no id gets no node at all, so an answer naming it has no parent to attach to and
     * must still be drawn. Build the id set from the whole stored list rather than from the filtered
     * one and this answer matches an id that never became a node: it attaches to nothing, and the row
     * disappears from the tree with nothing failing anywhere.
     */
    @Test
    fun `an answer naming a remark that got no node is still drawn`() {
        val root = buildTreeRoot(
            listOf(row(id = null, path = "src/Foo.kt")),
            listOf(answerRow(id = "a-1", remarkId = "")),
        )

        assertEquals(1, root.childCount)
        assertEquals(ANSWERS_KEY, (child(root, 0).userObject as GroupNode).key)
        assertEquals(listOf("a-1"), answerIdsUnder(root, 0))
    }

    /**
     * A nested row sits inside its question's file group, so the grey file name at its end would be
     * a third copy of something already on screen. A row in the top-level group has no file group
     * above it and keeps it. The position stays on both: an answer carries its own anchor and can
     * drift away from the line its question resolved to.
     */
    @Test
    fun `a nested answer row draws no file name and a top-level one draws its own`() {
        val root = buildTreeRoot(
            listOf(row(id = "r-1", path = "src/Foo.kt")),
            listOf(answerRow(id = "a-1", remarkId = "r-1"), answerRow(id = "a-2", remarkId = "r-gone")),
        )

        val topLevel = child(child(root, 0), 0).userObject as AnswerNode
        val nested = child(child(child(root, 1), 0), 0).userObject as AnswerNode

        assertEquals("Foo.kt", topLevel.fileName)
        assertEquals("", nested.fileName)
        assertEquals("5-7", nested.position)
    }

    @Test
    fun `an answer row carries the answer's first line, its position and its file name`() {
        val node = answerNode(
            answerRow(markdown = "because two threads write it\n\nand the second one wins")
        )

        assertEquals("5-7", node.position)
        assertEquals("because two threads write it", node.firstLine)
        assertEquals("Foo.kt", node.fileName)
    }

    /** A leading blank line, or a body that opens with a heading, both read well this way. */
    @Test
    fun `an answer row's first line skips leading blank lines`() {
        assertEquals("# The short answer", answerNode(answerRow(markdown = "\n\n# The short answer\nmore")).firstLine)
    }

    /**
     * An answer to a general remark has no file, so there is no position to print and no file name
     * to print beside it — the same way `isAboutNoFile` already handles the remark itself.
     */
    @Test
    fun `an answer to a general remark has no position and no file name`() {
        val node = answerNode(answerRow(path = "", result = AnchorResult.Exact(0, 0)))

        assertEquals("", node.position)
        assertEquals("", node.fileName)
    }

    @Test
    fun `an orphaned answer says so, the same way a remark row does`() {
        assertEquals("5-7 (orphaned)", answerNode(answerRow(result = AnchorResult.Orphaned(4, 6))).position)
    }

    /**
     * Somebody who has never used a bucket must still get root then file then remark, with the
     * Answers group added on top and no bucket level appearing from nowhere.
     */
    @Test
    fun `a tree with answers and no buckets still has no bucket level`() {
        val root = buildTreeRoot(
            listOf(row(id = "r-1", path = "src/Foo.kt")),
            listOf(answerRow(id = "a-1", remarkId = "r-gone")),
        )

        assertEquals(2, root.childCount)
        val file = child(root, 1)
        assertEquals("Foo.kt", (file.userObject as GroupNode).label)
        assertTrue((file.getChildAt(0) as DefaultMutableTreeNode).userObject is RemarkNode)
    }

    /** An answer row is an AnswerNode, so Publish Selected and the toggle take nothing from it. */
    @Test
    fun `selecting the Answers group gives no remark rows and every answer row`() {
        val root = buildTreeRoot(
            listOf(row(id = "r-1")),
            listOf(answerRow(id = "a-1", remarkId = "r-gone"), answerRow(id = "a-2", remarkId = "r-gone")),
        )
        val group = listOf(child(root, 0))

        assertEquals(emptyList<RemarkNode>(), remarkNodesUnder(group))
        assertEquals(listOf("a-1", "a-2"), answerNodesUnder(group).map { it.id })
    }

    @Test
    fun `the Answers group is not a drop target`() {
        val root = buildTreeRoot(
            listOf(row(id = "r-1", path = "src/Foo.kt", bucket = "auth refactor")),
            listOf(answerRow(id = "a-1", remarkId = "r-gone")),
        )
        val answers = child(root, 0)

        assertEquals(ANSWERS_KEY, (answers.userObject as GroupNode).key)
        assertEquals(null, bucketDropTarget(answers))
        assertEquals(null, bucketDropTarget(child(answers, 0)))
    }

    // ---- the asks / answered word on a remark row ----

    @Test
    fun `a marked remark's row says asks with no answer and answered with one`() {
        val root = buildTreeRoot(
            listOf(row(id = "r-1", asksForAnswer = true), row(id = "r-2", asksForAnswer = true)),
            listOf(answerRow(id = "a-1", remarkId = "r-2")),
        )
        val rows = (0 until root.childCount).flatMap { group ->
            val node = child(root, group)
            (0 until node.childCount).mapNotNull { (node.getChildAt(it) as DefaultMutableTreeNode).userObject as? RemarkNode }
        }.associateBy { it.id }

        assertEquals("asks", asksLabel(rows.getValue("r-1")))
        assertEquals("answered", asksLabel(rows.getValue("r-2")))
    }

    /** An unmarked remark says nothing, whether or not something answered it anyway. */
    @Test
    fun `an unmarked remark's row says neither word`() {
        assertNull(asksLabel(remarkNode(row(asksForAnswer = false))))
        assertNull(asksLabel(remarkNode(row(asksForAnswer = false), hasAnswer = true)))
    }

    /** Two top-level groups: "(no bucket)" first, then "auth refactor". */
    private fun bucketedTree() = buildTreeRoot(
        listOf(
            row(id = "r-1", path = "src/Foo.kt", bucket = "auth refactor"),
            row(id = "r-2", path = "src/Bar.kt", bucket = null),
        )
    )

    private fun child(node: DefaultMutableTreeNode, index: Int) =
        node.getChildAt(index) as DefaultMutableTreeNode

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

    private fun answerIdsUnder(root: DefaultMutableTreeNode, index: Int): List<String> {
        val group = root.getChildAt(index) as DefaultMutableTreeNode
        return (0 until group.childCount).map {
            ((group.getChildAt(it) as DefaultMutableTreeNode).userObject as AnswerNode).id
        }
    }

    private fun row(
        path: String = "src/Foo.kt",
        id: String? = "r-1",
        text: String = "why?",
        status: RemarkStatus = RemarkStatus.PENDING,
        result: AnchorResult = AnchorResult.Exact(4, 6),
        bucket: String? = null,
        commit: String? = null,
        startColumn: Int = 0,
        endColumn: Int = 0,
        asksForAnswer: Boolean = false,
    ) = ResolvedRemark(
        RemarkState().also {
            it.id = id
            it.path = path
            it.startLine = 4
            it.endLine = 6
            it.text = text
            it.status = status
            it.bucket = bucket
            it.commit = commit
            it.asksForAnswer = asksForAnswer
        },
        result,
        startColumn,
        endColumn,
    )

    /** The same builder shape as [row], for the answers half of a rebuild. */
    private fun answerRow(
        id: String = "a-1",
        remarkId: String? = "r-1",
        path: String = "src/Foo.kt",
        markdown: String = "because two threads write it",
        answeredAt: Long = 0L,
        result: AnchorResult = AnchorResult.Exact(4, 6),
        commit: String? = null,
        startColumn: Int = 0,
        endColumn: Int = 0,
    ) = ResolvedAnswer(
        AnswerState().also {
            it.id = id
            it.remarkId = remarkId
            it.question = "why is this synchronized?"
            it.markdown = markdown
            it.answeredAt = answeredAt
            it.path = path
            it.startLine = 4
            it.endLine = 6
            it.commit = commit
        },
        result,
        startColumn,
        endColumn,
    )
}
