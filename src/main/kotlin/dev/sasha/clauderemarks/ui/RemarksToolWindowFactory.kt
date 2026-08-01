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
import dev.sasha.clauderemarks.store.RemarkResolver
import java.awt.BorderLayout
import javax.swing.JButton
import javax.swing.JPanel

/**
 * Phase 2 screen: a flat list plus a Refresh button. Phase 3 replaces it with a tree
 * grouped by file, navigation on double click, and delete on the Delete key.
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
            // describeAll reads Documents. That needs a read lock and can touch disk, so it
            // runs off the EDT and only the list update comes back to it.
            ReadAction.nonBlocking<List<String>> { describeAll(project) }
                .expireWith(toolWindow.disposable)
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

    /** Runs inside a read action. Line numbers are shown 1-based, the way an editor shows them. */
    private fun describeAll(project: Project): List<String> =
        RemarkResolver.resolveAll(project).map { row ->
            val where = when (val r = row.result) {
                is AnchorResult.Exact -> "${r.startLine + 1}-${r.endLine + 1}"
                is AnchorResult.Relocated -> "${r.startLine + 1}-${r.endLine + 1} (moved)"
                is AnchorResult.Orphaned -> "${r.staleStartLine + 1}-${r.staleEndLine + 1} (orphaned)"
            }
            "${row.remark.path}:$where  ${row.remark.text}  [${row.remark.status}]"
        }
}
