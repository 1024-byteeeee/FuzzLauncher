# 下载源

在“设置 → 下载源”中选择后点击“保存设置”，立即生效并在重启后保留。

当前选项参照 HMCL 主分支的 DownloadSource、DownloadProviders 和 HMCLDownloadProvider（2026-10-28 核对）：

| 选项 | 行为 |
|---|---|
| 自动选择 | 根据 Java 系统地区判断；中国地区的版本查询优先官方、文件优先镜像，其他地区优先官方 |
| 官方源 | 优先官方地址；中国地区提供镜像备用地址 |
| 镜像源（BMCLAPI） | 优先镜像，失败或哈希不符时尝试官方备用地址 |

镜像端点与项目实际支持的下载内容：

- BMCLAPI：`https://bmclapi2.bangbang93.com`，用于游戏清单、版本描述、客户端、资源及加载器。其中 Mojang 依赖库使用 `/libraries`，Fabric/Forge/NeoForge Maven 文件使用 `/maven`。
- 腾讯 Maven：`https://mirrors.cloud.tencent.com/nexus/repository/maven-public`，用于 Maven Central 依赖。
- NeoForge 备用 CDN：`https://neoforged.forgecdn.net`，作为 NeoForge 下载的额外备用地址。

HMCL 还有用于模组平台的 MCIM 规则；FuzzLauncher 当前没有 Modrinth/CurseForge 模组下载功能，因此未添加对应的独立选项。南京大学源已移除，旧 `NJU` 和 `BMCLAPI` 配置会迁移为镜像模式；缺少下载源的旧配置使用自动模式。

更换源会清除内存中的版本列表，重新打开下载页即可自动加载。下载页显示来源与网站链接。保留原有哈希校验，未经验证的临时文件不会发布为已下载文件；微软、Xbox 和 Minecraft Services 认证使用独立官方通道。

源码参考（仅核对源地址与优先策略，未引入 HMCL 的实现代码）：

- <https://github.com/HMCL-dev/HMCL/blob/main/HMCL/src/main/java/org/jackhuang/hmcl/setting/DownloadSource.java>
- <https://github.com/HMCL-dev/HMCL/blob/main/HMCL/src/main/java/org/jackhuang/hmcl/setting/DownloadProviders.java>
- <https://github.com/HMCL-dev/HMCL/blob/main/HMCL/src/main/java/org/jackhuang/hmcl/download/HMCLDownloadProvider.java>

服务说明：<https://bmclapidoc.bangbang93.com/>。
