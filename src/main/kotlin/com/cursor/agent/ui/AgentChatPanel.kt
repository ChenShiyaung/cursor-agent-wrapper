package com.cursor.agent.ui

import com.cursor.agent.acp.ToolCallInfo
import com.cursor.agent.services.AgentSessionManager
import com.cursor.agent.services.AgentStatus
import com.cursor.agent.services.ChatHistoryService
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.*
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.util.concurrent.ConcurrentHashMap
import javax.swing.*

class AgentChatPanel(private val project: Project, private val parentDisposable: Disposable) : JPanel(BorderLayout()) {
    private val log = Logger.getInstance(AgentChatPanel::class.java)
    private val inputArea: JBTextArea
    private val sendButton: JButton
    private val cancelButton: JButton
    private val statusLabel: JBLabel
    private val modelLabel: JBLabel
    private val currentAgentMessage = StringBuilder()
    private val currentThought = StringBuilder()
    private var isThinking = false
    private var hasWelcome = false

    private val toolCallElements = ConcurrentHashMap<String, ToolCallInfo>()
    private val toolCallOrder = mutableListOf<String>()

    private data class ChatEntry(val role: String, val text: String)
    private val chatHistory = mutableListOf<ChatEntry>()

    private val chatRenderer: ChatRenderer

    private val sessionManager: AgentSessionManager
        get() = project.getService(AgentSessionManager::class.java)

    private fun isDark(): Boolean = !JBColor.isBright()

    private fun fontSize(): Int {
        return try {
            val uiSize = UIUtil.getLabelFont().size
            val editorSize = EditorColorsManager.getInstance().globalScheme.editorFontSize
            maxOf(uiSize, editorSize, 12)
        } catch (_: Exception) { 14 }
    }

    init {
        border = JBUI.Borders.empty(4)

        // ── Top toolbar ──
        val toolbar = JPanel(FlowLayout(FlowLayout.LEFT, 4, 2))
        val historyButton = JButton("History").apply { addActionListener { showSessionHistory() } }
        val newChatButton = JButton("New Chat").apply {
            addActionListener {
                clearChat()
                sessionManager.disconnect()
                com.cursor.agent.settings.AgentSettings.getInstance().state.lastSessionId = ""
                sessionManager.connect()
            }
        }
        cancelButton = JButton("Cancel").apply {
            isEnabled = false
            addActionListener { sessionManager.cancelCurrentPrompt() }
        }
        val reconnectButton = JButton("Reconnect").apply {
            addActionListener { sessionManager.disconnect(); sessionManager.connect() }
        }
        statusLabel = JBLabel("Disconnected").apply { foreground = JBColor.GRAY }
        toolbar.add(historyButton)
        toolbar.add(newChatButton)
        toolbar.add(cancelButton)
        toolbar.add(reconnectButton)
        toolbar.add(Box.createHorizontalStrut(4))
        toolbar.add(statusLabel)

        // ── Chat renderer ──
        chatRenderer = try {
            JcefChatRenderer(parentDisposable)
        } catch (e: Throwable) {
            log.warn("JCEF not available, falling back to JTextPane: ${e.message}")
            TextPaneChatRenderer()
        }

        // ── Model label ──
        val savedModel = com.cursor.agent.settings.AgentSettings.getInstance().state.selectedModel
        modelLabel = JBLabel(truncateModelName(formatModelDisplay(savedModel)) + " \u25BE").apply {
            foreground = if (isDark()) Color(0x6c, 0xb6, 0xff) else Color(0x15, 0x65, 0xc0)
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            toolTipText = savedModel.ifEmpty { "Auto" }
            addMouseListener(object : java.awt.event.MouseAdapter() {
                override fun mouseClicked(e: java.awt.event.MouseEvent) { showModelSelector() }
            })
        }

        // ── Input area ──
        inputArea = JBTextArea(3, 0).apply {
            lineWrap = true
            wrapStyleWord = true
            font = UIUtil.getLabelFont().deriveFont(fontSize().toFloat())
            border = JBUI.Borders.empty(6)
            emptyText.text = "Ask Cursor Agent... (Enter to send, Shift+Enter for newline)"
        }
        inputArea.addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                if (e.keyCode == KeyEvent.VK_ENTER) {
                    if (e.isShiftDown) {
                        e.consume()
                        inputArea.insert("\n", inputArea.caretPosition)
                    } else {
                        e.consume()
                        onSend()
                    }
                }
            }
        })

        sendButton = JButton("Send").apply { addActionListener { onSend() } }

        // ── Bottom bar: model left, send right ──
        val bottomBar = JPanel(BorderLayout(4, 0)).apply {
            border = JBUI.Borders.empty(4, 6, 4, 6)
            add(modelLabel, BorderLayout.WEST)
            val rightPanel = JPanel(FlowLayout(FlowLayout.RIGHT, 4, 0))
            rightPanel.add(cancelButton)
            rightPanel.add(sendButton)
            add(rightPanel, BorderLayout.EAST)
        }

        // ── Compose input + bottom bar with shared rounded border ──
        val inputScrollPane = JBScrollPane(inputArea).apply {
            verticalScrollBarPolicy = JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED
            horizontalScrollBarPolicy = JScrollPane.HORIZONTAL_SCROLLBAR_NEVER
            preferredSize = Dimension(0, 72)
            border = JBUI.Borders.empty()
        }

        val composerPanel = object : JPanel(BorderLayout()) {
            init { isOpaque = false }
            override fun paintComponent(g: Graphics) {
                val g2 = g.create() as Graphics2D
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                val bgColor = if (isDark()) Color(0x2b, 0x2b, 0x2b) else Color(0xf7, 0xf7, 0xf8)
                g2.color = bgColor
                g2.fillRoundRect(0, 0, width, height, 12, 12)
                val borderC = if (isDark()) Color(0x44, 0x44, 0x44) else Color(0xd0, 0xd0, 0xd0)
                g2.color = borderC
                g2.drawRoundRect(0, 0, width - 1, height - 1, 12, 12)
                g2.dispose()
            }
        }.apply {
            border = JBUI.Borders.empty(2)
            add(inputScrollPane, BorderLayout.CENTER)
            add(bottomBar, BorderLayout.SOUTH)
        }

        inputArea.isOpaque = false
        inputScrollPane.isOpaque = false
        inputScrollPane.viewport.isOpaque = false
        bottomBar.isOpaque = false

        add(toolbar, BorderLayout.NORTH)
        add(chatRenderer.component, BorderLayout.CENTER)
        add(composerPanel, BorderLayout.SOUTH)

        setupSessionCallbacks()
        updateStatus(AgentStatus.DISCONNECTED)

        val savedHistory = sessionManager.loadChatHistory()
        if (savedHistory.isNotEmpty()) {
            deserializeHistory(savedHistory)
            renderFullPage()
        } else {
            hasWelcome = true
            renderFullPage()
        }
    }

    // ───── Session callbacks ─────

    private fun setupSessionCallbacks() {
        val manager = sessionManager

        manager.onStatusChanged = { status -> SwingUtilities.invokeLater { updateStatus(status) } }

        manager.onModelChanged = { modelId ->
            SwingUtilities.invokeLater {
                modelLabel.text = truncateModelName(formatModelDisplay(modelId)) + " \u25BE"
                modelLabel.toolTipText = modelId
            }
        }

        manager.onThoughtChunk = { chunk ->
            SwingUtilities.invokeLater {
                isThinking = true
                currentThought.append(chunk)
                renderFullPage()
            }
        }

        manager.onMessageChunk = { chunk ->
            SwingUtilities.invokeLater {
                currentAgentMessage.append(chunk)
                renderFullPage()
            }
        }

        manager.onToolCallUpdate = { info ->
            SwingUtilities.invokeLater {
                if (!toolCallElements.containsKey(info.toolCallId)) toolCallOrder.add(info.toolCallId)
                toolCallElements[info.toolCallId] = info
                renderFullPage()
            }
        }

        manager.onPromptFinished = { stopReason ->
            SwingUtilities.invokeLater {
                finalizeAgentMessage()
                if (stopReason == "cancelled") chatHistory.add(ChatEntry("system", "Request cancelled."))
                persistChatHistory()
                renderFullPage()
            }
        }

        manager.onPermissionNeeded = { _, title, detail, callback ->
            SwingUtilities.invokeLater { showPermissionDialog(title, detail, callback) }
        }
    }

    // ───── Send ─────

    private fun onSend() {
        val text = inputArea.text.trim()
        if (text.isEmpty()) return
        if (sessionManager.status != AgentStatus.READY) {
            if (sessionManager.status == AgentStatus.DISCONNECTED) sessionManager.connect()
            return
        }
        inputArea.text = ""
        if (hasWelcome) { chatHistory.clear(); hasWelcome = false }
        chatHistory.add(ChatEntry("user", text))
        currentAgentMessage.clear()
        currentThought.clear()
        isThinking = false
        toolCallElements.clear()
        toolCallOrder.clear()
        renderFullPage()
        sessionManager.sendPrompt(text)
    }

    // ───── Finalize ─────

    private fun finalizeAgentMessage() {
        if (currentThought.isNotEmpty()) {
            chatHistory.add(ChatEntry("thought", currentThought.toString()))
        }
        if (currentAgentMessage.isNotEmpty()) {
            chatHistory.add(ChatEntry("assistant", currentAgentMessage.toString()))
        }
        for (tcId in toolCallOrder) {
            val info = toolCallElements[tcId] ?: continue
            val icon = when (info.status) { "completed" -> "\u2713"; "error" -> "\u2717"; else -> "\u25cf" }
            val title = info.title ?: info.kind ?: "tool call"
            val detail = extractToolCallDetail(info)
            chatHistory.add(ChatEntry("tool_call", "$icon $title${if (detail.isNotEmpty()) " $detail" else ""} [${info.status ?: "done"}]"))
        }
        currentAgentMessage.clear()
        currentThought.clear()
        isThinking = false
        toolCallElements.clear()
        toolCallOrder.clear()
    }

    // ───── HTML page building ─────

    private fun renderFullPage() {
        val html = buildFullHtml()
        chatRenderer.setHtml(html)
    }

    private fun buildFullHtml(): String {
        val dark = isDark()
        val fs = fontSize()
        val bg = colorHex(UIUtil.getPanelBackground())
        val fg = colorHex(UIUtil.getLabelForeground())
        val secondaryFg = if (dark) "#999" else "#666"
        val userBubble = if (dark) "#2b3d50" else "#e3f2fd"
        val agentBubble = if (dark) "#2d2d2d" else "#f5f5f5"
        val codeBg = if (dark) "#1a1a1a" else "#f4f4f4"
        val codeFg = if (dark) "#d4d4d4" else "#333"
        val userNameC = if (dark) "#6cb6ff" else "#1565c0"
        val agentNameC = if (dark) "#81c784" else "#2e7d32"
        val thoughtC = if (dark) "#c4b5fd" else "#6d28d9"
        val thoughtBg = if (dark) "#1a1a2e" else "#f9f5ff"
        val thoughtBorder = if (dark) "#7c3aed" else "#a78bfa"
        val toolC = if (dark) "#4a7fff" else "#2962ff"
        val toolBg = if (dark) "#1a2636" else "#f0f4ff"
        val toolBorder = if (dark) "#4a7fff" else "#4a7fff"
        val borderColor = if (dark) "#444" else "#ddd"

        val sb = StringBuilder()
        sb.append("""<!DOCTYPE html><html><head><meta charset="utf-8"><style>
* { box-sizing: border-box; }
body { font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif; font-size: ${fs}px; color: $fg; background: $bg; margin: 0; padding: 8px; line-height: 1.5; }
.welcome { text-align: center; padding: 30px 10px; color: $secondaryFg; }
.welcome h2 { color: $fg; margin: 0 0 8px 0; }
.welcome p { margin: 4px 0; }
.user-bubble { background: $userBubble; padding: 8px 12px; margin: 8px 0; border-radius: 6px; }
.agent-bubble { background: $agentBubble; padding: 8px 12px; margin: 8px 0; border-radius: 6px; }
.user-name { color: $userNameC; font-weight: bold; margin-bottom: 4px; }
.agent-name { color: $agentNameC; font-weight: bold; margin-bottom: 4px; }
.thought { background: $thoughtBg; border-left: 3px solid $thoughtBorder; padding: 6px 10px; margin: 4px 0; color: $thoughtC; font-size: ${fs - 1}px; border-radius: 0 4px 4px 0; }
.thought-label { font-weight: bold; }
.tool-call { background: $toolBg; border-left: 3px solid $toolBorder; padding: 4px 8px; margin: 2px 0; color: $toolC; font-size: ${fs - 1}px; border-radius: 0 4px 4px 0; }
.system { text-align: center; color: $secondaryFg; font-style: italic; font-size: ${fs - 1}px; margin: 8px 0; }
pre { background: $codeBg; color: $codeFg; padding: 10px 12px; border-radius: 4px; overflow-x: auto; font-family: 'JetBrains Mono', Consolas, 'Courier New', monospace; font-size: ${fs - 1}px; line-height: 1.4; border: 1px solid $borderColor; }
code { font-family: 'JetBrains Mono', Consolas, 'Courier New', monospace; font-size: ${fs - 1}px; }
:not(pre) > code { background: $codeBg; color: $codeFg; padding: 1px 4px; border-radius: 3px; }
table { border-collapse: collapse; margin: 6px 0; width: 100%; }
th, td { border: 1px solid $borderColor; padding: 6px 10px; text-align: left; }
th { background: $codeBg; font-weight: bold; }
blockquote { border-left: 3px solid $borderColor; padding-left: 10px; margin: 6px 0; color: $secondaryFg; }
a { color: $userNameC; }
h1, h2, h3, h4, h5, h6 { margin: 12px 0 6px 0; }
p { margin: 4px 0; }
ul, ol { padding-left: 20px; margin: 4px 0; }
img { max-width: 100%; }
::-webkit-scrollbar { width: 8px; height: 8px; }
::-webkit-scrollbar-track { background: transparent; }
::-webkit-scrollbar-thumb { background: $borderColor; border-radius: 4px; }
::-webkit-scrollbar-thumb:hover { background: $secondaryFg; }
::-webkit-scrollbar-corner { background: transparent; }
</style></head><body>
""")

        if (hasWelcome && chatHistory.isEmpty()) {
            sb.append("""<div class="welcome"><h2>Cursor Agent</h2><p>Agent will auto-connect when the project opens.</p><p style="font-size:${fs - 2}px;">Configure the agent binary path in Settings &gt; Tools &gt; Cursor Agent</p></div>""")
        }

        for (entry in chatHistory) {
            when (entry.role) {
                "user" -> {
                    sb.append("""<div class="user-bubble"><div class="user-name">You</div>""")
                    sb.append(MessageRenderer.renderMarkdown(entry.text))
                    sb.append("</div>")
                }
                "assistant" -> {
                    sb.append("""<div class="agent-bubble"><div class="agent-name">Agent</div>""")
                    sb.append(MessageRenderer.renderMarkdown(entry.text))
                    sb.append("</div>")
                }
                "thought" -> {
                    sb.append("""<div class="thought"><span class="thought-label">Thought</span><br/>""")
                    sb.append(truncateThought(entry.text))
                    sb.append("</div>")
                }
                "tool_call" -> {
                    sb.append("""<div class="tool-call">${MessageRenderer.escapeHtml(entry.text)}</div>""")
                }
                "system" -> {
                    sb.append("""<div class="system">${MessageRenderer.escapeHtml(entry.text)}</div>""")
                }
            }
        }

        // Live streaming content
        if (currentThought.isNotEmpty()) {
            val label = if (isThinking) "\u25cf Thinking..." else "Thought"
            sb.append("""<div class="thought"><span class="thought-label">$label</span><br/>""")
            sb.append(truncateThought(currentThought.toString()))
            sb.append("</div>")
        }

        if (currentAgentMessage.isNotEmpty()) {
            sb.append("""<div class="agent-bubble"><div class="agent-name">Agent</div>""")
            sb.append(MessageRenderer.renderMarkdown(currentAgentMessage.toString()))
            sb.append("</div>")
        }

        if (toolCallOrder.isNotEmpty()) {
            for (tcId in toolCallOrder) {
                val info = toolCallElements[tcId] ?: continue
                val icon = when (info.status) { "in_progress" -> "\u25b6"; "completed" -> "\u2713"; "error" -> "\u2717"; else -> "\u25cf" }
                val title = info.title ?: info.kind ?: "tool call"
                val detail = extractToolCallDetail(info)
                val detailStr = if (detail.isNotEmpty()) " $detail" else ""
                sb.append("""<div class="tool-call">${MessageRenderer.escapeHtml("$icon $title$detailStr [${info.status ?: "pending"}]")}</div>""")
            }
        }

        sb.append("<script>window.scrollTo(0, document.body.scrollHeight);</script></body></html>")
        return sb.toString()
    }

    private fun truncateThought(thought: String): String {
        val lines = thought.trimEnd().lines()
        return if (lines.size <= 1) {
            MessageRenderer.escapeHtml(lines.firstOrNull() ?: "")
        } else {
            "... " + MessageRenderer.escapeHtml(lines.last())
        }
    }

    private fun formatModelDisplay(acpValue: String): String {
        if (acpValue.isEmpty() || acpValue == "default[]") return "Auto"
        val modelOpt = sessionManager.getModelConfigOption()
        val name = modelOpt?.options?.find { it.value == acpValue }?.name
        if (name != null) return name

        val base = acpValue.substringBefore("[")
        val params = acpValue.substringAfter("[", "").trimEnd(']')
        if (params.isEmpty()) return base

        val tags = mutableListOf<String>()
        for (kv in params.split(",")) {
            val parts = kv.trim().split("=", limit = 2)
            if (parts.size != 2) continue
            val (key, value) = parts
            if (key == "effort") continue
            when {
                value == "true" -> tags.add(key)
                value == "false" -> {}
                else -> tags.add(value)
            }
        }
        return if (tags.isEmpty()) base else "$base ${tags.joinToString(" ")}"
    }

    private fun truncateModelName(name: String): String =
        if (name.length > 22) name.take(20) + "\u2026" else name

    private fun colorHex(c: Color): String = String.format("#%02x%02x%02x", c.red, c.green, c.blue)

    // ───── Tool call detail ─────

    private fun extractToolCallDetail(info: ToolCallInfo): String {
        val input = info.input ?: return ""
        return try {
            val obj = input.asJsonObject
            val parts = mutableListOf<String>()
            obj.get("path")?.asString?.let { parts.add(shortenPath(it)) }
            obj.get("command")?.asString?.let { parts.add(it.take(80)) }
            obj.get("pattern")?.asString?.let { parts.add("\"$it\"") }
            obj.get("glob_pattern")?.asString?.let { parts.add(it) }
            obj.get("regex")?.asString?.let { parts.add("/$it/") }
            obj.get("search_term")?.asString?.let { parts.add("\"$it\"") }
            obj.get("description")?.asString?.let { parts.add(it.take(60)) }
            obj.get("url")?.asString?.let { parts.add(it.take(80)) }
            obj.get("old_string")?.asString?.let { parts.add("replace ${it.take(30)}...") }
            parts.joinToString(" | ")
        } catch (_: Exception) { "" }
    }

    private fun shortenPath(path: String): String {
        val basePath = project.basePath ?: return path
        val n = path.replace("\\", "/")
        val nb = basePath.replace("\\", "/")
        return if (n.startsWith(nb)) ".${n.removePrefix(nb)}"
        else { val p = n.split("/"); if (p.size > 3) ".../${p.takeLast(3).joinToString("/")}" else path }
    }

    // ───── Permission ─────

    private fun showPermissionDialog(title: String, detail: String, callback: (Boolean) -> Unit) {
        val msg = buildString {
            append("Agent wants to execute:\n\n$title")
            if (detail.isNotBlank()) append("\n\n$detail")
            append("\n\nAllow this operation?")
        }
        val result = JOptionPane.showConfirmDialog(this, msg, "Permission Required", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE)
        callback(result == JOptionPane.YES_OPTION)
    }

    // ───── Clear / History ─────

    private fun clearChat() {
        chatHistory.clear()
        currentAgentMessage.clear()
        currentThought.clear()
        isThinking = false
        toolCallElements.clear()
        toolCallOrder.clear()
        sessionManager.saveChatHistory("")
        hasWelcome = true
        renderFullPage()
    }

    private fun showModelSelector() {
        val modelOpt = sessionManager.getModelConfigOption()
        if (modelOpt == null || modelOpt.options.isNullOrEmpty()) {
            JOptionPane.showMessageDialog(this, "No models available. Please connect first.", "Models", JOptionPane.INFORMATION_MESSAGE)
            return
        }

        val currentValue = sessionManager.currentModelId.ifEmpty { modelOpt.currentValue ?: "" }
        val popup = JPopupMenu()

        for (opt in modelOpt.options) {
            val isCurrent = opt.value == currentValue
            val displayName = opt.name ?: opt.value
            val item = JMenuItem("${if (isCurrent) "\u2713 " else "   "}$displayName")
            item.toolTipText = opt.value
            if (isCurrent) item.foreground = JBColor(Color(0x1565c0), Color(0x6cb6ff))
            item.addActionListener {
                sessionManager.switchModel(opt.value)
                chatHistory.add(ChatEntry("system", "Switched to model: $displayName"))
                renderFullPage()
            }
            popup.add(item)
        }
        popup.show(modelLabel, 0, -popup.preferredSize.height)
    }

    private fun showSessionHistory() {
        ApplicationManager.getApplication().executeOnPooledThread {
            val sessions = ChatHistoryService.listSessions()
            SwingUtilities.invokeLater {
                if (sessions.isEmpty()) {
                    JOptionPane.showMessageDialog(this, "No chat history found.", "History", JOptionPane.INFORMATION_MESSAGE)
                    return@invokeLater
                }
                val popup = JPopupMenu()
                for (session in sessions.take(20)) {
                    val preview = session.userMessagePreviews.firstOrNull() ?: ""
                    val label = "${session.formattedDate}  ${session.displayName}"
                    val item = JMenuItem("<html><b>${MessageRenderer.escapeHtml(label)}</b><br/><span style='color:gray;font-size:11px;'>${MessageRenderer.escapeHtml(preview.take(60))}</span></html>")
                    item.addActionListener { loadSession(session.chatId) }
                    popup.add(item)
                }
                val btn = this.components.flatMap {
                    if (it is JPanel) it.components.toList() else listOf(it)
                }.filterIsInstance<JButton>().find { it.text == "History" }
                popup.show(btn ?: this, btn?.let { 0 } ?: 10, btn?.height ?: 10)
            }
        }
    }

    private fun loadSession(chatId: String) {
        ApplicationManager.getApplication().executeOnPooledThread {
            val messages = ChatHistoryService.readSessionMessages(chatId)
            SwingUtilities.invokeLater {
                chatHistory.clear()
                currentAgentMessage.clear()
                toolCallElements.clear()
                toolCallOrder.clear()
                hasWelcome = false
                for (msg in messages) chatHistory.add(ChatEntry(msg.role, msg.content))
                renderFullPage()
                persistChatHistory()
                sessionManager.disconnect()
                com.cursor.agent.settings.AgentSettings.getInstance().state.lastSessionId = chatId
                sessionManager.connect()
            }
        }
    }

    // ───── Persistence ─────

    private fun persistChatHistory() {
        val sb = StringBuilder()
        for (entry in chatHistory) {
            val escaped = entry.text.replace("\\", "\\\\").replace("\n", "\\n").replace("\t", "\\t")
            sb.appendLine("${entry.role}\t$escaped")
        }
        sessionManager.saveChatHistory(sb.toString())
    }

    private fun deserializeHistory(data: String) {
        chatHistory.clear()
        if (data.isBlank()) return
        for (line in data.lines()) {
            if (line.isBlank()) continue
            val tab = line.indexOf('\t')
            if (tab < 0) continue
            val role = line.substring(0, tab)
            val text = line.substring(tab + 1).replace("\\n", "\n").replace("\\t", "\t").replace("\\\\", "\\")
            chatHistory.add(ChatEntry(role, text))
        }
    }

    // ───── Status ─────

    private fun updateStatus(status: AgentStatus) {
        when (status) {
            AgentStatus.DISCONNECTED -> {
                statusLabel.text = "Disconnected"; statusLabel.foreground = JBColor.GRAY
                sendButton.isEnabled = false; cancelButton.isEnabled = false; inputArea.isEnabled = false
            }
            AgentStatus.CONNECTING -> {
                statusLabel.text = "Connecting..."; statusLabel.foreground = JBColor.ORANGE
                sendButton.isEnabled = false; cancelButton.isEnabled = false; inputArea.isEnabled = false
            }
            AgentStatus.CONNECTED -> {
                statusLabel.text = "Connected"; statusLabel.foreground = JBColor.BLUE
                sendButton.isEnabled = false; cancelButton.isEnabled = false; inputArea.isEnabled = false
            }
            AgentStatus.READY -> {
                statusLabel.text = "Ready"; statusLabel.foreground = JBColor(Color(0x2e7d32), Color(0x81c784))
                sendButton.isEnabled = true; cancelButton.isEnabled = false; inputArea.isEnabled = true
                inputArea.requestFocusInWindow()
            }
            AgentStatus.THINKING -> {
                statusLabel.text = "Thinking..."; statusLabel.foreground = JBColor(Color(0x1565c0), Color(0x64b5f6))
                sendButton.isEnabled = false; cancelButton.isEnabled = true; inputArea.isEnabled = false
            }
        }
    }
}

// ───── Chat renderer abstraction ─────

interface ChatRenderer {
    val component: JComponent
    fun setHtml(html: String)
}

// ───── JCEF renderer ─────

class JcefChatRenderer(parentDisposable: Disposable) : ChatRenderer {
    private val browser: com.intellij.ui.jcef.JBCefBrowser

    init {
        if (!com.intellij.ui.jcef.JBCefApp.isSupported()) {
            throw UnsupportedOperationException("JCEF is not supported")
        }
        browser = com.intellij.ui.jcef.JBCefBrowser()
        Disposer.register(parentDisposable, browser)
    }

    override val component: JComponent get() = browser.component

    override fun setHtml(html: String) {
        browser.loadHTML(html)
    }
}

// ───── Fallback JTextPane renderer ─────

class TextPaneChatRenderer : ChatRenderer {
    private val textPane = JTextPane().apply {
        isEditable = false
        background = UIUtil.getPanelBackground()
        font = UIUtil.getLabelFont()
        caret = object : javax.swing.text.DefaultCaret() {
            override fun isVisible(): Boolean = false
            override fun isSelectionVisible(): Boolean = true
        }
    }
    private val scrollPane = JBScrollPane(textPane).apply {
        verticalScrollBarPolicy = JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED
        horizontalScrollBarPolicy = JScrollPane.HORIZONTAL_SCROLLBAR_NEVER
    }

    override val component: JComponent get() = scrollPane

    override fun setHtml(html: String) {
        val plain = html
            .replace(Regex("<style[^>]*>[\\s\\S]*?</style>"), "")
            .replace(Regex("<script[^>]*>[\\s\\S]*?</script>"), "")
            .replace(Regex("<br\\s*/?>"), "\n")
            .replace(Regex("</?p[^>]*>"), "\n")
            .replace(Regex("</?div[^>]*>"), "\n")
            .replace(Regex("</?h[1-6][^>]*>"), "\n")
            .replace(Regex("<[^>]+>"), "")
            .replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&")
            .replace("&quot;", "\"").replace("&#39;", "'")
            .replace(Regex("\n{3,}"), "\n\n")
            .trim()
        textPane.text = plain
        SwingUtilities.invokeLater {
            try { textPane.caretPosition = textPane.document.length } catch (_: Exception) {}
        }
    }
}
