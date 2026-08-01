package dev.sasha.clauderemarks.ui

import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.content.ContentFactory
import com.intellij.util.concurrency.AppExecutorUtil
import dev.sasha.clauderemarks.anchor.AnchorResult
import dev.sasha.clauderemarks.store.ResolvedRemark
import dev.sasha.clauderemarks.store.resolveAll
import java.awt.BorderLayout
import javax.swing.JButton
import javax.swing.JPanel

/**
 * Phase 2 screen: a flat list plus a Refresh button. The list only reloads when Refresh is
 * pressed, so a remark added while the window is open shows up on the next click. Phase 3
 * replaces this with a tree grouped by file, live refresh, navigation on double click, and
 * delete on the Delete key.
 */
class RemarksToolWindowFactory : ToolWindowFactory {

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val list = JBList<String>()
        val refreshButton = JButton("Refresh")
        val panel = JPanel(BorderLayout()).apply {
            add(refreshButton, BorderLayout.NORTH)
            add(JBScrollPane(list), BorderLayout.CENTER)
        }

        fun refresh() {
            // resolveAll reads Documents. That needs a read lock and can touch disk, so it
            // runs off the EDT and only the list update comes back to it. coalesceBy drops
            // the older run when Refresh is clicked twice, so a slow first result cannot
            // overwrite a newer one.
            ReadAction.nonBlocking<List<String>> { resolveAll(project).map(::describe) }
                .expireWith(toolWindow.disposable)
                .coalesceBy(this, project)
                .finishOnUiThread(ModalityState.defaultModalityState()) { rows ->
                    list.setListData(rows.toTypedArray())
                }
                .submit(AppExecutorUtil.getAppExecutorService())
        }

        refreshButton.addActionListener { refresh() }
        refresh()

        toolWindow.contentManager.addContent(
            ContentFactory.getInstance().createContent(panel, null, false)
        )
    }
}

/**
 * One row of the list. Line numbers are shown 1-based, the way an editor shows them, while
 * everything stored and resolved is 0-based.
 *
 * A relocated block that ended up back at its stored start line is not labelled "(moved)":
 * that is the case where the block itself was edited and the search found it through its
 * unchanged context.
 */
fun describe(row: ResolvedRemark): String {
    val result = row.result
    val label = when {
        result is AnchorResult.Orphaned -> " (orphaned)"
        result is AnchorResult.Relocated && result.startLine != row.remark.startLine -> " (moved)"
        else -> ""
    }
    val where = "${result.startLine + 1}-${result.endLine + 1}$label"
    return "${row.remark.path.orEmpty()}:$where  ${row.remark.text.orEmpty()}  [${row.remark.status}]"
}
