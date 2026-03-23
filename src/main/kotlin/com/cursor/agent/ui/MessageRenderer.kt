package com.cursor.agent.ui

import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.html.HtmlGenerator
import org.intellij.markdown.parser.MarkdownParser

object MessageRenderer {

    private val flavour = GFMFlavourDescriptor()
    private val parser = MarkdownParser(flavour)

    fun renderMarkdown(text: String): String {
        if (text.isBlank()) return ""
        val tree = parser.buildMarkdownTreeFromString(text)
        return HtmlGenerator(text, tree, flavour, false).generateHtml()
    }

    fun escapeHtml(text: String): String =
        text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
            .replace("\"", "&quot;").replace("'", "&#39;")
}
