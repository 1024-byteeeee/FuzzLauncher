# Fuzz Launcher 0.2.0

Windows x64 / JavaFX，Windows 95 风格 Minecraft Java 启动器。

## 已实现

- 实例新建、重命名、永久删除及刷新；删除前提示无法恢复，刷新可重新扫描磁盘上的可用实例。每个实例可分别手动输入最小/最大内存或用按钮调整，也可指定独立 Java 路径（留空自动选择）；新实例默认最小/最大内存均为 4096 MB。
- 实例详情页可打开“启动参数”窗口，按实例自定义 JVM 参数与游戏附加参数；支持自动换行、统一“恢复默认”及自动 JVM 参数预览，在窗口内修改 -Xms/-Xmx 会同步到详情页的内存设置。
- 全局“全部隔离 / 仅模组隔离 / 不隔离”；实例可继承或覆盖。隔离目录为 `versions/<实例名>/`，不同名称使用不同文件夹；已登记实例的旧 UUID 目录自动迁移，重命名同步移动实例数据。原版基础 JSON、JAR 统一放在启动器根目录的 `baseVerisons/<游戏版本>/`，资源和依赖库共享。
- 官方版本清单：正式版、快照、历史版筛选及搜索。
- 原版、Fabric、Forge、NeoForge 客户端自动安装；下载校验、取消、重试、安装失败不登记实例。
- MCBBS、MultiMC、服务器自动更新、Modrinth 整合包导入导出；独立实例导入、导出内容选择、服务器更新文件校验与本地修改保留。
- 依赖和资源按 CPU 自动并发（上限 64）；支持下载重试内的 HTTP Range 续传、快速切换备用源和下载速度显示。
- 微软浏览器授权、Xbox / XSTS / Minecraft 权益校验与加密缓存；另提供显式离线登录，保存本地用户名和离线 UUID。
- 游戏 Java 本地检测及实例手动指定路径；缺少对应版本时弹窗提示安装，不自动下载，与启动器内置 Java 分开。
- 设置提供自动选择、官方源、镜像源三种模式，源地址参考 HMCL：BMCLAPI、腾讯 Maven，以及 NeoForge 备用 CDN。保存后生效并持久化，支持备用源及文件哈希校验，认证服务始终使用官方地址。
- 游戏输出、启动器日志和异常退出提示；设置可打开日志目录、更改启动器数据目录，保存新路径后自动重启并迁移配置和账号。Windows 目录锁避免重复运行。
- 窗口、任务栏及 Windows 可执行文件使用统一的像素草地方块图标，打包程序内嵌多尺寸图标供资源管理器和任务管理器显示。

## 运行

最新便携应用位于 `dist/<构建时间>/FuzzLauncher/FuzzLauncher.exe`。
整个目录内置 Java 21 + JavaFX；移动时必须保留 `app`、`runtime` 等全部文件。
仓库只包含源码；发布版本的安装程序和便携应用可在 GitHub Releases 下载，也可以在本地构建。

默认数据：`%LOCALAPPDATA%\FuzzLauncher`。游戏根目录可在首次安装前从设置中修改。
首次打开没有演示账号或演示实例。“下载”页自动读取游戏版本列表；选择游戏版本和加载器类型后，自动查询兼容的加载器版本，再选择加载器版本并填写实例名称后安装。已加载的游戏列表在页面切换时复用，刷新按钮可手动重试或更新。

## 微软登录当前状态

Fuzz 的 Microsoft 应用已注册，Client ID 已内置，用户可直接点击“微软登录”，无需手动配置。
“微软登录”会直接打开默认浏览器，在微软网页授权后通过本机回调自动继续验证 Minecraft 正版权益，无需输入设备代码。
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
.\build.ps1 -Installer       # 额外生成当前用户安装程序；需要 Inno Setup 的 ISCC 在 PATH 中
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

## 整合包

实例页支持 MCBBS、MultiMC、服务器自动更新和 Modrinth 整合包的导入导出，也可拖入 ZIP/MRPACK。导出采用三步向导，支持展开目录并逐个勾选模组、配置、资源包及存档，包含半选状态、批量选择、文件大小统计和查找定位；导入可逐个选择可选客户端文件。服务器包在启动前检查更新并保留本地修改。详见[整合包功能](docs/modpacks.md)。

## 自动发布 Windows 安装包

将 `.github/workflows/release-windows.yml` 推送到仓库后，在 GitHub 的 Releases 页面新建版本，选择包含该工作流的提交，填写标签，点击 **Publish release**。工作流不检查标签的版本格式，也不比较标签与项目版本。
正式版和预发布版都会触发 **Windows release**；仅保存草稿或推送标签不会触发。

工作流检出该标签的源码，在 Windows x64 上使用 Zulu JDK 21 和经 SHA-256 校验的 Inno Setup 6.5.4，执行构建及核心测试，然后上传到同一个 Release：

- `FuzzLauncher-<版本>-windows-x64-setup.exe`：简体中文当前用户安装程序，发布者为 `1024_byteeeee`，内置启动器所需的 Java/JavaFX，支持选择安装目录、开始菜单和桌面快捷方式，默认安装到 `%LOCALAPPDATA%\Programs\FuzzLauncher`，无需管理员权限。游戏和数据默认位于程序旁的 `game`、`data` 目录。已有安装的数据不会由安装器迁移或删除。
- `FuzzLauncher-<版本>-windows-x64-portable.zip`：解压后运行的便携版，需保留完整目录。
- `SHA256SUMS.txt`：以上文件的 SHA-256 校验值。

构建和下载文件名中的版本读取自 `gradle.properties` 的 `launcher_version`；标签仅用于定位源码和接收附件的 Release。Windows 原生安装版本使用项目版本的三段数字，预发布后缀保留在下载文件名中。Windows 安装版本要求前两段不大于 255、第三段不大于 65535；同一数字版本的预发布与正式版具有相同的 Windows 安装版本。
便携包和安装程序不附带 README、项目文档或运行时说明文档。
默认运行本地核心测试，不自动进行账号授权、联网安装或桌面交互测试。安装包尚未配置代码签名。

无需添加个人令牌或 Secret，上传使用该仓库的 `GITHUB_TOKEN` 和 `contents: write` 权限。失败后可在 Actions 里重跑，或手动运行 **Windows release** 并输入已有的已发布标签；同名附件会替换。
标签必须指向已包含工作流与新版打包脚本的提交。若仓库启用了不可变 Release，发布后不允许添加附件，需要先关闭该设置，或改用先上传草稿附件再发布的流程。

参考：[GitHub Release 事件](https://docs.github.com/en/actions/reference/workflows-and-actions/events-that-trigger-workflows#release)、[jpackage](https://docs.oracle.com/en/java/javase/21/docs/specs/man/jpackage.html)、[Release 附件上传](https://cli.github.com/manual/gh_release_upload)。

## 设计与验收

- [架构、目录与边界](docs/architecture.md)
- [下载源范围与回退](docs/download-sources.md)
- [测试与兼容性记录](docs/verification.md)
- [第三方组件说明](THIRD_PARTY_NOTICES.md)

尚未逐一验证所有历史游戏、旧版 Forge 和最新快照组合；支持范围以验收记录为准。
切换隔离模式不会自动迁移旧存档。删除实例会永久删除该实例目录内的存档、模组和配置；共享游戏目录内的存档及公共版本缓存会保留。
实例名称不能重复（忽略大小写），也不能包含 Windows 文件名禁用字符。目录已有未登记数据时会提示冲突，不自动覆盖。旧 `game/versions/<游戏版本>/` 中的基础 JSON、JAR 会迁移到启动器根目录的 `baseVerisons`；内容冲突或夹有未登记数据时保留原文件，避免覆盖用户文件。
