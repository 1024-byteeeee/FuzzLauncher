# 整合包导入与导出

实例页提供“导入整合包”和“导出整合包”；导出前先选择一个实例。支持 MCBBS、MultiMC、服务器自动更新整合包和 Modrinth 四种格式。

导入 ZIP 或 MRPACK 后会显示识别出的游戏版本、加载器、整合包版本和说明，允许修改实例名称。导入实例固定为独立游戏目录，不会写入现有实例或共享 mods。游戏与 Fabric、Forge、NeoForge 通过现有安装器安装；解压、下载或安装失败时清理此次创建的实例。已存在的实例和用户文件不覆盖。

导出采用“选择类型 → 填写信息 → 选择文件”的三步向导。类型页提供四种格式卡片，信息页可填写名称、版本、作者、说明和服务器文件地址，文件页使用可展开的复选框目录树，能够精确到单个模组、配置文件或存档。父子选项联动、部分选中显示灰色勾号，支持全选、全不选、恢复推荐、展开/收起全部和按路径查找定位；前后翻页保留选项。目录在后台扫描，显示所选文件数量和大小。完整列出实例目录内的文件，不按文件名、目录名或扩展名过滤；默认推荐项不选择存档、截图、游戏设置和服务器列表，用户可自行勾选或取消任何文件，实际导出内容完全以勾选结果为准。导入同样不使用文件名黑名单，包括模组的 usernamecache.json、data 等配置均正常导入。解压路径仍校验不得越出目标目录，导出不跟随目录链接。Modrinth 导出将选中的内容内嵌于 overrides，支持离线导出，不需要查询远程模组 ID。

实例页也支持拖入一个 ZIP/MRPACK 文件进行识别和导入。Modrinth 导入预览中的可选文件使用独立目录树，能逐项选择；必需文件仍会安装，标记为不支持客户端的文件不会安装。导出选择可保存空目录，重叠目录与文件选择不会产生重复 ZIP 条目。

## 格式

| 类型 | 清单 | 内容目录 | 扩展名 |
| --- | --- | --- | --- |
| MCBBS | mcbbs.packmeta | overrides/ | .zip |
| MultiMC | mmc-pack.json、instance.cfg | .minecraft/ | .zip |
| 服务器自动更新 | server-manifest.json | overrides/ | .zip |
| Modrinth | modrinth.index.json | overrides/、client-overrides/ | .mrpack |

Modrinth 导入验证下载文件的 SHA-1、SHA-512 和大小，支持候选下载地址、可选客户端文件选择及客户端覆盖层，不安装声明为仅服务器使用的文件。MCBBS 和服务器包验证清单中的 SHA-1。支持 MultiMC 外层实例文件夹，以及 Minecraft、Fabric、Forge、NeoForge 标准组件；Quilt、LiteLoader、OptiFine、自定义 MultiMC 补丁或 MCBBS 自定义启动库会明确报不支持，不会静默丢失这些组件。整合包中的启动命令和自定义 JVM 参数不会自动执行。

## 服务器更新

导出时填写 HTTP 或 HTTPS 文件基础地址，例如 `https://example.com/my-pack`。将 ZIP 解压，把 `server-manifest.json` 与 `overrides/` 上传到该地址；清单下载地址为 `<fileApi>/server-manifest.json`，文件地址为 `<fileApi>/overrides/<相对路径>`。这与 HMCL 的静态文件服务器部署方式一致。Modrinth 索引中的文件下载仍遵循 HTTPS 规范，开发测试允许 HTTP 回环地址。

导入服务器包后自动保存更新来源。实例详情可手动检查更新，每次启动前也会检查。新增文件仅在本地不存在时下载；已管理文件只在本地仍匹配旧 SHA-1 时替换或删除。本地修改和同名未管理文件会保留。游戏版本与加载器变化时重新准备游戏组件，保留实例 ID、名称、内存配置和存档。

更新先下载到临时目录、验证，再提交文件、游戏描述和本地清单。提交失败回滚；回滚无法完成时保留临时备份并记录位置。更新下载失败时停止本次启动，避免以半更新状态运行。服务器来源变化需重新导入管理员提供的包。不要把服务器整合包切换到共享目录。

## 验证

本地夹具覆盖四种格式与各加载器的往返、嵌套 MultiMC、Modrinth 客户端覆盖层、可选和服务器文件、哈希不匹配、ZIP 越界与 Windows 路径、同名大小写碰撞、任意名称文件导入导出、取消与已有导出文件保护、安装失败清理、服务器文件同步、本地修改保留、提交失败回滚和游戏版本更新。JavaFX 回归验证三步向导、半选节点与父子联动、批量选择、搜索和折叠不改变勾选状态、翻页保留信息、逐个可选文件、文件选择器，以及通过实际 UI 导出后检查 ZIP 只包含勾选的嵌套文件。

格式依据：[Modrinth 格式规范](https://support.modrinth.com/en/articles/8802351-modrinth-modpack-format-mrpack)、[MultiMC 组件格式](https://multimc.org/posts/0-6-update.html)、[HMCL 格式定义](https://github.com/HMCL-dev/HMCL/tree/main/HMCLCore/src/main/java/org/jackhuang/hmcl/modpack)。本仓库独立实现，未复制其他启动器代码。
