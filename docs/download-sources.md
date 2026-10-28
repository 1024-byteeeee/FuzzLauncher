# 下载源

在“设置 → 下载源”中选择后点击“保存设置”，立即生效并在重启后保留。旧配置默认使用官方源。更换源会清除内存中的版本列表，重新打开下载页即可自动加载。

| 选项 | 镜像范围 |
|---|---|
| 官方源 | 使用版本描述和加载器提供的官方地址 |
| BMCLAPI | 游戏清单、版本描述、客户端、资源、依赖库、Fabric 元数据及 Forge/NeoForge Maven 仓库 |
| 南京大学镜像（依赖库） | Mojang、Fabric、Forge/NeoForge 的 Maven 依赖与安装器文件；其余请求使用官方服务 |

BMCLAPI 服务地址：<https://bmclapi2.bangbang93.com/>，接口说明：<https://bmclapidoc.bangbang93.com/>。
南京大学镜像地址：<https://mirror.nju.edu.cn/bmclapi/>。
下载页显示当前源与网站链接。镜像内容可能缺失或滞后，不保证覆盖所有历史或新版本。

镜像元数据请求失败时重试官方地址；文件下载先尝试镜像，失败或哈希不符时在最后一次尝试使用官方地址。部分文件缓存和 SHA-1/SHA-256 校验机制保持不变。没有提供镜像的地址（包括当前 Adoptium Java 下载）继续使用原地址。

微软账号登录、Xbox/XSTS 与 Minecraft Services 认证和权益查询使用独立的官方 HTTP 通道，不经镜像发送账号令牌。

2026-10-28 验证：两个镜像的 LaunchWrapper 1.12 下载与官方文件 SHA-1 一致；BMCLAPI 返回 921 个游戏清单条目，Minecraft 1.20.4 的 Fabric 查询返回 255 个版本。这些是抽样联网验证，并非所有加载器组合的完整安装验收。
