package com.cursor.agent.ui

import com.cursor.agent.acp.ToolCallInfo
import com.cursor.agent.services.AgentSessionManager
import com.cursor.agent.services.AgentStatus
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.colors.EditorColorsManager
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
import javax.swing.*
import javax.swing.text.html.HTMLEditorKit

class AgentChatPanel(private val project: Project) : JPanel(BorderLayout()) {
    private val chatDisplay: JEditorPane
    private val inputArea: JBTextArea
    private val sendButton: JButton
    private val connectButton: JButton
    private val cancelButton: JButton
    private val statusLabel: JBLabel
    private val chatContent = StringBuilder()
    private val currentAgentMessage = StringBuilder()

    private val sessionManager: AgentSessionManager
        get() = project.getService(AgentSessionManager::class.java)

    private fun isDark(): Boolean = !JBColor.isBright()

    private fun bgHex(): String = colorToHex(UIUtil.getPanelBackground())
    private fun fgHex(): String = colorToHex(UIUtil.getLabelForeground())
    private fun secondaryFgHex(): String = if (isDark()) "#999999" else "#666666"
    private fun userBubbleBg(): String = if (isDark()) "#2b3d50" else "#e3f2fd"
    private fun agentBubbleBg(): String = if (isDark()) "#2d2d2d" else "#f5f5f5"
    private fun codeBg(): String = if (isDark()) "#1e1e1e" else "#f4f4f4"
    private fun codeFg(): String = if (isDark()) "#d4d4d4" else "#333333"
    private fun toolCallBg(): String = if (isDark()) "#1a2636" else "#f0f4ff"
    private fun toolCallBorder(): String = if (isDark()) "#4a7fff" else "#4a7fff"
    private fun accentColor(): String = if (isDark()) "#6cb6ff" else "#1565c0"

    private fun colorToHex(c: Color): String = String.format("#%02x%02x%02x", c.red, c.green, c.blue)

    private fun editorFontSize(): Int {
        return try {
            val scheme = EditorColorsManager.getInstance().globalScheme
            scheme.editorFontSize
        } catch (_: Exception) {
            13
        }
    }

    init {
        border = JBUI.Borders.empty(4)

        val toolbar = JPanel(FlowLayout(FlowLayout.LEFT, 4, 2))
        connectButton = JButton("Connect").apply {
            addActionListener { onConnect() }
        }
        cancelButton = JButton("Cancel").apply {
            isEnabled = false
            addActionListener { sessionManager.cancelCurrentPrompt() }
        }
        val clearButton = JButton("Clear").apply {
            addActionListener { clearChat() }
        }
        statusLabel = JBLabel("Disconnected").apply {
            foreground = JBColor.GRAY
        }
        toolbar.add(connectButton)
        toolbar.add(cancelButton)
        toolbar.add(clearButton)
        toolbar.add(Box.createHorizontalStrut(8))
        toolbar.add(statusLabel)

        chatDisplay = JEditorPane().apply {
            isEditable = false
            contentType = "text/html"
            editorKit = HTMLEditorKit()
            background = UIUtil.getPanelBackground()
        }
        val scrollPane = JBScrollPane(chatDisplay).apply {
            verticalScrollBarPolicy = JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED
            horizontalScrollBarPolicy = JScrollPane.HORIZONTAL_SCROLLBAR_NEVER
        }

        inputArea = JBTextArea(3, 0).apply {
            lineWrap = true
            wrapStyleWord = true
            border = JBUI.Borders.compound(
                JBUI.Borders.customLine(JBColor.border(), 1, 0, 0, 0),
                JBUI.Borders.empty(6)
            )
            emptyText.text = "Ask Cursor Agent... (Enter to send, Shift+Enter for newline)"
        }
        inputArea.addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                if (e.keyCode == KeyEvent.VK_ENTER && !e.isShiftDown) {
                    e.consume()
                    onSend()
                }
            }
        })

        sendButton = JButton("Send").apply {
            addActionListener { onSend() }
        }

        val inputPanel = JPanel(BorderLayout(4, 0)).apply {
            add(JBScrollPane(inputArea).apply {
                preferredSize = Dimension(0, 70)
                verticalScrollBarPolicy = JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED
            }, BorderLayout.CENTER)
            add(sendButton, BorderLayout.EAST)
        }

        add(toolbar, BorderLayout.NORTH)
        add(scrollPane, BorderLayout.CENTER)
        add(inputPanel, BorderLayout.SOUTH)

        setupSessionCallbacks()
        updateStatus(AgentStatus.DISCONNECTED)

        val savedHistory = sessionManager.loadChatHistory()
        if (savedHistory.isNotEmpty()) {
            chatContent.append(savedHistory)
            updateChatHtml()
        } else {
            appendWelcome()
        }
    }

    private fun setupSessionCallbacks() {
        val manager = sessionManager

        manager.onStatusChanged = { status ->
            ApplicationManager.getApplication().invokeLater {
                updateStatus(status)
            }
        }

        manager.onMessageChunk = { chunk ->
            ApplicationManager.getApplication().invokeLater {
                currentAgentMessage.append(chunk)
                refreshChatDisplay()
            }
        }

        manager.onToolCallUpdate = { info ->
            ApplicationManager.getApplication().invokeLater {
                handleToolCallUpdate(info)
            }
        }

        manager.onPromptFinished = { stopReason ->
            ApplicationManager.getApplication().invokeLater {
                finalizeAgentMessage()
                if (stopReason == "cancelled") {
                    appendSystemMessage("Request cancelled.")
                }
                persistChatHistory()
            }
        }

        manager.onPermissionNeeded = { id, title, detail, callback ->
            ApplicationManager.getApplication().invokeLater {
                showPermissionDialog(title, detail, callback)
            }
        }

        manager.onSessionRestored = { sid ->
            ApplicationManager.getApplication().invokeLater {
                appendSystemMessage("Session restored: ${sid.take(8)}...")
            }
        }
    }

    private fun onConnect() {
        if (sessionManager.status == AgentStatus.DISCONNECTED) {
            sessionManager.connect()
        } else {
            sessionManager.disconnect()
        }
    }

    private fun onSend() {
        val text = inputArea.text.trim()
        if (text.isEmpty()) return
        if (sessionManager.status != AgentStatus.READY) {
            if (sessionManager.status == AgentStatus.DISCONNECTED) {
                sessionManager.connect()
            }
            return
        }

        inputArea.text = ""
        appendUserMessage(text)
        currentAgentMessage.clear()
        sessionManager.sendPrompt(text)
    }

    private fun appendWelcome() {
        val fs = editorFontSize()
        chatContent.append(
            "<div style='text-align:center;color:${secondaryFgHex()};padding:20px;font-size:${fs}px;'>" +
            "<h2 style='margin:0;color:${fgHex()};'>Cursor Agent</h2>" +
            "<p>Click <b>Connect</b> to start an AI coding session.</p>" +
            "<p style='font-size:${fs - 2}px;'>Configure the agent binary path in Settings - Tools - Cursor Agent</p>" +
            "</div>"
        )
        updateChatHtml()
    }

    private fun appendUserMessage(text: String) {
        val fs = editorFontSize()
        val rendered = MessageRenderer.renderMarkdown(text, fgHex(), codeBg(), codeFg(), fs)
        chatContent.append(
            "<div style='background:${userBubbleBg()};padding:8px 12px;margin:8px 0;font-size:${fs}px;'>" +
            "<b style='color:${accentColor()};'>You</b><br/>" +
            rendered +
            "</div>"
        )
        chatContent.append(
            "<div style='background:${agentBubbleBg()};padding:8px 12px;margin:8px 0;font-size:${fs}px;'>" +
            "<b style='color:${fgHex()};'>Agent</b><br/>"
        )
        updateChatHtml()
    }

    private fun refreshChatDisplay() {
        val fs = editorFontSize()
        val rendered = MessageRenderer.renderMarkdown(
            currentAgentMessage.toString(), fgHex(), codeBg(), codeFg(), fs
        )
        val html = chatContent.toString() + rendered + "</div>"
        setFullHtml(html)
    }

    private fun finalizeAgentMessage() {
        if (currentAgentMessage.isNotEmpty()) {
            val fs = editorFontSize()
            val rendered = MessageRenderer.renderMarkdown(
                currentAgentMessage.toString(), fgHex(), codeBg(), codeFg(), fs
            )
            chatContent.append(rendered)
        }
        chatContent.append("</div>")
        currentAgentMessage.clear()
        updateChatHtml()
    }

    private fun handleToolCallUpdate(info: ToolCallInfo) {
        val fs = editorFontSize()
        val statusIcon = when (info.status) {
            "in_progress" -> "&#9654;"
            "completed" -> "&#10003;"
            "error" -> "&#10007;"
            else -> "&#9679;"
        }
        val title = info.title ?: info.kind ?: "tool call"
        chatContent.append(
            "<div style='background:${toolCallBg()};border-left:3px solid ${toolCallBorder()};padding:4px 8px;margin:4px 0;font-size:${fs - 1}px;color:${fgHex()};'>" +
            "$statusIcon <b>$title</b> <span style='color:${secondaryFgHex()};'>[${info.status ?: "pending"}]</span>" +
            "</div>"
        )
        updateChatHtml()
    }

    private fun appendSystemMessage(text: String) {
        val fs = editorFontSize()
        chatContent.append(
            "<div style='text-align:center;color:${secondaryFgHex()};font-size:${fs - 1}px;margin:8px 0;font-style:italic;'>" +
            text +
            "</div>"
        )
        updateChatHtml()
    }

    private fun showPermissionDialog(title: String, detail: String, callback: (Boolean) -> Unit) {
        val message = buildString {
            append("Agent wants to execute:\n\n")
            append(title)
            if (detail.isNotBlank()) {
                append("\n\n")
                append(detail)
            }
            append("\n\nAllow this operation?")
        }

        val result = JOptionPane.showConfirmDialog(
            this,
            message,
            "Permission Required",
            JOptionPane.YES_NO_OPTION,
            JOptionPane.WARNING_MESSAGE
        )
        callback(result == JOptionPane.YES_OPTION)
    }

    private fun clearChat() {
        chatContent.clear()
        currentAgentMessage.clear()
        sessionManager.saveChatHistory("")
        appendWelcome()
    }

    private fun persistChatHistory() {
        sessionManager.saveChatHistory(chatContent.toString())
    }

    private fun updateStatus(status: AgentStatus) {
        when (status) {
            AgentStatus.DISCONNECTED -> {
                statusLabel.text = "Disconnected"
                statusLabel.foreground = JBColor.GRAY
                connectButton.text = "Connect"
                connectButton.isEnabled = true
                sendButton.isEnabled = false
                cancelButton.isEnabled = false
                inputArea.isEnabled = false
            }
            AgentStatus.CONNECTING -> {
                statusLabel.text = "Connecting..."
                statusLabel.foreground = JBColor.ORANGE
                connectButton.isEnabled = false
                sendButton.isEnabled = false
                cancelButton.isEnabled = false
                inputArea.isEnabled = false
            }
            AgentStatus.CONNECTED -> {
                statusLabel.text = "Connected"
                statusLabel.foreground = JBColor.BLUE
                connectButton.text = "Disconnect"
                connectButton.isEnabled = true
                sendButton.isEnabled = false
                cancelButton.isEnabled = false
                inputArea.isEnabled = false
            }
            AgentStatus.READY -> {
                statusLabel.text = "Ready"
                statusLabel.foreground = JBColor(Color(0x2e7d32), Color(0x81c784))
                connectButton.text = "Disconnect"
                connectButton.isEnabled = true
                sendButton.isEnabled = true
                cancelButton.isEnabled = false
                inputArea.isEnabled = true
                inputArea.requestFocusInWindow()
            }
            AgentStatus.THINKING -> {
                statusLabel.text = "Thinking..."
                statusLabel.foreground = JBColor(Color(0x1565c0), Color(0x64b5f6))
                connectButton.isEnabled = false
                sendButton.isEnabled = false
                cancelButton.isEnabled = true
                inputArea.isEnabled = false
            }
        }
    }

    private fun updateChatHtml() {
        setFullHtml(chatContent.toString())
    }

    private fun setFullHtml(bodyContent: String) {
        val fs = editorFontSize()
        val html = "<html><head><style>" +
            "body { font-family: sans-serif; font-size: ${fs}px; margin: 8px; color: ${fgHex()}; background: ${bgHex()}; }" +
            "</style></head><body>$bodyContent</body></html>"
        chatDisplay.text = html
        SwingUtilities.invokeLater {
            chatDisplay.caretPosition = chatDisplay.document.length
        }
    }
}
