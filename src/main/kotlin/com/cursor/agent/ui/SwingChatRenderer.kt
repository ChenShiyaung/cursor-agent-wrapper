package com.cursor.agent.ui

import com.cursor.agent.acp.ToolCallInfo
import com.intellij.lang.Language
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.ui.EditorTextField
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.HTMLEditorKitBuilder
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.*
import java.awt.datatransfer.StringSelection
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import kotlin.math.roundToInt
import javax.swing.*
import javax.swing.event.HyperlinkEvent
import javax.swing.text.html.StyleSheet

private val EXT_TO_LANG = mapOf(
    "ets" to "TypeScript", "arkts" to "TypeScript",
    "ts" to "TypeScript", "tsx" to "TypeScript",
    "js" to "JavaScript", "jsx" to "JavaScript",
    "kt" to "kotlin", "kts" to "kotlin",
    "java" to "JAVA", "py" to "Python", "rb" to "Ruby",
    "rs" to "Rust", "go" to "Go", "c" to "C", "cpp" to "C++",
    "h" to "C", "hpp" to "C++", "cs" to "C#",
    "swift" to "Swift", "m" to "ObjectiveC",
    "sh" to "Shell Script", "bash" to "Shell Script", "zsh" to "Shell Script",
    "yml" to "YAML", "yaml" to "YAML",
    "json" to "JSON", "xml" to "XML", "html" to "HTML",
    "css" to "CSS", "scss" to "CSS", "sql" to "SQL",
    "dart" to "Dart", "vue" to "Vue.js", "svelte" to "XML",
    "md" to "Markdown", "groovy" to "Groovy", "gradle" to "Groovy"
)

class SwingChatRenderer(
    private val parentDisposable: Disposable,
    private val project: Project? = null
) : ChatRenderer, Disposable {

    private val log = Logger.getInstance(SwingChatRenderer::class.java)

    private val messagesPanel = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        background = UIUtil.getPanelBackground()
    }

    private val scrollPane = JBScrollPane(messagesPanel).apply {
        verticalScrollBarPolicy = JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED
        horizontalScrollBarPolicy = JScrollPane.HORIZONTAL_SCROLLBAR_NEVER
        border = JBUI.Borders.empty()
        verticalScrollBar.unitIncrement = 16
    }
    private val bottomGlue = Box.createVerticalGlue()
    private data class ThemePalette(
        val panelBg: Color,
        val textPrimary: Color,
        val textMuted: Color,
        val link: Color,
        val userBubble: Color,
        val agentBubble: Color,
        val thoughtBg: Color,
        val thoughtText: Color,
        val thoughtAccent: Color,
        val toolBg: Color,
        val toolText: Color,
        val toolAccent: Color,
        val codeBg: Color,
        val codeBorder: Color,
        val codeHeaderBg: Color,
        val codeHeaderText: Color,
        val inlineCodeBg: Color,
        val inlineCodeText: Color
    )

    init {
        Disposer.register(parentDisposable, this)
    }

    override val component: JComponent get() = scrollPane
    override fun setHtml(html: String) {}

    fun renderMessages(
        chatHistory: List<ChatEntry>,
        hasWelcome: Boolean,
        toolCallOrder: List<String>,
        toolCallElements: Map<String, ToolCallInfo>,
        currentThought: CharSequence,
        isThinking: Boolean,
        currentAgentMessage: CharSequence,
        projectBasePath: String?
    ) {
        refreshSurfaceColors()
        messagesPanel.removeAll()

        if (hasWelcome && chatHistory.isEmpty()) {
            messagesPanel.add(createWelcomePanel())
            finishLayout()
            return
        }

        for (entry in chatHistory) {
            val panel = when (entry.role) {
                "user" -> createUserBubble(entry.text)
                "assistant" -> createAgentBubble(entry.text)
                "thought" -> createThoughtPanel(summarizeThoughtText(entry.text), "Thought")
                "tool_call" -> createToolCallPanel(entry.text)
                "system" -> createSystemPanel(entry.text)
                else -> null
            }
            if (panel != null) messagesPanel.add(panel)
        }

        addStreamingComponents(toolCallOrder, toolCallElements, currentThought, isThinking, currentAgentMessage, projectBasePath)
        finishLayout()
    }

    fun appendStreamingUpdate(
        currentThought: CharSequence,
        isThinking: Boolean,
        currentAgentMessage: CharSequence,
        toolCallOrder: List<String>,
        toolCallElements: Map<String, ToolCallInfo>,
        projectBasePath: String?
    ) {
        refreshSurfaceColors()
        removeStreamingComponents()
        addStreamingComponents(toolCallOrder, toolCallElements, currentThought, isThinking, currentAgentMessage, projectBasePath)
        finishLayout()
    }

    private fun refreshSurfaceColors() {
        val bg = palette().panelBg
        messagesPanel.background = bg
        scrollPane.background = bg
        scrollPane.viewport.background = bg
    }

    private fun addStreamingComponents(
        toolCallOrder: List<String>,
        toolCallElements: Map<String, ToolCallInfo>,
        currentThought: CharSequence,
        isThinking: Boolean,
        currentAgentMessage: CharSequence,
        projectBasePath: String?
    ) {
        if (toolCallOrder.isNotEmpty()) {
            val lastInfo = toolCallElements[toolCallOrder.last()]
            if (lastInfo != null) {
                val icon = when (lastInfo.status) { "in_progress" -> "\u25b6"; "completed" -> "\u2713"; "error" -> "\u2717"; else -> "\u25cf" }
                val title = lastInfo.title ?: lastInfo.kind ?: "tool call"
                val detail = singleLine(ChatHtmlBuilder.extractToolCallDetail(lastInfo, projectBasePath), 72)
                val detailStr = if (detail.isNotEmpty()) " $detail" else ""
                val countStr = if (toolCallOrder.size > 1) " (${toolCallOrder.size} calls)" else ""
                val toolLine = singleLine("$icon $title$detailStr [${lastInfo.status ?: "pending"}]$countStr", 110)
                val p = createToolCallPanel(toolLine)
                p.putClientProperty("streaming", true)
                messagesPanel.add(p)
            }
        }
        if (currentThought.isNotEmpty()) {
            val label = if (isThinking) "\u25cf Thinking..." else "Thought"
            val p = createThoughtPanel(summarizeThoughtText(currentThought.toString()), label)
            p.putClientProperty("streaming", true)
            messagesPanel.add(p)
        }
        if (currentAgentMessage.isNotEmpty()) {
            val p = createAgentBubble(currentAgentMessage.toString())
            p.putClientProperty("streaming", true)
            messagesPanel.add(p)
        }
    }

    private fun removeStreamingComponents() {
        val toRemove = messagesPanel.components.filter {
            (it as? JComponent)?.getClientProperty("streaming") == true
        }
        for (c in toRemove) messagesPanel.remove(c)
    }

    private fun finishLayout() {
        if (bottomGlue.parent === messagesPanel) {
            messagesPanel.remove(bottomGlue)
        }
        messagesPanel.add(bottomGlue)
        messagesPanel.revalidate()
        messagesPanel.repaint()
        SwingUtilities.invokeLater {
            val sb = scrollPane.verticalScrollBar
            sb.value = sb.maximum
        }
    }

    // ===== Message Bubble Factories =====

    private fun createWelcomePanel(): JPanel = JPanel(BorderLayout()).apply {
        background = UIUtil.getPanelBackground()
        border = JBUI.Borders.empty(30, 10)
        val label = JBLabel("<html><div style='text-align:center'><h2>Cursor Agent</h2>" +
                "<p>Agent will auto-connect when the project opens.</p></div></html>")
        label.horizontalAlignment = SwingConstants.CENTER
        label.foreground = JBColor.GRAY
        add(label, BorderLayout.CENTER)
        maximumSize = Dimension(Int.MAX_VALUE, preferredSize.height)
        alignmentX = Component.LEFT_ALIGNMENT
    }

    private fun createUserBubble(text: String): JPanel {
        val p = palette()
        val bg = p.userBubble
        val nameColor = p.link
        return createBubble("You", nameColor, bg, text, hasCodeBlocks = false)
    }

    private fun createAgentBubble(text: String): JPanel {
        val p = palette()
        val bg = p.agentBubble
        val nameColor = if (isDark()) Color(0x8d, 0xcf, 0x93) else Color(0x2e, 0x7d, 0x32)
        return createBubble("Agent", nameColor, bg, text, hasCodeBlocks = true)
    }

    private fun createBubble(name: String, nameColor: Color, bgColor: Color, mdText: String, hasCodeBlocks: Boolean): JPanel {
        val parts = if (hasCodeBlocks) splitCodeBlocks(mdText) else listOf(ContentPart(mdText, false))

        val bubble = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            background = bgColor
            border = JBUI.Borders.empty(8, 12)
        }

        val nameLabel = JBLabel(name).apply {
            foreground = nameColor
            font = font.deriveFont(Font.BOLD)
            border = JBUI.Borders.emptyBottom(4)
            alignmentX = Component.LEFT_ALIGNMENT
        }
        bubble.add(nameLabel)

        for (part in parts) {
            if (part.isCode) {
                val codePanel = createCodeBlockPanel(part.code, part.lang, part.filePath, part.lineNum)
                codePanel.alignmentX = Component.LEFT_ALIGNMENT
                bubble.add(codePanel)
            } else {
                val html = MessageRenderer.renderMarkdown(part.code)
                val pane = createMarkdownPane(html, bgColor)
                pane.alignmentX = Component.LEFT_ALIGNMENT
                bubble.add(pane)
            }
        }

        return wrapMargin(bubble)
    }

    private fun createThoughtPanel(text: String, label: String): JPanel {
        val p = palette()
        val panel = JPanel(BorderLayout()).apply {
            background = p.thoughtBg
            border = BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 3, 0, 0, p.thoughtAccent),
                JBUI.Borders.empty(6, 10)
            )
        }
        val htmlLabel = JBLabel("<html><b>$label</b><br/>${MessageRenderer.escapeHtml(text)}</html>").apply {
            foreground = p.thoughtText
            font = font.deriveFont(font.size2D - 1f)
        }
        panel.add(htmlLabel, BorderLayout.CENTER)
        return wrapMargin(panel, 4, 4)
    }

    private fun createToolCallPanel(text: String): JPanel {
        val p = palette()
        val oneLine = singleLine(text, 110)
        val panel = JPanel(BorderLayout()).apply {
            background = p.toolBg
            border = BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 3, 0, 0, p.toolAccent),
                JBUI.Borders.empty(4, 8)
            )
            toolTipText = text
        }
        val label = JBLabel(oneLine).apply {
            foreground = p.toolText
            font = font.deriveFont(font.size2D - 1f)
            toolTipText = text
        }
        panel.add(label, BorderLayout.CENTER)
        return wrapMargin(panel, 2, 2)
    }

    private fun createSystemPanel(text: String): JPanel {
        val panel = JPanel(BorderLayout()).apply {
            background = UIUtil.getPanelBackground()
            border = JBUI.Borders.empty(4, 8)
        }
        val label = JBLabel(text).apply {
            foreground = JBColor.GRAY
            font = font.deriveFont(Font.ITALIC, font.size2D - 1f)
            horizontalAlignment = SwingConstants.CENTER
        }
        panel.add(label, BorderLayout.CENTER)
        return wrapMargin(panel, 4, 4)
    }

    // ===== Markdown HTML Pane (JEditorPane + HTMLEditorKit) =====

    private fun createMarkdownPane(html: String, bgColor: Color): JEditorPane {
        val dark = isDark()
        val p = palette()
        val fs = UIUtil.getLabelFont().size
        val fg = ChatHtmlBuilder.colorHex(p.textPrimary)
        val linkC = ChatHtmlBuilder.colorHex(p.link)
        val inlineBg = ChatHtmlBuilder.colorHex(p.inlineCodeBg)
        val inlineFg = ChatHtmlBuilder.colorHex(p.inlineCodeText)
        val borderC = ChatHtmlBuilder.colorHex(p.codeBorder)
        val thBg = ChatHtmlBuilder.colorHex(if (dark) p.codeHeaderBg else p.codeBg)
        val preBg = ChatHtmlBuilder.colorHex(p.codeBg)
        val quoteFg = ChatHtmlBuilder.colorHex(p.textMuted)

        val kit = HTMLEditorKitBuilder().withWordWrapViewFactory().build()
        val ss = StyleSheet()
        ss.addRule("body { font-family: 'Segoe UI', system-ui, sans-serif; font-size: ${fs}pt; color: $fg; margin: 0; padding: 0; }")
        ss.addRule("p { margin: 6px 0; }")
        ss.addRule("h1 { font-size: ${fs + 8}pt; margin: 14px 0 6px 0; font-weight: bold; }")
        ss.addRule("h2 { font-size: ${fs + 5}pt; margin: 12px 0 6px 0; font-weight: bold; }")
        ss.addRule("h3 { font-size: ${fs + 3}pt; margin: 10px 0 4px 0; font-weight: bold; }")
        ss.addRule("h4 { font-size: ${fs + 1}pt; margin: 8px 0 4px 0; font-weight: bold; }")
        ss.addRule("h5, h6 { font-size: ${fs}pt; margin: 6px 0 4px 0; font-weight: bold; }")
        ss.addRule("a { color: $linkC; }")
        ss.addRule("code { font-family: 'JetBrains Mono', Consolas, monospace; font-size: ${fs - 1}pt; color: $inlineFg; background: $inlineBg; }")
        ss.addRule("pre { font-family: 'JetBrains Mono', Consolas, monospace; font-size: ${fs - 1}pt; margin: 6px 0; padding: 8px; background: $preBg; }")
        ss.addRule("blockquote { border-left: 3px solid $borderC; padding-left: 10px; margin: 6px 0; color: $quoteFg; }")
        ss.addRule("ul, ol { margin: 4px 0; padding-left: 24px; }")
        ss.addRule("li { margin: 2px 0; }")
        ss.addRule("table { border-collapse: collapse; margin: 8px 0; width: 100%; border: 1px solid $borderC; }")
        ss.addRule("th { border: 1px solid $borderC; padding: 6px 10px; background: $thBg; font-weight: bold; text-align: left; }")
        ss.addRule("td { border: 1px solid $borderC; padding: 6px 10px; text-align: left; }")
        ss.addRule("hr { border: 0; border-top: 1px solid $borderC; margin: 8px 0; }")
        ss.addRule("img { max-width: 100%; }")

        kit.styleSheet = ss

        val pane = JEditorPane().apply {
            editorKit = kit
            text = html
            isEditable = false
            isOpaque = false
            putClientProperty(JEditorPane.HONOR_DISPLAY_PROPERTIES, true)
            font = UIUtil.getLabelFont()
            border = JBUI.Borders.empty()
            background = bgColor
        }

        pane.addHyperlinkListener { e ->
            if (e.eventType == HyperlinkEvent.EventType.ACTIVATED) {
                val url = e.url?.toString() ?: e.description ?: return@addHyperlinkListener
                try { Desktop.getDesktop().browse(java.net.URI(url)) } catch (_: Exception) {}
            }
        }

        return pane
    }

    // ===== Code Block with EditorTextField =====

    private fun createCodeBlockPanel(code: String, lang: String?, filePath: String?, lineNum: Int?): JPanel {
        val dark = isDark()
        val p = palette()
        val wrapper = JPanel(BorderLayout()).apply {
            border = BorderFactory.createLineBorder(
                p.codeBorder, 1, true
            )
            background = p.codeBg
        }

        val header = JPanel(BorderLayout()).apply {
            background = p.codeHeaderBg
            border = JBUI.Borders.empty(4, 12)
            maximumSize = Dimension(Int.MAX_VALUE, 30)
        }

        val displayText = filePath ?: lang ?: ""
        val langLabel = JBLabel(displayText).apply {
            font = Font("JetBrains Mono", Font.BOLD, UIUtil.getLabelFont().size - 1)
            foreground = p.codeHeaderText
            if (filePath != null) {
                cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                addMouseListener(object : MouseAdapter() {
                    override fun mouseClicked(e: MouseEvent) { openFile(filePath, lineNum ?: 0) }
                    override fun mouseEntered(e: MouseEvent) {
                        foreground = p.link
                    }
                    override fun mouseExited(e: MouseEvent) {
                        foreground = p.codeHeaderText
                    }
                })
            }
        }

        val copyBtn = JButton("Copy").apply {
            font = UIUtil.getLabelFont().deriveFont(UIUtil.getLabelFont().size2D - 2f)
            isFocusPainted = false; isContentAreaFilled = false; isOpaque = true
            background = if (dark) p.codeHeaderBg.darker() else p.codeHeaderBg
            foreground = p.codeHeaderText
            border = JBUI.Borders.empty(2, 8)
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            val btn = this
            addActionListener {
                Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(code), null)
                btn.text = "Copied!"
                Timer(1500, null).apply {
                    isRepeats = false
                    addActionListener { btn.text = "Copy" }
                    start()
                }
            }
        }

        header.add(langLabel, BorderLayout.WEST)
        header.add(copyBtn, BorderLayout.EAST)
        wrapper.add(header, BorderLayout.NORTH)

        val editorComponent = createEditorForCode(code.trimEnd(), lang, filePath)
        wrapper.add(editorComponent, BorderLayout.CENTER)

        return object : JPanel(BorderLayout()) {
            override fun getMaximumSize(): Dimension = Dimension(Int.MAX_VALUE, preferredSize.height)
        }.apply {
            isOpaque = false
            border = JBUI.Borders.empty(6, 0)
            add(wrapper, BorderLayout.CENTER)
            alignmentX = Component.LEFT_ALIGNMENT
        }
    }

    private fun createEditorForCode(code: String, lang: String?, filePath: String?): JComponent {
        val proj = project ?: return createFallbackCodeArea(code)
        val language = resolveLanguage(lang, filePath)
        val p = palette()

        return try {
            ApplicationManager.getApplication().runReadAction<JComponent> {
                val doc = EditorFactory.getInstance().createDocument(code)
                doc.setReadOnly(true)
                val fileType = language?.associatedFileType
                    ?: FileTypeManager.getInstance().getFileTypeByExtension("txt")
                val field = EditorTextField(doc, proj, fileType, true, false)
                field.setOneLineMode(false)
                field.addSettingsProvider { editor ->
                    editor.settings.apply {
                        isLineNumbersShown = false
                        isFoldingOutlineShown = false
                        additionalLinesCount = 0
                        additionalColumnsCount = 0
                        isCaretRowShown = false
                        isRightMarginShown = false
                        isAdditionalPageAtBottom = false
                        isUseSoftWraps = false
                    }
                    editor.setBorder(JBUI.Borders.empty(6, 10))
                    editor.setCaretEnabled(false)
                    editor.colorsScheme = EditorColorsManager.getInstance().globalScheme
                    editor.setBackgroundColor(p.codeBg)
                }
                val lineCount = code.lines().size.coerceAtLeast(1)
                val scheme = EditorColorsManager.getInstance().globalScheme
                val lineH = maxOf(
                    UIUtil.getLabelFont().size + 2,
                    (scheme.editorFontSize * scheme.lineSpacing).roundToInt()
                )
                // Expand code blocks so vertical scrolling is handled by outer chat container.
                val editorHeight = lineCount * lineH + JBUI.scale(12)
                field.preferredSize = Dimension(0, editorHeight)
                field.minimumSize = Dimension(0, editorHeight)
                field.maximumSize = Dimension(Int.MAX_VALUE, editorHeight)
                field.alignmentX = Component.LEFT_ALIGNMENT
                JBScrollPane(field).apply {
                    border = JBUI.Borders.empty()
                    verticalScrollBarPolicy = JScrollPane.VERTICAL_SCROLLBAR_NEVER
                    horizontalScrollBarPolicy = JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED
                    preferredSize = Dimension(0, editorHeight)
                    minimumSize = Dimension(0, editorHeight)
                    maximumSize = Dimension(Int.MAX_VALUE, editorHeight)
                    alignmentX = Component.LEFT_ALIGNMENT
                }
            }
        } catch (e: Exception) {
            log.warn("EditorTextField creation failed: ${e.message}")
            createFallbackCodeArea(code)
        }
    }

    private fun createFallbackCodeArea(code: String): JTextArea {
        val p = palette()
        return JTextArea(code).apply {
            isEditable = false
            font = Font("JetBrains Mono", Font.PLAIN, UIUtil.getLabelFont().size - 1)
            background = p.codeBg
            foreground = p.textPrimary
            border = JBUI.Borders.empty(6, 10)
            lineWrap = false
        }
    }

    // ===== Markdown Code Block Splitting =====

    private data class ContentPart(
        val code: String,
        val isCode: Boolean = false,
        val lang: String? = null,
        val filePath: String? = null,
        val lineNum: Int? = null
    )

    private fun splitCodeBlocks(text: String): List<ContentPart> {
        val parts = mutableListOf<ContentPart>()
        // Support both ``` and ~~~ fenced code blocks.
        val pattern = Regex("(?m)(^|\\n)(`{3,}|~{3,})([^\\n]*)\\n([\\s\\S]*?)\\n\\2(?=\\n|$)")
        var lastEnd = 0

        for (match in pattern.findAll(text)) {
            if (match.range.first > lastEnd) {
                val before = text.substring(lastEnd, match.range.first)
                if (before.isNotBlank()) parts.add(ContentPart(before))
            }

            val langLine = match.groupValues[3].trim()
            val code = match.groupValues[4]
            var lang: String? = null
            var filePath: String? = null
            var lineNum: Int? = null

            val linePathMatch = Regex("^(\\d+):(\\d+):(.+)").find(langLine)
            if (linePathMatch != null) {
                lineNum = linePathMatch.groupValues[1].toIntOrNull()
                filePath = linePathMatch.groupValues[3]
                lang = filePath.substringAfterLast('.', "")
            } else if (langLine.contains('.') || langLine.contains('/') || langLine.contains('\\')) {
                filePath = langLine
                lang = langLine.substringAfterLast('.', "")
            } else if (langLine.isNotEmpty()) {
                lang = langLine
            }

            parts.add(ContentPart(code, isCode = true, lang = lang, filePath = filePath, lineNum = lineNum))
            lastEnd = match.range.last + 1
        }

        if (lastEnd < text.length) {
            val remaining = text.substring(lastEnd)
            if (remaining.isNotBlank()) parts.add(ContentPart(remaining))
        }

        if (parts.isEmpty()) parts.add(ContentPart(text))
        return parts
    }

    // ===== Utilities =====

    private fun wrapMargin(inner: JComponent, top: Int = 8, bottom: Int = 8): JPanel = object : JPanel(BorderLayout()) {
        override fun getMaximumSize(): Dimension = Dimension(Int.MAX_VALUE, preferredSize.height)
    }.apply {
        isOpaque = false
        border = JBUI.Borders.empty(top, 0, bottom, 0)
        add(inner, BorderLayout.CENTER)
        alignmentX = Component.LEFT_ALIGNMENT
    }

    private fun palette(): ThemePalette {
        val dark = isDark()
        val panelBg = UIUtil.getPanelBackground()
        val textPrimary = UIUtil.getLabelForeground()
        val textMuted = UIUtil.getLabelDisabledForeground()
        val link = if (dark) Color(0x7d, 0xb3, 0xff) else Color(0x1a, 0x73, 0xe8)
        val userBubble = if (dark) blend(panelBg, Color(0x6e, 0xa2, 0xff), 0.38) else blend(panelBg, link, 0.14)
        val agentBubble = blend(panelBg, if (dark) Color.WHITE else Color.BLACK, if (dark) 0.07 else 0.03)
        val thoughtAccent = blend(link, if (dark) Color(0xc8, 0x9a, 0xff) else Color(0x6d, 0x28, 0xd9), if (dark) 0.55 else 0.55)
        val thoughtBg = if (dark) blend(panelBg, thoughtAccent, 0.34) else blend(panelBg, thoughtAccent, 0.10)
        val toolAccent = blend(link, if (dark) Color(0xa6, 0xd1, 0xff) else Color(0x1f, 0x5f, 0xbf), if (dark) 0.20 else 0.45)
        val toolBg = blend(panelBg, toolAccent, if (dark) 0.16 else 0.09)
        val codeBg = blend(panelBg, if (dark) Color.BLACK else Color(0x2f, 0x3b, 0x4a), if (dark) 0.25 else 0.06)
        val codeHeaderBg = blend(codeBg, if (dark) Color.WHITE else Color.BLACK, if (dark) 0.08 else 0.04)
        val codeBorder = blend(codeBg, if (dark) Color.WHITE else Color.BLACK, if (dark) 0.20 else 0.18)
        val inlineCodeBg = blend(codeBg, if (dark) Color(0xff, 0xb7, 0x64) else Color(0xff, 0xd8, 0xa8), if (dark) 0.18 else 0.35)
        val inlineCodeText = if (dark) Color(0xff, 0xd4, 0x9a) else Color(0x8a, 0x4a, 0x00)
        return ThemePalette(
            panelBg = panelBg,
            textPrimary = textPrimary,
            textMuted = textMuted,
            link = link,
            userBubble = userBubble,
            agentBubble = agentBubble,
            thoughtBg = thoughtBg,
            thoughtText = if (dark) Color(0xf0, 0xe8, 0xff) else Color(0x5e, 0x35, 0xb1),
            thoughtAccent = thoughtAccent,
            toolBg = toolBg,
            toolText = if (dark) blend(textPrimary, toolAccent, 0.70) else Color(0x1f, 0x5f, 0xbf),
            toolAccent = toolAccent,
            codeBg = codeBg,
            codeBorder = codeBorder,
            codeHeaderBg = codeHeaderBg,
            codeHeaderText = blend(textMuted, if (dark) Color.WHITE else Color.BLACK, if (dark) 0.20 else 0.10),
            inlineCodeBg = inlineCodeBg,
            inlineCodeText = inlineCodeText
        )
    }

    private fun blend(a: Color, b: Color, ratio: Double): Color {
        val r = ratio.coerceIn(0.0, 1.0)
        val rr = (a.red + (b.red - a.red) * r).roundToInt().coerceIn(0, 255)
        val gg = (a.green + (b.green - a.green) * r).roundToInt().coerceIn(0, 255)
        val bb = (a.blue + (b.blue - a.blue) * r).roundToInt().coerceIn(0, 255)
        return Color(rr, gg, bb)
    }

    private fun summarizeThoughtText(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return ""
        val lines = trimmed.lines().filter { it.isNotBlank() }
        val last = lines.lastOrNull() ?: return trimmed
        return if (lines.size > 1) "... $last" else last
    }

    private fun singleLine(text: String, maxLength: Int): String {
        val normalized = text.replace(Regex("\\s+"), " ").trim()
        return if (normalized.length > maxLength) normalized.take(maxLength - 1) + "\u2026" else normalized
    }

    private fun resolveLanguage(lang: String?, filePath: String?): Language? {
        val effectiveLang = if (filePath != null) {
            val ext = filePath.substringAfterLast('.', "").lowercase()
            EXT_TO_LANG[ext] ?: ext
        } else if (lang != null) {
            EXT_TO_LANG[lang.lowercase()] ?: lang
        } else null

        if (effectiveLang == null) return null

        return Language.findLanguageByID(effectiveLang)
            ?: Language.getRegisteredLanguages().find {
                it.id.equals(effectiveLang, ignoreCase = true) ||
                    it.displayName.equals(effectiveLang, ignoreCase = true)
            }
    }

    private fun openFile(filePath: String, line: Int) {
        val proj = project ?: return
        val basePath = proj.basePath ?: ""
        val resolved = if (filePath.startsWith("/") || filePath.matches(Regex("^[A-Za-z]:.*"))) {
            filePath
        } else {
            "$basePath/$filePath"
        }.replace("\\", "/")

        SwingUtilities.invokeLater {
            val vf = LocalFileSystem.getInstance().findFileByPath(resolved)
            if (vf != null) {
                FileEditorManager.getInstance(proj).openTextEditor(
                    OpenFileDescriptor(proj, vf, maxOf(0, line - 1), 0), true
                )
            }
        }
    }

    private fun isDark(): Boolean =
        ChatHtmlBuilder.isDarkColor(UIUtil.getPanelBackground())

    override fun dispose() {}
}
