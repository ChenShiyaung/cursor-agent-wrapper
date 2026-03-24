# Cursor Agent for DevEco Studio

一个 DevEco Studio / IntelliJ IDEA 插件，通过 [Agent Client Protocol (ACP)](https://agentclientprotocol.com/) 将 Cursor Agent 的 AI 编程能力集成到 IDE 中。

## 功能

- **AI 对话聊天面板** — 在 IDE 右侧 Tool Window 中与 Cursor Agent 进行对话
- **JCEF 渲染** — 使用内嵌 Chromium 浏览器渲染 Markdown，支持代码块、表格、列表等完整格式
- **模型切换** — 支持在会话内无缝切换 AI 模型（Opus 4.6、Sonnet 4.6、GPT-5.4 等 26 个模型）
- **文件读写** — Agent 可以直接读取和修改项目中的文件
- **终端执行** — Agent 可以在项目目录中执行 shell 命令
- **权限控制** — 每次工具调用前请求用户授权，确保安全
- **流式输出** — 实时显示 Agent 的思考过程（折叠显示）和生成内容
- **会话管理** — 自动连接/断开、会话持久化、历史会话浏览（读取 `.cursor/chats` 数据库）
- **主题适配** — 自动跟随 IDE 深色/浅色主题（含滚动条样式）

## 前置条件

1. **Cursor Agent CLI** — 需要安装 Cursor CLI 工具（`agent` 命令）
   ```bash
   # 通过 Cursor 安装 CLI
   # 或从 https://cursor.com/downloads 下载
   ```

2. **认证** — 运行以下命令进行登录：
   ```bash
   agent login
   ```
   或在插件设置中配置 API Key / Auth Token。

3. **DevEco Studio / IntelliJ IDEA** — 版本 2024.3 或更高

## 构建

```bash
# 构建插件
./gradlew buildPlugin

# 运行沙箱 IDE 进行调试
./gradlew runIde
```

构建产物位于 `build/distributions/` 目录。

## 安装

1. 构建得到 `.zip` 文件
2. 在 DevEco Studio 中打开 **Settings → Plugins → ⚙️ → Install Plugin from Disk...**
3. 选择构建产物 zip 文件
4. 重启 IDE

## 配置

进入 **Settings → Tools → Cursor Agent**：

| 设置项 | 说明 | 默认值 |
|--------|------|--------|
| Agent binary path | `agent` CLI 的路径 | `agent` |
| API Key | Cursor API Key（可选，也可用环境变量 `CURSOR_API_KEY`） | — |
| Auth Token | Cursor Auth Token（可选） | — |
| API Endpoint | 自定义 API 端点（可选） | — |
| Auto-connect | 打开项目时自动连接 Agent | true |
| Auto-approve permissions | 自动批准所有工具调用（不推荐） | false |

## 使用

1. 在 IDE 右侧找到 **Cursor Agent** Tool Window
2. 插件会自动连接到 Agent（或点击 **Reconnect**）
3. 在输入框中输入需求，按 **Enter** 发送（**Shift+Enter** 换行）
4. Agent 会流式输出响应，遇到文件修改或终端操作时会请求授权
5. 点击底部模型名称可切换不同的 AI 模型
6. 点击 **Cancel** 可中断当前请求

## 架构

```
src/main/kotlin/com/cursor/agent/
├── acp/
│   ├── ACPClient.kt          # ACP JSON-RPC 客户端（stdio 通信）
│   ├── ACPModels.kt           # ACP 协议数据模型
│   └── JsonRpcMessage.kt      # JSON-RPC 消息定义
├── services/
│   ├── AgentSessionManager.kt # 会话管理 & IDE 功能桥接
│   └── ChatHistoryService.kt  # Cursor 本地聊天历史读取（SQLite）
├── settings/
│   ├── AgentSettings.kt       # 持久化设置
│   └── AgentSettingsConfigurable.kt # 设置 UI
└── ui/
    ├── AgentChatPanel.kt      # 聊天面板 UI + JCEF/JTextPane 渲染
    ├── AgentToolWindowFactory.kt # Tool Window 工厂
    └── MessageRenderer.kt     # Markdown → HTML 转换（org.intellij.markdown）
```

### 核心流程

```
User Input → AgentChatPanel → AgentSessionManager → ACPClient → agent acp (stdio)
                                    ↕                    ↕
                            IDE File System         JSON-RPC 2.0
                            IDE Terminal            (newline-delimited)
```

## 协议

插件通过 ACP (Agent Client Protocol) 与 Cursor Agent CLI 通信：

- **传输**: stdio (标准输入/输出)
- **协议**: JSON-RPC 2.0
- **帧格式**: 换行符分隔的 JSON
- **会话流程**: `initialize` → `authenticate` → `session/new` → `session/prompt`
- **模型切换**: `session/set_config_option` (会话内无缝切换，无需重连)

插件作为 ACP 客户端，实现了以下能力：
- `fs.readTextFile` — 读取文件内容
- `fs.writeTextFile` — 写入文件内容
- `terminal` — 创建/管理终端进程

## TODO

- [ ] **图片上传** — 支持在对话中上传图片，实现多模态交互
- [ ] **模型计费 / Tokens 查询** — 显示每次对话的 token 用量和费用估算
- [ ] **代码块语法高亮** — 引入 highlight.js 或 Prism.js 实现代码块语法着色
- [ ] **消息复制** — 支持一键复制 Agent 回复内容
- [ ] **会话导出** — 将对话内容导出为 Markdown 文件
- [ ] **会话删除** — 删除不需要的会话文件

## License

MIT
