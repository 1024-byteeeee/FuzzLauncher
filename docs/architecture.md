# Fuzz Launcher 架构

## 分层与依赖

- `domain`：实例、账号、会话、隔离策略。没有 JavaFX、HTTP 或文件存储依赖。
- `application`：安装、启动用例，以及实例仓库、认证、加载器安装接口。
- `infrastructure`：原子 JSON 文件、HTTP、DPAPI、微软认证、版本描述解析、资源下载、Java 管理和安装处理器。
- `ui`：MVVM。窗口外壳和四个页面独立；ViewModel 只暴露只读状态与操作方法。界面线程不下载游戏或执行安装器。
- `FuzzApplication`：组合根，通过构造器组装依赖，不使用全局服务定位器。

隔离采用 Strategy；加载器采用 Adapter；实例采用 Repository。避免为每个简单数据对象创建无用途接口。
Java 源码使用 google-java-format 1.24.0 格式，2 空格缩进。

## 游戏目录

默认启动器数据在 `%LOCALAPPDATA%/FuzzLauncher`，可在首次安装前修改游戏根目录。

游戏根目录包含共享 `assets`、`libraries` 和 `versions`。
每个实例有独立 UUID 标识，描述文件为 `versions/fuzz-UUID/fuzz-UUID.json`，继承官方基础版本。
隔离时 `--gameDir` 和 `ProcessBuilder.directory` 使用同一个解析结果：`versions/fuzz-UUID`；不隔离时都使用游戏根目录。
全局支持全部隔离、仅模组隔离、共享；实例支持继承、强制隔离、强制共享。
切换模式不会迁移文件，界面会要求确认。移除实例仅取消登记，不删除存档。

## 安装事务

1. 获取官方版本描述、客户端、资源、原生库和日志配置。
2. 在 `.fuzz-staging` 下载、校验加载器安装器。
3. Fabric 使用官方 profile JSON；Forge/NeoForge 使用官方 install-profile 的库和客户端处理器。
4. 映射下载统一通过启动器 HttpClient 和官方版本描述，避免旧版处理器的硬编码网络地址。Forge 的 sanitize/format 保留其对应 srgutils 版本的语义。
5. 执行官方补丁处理器，校验声明的输出 SHA-1，导入共享库。
6. 发布实例描述，解析最终依赖并校验资源，最后原子登记实例；失败描述回收到暂存目录。

取消会中断下载、终止安装处理器子进程。已完成的缓存和诊断文件保留，可重新安装复用缓存。
启动器只使用官方 HTTPS 地址，不关闭证书校验。

## 账号与启动

系统浏览器 + MSAL4J，随后 Xbox Live、XSTS、Minecraft 登录、权益与 profile 验证。
仅有效 Java 版权益可产生可启动会话；不支持离线、试玩、第三方认证或失败回退。
账号文件只存身份资料；MSAL 缓存通过 Windows DPAPI 加密绑定当前 Windows 用户。
每次启动在线验证；资源准备较久时会在启动前重新检查令牌时效。启动命令不写入日志，游戏输出中的当前访问令牌被替换。

游戏 Java 与启动器 Java 分开。先查找对应主版本的本地 Java，缺少时从 Adoptium 下载 x64 JRE，校验 SHA-256。
`jpackage` 应用包保留独立的 Java 21 + JavaFX 运行环境。

## 当前边界

首版面向 Windows x64。配置根目录迁移、镜像源、模组市场、整合包导入和旧实例扫描不在本次范围。
目录锁避免同一配置目录同时运行多个启动器。
安装协议覆盖旧版 Forge 和现代处理器，但实际兼容性以测试记录为准；未逐一运行所有历史游戏和加载器组合。
