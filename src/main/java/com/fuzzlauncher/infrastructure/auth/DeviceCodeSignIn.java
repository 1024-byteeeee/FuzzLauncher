package com.fuzzlauncher.infrastructure.auth;

import com.fuzzlauncher.application.*;
import com.microsoft.aad.msal4j.*;
import java.io.IOException;
import java.net.URI;
import java.time.Instant;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

/** Delegates token polling to MSAL, with cancellable waiting and localized, code-free errors. */
public final class DeviceCodeSignIn {
  private DeviceCodeSignIn() {}

  public static IAuthenticationResult acquire(
      PublicClientApplication app, Set<String> scopes, TaskContext task) throws Exception {
    task.checkCancelled();
    AtomicReference<Instant> deadline = new AtomicReference<>();
    var parameters =
        DeviceCodeFlowParameters.builder(
                scopes,
                code -> {
                  task.checkCancelled();
                  if (code.expiresIn() <= 0) throw new IllegalArgumentException("微软返回的登录代码有效期无效");
                  var challenge =
                      new DeviceLogin(
                          code.userCode(),
                          URI.create(code.verificationUri()),
                          Instant.now().plusSeconds(code.expiresIn()));
                  deadline.set(challenge.expiresAt());
                  task.report("请在微软网页输入代码并完成登录", -1);
                  task.deviceLogin(challenge);
                })
            .build();
    CompletableFuture<IAuthenticationResult> future = app.acquireToken(parameters);
    try {
      while (true) {
        task.checkCancelled();
        if (deadline.get() != null && !Instant.now().isBefore(deadline.get()))
          throw new IOException("登录代码已过期，请重新点击微软登录获取新代码");
        try {
          IAuthenticationResult result = future.get(250, TimeUnit.MILLISECONDS);
          task.checkCancelled();
          return result;
        } catch (TimeoutException waiting) {
          // The SDK controls request intervals; this wait only checks cancellation/expiration.
        }
      }
    } catch (ExecutionException ex) {
      task.checkCancelled();
      Throwable cause = ex.getCause();
      if (cause instanceof CancellationException cancelled) throw cancelled;
      if (cause instanceof MsalException msal) {
        String message =
            switch (java.util.Objects.toString(msal.errorCode(), "")) {
              case "code_expired", "expired_token" -> "登录代码已过期，请重新点击微软登录获取新代码";
              case "authorization_declined", "access_denied" -> "微软登录授权已被拒绝，请重新登录";
              case "invalid_client", "unauthorized_client" ->
                  "此应用暂不能使用设备代码登录。请在 Microsoft Entra 应用注册的“身份验证 → 设置”中启用“允许公共客户端流”，并确认平台为“移动和桌面应用”";
              default -> "微软设备代码登录失败，请检查网络或稍后重试";
            };
        throw new IOException(message, msal);
      }
      if (cause instanceof IllegalArgumentException invalid)
        throw new IOException("微软返回的设备登录信息无效，请重新登录", invalid);
      throw new IOException("无法获取微软登录授权，请检查网络后重试", cause);
    } finally {
      if (!future.isDone()) future.cancel(true);
    }
  }
}
