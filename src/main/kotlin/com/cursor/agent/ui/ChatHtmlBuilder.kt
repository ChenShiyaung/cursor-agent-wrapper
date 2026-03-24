package com.cursor.agent.ui

import com.cursor.agent.acp.ToolCallInfo
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.ui.JBColor
import com.intellij.util.ui.UIUtil
import java.awt.Color

data class ChatEntry(val role: String, val text: String)

class ChatHtmlBuilder {

    fun isDark(): Boolean = !JBColor.isBright()

    fun fontSize(): Int {
        return try {
            val uiSize = UIUtil.getLabelFont().size
            val editorSize = EditorColorsManager.getInstance().globalScheme.editorFontSize
            maxOf(uiSize, editorSize, 12)
        } catch (_: Exception) { 14 }
    }

    fun buildFullHtml(
        chatHistory: List<ChatEntry>,
        hasWelcome: Boolean,
        toolCallOrder: List<String>,
        toolCallElements: Map<String, ToolCallInfo>,
        currentThought: CharSequence,
        isThinking: Boolean,
        currentAgentMessage: CharSequence,
        projectBasePath: String?
    ): String {
        val dark = isDark()
        val fs = fontSize()
        val bg = colorHex(UIUtil.getPanelBackground())
        val fg = colorHex(UIUtil.getLabelForeground())
        val secondaryFg = if (dark) "#999" else "#666"
        val userBubble = if (dark) "#2b3d50" else "#e3f2fd"
        val agentBubble = if (dark) "#2d2d2d" else "#f5f5f5"
        val codeBg = if (dark) "#1a1a1a" else "#f4f4f4"
        val codeFg = if (dark) "#d4d4d4" else "#333"
        val inlineCodeBg = if (dark) "#3d2a1a" else "#fff5eb"
        val inlineCodeFg = if (dark) "#ffb86c" else "#e06c00"
        val inlineCodeBorder = if (dark) "#5c3a1a" else "#ffd9b3"
        val userNameC = if (dark) "#6cb6ff" else "#1565c0"
        val agentNameC = if (dark) "#81c784" else "#2e7d32"
        val thoughtC = if (dark) "#c4b5fd" else "#6d28d9"
        val thoughtBg = if (dark) "#1a1a2e" else "#f9f5ff"
        val thoughtBorder = if (dark) "#7c3aed" else "#a78bfa"
        val toolC = if (dark) "#4a7fff" else "#2962ff"
        val toolBg = if (dark) "#1a2636" else "#f0f4ff"
        val toolBorder = if (dark) "#4a7fff" else "#4a7fff"
        val borderColor = if (dark) "#444" else "#ddd"
        val codeBlockBorder = if (dark) "#3d3d3d" else "#d8d8d8"
        val codeHeaderBg = if (dark) "#1e1e2e" else "#f0eff4"
        val codeHeaderFg = if (dark) "#888" else "#777"

        val hljsTheme = if (dark) "vs2015" else "intellij-light"
        val copyBtnBg = if (dark) "#353545" else "#e4e0f0"
        val copyBtnHover = if (dark) "#454560" else "#d0c8e8"
        val copyBtnFg = if (dark) "#aaa" else "#666"

        val sb = StringBuilder()
        sb.append("""<!DOCTYPE html><html><head><meta charset="utf-8">
<link rel="stylesheet" href="https://cdnjs.cloudflare.com/ajax/libs/highlight.js/11.11.1/styles/$hljsTheme.min.css">
<script src="https://cdnjs.cloudflare.com/ajax/libs/highlight.js/11.11.1/highlight.min.js"></script>
<style>
* { box-sizing: border-box; }
body { font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif; font-size: ${fs}px; color: $fg; background: $bg; margin: 0; padding: 8px; line-height: 1.5; }
.welcome { text-align: center; padding: 30px 10px; color: $secondaryFg; }
.welcome h2 { color: $fg; margin: 0 0 8px 0; }
.welcome p { margin: 4px 0; }
.user-bubble { background: $userBubble; padding: 8px 12px; margin: 8px 0; border-radius: 6px; }
.agent-bubble { background: $agentBubble; padding: 8px 12px; margin: 8px 0; border-radius: 6px; }
.user-name { color: $userNameC; font-weight: bold; margin-bottom: 4px; }
.agent-name { color: $agentNameC; font-weight: bold; margin-bottom: 4px; }
.thought { background: $thoughtBg; border-left: 3px solid $thoughtBorder; padding: 6px 10px; margin: 4px 0; color: $thoughtC; font-size: ${fs - 1}px; border-radius: 0 4px 4px 0; }
.thought-label { font-weight: bold; }
.tool-call { background: $toolBg; border-left: 3px solid $toolBorder; padding: 4px 8px; margin: 2px 0; color: $toolC; font-size: ${fs - 1}px; border-radius: 0 4px 4px 0; }
.system { text-align: center; color: $secondaryFg; font-style: italic; font-size: ${fs - 1}px; margin: 8px 0; }
.code-block-wrapper { position: relative; margin: 8px 0; border-radius: 8px; overflow: hidden; border: 1px solid $codeBlockBorder; }
.code-block-header { display: flex; justify-content: space-between; align-items: center; background: $codeHeaderBg; padding: 4px 12px; font-size: ${fs - 2}px; color: $codeHeaderFg; }
.code-block-header .lang-tag { font-family: 'JetBrains Mono', Consolas, monospace; letter-spacing: 0.3px; font-size: ${fs - 1}px; font-weight: 600; }
.code-block-header .lang-tag a { color: inherit; text-decoration: none; cursor: pointer; }
.code-block-header .lang-tag a:hover { text-decoration: underline; color: $userNameC; }
.copy-btn { background: $copyBtnBg; border: none; border-radius: 4px; color: $copyBtnFg; cursor: pointer; padding: 3px 10px; font-size: ${fs - 2}px; line-height: 1.4; transition: all 0.2s; }
.copy-btn:hover { background: $copyBtnHover; color: $fg; }
.code-block-wrapper pre { margin: 0; border: none; border-radius: 0; }
pre { padding: 12px 14px; border-radius: 8px; overflow-x: auto; font-family: 'JetBrains Mono', Consolas, 'Courier New', monospace; font-size: ${fs - 1}px; line-height: 1.5; border: 1px solid $codeBlockBorder; background: $codeBg; color: $codeFg; margin: 6px 0; }
pre:has(code.hljs) { background: none; }
pre code.hljs { padding: 12px 14px; }
code { font-family: 'JetBrains Mono', Consolas, 'Courier New', monospace; font-size: ${fs - 1}px; }
:not(pre) > code { background: $inlineCodeBg; color: $inlineCodeFg; padding: 2px 6px; border-radius: 4px; border: 1px solid $inlineCodeBorder; font-size: 0.9em; }
table { border-collapse: collapse; margin: 6px 0; width: 100%; }
th, td { border: 1px solid $borderColor; padding: 6px 10px; text-align: left; }
th { background: $codeBg; font-weight: bold; }
blockquote { border-left: 3px solid $borderColor; padding-left: 10px; margin: 6px 0; color: $secondaryFg; }
a { color: $userNameC; }
h1, h2, h3, h4, h5, h6 { margin: 12px 0 6px 0; }
p { margin: 4px 0; }
ul, ol { padding-left: 20px; margin: 4px 0; }
img { max-width: 100%; }
::-webkit-scrollbar { width: 8px; height: 8px; }
::-webkit-scrollbar-track { background: transparent; }
::-webkit-scrollbar-thumb { background: $borderColor; border-radius: 4px; }
::-webkit-scrollbar-thumb:hover { background: $secondaryFg; }
::-webkit-scrollbar-corner { background: transparent; }
</style></head><body>
""")

        if (hasWelcome && chatHistory.isEmpty()) {
            sb.append("""<div class="welcome"><h2>Cursor Agent</h2><p>Agent will auto-connect when the project opens.</p><p style="font-size:${fs - 2}px;">Configure the agent binary path in Settings &gt; Tools &gt; Cursor Agent</p></div>""")
        }

        for (entry in chatHistory) {
            when (entry.role) {
                "user" -> {
                    sb.append("""<div class="user-bubble"><div class="user-name">You</div>""")
                    sb.append(MessageRenderer.renderMarkdown(entry.text))
                    sb.append("</div>")
                }
                "assistant" -> {
                    sb.append("""<div class="agent-bubble"><div class="agent-name">Agent</div>""")
                    sb.append(MessageRenderer.renderMarkdown(entry.text))
                    sb.append("</div>")
                }
                "thought" -> {
                    sb.append("""<div class="thought"><span class="thought-label">Thought</span><br/>""")
                    sb.append(truncateThought(entry.text))
                    sb.append("</div>")
                }
                "tool_call" -> {
                    sb.append("""<div class="tool-call">${MessageRenderer.escapeHtml(entry.text)}</div>""")
                }
                "system" -> {
                    sb.append("""<div class="system">${MessageRenderer.escapeHtml(entry.text)}</div>""")
                }
            }
        }

        if (toolCallOrder.isNotEmpty()) {
            val lastTcId = toolCallOrder.last()
            val info = toolCallElements[lastTcId]
            if (info != null) {
                val icon = when (info.status) { "in_progress" -> "\u25b6"; "completed" -> "\u2713"; "error" -> "\u2717"; else -> "\u25cf" }
                val title = info.title ?: info.kind ?: "tool call"
                val detail = extractToolCallDetail(info, projectBasePath)
                val detailStr = if (detail.isNotEmpty()) " $detail" else ""
                val countStr = if (toolCallOrder.size > 1) " (${toolCallOrder.size} calls)" else ""
                sb.append("""<div class="tool-call">${MessageRenderer.escapeHtml("$icon $title$detailStr [${info.status ?: "pending"}]$countStr")}</div>""")
            }
        }

        if (currentThought.isNotEmpty()) {
            val label = if (isThinking) "\u25cf Thinking..." else "Thought"
            sb.append("""<div class="thought"><span class="thought-label">$label</span><br/>""")
            sb.append(truncateThought(currentThought.toString()))
            sb.append("</div>")
        }

        if (currentAgentMessage.isNotEmpty()) {
            sb.append("""<div class="agent-bubble"><div class="agent-name">Agent</div>""")
            sb.append(MessageRenderer.renderMarkdown(currentAgentMessage.toString()))
            sb.append("</div>")
        }

        sb.append(HIGHLIGHT_SCRIPT)
        return sb.toString()
    }

    private fun truncateThought(thought: String): String {
        val lines = thought.trimEnd().lines()
        return if (lines.size <= 1) {
            MessageRenderer.escapeHtml(lines.firstOrNull() ?: "")
        } else {
            "... " + MessageRenderer.escapeHtml(lines.last())
        }
    }

    companion object {
        fun colorHex(c: Color): String = String.format("#%02x%02x%02x", c.red, c.green, c.blue)
        fun extractToolCallDetail(info: ToolCallInfo, basePath: String?): String {
            val input = info.input ?: return ""
            return try {
                val obj = input.asJsonObject
                val parts = mutableListOf<String>()
                obj.get("path")?.asString?.let { parts.add(shortenPath(it, basePath)) }
                obj.get("command")?.asString?.let { parts.add(it.take(80)) }
                obj.get("pattern")?.asString?.let { parts.add("\"$it\"") }
                obj.get("glob_pattern")?.asString?.let { parts.add(it) }
                obj.get("regex")?.asString?.let { parts.add("/$it/") }
                obj.get("search_term")?.asString?.let { parts.add("\"$it\"") }
                obj.get("description")?.asString?.let { parts.add(it.take(60)) }
                obj.get("url")?.asString?.let { parts.add(it.take(80)) }
                obj.get("old_string")?.asString?.let { parts.add("replace ${it.take(30)}...") }
                parts.joinToString(" | ")
            } catch (_: Exception) { "" }
        }

        fun shortenPath(path: String, basePath: String?): String {
            if (basePath == null) return path
            val n = path.replace("\\", "/")
            val nb = basePath.replace("\\", "/")
            return if (n.startsWith(nb)) ".${n.removePrefix(nb)}"
            else { val p = n.split("/"); if (p.size > 3) ".../${p.takeLast(3).joinToString("/")}" else path }
        }

        fun buildToolCallSummary(toolCallOrder: List<String>, toolCallElements: Map<String, ToolCallInfo>): String {
            val total = toolCallOrder.size
            val errors = toolCallOrder.count { toolCallElements[it]?.status == "error" }
            val lastInfo = toolCallOrder.lastOrNull()?.let { toolCallElements[it] }
            val lastName = lastInfo?.title ?: lastInfo?.kind ?: "tool call"
            val parts = mutableListOf<String>()
            parts.add("\u2713 $total tool call${if (total > 1) "s" else ""}")
            if (errors > 0) parts.add("$errors error${if (errors > 1) "s" else ""}")
            parts.add("last: $lastName")
            return parts.joinToString(" \u00b7 ")
        }

        private val HIGHLIGHT_SCRIPT = """<script>
var extToLang = {'ets':'typescript','arkts':'typescript','ts':'typescript','tsx':'typescript','js':'javascript','jsx':'javascript','kt':'kotlin','java':'java','py':'python','rb':'ruby','rs':'rust','go':'go','c':'c','cpp':'cpp','h':'c','hpp':'cpp','cs':'csharp','swift':'swift','m':'objectivec','sh':'bash','bash':'bash','zsh':'bash','yml':'yaml','yaml':'yaml','md':'markdown','json':'json','xml':'xml','html':'xml','css':'css','scss':'css','sql':'sql','dart':'dart','vue':'xml','svelte':'xml'};
document.querySelectorAll('pre code').forEach(function(block) {
    var clist = block.classList;
    var cls = Array.from(clist).find(function(c){return c.startsWith('language-');});
    var rawLang = cls ? cls.replace('language-','') : '';
    var filePath = null;
    var lineNum = null;
    var hljsLang = rawLang;
    if (rawLang.match(/[\/\\.]/) || rawLang.match(/^\d+:\d+:/)) {
        var pm = rawLang.match(/^(\d+):(\d+):(.+)/);
        if (pm) { lineNum = parseInt(pm[1]); filePath = pm[3]; }
        else if (rawLang.match(/\.\w+$/)) { filePath = rawLang; }
        if (filePath) {
            var ext = filePath.split('.').pop().toLowerCase();
            hljsLang = extToLang[ext] || ext;
        }
    } else if (extToLang[rawLang]) {
        hljsLang = extToLang[rawLang];
    }
    if (cls) { clist.remove(cls); }
    if (hljsLang) { clist.add('language-' + hljsLang); }
    hljs.highlightElement(block);
    var pre = block.parentElement;
    if (pre.parentElement.classList.contains('code-block-wrapper')) return;
    var wrapper = document.createElement('div');
    wrapper.className = 'code-block-wrapper';
    var header = document.createElement('div');
    header.className = 'code-block-header';
    var langTag = document.createElement('span');
    langTag.className = 'lang-tag';
    if (filePath) {
        var a = document.createElement('a');
        a.textContent = filePath;
        a.href = 'javascript:void(0)';
        a.onclick = function(e) { e.preventDefault(); window.__openFile && window.__openFile(filePath, lineNum || 0); };
        a.setAttribute('data-file', filePath);
        if (lineNum) a.setAttribute('data-line', lineNum);
        langTag.appendChild(a);
    } else {
        langTag.textContent = rawLang;
    }
    var btn = document.createElement('button');
    btn.className = 'copy-btn';
    btn.textContent = 'Copy';
    btn.onclick = function() {
        navigator.clipboard.writeText(block.textContent).then(function(){
            btn.textContent = 'Copied!';
            setTimeout(function(){ btn.textContent = 'Copy'; }, 1500);
        });
    };
    header.appendChild(langTag);
    header.appendChild(btn);
    pre.parentNode.insertBefore(wrapper, pre);
    wrapper.appendChild(header);
    wrapper.appendChild(pre);
});
window.scrollTo(0, document.body.scrollHeight);
</script></body></html>"""
    }
}
