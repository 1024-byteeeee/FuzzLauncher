package com.fuzzlauncher.infrastructure.auth;

import static org.junit.jupiter.api.Assertions.*;

import com.fuzzlauncher.application.TaskContext;
import com.microsoft.aad.msal4j.*;
import java.io.IOException;
import java.net.*;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.*;

class BrowserSignInTest {
  private static final String CLIENT = "00000000-0000-0000-0000-000000000001";
  private final ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();
  private final AtomicBoolean cancelled = new AtomicBoolean();
  private final TaskContext task =
      new TaskContext() {
        public boolean cancelled() {
          return cancelled.get();
        }

        public void report(String message, double progress) {
          assertFalse(message.contains("private-authorization-code"));
          assertFalse(message.contains("fixture-access"));
        }
      };

  @AfterEach
  void stopWorkers() throws Exception {
    workers.shutdownNow();
    assertTrue(workers.awaitTermination(5, TimeUnit.SECONDS), "SDK callback workers must stop");
  }

  private record Response(int statusCode, String body) implements IHttpResponse {
    public Map<String, List<String>> headers() {
      return Map.of("Content-Type", List.of("application/json"));
    }
  }

  private PublicClientApplication application(IHttpClient tokens) throws Exception {
    return PublicClientApplication.builder(CLIENT)
        .authority("https://login.microsoftonline.com/consumers/")
        .validateAuthority(false)
        .executorService(workers)
        .httpClient(
            request -> {
              String path = request.url().getPath();
              if (path.contains(".well-known"))
                return new Response(
                    200,
                    """
                    {"authorization_endpoint":"https://login.microsoftonline.com/consumers/oauth2/v2.0/authorize",
                     "token_endpoint":"https://login.microsoftonline.com/consumers/oauth2/v2.0/token",
                     "issuer":"https://login.microsoftonline.com/consumers/v2.0"}
                    """);
              if (path.contains("discovery/instance"))
                return new Response(
                    200,
                    """
                    {"tenant_discovery_endpoint":"https://login.microsoftonline.com/consumers/v2.0/.well-known/openid-configuration",
                     "metadata":[{"preferred_network":"login.microsoftonline.com","preferred_cache":"login.microsoftonline.com",
                     "aliases":["login.microsoftonline.com"]}]}
                    """);
              assertFalse(path.endsWith("devicecode"), "Device login must never be requested");
              return tokens.send(request);
            })
        .build();
  }

  private static Map<String, String> form(String value) {
    Map<String, String> result = new HashMap<>();
    for (String pair : value.split("&")) {
      String[] parts = pair.split("=", 2);
      result.put(
          URLDecoder.decode(parts[0], StandardCharsets.UTF_8),
          URLDecoder.decode(parts.length == 2 ? parts[1] : "", StandardCharsets.UTF_8));
    }
    return result;
  }

  private static String encoded(String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8);
  }

  private static String b64(String value) {
    return Base64.getUrlEncoder()
        .withoutPadding()
        .encodeToString(value.getBytes(StandardCharsets.UTF_8));
  }

  private static String callback(Map<String, String> query, String state) {
    try {
      var response =
          HttpClient.newHttpClient()
              .send(
                  HttpRequest.newBuilder(URI.create(query.get("redirect_uri")))
                      .timeout(java.time.Duration.ofSeconds(3))
                      .header("Content-Type", "application/x-www-form-urlencoded")
                      .POST(
                          HttpRequest.BodyPublishers.ofString(
                              "code=private-authorization-code&state=" + encoded(state)))
                      .build(),
                  HttpResponse.BodyHandlers.ofString());
      assertEquals(200, response.statusCode());
      return response.body();
    } catch (Exception ex) {
      throw new RuntimeException(ex);
    }
  }

  @Test
  void actualSdkOpensBrowserValidatesPkceAndExchangesCallback() throws Exception {
    AtomicReference<Map<String, String>> authorization = new AtomicReference<>();
    AtomicInteger tokenCalls = new AtomicInteger();
    var app =
        application(
            request -> {
              tokenCalls.incrementAndGet();
              assertEquals("/consumers/oauth2/v2.0/token", request.url().getPath());
              var body = form(request.body());
              assertEquals("authorization_code", body.get("grant_type"));
              assertEquals("private-authorization-code", body.get("code"));
              assertEquals(CLIENT, body.get("client_id"));
              assertEquals(authorization.get().get("redirect_uri"), body.get("redirect_uri"));
              try {
                String hash =
                    Base64.getUrlEncoder()
                        .withoutPadding()
                        .encodeToString(
                            MessageDigest.getInstance("SHA-256")
                                .digest(
                                    body.get("code_verifier").getBytes(StandardCharsets.US_ASCII)));
                assertEquals(authorization.get().get("code_challenge"), hash);
              } catch (java.security.NoSuchAlgorithmException ex) {
                throw new AssertionError(ex);
              }
              return new Response(
                  200,
                  """
                  {"access_token":"fixture-access","refresh_token":"fixture-refresh","token_type":"Bearer",
                   "expires_in":3600,"scope":"XboxLive.signin","client_info":"%s","id_token":"%s"}
                  """
                      .formatted(
                          b64("{\"uid\":\"fixture\",\"utid\":\"consumers\"}"),
                          b64("{\"alg\":\"none\"}")
                              + "."
                              + b64(
                                  "{\"sub\":\"fixture\",\"tid\":\"consumers\",\"preferred_username\":\"fixture@example.invalid\"}")
                              + "."));
            });
    var result =
        BrowserSignIn.acquire(
            app,
            Set.of("XboxLive.signin"),
            task,
            url -> {
              assertEquals("https", url.getProtocol());
              assertEquals("login.microsoftonline.com", url.getHost());
              var query = form(url.getQuery());
              authorization.set(query);
              assertEquals("code", query.get("response_type"));
              assertEquals("form_post", query.get("response_mode"));
              assertEquals("select_account", query.get("prompt"));
              assertEquals("S256", query.get("code_challenge_method"));
              assertEquals(
                  Set.of("XboxLive.signin", "openid", "profile", "offline_access"),
                  Set.of(query.get("scope").split(" +")));
              URI redirect = URI.create(query.get("redirect_uri"));
              assertEquals("localhost", redirect.getHost());
              assertTrue(redirect.getPort() > 0);
              assertTrue(callback(query, query.get("state")).contains("请返回 FuzzLauncher"));
            });
    assertEquals("fixture-access", result.accessToken());
    assertNotNull(result.account());
    assertEquals(1, tokenCalls.get());
  }

  @Test
  void mismatchedStateNeverExchangesAuthorizationCode() throws Exception {
    AtomicInteger tokenCalls = new AtomicInteger();
    var app =
        application(
            request -> {
              tokenCalls.incrementAndGet();
              throw new AssertionError("Unexpected token exchange");
            });
    var failure =
        assertThrows(
            IOException.class,
            () ->
                BrowserSignIn.acquire(
                    app,
                    Set.of("XboxLive.signin"),
                    task,
                    url -> callback(form(url.getQuery()), "wrong-state")));
    assertTrue(failure.getMessage().contains("回调校验失败"));
    assertFalse(failure.getMessage().contains("private-authorization-code"));
    assertEquals(0, tokenCalls.get());
  }

  @Test
  void cancellingWaitingBrowserClosesLoopbackWithoutTokenExchange() throws Exception {
    AtomicReference<URI> redirect = new AtomicReference<>();
    CountDownLatch opened = new CountDownLatch(1);
    var app =
        application(
            request -> {
              throw new AssertionError("Unexpected token exchange");
            });
    Future<IAuthenticationResult> login =
        workers.submit(
            () ->
                BrowserSignIn.acquire(
                    app,
                    Set.of("XboxLive.signin"),
                    task,
                    url -> {
                      redirect.set(URI.create(form(url.getQuery()).get("redirect_uri")));
                      opened.countDown();
                    }));
    assertTrue(opened.await(5, TimeUnit.SECONDS));
    cancelled.set(true);
    assertInstanceOf(
        CancellationException.class,
        assertThrows(ExecutionException.class, () -> login.get(5, TimeUnit.SECONDS)).getCause());
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
    boolean closed = false;
    while (System.nanoTime() < deadline) {
      try (Socket socket = new Socket()) {
        socket.connect(new InetSocketAddress("localhost", redirect.get().getPort()), 100);
      } catch (IOException expected) {
        closed = true;
        break;
      }
      Thread.sleep(20);
    }
    assertTrue(closed, "Cancellation must release the local callback port");
  }

  @Test
  void browserFailureAndTokenRejectionUseChineseErrors() throws Exception {
    var app =
        application(
            request ->
                new Response(
                    400,
                    "{\"error\":\"invalid_grant\",\"error_description\":\"private diagnostic\"}"));
    var browserError =
        assertThrows(
            IOException.class,
            () ->
                BrowserSignIn.acquire(
                    app,
                    Set.of("XboxLive.signin"),
                    task,
                    url -> {
                      throw new MsalClientException(
                          "private diagnostic", "desktop_browser_not_supported");
                    }));
    assertTrue(browserError.getMessage().contains("默认浏览器"));
    var tokenError =
        assertThrows(
            IOException.class,
            () ->
                BrowserSignIn.acquire(
                    app,
                    Set.of("XboxLive.signin"),
                    task,
                    url -> {
                      var query = form(url.getQuery());
                      callback(query, query.get("state"));
                    }));
    assertTrue(tokenError.getMessage().contains("http://localhost"));
    assertFalse(tokenError.getMessage().contains("private diagnostic"));
  }
}
