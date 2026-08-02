package dev.sasha.clauderemarks.ui

import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.ui.UIUtil
import dev.sasha.clauderemarks.store.RemarkStore
import dev.sasha.clauderemarks.store.addRemark

/**
 * The panel, not just the nodes it builds. RemarksTreeTest only ever looks at the node model, so
 * both defects checked here were invisible to it: the tree showed one open file group out of three,
 * and every rebuild threw the selection away under the user.
 */
class RemarksPanelTest : BasePlatformTestCase() {

    override fun setUp() {
        super.setUp()
        // The light fixture project is shared across test classes.
        RemarkStore.getInstance(project).clear()
    }

    override fun tearDown() {
        RemarkStore.getInstance(project).clear()
        super.tearDown()
    }

    fun testEveryFileGroupIsExpanded() {
        listOf("A.kt", "B.kt", "C.kt").forEach { path ->
            addRemark(project, path, LINES, 0..0, "first note in $path", null)
            addRemark(project, path, LINES, 1..1, "second note in $path", null)
        }

        val panel = panel()

        // Three file nodes plus their six rows. The earlier loop read rowCount once, before any
        // expansion, so only the first file opened and this came back as 5.
        assertEquals(9, panel.tree.rowCount)
    }

    fun testTheSelectionSurvivesARefresh() {
        val remark = addRemark(project, "A.kt", LINES, 0..0, "a note", null)
        val panel = panel()
        panel.tree.setSelectionRow(1)
        assertEquals(listOf(remark.id), panel.selectedIds())

        // What happens on every remark change and on every editor opening.
        panel.refresh()
        settle()

        assertEquals(listOf(remark.id), panel.selectedIds())
    }

    private fun panel(): RemarksPanel {
        val disposable = Disposer.newDisposable()
        Disposer.register(testRootDisposable, disposable)
        return RemarksPanel(project, disposable).also { settle() }
    }

    /** refresh() hops to a pooled thread and back to the EDT, so both queues have to drain. */
    private fun settle() {
        repeat(10) {
            UIUtil.dispatchAllInvocationEvents()
            Thread.sleep(10)
        }
        UIUtil.dispatchAllInvocationEvents()
    }

    private companion object {
        val LINES = listOf("alpha", "beta")
    }
}
