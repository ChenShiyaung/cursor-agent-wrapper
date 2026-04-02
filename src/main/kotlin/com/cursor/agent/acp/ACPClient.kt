package com.cursor.agent.acp

import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.intellij.openapi.diagnostic.Logger
import kotlinx.coroutines.*
import java.io.BufferedReader
import java.io.File
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

class ACPClient(
    private val agentPath: String,
    private val apiKey: String? = null,
    private val authToken: String? = null,
    private val endpoint: String? = null
) {
    private data class ResolvedAgentCommand(
        val executable: String,
        val source: String,
        val attempts: List<String>
    )

    private val log = Logger.getInstance(ACPClient::class.java)
    private val gson = Gson()
    private val nextId = AtomicInteger(1)
    private val pendingRequests = ConcurrentHashMap<Int, CompletableDeferred<JsonElement?>>()

    private var process: Process? = null
    private var writer: OutputStreamWriter? = null
    private var readerJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var lastStartErrorDetails: String? = null

    var onSessionUpdate: ((SessionUpdateContent) -> Unit)? = null
    var onPermissionRequest: ((Int, PermissionRequest) -> Unit)? = null
    var onReadFileRequest: ((Int, ReadTextFileRequest) -> Unit)? = null
    var onWriteFileRequest: ((Int, WriteTextFileRequest) -> Unit)? = null
    var onCreateTerminalRequest: ((Int, CreateTerminalRequest) -> Unit)? = null
    var onTerminalOutputRequest: ((Int, TerminalOutputRequest) -> Unit)? = null
    var onWaitForExitRequest: ((Int, WaitForTerminalExitRequest) -> Unit)? = null
    var onReleaseTerminalRequest: ((Int, ReleaseTerminalRequest) -> Unit)? = null
    var onKillTerminalRequest: ((Int, KillTerminalRequest) -> Unit)? = null
    var onDisconnected: (() -> Unit)? = null

    val isConnected: Boolean get() = process?.isAlive == true
    fun getLastStartErrorDetails(): String? = lastStartErrorDetails
    var supportsImagePrompt: Boolean = false
        private set

    fun start(): Boolean {
        try {
            lastStartErrorDetails = null
            val isWindows = System.getProperty("os.name").lowercase().contains("win")
            val resolved = resolveAgentCommand(agentPath, isWindows)
            val command = mutableListOf<String>()
            if (isWindows && (resolved.executable.endsWith(".cmd") || resolved.executable.endsWith(".bat"))) {
                command.addAll(listOf("cmd", "/c"))
            }
            command.add(resolved.executable)
            apiKey?.let { command.addAll(listOf("--api-key", it)) }
            authToken?.let { command.addAll(listOf("--auth-token", it)) }
            endpoint?.let { command.addAll(listOf("-e", it)) }

            command.add("acp")

            log.info("Starting ACP agent from ${resolved.source}: ${command.joinToString(" ")}")

            val pb = ProcessBuilder(command)
            pb.redirectErrorStream(false)
            process = pb.start()

            writer = OutputStreamWriter(process!!.outputStream, Charsets.UTF_8)

            readerJob = scope.launch {
                val reader = BufferedReader(InputStreamReader(process!!.inputStream, Charsets.UTF_8))
                try {
                    var line: String?
                    while (reader.readLine().also { line = it } != null) {
                        if (!isActive) break
                        handleMessage(line!!)
                    }
                } catch (e: Exception) {
                    if (isActive) {
                        log.warn("ACP reader error", e)
                    }
                } finally {
                    onDisconnected?.invoke()
                }
            }

            scope.launch {
                val errReader = BufferedReader(InputStreamReader(process!!.errorStream, Charsets.UTF_8))
                try {
                    var line: String?
                    while (errReader.readLine().also { line = it } != null) {
                        log.info("[agent stderr] $line")
                    }
                } catch (_: Exception) {}
            }

            return true
        } catch (e: Exception) {
            val pathEnv = System.getenv("PATH").orEmpty()
            val rawConfig = agentPath.ifBlank { "<empty>" }
            val message = buildString {
                append("Failed to start ACP agent.")
                append("\nConfigured agent path: $rawConfig")
                append("\nOS: ${System.getProperty("os.name")}")
                append("\nPATH: $pathEnv")
                append("\nCause: ${e.message ?: e.javaClass.simpleName}")
            }
            lastStartErrorDetails = message
            log.error("Failed to start ACP agent", e)
            return false
        }
    }

    private fun resolveAgentCommand(configuredPath: String, isWindows: Boolean): ResolvedAgentCommand {
        val attempts = mutableListOf<String>()

        val normalizedConfigured = configuredPath.trim()
        if (normalizedConfigured.isNotEmpty()) {
            attempts.add("settings:$normalizedConfigured")
            resolveDirectPath(normalizedConfigured, isWindows)?.let {
                return ResolvedAgentCommand(it, "settings", attempts)
            }
        }

        val envPath = System.getenv("CURSOR_AGENT_PATH")?.trim().orEmpty()
        if (envPath.isNotEmpty()) {
            attempts.add("env:CURSOR_AGENT_PATH=$envPath")
            resolveDirectPath(envPath, isWindows)?.let {
                return ResolvedAgentCommand(it, "CURSOR_AGENT_PATH", attempts)
            }
        }

        val names = if (isWindows) listOf("agent.exe", "agent.cmd", "agent.bat", "agent") else listOf("agent")
        names.forEach { name ->
            attempts.add("path:$name")
            findInPath(name, isWindows)?.let {
                return ResolvedAgentCommand(it, "PATH", attempts)
            }
        }

        val commonPaths = if (isWindows) {
            listOf(
                "C:\\Program Files\\Cursor\\resources\\app\\bin\\agent.exe",
                "C:\\Users\\${System.getProperty("user.name")}\\AppData\\Local\\Programs\\Cursor\\resources\\app\\bin\\agent.exe"
            )
        } else {
            listOf(
                "/opt/homebrew/bin/agent",
                "/usr/local/bin/agent",
                "/Applications/Cursor.app/Contents/Resources/app/bin/agent"
            )
        }
        commonPaths.forEach { path ->
            attempts.add("common:$path")
            resolveDirectPath(path, isWindows)?.let {
                return ResolvedAgentCommand(it, "commonPath", attempts)
            }
        }

        throw IOException("Cannot find Cursor agent binary. Attempts: ${attempts.joinToString(", ")}")
    }

    private fun resolveDirectPath(path: String, isWindows: Boolean): String? {
        val file = File(path)
        if (file.isFile && (isWindows || file.canExecute())) {
            return file.absolutePath
        }
        return null
    }

    private fun findInPath(name: String, isWindows: Boolean): String? {
        val pathEnv = System.getenv("PATH") ?: return null
        val dirs = pathEnv.split(File.pathSeparator).filter { it.isNotBlank() }
        for (dir in dirs) {
            val candidate = File(dir, name)
            if (candidate.isFile && (isWindows || candidate.canExecute())) {
                return candidate.absolutePath
            }
        }
        return null
    }

    fun stop() {
        readerJob?.cancel()
        writer?.close()
        process?.destroyForcibly()
        process = null
        pendingRequests.values.forEach { it.cancel() }
        pendingRequests.clear()
    }

    fun destroy() {
        stop()
        scope.cancel()
    }

    private fun handleMessage(line: String) {
        try {
            val msg = gson.fromJson(line, JsonRpcIncoming::class.java)
            log.debug("Received: id=${msg.id}, method=${msg.method}")

            when {
                msg.isResponse -> {
                    val id = msg.id ?: return
                    val deferred = pendingRequests.remove(id) ?: return
                    if (msg.error != null) {
                        log.warn("ACP error id=$id: ${msg.error.message}")
                        deferred.completeExceptionally(
                            ACPException(msg.error.code, msg.error.message, msg.error.data)
                        )
                    } else {
                        deferred.complete(msg.result)
                    }
                }
                msg.isRequest -> {
                    handleIncomingRequest(msg.id!!, msg.method!!, msg.params)
                }
                msg.isNotification -> {
                    handleNotification(msg.method!!, msg.params)
                }
            }
        } catch (e: Exception) {
            log.warn("Failed to parse ACP message: $line", e)
        }
    }

    private fun handleIncomingRequest(id: Int, method: String, params: JsonElement?) {
        when (method) {
            "session/request_permission" -> {
                val req = gson.fromJson(params, PermissionRequest::class.java)
                onPermissionRequest?.invoke(id, req)
            }
            "fs/read_text_file" -> {
                val req = gson.fromJson(params, ReadTextFileRequest::class.java)
                onReadFileRequest?.invoke(id, req)
            }
            "fs/write_text_file" -> {
                val req = gson.fromJson(params, WriteTextFileRequest::class.java)
                onWriteFileRequest?.invoke(id, req)
            }
            "terminal/create" -> {
                val req = gson.fromJson(params, CreateTerminalRequest::class.java)
                onCreateTerminalRequest?.invoke(id, req)
            }
            "terminal/output" -> {
                val req = gson.fromJson(params, TerminalOutputRequest::class.java)
                onTerminalOutputRequest?.invoke(id, req)
            }
            "terminal/wait_for_exit" -> {
                val req = gson.fromJson(params, WaitForTerminalExitRequest::class.java)
                onWaitForExitRequest?.invoke(id, req)
            }
            "terminal/release" -> {
                val req = gson.fromJson(params, ReleaseTerminalRequest::class.java)
                onReleaseTerminalRequest?.invoke(id, req)
            }
            "terminal/kill" -> {
                val req = gson.fromJson(params, KillTerminalRequest::class.java)
                onKillTerminalRequest?.invoke(id, req)
            }
            else -> {
                log.warn("Unknown incoming request method: $method")
                respondError(id, -32601, "Method not found: $method")
            }
        }
    }

    private fun handleNotification(method: String, params: JsonElement?) {
        when (method) {
            "session/update" -> {
                val obj = params?.asJsonObject ?: return
                val updateJson = obj.get("update")
                val updateType = updateJson?.asJsonObject?.get("sessionUpdate")?.asString
                if (updateType != "agent_message_chunk" && updateType != "agent_thought_chunk") {
                    log.info("session/update type=$updateType raw=${updateJson.toString().take(500)}")
                }
                val update = gson.fromJson(updateJson, SessionUpdateContent::class.java)
                onSessionUpdate?.invoke(update)
            }
            else -> {
                log.info("Unhandled notification: $method")
            }
        }
    }

    suspend fun sendRequest(method: String, params: Any? = null): JsonElement? {
        val id = nextId.getAndIncrement()
        val paramsJson = if (params != null) gson.toJsonTree(params) else null
        val request = JsonRpcRequest(id = id, method = method, params = paramsJson)

        val deferred = CompletableDeferred<JsonElement?>()
        pendingRequests[id] = deferred

        val json = gson.toJson(request) + "\n"
        if (method.startsWith("session/")) {
            log.info("Sending [$method] id=$id params=${paramsJson?.toString()?.take(500)}")
        } else {
            log.debug("Sending [$method] id=$id")
        }
        synchronized(this) {
            writer?.write(json)
            writer?.flush()
        }

        return deferred.await()
    }

    fun respond(id: Int, result: Any?) {
        val resultJson = if (result != null) gson.toJsonTree(result) else JsonObject()
        val response = JsonRpcResponse(id = id, result = resultJson)
        val json = gson.toJson(response) + "\n"
        synchronized(this) {
            writer?.write(json)
            writer?.flush()
        }
    }

    fun respondError(id: Int, code: Int, message: String) {
        val response = mapOf(
            "jsonrpc" to "2.0",
            "id" to id,
            "error" to mapOf("code" to code, "message" to message)
        )
        val json = gson.toJson(response) + "\n"
        synchronized(this) {
            writer?.write(json)
            writer?.flush()
        }
    }

    fun sendNotification(method: String, params: Any? = null) {
        val paramsJson = if (params != null) gson.toJsonTree(params) else null
        val notification = JsonRpcNotification(method = method, params = paramsJson)
        val json = gson.toJson(notification) + "\n"
        synchronized(this) {
            writer?.write(json)
            writer?.flush()
        }
    }

    var supportsLoadSession: Boolean = false
        private set

    suspend fun initialize(): JsonElement? {
        val result = sendRequest("initialize", InitializeParams())
        try {
            val caps = result?.asJsonObject?.getAsJsonObject("agentCapabilities")
            supportsLoadSession = caps?.get("loadSession")?.asBoolean == true
            supportsImagePrompt = caps
                ?.getAsJsonObject("promptCapabilities")
                ?.get("image")
                ?.asBoolean == true
            log.info("initialize result capabilities: loadSession=$supportsLoadSession, imagePrompt=$supportsImagePrompt, raw=${result?.toString()?.take(500)}")
        } catch (e: Exception) {
            log.warn("Failed to parse initialize capabilities: ${e.message}")
        }
        return result
    }

    suspend fun authenticate(): JsonElement? {
        return sendRequest("authenticate", AuthenticateParams())
    }

    suspend fun newSession(cwd: String): NewSessionResult? {
        val result = sendRequest("session/new", NewSessionParams(cwd = cwd))
        return result?.let { gson.fromJson(it, NewSessionResult::class.java) }
    }

    suspend fun loadSession(sessionId: String, cwd: String): NewSessionResult? {
        val result = sendRequest("session/load", LoadSessionParams(sessionId = sessionId, cwd = cwd))
        if (result == null || result.isJsonNull) {
            return NewSessionResult(sessionId = null)
        }
        return gson.fromJson(result, NewSessionResult::class.java)
    }

    suspend fun prompt(sessionId: String, prompt: List<ContentBlock>): PromptResult? {
        val params = PromptParams(sessionId = sessionId, prompt = prompt)
        val result = sendRequest("session/prompt", params)
        return result?.let { gson.fromJson(it, PromptResult::class.java) }
    }

    suspend fun setConfigOption(sessionId: String, configId: String, value: String): SetConfigOptionResult? {
        val params = SetConfigOptionParams(sessionId = sessionId, configId = configId, value = value)
        val result = sendRequest("session/set_config_option", params)
        return result?.let { gson.fromJson(it, SetConfigOptionResult::class.java) }
    }

    fun cancel(sessionId: String) {
        sendNotification("session/cancel", CancelParams(sessionId))
    }
}

class ACPException(
    val code: Int,
    override val message: String,
    val data: JsonElement? = null
) : Exception(message)
