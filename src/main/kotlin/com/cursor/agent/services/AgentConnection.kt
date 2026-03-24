package com.cursor.agent.services

import com.cursor.agent.acp.*
import com.cursor.agent.settings.AgentSettings
import com.google.gson.JsonObject
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import kotlinx.coroutines.*
import java.io.File
import java.util.concurrent.ConcurrentHashMap

class AgentConnection(
    private val project: Project,
    val chatId: String? = null
) {
    private val log = Logger.getInstance(AgentConnection::class.java)
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private var acpClient: ACPClient? = null
    var sessionId: String? = null
        private set
    private var isInitialized = false
    private val terminals = ConcurrentHashMap<String, TerminalSession>()
    private val toolCallCache = ConcurrentHashMap<String, ToolCallInfo>()
    private var terminalIdCounter = 0

    var currentModelId: String = ""
        private set
    var configOptions: List<ConfigOption> = emptyList()
        private set

    var onMessageChunk: ((String) -> Unit)? = null
    var onThoughtChunk: ((String) -> Unit)? = null
    var onToolCallUpdate: ((ToolCallInfo) -> Unit)? = null
    var onStatusChanged: ((AgentStatus) -> Unit)? = null
    var onPermissionNeeded: ((Int, String, String, (Boolean) -> Unit) -> Unit)? = null
    var onPromptFinished: ((String) -> Unit)? = null
    var onModelChanged: ((String) -> Unit)? = null
    var onConfigOptionsUpdated: ((List<ConfigOption>) -> Unit)? = null
    var onSessionTitleChanged: ((String) -> Unit)? = null

    var sessionLoadSucceeded: Boolean = false
        private set
    @Volatile
    private var isLoadingSession: Boolean = false

    val status: AgentStatus get() = when {
        acpClient == null -> AgentStatus.DISCONNECTED
        !isInitialized -> AgentStatus.CONNECTING
        sessionId == null -> AgentStatus.CONNECTED
        else -> AgentStatus.READY
    }

    fun connect() {
        scope.launch {
            try {
                log.info("AgentConnection.connect() called, chatId=$chatId")
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
                    notifyError("Failed to start Cursor Agent process.")
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

                log.info("About to create session. chatId=$chatId, supportsLoadSession=${client.supportsLoadSession}")
                sessionLoadSucceeded = false
                val result = if (chatId != null && client.supportsLoadSession) {
                    try {
                        log.info(">>> Attempting session/load for chatId=$chatId cwd=$cwd")
                        isLoadingSession = true
                        val loadResult = client.loadSession(chatId, cwd)
                        isLoadingSession = false
                        if (loadResult?.sessionId != null) {
                            sessionId = loadResult.sessionId
                            log.info(">>> session/load returned sessionId=${loadResult.sessionId}")
                        } else {
                            sessionId = chatId
                            log.info(">>> session/load returned null sessionId, using chatId=$chatId")
                        }
                        sessionLoadSucceeded = true
                        log.info(">>> session/load SUCCEEDED for chatId=$chatId")
                        loadResult
                    } catch (e: Exception) {
                        isLoadingSession = false
                        log.warn(">>> session/load FAILED for chatId=$chatId: ${e.javaClass.simpleName}: ${e.message}")
                        log.warn(">>> Falling back to session/new")
                        val newResult = client.newSession(cwd)
                        sessionId = newResult?.sessionId
                        log.info(">>> session/new fallback created sessionId=${newResult?.sessionId} (chatId was $chatId)")
                        newResult
                    }
                } else if (chatId != null && !client.supportsLoadSession) {
                    log.info(">>> chatId=$chatId but agent does NOT support loadSession, using session/new")
                    val newResult = client.newSession(cwd)
                    sessionId = newResult?.sessionId
                    log.info(">>> session/new created sessionId=${newResult?.sessionId}")
                    newResult
                } else {
                    log.info(">>> chatId is null, using session/new directly")
                    val newResult = client.newSession(cwd)
                    sessionId = newResult?.sessionId
                    log.info(">>> session/new created sessionId=${newResult?.sessionId}")
                    newResult
                }

                if (result != null) {
                    val sid = sessionId
                    if (sid != null) {
                        resolveWorkspaceHash(sid)
                        settings.projectSessionCwds[projectKey()] = cwd
                    }
                    log.info("Session ready: $sid")

                    result.configOptions?.let { opts ->
                        configOptions = opts
                        val modelOpt = opts.find { it.id == "model" }
                        if (modelOpt != null) {
                            currentModelId = modelOpt.currentValue ?: ""
                            settings.selectedModel = currentModelId
                            if (sid != null) settings.sessionModels[sid] = currentModelId
                            onModelChanged?.invoke(currentModelId)
                        }
                        onConfigOptionsUpdated?.invoke(opts)
                    }

                    if (configOptions.isEmpty() && result.models != null) {
                        val m = result.models
                        currentModelId = m.currentModelId ?: ""
                        settings.selectedModel = currentModelId
                        configOptions = listOf(ConfigOption(
                            id = "model", name = "Model", category = "model", type = "select",
                            currentValue = m.currentModelId,
                            options = m.availableModels?.map { ConfigOptionValue(value = it.modelId, name = it.name) }
                        ))
                        onModelChanged?.invoke(currentModelId)
                        onConfigOptionsUpdated?.invoke(configOptions)
                    }

                    if (currentModelId.isEmpty()) {
                        val saved = sid?.let { settings.sessionModels[it] } ?: settings.selectedModel
                        if (saved.isNotEmpty()) {
                            currentModelId = saved
                            onModelChanged?.invoke(currentModelId)
                        }
                    }

                    onStatusChanged?.invoke(AgentStatus.READY)
                } else {
                    notifyError("Failed to create agent session")
                    onStatusChanged?.invoke(AgentStatus.DISCONNECTED)
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

    fun disconnect() {
        try {
            acpClient?.destroy()
        } catch (e: Exception) {
            log.warn("Error destroying ACP client", e)
        }
        acpClient = null
        sessionId = null
        isInitialized = false
        terminals.values.forEach { it.process?.destroyForcibly() }
        terminals.clear()
        toolCallCache.clear()
        onStatusChanged?.invoke(AgentStatus.DISCONNECTED)
    }

    fun destroy() {
        disconnect()
        scope.cancel()
    }

    fun sendPrompt(text: String) {
        val sid = sessionId ?: run {
            notifyError("No active session.")
            return
        }
        val client = acpClient ?: return

        scope.launch {
            try {
                onStatusChanged?.invoke(AgentStatus.THINKING)
                val result = client.prompt(sid, text)
                resolveWorkspaceHash(sid)
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

    fun cancelPrompt() {
        val sid = sessionId ?: return
        acpClient?.cancel(sid)
    }

    fun getModelConfigOption(): ConfigOption? = configOptions.find { it.id == "model" }

    fun switchModel(acpValue: String) {
        val sid = sessionId ?: return
        val client = acpClient ?: return
        val modelOpt = getModelConfigOption() ?: return

        scope.launch {
            try {
                val result = client.setConfigOption(sid, modelOpt.id, acpValue)
                if (result?.configOptions != null) configOptions = result.configOptions
                currentModelId = acpValue
                val state = AgentSettings.getInstance().state
                state.selectedModel = acpValue
                sessionId?.let { state.sessionModels[it] = acpValue }
                onModelChanged?.invoke(acpValue)
            } catch (e: Exception) {
                log.error("Failed to switch model to $acpValue", e)
                notifyError("Failed to switch model: ${e.message}")
            }
        }
    }

    private fun projectKey(): String = project.basePath ?: ""

    private fun setupClientHandlers(client: ACPClient) {
        client.onSessionUpdate = { update ->
            when (update.sessionUpdate) {
                "agent_message_chunk" -> {
                    if (!isLoadingSession) update.content?.text?.let { onMessageChunk?.invoke(it) }
                }
                "agent_thought_chunk" -> {
                    if (!isLoadingSession) update.content?.text?.let { onThoughtChunk?.invoke(it) }
                }
                "user_message_chunk" -> {
                    // Ignored: replay during session/load, content loaded from DB
                }
                "tool_call", "tool_call_update" -> {
                    if (!isLoadingSession) {
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
                }
                "config_option_update" -> {
                    update.configOptions?.let { opts ->
                        configOptions = opts
                        val modelOpt = opts.find { it.id == "model" }
                        if (modelOpt != null) {
                            currentModelId = modelOpt.currentValue ?: ""
                            AgentSettings.getInstance().state.selectedModel = currentModelId
                            onModelChanged?.invoke(currentModelId)
                        }
                        onConfigOptionsUpdated?.invoke(opts)
                    }
                }
                "session_info_update", "session_title_update" -> {
                    val newTitle = update.sessionTitle ?: update.title
                    if (!newTitle.isNullOrBlank()) {
                        log.info("Title update received: $newTitle (type=${update.sessionUpdate})")
                        onSessionTitleChanged?.invoke(newTitle)
                    }
                }
                else -> {
                    log.info("Unhandled sessionUpdate type: ${update.sessionUpdate}")
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
                    val outcome = if (approved) PermissionOutcome()
                    else PermissionOutcome(outcome = "selected", optionId = "reject-once")
                    client.respond(id, PermissionResponse(outcome))
                }
            }
        }

        client.onReadFileRequest = { id, req -> handleReadFile(client, id, req) }
        client.onWriteFileRequest = { id, req -> handleWriteFile(client, id, req) }
        client.onCreateTerminalRequest = { id, req -> handleCreateTerminal(client, id, req) }
        client.onTerminalOutputRequest = { id, req -> handleTerminalOutput(client, id, req) }
        client.onWaitForExitRequest = { id, req -> handleWaitForExit(client, id, req) }
        client.onReleaseTerminalRequest = { id, req -> handleReleaseTerminal(client, id, req) }
        client.onKillTerminalRequest = { id, req -> handleKillTerminal(client, id, req) }

        client.onDisconnected = {
            isInitialized = false
            sessionId = null
            onStatusChanged?.invoke(AgentStatus.DISCONNECTED)
        }
    }

    private fun handleReadFile(client: ACPClient, id: Int, req: ReadTextFileRequest) {
        ApplicationManager.getApplication().runReadAction {
            try {
                val file = File(req.path)
                if (!file.exists()) { client.respondError(id, -32000, "File not found: ${req.path}"); return@runReadAction }
                val lines = file.readLines()
                val startLine = (req.startLine ?: 1) - 1
                val limit = req.limit ?: lines.size
                val content = lines.drop(startLine.coerceAtLeast(0)).take(limit).joinToString("\n")
                client.respond(id, JsonObject().apply { addProperty("content", content) })
            } catch (e: Exception) { client.respondError(id, -32000, "Failed to read: ${e.message}") }
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
                } catch (e: Exception) { client.respondError(id, -32000, "Failed to write: ${e.message}") }
            }
        }
    }

    private fun handleCreateTerminal(client: ACPClient, id: Int, req: CreateTerminalRequest) {
        scope.launch {
            try {
                val termId = "term_${++terminalIdCounter}"
                val isWindows = System.getProperty("os.name").lowercase().contains("win")
                val command = if (isWindows) mutableListOf("cmd", "/c", req.command) else mutableListOf("sh", "-c", req.command)
                req.args?.let { command.addAll(it) }
                val pb = ProcessBuilder(command)
                req.cwd?.let { pb.directory(File(it)) } ?: project.basePath?.let { pb.directory(File(it)) }
                pb.redirectErrorStream(true)
                val process = pb.start()
                val output = StringBuilder()
                val outputJob = scope.launch {
                    val reader = process.inputStream.bufferedReader()
                    var line: String?
                    while (reader.readLine().also { line = it } != null) {
                        output.append(line).append("\n")
                        val limit = req.outputBytesLimit
                        if (limit != null && output.length > limit) output.delete(0, output.length - limit)
                    }
                }
                terminals[termId] = TerminalSession(process, output, outputJob)
                client.respond(id, JsonObject().apply { addProperty("terminalId", termId) })
            } catch (e: Exception) { client.respondError(id, -32000, "Failed to create terminal: ${e.message}") }
        }
    }

    private fun handleTerminalOutput(client: ACPClient, id: Int, req: TerminalOutputRequest) {
        val session = terminals[req.terminalId]
        if (session == null) { client.respondError(id, -32000, "Terminal not found"); return }
        client.respond(id, JsonObject().apply {
            addProperty("output", session.output.toString())
            addProperty("truncated", false)
            if (!session.process!!.isAlive) add("exitStatus", JsonObject().apply { addProperty("exitCode", session.process.exitValue()) })
        })
    }

    private fun handleWaitForExit(client: ACPClient, id: Int, req: WaitForTerminalExitRequest) {
        scope.launch {
            val session = terminals[req.terminalId]
            if (session == null) { client.respondError(id, -32000, "Terminal not found"); return@launch }
            val exitCode = session.process?.waitFor()
            session.outputJob?.join()
            client.respond(id, JsonObject().apply { addProperty("exitCode", exitCode) })
        }
    }

    private fun handleReleaseTerminal(client: ACPClient, id: Int, req: ReleaseTerminalRequest) {
        val session = terminals.remove(req.terminalId)
        if (session == null) { client.respondError(id, -32000, "Terminal not found"); return }
        session.process?.destroyForcibly(); session.outputJob?.cancel()
        client.respond(id, JsonObject())
    }

    private fun handleKillTerminal(client: ACPClient, id: Int, req: KillTerminalRequest) {
        val session = terminals[req.terminalId]
        if (session == null) { client.respondError(id, -32000, "Terminal not found"); return }
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
                obj.get("content")?.asString?.let { parts.add("Content: ${it.take(200)}${if (it.length > 200) "..." else ""}") }
            } catch (_: Exception) { parts.add(input.toString().take(300)) }
        }
        return parts.joinToString("\n")
    }

    private fun resolveWorkspaceHash(sessionId: String) {
        val chatsDir = File(System.getProperty("user.home"), ".cursor/chats")
        if (!chatsDir.exists()) return
        val wsDirs = chatsDir.listFiles { f -> f.isDirectory } ?: return
        for (wsDir in wsDirs) {
            if (File(wsDir, sessionId).isDirectory) {
                AgentSettings.getInstance().state.projectWorkspaceHashes[projectKey()] = wsDir.name
                return
            }
        }
    }

    private fun notifyError(message: String) {
        ApplicationManager.getApplication().invokeLater {
            com.intellij.notification.NotificationGroupManager.getInstance()
                .getNotificationGroup("Cursor Agent")
                .createNotification(message, com.intellij.notification.NotificationType.ERROR)
                .notify(project)
        }
    }
}
