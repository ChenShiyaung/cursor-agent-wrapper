package com.cursor.agent.acp

import com.google.gson.JsonElement

data class JsonRpcRequest(
    val jsonrpc: String = "2.0",
    val id: Int,
    val method: String,
    val params: JsonElement? = null
)

data class JsonRpcNotification(
    val jsonrpc: String = "2.0",
    val method: String,
    val params: JsonElement? = null
)

data class JsonRpcResponse(
    val jsonrpc: String = "2.0",
    val id: Int,
    val result: JsonElement? = null,
    val error: JsonRpcError? = null
)

data class JsonRpcError(
    val code: Int,
    val message: String,
    val data: JsonElement? = null
)

data class JsonRpcIncoming(
    val jsonrpc: String?,
    val id: Int?,
    val method: String?,
    val params: JsonElement?,
    val result: JsonElement?,
    val error: JsonRpcError?
) {
    val isResponse: Boolean get() = method == null && (result != null || error != null)
    val isRequest: Boolean get() = method != null && id != null
    val isNotification: Boolean get() = method != null && id == null
}
