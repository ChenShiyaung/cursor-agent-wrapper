package com.cursor.agent.ui

import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory

class AgentToolWindowFactory : ToolWindowFactory, DumbAware {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val chatPanel = AgentChatPanel(project)
        val content = ContentFactory.getInstance().createContent(chatPanel, "Chat", false)
        toolWindow.contentManager.addContent(content)
    }
}
