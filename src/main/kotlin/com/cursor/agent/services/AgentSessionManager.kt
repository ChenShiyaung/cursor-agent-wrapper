package com.cursor.agent.services

import com.cursor.agent.acp.*
import com.cursor.agent.settings.AgentSettings
import com.google.gson.JsonObject
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import kotlinx.coroutines.*
import java.io.File
import java.util.concurrent.ConcurrentHashMap

@Service(Service.Level.PROJECT)
class AgentSessionManager(private val project: Project) {
    private val log = Logger.getInstance(AgentSessionManager::class.java)
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private var acpClient: ACPClient? = null
    private var sessionId: String? = null
    private var isInitialized = false
    private val terminals = ConcurrentHashMap<String, TerminalSession>()
    private val toolCallCache = ConcurrentHashMap<String, ToolCallInfo>()
    private var terminalIdCounter = 0

    var onMessageChunk: ((String) -> Unit)? = null
    var onToolCallUpdate: ((ToolCallInfo) -> Unit)? = null
    var onStatusChanged: ((AgentStatus) -> Unit)? = null
    var onPermissionNeeded: ((Int, String, String, (Boolean) -> Unit) -> Unit)? = null
    var onPromptFinished: ((String) -> Unit)? = null
    var onSessionRestored: ((String) -> Unit)? = null

    fun saveChatHistory(html: String) {
        val settings = AgentSettings.getInstance()
        settings.state.chatHistory = html
    }

    fun loadChatHistory(): String {
        return AgentSettings.getInstance().state.chatHistory
    }

    fun getLastSessionId(): String {
        return AgentSettings.getInstance().state.lastSessionId
    }

    val status: AgentStatus get() = when {
        acpClient == null -> AgentStatus.DISCONNECTED
        !isInitialized -> AgentStatus.CONNECTING
        sessionId == null -> AgentStatus.CONNECTED
        else -> AgentStatus.READY
    }

    fun connect(resumeSessionId: String? = null) {
        scope.launch {
            try {
                onStatusChanged?.invoke(AgentStatus.CONNECTING)

                val settings = AgentSettings.getInstance().state
                val client = ACPClient(
                    agentPath = settings.agentPath,
                    apiKey = settings.apiKey.ifEmpty { null },
                    authToken = settings.authToken.ifEmpty { null },
                    endpoint = settings.endpoint.ifEmpty { null }
                )

                setupClientHandlers(client)

                if (!client.start()) {
                    notifyError("Failed to start Cursor Agent process. Check agent binary path in settings.")
                    onStatusChanged?.invoke(AgentStatus.DISCONNECTED)
                    return@launch
                }

                acpClient = client

                client.initialize()
                log.info("ACP initialized")

                client.authenticate()
                log.info("ACP authenticated")

                isInitialized = true
                onStatusChanged?.invoke(AgentStatus.CONNECTED)

                val cwd = project.basePath ?: System.getProperty("user.dir")
                val targetSessionId = resumeSessionId ?: settings.lastSessionId.ifEmpty { null }

                if (targetSessionId != null) {
                    try {
                        val loadResult = client.loadSession(targetSessionId, cwd)
                        if (loadResult != null) {
                            sessionId = targetSessionId
                            log.info("Session restored: $targetSessionId")
                            onStatusChanged?.invoke(AgentStatus.READY)
                            onSessionRestored?.invoke(targetSessionId)
                            return@launch
                        }
                    } catch (e: ACPException) {
                        log.warn("Failed to load previous session ($targetSessionId), creating new one: ${e.message}")
                    }
                }

                val result = client.newSession(cwd)
                if (result != null) {
                    sessionId = result.sessionId
                    saveSessionInfo(result.sessionId, cwd)
                    log.info("Session created: ${result.sessionId}")
                    onStatusChanged?.invoke(AgentStatus.READY)
                } else {
                    notifyError("Failed to create agent session")
                }
            } catch (e: ACPException) {
                log.error("ACP error during connection", e)
                notifyError("Agent error: ${e.message}")
                onStatusChanged?.invoke(AgentStatus.DISCONNECTED)
            } catch (e: Exception) {
                log.error("Failed to connect to agent", e)
                notifyError("Connection failed: ${e.message}")
                onStatusChanged?.invoke(AgentStatus.DISCONNECTED)
            }
        }
    }

    private fun saveSessionInfo(sessionId: String, cwd: String) {
        val settings = AgentSettings.getInstance()
        settings.state.lastSessionId = sessionId
        settings.state.lastSessionCwd = cwd
    }

    fun disconnect() {
        acpClient?.destroy()
        acpClient = null
        sessionId = null
        isInitialized = false
        terminals.values.forEach { it.process?.destroyForcibly() }
        terminals.clear()
        toolCallCache.clear()
        onStatusChanged?.invoke(AgentStatus.DISCONNECTED)
    }

    fun sendPrompt(text: String) {
        val sid = sessionId ?: run {
            notifyError("No active session. Please connect first.")
            return
        }
        val client = acpClient ?: return

        scope.launch {
            try {
                onStatusChanged?.invoke(AgentStatus.THINKING)
                val result = client.prompt(sid, text)
                onPromptFinished?.invoke(result?.stopReason ?: "unknown")
                onStatusChanged?.invoke(AgentStatus.READY)
            } catch (e: ACPException) {
                log.error("Prompt error", e)
                onStatusChanged?.invoke(AgentStatus.READY)
                notifyError("Agent error: ${e.message}")
            } catch (e: CancellationException) {
                onStatusChanged?.invoke(AgentStatus.READY)
            } catch (e: Exception) {
                log.error("Prompt error", e)
                onStatusChanged?.invoke(AgentStatus.READY)
                notifyError("Error: ${e.message}")
            }
        }
    }

    fun cancelCurrentPrompt() {
        val sid = sessionId ?: return
        acpClient?.cancel(sid)
    }

    private fun setupClientHandlers(client: ACPClient) {
        client.onSessionUpdate = { update ->
            when (update.sessionUpdate) {
                "agent_message_chunk" -> {
                    update.content?.text?.let { onMessageChunk?.invoke(it) }
                }
                "tool_call" -> {
                    val tcId = update.toolCallId
                    if (tcId != null) {
                        val info = ToolCallInfo(
                            toolCallId = tcId,
                            title = update.title,
                            kind = update.kind,
                            status = update.status,
                            input = update.input
                        )
                        toolCallCache[tcId] = info
                        onToolCallUpdate?.invoke(info)
                    }
                }
                "tool_call_update" -> {
                    val tcId = update.toolCallId
                    if (tcId != null) {
                        val cached = toolCallCache[tcId]
                        val info = ToolCallInfo(
                            toolCallId = tcId,
                            title = update.title ?: cached?.title,
                            kind = update.kind ?: cached?.kind,
                            status = update.status ?: cached?.status,
                            input = update.input ?: cached?.input
                        )
                        toolCallCache[tcId] = info
                        onToolCallUpdate?.invoke(info)
                    }
                }
                else -> {
                    log.info("Session update: ${update.sessionUpdate}")
                }
            }
        }

        client.onPermissionRequest = { id, req ->
            val settings = AgentSettings.getInstance().state
            if (settings.autoApprovePermissions) {
                client.respond(id, PermissionResponse(PermissionOutcome()))
            } else {
                val tcId = req.toolCall.toolCallId
                val cached = tcId?.let { toolCallCache[it] }
                val title = req.toolCall.title ?: cached?.title ?: "Unknown operation"
                val detail = buildPermissionDetail(req, cached)
                onPermissionNeeded?.invoke(id, title, detail) { approved ->
                    if (approved) {
                        client.respond(id, PermissionResponse(PermissionOutcome()))
                    } else {
                        client.respond(id, PermissionResponse(
                            PermissionOutcome(outcome = "selected", optionId = "reject-once")
                        ))
                    }
                }
            }
        }

        client.onReadFileRequest = { id, req ->
            handleReadFile(client, id, req)
        }

        client.onWriteFileRequest = { id, req ->
            handleWriteFile(client, id, req)
        }

        client.onCreateTerminalRequest = { id, req ->
            handleCreateTerminal(client, id, req)
        }

        client.onTerminalOutputRequest = { id, req ->
            handleTerminalOutput(client, id, req)
        }

        client.onWaitForExitRequest = { id, req ->
            handleWaitForExit(client, id, req)
        }

        client.onReleaseTerminalRequest = { id, req ->
            handleReleaseTerminal(client, id, req)
        }

        client.onKillTerminalRequest = { id, req ->
            handleKillTerminal(client, id, req)
        }

        client.onDisconnected = {
            isInitialized = false
            sessionId = null
            onStatusChanged?.invoke(AgentStatus.DISCONNECTED)
        }
    }

    // --- File operations ---

    private fun handleReadFile(client: ACPClient, id: Int, req: ReadTextFileRequest) {
        ApplicationManager.getApplication().runReadAction {
            try {
                val file = File(req.path)
                if (!file.exists()) {
                    client.respondError(id, -32000, "File not found: ${req.path}")
                    return@runReadAction
                }
                val lines = file.readLines()
                val startLine = (req.startLine ?: 1) - 1
                val limit = req.limit ?: lines.size
                val selectedLines = lines.drop(startLine.coerceAtLeast(0)).take(limit)
                val content = selectedLines.joinToString("\n")

                val result = JsonObject().apply {
                    addProperty("content", content)
                }
                client.respond(id, result)
            } catch (e: Exception) {
                client.respondError(id, -32000, "Failed to read file: ${e.message}")
            }
        }
    }

    private fun handleWriteFile(client: ACPClient, id: Int, req: WriteTextFileRequest) {
        ApplicationManager.getApplication().invokeLater {
            ApplicationManager.getApplication().runWriteAction {
                try {
                    val file = File(req.path)
                    file.parentFile?.mkdirs()
                    file.writeText(req.content)

                    LocalFileSystem.getInstance().refreshAndFindFileByPath(req.path)

                    client.respond(id, JsonObject())
                } catch (e: Exception) {
                    client.respondError(id, -32000, "Failed to write file: ${e.message}")
                }
            }
        }
    }

    // --- Terminal operations ---

    private fun handleCreateTerminal(client: ACPClient, id: Int, req: CreateTerminalRequest) {
        scope.launch {
            try {
                val termId = "term_${++terminalIdCounter}"
                val command = mutableListOf<String>()

                val isWindows = System.getProperty("os.name").lowercase().contains("win")
                if (isWindows) {
                    command.addAll(listOf("cmd", "/c", req.command))
                } else {
                    command.addAll(listOf("sh", "-c", req.command))
                }
                req.args?.let { command.addAll(it) }

                val pb = ProcessBuilder(command)
                req.cwd?.let { pb.directory(File(it)) }
                    ?: project.basePath?.let { pb.directory(File(it)) }
                pb.redirectErrorStream(true)

                val process = pb.start()
                val output = StringBuilder()

                val outputJob = scope.launch {
                    val reader = process.inputStream.bufferedReader()
                    var line: String?
                    while (reader.readLine().also { line = it } != null) {
                        output.append(line).append("\n")
                        val limit = req.outputBytesLimit
                        if (limit != null && output.length > limit) {
                            val excess = output.length - limit
                            output.delete(0, excess)
                        }
                    }
                }

                terminals[termId] = TerminalSession(process, output, outputJob)

                val result = JsonObject().apply {
                    addProperty("terminalId", termId)
                }
                client.respond(id, result)
            } catch (e: Exception) {
                client.respondError(id, -32000, "Failed to create terminal: ${e.message}")
            }
        }
    }

    private fun handleTerminalOutput(client: ACPClient, id: Int, req: TerminalOutputRequest) {
        val session = terminals[req.terminalId]
        if (session == null) {
            client.respondError(id, -32000, "Terminal not found: ${req.terminalId}")
            return
        }
        val result = JsonObject().apply {
            addProperty("output", session.output.toString())
            addProperty("truncated", false)
            if (!session.process!!.isAlive) {
                val exitObj = JsonObject().apply {
                    addProperty("exitCode", session.process.exitValue())
                }
                add("exitStatus", exitObj)
            }
        }
        client.respond(id, result)
    }

    private fun handleWaitForExit(client: ACPClient, id: Int, req: WaitForTerminalExitRequest) {
        scope.launch {
            val session = terminals[req.terminalId]
            if (session == null) {
                client.respondError(id, -32000, "Terminal not found: ${req.terminalId}")
                return@launch
            }
            val exitCode = session.process?.waitFor()
            session.outputJob?.join()
            val result = JsonObject().apply {
                addProperty("exitCode", exitCode)
            }
            client.respond(id, result)
        }
    }

    private fun handleReleaseTerminal(client: ACPClient, id: Int, req: ReleaseTerminalRequest) {
        val session = terminals.remove(req.terminalId)
        if (session == null) {
            client.respondError(id, -32000, "Terminal not found: ${req.terminalId}")
            return
        }
        session.process?.destroyForcibly()
        session.outputJob?.cancel()
        client.respond(id, JsonObject())
    }

    private fun handleKillTerminal(client: ACPClient, id: Int, req: KillTerminalRequest) {
        val session = terminals[req.terminalId]
        if (session == null) {
            client.respondError(id, -32000, "Terminal not found: ${req.terminalId}")
            return
        }
        session.process?.destroyForcibly()
        client.respond(id, JsonObject())
    }

    private fun buildPermissionDetail(req: PermissionRequest, cached: ToolCallInfo?): String {
        val parts = mutableListOf<String>()
        val kind = req.toolCall.kind ?: cached?.kind
        if (kind != null) parts.add("Type: $kind")

        val input = req.toolCall.input ?: cached?.input
        if (input != null) {
            try {
                val obj = input.asJsonObject
                obj.get("command")?.asString?.let { parts.add("Command: $it") }
                obj.get("path")?.asString?.let { parts.add("Path: $it") }
                obj.get("content")?.asString?.let {
                    parts.add("Content: ${it.take(200)}${if (it.length > 200) "..." else ""}")
                }
                obj.get("pattern")?.asString?.let { parts.add("Pattern: $it") }
                obj.get("text")?.asString?.let { parts.add("Text: ${it.take(200)}") }
                obj.get("description")?.asString?.let { parts.add("Description: $it") }
            } catch (_: Exception) {
                parts.add(input.toString().take(300))
            }
        }
        return parts.joinToString("\n")
    }

    private fun notifyError(message: String) {
        ApplicationManager.getApplication().invokeLater {
            NotificationGroupManager.getInstance()
                .getNotificationGroup("Cursor Agent")
                .createNotification(message, NotificationType.ERROR)
                .notify(project)
        }
    }

    fun dispose() {
        disconnect()
        scope.cancel()
    }
}

data class TerminalSession(
    val process: Process?,
    val output: StringBuilder,
    val outputJob: Job?
)

enum class AgentStatus {
    DISCONNECTED, CONNECTING, CONNECTED, READY, THINKING
}
