# 数据驱动配置

启动器在启动时加载配置并校验，通过后供各模块使用。修改后需要重启。

## 内置配置

文件位于 `src/main/resources/com/fuzzlauncher/config/`，随 JAR 发布：

| 文件 | 内容 |
| --- | --- |
| `defaults.json` | 配置版本、新实例内存、默认游戏目录名、隔离方式、下载模式、启动器可见性、默认 JVM 参数 |
| `downloads.json` | 网络超时、重试次数、并发策略、进度更新间隔、下载源名称、镜像规则和备用地址 |
| `services.json` | 版本清单、加载器 Maven/Fabric、资源和依赖库、皮肤、Minecraft 和微软认证服务地址，以及内置 Client ID |
| `compatibility.json` | Java 检测厂商目录、缺少版本元数据时的 Java 兼容规则、NeoForge 旧版组件例外 |
| `jvm.json` | 参考 HMCL 的默认 JVM 参数规则，按 Java 版本、VM 类型、位数和物理内存启用 |

服务地址和镜像规则属于发布数据，不需要改 Java 代码即可维护，但修改内置资源后需要重新打包。
认证协议、路径安全、文件校验、平台常量和历史实例数据迁移仍由代码负责。

## 本地覆盖

在当前启动器数据目录下手动创建 `config/launcher.json`。文件不存在时使用内置值，不会自动创建或改写该文件。
只允许覆盖 `defaults` 和 `network`。未填写的字段继承内置值；数组整体替换，不会逐项合并。

```json
{
    "schemaVersion": 1,
    "defaults": {
        "minMemoryMb": 2048,
        "maxMemoryMb": 8192
    },
    "network": {
        "attempts": 4,
        "maxConcurrency": 32
    }
}
```

完整可用字段以两个内置文件为准。默认 JVM 参数按单个参数组成数组，内存参数由实例内存字段统一生成，不能在默认参数中重复指定。

已有 `settings.json` 和实例字段优先：更改默认内存只影响新建实例，不会批量修改已有实例。
旧实例缺少最小内存时仍采用原来的 256 MB 兼容规则。默认 JVM 附加参数为空，编码等参数由规则生成，已有实例的附加参数仍会接在后面。
运行环境选择优先采用官方版本元数据中的 `javaVersion`，只有缺失时才查询兼容规则表。
只检测本地 Java 或使用实例指定的路径；没有对应版本时弹窗提示，不自动下载。之前缓存的本地运行环境仍可使用。

配置错误会阻止启动并显示原因，包括未知字段、版本不兼容、空值、数字类型错误、越界、最小内存大于最大内存等。
本地覆盖不允许修改认证地址、Client ID、镜像路由及兼容规则。微软登录继续使用内置 Client ID，不增加手动配置入口。
认证请求直接连接服务地址，不经过游戏下载镜像路由。

主要范围限制：内存最小值 128–65536 MB，最大值 512–65536 MB；重试次数 1–10；并发上限 1–128；超时和进度间隔也有范围校验。

## 下载并行数

设置页提供自动模式与手动输入。默认自动模式采用 HMCL 的 `min(可见处理器数 × 4, 64)` 策略；手动输入初始值为 64，范围 1–128。
自动计算策略仍取自 `downloads.json` 的 `threadsPerProcessor` 和 `maxConcurrency`，可以通过前述本地覆盖调整。
选择保存在 `settings.json` 的 `autoDownloadThreads` 和 `downloadThreads` 中，旧文件缺少字段时按默认值补齐。
保存后下一次下载生效，不需要重启。并发限制作用于共享游戏下载客户端的实际文件传输，重试、失败和取消均会释放占用。

## 默认启动参数

默认自定义游戏附加参数为空；基础游戏参数继续来自 Minecraft / 加载器版本描述，账号、路径和令牌在启动时替换。
新实例的最小和最大内存继续默认为 4096 MB。JVM 默认生成规则参考 HMCL：

- 使用所选 Java 所在系统的本地编码，并按 Java 版本选择标准输出和错误输出的编码属性。
- HotSpot 默认采用 G1 和相应调优；用户选择其他 GC 或禁用 G1 时不添加 G1 默认参数。
- 根据 Java 版本、64 位运行环境和物理内存选择 JIT、代码缓存及紧凑对象头参数。
- 默认补充系统代理、Log4j/JNDI 和 Forge 兼容属性；用户或版本已指定的同名选项优先。
- Windows 的驱动兼容参数按平台条件生成；启动器标识使用 FuzzLauncher 自身名称和版本。
- HotSpot 的 `-XX` 参数还必须存在于所选运行环境的 `PrintFlagsFinal` 列表中，避免向新版 Java 传递已移除的默认选项。
- OpenJ9 等其他 VM 不接收 HotSpot 专用调优。不使用忽略未知参数的开关掩盖错误。

参数窗口将可编辑的内存和附加参数与只读的自动参数预览分开显示，自动生成的参数不会写死进实例配置。
预览仅查询已安装的 Java；所需 Java 尚未安装时提示在启动时生成。预览检测在后台进行，关闭窗口会取消。
实际启动时重新检测最终选用的 Java；检测失败会停止启动并报告原因。已有实例的自定义参数不做批量改写。
日志读取使用有效的游戏输出编码，再转为 UTF-8 保存；启动器保持运行和退出后的日志辅助进程都采用相同规则。

参考源码（HMCL main，2026-10-30 查阅）：

- [FetchTask：自动下载并发](https://github.com/HMCL-dev/HMCL/blob/main/HMCLCore/src/main/java/org/jackhuang/hmcl/task/FetchTask.java)
- [LauncherSettings：自动模式和手动初始值](https://github.com/HMCL-dev/HMCL/blob/main/HMCL/src/main/java/org/jackhuang/hmcl/setting/LauncherSettings.java)
- [DefaultLauncher：运行环境相关 JVM 默认值](https://github.com/HMCL-dev/HMCL/blob/main/HMCLCore/src/main/java/org/jackhuang/hmcl/launch/DefaultLauncher.java)

这里独立实现相应行为，未引入 HMCL 运行库。生成策略不包括 HMCL 专有功能所需的代理程序和其他组件。
