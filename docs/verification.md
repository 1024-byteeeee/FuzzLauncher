# 验证记录

日期：2026-10-28。平台：Windows x64，构建 JDK 21，JavaFX 21.0.2。

## 下载性能与实例运行环境

本地 HTTP 测试验证资源并发上限、已校验缓存不重复下载、中断后的 Range/If-Range 续传，以及服务器忽略 Range 时从头覆盖。保留镜像损坏后切换备用源和最终哈希验证测试。
界面验证手动输入 5000 MB、指定包含空格的 Java 路径、保存和重启恢复，以及另一实例保持原有 2048 MB 和自动 Java 配置。使用本地测试子进程验证指定 Java 路径和 5000 MB 启动设置。
这些验证不等于真实网络提速基准测试，也没有重新安装全部 Minecraft/加载器版本组合。

## 离线账号验证

验证标准离线 UUID、用户名校验、账号保存/重载/退出、旧微软账号文件兼容，以及微软失败不自动转为离线账号。
使用本地编译的测试程序验证离线启动的实际 Java 子进程、用户名/UUID/会话参数和日志输出，不调用微软刷新接口；该测试程序不是 Minecraft 客户端，不能视为所有游戏和加载器版本的实际游玩验收。

## Gradle 迁移验收

- 使用 Groovy DSL 和 Gradle Wrapper 9.7.1，依赖由 Maven Central 自动解析；不依赖本地 Maven 或 JavaFX SDK。
- `gradlew.bat build preparePackage prepareJavafx`：编译、14 个核心测试、应用分发包和打包输入生成通过。联网安装与 UI 测试默认按需启用。
- `gradlew.bat test -Dfuzz.uiTests=true`：14 个核心测试和 1 个 JavaFX 页面冒烟测试通过。
- `build.ps1 -Package -JdkPath <JDK21目录>`：成功生成 `dist/20261028-180940/FuzzLauncher`。
- 新包原生入口 `FuzzLauncher.exe` 在截图模式下生成截图并以退出码 0 退出；包内 Java 的 MSAL、HTTP、本地回调与 DPAPI 加密往返验证通过。
- 本次迁移没有重新执行下述联网安装测试，也没有进行真实微软账号登录验收。

下述安装与旧便携包记录保留为迁移前的验收结果。

## 自动测试

完整测试执行 16 个测试方法，全部通过：

- 14 个核心与回归测试：隔离矩阵、实例持久化、版本继承、平台规则、路径与 Zip 越界、下载校验和取消、DPAPI、旧版参数解析、正版启动门禁、官方映射下载及安装失败回收。
- 1 个联网安装测试：重新执行原版及三个加载器安装，验证最终描述和依赖文件。
- 1 个 JavaFX UI 冒烟测试：实例选择更新详情、四个页面导航和字段边界；Gradle 迁移后输出 `build/ui-check/page-0.png` 至 `page-3.png`。

## 真实下载与安装

同一个 Minecraft 1.20.4 基础版本：

| 类型 | 加载器版本 | 结果 |
|---|---|---|
| 原版 | — | 客户端、库、原生库、资源、日志配置安装成功 |
| Fabric | 0.20.1 | profile 合并及依赖下载成功 |
| Forge | 1.20.4-49.2.13 | 官方处理器、映射清理与客户端补丁输出校验通过 |
| NeoForge | 20.4.253 | 官方处理器、映射和客户端补丁安装成功 |

游戏 Java 17 自动下载并通过 SHA-256 校验，成功运行安装处理器。
NeoForge 旧映射处理器访问旧域名失败的问题，通过统一 HTTP 下载层和官方版本描述解决；没有关闭 TLS 验证。
Forge 声明的 sanitize 操作使用安装器自身对应的 srgutils 版本执行，产物 SHA-1 与 install-profile 声明一致。

## 尚未验收

迁移前尚未注册 Microsoft 应用。后续已注册并内置自有 Client ID、提交应用准入申请，真实账号尝试登录时 Minecraft Services 返回 HTTP 403。
尚未完成 Minecraft 权益及进入游戏的端到端验收。
测试不会用虚构身份实际启动游戏，不能把安装成功等同于所有游戏组合均可运行。
历史版本、旧版 Forge、NeoForge 1.20.1 以及最新游戏版本需要后续扩大兼容性测试。

## 便携包验证

发布包：`dist/20261028-155447/FuzzLauncher`。
清空 JAVA_HOME、将 PATH 限制为 Windows 系统目录后，通过原生 FuzzLauncher.exe 成功生成 JavaFX 实际截图并以退出码 0 退出。
使用包内 Java 验证了 MSAL、HTTP / 本地回调所需模块，以及 Windows DPAPI 加密往返。
便携包内置 Java 21.0.4 和 JavaFX 21.0.2；SLF4J API 与绑定版本统一为 1.7.36。
