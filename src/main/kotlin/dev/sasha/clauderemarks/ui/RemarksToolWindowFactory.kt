package dev.sasha.clauderemarks.ui

import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory
import javax.swing.JLabel

class RemarksToolWindowFactory : ToolWindowFactory {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val content = ContentFactory.getInstance()
            .createContent(JLabel("No remarks yet."), null, false)
        toolWindow.contentManager.addContent(content)
    }
}
