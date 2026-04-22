package no.huzef.blackhole

import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory
import no.huzef.blackhole.ui.SqlToJavaToolWindow

class MyToolWindowFactory : ToolWindowFactory {

    override fun shouldBeAvailable(project: Project) = true

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val toolWindowUI = SqlToJavaToolWindow(project)
        val content = ContentFactory.getInstance()
            .createContent(toolWindowUI.panel, null, false)
        toolWindow.contentManager.addContent(content)
    }
}