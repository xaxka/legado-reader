# AGENTS.md — AI 代理协作指南

## 项目简介

Legado Reader 是开源阅读 APP（[gedoor/legado](https://github.com/gedoor/legado)）的 **JetBrains IDE 插件版**（不是 Android 应用），允许在 IDE 的 ToolWindow 或编辑器行内阅读，通过阅读 APP 的 Web 服务 HTTP API 通信。本仓库是 [nancheung/legado-reader](https://github.com/nancheung/legado-reader) 的 fork。插件 ID：`com.nancheung.plugins.jetbrains.legado-reader`。

## 技术栈与结构

- **Java 21**（Record、sealed types、pattern matching）为主，Kotlin 2.1.20 插件已启用；Lombok（`@Slf4j` 等）。
- Gradle 9.2.0（Kotlin DSL）+ IntelliJ Platform Gradle Plugin 2.10.4；目标平台 IntelliJ IDEA 2024.2+（build 242，IC）。
- 依赖版本统一在 `gradle/libs.versions.toml`（Version Catalog：`libs.jackson`、`libs.hutool` 等）；插件版本号在 `gradle.properties`。
- 单模块结构：`src/main/java/com/nancheung/`（源码，按 command / event / service / storage / manager / model / presentation 分包）、`src/main/resources/META-INF/plugin.xml`（扩展点、快捷键、postStartupActivity 注册）。

## 构建与 CI

- CI：`.github/workflows/build.yml`（Build & Release）——JDK 21 temurin，执行 `sh gradlew buildPlugin --build-cache --parallel`，产物 `build/distributions/*.zip`；push 到 master / 打 tag 时自动上传 GitHub Release（无 tag 推送发布为 "Dev Build" prerelease）。
- 以下命令**仅作参考，本地禁止执行**：`./gradlew build`、`./gradlew buildPlugin`、`./gradlew runIde`。

## 硬性工作规则（用户长期要求）

1. **禁止本地编译**：不要在本地运行任何构建/编译/测试命令（如 `gradlew`）；改动是否可用以 GitHub CI 编译通过为准。
2. **每完成一个改动立即 commit 并 push**，再进行下一项改动。
3. **所有提交使用 xaxka 身份**（本 clone 已配置 user.name=xaxka，user.email=73456104+xaxka@users.noreply.github.com，不要改动）。
4. **任务结束后清理本地 clone**。

## 架构要点与敏感区

**改代码前必读根目录 `CLAUDE.md`** —— 它是本仓库最详细的架构文档（指令系统/事件系统/状态机/离线缓存子系统），此处仅列最容易踩的约束：

- 架构为指令驱动 + 事件响应（v2.0+）：用户操作经 `CommandBus.dispatch(Command.of(CommandType.*))` 分发到 `CommandHandler`，结果通过 `EventPublisher` 发布 sealed Record 事件，UI 只订阅事件。新增指令 = 新建 Handler + 在 `CommandHandlerInitializer` 注册，不要绕过 CommandBus 直调服务。
- 线程规则：所有 API 调用（`ApiUtil`，基于 Hutool）必须异步（`CompletableFuture`），UI 更新必须回 EDT（`invokeLater`）；状态转换前先查 `ReadingSessionStateMachine`。
- 数据模型是 Java Record：访问器**不带 get 前缀**（`session.chapters()`，不是 `getChapters()`）；状态更新用 `AtomicReference` + 返回新实例。
- 服务一律 Application 级 Light Services（`@Service` + `final` 类），多窗口共享状态，不注册进 plugin.xml。
- `plugin.xml` 与源码类名/包路径强耦合，重命名类时必须同步；启动初始化顺序敏感（`ReaderFactory` 必须先于服务订阅初始化）。
- `updatePlugins.xml` 是自有私服更新入口（指向 Release 的 zip），发版相关改动需谨慎。
- 依赖变更只改 `gradle/libs.versions.toml`；插件版本号/变更日志走 `gradle.properties` + `CHANGELOG.md`（Changelog Plugin 2.4.0）。

## 与上游 fork 的关系

- 上游：nancheung/legado-reader（master 为默认分支，push master 即触发 CI 发布 Dev Build）。
- 同步上游时注意：保留 fork 自己的 `.github/workflows/build.yml` 的发布目标与 `updatePlugins.xml` 中的仓库地址（上游指向 nancheung，不要在合并时丢失本地差异）。
