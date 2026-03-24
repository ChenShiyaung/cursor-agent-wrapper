package com.cursor.agent.settings

import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPasswordField
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import javax.swing.JComponent
import javax.swing.JPanel

class AgentSettingsConfigurable : Configurable {
    private var agentPathField: TextFieldWithBrowseButton? = null
    private var apiKeyField: JBPasswordField? = null
    private var authTokenField: JBPasswordField? = null
    private var endpointField: JBTextField? = null
    private var autoApproveBox: JBCheckBox? = null
    private var autoConnectBox: JBCheckBox? = null
    private var panel: JPanel? = null

    override fun getDisplayName(): String = "Cursor Agent"

    override fun createComponent(): JComponent {
        agentPathField = TextFieldWithBrowseButton().apply {
            @Suppress("DEPRECATION")
            addBrowseFolderListener(
                "Select Agent Binary",
                "Choose the Cursor agent CLI binary path",
                null,
                FileChooserDescriptorFactory.createSingleFileDescriptor()
            )
        }
        apiKeyField = JBPasswordField()
        authTokenField = JBPasswordField()
        endpointField = JBTextField()
        autoApproveBox = JBCheckBox("Auto-approve all tool permissions (not recommended)")
        autoConnectBox = JBCheckBox("Auto-connect on project open")

        panel = FormBuilder.createFormBuilder()
            .addLabeledComponent(JBLabel("Agent binary path:"), agentPathField!!, 1, false)
            .addLabeledComponent(JBLabel("API Key (CURSOR_API_KEY):"), apiKeyField!!, 1, false)
            .addLabeledComponent(JBLabel("Auth Token (CURSOR_AUTH_TOKEN):"), authTokenField!!, 1, false)
            .addLabeledComponent(JBLabel("API Endpoint:"), endpointField!!, 1, false)
            .addComponent(autoConnectBox!!, 1)
            .addComponent(autoApproveBox!!, 1)
            .addComponentFillVertically(JPanel(), 0)
            .panel

        return panel!!
    }

    override fun isModified(): Boolean {
        val settings = AgentSettings.getInstance().state
        return agentPathField?.text != settings.agentPath ||
                String(apiKeyField?.password ?: charArrayOf()) != settings.apiKey ||
                String(authTokenField?.password ?: charArrayOf()) != settings.authToken ||
                endpointField?.text != settings.endpoint ||
                autoApproveBox?.isSelected != settings.autoApprovePermissions ||
                autoConnectBox?.isSelected != settings.autoConnect
    }

    override fun apply() {
        val settings = AgentSettings.getInstance()
        val old = settings.state
        settings.loadState(
            AgentSettings.State(
                agentPath = agentPathField?.text ?: "agent",
                apiKey = String(apiKeyField?.password ?: charArrayOf()),
                authToken = String(authTokenField?.password ?: charArrayOf()),
                endpoint = endpointField?.text ?: "",
                autoApprovePermissions = autoApproveBox?.isSelected ?: false,
                autoConnect = autoConnectBox?.isSelected ?: true,
                defaultMode = old.defaultMode,
                selectedModel = old.selectedModel,
                lastSessionId = old.lastSessionId,
                lastSessionCwd = old.lastSessionCwd,
                chatHistory = old.chatHistory
            )
        )
    }

    override fun reset() {
        val settings = AgentSettings.getInstance().state
        agentPathField?.text = settings.agentPath
        apiKeyField?.text = settings.apiKey
        authTokenField?.text = settings.authToken
        endpointField?.text = settings.endpoint
        autoApproveBox?.isSelected = settings.autoApprovePermissions
        autoConnectBox?.isSelected = settings.autoConnect
    }

    override fun disposeUIResources() {
        agentPathField = null
        apiKeyField = null
        authTokenField = null
        endpointField = null
        autoApproveBox = null
        autoConnectBox = null
        panel = null
    }
}
