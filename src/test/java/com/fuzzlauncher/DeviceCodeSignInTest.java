package com.fuzzlauncher;

import static org.junit.jupiter.api.Assertions.*;

import com.fuzzlauncher.application.*;
import com.fuzzlauncher.infrastructure.auth.DeviceCodeSignIn;
import com.microsoft.aad.msal4j.*;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;

class DeviceCodeSignInTest {
  private static final String CLIENT = "00000000-0000-0000-0000-000000000001";
  private final AtomicBoolean cancelled = new AtomicBoolean();
  private final AtomicReference<DeviceLogin> challenge = new AtomicReference<>();
  private final AtomicBoolean authorized = new AtomicBoolean();
  private final TaskContext task =
      new TaskContext() {
        public boolean cancelled() {
          return cancelled.get();
        }

        public void report(String message, double progress) {
          assertFalse(message.contains("ABCD-EFGH"));
          assertFalse(message.contains("private-device-code"));
        }

        public void deviceLogin(DeviceLogin value) {
          challenge.set(value);
        }

        public void deviceLoginCompleted() {
          authorized.set(true);
        }
      };

  private record Response(int statusCode, String body) implements IHttpResponse {
    public Map<String, List<String>> headers() {
      return Map.of("Content-Type", List.of("application/json"));
    }
  }

  private PublicClientApplication application(IHttpClient client) throws Exception {
    return PublicClientApplication.builder(CLIENT)
        .authority("https://login.microsoftonline.com/consumers/")
        .validateAuthority(false)
        .httpClient(
            request -> {
              String path = request.url().getPath();
              if (path.contains(".well-known"))
                return new Response(
                    200,
                    """
{"authorization_endpoint":"https://login.microsoftonline.com/consumers/oauth2/v2.0/authorize",
 "token_endpoint":"https://login.microsoftonline.com/consumers/oauth2/v2.0/token",
 "device_authorization_endpoint":"https://login.microsoftonline.com/consumers/oauth2/v2.0/devicecode",
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
              return client.send(request);
            })
        .build();
  }

  private static Response code(long expires) {
    return new Response(
        200,
        """
        {"user_code":"ABCD-EFGH","device_code":"private-device-code",
         "verification_uri":"https://microsoft.com/devicelogin","expires_in":%d,"interval":1,
         "message":"Enter the code on the Microsoft website"}
        """
            .formatted(expires));
  }

  private static Response error(String code) {
    return new Response(
        400, "{\"error\":\"" + code + "\",\"error_description\":\"Fixture response\"}");
  }

  @Test
  void actualSdkReceivesCodePollsAndReturnsToken() throws Exception {
    AtomicInteger polls = new AtomicInteger();
    var app =
        application(
            request -> {
              if (request.url().getPath().endsWith("devicecode")) {
                assertTrue(request.body().contains("client_id=" + CLIENT));
                return code(120);
              }
              assertTrue(request.body().contains("device_code=private-device-code"));
              if (polls.incrementAndGet() == 1) return error("authorization_pending");
              String info = b64("{\"uid\":\"fixture\",\"utid\":\"consumers\"}");
              String jwt =
                  b64("{\"alg\":\"none\"}")
                      + "."
                      + b64(
                          "{\"sub\":\"fixture\",\"tid\":\"consumers\",\"preferred_username\":\"fixture@example.invalid\"}")
                      + ".";
              return new Response(
                  200,
                  """
{"access_token":"fixture-access","refresh_token":"fixture-refresh","token_type":"Bearer",
 "expires_in":3600,"scope":"XboxLive.signin","client_info":"%s","id_token":"%s"}
"""
                      .formatted(info, jwt));
            });
    var result = DeviceCodeSignIn.acquire(app, Set.of("XboxLive.signin"), task);
    assertEquals("fixture-access", result.accessToken());
    assertNotNull(result.account());
    assertEquals("ABCD-EFGH", challenge.get().userCode());
    assertEquals(
        URI.create("https://microsoft.com/devicelogin"), challenge.get().verificationUri());
    assertTrue(challenge.get().expiresAt().isAfter(Instant.now()));
    assertEquals(2, polls.get());
    assertTrue(authorized.get());
  }

  @Test
  void cancellationStopsSdkPollingAndNeverProducesToken() throws Exception {
    AtomicInteger polls = new AtomicInteger();
    var app =
        application(
            request -> {
              if (request.url().getPath().endsWith("devicecode")) return code(120);
              polls.incrementAndGet();
              cancelled.set(true);
              return error("authorization_pending");
            });
    assertThrows(
        CancellationException.class,
        () -> DeviceCodeSignIn.acquire(app, Set.of("XboxLive.signin"), task));
    int stopped = polls.get();
    Thread.sleep(1400);
    assertEquals(stopped, polls.get());
    assertFalse(authorized.get());
  }

  @Test
  void expirationAndRejectedAuthorizationGiveActionableErrors() throws Exception {
    var expiring =
        application(
            request ->
                request.url().getPath().endsWith("devicecode")
                    ? code(1)
                    : error("authorization_pending"));
    assertTrue(
        assertThrows(
                IOException.class,
                () -> DeviceCodeSignIn.acquire(expiring, Set.of("XboxLive.signin"), task))
            .getMessage()
            .contains("过期"));
    for (String error : List.of("authorization_declined", "invalid_client")) {
      var app =
          application(
              request -> request.url().getPath().endsWith("devicecode") ? code(120) : error(error));
      String message =
          assertThrows(
                  IOException.class,
                  () -> DeviceCodeSignIn.acquire(app, Set.of("XboxLive.signin"), task))
              .getMessage();
      assertTrue(message.contains(error.equals("invalid_client") ? "公共客户端" : "拒绝"));
      assertFalse(message.contains("private-device-code"));
    }
  }

  @Test
  void challengeRejectsUnsafeOrMalformedBrowserTargets() {
    for (String url :
        List.of(
            "http://microsoft.com/devicelogin",
            "https://evil.invalid/",
            "https://microsoft.com.evil.invalid/",
            "https://user@microsoft.com/",
            "https://microsoft.com:8443/"))
      assertThrows(
          IllegalArgumentException.class,
          () -> new DeviceLogin("CODE", URI.create(url), Instant.now().plusSeconds(10)));
  }

  private static String b64(String value) {
    return Base64.getUrlEncoder()
        .withoutPadding()
        .encodeToString(value.getBytes(StandardCharsets.UTF_8));
  }

  @Test
  void remainingLifetimeIsMonotonicAndBrowserUsesCurrentCode() {
    var futureWallClock =
        new DeviceLogin(
            "CODE",
            URI.create("https://www.microsoft.com/link"),
            Instant.now().plusSeconds(3600),
            System.nanoTime() - 1);
    assertTrue(futureWallClock.expired(), "Wall-clock changes must not extend the code lifetime");
    var pastWallClock =
        new DeviceLogin(
            "A B&C",
            URI.create("https://www.microsoft.com/link?otc=OLD"),
            Instant.now().minusSeconds(3600),
            System.nanoTime() + TimeUnit.SECONDS.toNanos(60));
    assertFalse(pastWallClock.expired());
    assertTrue(pastWallClock.remainingSeconds() <= 60);
    assertEquals(
        "https://www.microsoft.com/link?otc=A+B%26C", pastWallClock.browserUri().toString());
    var standard =
        new DeviceLogin(
            "CODE", URI.create("https://microsoft.com/devicelogin"), Instant.now().plusSeconds(60));
    assertEquals(standard.verificationUri(), standard.browserUri());
  }

  @Test
  void timeSpentRequestingACodeIsNotAddedToItsLifetime() throws Exception {
    var app =
        application(
            request -> {
              if (request.url().getPath().endsWith("devicecode")) {
                Thread.sleep(1200);
                return code(10);
              }
              cancelled.set(true);
              return error("authorization_pending");
            });
    assertThrows(
        CancellationException.class,
        () -> DeviceCodeSignIn.acquire(app, Set.of("XboxLive.signin"), task));
    assertTrue(
        challenge.get().remainingSeconds() <= 9,
        "Network delay must be deducted rather than granting a fresh full lifetime on receipt");
  }
}
