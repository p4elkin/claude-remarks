package dev.sasha.clauderemarks.ui

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.ui.SimpleColoredComponent
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.treeStructure.Tree
import com.intellij.util.ui.UIUtil
import dev.sasha.clauderemarks.model.RemarkStatus
import javax.swing.UIManager
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

    private var forcedForegroundBefore: Any? = null

    override fun setUp() {
        super.setUp()
        forcedForegroundBefore = UIManager.get(FORCE_SELECTION_FOREGROUND)
    }

    /**
     * ⚠️ The theme key is put back by hand. `UIManager`'s defaults are shared by the whole test run,
     * exactly like the light fixture project, so a key left set here would change how every later
     * class's renderer draws.
     */
    override fun tearDown() {
        try {
            UIManager.put(FORCE_SELECTION_FOREGROUND, forcedForegroundBefore)
        } finally {
            super.tearDown()
        }
    }

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

    fun testARemarkRowDrawsItsTextAloneOnTheFirstLineAndItsPositionOnTheMetadataLine() {
        val renderer = render(remarkRow(text = "why?", position = "4-6"))

        val textFragments = fragments(renderer.lines[0])
        assertEquals(listOf("why?"), textFragments.map { it.first })
        assertSame(RemarkStatusLook.textAttributes(RemarkStatus.PENDING), textFragments.single().second)

        val metadataFragments = fragments(renderer.metadataLine)
        assertEquals(listOf("4-6"), metadataFragments.map { it.first })
        assertSame(SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES, metadataFragments.single().second)
    }

    /**
     * ⚠️ The metadata line is the **smaller** grey, not the plain one. `RemarkStatusLook` gives a
     * `READ` remark's body exactly the plain grey, and every row in Done is `READ` or answered — so
     * with one grey for both, the body and the line under it were the same colour on every row in
     * Done and the metadata stopped reading as subordinate.
     */
    fun testAReadRowsMetadataLineIsStillToldApartFromItsGreyBody() {
        val renderer = render(remarkRow(text = "why?", position = "4-6", status = RemarkStatus.READ))

        val body = fragments(renderer.lines[0]).single().second
        val metadata = fragments(renderer.metadataLine).single().second
        assertSame(SimpleTextAttributes.GRAYED_ATTRIBUTES, body)
        assertNotSame(body, metadata)
        assertTrue(metadata.isSmaller)
    }

    /** A read remark greys out whole, the same rule the one-line renderer followed. */
    fun testAReadRemarkDrawsItsTextInTheGreyStatusAttributes() {
        val renderer = render(remarkRow(text = "why?", status = RemarkStatus.READ))

        assertSame(
            RemarkStatusLook.textAttributes(RemarkStatus.READ),
            fragments(renderer.lines[0]).last().second,
        )
    }

    /** A general remark has no position, so the metadata row has nothing to draw and stays hidden. */
    fun testAGeneralRemarkDrawsNoMetadataRow() {
        val renderer = render(remarkRow(text = "the whole change reads well", position = ""))

        assertFalse(renderer.metadataLine.isVisible)
    }

    /**
     * The other half of the same pair: a remark that does carry a position gets a visible metadata
     * row, which is what proves the hidden case above is really conditional and not just always off.
     */
    fun testARemarkWithAPositionDrawsAVisibleMetadataRow() {
        val renderer = render(remarkRow(text = "why is this synchronized?", position = "4-6"))

        assertTrue(renderer.metadataLine.isVisible)
        assertEquals("4-6", textOf(renderer.metadataLine))
    }

    fun testAnAnswerRowDrawsItsTextAloneAndItsPositionAndFileNameOnTheMetadataLine() {
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

        assertEquals(
            listOf("because two threads write it"),
            fragments(renderer.lines[0]).map { it.first },
        )
        assertEquals("4-6  Foo.kt", textOf(renderer.metadataLine))
    }

    /**
     * A nested answer carries no file name — see [AnswerNode.fileName] — so the metadata line shows
     * only its position, with none of the two-space gap a file name after it would need.
     */
    fun testANestedAnswerRowsMetadataLineIsThePositionAlone() {
        val renderer = render(
            AnswerNode(
                id = "a-1",
                path = "src/Foo.kt",
                startLine = 3,
                position = "4-6",
                fileName = "",
                question = "why?",
                firstLine = "because two threads write it",
                markdown = "because two threads write it",
                answeredAt = 0L,
            )
        )

        assertEquals("4-6", textOf(renderer.metadataLine))
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

    /**
     * ⚠️ The regression this pins is invisible in a light theme and obvious in the default dark one.
     * `ColoredTreeCellRenderer` overrode `append` to rewrite every fragment's colour to the tree's
     * selection foreground when the row is selected, the tree is focused and the theme sets
     * `Tree.forceFocusedSelectionForeground` — which the dark themes do. Without that substitution a
     * grey fragment keeps its own colour, because `SimpleColoredComponent` prefers the attribute's
     * colour over the component's foreground, so a `READ` row's whole body and every row's metadata
     * line stayed grey on top of the selection band.
     *
     * The key is set here rather than read: a test fixture loads no theme, so the production path
     * would silently take the "theme does not ask for it" branch and assert nothing.
     */
    fun testAGreyFragmentTakesTheSelectionForegroundOnAFocusedSelectedRow() {
        UIManager.put(FORCE_SELECTION_FOREGROUND, true)

        val adjusted = selectionAdjusted(
            SimpleTextAttributes.GRAYED_ATTRIBUTES,
            selected = true,
            focused = true,
        )

        assertEquals(UIUtil.getTreeSelectionForeground(true), adjusted.fgColor)
        assertEquals(SimpleTextAttributes.GRAYED_ATTRIBUTES.style, adjusted.style)
    }

    /** And nothing is rewritten on a row that is not selected, or in a tree that has no focus. */
    fun testAFragmentKeepsItsOwnAttributesWhenTheRowIsNotSelectedOrTheTreeIsNotFocused() {
        UIManager.put(FORCE_SELECTION_FOREGROUND, true)
        val grey = SimpleTextAttributes.GRAYED_ATTRIBUTES

        assertSame(grey, selectionAdjusted(grey, selected = false, focused = true))
        assertSame(grey, selectionAdjusted(grey, selected = true, focused = false))
    }

    /** A theme that does not ask for the substitution gets the attributes it was given, untouched. */
    fun testAThemeThatDoesNotForceTheSelectionForegroundLeavesTheAttributesAlone() {
        UIManager.put(FORCE_SELECTION_FOREGROUND, false)
        val grey = SimpleTextAttributes.GRAYED_ATTRIBUTES

        assertSame(grey, selectionAdjusted(grey, selected = true, focused = true))
    }

    /**
     * The indent subtraction runs on every painted row and nothing else reaches it: every node in a
     * real tree sits at level 2 or deeper — the side, then the file group, then the row — while every
     * other test in this class renders a node that is its own tree's root, at level 0.
     */
    fun testTheWrapWidthLosesOneIndentPerLevelOfDepth() {
        val renderer = RemarkTreeRenderer()
        val root = DefaultMutableTreeNode("remarks")
        val group = DefaultMutableTreeNode(GroupNode(DONE_KEY, DONE_LABEL))
        val leaf = DefaultMutableTreeNode(remarkRow(text = "why?"))
        root.add(group)
        group.add(leaf)
        val tree = Tree(DefaultTreeModel(root))
        tree.setSize(600, 200)
        val perLevel = UIUtil.getTreeLeftChildIndent() + UIUtil.getTreeRightChildIndent()

        assertEquals(600 - ROW_MARGIN, renderer.wrapWidth(tree, root))
        assertEquals(600 - perLevel - ROW_MARGIN, renderer.wrapWidth(tree, group))
        assertEquals(600 - 2 * perLevel - ROW_MARGIN, renderer.wrapWidth(tree, leaf))
    }

    /**
     * The floor, which is what a tree that has not been laid out yet needs: it reports a width of 0,
     * and without this every row would come back as three one-character lines.
     */
    fun testATreeWithNoWidthYetWrapsAtTheFloorRatherThanAtNothing() {
        val renderer = RemarkTreeRenderer()
        val node = DefaultMutableTreeNode(remarkRow(text = "why?"))

        assertEquals(MIN_WRAP_WIDTH, renderer.wrapWidth(Tree(DefaultTreeModel(node)), node))
    }

    /**
     * A long orphaned position plus a long file name used to be appended as one fragment however wide
     * it came out, and one row wider than the viewport puts a horizontal scroll bar under the whole
     * tree. It is elided rather than wrapped, because that line is one deliberate string — wrapping
     * would re-flow it and collapse the two-space gap between the position and the file name.
     */
    fun testAMetadataLineTooWideForTheRowIsCutShortWithAnEllipsis() {
        val renderer = render(
            remarkRow(
                text = "why?",
                position = "9:12-11:5 (orphaned, written at 5dc8d197)",
            ),
            treeWidth = 160,
        )

        assertTrue(textOf(renderer.metadataLine), textOf(renderer.metadataLine).endsWith("…"))
    }

    /**
     * `JTree`'s `AccessibleJTree` builds a row's accessible context out of whatever the renderer hands
     * back. `SimpleColoredComponent` supplies one carrying its own text and `ColoredTreeCellRenderer`
     * inherited it; a plain `JPanel`'s context has a null name, so a screen reader announced nothing
     * at all for every row until this override existed.
     */
    fun testARowsAccessibleNameIsTheTextItDrew() {
        val renderer = render(remarkRow(text = "why is this synchronized?", position = "4-6"))

        assertEquals("why is this synchronized? 4-6", renderer.getAccessibleContext().accessibleName)
    }

    /** And a row that drew nothing on its metadata line does not announce a stray separator either. */
    fun testAGeneralRemarksAccessibleNameIsItsTextAlone() {
        val renderer = render(remarkRow(text = "the whole change reads well", position = ""))

        assertEquals("the whole change reads well", renderer.getAccessibleContext().accessibleName)
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

    private companion object {
        /** The theme key `JBUI.CurrentTheme.Tree.Selection.forceFocusedSelectionForeground` reads. */
        const val FORCE_SELECTION_FOREGROUND = "Tree.forceFocusedSelectionForeground"
    }
}
