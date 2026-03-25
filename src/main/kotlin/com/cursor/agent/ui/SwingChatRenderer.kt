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
                "thought" -> createThoughtPanel(entry.text, "Thought")
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
        removeStreamingComponents()
        addStreamingComponents(toolCallOrder, toolCallElements, currentThought, isThinking, currentAgentMessage, projectBasePath)
        finishLayout()
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
                val detail = ChatHtmlBuilder.extractToolCallDetail(lastInfo, projectBasePath)
                val detailStr = if (detail.isNotEmpty()) " $detail" else ""
                val countStr = if (toolCallOrder.size > 1) " (${toolCallOrder.size} calls)" else ""
                val p = createToolCallPanel("$icon $title$detailStr [${lastInfo.status ?: "pending"}]$countStr")
                p.putClientProperty("streaming", true)
                messagesPanel.add(p)
            }
        }
        if (currentThought.isNotEmpty()) {
            val label = if (isThinking) "\u25cf Thinking..." else "Thought"
            val lines = currentThought.toString().trimEnd().lines()
            val display = if (lines.size <= 1) lines.firstOrNull() ?: "" else "... ${lines.last()}"
            val p = createThoughtPanel(display, label)
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
        messagesPanel.add(Box.createVerticalGlue())
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
        val dark = isDark()
        val bg = if (dark) Color(0x2b, 0x3d, 0x50) else Color(0xe3, 0xf2, 0xfd)
        val nameColor = if (dark) Color(0x6c, 0xb6, 0xff) else Color(0x15, 0x65, 0xc0)
        return createBubble("You", nameColor, bg, text, hasCodeBlocks = false)
    }

    private fun createAgentBubble(text: String): JPanel {
        val dark = isDark()
        val bg = if (dark) Color(0x2d, 0x2d, 0x2d) else Color(0xf5, 0xf5, 0xf5)
        val nameColor = if (dark) Color(0x81, 0xc7, 0x84) else Color(0x2e, 0x7d, 0x32)
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
        val dark = isDark()
        val panel = JPanel(BorderLayout()).apply {
            background = if (dark) Color(0x1a, 0x1a, 0x2e) else Color(0xf9, 0xf5, 0xff)
            border = BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 3, 0, 0,
                    if (dark) Color(0x7c, 0x3a, 0xed) else Color(0xa7, 0x8b, 0xfa)),
                JBUI.Borders.empty(6, 10)
            )
        }
        val color = if (dark) Color(0xc4, 0xb5, 0xfd) else Color(0x6d, 0x28, 0xd9)
        val htmlLabel = JBLabel("<html><b>$label</b><br/>${MessageRenderer.escapeHtml(text)}</html>").apply {
            foreground = color
            font = font.deriveFont(font.size2D - 1f)
        }
        panel.add(htmlLabel, BorderLayout.CENTER)
        return wrapMargin(panel, 4, 4)
    }

    private fun createToolCallPanel(text: String): JPanel {
        val dark = isDark()
        val panel = JPanel(BorderLayout()).apply {
            background = if (dark) Color(0x1a, 0x26, 0x36) else Color(0xf0, 0xf4, 0xff)
            border = BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 3, 0, 0, Color(0x4a, 0x7f, 0xff)),
                JBUI.Borders.empty(4, 8)
            )
        }
        val label = JBLabel(text).apply {
            foreground = if (dark) Color(0x4a, 0x7f, 0xff) else Color(0x29, 0x62, 0xff)
            font = font.deriveFont(font.size2D - 1f)
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
        val fs = UIUtil.getLabelFont().size
        val fg = if (dark) "#ccc" else "#333"
        val linkC = if (dark) "#6cb6ff" else "#1565c0"
        val inlineBg = if (dark) "#3d2a1a" else "#fff5eb"
        val inlineFg = if (dark) "#ffb86c" else "#e06c00"
        val borderC = if (dark) "#444" else "#ddd"
        val thBg = if (dark) "#333" else "#f0f0f0"

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
        ss.addRule("pre { font-family: 'JetBrains Mono', Consolas, monospace; font-size: ${fs - 1}pt; margin: 6px 0; padding: 8px; background: ${if (dark) "#1a1a1a" else "#f4f4f4"}; }")
        ss.addRule("blockquote { border-left: 3px solid $borderC; padding-left: 10px; margin: 6px 0; color: ${if (dark) "#999" else "#666"}; }")
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
        val wrapper = JPanel(BorderLayout()).apply {
            border = BorderFactory.createLineBorder(
                if (dark) Color(0x3d, 0x3d, 0x3d) else Color(0xd8, 0xd8, 0xd8), 1, true
            )
            background = if (dark) Color(0x1a, 0x1a, 0x1a) else Color(0xf4, 0xf4, 0xf4)
        }

        val header = JPanel(BorderLayout()).apply {
            background = if (dark) Color(0x1e, 0x1e, 0x2e) else Color(0xf0, 0xef, 0xf4)
            border = JBUI.Borders.empty(4, 12)
            maximumSize = Dimension(Int.MAX_VALUE, 30)
        }

        val displayText = filePath ?: lang ?: ""
        val langLabel = JBLabel(displayText).apply {
            font = Font("JetBrains Mono", Font.BOLD, UIUtil.getLabelFont().size - 1)
            foreground = if (dark) Color(0x88, 0x88, 0x88) else Color(0x77, 0x77, 0x77)
            if (filePath != null) {
                cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                addMouseListener(object : MouseAdapter() {
                    override fun mouseClicked(e: MouseEvent) { openFile(filePath, lineNum ?: 0) }
                    override fun mouseEntered(e: MouseEvent) {
                        foreground = if (isDark()) Color(0x6c, 0xb6, 0xff) else Color(0x15, 0x65, 0xc0)
                    }
                    override fun mouseExited(e: MouseEvent) {
                        foreground = if (isDark()) Color(0x88, 0x88, 0x88) else Color(0x77, 0x77, 0x77)
                    }
                })
            }
        }

        val copyBtn = JButton("Copy").apply {
            font = UIUtil.getLabelFont().deriveFont(UIUtil.getLabelFont().size2D - 2f)
            isFocusPainted = false; isContentAreaFilled = false; isOpaque = true
            background = if (dark) Color(0x35, 0x35, 0x45) else Color(0xe4, 0xe0, 0xf0)
            foreground = if (dark) Color(0xaa, 0xaa, 0xaa) else Color(0x66, 0x66, 0x66)
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

        return JPanel(BorderLayout()).apply {
            isOpaque = false
            border = JBUI.Borders.empty(6, 0)
            add(wrapper, BorderLayout.CENTER)
            maximumSize = Dimension(Int.MAX_VALUE, Int.MAX_VALUE)
        }
    }

    private fun createEditorForCode(code: String, lang: String?, filePath: String?): JComponent {
        val proj = project ?: return createFallbackCodeArea(code)
        val language = resolveLanguage(lang, filePath)

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
                    }
                    editor.setBorder(JBUI.Borders.empty(6, 10))
                    editor.setCaretEnabled(false)
                    editor.colorsScheme = EditorColorsManager.getInstance().globalScheme
                    editor.setBackgroundColor(
                        if (isDark()) Color(0x1a, 0x1a, 0x1a) else Color(0xf4, 0xf4, 0xf4)
                    )
                }
                val lineCount = code.lines().size.coerceIn(1, 40)
                val lineH = (UIUtil.getLabelFont().size + 5)
                field.preferredSize = Dimension(0, lineCount * lineH + 16)
                field.maximumSize = Dimension(Int.MAX_VALUE, lineCount * lineH + 16)
                field.alignmentX = Component.LEFT_ALIGNMENT
                field
            }
        } catch (e: Exception) {
            log.warn("EditorTextField creation failed: ${e.message}")
            createFallbackCodeArea(code)
        }
    }

    private fun createFallbackCodeArea(code: String): JTextArea {
        val dark = isDark()
        return JTextArea(code).apply {
            isEditable = false
            font = Font("JetBrains Mono", Font.PLAIN, UIUtil.getLabelFont().size - 1)
            background = if (dark) Color(0x1a, 0x1a, 0x1a) else Color(0xf4, 0xf4, 0xf4)
            foreground = if (dark) Color(0xd4, 0xd4, 0xd4) else Color(0x33, 0x33, 0x33)
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
        val pattern = Regex("```([^\\n]*?)\\n([\\s\\S]*?)```", RegexOption.MULTILINE)
        var lastEnd = 0

        for (match in pattern.findAll(text)) {
            if (match.range.first > lastEnd) {
                val before = text.substring(lastEnd, match.range.first).trim()
                if (before.isNotEmpty()) parts.add(ContentPart(before))
            }

            val langLine = match.groupValues[1].trim()
            val code = match.groupValues[2]
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
            val remaining = text.substring(lastEnd).trim()
            if (remaining.isNotEmpty()) parts.add(ContentPart(remaining))
        }

        if (parts.isEmpty()) parts.add(ContentPart(text))
        return parts
    }

    // ===== Utilities =====

    private fun wrapMargin(inner: JComponent, top: Int = 8, bottom: Int = 8): JPanel = JPanel(BorderLayout()).apply {
        isOpaque = false
        border = JBUI.Borders.empty(top, 0, bottom, 0)
        add(inner, BorderLayout.CENTER)
        maximumSize = Dimension(Int.MAX_VALUE, Int.MAX_VALUE)
        alignmentX = Component.LEFT_ALIGNMENT
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

    private fun isDark() = !JBColor.isBright()

    override fun dispose() {}
}
