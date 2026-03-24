package com.cursor.agent.ui

import com.cursor.agent.services.ChatHistoryService
import com.cursor.agent.settings.AgentSettings
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.*
import java.security.MessageDigest
import javax.swing.*

class AgentChatPanel(
    private val project: Project,
    private val parentDisposable: Disposable
) : JPanel(BorderLayout()), Disposable {

    private val log = Logger.getInstance(AgentChatPanel::class.java)

    private val tabbedPane = JTabbedPane(JTabbedPane.TOP).apply {
        tabLayoutPolicy = JTabbedPane.WRAP_TAB_LAYOUT
    }
    private val tabs = mutableListOf<ChatSessionTab>()

    private val outerCards = CardLayout()
    private val outerPanel = JPanel(outerCards)
    private val historyPanel: SessionHistoryPanel

    private val toolbar = JPanel(FlowLayout(FlowLayout.LEFT, 4, 2)).apply {
        border = JBUI.Borders.empty(2, 4)
    }

    private val projectKey = project.basePath ?: ""

    init {
        border = JBUI.Borders.empty()

        val historyButton = JButton("History").apply { addActionListener { showHistory() } }
        val newChatButton = JButton("New Chat").apply { addActionListener { openNewChat() } }
        toolbar.add(historyButton)
        toolbar.add(newChatButton)

        tabbedPane.addChangeListener { saveOpenTabs() }

        val chatArea = JPanel(BorderLayout()).apply {
            add(toolbar, BorderLayout.NORTH)
            add(tabbedPane, BorderLayout.CENTER)
        }

        historyPanel = SessionHistoryPanel(
            workspaceHash = workspaceHash(),
            onSessionSelected = { chatId -> onHistorySessionSelected(chatId) },
            onBack = { outerCards.show(outerPanel, "tabs") }
        )

        outerPanel.add(chatArea, "tabs")
        outerPanel.add(historyPanel, "history")
        add(outerPanel, BorderLayout.CENTER)

        Disposer.register(parentDisposable, this)

        restoreOrNewChat()
    }

    fun openNewChat() {
        val tab = ChatSessionTab(project, parentDisposable, null) { t, title ->
            updateTabTitle(t, title)
            saveOpenTabs()
        }
        addTab(tab, "New Chat")
    }

    private fun openHistorySession(chatId: String) {
        val existing = tabs.find { it.effectiveSessionId == chatId || it.historyChatId == chatId }
        if (existing != null) {
            tabbedPane.selectedComponent = existing
            return
        }
        ApplicationManager.getApplication().executeOnPooledThread {
            val dbTitle = ChatHistoryService.readSessionTitle(chatId)
            val displayTitle = if (!dbTitle.isNullOrBlank()) {
                if (dbTitle.length > 20) dbTitle.take(18) + "\u2026" else dbTitle
            } else "Chat"
            SwingUtilities.invokeLater {
                val alreadyOpened = tabs.find { it.effectiveSessionId == chatId || it.historyChatId == chatId }
                if (alreadyOpened != null) {
                    tabbedPane.selectedComponent = alreadyOpened
                    return@invokeLater
                }
                val tab = ChatSessionTab(project, parentDisposable, chatId) { t, title ->
                    updateTabTitle(t, title)
                    saveOpenTabs()
                }
                addTab(tab, displayTitle)
            }
        }
    }

    private fun addTab(tab: ChatSessionTab, title: String) {
        tabs.add(tab)
        tab.onSessionReady = { saveOpenTabs() }
        Disposer.register(this, tab)
        tabbedPane.addTab(title, tab)
        val idx = tabbedPane.indexOfComponent(tab)
        tabbedPane.setTabComponentAt(idx, CloseableTabHeader(title, tab))
        tabbedPane.selectedIndex = idx
        saveOpenTabs()
    }

    private fun closeTab(tab: ChatSessionTab) {
        val idx = tabbedPane.indexOfComponent(tab)
        if (idx < 0) return
        tabbedPane.removeTabAt(idx)
        tabs.remove(tab)
        Disposer.dispose(tab)
        saveOpenTabs()
        if (tabs.isEmpty()) openNewChat()
    }

    private fun restoreOrNewChat() {
        val settings = AgentSettings.getInstance().state
        val saved = settings.projectOpenTabs[projectKey]
        val ids = saved?.split(",")?.filter { it.isNotBlank() } ?: emptyList()
        if (ids.isNotEmpty()) {
            for (chatId in ids) {
                openHistorySession(chatId)
            }
        } else if (settings.autoConnect) {
            openNewChat()
        }
    }

    private fun saveOpenTabs() {
        val ids = tabs.mapNotNull { it.persistentSessionId }
        val joined = ids.joinToString(",")
        AgentSettings.getInstance().state.projectOpenTabs[projectKey] = joined
        log.info("saveOpenTabs: projectKey=$projectKey ids=$joined")
        for (tab in tabs) {
            log.info("  tab: historyChatId=${tab.historyChatId} effectiveSessionId=${tab.effectiveSessionId} persistentSessionId=${tab.persistentSessionId}")
        }
    }

    private fun updateTabTitle(tab: ChatSessionTab, title: String) {
        val idx = tabbedPane.indexOfComponent(tab)
        if (idx < 0) return
        val shortTitle = if (title.length > 20) title.take(18) + "\u2026" else title
        tabbedPane.setTitleAt(idx, shortTitle)
        val header = tabbedPane.getTabComponentAt(idx)
        if (header is CloseableTabHeader) header.setTitle(shortTitle)
    }

    private fun showHistory() {
        val openIds = tabs.mapNotNull { it.effectiveSessionId }.toSet()
        val selectedTab = tabbedPane.selectedComponent as? ChatSessionTab
        val activeId = selectedTab?.effectiveSessionId
        historyPanel.refreshSessions(
            ChatHtmlBuilder().isDark(),
            ChatHtmlBuilder().fontSize(),
            openIds,
            activeId
        )
        outerCards.show(outerPanel, "history")
    }

    private fun onHistorySessionSelected(chatId: String) {
        outerCards.show(outerPanel, "tabs")
        openHistorySession(chatId)
    }

    private fun workspaceHash(): String {
        val settings = com.cursor.agent.settings.AgentSettings.getInstance().state
        val cached = settings.projectWorkspaceHashes[project.basePath ?: ""]
        if (!cached.isNullOrBlank()) return cached
        val path = project.basePath ?: ""
        val md5 = MessageDigest.getInstance("MD5")
        return md5.digest(path.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }

    override fun dispose() {
        // tabs disposed by Disposer tree
    }

    private inner class CloseableTabHeader(
        private var titleText: String,
        private val tab: ChatSessionTab
    ) : JPanel(BorderLayout(6, 0)) {

        private val titleLabel = JBLabel(titleText).apply {
            font = UIUtil.getLabelFont()
            foreground = UIUtil.getLabelForeground()
        }

        private val closeBtn = object : JBLabel("\u00d7") {
            private val normalColor = if (!com.intellij.ui.JBColor.isBright()) Color(0x88, 0x88, 0x88) else Color(0x99, 0x99, 0x99)
            private val hoverColor = if (!com.intellij.ui.JBColor.isBright()) Color(0xff, 0x66, 0x66) else Color(0xcc, 0x33, 0x33)
            private val hoverBg = if (!com.intellij.ui.JBColor.isBright()) Color(0x55, 0x55, 0x55) else Color(0xd8, 0xd8, 0xd8)
            private var isHover = false

            init {
                font = UIUtil.getLabelFont().deriveFont(Font.BOLD, 16f)
                foreground = normalColor
                cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                border = JBUI.Borders.empty(2, 6, 2, 6)
                toolTipText = "Close"
                isOpaque = false
                horizontalAlignment = SwingConstants.CENTER
                verticalAlignment = SwingConstants.CENTER
                addMouseListener(object : java.awt.event.MouseAdapter() {
                    override fun mouseClicked(e: java.awt.event.MouseEvent) { closeTab(tab) }
                    override fun mouseEntered(e: java.awt.event.MouseEvent) { isHover = true; foreground = hoverColor; repaint() }
                    override fun mouseExited(e: java.awt.event.MouseEvent) { isHover = false; foreground = normalColor; repaint() }
                })
            }

            override fun paintComponent(g: Graphics) {
                if (isHover) {
                    val g2 = g.create() as Graphics2D
                    g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                    g2.color = hoverBg
                    g2.fillRoundRect(0, 0, width, height, 6, 6)
                    g2.dispose()
                }
                super.paintComponent(g)
            }
        }

        init {
            isOpaque = false
            border = JBUI.Borders.empty(2, 0)
            add(titleLabel, BorderLayout.CENTER)
            add(closeBtn, BorderLayout.EAST)
        }

        fun setTitle(title: String) {
            titleText = title
            titleLabel.text = title
            titleLabel.foreground = UIUtil.getLabelForeground()
        }
    }
}
