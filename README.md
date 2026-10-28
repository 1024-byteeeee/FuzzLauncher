# Fuzz Launcher 0.2.0

Windows x64 / JavaFX，Windows 95 风格 Minecraft Java 启动器。

## 已实现

- 实例新建、重命名、移除登记；每个实例可手动输入内存或用按钮调整，也可指定独立 Java 路径（留空自动选择）。
- 全局“全部隔离 / 仅模组隔离 / 不隔离”；实例可继承或覆盖。隔离目录为 `versions/<实例名>/`，不同名称使用不同文件夹；已登记实例的旧 UUID 目录自动迁移，重命名同步移动实例数据。资源和依赖库共享。
- 官方版本清单：正式版、快照、历史版筛选及搜索。
- 原版、Fabric、Forge、NeoForge 客户端自动安装；下载校验、取消、重试、安装失败不登记实例。
- 依赖和资源按 CPU 自动并发（上限 64）；支持下载重试内的 HTTP Range 续传、快速切换备用源和下载速度显示。
- 微软浏览器授权、Xbox / XSTS / Minecraft 权益校验与加密缓存；另提供显式离线登录，保存本地用户名和离线 UUID。
- 游戏 Java 自动检测及缺失时下载，与启动器内置 Java 分开。
- 设置提供自动选择、官方源、镜像源三种模式，源地址参考 HMCL：BMCLAPI、腾讯 Maven，以及 NeoForge 备用 CDN。保存后生效并持久化，支持备用源及文件哈希校验，认证服务始终使用官方地址。
- 游戏输出日志和异常退出提示；Windows 目录锁避免重复运行。

## 运行

最新便携应用位于 `dist/<构建时间>/FuzzLauncher/FuzzLauncher.exe`。
整个目录内置 Java 21 + JavaFX；移动时必须保留 `app`、`runtime` 等全部文件。
仓库只包含源码；便携应用需要在本地构建。

默认数据：`%LOCALAPPDATA%\FuzzLauncher`。游戏根目录可在首次安装前从设置中修改。
首次打开没有演示账号或演示实例。“下载”页自动读取游戏版本列表；选择游戏版本和加载器类型后，自动查询兼容的加载器版本，再选择加载器版本并填写实例名称后安装。已加载的游戏列表在页面切换时复用，刷新按钮可手动重试或更新。

## 微软登录当前状态

Fuzz 的 Microsoft 应用已注册，Client ID 已内置，用户可直接点击“微软登录”，无需手动配置。
Minecraft Services 准入申请已提交，当前登录曾返回 HTTP 403，尚未完成真实账号登录和进入游戏的端到端验收。
微软账号没有权益或认证失败时会阻止启动，不会自动降级成离线身份。离线账号请在“账号 → 离线账号”输入 3–16 位英文字母、数字或下划线，再点击“离线登录”。账号会自动保存，适用于单人游戏和允许离线账号的服务器。
离线登录不访问微软服务；断网游玩前需要准备好游戏文件、依赖、资源和对应的游戏 Java，缺失文件仍会触发下载。
配置步骤见 [docs/microsoft-login.md](docs/microsoft-login.md)。

## 构建和验证

使用完整 JDK 21，设置 `JAVA_HOME` 后运行。Gradle 使用 Groovy DSL（`build.gradle`），可直接导入 IntelliJ IDEA。
仓库自带 Gradle Wrapper，首次构建自动下载 Gradle 和依赖，包括 Windows x64 JavaFX；无需安装 Maven 或单独下载 JavaFX SDK。

```powershell
.\gradlew.bat build           # 编译、运行核心测试并生成 JAR 和应用分发包
.\gradlew.bat run             # 从源码启动
.\build.ps1                  # 同样执行 Gradle build
.\build.ps1 -Preview         # 编译并导出实际 JavaFX 截图
.\build.ps1 -Package         # jlink + jpackage，生成新时间目录，不覆盖旧包
.\build.ps1 -Integration     # 额外联网安装测试；需要较多下载流量

# 单独执行完整安装和 UI 冒烟测试
$env:JAVA_HOME = 'C:\Program Files\Zulu\zulu-21'
.\gradlew.bat test '-Dfuzz.integration=true' '-Dfuzz.uiTests=true'
```

`-Dfuzz.forceInstall=true` 可要求重新执行加载器处理器；默认联网测试复用之前验证过的安装。
联网测试数据在 `build/integration`，不会安装到用户的游戏目录，也不会登录或使用伪造账号启动游戏。
测试报告位于 `build/reports/tests/test/index.html`；UI 测试截图位于 `build/ui-check`。
便携包使用 `jlink` 和 `jpackage`，打包时请使用 JDK 21：`.\build.ps1 -Package -JdkPath '你的JDK21目录'`。

`build/`、`dist/`、旧 Maven 的 `target/`、本地 `tools/`、IDE 配置、截图和账号凭据不提交到仓库。

## 设计与验收

- [架构、目录与边界](docs/architecture.md)
- [下载源范围与回退](docs/download-sources.md)
- [测试与兼容性记录](docs/verification.md)
- [第三方组件说明](THIRD_PARTY_NOTICES.md)

尚未逐一验证所有历史游戏、旧版 Forge 和最新快照组合；支持范围以验收记录为准。
切换隔离模式不会自动迁移旧存档；移除实例只移除登记，保留文件。
实例名称不能重复（忽略大小写），也不能包含 Windows 文件名禁用字符。目录已有未登记数据时会提示冲突，不自动覆盖。基础版本号目录可能继续作为共享游戏文件缓存保留。
