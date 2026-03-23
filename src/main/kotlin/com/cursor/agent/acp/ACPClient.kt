package com.cursor.agent.acp

import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.intellij.openapi.diagnostic.Logger
import kotlinx.coroutines.*
import java.io.BufferedReader
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
    private val log = Logger.getInstance(ACPClient::class.java)
    private val gson = Gson()
    private val nextId = AtomicInteger(1)
    private val pendingRequests = ConcurrentHashMap<Int, CompletableDeferred<JsonElement?>>()

    private var process: Process? = null
    private var writer: OutputStreamWriter? = null
    private var readerJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

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

    fun start(): Boolean {
        try {
            val command = mutableListOf<String>()
            val isWindows = System.getProperty("os.name").lowercase().contains("win")
            if (isWindows && (agentPath.endsWith(".cmd") || agentPath.endsWith(".bat") || !agentPath.contains("."))) {
                command.addAll(listOf("cmd", "/c"))
            }
            command.add(agentPath)
            apiKey?.let { command.addAll(listOf("--api-key", it)) }
            authToken?.let { command.addAll(listOf("--auth-token", it)) }
            endpoint?.let { command.addAll(listOf("-e", it)) }
            command.add("acp")

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

            // Read stderr in background for logging
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
            log.error("Failed to start ACP agent", e)
            return false
        }
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

            when {
                msg.isResponse -> {
                    val id = msg.id ?: return
                    val deferred = pendingRequests.remove(id) ?: return
                    if (msg.error != null) {
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
                val update = gson.fromJson(obj.get("update"), SessionUpdateContent::class.java)
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

    // High-level ACP operations

    suspend fun initialize(): JsonElement? {
        return sendRequest("initialize", InitializeParams())
    }

    suspend fun authenticate(): JsonElement? {
        return sendRequest("authenticate", AuthenticateParams())
    }

    suspend fun newSession(cwd: String): NewSessionResult? {
        val result = sendRequest("session/new", NewSessionParams(cwd = cwd))
        return result?.let { gson.fromJson(it, NewSessionResult::class.java) }
    }

    suspend fun loadSession(sessionId: String, cwd: String): LoadSessionResult? {
        val result = sendRequest("session/load", LoadSessionParams(sessionId = sessionId, cwd = cwd))
        return result?.let { gson.fromJson(it, LoadSessionResult::class.java) }
    }

    suspend fun listSessions(cwd: String? = null): ListSessionsResult? {
        val params = ListSessionsParams(cwd = cwd)
        val result = sendRequest("session/list", params)
        return result?.let { gson.fromJson(it, ListSessionsResult::class.java) }
    }

    suspend fun prompt(sessionId: String, text: String): PromptResult? {
        val params = PromptParams(
            sessionId = sessionId,
            prompt = listOf(ContentBlock(text = text))
        )
        val result = sendRequest("session/prompt", params)
        return result?.let { gson.fromJson(it, PromptResult::class.java) }
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
