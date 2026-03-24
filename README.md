# Cursor Agent for DevEco Studio

一个 DevEco Studio / IntelliJ IDEA 插件，通过 [Agent Client Protocol (ACP)](https://agentclientprotocol.com/) 将 Cursor Agent 的 AI 编程能力集成到 IDE 中。

## 功能

- **多 Tab 聊天面板** — 在 IDE 右侧 Tool Window 中同时管理多个独立会话，每个 Tab 拥有独立的 Agent 连接
- **JCEF 渲染** — 使用内嵌 Chromium 浏览器渲染 Markdown，支持代码高亮（highlight.js）、一键复制、表格、列表等
- **模型切换** — 支持在会话内无缝切换 AI 模型（Opus 4.6、Sonnet 4.6、GPT-5.4 等 26 个 ACP 模型）
- **文件读写** — Agent 可以直接读取和修改项目中的文件
- **终端执行** — Agent 可以在项目目录中执行 shell 命令
- **权限控制** — 每次工具调用前请求用户授权，确保安全
- **流式输出** — 实时显示 Agent 的思考过程（折叠紫色区块）和生成内容
- **会话持久化** — 重启 IDE 自动恢复上一次打开的 Tab 和历史内容
- **会话恢复** — 通过 ACP `session/load` 恢复 Agent 上下文记忆（需 Agent 支持）
- **历史会话** — 浏览并重新打开 `.cursor/chats` 数据库中的本地会话记录
- **会话删除** — 支持删除会话（含二次确认），同时清理本地 Cursor 数据
- **代码块增强** — 语法高亮、文件路径点击跳转、ETS/ArkTS 语法支持、一键复制
- **主题适配** — 自动跟随 IDE 深色/浅色主题（含滚动条、图标、代码高亮配色）

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
6. 点击 **History** 浏览历史会话，双击打开
7. 点击 **New Chat** 创建新的 Tab 会话
8. 点击 Tab 上的 × 关闭会话并释放连接

## 架构

```
src/main/kotlin/com/cursor/agent/
├── acp/
│   ├── ACPClient.kt              # ACP JSON-RPC 客户端（stdio 通信）
│   ├── ACPModels.kt              # ACP 协议数据模型
│   └── JsonRpcMessage.kt         # JSON-RPC 消息定义
├── services/
│   ├── AgentConnection.kt        # 单个 Agent 连接（session/new + session/load）
│   ├── AgentSessionManager.kt    # 项目级会话管理服务
│   └── ChatHistoryService.kt     # Cursor 本地聊天历史读取（SQLite）
├── settings/
│   ├── AgentSettings.kt          # 持久化设置（含 Tab 恢复、模型记忆）
│   └── AgentSettingsConfigurable.kt # 设置 UI
└── ui/
    ├── AgentChatPanel.kt         # 多 Tab 容器面板 + 历史切换
    ├── AgentToolWindowFactory.kt # Tool Window 工厂
    ├── ChatSessionTab.kt         # 单个聊天 Tab（输入、渲染、回调）
    ├── ChatHtmlBuilder.kt        # HTML/CSS 生成 + highlight.js 集成
    ├── ChatRenderer.kt           # JCEF 渲染器（支持文件路径跳转）
    ├── SessionHistoryPanel.kt    # 会话历史列表面板
    └── MessageRenderer.kt        # Markdown → HTML 转换
```

### 核心流程

```
User Input → ChatSessionTab → AgentConnection → ACPClient → agent acp (stdio)
                  ↕                  ↕               ↕
             ChatRenderer      session/load      JSON-RPC 2.0
             (JCEF HTML)       session/new       (newline-delimited)
                               session/prompt
```

### 多 Tab 架构

每个 `ChatSessionTab` 持有独立的 `AgentConnection`，各自管理一个 `agent acp` 子进程。Tab 的打开状态通过 `AgentSettings.projectOpenTabs` 持久化，IDE 重启时自动恢复。

## 协议

插件通过 ACP (Agent Client Protocol) 与 Cursor Agent CLI 通信：

- **传输**: stdio (标准输入/输出)
- **协议**: JSON-RPC 2.0
- **帧格式**: 换行符分隔的 JSON
- **会话流程**: `initialize` → `authenticate` → `session/load` (或 `session/new`) → `session/prompt`
- **模型切换**: `session/set_config_option` (会话内无缝切换，无需重连)
- **会话恢复**: `session/load` 恢复 Agent 上下文记忆，replay 历史消息由插件静默处理

插件作为 ACP 客户端，实现了以下能力：
- `fs.readTextFile` — 读取文件内容
- `fs.writeTextFile` — 写入文件内容
- `terminal` — 创建/管理终端进程

## TODO

- [ ] 图片上传 — 支持在对话中上传图片，实现多模态交互
- [ ] 模型计费 / Tokens 查询 — 显示每次对话的 token 用量和费用估算
- [ ] 会话导出 — 将对话内容导出为 Markdown 文件
- [ ] Tab 拖拽排序

## License

MIT
