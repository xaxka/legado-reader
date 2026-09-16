# Changelog

Legado Reader是 [开源阅读APP](https://github.com/gedoor/legado) 的Jetbrains IDE插件版，旨在随时随地在IDE中进行阅读。比如：

- 在IDE中的窗口中
- 在任意一行代码后
- 随时隐藏/显示，任意调节颜色和大小

## [Unreleased]

### Added

- :sparkles: feat: 新增老板键（默认快捷键 Shift+Alt+D，未被 IDEA 默认键位及常见应用占用），一键隐藏/恢复行内阅读与阅读窗口
- :sparkles: feat: 新增离线缓存书籍功能，支持后台 AES-256 加密落盘与断点续传
- :sparkles: feat: 阅读时优先读取本地缓存，支持离线阅读
- :sparkles: feat: ToolWindow 底部展示缓存进度面板，支持取消缓存任务
- :sparkles: feat: 书架右键菜单、章节列表底部按钮均可触发离线缓存
- :memo: docs: CLAUDE.md 增加离线缓存架构说明

### Fixed

- :bug: fix: 修复打开设置页时字体预览报错（颜色按钮未初始化导致 JBColor 构造空指针）
- :bug: fix: 修复向下翻页时文字被截断的行（如只显示 20%）被直接翻过的问题；判定"行是否显示完整"时减去行间隙，只有完整显示的行才会翻过去，被截断的行整体带到下一页顶部完整显示


## [1.5.1] - 2025-12-25

### Added

- :sparkles: feat: 新增字体和行高设置，支持设置中预览字体样式 (4866872)
- :memo: docs: 增加插件的官方市场地址 (cd91d52)

### Changed

- :memo: docs: 使用README中的片段作为插件描述 (60f6977)

### Removed
- :fire: chore: 清理不必提交的配置文件 (5b2ef828)

### Fixed

- :bug: fix: 设置页修改值不会触发isModified，不会重置 (ac91417)
- :bug: fix: 正文页报错后返回书架按钮无效 (822c2f0)
- :bug: fix: 当字体大小为0时，阅读异常 (3d44d22)

## [1.5.0] - 2025-12-21

### Added

- docs: 添加 claude.md (dffa3d7)
- feat: **支持行内阅读模式，可以在代码行后阅读** (a5e06fb)

### Changed

- build: 升级 IntelliJ 插件 SDK 和 Gradle 版本，**支持最低IDE版本：2024.2** (67b7227)
- refactor: 所有 action 实现事件驱动，优化 UI 更新流程  (01e4670)(60bd4d9)
- refactor: 重构地址历史和插件设置管理，不兼容历史设置 (67763dd)
- refactor: 重构数据层 (fd214fb)
- refactor: 彻底移除 CurrentReadData 并改用 ReadingSessionManager (438d047)

[Unreleased]: https://github.com/nancheung/legado-reader/compare/v1.5.1...HEAD
[1.5.1]: https://github.com/nancheung/legado-reader/compare/v1.4.1...v1.5.1
[1.5.0]: https://github.com/nancheung/legado-reader/commits/v1.5.0
