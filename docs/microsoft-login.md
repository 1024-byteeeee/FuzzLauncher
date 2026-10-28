# Microsoft 登录配置

真实账号联调需要 Fuzz 自有应用注册，不能借用其他启动器的 Client ID。

1. 在 Microsoft Entra 应用注册中创建支持个人 Microsoft 账号的应用。
2. 按 MSAL4J 桌面应用要求配置系统浏览器回调 `http://localhost`，应用类型为公共桌面客户端。
3. 在需要时完成 Minecraft Services 的应用准入申请：<https://aka.ms/mce-reviewappid>。
4. 将“应用程序(客户端) ID”填写到 Fuzz 的“设置 → Client ID”，或开发时设置环境变量 `FUZZ_MSA_CLIENT_ID`。
5. 在“账号”页点击“微软正版登录”，在微软网站授权拥有 Java 版权益的账号。

Client ID 是公开标识；不要填写 Client Secret，也不要把账号密码或令牌粘贴到聊天或设置里。
启动器采用 `XboxLive.signin`，由 MSAL 管理刷新授权及 PKCE；令牌缓存由 Windows DPAPI 加密。

## 诊断

- 启动器默认读取 Windows 系统代理设置，修改代理后请完全退出并重新启动。可通过 JVM 参数 `-Djava.net.useSystemProxies=false` 禁用此行为。
- `HTTPS/TLS 连接失败` 或 `Remote host terminated the handshake`：连接在收到 HTTP 授权结果前失败，先检查网络、代理和 HTTPS 拦截设置，不能据此判断应用准入是否通过。
- 缺少 Client ID：先完成上述注册和配置。
- Microsoft/Xbox 403：检查账号 Xbox 资料、家庭限制以及授权状态。
- Minecraft 登录 403：检查应用准入和账号状态；仅注册 Entra 应用不等于获得 Minecraft 服务准入。
- 没有 Java 版权益：登录购买账号或拥有有效 Java 版订阅权益的账号。
- Windows 用户改变导致 DPAPI 解密失败：用原 Windows 用户运行，或备份启动器数据后重新建立账号授权。

当前实现尚未使用真实账号完成端到端验收。缺少已获准入的 Fuzz Client ID 时，安装功能可用，正版登录与进入游戏仍需要真实联调。
