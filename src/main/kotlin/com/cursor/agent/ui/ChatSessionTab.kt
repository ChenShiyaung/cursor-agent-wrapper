package com.cursor.agent.ui

import com.cursor.agent.acp.ConfigOptionValue
import com.cursor.agent.acp.ToolCallInfo
import com.cursor.agent.services.*
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
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

class ChatSessionTab(
    private val project: Project,
    private val parentDisposable: Disposable,
    initialChatId: String?,
    private val onTitleChanged: (ChatSessionTab, String) -> Unit
) : JPanel(BorderLayout()), Disposable {

    private val log = Logger.getInstance(ChatSessionTab::class.java)

    /**
     * Stable identifier for this session, used for history matching and tab persistence.
     * - From history: the DB chatId (never changes).
     * - New chat: null until agent creates a session in .cursor/chats, then set to that ID.
     */
    var chatId: String? = initialChatId
        private set

    val connection = AgentConnection(project, initialChatId)

    private val inputArea: JBTextArea
    private val sendButton: JButton
    private val cancelButton: JButton
    private val statusLabel: JBLabel
    private val modelLabel: JBLabel

    private val currentAgentMessage = StringBuilder()
    private val currentThought = StringBuilder()
    private var isThinking = false
    private var hasWelcome = false
    private var titleGenerated = false

    private val toolCallElements = ConcurrentHashMap<String, ToolCallInfo>()
    private val toolCallOrder = mutableListOf<String>()

    private val chatHistory = mutableListOf<ChatEntry>()
    private val htmlBuilder = ChatHtmlBuilder()
    private val chatRenderer: ChatRenderer

    private var cachedModelPopup: JPopupMenu? = null
    private var cachedModelValue: String? = null

    var tabTitle: String = "New Chat"

    fun setTitleManually(title: String) {
        tabTitle = title
        titleGenerated = true
    }

    init {
        border = JBUI.Borders.empty(4)

        statusLabel = JBLabel("Disconnected").apply { foreground = JBColor.GRAY }
        cancelButton = JButton("Cancel").apply {
            isEnabled = false
            addActionListener { connection.cancelPrompt() }
        }
        val reconnectButton = JButton("Reconnect").apply {
            addActionListener { connection.disconnect(); connection.connect() }
        }

        val toolbar = JPanel(FlowLayout(FlowLayout.LEFT, 4, 2))
        toolbar.add(reconnectButton)
        toolbar.add(Box.createHorizontalStrut(4))
        toolbar.add(statusLabel)

        chatRenderer = try {
            JcefChatRenderer(parentDisposable, project)
        } catch (e: Throwable) {
            log.warn("JCEF not available, falling back to JTextPane: ${e.message}")
            TextPaneChatRenderer()
        }

        val dark = htmlBuilder.isDark()
        val savedModel = com.cursor.agent.settings.AgentSettings.getInstance().state.selectedModel
        modelLabel = JBLabel(truncateModelName(formatModelDisplay(savedModel)) + " \u25BC").apply {
            foreground = if (dark) Color(0x6c, 0xb6, 0xff) else Color(0x15, 0x65, 0xc0)
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            toolTipText = savedModel.ifEmpty { "Auto" }
            addMouseListener(object : java.awt.event.MouseAdapter() {
                override fun mouseClicked(e: java.awt.event.MouseEvent) { showModelSelector() }
            })
        }

        inputArea = JBTextArea(3, 0).apply {
            lineWrap = true; wrapStyleWord = true
            font = UIUtil.getLabelFont().deriveFont(htmlBuilder.fontSize().toFloat())
            border = JBUI.Borders.empty(6)
            emptyText.text = "Ask Cursor Agent... (Enter to send, Shift+Enter for newline)"
        }
        inputArea.addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                if (e.keyCode == KeyEvent.VK_ENTER) {
                    if (e.isShiftDown) { e.consume(); inputArea.insert("\n", inputArea.caretPosition) }
                    else { e.consume(); onSend() }
                }
            }
        })
        sendButton = JButton("Send").apply { addActionListener { onSend() } }

        val composerPanel = buildComposerPanel()

        add(toolbar, BorderLayout.NORTH)
        add(chatRenderer.component, BorderLayout.CENTER)
        add(composerPanel, BorderLayout.SOUTH)

        setupCallbacks()
        updateStatus(AgentStatus.DISCONNECTED)

        if (chatId != null) {
            loadFromDb(chatId!!)
        } else {
            hasWelcome = true
            showConnectingOverlay()
        }

        connection.connect()
    }

    private fun loadFromDb(chatId: String) {
        ApplicationManager.getApplication().executeOnPooledThread {
            val messages = ChatHistoryService.readSessionMessages(chatId)
            val title = ChatHistoryService.readSessionTitle(chatId)
            SwingUtilities.invokeLater {
                chatHistory.clear()
                for (msg in messages) {
                    if (msg.role == "thought") continue
                    chatHistory.add(ChatEntry(msg.role, msg.content))
                }

                val resolvedTitle = title
                    ?: messages.firstOrNull { it.role == "user" }?.content
                        ?.replace(Regex("\\s+"), " ")?.trim()
                        ?.let { if (it.length > 30) it.take(30) + "\u2026" else it }
                if (!resolvedTitle.isNullOrBlank()) {
                    tabTitle = resolvedTitle
                    titleGenerated = true
                    onTitleChanged(this, resolvedTitle)
                }

                if (chatHistory.isEmpty()) hasWelcome = true
                renderFullPage()
            }
        }
    }

    private fun buildComposerPanel(): JPanel {
        val bottomBar = JPanel(BorderLayout(4, 0)).apply {
            border = JBUI.Borders.empty(4, 6, 4, 6)
            add(modelLabel, BorderLayout.WEST)
            val rightPanel = JPanel(FlowLayout(FlowLayout.RIGHT, 4, 0))
            rightPanel.add(cancelButton); rightPanel.add(sendButton)
            add(rightPanel, BorderLayout.EAST)
        }
        val inputScrollPane = JBScrollPane(inputArea).apply {
            verticalScrollBarPolicy = JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED
            horizontalScrollBarPolicy = JScrollPane.HORIZONTAL_SCROLLBAR_NEVER
            preferredSize = Dimension(0, 72); border = JBUI.Borders.empty()
        }
        val panel = object : JPanel(BorderLayout()) {
            init { isOpaque = false }
            override fun paintComponent(g: Graphics) {
                val g2 = g.create() as Graphics2D
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                val dk = htmlBuilder.isDark()
                g2.color = if (dk) Color(0x2b, 0x2b, 0x2b) else Color(0xf7, 0xf7, 0xf8)
                g2.fillRoundRect(0, 0, width, height, 12, 12)
                g2.color = if (dk) Color(0x44, 0x44, 0x44) else Color(0xd0, 0xd0, 0xd0)
                g2.drawRoundRect(0, 0, width - 1, height - 1, 12, 12)
                g2.dispose()
            }
        }.apply {
            border = JBUI.Borders.empty(2)
            add(inputScrollPane, BorderLayout.CENTER); add(bottomBar, BorderLayout.SOUTH)
        }
        inputArea.isOpaque = false
        inputScrollPane.isOpaque = false; inputScrollPane.viewport.isOpaque = false
        bottomBar.isOpaque = false
        return panel
    }

    var onSessionReady: (() -> Unit)? = null

    private fun setupCallbacks() {
        connection.onStatusChanged = { status ->
            SwingUtilities.invokeLater { updateStatus(status) }
            if (status == AgentStatus.READY) {
                if (chatId != null) {
                    log.info("Session ready: chatId=$chatId, acpSessionId=${connection.sessionId}, loadSucceeded=${connection.sessionLoadSucceeded}")
                } else {
                    chatId = connection.sessionId
                    log.info("New session ready: chatId=$chatId")
                }
                SwingUtilities.invokeLater { onSessionReady?.invoke() }
            }
        }
        connection.onModelChanged = { modelId ->
            SwingUtilities.invokeLater {
                modelLabel.text = truncateModelName(formatModelDisplay(modelId)) + " \u25BC"
                modelLabel.toolTipText = modelId
                cachedModelPopup = null
            }
        }
        connection.onSessionTitleChanged = { title ->
            SwingUtilities.invokeLater {
                tabTitle = title
                onTitleChanged(this, title)
            }
        }
        connection.onThoughtChunk = { chunk ->
            SwingUtilities.invokeLater { isThinking = true; currentThought.append(chunk); renderFullPage() }
        }
        connection.onMessageChunk = { chunk ->
            SwingUtilities.invokeLater { currentAgentMessage.append(chunk); renderFullPage() }
        }
        connection.onToolCallUpdate = { info ->
            SwingUtilities.invokeLater {
                if (!toolCallElements.containsKey(info.toolCallId)) toolCallOrder.add(info.toolCallId)
                toolCallElements[info.toolCallId] = info
                renderFullPage()
            }
        }
        connection.onPromptFinished = { stopReason ->
            SwingUtilities.invokeLater {
                finalizeAgentMessage()
                if (stopReason == "cancelled") chatHistory.add(ChatEntry("system", "Request cancelled."))
                renderFullPage()
            }
            if (chatId == null) {
                connection.sessionId?.let { chatId = it }
            }
            onSessionReady?.invoke()
            if (!titleGenerated) {
                generateAndSaveTitle()
            }
        }
        connection.onPermissionNeeded = { _, title, detail, callback ->
            SwingUtilities.invokeLater { showPermissionDialog(title, detail, callback) }
        }
    }

    private fun onSend() {
        val text = inputArea.text.trim()
        if (text.isEmpty()) return
        if (connection.status != AgentStatus.READY) {
            if (connection.status == AgentStatus.DISCONNECTED) connection.connect()
            return
        }
        inputArea.text = ""
        if (hasWelcome) { chatHistory.clear(); hasWelcome = false }
        chatHistory.add(ChatEntry("user", text))
        currentAgentMessage.clear(); currentThought.clear(); isThinking = false
        toolCallElements.clear(); toolCallOrder.clear()
        renderFullPage()
        connection.sendPrompt(text)
    }

    private fun finalizeAgentMessage() {
        if (toolCallOrder.isNotEmpty()) {
            val summary = ChatHtmlBuilder.buildToolCallSummary(toolCallOrder, toolCallElements)
            if (summary.isNotBlank()) chatHistory.add(ChatEntry("tool_call", summary))
        }
        if (currentThought.isNotEmpty()) chatHistory.add(ChatEntry("thought", currentThought.toString()))
        if (currentAgentMessage.isNotEmpty()) chatHistory.add(ChatEntry("assistant", currentAgentMessage.toString()))
        currentAgentMessage.clear(); currentThought.clear(); isThinking = false
        toolCallElements.clear(); toolCallOrder.clear()
    }

    private fun renderFullPage() {
        chatRenderer.setHtml(htmlBuilder.buildFullHtml(
            chatHistory, hasWelcome, toolCallOrder.toList(), HashMap(toolCallElements),
            currentThought, isThinking, currentAgentMessage, project.basePath
        ))
    }

    private fun formatModelDisplay(acpValue: String): String {
        if (acpValue.isEmpty() || acpValue == "default[]") return "Auto"
        val modelOpt = connection.getModelConfigOption()
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
            when { value == "true" -> tags.add(key); value == "false" -> {}; else -> tags.add(value) }
        }
        return if (tags.isEmpty()) base else "$base ${tags.joinToString(" ")}"
    }

    private fun truncateModelName(name: String): String =
        if (name.length > 22) name.take(20) + "\u2026" else name

    private fun showModelSelector() {
        val modelOpt = connection.getModelConfigOption()
        if (modelOpt == null || modelOpt.options.isNullOrEmpty()) {
            JOptionPane.showMessageDialog(this, "No models available. Please connect first.", "Models", JOptionPane.INFORMATION_MESSAGE)
            return
        }
        val currentValue = connection.currentModelId.ifEmpty { modelOpt.currentValue ?: "" }
        if (cachedModelPopup != null && cachedModelValue == currentValue) {
            cachedModelPopup!!.show(modelLabel, 0, -cachedModelPopup!!.preferredSize.height)
            return
        }
        val popup = JPopupMenu()
        for (opt in modelOpt.options) {
            val isCurrent = opt.value == currentValue
            val displayName = opt.name ?: opt.value
            val item = JMenuItem("${if (isCurrent) "\u2713 " else "   "}$displayName")
            item.toolTipText = opt.value
            if (isCurrent) item.foreground = JBColor(Color(0x1565c0), Color(0x6cb6ff))
            item.addActionListener {
                cachedModelPopup = null
                connection.switchModel(opt.value)
                chatHistory.add(ChatEntry("system", "Switched to model: $displayName"))
                renderFullPage()
            }
            popup.add(item)
        }
        cachedModelPopup = popup; cachedModelValue = currentValue
        popup.show(modelLabel, 0, -popup.preferredSize.height)
    }

    private fun generateAndSaveTitle() {
        val firstUserMsg = chatHistory.firstOrNull { it.role == "user" }?.text ?: return
        val cleaned = firstUserMsg.replace(Regex("\\s+"), " ").trim()
        val title = if (cleaned.length > 30) cleaned.take(30) + "\u2026" else cleaned
        if (title.isBlank()) return

        titleGenerated = true
        val id = chatId

        SwingUtilities.invokeLater {
            tabTitle = title
            onTitleChanged(this, title)
        }

        if (id != null) {
            ApplicationManager.getApplication().executeOnPooledThread {
                val updated = ChatHistoryService.updateSessionTitle(id, title)
                log.info("generateAndSaveTitle: chatId=$id title='$title' dbUpdated=$updated")
            }
        }
    }

    private fun showPermissionDialog(title: String, detail: String, callback: (Boolean) -> Unit) {
        val msg = buildString {
            append("Agent wants to execute:\n\n$title")
            if (detail.isNotBlank()) append("\n\n$detail")
            append("\n\nAllow this operation?")
        }
        val result = JOptionPane.showConfirmDialog(this, msg, "Permission Required", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE)
        callback(result == JOptionPane.YES_OPTION)
    }

    private fun showConnectingOverlay() {
        val dark = htmlBuilder.isDark()
        val fs = htmlBuilder.fontSize()
        val bg = ChatHtmlBuilder.colorHex(UIUtil.getPanelBackground())
        val fg = if (dark) "#999" else "#666"
        chatRenderer.setHtml("""<!DOCTYPE html><html><head><meta charset="utf-8">
<style>
body { margin:0; background:$bg; display:flex; align-items:center; justify-content:center; height:100vh; font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,sans-serif; }
.loader { text-align:center; color:$fg; font-size:${fs}px; }
.spinner { width:28px; height:28px; border:3px solid ${if (dark) "#444" else "#ddd"}; border-top:3px solid ${if (dark) "#6cb6ff" else "#1565c0"}; border-radius:50%; animation:spin 0.8s linear infinite; margin:0 auto 12px; }
@keyframes spin { to { transform:rotate(360deg); } }
</style></head><body>
<div class="loader"><div class="spinner"></div>Connecting...</div>
</body></html>""")
    }

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
                renderFullPage()
            }
            AgentStatus.THINKING -> {
                statusLabel.text = "Thinking..."; statusLabel.foreground = JBColor(Color(0x1565c0), Color(0x64b5f6))
                sendButton.isEnabled = false; cancelButton.isEnabled = true; inputArea.isEnabled = false
            }
        }
    }

    override fun dispose() {
        connection.destroy()
    }
}
