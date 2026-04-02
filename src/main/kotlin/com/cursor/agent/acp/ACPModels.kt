package com.cursor.agent.acp

import com.google.gson.JsonElement

data class InitializeParams(
    val protocolVersion: Int = 1,
    val clientCapabilities: ClientCapabilities = ClientCapabilities(),
    val clientInfo: ClientInfo = ClientInfo()
)

data class ClientCapabilities(
    val fs: FsCapabilities = FsCapabilities(),
    val terminal: Boolean = true
)

data class FsCapabilities(
    val readTextFile: Boolean = true,
    val writeTextFile: Boolean = true
)

data class ClientInfo(
    val name: String = "cursor-agent-deveco",
    val version: String = "1.0.0"
)

data class AuthenticateParams(
    val methodId: String = "cursor_login"
)

data class NewSessionParams(
    val cwd: String,
    val mcpServers: List<JsonElement> = emptyList()
)

data class LoadSessionParams(
    val sessionId: String,
    val cwd: String,
    val mcpServers: List<JsonElement> = emptyList()
)

data class NewSessionResult(
    val sessionId: String? = null,
    val configOptions: List<ConfigOption>? = null,
    val models: ModelsInfo? = null,
    val modes: ModesInfo? = null
) {
    @Transient var resolvedSessionId: String? = null
}

data class ModelsInfo(
    val currentModelId: String? = null,
    val availableModels: List<AvailableModel>? = null
)

data class AvailableModel(
    val modelId: String,
    val name: String? = null
)

data class ModesInfo(
    val currentModeId: String? = null,
    val availableModes: List<AvailableMode>? = null
)

data class AvailableMode(
    val id: String,
    val name: String? = null,
    val description: String? = null
)

data class ConfigOption(
    val id: String,
    val name: String? = null,
    val description: String? = null,
    val category: String? = null,
    val type: String? = null,
    val currentValue: String? = null,
    val options: List<ConfigOptionValue>? = null
)

data class ConfigOptionValue(
    val value: String,
    val name: String? = null,
    val description: String? = null,
    val group: String? = null
)

data class SetConfigOptionParams(
    val sessionId: String,
    val configId: String,
    val value: String
)

data class SetConfigOptionResult(
    val configOptions: List<ConfigOption>? = null
)

data class PromptParams(
    val sessionId: String,
    val prompt: List<ContentBlock>
)

data class ContentBlock(
    val type: String = "text",
    val text: String? = null,
    val mimeType: String? = null,
    val data: String? = null,
    val uri: String? = null
)

data class PromptResult(
    val stopReason: String
)

data class SessionUpdate(
    val sessionId: String,
    val update: SessionUpdateContent
)

data class SessionUpdateContent(
    val sessionUpdate: String,
    val content: UpdateContent? = null,
    // tool_call / tool_call_update fields (flattened at update level per ACP)
    val toolCallId: String? = null,
    val title: String? = null,
    val kind: String? = null,
    val status: String? = null,
    val input: JsonElement? = null,
    val output: JsonElement? = null,
    val todos: JsonElement? = null,
    val plan: JsonElement? = null,
    // config_option_update
    val configOptions: List<ConfigOption>? = null,
    // session_title_update
    val sessionTitle: String? = null
)

data class UpdateContent(
    val text: String? = null,
    val type: String? = null
)

data class ToolCallInfo(
    val toolCallId: String,
    val title: String?,
    val kind: String?,
    val status: String?,
    val input: JsonElement? = null
)

data class PermissionRequest(
    val sessionId: String,
    val toolCall: PermissionToolCallRef,
    val options: List<PermissionOption>? = null
)

data class PermissionToolCallRef(
    val toolCallId: String? = null,
    val title: String? = null,
    val kind: String? = null,
    val status: String? = null,
    val input: JsonElement? = null
)

data class PermissionOption(
    val optionId: String,
    val label: String? = null
)

data class PermissionResponse(
    val outcome: PermissionOutcome
)

data class PermissionOutcome(
    val outcome: String = "selected",
    val optionId: String = "allow-once"
)

data class CancelParams(
    val sessionId: String
)

// Client-side request models (from agent to client)
data class ReadTextFileRequest(
    val sessionId: String,
    val path: String,
    val startLine: Int? = null,
    val limit: Int? = null
)

data class WriteTextFileRequest(
    val sessionId: String,
    val path: String,
    val content: String
)

data class CreateTerminalRequest(
    val sessionId: String,
    val command: String,
    val args: List<String>? = null,
    val cwd: String? = null,
    val env: List<JsonElement>? = null,
    val outputBytesLimit: Int? = null
)

data class TerminalOutputRequest(
    val sessionId: String,
    val terminalId: String
)

data class WaitForTerminalExitRequest(
    val sessionId: String,
    val terminalId: String
)

data class ReleaseTerminalRequest(
    val sessionId: String,
    val terminalId: String
)

data class KillTerminalRequest(
    val sessionId: String,
    val terminalId: String
)
