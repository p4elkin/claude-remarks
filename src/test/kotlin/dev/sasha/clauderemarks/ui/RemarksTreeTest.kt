package dev.sasha.clauderemarks.ui

import dev.sasha.clauderemarks.anchor.AnchorResult
import dev.sasha.clauderemarks.model.AnswerState
import dev.sasha.clauderemarks.model.RemarkState
import dev.sasha.clauderemarks.model.RemarkStatus
import dev.sasha.clauderemarks.store.ResolvedAnswer
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

        val side = openSide(root)
        assertEquals(listOf("Alpha.kt", "Zed.kt"), fileNames(side))
        assertEquals(listOf("r-2"), idsUnder(side, 0))
        assertEquals(listOf("r-1", "r-3"), idsUnder(side, 1))
    }

    /**
     * Oldest first, so a remark written now lands at the bottom of its file group and nothing above
     * it moves. The ids are named so that alphabetical order and line order both DISAGREE with the
     * expected order: sorting by either would pass this test without ever reading `createdAt`.
     */
    @Test
    fun `rows inside a file in Open are ordered oldest first`() {
        val root = buildTreeRoot(
            listOf(
                row(id = "b-second", createdAt = 200L, result = AnchorResult.Exact(3, 3)),
                row(id = "a-first", createdAt = 100L, result = AnchorResult.Exact(20, 20)),
            )
        )

        assertEquals(listOf("a-first", "b-second"), idsUnder(openSide(root), 0))
    }

    /**
     * The tie-break, and what keeps a whole existing store in a sensible order: two remarks written
     * in the same millisecond — and every remark stored before `createdAt` was ever stamped, which
     * all carry 0 — fall back to the line they resolved to.
     *
     * The ids are named so that alphabetical order DISAGREES with line order. With ids "later" and
     * "earlier" the two orders happen to agree, and sorting by id would pass this test.
     */
    @Test
    fun `rows created at the same moment fall back to the line they resolved to`() {
        val root = buildTreeRoot(
            listOf(
                row(id = "a-later", result = AnchorResult.Exact(20, 20)),
                row(id = "b-earlier", result = AnchorResult.Exact(3, 3)),
            )
        )

        assertEquals(listOf("b-earlier", "a-later"), idsUnder(openSide(root), 0))
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

    /**
     * Shift+Enter in the input popup makes a multi-line remark ordinary. The node used to flatten
     * every newline to a space, because one SimpleColoredComponent cannot draw a break; a row is a
     * stack of them since phase 13, and `wrapToLines` splits on '\n' itself, so the breaks the person
     * typed have to reach the renderer intact.
     */
    @Test
    fun `a multi-line remark keeps its own line breaks`() {
        assertEquals("first line\nsecond line", remarkNode(row(text = "first line\nsecond line")).text)
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

        val file = child(openSide(root), 0)
        val ids = remarkNodesUnder(listOf(file)).map { it.id }

        assertEquals(listOf("b-earlier", "a-later"), ids)
    }

    @Test
    fun `a file and one of its own rows selected together do not count that row twice`() {
        val root = buildTreeRoot(listOf(row(id = "r-1"), row(id = "r-2")))
        val file = child(openSide(root), 0)
        val firstRow = child(file, 0)

        assertEquals(2, remarkNodesUnder(listOf(file, firstRow)).size)
    }

    @Test
    fun `a remark about a file sits directly under a file group, inside its side`() {
        val root = buildTreeRoot(listOf(row(path = "src/Foo.kt", id = "r-1")))

        assertEquals(1, root.childCount)
        val file = child(openSide(root), 0)
        assertEquals("Foo.kt", (file.userObject as GroupNode).label)
        assertTrue(child(file, 0).userObject is RemarkNode)
    }

    @Test
    fun `a file group's label is the file name and its detail is the directory`() {
        val root = buildTreeRoot(listOf(row(path = "src/main/Foo.kt", id = "r-1")))

        val group = child(openSide(root), 0).userObject as GroupNode
        assertEquals("Foo.kt", group.label)
        assertEquals("src/main", group.detail)
    }

    @Test
    fun `a file in the project root has no detail`() {
        val root = buildTreeRoot(listOf(row(path = "Foo.kt", id = "r-1")))

        val group = child(openSide(root), 0).userObject as GroupNode
        assertEquals("Foo.kt", group.label)
        assertEquals(null, group.detail)
    }

    @Test
    fun `a deep path's detail is shortened to the last two segments with an ellipsis`() {
        val root = buildTreeRoot(
            listOf(row(path = "src/main/kotlin/dev/sasha/clauderemarks/ui/Foo.kt", id = "r-1"))
        )

        val group = child(openSide(root), 0).userObject as GroupNode
        assertEquals("Foo.kt", group.label)
        assertEquals("…/clauderemarks/ui", group.detail)
    }

    /**
     * The label shows the file name only, and the key is its side plus the whole path — that is
     * what lets `RemarksPanel`'s selection restore, which matches groups by key, keep working.
     */
    @Test
    fun `a file group's key carries its side and the whole path`() {
        val root = buildTreeRoot(listOf(row(path = "src/main/Foo.kt", id = "r-1")))

        val group = child(openSide(root), 0).userObject as GroupNode
        assertEquals("open/file:src/main/Foo.kt", group.key)
    }

    @Test
    fun `a general remark is in a group keyed general, placed first inside its side`() {
        val root = buildTreeRoot(
            listOf(
                row(path = "src/Foo.kt", id = "r-1"),
                row(path = "", id = "r-2"),
            )
        )

        val side = openSide(root)
        val group = child(side, 0).userObject as GroupNode
        assertEquals("open/$GENERAL_KEY", group.key)
        assertEquals("General", group.label)
        assertEquals(listOf("r-2"), idsUnder(side, 0))
    }

    // ---- Open and Done ----

    @Test
    fun `a read remark is under Done and a pending or published one is under Open`() {
        val root = buildTreeRoot(
            listOf(
                row(id = "r-pending", status = RemarkStatus.PENDING),
                row(id = "r-published", status = RemarkStatus.PUBLISHED),
                row(id = "r-read", status = RemarkStatus.READ),
            )
        )

        assertEquals(listOf("r-pending", "r-published"), idsUnder(openSide(root), 0))
        assertEquals(listOf("r-read"), idsUnder(doneSide(root), 0))
    }

    /**
     * ⚠️ An answer is enough on its own: a question that nothing has acknowledged is still processed
     * once the answer lands, and it leaves Open at that moment. Decided knowing the cost, and the
     * nesting below is half of what pays for it — the answer is still there to read, under the
     * question, one expand away.
     */
    @Test
    fun `an answered question is under Done with its answer still nested`() {
        val root = buildTreeRoot(
            listOf(row(id = "r-1", path = "src/Foo.kt", status = RemarkStatus.PENDING)),
            listOf(answerRow(id = "a-1", remarkId = "r-1")),
        )

        val question = child(child(doneSide(root), 0), 0)
        assertEquals("r-1", (question.userObject as RemarkNode).id)
        assertEquals(1, question.childCount)
        assertEquals("a-1", (child(question, 0).userObject as AnswerNode).id)
    }

    /** Newest processed first, so what an agent just picked up sits at the top of Done. */
    @Test
    fun `rows inside a file in Done are ordered newest processed first`() {
        val root = buildTreeRoot(
            listOf(
                row(id = "a-read-first", status = RemarkStatus.READ, createdAt = 900L, readAt = 100L),
                row(id = "b-read-later", status = RemarkStatus.READ, createdAt = 100L, readAt = 300L),
            )
        )

        assertEquals(listOf("b-read-later", "a-read-first"), idsUnder(doneSide(root), 0))
    }

    /**
     * ⚠️ Every remark read before `readAt` existed carries 0. Without the fallback to `createdAt`
     * the whole backlog would sort as one lump at the epoch, in whatever order the store handed it
     * over.
     */
    @Test
    fun `a Done row with no readAt falls back to when it was written`() {
        val root = buildTreeRoot(
            listOf(
                row(id = "a-written-first", status = RemarkStatus.READ, createdAt = 100L, readAt = 0L),
                row(id = "b-written-later", status = RemarkStatus.READ, createdAt = 200L, readAt = 0L),
            )
        )

        assertEquals(listOf("b-written-later", "a-written-first"), idsUnder(doneSide(root), 0))
    }

    @Test
    fun `processedAt is the read time when there is one and the written time when there is not`() {
        assertEquals(300L, remarkNode(row(createdAt = 100L, readAt = 300L)).processedAt)
        assertEquals(100L, remarkNode(row(createdAt = 100L, readAt = 0L)).processedAt)
    }

    /** An empty heading above another empty heading would be two rows saying nothing. */
    @Test
    fun `a side with nothing on it is not drawn at all`() {
        assertEquals(listOf(OPEN_KEY), keysUnder(buildTreeRoot(listOf(row(id = "r-1")))))
        assertEquals(
            listOf(DONE_KEY),
            keysUnder(buildTreeRoot(listOf(row(id = "r-1", status = RemarkStatus.READ)))),
        )
    }

    @Test
    fun `Open is above Done`() {
        val root = buildTreeRoot(
            listOf(
                row(id = "r-read", status = RemarkStatus.READ),
                row(id = "r-open", status = RemarkStatus.PENDING),
            )
        )

        assertEquals(listOf(OPEN_KEY, DONE_KEY), keysUnder(root))
    }

    /**
     * ⚠️ One file can hold an open remark and a processed one at the same time, and then it gets a
     * group on each side. `RemarksPanel` matches groups by key alone, so two groups sharing a key
     * would collapse together and — worse — select together, and a selected group is what Delete
     * acts on.
     */
    @Test
    fun `the same file on both sides gets its own key per side`() {
        val root = buildTreeRoot(
            listOf(
                row(id = "r-open", path = "src/Foo.kt"),
                row(id = "r-done", path = "src/Foo.kt", status = RemarkStatus.READ),
            )
        )

        assertEquals(listOf("open/file:src/Foo.kt"), keysUnder(openSide(root)))
        assertEquals(listOf("done/file:src/Foo.kt"), keysUnder(doneSide(root)))
    }

    /** The General group splits the same way, and its key carries its side for the same reason. */
    @Test
    fun `a general remark on each side gets a General group on each side`() {
        val root = buildTreeRoot(
            listOf(
                row(id = "r-open", path = ""),
                row(id = "r-done", path = "", status = RemarkStatus.READ),
            )
        )

        assertEquals(listOf("open/general"), keysUnder(openSide(root)))
        assertEquals(listOf("done/general"), keysUnder(doneSide(root)))
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
        val file = child(doneSide(root), 0)
        assertEquals("done/file:src/Foo.kt", (file.userObject as GroupNode).key)
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

        val general = child(doneSide(root), 0)
        assertEquals("done/$GENERAL_KEY", (general.userObject as GroupNode).key)
        val question = child(general, 0)
        assertEquals(1, question.childCount)
        assertEquals("a-1", (child(question, 0).userObject as AnswerNode).id)
    }

    /**
     * `recordAnswer` upserts on the remark id, so the store cannot hold two answers to one question
     * and this is defensive. Newest first, the order the top-level group already uses: if two ever
     * do appear, the one that just came back is the one to read.
     */
    @Test
    fun `two answers to one question sort newest first under it`() {
        val root = buildTreeRoot(
            listOf(row(id = "r-1", path = "src/Foo.kt")),
            listOf(
                answerRow(id = "a-old", remarkId = "r-1", answeredAt = 100L),
                answerRow(id = "a-new", remarkId = "r-1", answeredAt = 300L),
            ),
        )

        val question = child(child(doneSide(root), 0), 0)

        assertEquals(listOf("a-new", "a-old"), answerIdsIn(question))
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

        assertEquals(listOf(DONE_KEY), keysUnder(root))
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

        val group = child(root, 0).userObject as GroupNode
        assertEquals(ANSWERS_KEY, group.key)
        assertEquals("Answers with no question", group.label)
        assertEquals(setOf("a-1", "a-2"), answerIdsIn(child(root, 0)).toSet())
        // And the live question keeps no child of its own.
        assertEquals(0, child(child(openSide(root), 0), 0).childCount)
    }

    /**
     * Above Open, above the files — where the old Answers group sat, and with the same key, so a
     * person who had it collapsed keeps it collapsed across the upgrade.
     */
    @Test
    fun `the group for answers with no question is first, above Open`() {
        val root = buildTreeRoot(
            listOf(row(path = "", id = "r-1"), row(path = "src/Foo.kt", id = "r-2")),
            listOf(answerRow(id = "a-1", remarkId = "r-gone")),
        )

        assertEquals(listOf(ANSWERS_KEY, OPEN_KEY), keysUnder(root))
        assertEquals(listOf("open/$GENERAL_KEY", "open/file:src/Foo.kt"), keysUnder(openSide(root)))
    }

    /**
     * ⚠️ It is not folded into Done either, however processed the answers in it look. An answer with
     * no question left is a loose end, and burying it under a group that starts shut is how a loose
     * end goes unnoticed.
     */
    @Test
    fun `the group for answers with no question stays above Done, not inside it`() {
        val root = buildTreeRoot(
            listOf(row(id = "r-1", path = "src/Foo.kt", status = RemarkStatus.READ)),
            listOf(answerRow(id = "a-1", remarkId = "r-gone")),
        )

        assertEquals(listOf(ANSWERS_KEY, DONE_KEY), keysUnder(root))
        assertEquals(listOf("a-1"), answerIdsIn(child(root, 0)))
    }

    /** Nobody who has never received an answer gets an empty group wrapped around their tree. */
    @Test
    fun `the Answers group appears only when an answer exists`() {
        val root = buildTreeRoot(listOf(row(id = "r-1")), emptyList())

        assertEquals(listOf(OPEN_KEY), keysUnder(root))
        assertEquals(listOf("open/file:src/Foo.kt"), keysUnder(openSide(root)))
    }

    /**
     * Newest first, which is a different order from Open. Deliberate: of the answers with nothing
     * left to sit under, the one that just arrived is the one to read. A nested answer gets no order
     * of its own beyond its question's — it sits where its question sits.
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

        assertEquals(listOf("a-new", "a-middle", "a-old"), answerIdsIn(child(root, 0)))
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

        assertEquals(listOf(ANSWERS_KEY), keysUnder(root))
        assertEquals(listOf("a-1"), answerIdsIn(child(root, 0)))
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
        val question = child(child(doneSide(root), 0), 0)
        val nested = child(question, 0).userObject as AnswerNode

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

    /** The Answers group sits above the side-then-file-then-remark structure, not nested inside it. */
    @Test
    fun `a tree with an unmatched answer still has the Answers group above the file group`() {
        val root = buildTreeRoot(
            listOf(row(id = "r-1", path = "src/Foo.kt")),
            listOf(answerRow(id = "a-1", remarkId = "r-gone")),
        )

        assertEquals(2, root.childCount)
        val file = child(openSide(root), 0)
        assertEquals("Foo.kt", (file.userObject as GroupNode).label)
        assertTrue(child(file, 0).userObject is RemarkNode)
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

    /**
     * `leavesOf` used to stop at a RemarkNode. Since an answer nests under its question, that would
     * have left a selected question's answer alive after Delete took the question — a delete that
     * makes a different row (the now-orphaned answer, in the no-question group) appear rather than
     * just removing what was selected.
     */
    @Test
    fun `selecting a question also selects the answer nested under it`() {
        val root = buildTreeRoot(
            listOf(row(id = "r-1", path = "src/Foo.kt")),
            listOf(answerRow(id = "a-1", remarkId = "r-1")),
        )
        val question = child(child(doneSide(root), 0), 0)

        assertEquals(listOf("a-1"), answerNodesUnder(listOf(question)).map { it.id })
    }

    /**
     * `hasAnswer` on a `RemarkNode` is the second of the two facts `RemarkStatusLook.icon` reads, and
     * it is the one `buildTreeRoot` computes rather than reads off the stored remark. Nothing else in
     * the suite pushes a **true** `hasAnswer` through this function: the two tests that once did were
     * the `asksLabel` pair, deleted when the grey word went. So derive the answered ids from the wrong
     * list — the loose answers rather than the nested ones — and every question row keeps the yellow
     * question mark for ever with the whole suite still green.
     */
    @Test
    fun `a question with an answer nested under it carries hasAnswer`() {
        val root = buildTreeRoot(
            listOf(row(id = "r-1", path = "src/Foo.kt", asksForAnswer = true)),
            listOf(answerRow(id = "a-1", remarkId = "r-1")),
        )

        val question = child(child(doneSide(root), 0), 0).userObject as RemarkNode

        assertTrue(question.hasAnswer)
    }

    /**
     * The other direction, so a set that is always full fails as loudly as one that is always empty.
     * The stored answer here names a remark that is not in the tree, so it draws its own top-level
     * row and must leave this question's icon alone — and must leave the question in Open.
     */
    @Test
    fun `a question whose answer belongs to another remark carries hasAnswer false`() {
        val root = buildTreeRoot(
            listOf(row(id = "r-1", path = "src/Foo.kt", asksForAnswer = true)),
            listOf(answerRow(id = "a-1", remarkId = "r-other")),
        )

        val question = child(child(openSide(root), 0), 0).userObject as RemarkNode

        assertFalse(question.hasAnswer)
    }

    /** The other half: selecting the question still gives exactly its own remark, not two copies. */
    @Test
    fun `selecting a question still gives only its own remark from remarkNodesUnder`() {
        val root = buildTreeRoot(
            listOf(row(id = "r-1", path = "src/Foo.kt")),
            listOf(answerRow(id = "a-1", remarkId = "r-1")),
        )
        val question = child(child(doneSide(root), 0), 0)

        assertEquals(listOf("r-1"), remarkNodesUnder(listOf(question)).map { it.id })
    }

    private fun child(node: DefaultMutableTreeNode, index: Int) =
        node.getChildAt(index) as DefaultMutableTreeNode

    /** The Open or Done group by key, so a test never has to know which index its side landed at. */
    private fun side(root: DefaultMutableTreeNode, key: String): DefaultMutableTreeNode =
        (0 until root.childCount)
            .map { child(root, it) }
            .single { (it.userObject as GroupNode).key == key }

    private fun openSide(root: DefaultMutableTreeNode) = side(root, OPEN_KEY)

    private fun doneSide(root: DefaultMutableTreeNode) = side(root, DONE_KEY)

    private fun keysUnder(node: DefaultMutableTreeNode): List<String> =
        (0 until node.childCount).map { (child(node, it).userObject as GroupNode).key }

    private fun fileNames(side: DefaultMutableTreeNode) =
        (0 until side.childCount).map { (child(side, it).userObject as GroupNode).label }

    private fun idsUnder(side: DefaultMutableTreeNode, index: Int): List<String> {
        val file = child(side, index)
        return (0 until file.childCount).map { (child(file, it).userObject as RemarkNode).id }
    }

    private fun answerIdsIn(parent: DefaultMutableTreeNode): List<String> =
        (0 until parent.childCount).map { (child(parent, it).userObject as AnswerNode).id }

    private fun row(
        path: String = "src/Foo.kt",
        id: String? = "r-1",
        text: String = "why?",
        status: RemarkStatus = RemarkStatus.PENDING,
        result: AnchorResult = AnchorResult.Exact(4, 6),
        commit: String? = null,
        startColumn: Int = 0,
        endColumn: Int = 0,
        asksForAnswer: Boolean = false,
        createdAt: Long = 0L,
        readAt: Long = 0L,
    ) = ResolvedRemark(
        RemarkState().also {
            it.id = id
            it.path = path
            it.startLine = 4
            it.endLine = 6
            it.text = text
            it.status = status
            it.commit = commit
            it.asksForAnswer = asksForAnswer
            it.createdAt = createdAt
            it.readAt = readAt
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
