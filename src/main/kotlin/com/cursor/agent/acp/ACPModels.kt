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

data class NewSessionResult(
    val sessionId: String,
    val sessionConfigOptions: List<JsonElement>? = null,
    val modeState: JsonElement? = null
)


data class PromptParams(
    val sessionId: String,
    val prompt: List<ContentBlock>
)

data class ContentBlock(
    val type: String = "text",
    val text: String? = null
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
    val plan: JsonElement? = null
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
