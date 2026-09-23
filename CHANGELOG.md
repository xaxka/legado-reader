# Changelog

Legado Reader是 [开源阅读APP](https://github.com/gedoor/legado) 的Jetbrains IDE插件版，旨在随时随地在IDE中进行阅读。比如：

- 在IDE中的窗口中
- 在任意一行代码后
- 随时隐藏/显示，任意调节颜色和大小

## [Unreleased]

## [1.5.2] - 2026-09-23

### Added

- :sparkles: feat: 新增老板键（默认快捷键 Shift+Alt+D，未被 IDEA 默认键位及常见应用占用），一键隐藏/恢复行内阅读与阅读窗口
- :sparkles: feat: 新增离线缓存书籍功能，支持后台 AES-256 加密落盘与断点续传
- :sparkles: feat: 阅读时优先读取本地缓存，支持离线阅读
- :sparkles: feat: ToolWindow 底部展示缓存进度面板，支持取消缓存任务
- :sparkles: feat: 书架右键菜单、章节列表底部按钮均可触发离线缓存
- :sparkles: feat: 服务器下拉列表项右侧内联 ✕ 标记，点击 ✕ 即可删除失效的服务器地址（"离线"虚拟选项不渲染 ✕、不可删除）
- :memo: docs: CLAUDE.md 增加离线缓存架构说明

### Fixed

- :bug: fix: 修复打开设置页时字体预览报错（颜色按钮未初始化导致 JBColor 构造空指针）
- :bug: fix: 修复翻页"多一行"：实测确认 modelToView2D 行矩形高度为整行高（文字高 + 行间距，行间距在行的底部内边距上），"文字是否完整显示"误按行矩形底判定，文字已读全、仅行尾间隙被裁的行被整行带到下一页重看；现按 FontMetrics 文字高判定，文字被截断的行仍整行带到下一页完整显示
- :bug: fix: 翻页相位对齐：按实测行距与真实行间隙计算滚动相位，配合动态底边距（≤行间隙）与首行上边距使视口与行距整除对齐，并为末页预留滚动余量；任何窗口尺寸下（含向上翻页、章节首页、末页）每页恰好显示整数行完整文字，零截断、零重复


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

[Unreleased]: https://github.com/nancheung/legado-reader/compare/v1.5.2...HEAD
[1.5.2]: https://github.com/nancheung/legado-reader/compare/v1.5.1...v1.5.2
[1.5.1]: https://github.com/nancheung/legado-reader/compare/v1.4.1...v1.5.1
[1.5.0]: https://github.com/nancheung/legado-reader/commits/v1.5.0
