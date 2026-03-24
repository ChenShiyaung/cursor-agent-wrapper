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
            val displayTitle = resolveDisplayTitle(chatId)
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

    private fun resolveDisplayTitle(chatId: String): String {
        val dbTitle = ChatHistoryService.readSessionTitle(chatId)
        if (!dbTitle.isNullOrBlank()) {
            return if (dbTitle.length > 12) dbTitle.take(10) + "\u2026" else dbTitle
        }
        val previews = ChatHistoryService.readSessionMessages(chatId)
        val firstUser = previews.firstOrNull { it.role == "user" }?.content
        if (!firstUser.isNullOrBlank()) {
            val clean = firstUser.replace(Regex("\\s+"), " ").trim()
            return if (clean.length > 12) clean.take(10) + "\u2026" else clean
        }
        return "Chat"
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
        val shortTitle = if (title.length > 12) title.take(10) + "\u2026" else title
        tabbedPane.setTitleAt(idx, shortTitle)
        val header = tabbedPane.getTabComponentAt(idx)
        if (header is CloseableTabHeader) header.setTitle(shortTitle)
    }

    private fun showHistory() {
        val openIds = tabs.mapNotNull { it.effectiveSessionId ?: it.historyChatId }.toSet()
        val selectedTab = tabbedPane.selectedComponent as? ChatSessionTab
        val activeId = selectedTab?.effectiveSessionId ?: selectedTab?.historyChatId
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
            toolTipText = tab.tabTitle
        }

        private var editing = false
        private var activeEditor: JTextField? = null

        private val closeBtnNormalColor = if (!com.intellij.ui.JBColor.isBright()) Color(0x88, 0x88, 0x88) else Color(0x99, 0x99, 0x99)
        private val closeBtnHoverColor = if (!com.intellij.ui.JBColor.isBright()) Color(0xff, 0x66, 0x66) else Color(0xcc, 0x33, 0x33)
        private val closeBtnHoverBg = if (!com.intellij.ui.JBColor.isBright()) Color(0x55, 0x55, 0x55) else Color(0xd8, 0xd8, 0xd8)

        private val closeBtn = object : JBLabel("\u00d7") {
            private var isHover = false

            init {
                font = UIUtil.getLabelFont().deriveFont(Font.BOLD, 16f)
                foreground = closeBtnNormalColor
                cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                border = JBUI.Borders.empty(2, 6, 2, 6)
                toolTipText = "Close"
                isOpaque = false
                horizontalAlignment = SwingConstants.CENTER
                verticalAlignment = SwingConstants.CENTER
                addMouseListener(object : java.awt.event.MouseAdapter() {
                    override fun mouseClicked(e: java.awt.event.MouseEvent) {
                        if (editing) { commitEditing(); return }
                        closeTab(tab)
                    }
                    override fun mouseEntered(e: java.awt.event.MouseEvent) {
                        if (editing) return
                        isHover = true; foreground = closeBtnHoverColor; repaint()
                    }
                    override fun mouseExited(e: java.awt.event.MouseEvent) {
                        isHover = false; foreground = closeBtnNormalColor; repaint()
                    }
                })
            }

            override fun paintComponent(g: Graphics) {
                if (isHover && !editing) {
                    val g2 = g.create() as Graphics2D
                    g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                    g2.color = closeBtnHoverBg
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

            val headerMouseHandler = object : java.awt.event.MouseAdapter() {
                private fun dispatch(e: java.awt.event.MouseEvent) {
                    if (editing) return
                    val pt = SwingUtilities.convertPoint(e.component, e.point, tabbedPane)
                    tabbedPane.dispatchEvent(java.awt.event.MouseEvent(
                        tabbedPane, e.id, e.`when`, e.modifiersEx,
                        pt.x, pt.y, e.clickCount, e.isPopupTrigger, e.button
                    ))
                }
                override fun mousePressed(e: java.awt.event.MouseEvent) {
                    if (editing) { commitEditing(); e.consume(); return }
                    val idx = tabbedPane.indexOfTabComponent(this@CloseableTabHeader)
                    if (idx >= 0) tabbedPane.selectedIndex = idx
                    dispatch(e)
                }
                override fun mouseEntered(e: java.awt.event.MouseEvent) { dispatch(e) }
                override fun mouseExited(e: java.awt.event.MouseEvent) { dispatch(e) }
                override fun mouseMoved(e: java.awt.event.MouseEvent) { dispatch(e) }
                override fun mouseClicked(e: java.awt.event.MouseEvent) {
                    if (editing) return
                    if (e.clickCount == 2) startEditing()
                }
            }
            titleLabel.addMouseListener(headerMouseHandler)
            titleLabel.addMouseMotionListener(headerMouseHandler)
            addMouseListener(headerMouseHandler)
            addMouseMotionListener(headerMouseHandler)
        }

        private var globalClickListener: java.awt.event.AWTEventListener? = null

        private fun clearTabbedPaneHover() {
            tabbedPane.dispatchEvent(java.awt.event.MouseEvent(
                tabbedPane, java.awt.event.MouseEvent.MOUSE_EXITED,
                System.currentTimeMillis(), 0, -1, -1, 0, false
            ))
        }

        private fun isInsideTabbedPaneHeader(src: Component, pt: Point): Boolean {
            val ptInPane = SwingUtilities.convertPoint(src, pt, tabbedPane)
            val tabRunHeight = tabbedPane.getBoundsAt(0)?.let { it.y + it.height } ?: 30
            return ptInPane.y < tabRunHeight && tabbedPane.contains(ptInPane)
        }

        private fun installGlobalClickInterceptor() {
            val listener = java.awt.event.AWTEventListener { event ->
                if (!editing) return@AWTEventListener
                val me = event as? java.awt.event.MouseEvent ?: return@AWTEventListener
                val src = me.component ?: return@AWTEventListener

                when (me.id) {
                    java.awt.event.MouseEvent.MOUSE_PRESSED -> {
                        val editor = activeEditor ?: return@AWTEventListener
                        val clickPt = SwingUtilities.convertPoint(src, me.point, editor)
                        if (!editor.contains(clickPt)) {
                            SwingUtilities.invokeLater { commitEditing() }
                            me.consume()
                        }
                    }
                    java.awt.event.MouseEvent.MOUSE_MOVED,
                    java.awt.event.MouseEvent.MOUSE_ENTERED,
                    java.awt.event.MouseEvent.MOUSE_EXITED -> {
                        try {
                            if (src === tabbedPane || SwingUtilities.isDescendingFrom(src, tabbedPane)) {
                                if (tabbedPane.tabCount > 0 && isInsideTabbedPaneHeader(src, me.point)) {
                                    me.consume()
                                    clearTabbedPaneHover()
                                }
                            }
                        } catch (_: Exception) {}
                    }
                }
            }
            globalClickListener = listener
            Toolkit.getDefaultToolkit().addAWTEventListener(
                listener,
                java.awt.AWTEvent.MOUSE_EVENT_MASK or java.awt.AWTEvent.MOUSE_MOTION_EVENT_MASK
            )
        }

        private fun removeGlobalClickInterceptor() {
            globalClickListener?.let { Toolkit.getDefaultToolkit().removeAWTEventListener(it) }
            globalClickListener = null
        }

        private fun startEditing() {
            if (editing) return
            editing = true
            clearTabbedPaneHover()
            tabbedPane.putClientProperty("JTabbedPane.tabHoverEnabled", false)

            val editor = JTextField(tab.tabTitle).apply {
                font = UIUtil.getLabelFont()
                selectAll()
                border = JBUI.Borders.empty(0, 2)
            }
            activeEditor = editor

            editor.addActionListener { commitEditing() }
            editor.addKeyListener(object : java.awt.event.KeyAdapter() {
                override fun keyPressed(e: java.awt.event.KeyEvent) {
                    if (e.keyCode == java.awt.event.KeyEvent.VK_ESCAPE) cancelEditing()
                }
            })

            remove(titleLabel)
            add(editor, BorderLayout.CENTER)
            revalidate()
            repaint()
            editor.requestFocusInWindow()

            installGlobalClickInterceptor()
        }

        private fun commitEditing() {
            val editor = activeEditor ?: return
            if (!editing) return
            val newTitle = editor.text.trim()
            if (newTitle.isNotBlank() && newTitle != tab.tabTitle) {
                applyRename(newTitle)
            }
            finishEditing(editor)
        }

        private fun cancelEditing() {
            val editor = activeEditor ?: return
            finishEditing(editor)
        }

        private fun finishEditing(editor: JTextField) {
            if (!editing) return
            editing = false
            activeEditor = null
            removeGlobalClickInterceptor()
            tabbedPane.putClientProperty("JTabbedPane.tabHoverEnabled", true)
            remove(editor)
            add(titleLabel, BorderLayout.CENTER)
            revalidate()
            repaint()
            tabbedPane.requestFocusInWindow()
        }

        private fun applyRename(newTitle: String) {
            val short = if (newTitle.length > 12) newTitle.take(10) + "\u2026" else newTitle
            titleText = short
            titleLabel.text = short
            titleLabel.toolTipText = newTitle
            titleLabel.foreground = UIUtil.getLabelForeground()
            tab.setTitleManually(newTitle)

            val idx = tabbedPane.indexOfComponent(tab)
            if (idx >= 0) tabbedPane.setTitleAt(idx, short)

            val sid = tab.persistentSessionId
            if (sid != null) {
                ApplicationManager.getApplication().executeOnPooledThread {
                    ChatHistoryService.updateSessionTitle(sid, newTitle)
                    log.info("Tab renamed to '$newTitle' (sid=$sid)")
                }
            }
        }

        fun setTitle(title: String) {
            titleText = title
            titleLabel.text = title
            titleLabel.toolTipText = tab.tabTitle
            titleLabel.foreground = UIUtil.getLabelForeground()
        }
    }
}
