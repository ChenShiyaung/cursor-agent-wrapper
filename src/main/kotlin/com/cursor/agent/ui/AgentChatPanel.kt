package com.cursor.agent.ui

import com.cursor.agent.services.ChatHistoryService
import com.cursor.agent.settings.AgentSettings
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
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

    private val tabs = mutableListOf<ChatSessionTab>()
    private var selectedTab: ChatSessionTab? = null

    private val contentCards = CardLayout()
    private val contentPanel = JPanel(contentCards)

    private val tabBarPanel = object : JPanel() {
        init {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            isOpaque = false
        }
        override fun getPreferredSize(): Dimension {
            var w = 0
            var h = 0
            for (c in components) {
                val ps = c.preferredSize
                w += ps.width
                h = maxOf(h, ps.height)
            }
            val ins = insets
            return Dimension(w + ins.left + ins.right, h + ins.top + ins.bottom)
        }
    }
    private val tabBarScroll = JBScrollPane(tabBarPanel,
        JScrollPane.VERTICAL_SCROLLBAR_NEVER,
        JScrollPane.HORIZONTAL_SCROLLBAR_NEVER
    ).apply {
        border = JBUI.Borders.customLine(UIUtil.getSeparatorColor(), 0, 0, 1, 0)
        isOpaque = false
        viewport.isOpaque = false
        addMouseWheelListener { e ->
            val hBar = horizontalScrollBar
            hBar.value = hBar.value + e.unitsToScroll * hBar.unitIncrement
        }
    }

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

        val topPanel = JPanel(BorderLayout()).apply {
            add(toolbar, BorderLayout.NORTH)
            add(tabBarScroll, BorderLayout.CENTER)
        }

        val chatArea = JPanel(BorderLayout()).apply {
            add(topPanel, BorderLayout.NORTH)
            add(contentPanel, BorderLayout.CENTER)
        }

        historyPanel = SessionHistoryPanel(
            workspaceHash = workspaceHash(),
            onSessionSelected = { chatId -> onHistorySessionSelected(chatId) },
            onSessionDeleted = { chatId -> onHistorySessionDeleted(chatId) },
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
        val existing = tabs.find { it.chatId == chatId }
        if (existing != null) {
            selectTab(existing)
            return
        }
        ApplicationManager.getApplication().executeOnPooledThread {
            val displayTitle = resolveDisplayTitle(chatId)
            SwingUtilities.invokeLater {
                val alreadyOpened = tabs.find { it.chatId == chatId }
                if (alreadyOpened != null) {
                    selectTab(alreadyOpened)
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
            return if (dbTitle.length > 14) dbTitle.take(12) + "\u2026" else dbTitle
        }
        val previews = ChatHistoryService.readSessionMessages(chatId)
        val firstUser = previews.firstOrNull { it.role == "user" }?.content
        if (!firstUser.isNullOrBlank()) {
            val clean = firstUser.replace(Regex("\\s+"), " ").trim()
            return if (clean.length > 14) clean.take(12) + "\u2026" else clean
        }
        return "Chat"
    }

    private fun addTab(tab: ChatSessionTab, title: String) {
        tabs.add(tab)
        tab.onSessionReady = { saveOpenTabs() }
        Disposer.register(this, tab)

        val cardKey = System.identityHashCode(tab).toString()
        contentPanel.add(tab, cardKey)

        val header = TabButton(title, tab)
        tabBarPanel.add(header)
        tabBarPanel.revalidate()
        tabBarPanel.repaint()

        selectTab(tab)
        saveOpenTabs()
    }

    private fun selectTab(tab: ChatSessionTab) {
        if (selectedTab === tab) return
        selectedTab = tab
        val cardKey = System.identityHashCode(tab).toString()
        contentCards.show(contentPanel, cardKey)
        refreshTabBarSelection()
        saveOpenTabs()
    }

    private fun closeTab(tab: ChatSessionTab) {
        val idx = tabs.indexOf(tab)
        if (idx < 0) return

        val cardKey = System.identityHashCode(tab).toString()
        contentPanel.remove(tab)
        tabs.removeAt(idx)

        val header = findTabButton(tab)
        if (header != null) {
            tabBarPanel.remove(header)
            tabBarPanel.revalidate()
            tabBarPanel.repaint()
        }

        Disposer.dispose(tab)
        saveOpenTabs()

        if (tabs.isEmpty()) {
            selectedTab = null
            openNewChat()
        } else {
            val newIdx = idx.coerceAtMost(tabs.size - 1)
            selectTab(tabs[newIdx])
        }
    }

    private fun findTabButton(tab: ChatSessionTab): TabButton? {
        return tabBarPanel.components.filterIsInstance<TabButton>().find { it.tab === tab }
    }

    private fun refreshTabBarSelection() {
        for (comp in tabBarPanel.components) {
            if (comp is TabButton) {
                comp.setActive(comp.tab === selectedTab)
            }
        }
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
        val ids = tabs.mapNotNull { it.chatId }
        val joined = ids.joinToString(",")
        AgentSettings.getInstance().state.projectOpenTabs[projectKey] = joined
    }

    private fun updateTabTitle(tab: ChatSessionTab, title: String) {
        val shortTitle = if (title.length > 14) title.take(12) + "\u2026" else title
        val header = findTabButton(tab)
        header?.setTitle(shortTitle)
    }

    private fun showHistory() {
        val openIds = tabs.mapNotNull { it.chatId }.toSet()
        val activeIds = selectedTab?.chatId?.let { setOf(it) } ?: emptySet()
        historyPanel.refreshSessions(
            ChatHtmlBuilder().isDark(),
            ChatHtmlBuilder().fontSize(),
            openIds,
            activeIds
        )
        outerCards.show(outerPanel, "history")
    }

    private fun onHistorySessionSelected(chatId: String) {
        outerCards.show(outerPanel, "tabs")
        openHistorySession(chatId)
    }

    private fun onHistorySessionDeleted(chatId: String) {
        val tab = tabs.find { it.chatId == chatId } ?: return
        closeTab(tab)
    }

    private fun workspaceHash(): String {
        val settings = AgentSettings.getInstance().state
        val cached = settings.projectWorkspaceHashes[project.basePath ?: ""]
        if (!cached.isNullOrBlank()) return cached
        val path = project.basePath ?: ""
        val md5 = MessageDigest.getInstance("MD5")
        return md5.digest(path.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }

    override fun dispose() {}

    // ─── Custom Tab Button ──────────────────────────────────────────

    private inner class TabButton(
        private var titleText: String,
        val tab: ChatSessionTab
    ) : JPanel(BorderLayout(4, 0)) {

        private var active = false
        private var editing = false
        private var activeEditor: JTextField? = null
        private var globalClickListener: java.awt.event.AWTEventListener? = null

        private val dark = !com.intellij.ui.JBColor.isBright()
        private val activeBg = if (dark) Color(0x3C, 0x3F, 0x41) else Color(0xFF, 0xFF, 0xFF)
        private val inactiveBg = if (dark) Color(0x2B, 0x2B, 0x2B) else Color(0xEC, 0xEC, 0xEC)
        private val hoverBg = if (dark) Color(0x35, 0x38, 0x3A) else Color(0xF5, 0xF5, 0xF5)
        private val activeBottomBar = if (dark) Color(0x4A, 0x88, 0xDA) else Color(0x40, 0x78, 0xC0)

        private val titleLabel = JBLabel(titleText).apply {
            font = UIUtil.getLabelFont()
            foreground = UIUtil.getLabelForeground()
            toolTipText = tab.tabTitle
            border = JBUI.Borders.empty(0, 2)
        }

        private val closeBtnNormalColor = if (dark) Color(0x88, 0x88, 0x88) else Color(0x99, 0x99, 0x99)
        private val closeBtnHoverColor = if (dark) Color(0xff, 0x66, 0x66) else Color(0xcc, 0x33, 0x33)
        private val closeBtnHoverBg = if (dark) Color(0x55, 0x55, 0x55) else Color(0xd8, 0xd8, 0xd8)

        private val closeBtn = object : JBLabel("\u00d7") {
            private var isHover = false

            init {
                font = UIUtil.getLabelFont().deriveFont(Font.BOLD, 14f)
                foreground = closeBtnNormalColor
                cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                border = JBUI.Borders.empty(2, 4, 2, 4)
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

        private var isHover = false

        override fun getMaximumSize(): Dimension = preferredSize
        override fun getMinimumSize(): Dimension = preferredSize

        init {
            isOpaque = false
            border = JBUI.Borders.empty(4, 6, 4, 2)
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            add(titleLabel, BorderLayout.CENTER)
            add(closeBtn, BorderLayout.EAST)


            addMouseListener(object : java.awt.event.MouseAdapter() {
                override fun mousePressed(e: java.awt.event.MouseEvent) {
                    if (editing) { commitEditing(); e.consume(); return }
                    selectTab(tab)
                }
                override fun mouseClicked(e: java.awt.event.MouseEvent) {
                    if (editing) return
                    if (e.clickCount == 2) startEditing()
                }
                override fun mouseEntered(e: java.awt.event.MouseEvent) {
                    if (!editing) { isHover = true; repaint() }
                }
                override fun mouseExited(e: java.awt.event.MouseEvent) {
                    isHover = false; repaint()
                }
            })
            titleLabel.addMouseListener(object : java.awt.event.MouseAdapter() {
                override fun mousePressed(e: java.awt.event.MouseEvent) {
                    if (editing) { commitEditing(); e.consume(); return }
                    selectTab(tab)
                }
                override fun mouseClicked(e: java.awt.event.MouseEvent) {
                    if (editing) return
                    if (e.clickCount == 2) startEditing()
                }
                override fun mouseEntered(e: java.awt.event.MouseEvent) {
                    if (!editing) { isHover = true; repaint() }
                }
                override fun mouseExited(e: java.awt.event.MouseEvent) {
                    isHover = false; repaint()
                }
            })
        }

        override fun paintComponent(g: Graphics) {
            val g2 = g.create() as Graphics2D
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.color = when {
                active -> activeBg
                isHover -> hoverBg
                else -> inactiveBg
            }
            g2.fillRect(0, 0, width, height)
            if (active) {
                g2.color = activeBottomBar
                g2.fillRect(0, height - 2, width, 2)
            }
            g2.dispose()
            super.paintComponent(g)
        }

        fun setActive(isActive: Boolean) {
            active = isActive
            titleLabel.foreground = if (isActive) UIUtil.getLabelForeground() else UIUtil.getLabelDisabledForeground()
            repaint()
        }

        fun setTitle(title: String) {
            titleText = title
            titleLabel.text = title
            titleLabel.toolTipText = tab.tabTitle
            titleLabel.foreground = if (active) UIUtil.getLabelForeground() else UIUtil.getLabelDisabledForeground()
        }

        // ─── Editing ────────────────────────────────────────────

        private fun installGlobalClickInterceptor() {
            val listener = java.awt.event.AWTEventListener { event ->
                if (!editing) return@AWTEventListener
                val me = event as? java.awt.event.MouseEvent ?: return@AWTEventListener
                val src = me.component ?: return@AWTEventListener
                if (me.id == java.awt.event.MouseEvent.MOUSE_PRESSED) {
                    val editor = activeEditor ?: return@AWTEventListener
                    val clickPt = SwingUtilities.convertPoint(src, me.point, editor)
                    if (!editor.contains(clickPt)) {
                        SwingUtilities.invokeLater { commitEditing() }
                        me.consume()
                    }
                }
            }
            globalClickListener = listener
            Toolkit.getDefaultToolkit().addAWTEventListener(
                listener, java.awt.AWTEvent.MOUSE_EVENT_MASK
            )
        }

        private fun removeGlobalClickInterceptor() {
            globalClickListener?.let { Toolkit.getDefaultToolkit().removeAWTEventListener(it) }
            globalClickListener = null
        }

        private fun startEditing() {
            if (editing) return
            editing = true

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
            remove(editor)
            add(titleLabel, BorderLayout.CENTER)
            revalidate()
            repaint()
        }

        private fun applyRename(newTitle: String) {
            val short = if (newTitle.length > 14) newTitle.take(12) + "\u2026" else newTitle
            titleText = short
            titleLabel.text = short
            titleLabel.toolTipText = newTitle
            titleLabel.foreground = if (active) UIUtil.getLabelForeground() else UIUtil.getLabelDisabledForeground()
            tab.setTitleManually(newTitle)

            val sid = tab.chatId
            if (sid != null) {
                ApplicationManager.getApplication().executeOnPooledThread {
                    ChatHistoryService.updateSessionTitle(sid, newTitle)
                    log.info("Tab renamed to '$newTitle' (sid=$sid)")
                }
            }
        }

        fun triggerEditing() {
            if (!editing) startEditing()
        }
    }
}
