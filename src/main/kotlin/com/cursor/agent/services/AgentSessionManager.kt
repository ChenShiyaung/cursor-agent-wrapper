package com.cursor.agent.services

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import java.security.MessageDigest

@Service(Service.Level.PROJECT)
class AgentSessionManager(private val project: Project) : Disposable {

    val workspaceHash: String
        get() {
            val cached = com.cursor.agent.settings.AgentSettings.getInstance().state.projectWorkspaceHashes[projectKey]
            if (!cached.isNullOrBlank()) return cached
            val path = project.basePath ?: ""
            val md5 = MessageDigest.getInstance("MD5")
            return md5.digest(path.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
        }

    val projectKey: String get() = project.basePath ?: ""

    override fun dispose() {}
}

data class TerminalSession(
    val process: Process?,
    val output: StringBuilder,
    val outputJob: kotlinx.coroutines.Job?
)

enum class AgentStatus {
    DISCONNECTED, CONNECTING, CONNECTED, READY, THINKING
}
