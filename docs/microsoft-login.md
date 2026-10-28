# Microsoft 登录

FuzzLauncher 已内置自有 Microsoft Client ID，用户无需注册应用或填写 ID。在“账号”页点击“微软登录”即可。

当前 Client ID：`76c4eab4-d8c4-49df-9fa1-c996e33748a8`。
它是公开应用标识，定义在 `MicrosoftAuthentication.java`；不读取用户设置或 `FUZZ_MSA_CLIENT_ID` 环境变量。旧设置文件中的 ID 字段会被忽略，重新保存设置时移除，游戏目录和隔离配置保留。

## 开发者维护

自行分发独立应用的开发者应注册自己的应用并申请 Minecraft Services 准入，再修改代码中的 `CLIENT_ID`。

1. 在 Microsoft Entra 应用注册中创建支持个人 Microsoft 账号的应用。
2. 按 MSAL4J 桌面应用要求配置系统浏览器回调 `http://localhost`，应用类型为公共桌面客户端。
3. 在需要时完成 Minecraft Services 的应用准入申请：<https://aka.ms/mce-reviewappid>。
4. 将“应用程序(客户端) ID”配置到 `MicrosoftAuthentication.java` 的 `CLIENT_ID` 常量。
5. 在“账号”页点击“微软登录”，在微软网站授权拥有 Java 版权益的账号。

Client ID 是公开标识；不要填写 Client Secret，也不要把账号密码或令牌粘贴到聊天或设置里。
启动器采用 `XboxLive.signin`，由 MSAL 管理刷新授权及 PKCE；令牌缓存由 Windows DPAPI 加密。

## 诊断

- 启动器默认读取 Windows 系统代理设置，修改代理后请完全退出并重新启动。可通过 JVM 参数 `-Djava.net.useSystemProxies=false` 禁用此行为。
- `HTTPS/TLS 连接失败` 或 `Remote host terminated the handshake`：连接在收到 HTTP 授权结果前失败，先检查网络、代理和 HTTPS 拦截设置，不能据此判断应用准入是否通过。
- Microsoft/Xbox 403：检查账号 Xbox 资料、家庭限制以及授权状态。
- Minecraft 登录 403：检查应用准入和账号状态；仅注册 Entra 应用不等于获得 Minecraft 服务准入。
- 没有 Java 版权益：登录购买账号或拥有有效 Java 版订阅权益的账号。
- Windows 用户改变导致 DPAPI 解密失败：用原 Windows 用户运行，或备份启动器数据后重新建立账号授权。

Fuzz 的应用准入申请已提交，真实登录曾返回 HTTP 403；此状态不能单独证明审核结果。尚未完成真实账号登录并进入游戏的端到端验收，内置 Client ID 不会绕过服务端的准入检查。
