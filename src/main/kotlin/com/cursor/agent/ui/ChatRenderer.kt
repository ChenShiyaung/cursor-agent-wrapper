package com.cursor.agent.ui

import com.intellij.openapi.Disposable
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.UIUtil
import org.cef.browser.CefBrowser
import org.cef.browser.CefFrame
import org.cef.handler.CefLoadHandlerAdapter
import javax.swing.JComponent
import javax.swing.JScrollPane
import javax.swing.JTextPane
import javax.swing.SwingUtilities

interface ChatRenderer {
    val component: JComponent
    fun setHtml(html: String)
}

class JcefChatRenderer(parentDisposable: Disposable, private val project: Project? = null) : ChatRenderer {
    private val log = Logger.getInstance(JcefChatRenderer::class.java)
    private val browser: com.intellij.ui.jcef.JBCefBrowser

    init {
        if (!com.intellij.ui.jcef.JBCefApp.isSupported()) {
            throw UnsupportedOperationException("JCEF is not supported")
        }
        browser = com.intellij.ui.jcef.JBCefBrowser()
        Disposer.register(parentDisposable, browser)

        if (project != null) {
            val jsQuery = com.intellij.ui.jcef.JBCefJSQuery.create(browser as com.intellij.ui.jcef.JBCefBrowserBase)
            jsQuery.addHandler { arg ->
                try {
                    val parts = arg.split("|", limit = 2)
                    val line = parts[0].toIntOrNull() ?: 0
                    val filePath = parts.getOrElse(1) { "" }
                    if (filePath.isNotBlank()) openFileInEditor(filePath, line)
                } catch (e: Exception) {
                    log.warn("Failed to open file from link: $arg", e)
                }
                null
            }
            browser.jbCefClient.addLoadHandler(object : CefLoadHandlerAdapter() {
                override fun onLoadEnd(cefBrowser: CefBrowser?, frame: CefFrame?, httpStatusCode: Int) {
                    val inject = jsQuery.inject("line + '|' + path")
                    cefBrowser?.executeJavaScript(
                        "window.__openFile = function(path, line) { $inject };",
                        "about:blank", 0
                    )
                }
            }, browser.cefBrowser)
        }
    }

    private fun openFileInEditor(filePath: String, line: Int) {
        val proj = project ?: return
        val basePath = proj.basePath ?: ""
        val resolvedPath = if (filePath.startsWith("/") || filePath.matches(Regex("^[A-Za-z]:.*"))) {
            filePath
        } else {
            "$basePath/$filePath"
        }.replace("\\", "/")

        SwingUtilities.invokeLater {
            val vf = LocalFileSystem.getInstance().findFileByPath(resolvedPath)
            if (vf != null) {
                val lineIdx = maxOf(0, line - 1)
                FileEditorManager.getInstance(proj).openTextEditor(
                    OpenFileDescriptor(proj, vf, lineIdx, 0), true
                )
            } else {
                log.warn("File not found: $resolvedPath")
            }
        }
    }

    override val component: JComponent get() = browser.component

    override fun setHtml(html: String) {
        browser.loadHTML(html)
    }
}

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
