# Cursor Agent Wrapper

一个 DevEco Studio / IntelliJ IDEA 插件，通过 [Agent Client Protocol (ACP)](https://agentclientprotocol.com/) 将 [Cursor Agent](https://cursor.com) 的 AI 编程能力集成到 IDE 中。

插件启动配置的 `agent` CLI 二进制，经 stdio 建立 JSON-RPC 2.0 连接，完成 `initialize → authenticate → session/new (或 session/load) → session/prompt` 的会话流程，让你在 IDE 内直接与 AI Agent 协作编码。

## 功能

### 聊天与交互
- **多 Tab 聊天** — 自定义横向可滚动 Tab 栏，同时管理多个独立会话，每个 Tab 拥有独立的 Agent 连接（独立子进程）
- **流式输出** — 实时显示 Agent 的思考过程（紫色折叠区块）和生成内容
- **模型切换** — 通过 `session/set_config_option` 在会话内无缝切换 AI 模型（Claude、GPT、Gemini 等 26+ 个 ACP 模型），无需断开重连
- **Tab 重命名** — 双击 Tab 标题直接编辑会话名称，自动同步到本地数据库
- **本地图片上传** — 支持选择本地图片或拖拽图片到输入框，作为 ACP `image` content block 发送
- **附件管理** — 支持多图附件展示、单个附件删除、同一路径图片去重

### Agent 能力
- **文件读写** — Agent 可以直接读取和修改项目中的文件（`fs.readTextFile` / `fs.writeTextFile`）
- **终端执行** — Agent 可以在项目目录中创建和管理 shell 子进程（create / output / wait / kill）
- **权限控制** — 每次工具调用前弹窗请求用户授权，也可在设置中开启自动批准
- **操作取消** — 支持随时取消正在执行的 Agent 操作

### 渲染与 UI
- **JCEF 富文本渲染** — 使用内嵌 Chromium 渲染 Markdown，支持 GFM 表格、列表、内联代码等（JTextPane 纯文本作为降级方案）
- **代码块增强** — highlight.js 语法高亮、一键复制按钮、文件路径点击跳转到 IDE 编辑器
- **ETS / ArkTS 支持** — 代码块中 `ets`、`arkts` 语言标记自动映射为 TypeScript 高亮
- **主题适配** — 自动跟随 IDE 深色/浅色主题，包括滚动条、图标、代码高亮配色

### 会话管理
- **会话持久化** — 重启 IDE 自动恢复上一次打开的 Tab 及历史内容
- **上下文恢复** — 通过 ACP `session/load` 尝试恢复 Agent 上下文记忆（需 Agent 支持），replay 历史消息由插件静默处理
- **历史浏览** — 从 `~/.cursor/chats` 本地 SQLite 数据库浏览和重新打开历史会话
- **会话删除** — 支持删除任意会话（含已打开的），删除后自动关闭对应 Tab 并跳转

### 会话 ID 管理
- 每个 Tab 持有一个稳定的 `chatId`，对应 Cursor 本地数据库中的目录名
- 从历史打开的 Tab：`chatId` 为 DB 中的原始 ID，始终不变
- 新建的 Tab：`chatId` 在 ACP 连接成功后由 `session/new` 返回的 sessionId 赋值
- ACP 通信使用 `connection.sessionId`，与 UI 层的 `chatId` 解耦

## 前置条件

1. **Cursor Agent CLI** — 需要安装 [Cursor](https://cursor.com/downloads) 并确保 `agent` 命令在 PATH 中可用

2. **认证** — 运行以下命令登录：
   ```bash
   agent login
   ```
   或在插件设置中配置 API Key / Auth Token

3. **DevEco Studio / IntelliJ IDEA** — 版本 2024.3（build 243）或更高
4. **JDK** — 支持 JDK 17 / 21（构建时自动优先使用 21，没有则回落 17）

## 构建

```bash
# 构建插件
./gradlew buildPlugin

# 运行沙箱 IDE 进行调试
./gradlew runIde
```

构建产物位于 `build/distributions/` 目录。
构建行为说明：
- 可通过环境变量覆盖工具链版本：`JDK_TOOLCHAIN_VERSION=17` 或 `JDK_TOOLCHAIN_VERSION=21`
- IDE 平台优先读取 `DEVECO_STUDIO_HOME` / `IDEA_HOME`，未配置时回退到 `intellijIdeaCommunity("2024.3.5")`

## 安装

1. 构建得到 `.zip` 文件
2. 在 DevEco Studio 中打开 **Settings → Plugins → ⚙️ → Install Plugin from Disk...**
3. 选择 `build/distributions/` 下的 zip 文件
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

> 插件会按以下优先级解析 Agent 可执行文件：设置中的 `Agent binary path` → `CURSOR_AGENT_PATH` 环境变量 → 系统 PATH（`agent` / `agent.exe`）→ 常见安装路径（macOS / Windows）。

## 使用

1. 在 IDE 右侧找到 **Cursor Agent** Tool Window
2. 插件会自动连接到 Agent（或点击工具栏 **Reconnect**）
3. 在底部输入框中输入需求，按 **Enter** 发送（**Shift+Enter** 换行）
4. Agent 流式输出响应，遇到文件修改或终端操作时弹窗请求授权
5. 点击底部模型名称切换 AI 模型
6. 点击 **History** 浏览历史会话，双击打开
7. 点击 **New Chat** 创建新的 Tab 会话
8. 双击 Tab 标题可重命名会话
9. 点击 Tab 上的 × 关闭会话并释放连接
10. 滚轮滚动可横向浏览 Tab 栏
11. 点击 **Upload Image** 选择本地图片，或将图片直接拖拽到输入框
12. 已添加的图片会显示在输入框下方，可点击每张图片右侧 `×` 删除

## 架构

```
src/main/kotlin/com/cursor/agent/
├── acp/                              # ACP 协议层
│   ├── ACPClient.kt                  # JSON-RPC 2.0 客户端，管理 agent CLI 子进程的 stdio 通信
│   ├── ACPModels.kt                  # 协议数据模型（会话、提示、权限、FS/终端请求等）
│   └── JsonRpcMessage.kt             # JSON-RPC 消息定义与分发
├── services/                         # 服务层
│   ├── AgentConnection.kt            # 单个 Agent 连接，封装完整会话生命周期与工具调用实现
│   ├── AgentSessionManager.kt        # 项目级服务，提供 workspace 身份标识
│   └── ChatHistoryService.kt         # Cursor 本地聊天历史读写（SQLite JDBC）
├── settings/                         # 配置层
│   ├── AgentSettings.kt              # 持久化设置（PersistentStateComponent）
│   └── AgentSettingsConfigurable.kt  # Settings UI 面板
└── ui/                               # UI 层
    ├── AgentChatPanel.kt             # 自定义 Tab 栏 + CardLayout 内容切换 + 历史面板
    ├── AgentToolWindowFactory.kt     # Tool Window 注册工厂
    ├── ChatSessionTab.kt             # 单个聊天 Tab（输入、渲染、模型选择、权限弹窗）
    ├── ChatHtmlBuilder.kt            # HTML/CSS 生成 + highlight.js + 主题适配
    ├── ChatRenderer.kt               # JCEF 渲染器（含文件路径跳转）/ JTextPane 降级
    ├── SessionHistoryPanel.kt        # 会话历史列表面板（含删除回调）
    └── MessageRenderer.kt            # Markdown → HTML 转换（IntelliJ GFM Parser）
```

### 数据流

```
User Input → ChatSessionTab → AgentConnection → ACPClient → agent acp (stdio)
                  ↕                  ↕               ↕
             ChatRenderer      session/load      JSON-RPC 2.0
             (JCEF HTML)       session/new       (newline-delimited)
                               session/prompt
                               set_config_option
```

### Tab 架构

插件使用自定义 Tab 栏（`BoxLayout` + `JBScrollPane`）替代 `JTabbedPane`，避免原生 Tab 换行跳动和自定义 header 兼容性问题。每个 `TabButton` 支持点击切换、双击重命名、关闭按钮、hover 效果和选中高亮条。内容区域使用 `CardLayout` 按 Tab 切换。

每个 `ChatSessionTab` 持有独立的 `AgentConnection`，各自管理一个 `agent acp` 子进程。Tab 的打开状态通过 `AgentSettings.projectOpenTabs` 按项目持久化，IDE 重启时自动恢复。

### 会话存储

插件读取 Cursor 本地数据（`~/.cursor/chats/<workspaceHash>/<chatId>/store.db`），通过 SQLite JDBC 解析 `meta` 表获取会话元信息，`blobs` 表获取消息内容。支持 Cursor 使用的 hex 编码和 plain JSON 两种存储格式。

## TODO

- [ ] Token 用量 — 显示每次对话的 token 用量和费用估算
- [ ] 会话导出 — 将对话内容导出为 Markdown 文件
- [ ] Tab 拖拽排序
- [ ] 发布到 JetBrains Marketplace / DevEco 插件市场

## License

[MIT](LICENSE)
