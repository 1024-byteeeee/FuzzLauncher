package com.fuzzlauncher.infrastructure.auth;

import com.fuzzlauncher.application.TaskContext;

import com.microsoft.aad.msal4j.*;

import java.io.IOException;
import java.net.URI;
import java.util.Set;
import java.util.concurrent.*;

/** System-browser authorization with MSAL-managed loopback callback, state and PKCE. */
public final class BrowserSignIn {
    private BrowserSignIn() {
    }

    public static IAuthenticationResult acquire(PublicClientApplication app, Set<String> scopes, TaskContext task) throws Exception {
        return acquire(app, scopes, task, null);
    }

    static IAuthenticationResult acquire(PublicClientApplication app, Set<String> scopes, TaskContext task, OpenBrowserAction browser)
        throws Exception {
        task.checkCancelled();
        var options = SystemBrowserOptions.builder().htmlMessageSuccess(page("微软授权已返回", "请返回 FuzzLauncher，等待游戏账号验证。此页面可以关闭。"))
            .htmlMessageError(page("微软授权未完成", "请返回 FuzzLauncher 查看提示并重新登录。"));
        if (browser != null) {
            options.openBrowserAction(browser);
        }
        var parameters = InteractiveRequestParameters.builder(URI.create("http://localhost")).scopes(scopes).prompt(Prompt.SELECT_ACCOUNT)
            .httpPollingTimeoutInSeconds(300).systemBrowserOptions(options.build()).build();
        task.report("请在浏览器中完成微软登录，完成后会自动返回启动器", -1);
        CompletableFuture<IAuthenticationResult> future = app.acquireToken(parameters);
        long deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(5);
        try {
            while (true) {
                task.checkCancelled();
                if (System.nanoTime() - deadline >= 0 && !future.isDone()) {
                    throw new IOException("等待微软网页授权超时，请重新登录");
                }
                try {
                    IAuthenticationResult result = future.get(250, TimeUnit.MILLISECONDS);
                    task.checkCancelled();
                    return result;
                } catch (TimeoutException waiting) {
                    // Only cancellation is polled here; MSAL owns the callback and token exchange.
                }
            }
        } catch (ExecutionException ex) {
            task.checkCancelled();
            Throwable cause = ex.getCause();
            if (cause instanceof CancellationException cancelled) {
                throw cancelled;
            }
            String message = "微软浏览器登录失败，请检查网络后重试";
            if (cause instanceof MsalException msal) {
                message = switch (java.util.Objects.toString(msal.errorCode(), "")) {
                    case "access_denied", "authentication_canceled" -> "微软登录授权已取消或被拒绝，请重新登录";
                    case "authorization_pending", "authentication_timeout", "http_listener_timeout" -> "等待微软网页授权超时，请重新登录";
                    case "desktop_browser_not_supported" -> "无法打开默认浏览器，请设置 Windows 默认浏览器后重新登录";
                    case "unable_to_start_http_listener" -> "无法接收微软登录回调，请检查本机端口是否可用后重试";
                    case "invalid_authorization_result" -> "微软登录回调校验失败，请重新登录";
                    case "invalid_client", "unauthorized_client", "invalid_request", "invalid_grant" ->
                        "微软拒绝了登录请求，请检查应用注册，并在“移动和桌面应用”中配置重定向 URI：http://localhost";
                    default -> message;
                };
            }
            throw new IOException(message, cause);
        } finally {
            if (!future.isDone()) {
                future.cancel(true);
            }
        }
    }

    private static String page(String title, String message) {
        return "<!doctype html><html lang=\"zh-CN\"><meta charset=\"utf-8\"><title>" + title + "</title><body><h1>" + title + "</h1><p>"
            + message + "</p></body></html>";
    }
}
