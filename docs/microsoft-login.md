# Microsoft 登录

“账号”页也提供独立的离线登录入口，无需 Microsoft Client ID 或服务端准入。离线账号用于单人游戏和允许离线账号的服务器；微软账号认证与权益校验规则保持不变。

FuzzLauncher 已内置自有 Microsoft Client ID，用户无需注册应用或填写 ID。在“账号”页点击“微软登录”后，启动器直接打开默认浏览器；在微软网页选择账号并授权后自动继续 Xbox 和 Minecraft 验证，无需输入设备代码。

当前 Client ID：`76c4eab4-d8c4-49df-9fa1-c996e33748a8`。
它是公开应用标识，定义在 `MicrosoftAuthentication.java`；不读取用户设置或 `FUZZ_MSA_CLIENT_ID` 环境变量。旧设置文件中的 ID 字段会被忽略，重新保存设置时移除，游戏目录和隔离配置保留。

## 开发者维护

自行分发独立应用的开发者应注册自己的应用并申请 Minecraft Services 准入，再修改代码中的 `CLIENT_ID`。

1. 在 Microsoft Entra 应用注册中创建支持个人 Microsoft 账号的应用。
2. 在“身份验证”中添加“移动和桌面应用”平台，配置重定向 URI `http://localhost`（公共桌面客户端，无 Client Secret）。MSAL 会在本机随机可用端口监听授权回调；不要将该 URI 配置为 Web 或 SPA 平台。
3. 在需要时完成 Minecraft Services 的应用准入申请：<https://aka.ms/mce-reviewappid>。
4. 将“应用程序(客户端) ID”配置到 `MicrosoftAuthentication.java` 的 `CLIENT_ID` 常量。
5. 在“账号”页点击“微软登录”，在微软网站授权拥有 Java 版权益的账号。

启动器采用 MSAL4J 1.26.0 的系统浏览器授权码流程，使用 `consumers` 端点、PKCE 和随机 state 校验，通过本机 HTTP 回调自动接收授权。关闭或取消登录会停止等待并释放监听端口；等待超过五分钟可重新登录。浏览器回调页为中文，不显示授权码或令牌。新账号只有在 Minecraft 权益与资料验证成功后才保存。

Client ID 是公开标识；不要填写 Client Secret，也不要把账号密码或令牌粘贴到聊天或设置里。
启动器采用 `XboxLive.signin`，由 MSAL 管理浏览器登录及刷新授权；令牌缓存由 Windows DPAPI 加密。

## 诊断

- 启动器默认读取 Windows 系统代理设置，修改代理后请完全退出并重新启动。可通过 JVM 参数 `-Djava.net.useSystemProxies=false` 禁用此行为。
- `HTTPS/TLS 连接失败` 或 `Remote host terminated the handshake`：连接在收到 HTTP 授权结果前失败，先检查网络、代理和 HTTPS 拦截设置，不能据此判断应用准入是否通过。
- Microsoft/Xbox 403：检查账号 Xbox 资料、家庭限制以及授权状态。
- 网页返回 `invalid_request`，说明第一方应用不能由用户同意、需要 `pre-authorization`：核对本次登录实际使用的 Client ID 是否与上述自有应用 ID 一致，再核对应用注册和 Xbox 服务授权配置。截图本身不能证明是哪个应用 ID 导致；启用公共客户端流或成功取得设备代码不代表用户授权已经通过。此错误发生在微软授权阶段，不能据此判断 Minecraft Services 准入结果。
- `AADSTS50011` 或重定向 URI 不匹配：在自有应用的“移动和桌面应用”中添加 `http://localhost`。系统浏览器流程使用本机随机端口，不使用 `nativeclient` 页面或设备代码地址。
- 门户 API 权限列表中的 Microsoft Graph `User.Read` 不等于登录请求会携带它。当前浏览器请求发送 `XboxLive.signin` 与 SDK 添加的 `openid profile offline_access`，不请求 `User.Read` 或 `.default`；不能仅凭门户缺少 Xbox 条目判断错误原因，也不应通过授予 Graph 管理员同意来处理 Xbox 预授权错误。
- Minecraft 登录 403：检查应用准入和账号状态；仅注册 Entra 应用不等于获得 Minecraft 服务准入。
- 没有 Java 版权益：登录购买账号或拥有有效 Java 版订阅权益的账号。
- Windows 用户改变导致 DPAPI 解密失败：用原 Windows 用户运行，或备份启动器数据后重新建立账号授权。

Fuzz 的应用准入申请已提交，真实登录曾返回 HTTP 403；此状态不能单独证明审核结果。尚未完成真实账号登录并进入游戏的端到端验收，内置 Client ID 不会绕过服务端的准入检查。

参考：[MSAL Java 系统浏览器登录](https://learn.microsoft.com/en-us/entra/msal/java/getting-started/acquiring-tokens-interactively)。本地测试使用实际 MSAL SDK 与模拟授权服务验证回调、PKCE、state、取消清理和错误提示，不代表真实账号授权或 Minecraft 服务准入已经通过。

2026-10-29 使用内置 Client ID、`consumers` 浏览器授权端点及 `http://localhost:54321` 进行了未登录检查，请求进入 `login.live.com` 的账号登录页（HTTP 200），未出现 AADSTS 错误。该检查没有输入账号或密码，也没有获取令牌，不能代替授权完成后的回调与 Minecraft 权益验收。
