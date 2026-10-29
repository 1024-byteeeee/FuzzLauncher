package com.fuzzlauncher.application;

import java.net.URI;
import java.time.Instant;
import java.util.Set;

/** Only the user-facing challenge; the secret device_code never reaches presentation code. */
public record DeviceLogin(String userCode, URI verificationUri, Instant expiresAt) {
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
