package com.cursor.agent.ui

import com.cursor.agent.acp.ContentBlock
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
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable
import java.awt.dnd.DnDConstants
import java.awt.dnd.DropTarget
import java.awt.dnd.DropTargetAdapter
import java.awt.dnd.DropTargetDragEvent
import java.awt.dnd.DropTargetDropEvent
import java.awt.dnd.DropTargetEvent
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.io.File
import java.net.URI
import java.nio.file.Files
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import javax.swing.*
import javax.swing.filechooser.FileNameExtensionFilter

class ChatSessionTab(
    private val project: Project,
    private val parentDisposable: Disposable,
    initialChatId: String?,
    private val onTitleChanged: (ChatSessionTab, String) -> Unit
) : JPanel(BorderLayout()), Disposable {
    private data class PendingImage(
        val displayName: String,
        val pathKey: String,
        val block: ContentBlock
    )

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
    private val uploadImageButton: JButton
    private val statusLabel: JBLabel
    private val modelLabel: JBLabel
    private val attachmentPanel: JPanel
    private val inputNormalBorder = JBUI.Borders.empty(6)
    private val inputDropBorder = JBUI.Borders.customLine(JBColor(Color(0x4A, 0x88, 0xDA), Color(0x6C, 0xB6, 0xFF)), 2)

    private val currentAgentMessage = StringBuilder()
    private val currentThought = StringBuilder()
    private var isThinking = false
    private var hasWelcome = false
    private var titleGenerated = false
    private val pendingImages = mutableListOf<PendingImage>()

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
        uploadImageButton = JButton("Upload Image").apply {
            addActionListener { pickLocalImages() }
        }
        attachmentPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            isVisible = false
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
            border = inputNormalBorder
            emptyText.text = "Ask Cursor Agent... (Enter to send, Shift+Enter for newline)"
        }
        installImageDropSupport()
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
            val leftPanel = JPanel(FlowLayout(FlowLayout.LEFT, 4, 0)).apply {
                isOpaque = false
                add(modelLabel)
                add(uploadImageButton)
            }
            add(leftPanel, BorderLayout.WEST)
            val rightPanel = JPanel(FlowLayout(FlowLayout.RIGHT, 4, 0))
            rightPanel.add(cancelButton); rightPanel.add(sendButton)
            rightPanel.isOpaque = false
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
            add(inputScrollPane, BorderLayout.CENTER)
            val bottomContainer = JPanel(BorderLayout()).apply {
                isOpaque = false
                add(attachmentPanel, BorderLayout.NORTH)
                add(bottomBar, BorderLayout.SOUTH)
            }
            add(bottomContainer, BorderLayout.SOUTH)
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
            SwingUtilities.invokeLater { isThinking = true; currentThought.append(chunk); renderStreaming() }
        }
        connection.onMessageChunk = { chunk ->
            SwingUtilities.invokeLater { currentAgentMessage.append(chunk); renderStreaming() }
        }
        connection.onToolCallUpdate = { info ->
            SwingUtilities.invokeLater {
                if (!toolCallElements.containsKey(info.toolCallId)) toolCallOrder.add(info.toolCallId)
                toolCallElements[info.toolCallId] = info
                renderStreaming()
            }
        }
        connection.onPromptFinished = { stopReason ->
            SwingUtilities.invokeLater {
                isStreaming = false
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
        if (text.isEmpty() && pendingImages.isEmpty()) return
        if (connection.status != AgentStatus.READY) {
            if (connection.status == AgentStatus.DISCONNECTED) connection.connect()
            return
        }
        if (pendingImages.isNotEmpty() && !connection.supportsImagePrompt) {
            JOptionPane.showMessageDialog(
                this,
                "Current ACP agent does not advertise image prompt capability.",
                "Image Not Supported",
                JOptionPane.WARNING_MESSAGE
            )
            return
        }
        val imagesToSend = pendingImages.map { it.block }
        val imageSummary = pendingImages.joinToString(", ") { it.displayName }
        inputArea.text = ""
        if (hasWelcome) { chatHistory.clear(); hasWelcome = false }
        chatHistory.add(ChatEntry("user", buildUserPreview(text, imageSummary, imagesToSend.size)))
        pendingImages.clear()
        refreshAttachmentLabel()
        currentAgentMessage.clear(); currentThought.clear(); isThinking = false
        toolCallElements.clear(); toolCallOrder.clear()
        isStreaming = true
        renderFullPage()
        connection.sendPrompt(text, imagesToSend)
    }

    private fun buildUserPreview(text: String, imageSummary: String, imageCount: Int): String {
        if (imageCount <= 0) return text
        val header = "Attached images ($imageCount): $imageSummary"
        return if (text.isBlank()) header else "$text\n\n$header"
    }

    private fun refreshAttachmentLabel() {
        attachmentPanel.removeAll()
        if (pendingImages.isEmpty()) {
            attachmentPanel.isVisible = false
            attachmentPanel.revalidate()
            attachmentPanel.repaint()
            return
        }

        attachmentPanel.add(JBLabel("Pending images:").apply {
            foreground = JBColor.GRAY
            border = JBUI.Borders.empty(0, 0, 2, 0)
            alignmentX = LEFT_ALIGNMENT
        })
        pendingImages.forEach { image ->
            attachmentPanel.add(createAttachmentChip(image))
        }

        attachmentPanel.isVisible = true
        attachmentPanel.revalidate()
        attachmentPanel.repaint()
    }

    private fun createAttachmentChip(image: PendingImage): JComponent {
        val chip = JPanel(FlowLayout(FlowLayout.LEFT, 2, 0)).apply {
            isOpaque = false
            border = JBUI.Borders.customLine(
                if (htmlBuilder.isDark()) Color(0x55, 0x55, 0x55) else Color(0xcc, 0xcc, 0xcc),
                1
            )
            alignmentX = LEFT_ALIGNMENT
        }
        val nameLabel = JBLabel(image.displayName).apply {
            border = JBUI.Borders.empty(1, 4, 1, 2)
        }
        val removeBtn = JButton("×").apply {
            isFocusable = false
            margin = Insets(0, 4, 0, 4)
            toolTipText = "Remove image"
            addActionListener {
                pendingImages.removeAll { it.pathKey == image.pathKey }
                refreshAttachmentLabel()
            }
        }
        chip.add(nameLabel)
        chip.add(removeBtn)
        return chip
    }

    private fun pickLocalImages() {
        val chooser = JFileChooser().apply {
            isMultiSelectionEnabled = true
            fileSelectionMode = JFileChooser.FILES_ONLY
            fileFilter = FileNameExtensionFilter(
                "Image Files",
                "png", "jpg", "jpeg", "gif", "webp", "bmp"
            )
        }
        if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return
        val files = chooser.selectedFiles?.toList().orEmpty()
        addLocalImages(files)
    }

    private fun installImageDropSupport() {
        DropTarget(inputArea, object : DropTargetAdapter() {
            override fun dragEnter(dtde: DropTargetDragEvent) {
                if (supportsFileDrop(dtde.currentDataFlavors)) {
                    dtde.acceptDrag(DnDConstants.ACTION_COPY)
                    setDropHighlight(true)
                } else {
                    dtde.rejectDrag()
                    setDropHighlight(false)
                }
            }

            override fun dragOver(dtde: DropTargetDragEvent) {
                if (supportsFileDrop(dtde.currentDataFlavors)) {
                    dtde.acceptDrag(DnDConstants.ACTION_COPY)
                    setDropHighlight(true)
                } else {
                    dtde.rejectDrag()
                    setDropHighlight(false)
                }
            }

            override fun dragExit(dte: DropTargetEvent) {
                setDropHighlight(false)
            }

            override fun drop(dtde: DropTargetDropEvent) {
                setDropHighlight(false)
                if (!supportsFileDrop(dtde.currentDataFlavors)) {
                    dtde.rejectDrop()
                    return
                }
                dtde.acceptDrop(DnDConstants.ACTION_COPY)
                val files = extractDroppedFiles(dtde.transferable, dtde.currentDataFlavors.toList())
                if (files.isEmpty()) {
                    dtde.dropComplete(false)
                    return
                }
                addLocalImages(files)
                dtde.dropComplete(true)
            }
        })
    }

    private fun supportsFileDrop(flavors: Array<DataFlavor>): Boolean {
        return flavors.any {
            it == DataFlavor.javaFileListFlavor ||
                it == DataFlavor.stringFlavor ||
                it.mimeType.startsWith("text/uri-list")
        }
    }

    private fun extractDroppedFiles(transferable: Transferable, flavors: List<DataFlavor>): List<File> {
        if (transferable.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) {
            val files = runCatching {
                @Suppress("UNCHECKED_CAST")
                transferable.getTransferData(DataFlavor.javaFileListFlavor) as? List<File>
            }.getOrNull().orEmpty()
            if (files.isNotEmpty()) return files
        }
        for (flavor in flavors) {
            if (flavor == DataFlavor.stringFlavor || flavor.mimeType.startsWith("text/uri-list")) {
                val raw = runCatching {
                    transferable.getTransferData(flavor)?.toString()
                }.getOrNull().orEmpty()
                val files = parseUriListToFiles(raw)
                if (files.isNotEmpty()) return files
            }
        }
        return emptyList()
    }

    private fun parseUriListToFiles(raw: String): List<File> {
        return raw
            .lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .mapNotNull { line ->
                val normalized = line.removePrefix("file://localhost")
                runCatching { File(URI(normalized)) }.getOrNull()
            }
            .toList()
    }

    private fun setDropHighlight(active: Boolean) {
        SwingUtilities.invokeLater {
            inputArea.border = if (active) inputDropBorder else inputNormalBorder
            inputArea.revalidate()
            inputArea.repaint()
        }
    }

    private fun addLocalImages(files: List<File>) {
        if (files.isEmpty()) return
        val added = mutableListOf<String>()
        val skippedDuplicate = mutableListOf<String>()
        val skippedInvalid = mutableListOf<String>()
        val selectedPathKeys = mutableSetOf<String>()
        files.forEach { file ->
            if (!file.exists() || !file.isFile) {
                skippedInvalid.add(file.name)
                return@forEach
            }
            val pathKey = normalizePathKey(file)
            val duplicated = pathKey in selectedPathKeys || pendingImages.any { it.pathKey == pathKey }
            if (duplicated) {
                skippedDuplicate.add(file.name)
                return@forEach
            }
            selectedPathKeys.add(pathKey)
            runCatching { buildImageBlockFromFile(file) }
                .onSuccess { block ->
                    pendingImages.add(PendingImage(file.name, pathKey, block))
                    added.add(file.name)
                }
                .onFailure { e ->
                    JOptionPane.showMessageDialog(
                        this,
                        "Failed to attach ${file.name}: ${e.message}",
                        "Attach Image Failed",
                        JOptionPane.ERROR_MESSAGE
                    )
                }
        }
        refreshAttachmentLabel()
        if (added.isNotEmpty()) {
            log.info("Attached local images: ${added.joinToString(", ")}")
        }
        if (skippedInvalid.isNotEmpty()) {
            JOptionPane.showMessageDialog(
                this,
                "Skipped invalid files: ${skippedInvalid.joinToString(", ")}",
                "Invalid Files",
                JOptionPane.INFORMATION_MESSAGE
            )
        }
        if (skippedDuplicate.isNotEmpty()) {
            JOptionPane.showMessageDialog(
                this,
                "Skipped duplicate images: ${skippedDuplicate.joinToString(", ")}",
                "Duplicate Images",
                JOptionPane.INFORMATION_MESSAGE
            )
        }
    }

    private fun normalizePathKey(file: File): String {
        val raw = runCatching { file.canonicalPath }.getOrElse { file.absolutePath }
        val isWindows = System.getProperty("os.name").lowercase().contains("win")
        return if (isWindows) raw.lowercase() else raw
    }

    private fun buildImageBlockFromFile(file: File): ContentBlock {
        require(file.exists() && file.isFile) { "File does not exist." }
        val mimeType = detectImageMime(file.name, Files.probeContentType(file.toPath()))
        val data = Base64.getEncoder().encodeToString(file.readBytes())
        return ContentBlock(type = "image", mimeType = mimeType, data = data, uri = file.toURI().toString())
    }

    private fun detectImageMime(nameOrUrl: String, contentType: String?): String {
        val ct = contentType?.substringBefore(";")?.trim()?.lowercase()
        if (ct != null && ct.startsWith("image/")) return ct
        val lower = nameOrUrl.lowercase()
        return when {
            lower.endsWith(".png") -> "image/png"
            lower.endsWith(".jpg") || lower.endsWith(".jpeg") -> "image/jpeg"
            lower.endsWith(".gif") -> "image/gif"
            lower.endsWith(".webp") -> "image/webp"
            lower.endsWith(".bmp") -> "image/bmp"
            else -> throw IllegalArgumentException("Unsupported or unknown image MIME type.")
        }
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

    private var isStreaming = false
    private var streamDirty = false
    private var lastStreamRender = 0L
    private val streamThrottleMs = 150L

    private fun renderFullPage() {
        chatRenderer.setHtml(htmlBuilder.buildFullHtml(
            chatHistory, hasWelcome, toolCallOrder.toList(), HashMap(toolCallElements),
            currentThought, isThinking, currentAgentMessage, project.basePath
        ))
    }

    private fun renderStreaming() {
        if (!isStreaming) {
            renderFullPage()
            return
        }
        val now = System.currentTimeMillis()
        if (now - lastStreamRender < streamThrottleMs) {
            if (!streamDirty) {
                streamDirty = true
                Timer(streamThrottleMs.toInt(), null).apply {
                    isRepeats = false
                    addActionListener {
                        streamDirty = false
                        doStreamUpdate()
                    }
                    start()
                }
            }
            return
        }
        doStreamUpdate()
    }

    private fun doStreamUpdate() {
        lastStreamRender = System.currentTimeMillis()

        fun escapeJs(s: String) = s
            .replace("\\", "\\\\")
            .replace("`", "\\`")
            .replace("\${", "\\\${")
            .replace("\n", "\\n")
            .replace("\r", "")

        val js = StringBuilder("(function(){")

        if (toolCallOrder.isNotEmpty()) {
            val lastInfo = toolCallElements[toolCallOrder.last()]
            if (lastInfo != null) {
                val icon = when (lastInfo.status) { "in_progress" -> "\u25b6"; "completed" -> "\u2713"; "error" -> "\u2717"; else -> "\u25cf" }
                val title = lastInfo.title ?: lastInfo.kind ?: "tool call"
                val detail = ChatHtmlBuilder.extractToolCallDetail(lastInfo, project.basePath)
                val detailStr = if (detail.isNotEmpty()) " $detail" else ""
                val countStr = if (toolCallOrder.size > 1) " (${toolCallOrder.size} calls)" else ""
                val toolText = escapeJs(MessageRenderer.escapeHtml("$icon $title$detailStr [${lastInfo.status ?: "pending"}]$countStr"))
                js.append("var tl=document.getElementById('stream-tool');if(tl){tl.style.display='';tl.innerHTML=`$toolText`;}")
            }
        }

        if (currentThought.isNotEmpty()) {
            val lines = currentThought.toString().trimEnd().lines()
            val lastLine = if (lines.size <= 1) MessageRenderer.escapeHtml(lines.firstOrNull() ?: "")
                else "... " + MessageRenderer.escapeHtml(lines.last())
            val label = if (isThinking) "\u25cf Thinking..." else "Thought"
            val thoughtHtml = escapeJs("<span class=\"thought-label\">$label</span><br/>$lastLine")
            js.append("var te=document.getElementById('stream-thought');if(te){te.style.display='';te.innerHTML=`$thoughtHtml`;}")
        }

        if (currentAgentMessage.isNotEmpty()) {
            val rendered = MessageRenderer.renderMarkdown(currentAgentMessage.toString())
            val msgHtml = escapeJs("<div class=\"agent-name\">Agent</div>$rendered")
            js.append("var me=document.getElementById('stream-message');if(me){me.style.display='';me.innerHTML=`$msgHtml`;}")
        }

        js.append("window.scrollTo(0,document.body.scrollHeight);")
        js.append("if(typeof hljs!=='undefined'){document.querySelectorAll('#stream-message pre code:not(.hljs)').forEach(function(b){hljs.highlightElement(b);});}")
        js.append("})();")

        chatRenderer.executeJs(js.toString())
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
                uploadImageButton.isEnabled = false
            }
            AgentStatus.CONNECTING -> {
                statusLabel.text = "Connecting..."; statusLabel.foreground = JBColor.ORANGE
                sendButton.isEnabled = false; cancelButton.isEnabled = false; inputArea.isEnabled = false
                uploadImageButton.isEnabled = false
            }
            AgentStatus.CONNECTED -> {
                statusLabel.text = "Connected"; statusLabel.foreground = JBColor.BLUE
                sendButton.isEnabled = false; cancelButton.isEnabled = false; inputArea.isEnabled = false
                uploadImageButton.isEnabled = false
            }
            AgentStatus.READY -> {
                statusLabel.text = "Ready"; statusLabel.foreground = JBColor(Color(0x2e7d32), Color(0x81c784))
                sendButton.isEnabled = true; cancelButton.isEnabled = false; inputArea.isEnabled = true
                uploadImageButton.isEnabled = true
                inputArea.requestFocusInWindow()
                renderFullPage()
            }
            AgentStatus.THINKING -> {
                statusLabel.text = "Thinking..."; statusLabel.foreground = JBColor(Color(0x1565c0), Color(0x64b5f6))
                sendButton.isEnabled = false; cancelButton.isEnabled = true; inputArea.isEnabled = false
                uploadImageButton.isEnabled = false
            }
        }
    }

    override fun dispose() {
        connection.destroy()
    }
}
