package com.cursor.agent.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage

@State(
    name = "CursorAgentSettings",
    storages = [Storage("CursorAgentSettings.xml")]
)
class AgentSettings : PersistentStateComponent<AgentSettings.State> {
    data class State(
        var agentPath: String = "agent",
        var apiKey: String = "",
        var authToken: String = "",
        var endpoint: String = "",
        var autoApprovePermissions: Boolean = false,
        var defaultMode: String = "agent",
        var lastSessionId: String = "",
        var lastSessionCwd: String = "",
        var chatHistory: String = ""
    )

    private var myState = State()

    override fun getState(): State = myState
    override fun loadState(state: State) {
        myState = state
    }

    companion object {
        fun getInstance(): AgentSettings =
            ApplicationManager.getApplication().getService(AgentSettings::class.java)
    }
}
