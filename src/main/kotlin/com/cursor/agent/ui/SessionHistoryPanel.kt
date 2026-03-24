package com.cursor.agent.ui

import com.cursor.agent.services.ChatHistoryService
import com.cursor.agent.services.ChatSession
import com.intellij.openapi.application.ApplicationManager
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.AsyncProcessIcon
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.*
import javax.swing.*

class SessionHistoryPanel(
    private val workspaceHash: String,
    private val onSessionSelected: (String) -> Unit,
    private val onSessionDeleted: (String) -> Unit,
    private val onBack: () -> Unit
) : JPanel(BorderLayout()) {

    private val listModel = DefaultListModel<ChatSession>()
    private val sessionList = JList(listModel)
    private val contentCards = CardLayout()
    private val contentPanel = JPanel(contentCards)

    private val loadingPanel = JPanel(GridBagLayout()).apply {
        val inner = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            add(AsyncProcessIcon("loading").apply { alignmentX = Component.CENTER_ALIGNMENT })
            add(JBLabel("Loading...").apply {
                alignmentX = Component.CENTER_ALIGNMENT
                foreground = UIUtil.getLabelDisabledForeground()
                border = JBUI.Borders.emptyTop(8)
            })
        }
        add(inner)
    }
    private val emptyLabel = JBLabel("No chat history found.", SwingConstants.CENTER).apply {
        foreground = UIUtil.getLabelDisabledForeground()
    }

    init {
        val toolbar = JPanel(BorderLayout(4, 2)).apply {
            border = JBUI.Borders.empty(2, 4)
            val leftPart = JPanel(FlowLayout(FlowLayout.LEFT, 4, 2))
            leftPart.add(JButton("\u2190 Back").apply { addActionListener { onBack() } })
            leftPart.add(JBLabel("Session History").apply {
                font = UIUtil.getLabelFont().deriveFont(Font.BOLD)
            })
            add(leftPart, BorderLayout.WEST)
        }

        sessionList.selectionMode = ListSelectionModel.SINGLE_SELECTION
        sessionList.fixedCellHeight = -1
        sessionList.addMouseListener(object : java.awt.event.MouseAdapter() {
            override fun mouseClicked(e: java.awt.event.MouseEvent) {
                val idx = sessionList.locationToIndex(e.point)
                if (idx < 0) return
                val cellBounds = sessionList.getCellBounds(idx, idx) ?: return
                if (!cellBounds.contains(e.point)) return
                val relX = e.x - cellBounds.x
                val relY = e.y - cellBounds.y
                if (isDeleteButtonHit(relX, relY, cellBounds.width, cellBounds.height)) {
                    deleteSession(listModel.getElementAt(idx), idx)
                } else if (e.clickCount == 2) {
                    onSessionSelected(listModel.getElementAt(idx).chatId)
                }
            }
        })

        val listScrollPane = JBScrollPane(sessionList).apply {
            verticalScrollBarPolicy = JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED
            horizontalScrollBarPolicy = JScrollPane.HORIZONTAL_SCROLLBAR_NEVER
        }

        contentPanel.add(loadingPanel, "loading")
        contentPanel.add(listScrollPane, "list")
        contentPanel.add(emptyLabel, "empty")

        add(toolbar, BorderLayout.NORTH)
        add(contentPanel, BorderLayout.CENTER)
    }

    private fun isDeleteButtonHit(relX: Int, relY: Int, cellW: Int, cellH: Int): Boolean {
        val btnW = 56; val btnH = 22; val rightPad = 10; val bottomPad = 8
        val btnRight = cellW - rightPad; val btnLeft = btnRight - btnW
        val btnBottom = cellH - bottomPad; val btnTop = btnBottom - btnH
        return relX in btnLeft..btnRight && relY in btnTop..btnBottom
    }

    fun refreshSessions(dark: Boolean, fontSize: Int, openSessionIds: Set<String>, activeSessionIds: Set<String> = emptySet()) {
        contentCards.show(contentPanel, "loading")
        listModel.clear()

        ApplicationManager.getApplication().executeOnPooledThread {
            val rawSessions = ChatHistoryService.listSessions(workspaceHash)
            val savedModels = com.cursor.agent.settings.AgentSettings.getInstance().state.sessionModels
            val sessions = rawSessions.map { s ->
                if (s.lastUsedModel.isNullOrBlank()) {
                    val model = savedModels[s.chatId]
                    if (!model.isNullOrBlank()) s.copy(lastUsedModel = model) else s
                } else s
            }

            SwingUtilities.invokeLater {
                listModel.clear()
                if (sessions.isEmpty()) {
                    contentCards.show(contentPanel, "empty")
                } else {
                    sessions.forEach { listModel.addElement(it) }
                    sessionList.cellRenderer = SessionCellRenderer(dark, fontSize, openSessionIds, activeSessionIds)
                    contentCards.show(contentPanel, "list")
                }
            }
        }
    }

    private fun deleteSession(session: ChatSession, index: Int) {
        val confirm = JOptionPane.showConfirmDialog(
            this, "Delete session \"${session.displayName}\"?",
            "Confirm Delete", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE
        )
        if (confirm != JOptionPane.YES_OPTION) return
        val chatId = session.chatId
        ApplicationManager.getApplication().executeOnPooledThread {
            val ok = ChatHistoryService.deleteSession(chatId)
            SwingUtilities.invokeLater {
                if (ok) {
                    listModel.removeElementAt(index)
                    if (listModel.isEmpty) contentCards.show(contentPanel, "empty")
                    onSessionDeleted(chatId)
                }
            }
        }
    }
}

private class SessionCellRenderer(
    private val dark: Boolean,
    private val fs: Int,
    private val openSessionIds: Set<String>,
    private val activeSessionIds: Set<String> = emptySet()
) : ListCellRenderer<ChatSession> {

    override fun getListCellRendererComponent(
        list: JList<out ChatSession>, value: ChatSession,
        index: Int, isSelected: Boolean, cellHasFocus: Boolean
    ): Component {
        val isOpen = value.chatId in openSessionIds
        val isActive = value.chatId in activeSessionIds
        val accentColor = if (dark) Color(0x6c, 0xb6, 0xff) else Color(0x15, 0x65, 0xc0)
        val activeColor = if (dark) Color(0x4f, 0xc3, 0x7f) else Color(0x1b, 0x8a, 0x3e)

        val leftBarColor = when {
            isActive -> activeColor
            isOpen -> accentColor
            else -> null
        }

        val panel = JPanel(BorderLayout(4, 0)).apply {
            border = JBUI.Borders.compound(
                JBUI.Borders.customLine(if (dark) Color(0x38, 0x38, 0x38) else Color(0xe8, 0xe8, 0xe8), 0, 0, 1, 0),
                if (leftBarColor != null) JBUI.Borders.compound(
                    JBUI.Borders.customLine(leftBarColor, 0, 3, 0, 0),
                    JBUI.Borders.empty(8, 8, 8, 6)
                ) else JBUI.Borders.empty(8, 10, 8, 6)
            )
            isOpaque = true
            background = when {
                isSelected -> if (dark) Color(0x2b, 0x3d, 0x50) else Color(0xd6, 0xe8, 0xfa)
                isActive -> if (dark) Color(0x1a, 0x30, 0x22) else Color(0xe0, 0xf5, 0xe8)
                isOpen -> if (dark) Color(0x1e, 0x2e, 0x3e) else Color(0xe8, 0xf0, 0xfc)
                else -> if (dark) Color(0x2b, 0x2b, 0x2b) else Color.WHITE
            }
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        }

        val labelColor = when {
            isActive -> activeColor
            isOpen -> accentColor
            else -> null
        }
        val nameLabel = JBLabel(value.displayName).apply {
            font = UIUtil.getLabelFont().deriveFont(Font.BOLD, fs.toFloat())
            foreground = when {
                isSelected -> UIUtil.getListSelectionForeground(true)
                labelColor != null -> labelColor
                dark -> Color(0xe0, 0xe0, 0xe0)
                else -> Color(0x22, 0x22, 0x22)
            }
        }

        val badgeText = when {
            isActive -> " (active)"
            isOpen -> " (open)"
            else -> null
        }
        val badgeColor = when {
            isActive -> activeColor
            isOpen -> accentColor
            else -> null
        }
        val openBadge = if (badgeText != null) JBLabel(badgeText).apply {
            font = UIUtil.getLabelFont().deriveFont((fs - 2).toFloat())
            foreground = badgeColor
        } else null

        val dateLabel = JBLabel(value.formattedDate).apply {
            font = UIUtil.getLabelFont().deriveFont((fs - 2).toFloat())
            foreground = if (dark) Color(0x88, 0x88, 0x88) else Color(0x99, 0x99, 0x99)
        }

        val nameRow = JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply {
            isOpaque = false; add(nameLabel)
            if (openBadge != null) add(openBadge)
        }

        val topRow = JPanel(BorderLayout()).apply {
            isOpaque = false; add(nameRow, BorderLayout.CENTER); add(dateLabel, BorderLayout.EAST)
        }

        val preview = value.userMessagePreviews.firstOrNull() ?: ""
        val previewLabel = JBLabel(if (preview.isNotBlank()) preview.take(60) else "(empty)").apply {
            font = UIUtil.getLabelFont().deriveFont((fs - 1).toFloat())
            foreground = if (dark) Color(0x80, 0x80, 0x80) else Color(0x88, 0x88, 0x88)
        }

        val modelText = value.lastUsedModel
        val modelDisplay = if (!modelText.isNullOrBlank()) {
            modelText.substringBefore("[").let { if (it.length > 25) it.take(23) + "\u2026" else it }
        } else null

        val bottomRow = JPanel(BorderLayout()).apply {
            isOpaque = false
            if (modelDisplay != null) {
                add(JBLabel(modelDisplay).apply {
                    font = UIUtil.getLabelFont().deriveFont((fs - 2).toFloat())
                    foreground = accentColor; toolTipText = modelText
                }, BorderLayout.WEST)
            }
            add(RoundedDeleteLabel(dark, fs), BorderLayout.EAST)
        }

        topRow.alignmentX = Component.LEFT_ALIGNMENT
        previewLabel.alignmentX = Component.LEFT_ALIGNMENT
        bottomRow.alignmentX = Component.LEFT_ALIGNMENT

        val infoPanel = JPanel().apply {
            isOpaque = false; layout = BoxLayout(this, BoxLayout.Y_AXIS)
            add(topRow); add(Box.createVerticalStrut(2))
            add(previewLabel); add(Box.createVerticalStrut(3))
            add(bottomRow)
        }

        panel.add(infoPanel, BorderLayout.CENTER)
        return panel
    }
}

private class RoundedDeleteLabel(dark: Boolean, fs: Int) : JBLabel("Delete") {
    private val bgColor = if (dark) Color(0x4a, 0x2a, 0x2a) else Color(0xfc, 0xe8, 0xe6)
    private val fgColor = if (dark) Color(0xf0, 0x70, 0x70) else Color(0xc0, 0x39, 0x2b)
    private val borderColor = if (dark) Color(0x6a, 0x3a, 0x3a) else Color(0xf0, 0xc0, 0xb8)

    init {
        font = UIUtil.getLabelFont().deriveFont((fs - 2).toFloat())
        foreground = fgColor; horizontalAlignment = SwingConstants.CENTER
        border = JBUI.Borders.empty(2, 10); isOpaque = false
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
    }

    override fun paintComponent(g: Graphics) {
        val g2 = g.create() as Graphics2D
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g2.color = bgColor; g2.fillRoundRect(0, 0, width, height, 8, 8)
        g2.color = borderColor; g2.drawRoundRect(0, 0, width - 1, height - 1, 8, 8)
        g2.dispose()
        super.paintComponent(g)
    }
}
