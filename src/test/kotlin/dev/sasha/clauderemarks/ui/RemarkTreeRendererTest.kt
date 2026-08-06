package dev.sasha.clauderemarks.ui

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.ui.SimpleColoredComponent
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.treeStructure.Tree
import com.intellij.util.ui.UIUtil
import dev.sasha.clauderemarks.model.RemarkStatus
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel

/**
 * What one row actually draws.
 *
 * Fixture-backed rather than plain JUnit, unlike `RemarksTreeTest` beside it: every assertion here
 * goes through a real `SimpleColoredComponent`, a real `Tree` and `UIUtil`'s theme colours, none of
 * which exist without an application. ⚠️ That also means JUnit 3 discovery, so every test method is
 * named `testSomething` — a backticked name with an `@Test` on it is silently never run here.
 *
 * ⚠️ **Painting is not what is tested here, and cannot be.** Whether text visibly wraps, how a
 * variable-height row looks, and whether the selection band really covers all three lines are hand
 * checks in the phase 13 plan. What a test can pin is which line components came back visible, what
 * text and attributes each of them carries, where the icon landed, and which background the panel was
 * left holding — and every one of those is a way the row could go wrong silently.
 */
class RemarkTreeRendererTest : BasePlatformTestCase() {

    fun testAShortRemarkDrawsOneLine() {
        val renderer = render(remarkRow(text = "why is this synchronized?"))

        assertEquals(1, visibleLines(renderer).size)
        assertTrue(textOf(renderer.lines[0]).contains("why is this synchronized?"))
    }

    /**
     * The cap is [MAX_TEXT_LINES] and the fourth line is elided, not clipped: a row that simply
     * stopped mid-sentence would look like the remark itself ended there.
     */
    fun testARemarkTooLongForThreeLinesFillsThreeAndEndsWithAnEllipsis() {
        val renderer = render(remarkRow(text = longText()), treeWidth = 220)

        assertEquals(MAX_TEXT_LINES, visibleLines(renderer).size)
        assertTrue(textOf(renderer.lines[MAX_TEXT_LINES - 1]).endsWith("…"))
    }

    /**
     * The end-to-end half of `RemarksTreeTest`'s "a multi-line remark keeps its own line breaks":
     * that one pins the node, this one pins that the renderer really does draw the two lines apart.
     */
    fun testARemarkWrittenAcrossTwoLinesDrawsTwo() {
        val renderer = render(remarkRow(text = "first line\nsecond line"))

        assertEquals(2, visibleLines(renderer).size)
        assertTrue(textOf(renderer.lines[0]).contains("first line"))
        assertEquals("second line", textOf(renderer.lines[1]))
    }

    /** Otherwise every wrapped line would draw a second copy of the same icon down the row. */
    fun testOnlyTheFirstLineOfAWrappedRowCarriesTheIcon() {
        val renderer = render(remarkRow(text = longText()), treeWidth = 220)

        assertNotNull(renderer.lines[0].icon)
        assertNull(renderer.lines[1].icon)
        assertNull(renderer.lines[2].icon)
    }

    /** A row that needs fewer lines must leave no blank ones behind from the row rendered before it. */
    fun testTheLinesALongRowUsedAreHiddenAgainForAShortOne() {
        val renderer = RemarkTreeRenderer()
        renderInto(renderer, remarkRow(text = longText()), treeWidth = 220)
        renderInto(renderer, remarkRow(text = "short"), treeWidth = 220)

        assertEquals(1, visibleLines(renderer).size)
        assertEquals("", textOf(renderer.lines[1]))
    }

    fun testARemarkRowDrawsItsPositionInGreyAndItsTextInTheStatusAttributes() {
        val renderer = render(remarkRow(text = "why?", position = "4-6"))
        val drawn = fragments(renderer.lines[0])

        assertEquals(listOf("4-6  ", "why?"), drawn.map { it.first })
        assertSame(SimpleTextAttributes.GRAYED_ATTRIBUTES, drawn[0].second)
        assertSame(RemarkStatusLook.textAttributes(RemarkStatus.PENDING), drawn[1].second)
    }

    /** A read remark greys out whole, the same rule the one-line renderer followed. */
    fun testAReadRemarkDrawsItsTextInTheGreyStatusAttributes() {
        val renderer = render(remarkRow(text = "why?", status = RemarkStatus.READ))

        assertSame(
            RemarkStatusLook.textAttributes(RemarkStatus.READ),
            fragments(renderer.lines[0]).last().second,
        )
    }

    /** A general remark has no position, and must not draw the two spaces that would separate one. */
    fun testARemarkWithNoPositionDrawsNoGreyPrefixAtAll() {
        val renderer = render(remarkRow(text = "the whole change reads well", position = ""))

        assertEquals(
            listOf("the whole change reads well"),
            fragments(renderer.lines[0]).map { it.first },
        )
    }

    fun testAnAnswerRowKeepsItsPositionInFrontAndItsFileNameAfterTheText() {
        val renderer = render(
            AnswerNode(
                id = "a-1",
                path = "src/Foo.kt",
                startLine = 3,
                position = "4-6",
                fileName = "Foo.kt",
                question = "why?",
                firstLine = "because two threads write it",
                markdown = "because two threads write it",
                answeredAt = 0L,
            )
        )
        val drawn = fragments(renderer.lines[0])

        assertEquals(listOf("4-6  ", "because two threads write it", "  Foo.kt"), drawn.map { it.first })
        assertSame(SimpleTextAttributes.GRAYED_ATTRIBUTES, drawn[0].second)
        assertSame(SimpleTextAttributes.REGULAR_ATTRIBUTES, drawn[1].second)
        assertSame(SimpleTextAttributes.GRAYED_ATTRIBUTES, drawn[2].second)
    }

    fun testAGroupRowDrawsItsLabelBoldAndItsDirectoryGreyOnOneLine() {
        val renderer = render(GroupNode("open/file:src/a/b/Foo.kt", "Foo.kt", "…/a/b"))
        val drawn = fragments(renderer.lines[0])

        assertEquals(1, visibleLines(renderer).size)
        assertEquals(listOf("Foo.kt", "  …/a/b"), drawn.map { it.first })
        assertSame(SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES, drawn[0].second)
        assertSame(SimpleTextAttributes.GRAYED_ATTRIBUTES, drawn[1].second)
    }

    /** A side heading carries no directory, so nothing grey may be appended after it. */
    fun testAGroupRowWithNoDetailDrawsTheLabelAlone() {
        val renderer = render(GroupNode(OPEN_KEY, OPEN_LABEL))

        assertEquals(listOf(OPEN_LABEL), fragments(renderer.lines[0]).map { it.first })
    }

    /**
     * The one thing `ColoredTreeCellRenderer` used to give for free. A plain JPanel paints nothing,
     * so without this a selected row would look unselected — and the selection is what Publish
     * Selected, Delete and the whole right-click menu act on.
     */
    fun testASelectedRowIsOpaqueAndHoldsTheTreesSelectionBackground() {
        val renderer = render(remarkRow(text = "why?"), selected = true)

        assertTrue(renderer.isOpaque)
        assertEquals(UIUtil.getTreeSelectionBackground(false), renderer.background)
    }

    fun testAnUnselectedRowIsNotOpaqueAndPaintsNothingOfItsOwn() {
        val renderer = render(remarkRow(text = "why?"), selected = false)

        assertFalse(renderer.isOpaque)
        assertEquals(UIUtil.getTreeBackground(), renderer.background)
    }

    /** Long enough that no plausible font fits it into three lines of a 220-pixel row. */
    private fun longText() = (1..80).joinToString(" ") { "word$it" }

    private fun remarkRow(
        text: String,
        position: String = "4-6",
        status: RemarkStatus = RemarkStatus.PENDING,
    ) = RemarkNode(
        id = "r-1",
        path = "src/Foo.kt",
        position = position,
        text = text,
        status = status,
        startLine = 3,
    )

    private fun render(user: Any, treeWidth: Int = 600, selected: Boolean = false): RemarkTreeRenderer {
        val renderer = RemarkTreeRenderer()
        renderInto(renderer, user, treeWidth, selected)
        return renderer
    }

    /**
     * The node is built without a parent, so its level is 0 and the wrap width owes no indent —
     * which is what keeps the "three lines at 220 pixels" assertion arithmetic and not guesswork.
     */
    private fun renderInto(
        renderer: RemarkTreeRenderer,
        user: Any,
        treeWidth: Int = 600,
        selected: Boolean = false,
    ) {
        val node = DefaultMutableTreeNode(user)
        val tree = Tree(DefaultTreeModel(node))
        tree.setSize(treeWidth, 200)
        renderer.getTreeCellRendererComponent(tree, node, selected, false, true, 0, false)
    }

    private fun visibleLines(renderer: RemarkTreeRenderer) = renderer.lines.filter { it.isVisible }

    private fun textOf(line: SimpleColoredComponent) = line.getCharSequence(false).toString()

    private fun fragments(line: SimpleColoredComponent): List<Pair<String, SimpleTextAttributes>> {
        val drawn = mutableListOf<Pair<String, SimpleTextAttributes>>()
        val walk = line.iterator()
        while (walk.hasNext()) {
            walk.next()
            drawn += walk.fragment to walk.textAttributes
        }
        return drawn
    }
}
