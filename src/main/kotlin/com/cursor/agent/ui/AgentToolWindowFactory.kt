package com.cursor.agent.ui

import com.cursor.agent.services.AgentSessionManager
import com.cursor.agent.services.AgentStatus
import com.cursor.agent.settings.AgentSettings
import com.intellij.openapi.Disposable
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory

class AgentToolWindowFactory : ToolWindowFactory, DumbAware {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val chatPanel = AgentChatPanel(project, toolWindow.disposable)
        val content = ContentFactory.getInstance().createContent(chatPanel, "Chat", false)
        toolWindow.contentManager.addContent(content)

        val manager = project.getService(AgentSessionManager::class.java)
        Disposer.register(toolWindow.disposable, manager)

        if (AgentSettings.getInstance().state.autoConnect) {
            if (manager.status == AgentStatus.DISCONNECTED) {
                manager.connect()
            }
        }
    }
}
