package com.fuzzlauncher.application;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;

/** Only the user-facing challenge; the secret device_code never reaches presentation code. */
public record DeviceLogin(
    String userCode, URI verificationUri, Instant expiresAt, long deadlineNanos) {
  public DeviceLogin(String userCode, URI verificationUri, Instant expiresAt) {
    this(userCode, verificationUri, expiresAt, deadline(expiresAt));
  }

  private static long deadline(Instant expiresAt) {
    if (expiresAt == null) throw new IllegalArgumentException("登录代码有效期无效");
    try {
      return System.nanoTime() + Duration.between(Instant.now(), expiresAt).toNanos();
    } catch (ArithmeticException ex) {
      throw new IllegalArgumentException("登录代码有效期超出范围", ex);
    }
  }

  public boolean expired() {
    return remainingNanos() == 0;
  }

  public long remainingSeconds() {
    long nanos = remainingNanos();
    return nanos / 1_000_000_000L + (nanos % 1_000_000_000L == 0 ? 0 : 1);
  }

  private long remainingNanos() {
    return Math.max(0, deadlineNanos - System.nanoTime());
  }

  /** Microsoft's /link endpoint supports binding the current one-time user code via otc (HMCL). */
  public URI browserUri() {
    if ("/link".equalsIgnoreCase(verificationUri.getPath())
        && Set.of("microsoft.com", "www.microsoft.com")
            .contains(verificationUri.getHost().toLowerCase(java.util.Locale.ROOT)))
      return URI.create(
          "https://www.microsoft.com/link?otc="
              + java.net.URLEncoder.encode(userCode, java.nio.charset.StandardCharsets.UTF_8));
    return verificationUri;
  }

  public DeviceLogin {
    if (userCode == null
        || userCode.isBlank()
        || expiresAt == null
        || verificationUri == null
        || !"https".equalsIgnoreCase(verificationUri.getScheme())
        || verificationUri.getUserInfo() != null
        || (verificationUri.getPort() != -1 && verificationUri.getPort() != 443)
        || !Set.of("microsoft.com", "www.microsoft.com", "login.microsoftonline.com")
            .contains(
                verificationUri.getHost() == null
                    ? ""
                    : verificationUri.getHost().toLowerCase(java.util.Locale.ROOT)))
      throw new IllegalArgumentException("微软返回的设备登录信息无效");
  }
}
