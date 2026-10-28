package com.fuzzlauncher.domain;

import java.time.Instant;

public record GameSession(Account account, String accessToken, Instant expiresAt) {
  public GameSession {
    java.util.Objects.requireNonNull(account);
    java.util.Objects.requireNonNull(expiresAt);
    if (accessToken == null || accessToken.isBlank())
      throw new IllegalArgumentException("缺少有效的游戏访问令牌");
  }

  @Override
  public String toString() {
    return "GameSession[" + account.name() + ", token=REDACTED]";
  }
}
