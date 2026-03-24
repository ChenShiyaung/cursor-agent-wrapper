# Changelog

## [1.0.0] - 2026-03-23

### Added
- Multi-tab chat panel with independent Agent connections per tab
- JCEF-based Markdown rendering with syntax highlighting (highlight.js)
- Model switching within sessions (26 ACP models supported)
- Streaming output with real-time thinking process display
- Session persistence — auto-restore open tabs on IDE restart
- Session context recovery via ACP `session/load`
- History panel for browsing and reopening local `.cursor/chats` sessions
- Session deletion with confirmation and local data cleanup
- Code block enhancements: syntax highlighting, file path click-to-open, copy button
- ETS/ArkTS syntax highlighting support
- Theme adaptation for dark/light mode (scrollbar, icons, code blocks)
- Editable tab titles with double-click rename and DB sync
- Permission-based tool execution (file read/write, terminal)
- Configurable Agent binary path, API Key, Auth Token, and endpoint
