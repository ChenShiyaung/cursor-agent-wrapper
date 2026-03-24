package com.cursor.agent.services

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.intellij.openapi.diagnostic.Logger
import java.io.File
import java.sql.DriverManager
import java.text.SimpleDateFormat
import java.util.*

data class ChatSession(
    val chatId: String,
    val name: String,
    val createdAt: Long,
    val lastUsedModel: String?,
    val workspaceHash: String,
    val userMessagePreviews: List<String>
) {
    val formattedDate: String
        get() {
            val sdf = SimpleDateFormat("MM/dd HH:mm", Locale.getDefault())
            return sdf.format(Date(createdAt))
        }

    val displayName: String
        get() = if (name.isNotBlank()) name else "Chat ${chatId.take(8)}"
}

object ChatHistoryService {
    private val log = Logger.getInstance(ChatHistoryService::class.java)
    private val gson = Gson()

    init {
        try {
            Class.forName("org.sqlite.JDBC")
            log.info("SQLite JDBC driver loaded successfully")
        } catch (e: Exception) {
            log.error("Failed to load SQLite JDBC driver", e)
        }
    }

    private val chatsDir: File
        get() = File(System.getProperty("user.home"), ".cursor/chats")

    fun listSessions(filterWorkspaceHash: String? = null): List<ChatSession> {
        val dir = chatsDir
        if (!dir.exists()) return emptyList()

        val sessions = mutableListOf<ChatSession>()
        val workspaceDirs = if (filterWorkspaceHash != null) {
            val target = File(dir, filterWorkspaceHash)
            if (target.isDirectory) arrayOf(target) else return emptyList()
        } else {
            dir.listFiles { f -> f.isDirectory } ?: return emptyList()
        }

        for (wsDir in workspaceDirs) {
            val chatDirs = wsDir.listFiles { f -> f.isDirectory } ?: continue
            for (chatDir in chatDirs) {
                val dbFile = File(chatDir, "store.db")
                if (!dbFile.exists()) continue
                try {
                    val session = readSessionMeta(dbFile, wsDir.name)
                    if (session != null) sessions.add(session)
                } catch (e: Exception) {
                    log.warn("Failed to read chat DB: ${dbFile.absolutePath}: ${e.message}")
                }
            }
        }
        return sessions.sortedByDescending { it.createdAt }
    }

    fun readSessionMessages(chatId: String): List<ChatMessage> {
        val dbFile = findDbFile(chatId) ?: return emptyList()
        return readMessages(dbFile)
    }

    fun readSessionTitle(chatId: String): String? {
        val dbFile = findDbFile(chatId) ?: return null
        return try {
            val url = "jdbc:sqlite:${dbFile.absolutePath}"
            DriverManager.getConnection(url).use { conn ->
                val rs = conn.prepareStatement("SELECT value FROM meta WHERE key = '0'").executeQuery()
                if (!rs.next()) return null
                val rawValue = rs.getString("value")
                val jsonStr = if (rawValue.matches(Regex("^[0-9a-fA-F]+$"))) {
                    String(hexToBytes(rawValue), Charsets.UTF_8)
                } else rawValue
                val meta = gson.fromJson(jsonStr, JsonObject::class.java)
                val name = meta.get("name")?.asString
                if (!name.isNullOrBlank() && name != "New Agent") name else null
            }
        } catch (e: Exception) {
            log.warn("Failed to read session title for $chatId: ${e.message}")
            null
        }
    }

    fun deleteSession(chatId: String): Boolean {
        val dbFile = findDbFile(chatId) ?: return false
        val chatDir = dbFile.parentFile ?: return false
        return try {
            chatDir.deleteRecursively()
        } catch (e: Exception) {
            log.warn("Failed to delete session dir: ${chatDir.absolutePath}", e)
            false
        }
    }

    private fun findDbFile(chatId: String): File? {
        val dir = chatsDir
        if (!dir.exists()) return null
        val workspaceDirs = dir.listFiles { f -> f.isDirectory } ?: return null
        for (wsDir in workspaceDirs) {
            val chatDir = File(wsDir, chatId)
            val dbFile = File(chatDir, "store.db")
            if (dbFile.exists()) return dbFile
        }
        return null
    }

    private fun readSessionMeta(dbFile: File, workspaceHash: String): ChatSession? {
        val url = "jdbc:sqlite:${dbFile.absolutePath}"
        DriverManager.getConnection(url).use { conn ->
            val metaStmt = conn.prepareStatement("SELECT value FROM meta WHERE key = '0'")
            val rs = metaStmt.executeQuery()
            if (!rs.next()) return null

            val rawValue = rs.getString("value")
            val jsonStr = if (rawValue.matches(Regex("^[0-9a-fA-F]+$"))) {
                String(hexToBytes(rawValue), Charsets.UTF_8)
            } else {
                rawValue
            }

            val meta = gson.fromJson(jsonStr, JsonObject::class.java)
            val chatId = meta.get("agentId")?.asString ?: return null
            val name = meta.get("name")?.asString ?: ""
            val createdAt = meta.get("createdAt")?.asLong ?: 0
            val model = meta.get("lastUsedModel")?.asString

            val previews = readUserPreviews(conn, 3)

            return ChatSession(
                chatId = chatId,
                name = name,
                createdAt = createdAt,
                lastUsedModel = model,
                workspaceHash = workspaceHash,
                userMessagePreviews = previews
            )
        }
    }

    private fun readUserPreviews(conn: java.sql.Connection, limit: Int): List<String> {
        val previews = mutableListOf<String>()
        val stmt = conn.prepareStatement("SELECT data FROM blobs LIMIT 200")
        val rs = stmt.executeQuery()
        while (rs.next() && previews.size < limit) {
            try {
                val data = rs.getBytes("data")
                val str = String(data, Charsets.UTF_8)
                val obj = gson.fromJson(str, JsonObject::class.java)
                if (obj.get("role")?.asString == "user") {
                    val content = obj.get("content")
                    val text = when {
                        content == null -> continue
                        content.isJsonPrimitive -> content.asString
                        content.isJsonArray -> content.asJsonArray
                            .filter { it.isJsonObject }
                            .mapNotNull { it.asJsonObject.get("text")?.asString }
                            .joinToString("")
                        else -> continue
                    }
                    val clean = text.replace(Regex("<[^>]+>"), "").trim()
                    if (clean.isNotBlank() && !clean.startsWith("OS Version:") && !clean.startsWith("[Previous conversation")) {
                        previews.add(clean.take(80))
                    }
                }
            } catch (_: Exception) {}
        }
        return previews
    }

    private fun readMessages(dbFile: File): List<ChatMessage> {
        val messages = mutableListOf<ChatMessage>()
        val url = "jdbc:sqlite:${dbFile.absolutePath}"
        DriverManager.getConnection(url).use { conn ->
            val stmt = conn.prepareStatement("SELECT data FROM blobs")
            val rs = stmt.executeQuery()
            while (rs.next()) {
                try {
                    val data = rs.getBytes("data")
                    val str = String(data, Charsets.UTF_8)
                    val obj = gson.fromJson(str, JsonObject::class.java)
                    val role = obj.get("role")?.asString ?: continue
                    if (role != "user" && role != "assistant") continue

                    val content = obj.get("content")

                    if (role == "user") {
                        val text = extractText(content) ?: continue
                        val clean = text.replace(Regex("<[^>]+>"), "").trim()
                        if (clean.isNotBlank() && !clean.startsWith("OS Version:") && !clean.startsWith("[Previous conversation")) {
                            messages.add(ChatMessage("user", clean))
                        }
                    } else {
                        if (content != null && content.isJsonArray) {
                            val thinking = StringBuilder()
                            val reply = StringBuilder()
                            for (block in content.asJsonArray) {
                                if (!block.isJsonObject) continue
                                val blockObj = block.asJsonObject
                                val type = blockObj.get("type")?.asString
                                val text = blockObj.get("text")?.asString ?: ""
                                when (type) {
                                    "thinking", "reasoning" -> thinking.append(text)
                                    "text" -> {
                                        val (thinkPart, replyPart) = splitThinkTags(text)
                                        thinking.append(thinkPart)
                                        reply.append(replyPart)
                                    }
                                }
                            }
                            if (thinking.isNotBlank()) {
                                messages.add(ChatMessage("thought", thinking.toString()))
                            }
                            if (reply.isNotBlank()) {
                                messages.add(ChatMessage("assistant", reply.toString()))
                            }
                        } else {
                            val text = extractText(content) ?: continue
                            if (text.isNotBlank()) {
                                val (thinkPart, replyPart) = splitThinkTags(text)
                                if (thinkPart.isNotBlank()) messages.add(ChatMessage("thought", thinkPart))
                                if (replyPart.isNotBlank()) messages.add(ChatMessage("assistant", replyPart))
                            }
                        }
                    }
                } catch (_: Exception) {}
            }
        }
        return messages
    }

    private fun splitThinkTags(text: String): Pair<String, String> {
        val thinkRegex = Regex("<think>([\\s\\S]*?)</think>", RegexOption.DOT_MATCHES_ALL)
        val thinkParts = thinkRegex.findAll(text).map { it.groupValues[1] }.joinToString("\n")
        val replyPart = text.replace(thinkRegex, "").trim()
        return Pair(thinkParts, replyPart)
    }

    private fun extractText(content: com.google.gson.JsonElement?): String? {
        return when {
            content == null -> null
            content.isJsonPrimitive -> content.asString
            content.isJsonArray -> content.asJsonArray
                .filter { it.isJsonObject }
                .mapNotNull { it.asJsonObject.get("text")?.asString }
                .joinToString("")
            else -> null
        }
    }

    private fun hexToBytes(hex: String): ByteArray {
        val len = hex.length
        val data = ByteArray(len / 2)
        var i = 0
        while (i < len) {
            data[i / 2] = ((Character.digit(hex[i], 16) shl 4) + Character.digit(hex[i + 1], 16)).toByte()
            i += 2
        }
        return data
    }
}

data class ChatMessage(
    val role: String,
    val content: String
)
