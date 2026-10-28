package com.fuzzlauncher.infrastructure.auth;

import com.fuzzlauncher.application.TaskContext;
import com.fuzzlauncher.domain.*;
import com.fuzzlauncher.infrastructure.*;
import com.google.gson.*;
import com.microsoft.aad.msal4j.*;
import java.io.*;
import java.net.URI;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

/** Official Microsoft browser flow followed by Xbox and Minecraft entitlement checks. */
public final class MicrosoftAuthentication
    implements com.fuzzlauncher.application.AccountAuthenticator {
  private static final Set<String> SCOPES = Set.of("XboxLive.signin");
  private static final String CLIENT_ID = "76c4eab4-d8c4-49df-9fa1-c996e33748a8";
  private final HttpTransport http;
  private final Path identityFile;
  private final WindowsSecretStore secrets;

  public MicrosoftAuthentication(HttpTransport http, Path dataDirectory) {
    this.http = http;
    identityFile = dataDirectory.resolve("account.json");
    secrets = new WindowsSecretStore(dataDirectory.resolve("credentials.dpapi"));
  }

  public Optional<Account> savedAccount() throws IOException {
    if (!Files.exists(identityFile)) return Optional.empty();
    return Optional.ofNullable(
        JsonFiles.GSON.fromJson(JsonFiles.read(identityFile).get("account"), Account.class));
  }

  private PublicClientApplication application() throws Exception {
    ITokenCacheAccessAspect cache =
        new ITokenCacheAccessAspect() {
          @Override
          public void beforeCacheAccess(ITokenCacheAccessContext context) {
            try {
              String value = secrets.read();
              if (!value.isBlank()) context.tokenCache().deserialize(value);
            } catch (IOException ex) {
              throw new UncheckedIOException(ex);
            }
          }

          @Override
          public void afterCacheAccess(ITokenCacheAccessContext context) {
            if (context.hasCacheChanged())
              try {
                secrets.write(context.tokenCache().serialize());
              } catch (IOException ex) {
                throw new UncheckedIOException(ex);
              }
          }
        };
    return PublicClientApplication.builder(CLIENT_ID)
        .authority("https://login.microsoftonline.com/consumers/")
        .setTokenCacheAccessAspect(cache)
        .build();
  }

  public GameSession login(TaskContext task) throws Exception {
    task.checkCancelled();
    task.report("请在系统浏览器中完成微软登录", -1);
    PublicClientApplication app = application();
    var parameters =
        InteractiveRequestParameters.builder(URI.create("http://localhost"))
            .scopes(SCOPES)
            .prompt(Prompt.SELECT_ACCOUNT)
            .httpPollingTimeoutInSeconds(180)
            .build();
    IAuthenticationResult result = await(app.acquireToken(parameters), task);
    GameSession session = exchange(result, task);
    JsonFiles.write(identityFile, Map.of("account", session.account()));
    return session;
  }

  public GameSession refresh(Account account, TaskContext task) throws Exception {
    task.report("验证正版账号与游戏权限", -1);
    PublicClientApplication app = application();
    IAccount cached =
        await(app.getAccounts(), task).stream()
            .filter(a -> a.homeAccountId().equals(account.homeAccountId()))
            .findFirst()
            .orElseThrow(() -> new IOException("账号授权已失效，请重新进行微软登录"));
    try {
      IAuthenticationResult result =
          await(app.acquireTokenSilently(SilentParameters.builder(SCOPES, cached).build()), task);
      GameSession session = exchange(result, task);
      if (!session.account().uuid().equals(account.uuid())) throw new IOException("账号身份发生变化，请重新登录");
      return session;
    } catch (MsalInteractionRequiredException ex) {
      throw new IOException("微软授权需要重新登录，无法离线启动", ex);
    }
  }

  public void logout(TaskContext task) throws IOException {
    task.checkCancelled();
    // A single Minecraft identity is active. Clear all locally retained refresh credentials.
    secrets.write("");
    JsonFiles.write(identityFile, new JsonObject());
  }

  private GameSession exchange(IAuthenticationResult microsoft, TaskContext task) throws Exception {
    task.checkCancelled();
    task.report("Xbox Live 验证", -1);
    JsonObject body =
        JsonParser.parseString(
                "{\"Properties\":{\"AuthMethod\":\"RPS\",\"SiteName\":\"user.auth.xboxlive.com\"},\"RelyingParty\":\"http://auth.xboxlive.com\",\"TokenType\":\"JWT\"}")
            .getAsJsonObject();
    body.getAsJsonObject("Properties").addProperty("RpsTicket", "d=" + microsoft.accessToken());
    JsonObject xbox = http.postJson("https://user.auth.xboxlive.com/user/authenticate", body);
    JsonObject xstsBody =
        JsonParser.parseString(
                "{\"Properties\":{\"SandboxId\":\"RETAIL\",\"UserTokens\":[]},\"RelyingParty\":\"rp://api.minecraftservices.com/\",\"TokenType\":\"JWT\"}")
            .getAsJsonObject();
    xstsBody
        .getAsJsonObject("Properties")
        .getAsJsonArray("UserTokens")
        .add(xbox.get("Token").getAsString());
    task.checkCancelled();
    task.report("XSTS 验证", -1);
    JsonObject xsts = http.postJson("https://xsts.auth.xboxlive.com/xsts/authorize", xstsBody);
    String hash =
        xsts.getAsJsonObject("DisplayClaims")
            .getAsJsonArray("xui")
            .get(0)
            .getAsJsonObject()
            .get("uhs")
            .getAsString();
    JsonObject login = new JsonObject();
    login.addProperty("identityToken", "XBL3.0 x=" + hash + ";" + xsts.get("Token").getAsString());
    task.checkCancelled();
    task.report("Minecraft 正版权限校验", -1);
    JsonObject response;
    try {
      response =
          http.postJson("https://api.minecraftservices.com/authentication/login_with_xbox", login);
    } catch (HttpTransport.HttpStatusException ex) {
      if (ex.statusCode() != 403) throw ex;
      throw new IOException(
          "Minecraft 服务返回 HTTP 403。请检查 Fuzz Client ID 的应用准入及账号状态。" + ex.getMessage(), ex);
    }
    String token = response.get("access_token").getAsString();
    JsonObject entitlements =
        http.authorizedGet("https://api.minecraftservices.com/entitlements/mcstore", token);
    boolean owns = false;
    if (entitlements.has("items"))
      for (JsonElement item : entitlements.getAsJsonArray("items")) {
        String name = JsonFiles.string(item.getAsJsonObject(), "name", "");
        owns |= name.equals("game_minecraft") || name.equals("product_minecraft");
      }
    if (!owns) throw new IOException("此微软账号没有有效的 Minecraft Java 版权益，无法启动游戏");
    JsonObject profile =
        http.authorizedGet("https://api.minecraftservices.com/minecraft/profile", token);
    Account account =
        new Account(
            microsoft.account().homeAccountId(),
            profile.get("id").getAsString(),
            profile.get("name").getAsString());
    return new GameSession(
        account, token, Instant.now().plusSeconds(response.get("expires_in").getAsLong()));
  }

  private static <T> T await(CompletableFuture<T> future, TaskContext task) throws Exception {
    try {
      while (true) {
        task.checkCancelled();
        try {
          return future.get(250, TimeUnit.MILLISECONDS);
        } catch (TimeoutException waiting) {
          /* Poll only to allow user cancellation. */
        }
      }
    } catch (ExecutionException ex) {
      if (ex.getCause() instanceof Exception cause) throw cause;
      throw new IOException("微软登录失败", ex.getCause());
    } finally {
      if (!future.isDone()) future.cancel(true);
    }
  }
}
