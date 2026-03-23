package com.cursor.agent.ui

import com.vladsch.flexmark.ext.gfm.strikethrough.StrikethroughExtension
import com.vladsch.flexmark.ext.gfm.tasklist.TaskListExtension
import com.vladsch.flexmark.ext.tables.TablesExtension
import com.vladsch.flexmark.html.HtmlRenderer
import com.vladsch.flexmark.parser.Parser
import com.vladsch.flexmark.util.data.MutableDataSet

object MessageRenderer {

    private val options = MutableDataSet().apply {
        set(Parser.EXTENSIONS, listOf(
            TablesExtension.create(),
            StrikethroughExtension.create(),
            TaskListExtension.create()
        ))
        set(HtmlRenderer.SOFT_BREAK, "<br/>\n")
        set(HtmlRenderer.ESCAPE_HTML, false)
    }

    private val parser: Parser = Parser.builder(options).build()
    private val renderer: HtmlRenderer = HtmlRenderer.builder(options).build()

    fun renderMarkdown(text: String, fgColor: String, codeBg: String, codeFg: String, fontSize: Int): String {
        if (text.isBlank()) return ""
        val document = parser.parse(text)
        val rawHtml = renderer.render(document)
        return applyStyles(rawHtml, fgColor, codeBg, codeFg, fontSize)
    }

    private fun applyStyles(html: String, fg: String, codeBg: String, codeFg: String, fontSize: Int): String {
        var result = html

        result = result.replace("<pre><code", "<div style='background:$codeBg;padding:8px 12px;margin:6px 0;'><pre style='margin:0;font-family:monospace;font-size:${fontSize - 1}px;color:$codeFg;'><code")
        result = result.replace("</code></pre>", "</code></pre></div>")

        result = result.replace("<code>", "<code style='background:$codeBg;color:$codeFg;padding:1px 4px;font-size:${fontSize - 1}px;'>")

        result = result.replace("<table>", "<table style='border-collapse:collapse;margin:6px 0;color:$fg;font-size:${fontSize}px;' cellpadding='4' cellspacing='0'>")
        result = result.replace("<th>", "<th style='border:1px solid $fg;padding:4px 8px;font-weight:bold;'>")
        result = result.replace("<th ", "<th style='border:1px solid $fg;padding:4px 8px;font-weight:bold;' ")
        result = result.replace("<td>", "<td style='border:1px solid $fg;padding:4px 8px;'>")
        result = result.replace("<td ", "<td style='border:1px solid $fg;padding:4px 8px;' ")

        result = result.replace("<p>", "<p style='margin:2px 0;color:$fg;'>")
        result = result.replace("<h1>", "<h1 style='font-size:${fontSize + 4}px;margin:8px 0 4px 0;color:$fg;'>")
        result = result.replace("<h2>", "<h2 style='font-size:${fontSize + 2}px;margin:8px 0 4px 0;color:$fg;'>")
        result = result.replace("<h3>", "<h3 style='font-size:${fontSize + 1}px;margin:6px 0 3px 0;color:$fg;'>")
        result = result.replace("<h4>", "<h4 style='font-size:${fontSize}px;margin:6px 0 3px 0;color:$fg;'>")
        result = result.replace("<h5>", "<h5 style='font-size:${fontSize}px;margin:4px 0 2px 0;color:$fg;'>")
        result = result.replace("<h6>", "<h6 style='font-size:${fontSize}px;margin:4px 0 2px 0;color:$fg;'>")

        result = result.replace("<ul>", "<ul style='margin:2px 0;padding-left:20px;color:$fg;'>")
        result = result.replace("<ol>", "<ol style='margin:2px 0;padding-left:20px;color:$fg;'>")
        result = result.replace("<li>", "<li style='margin:1px 0;color:$fg;'>")

        result = result.replace("<blockquote>", "<blockquote style='border-left:3px solid $fg;padding-left:8px;margin:4px 0;color:$fg;'>")

        return result
    }
}
