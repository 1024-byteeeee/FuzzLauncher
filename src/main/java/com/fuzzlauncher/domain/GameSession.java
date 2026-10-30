package com.fuzzlauncher.domain;

import java.time.Instant;

public record GameSession(Account account, String accessToken, Instant expiresAt) {
    public GameSession {
        java.util.Objects.requireNonNull(account);
        java.util.Objects.requireNonNull(expiresAt);
        if (accessToken == null || accessToken.isBlank()) {
            throw new IllegalArgumentException("缺少有效的游戏访问令牌");
        }
    }

    public static GameSession offline(Account account) {
        if (!account.isOffline()) {
            throw new IllegalArgumentException("此账号不是离线账号");
        }
        return new GameSession(account, "0", Instant.MAX);
    }

    @Override
    public String toString() {
        return "GameSession[" + account.name() + ", token=REDACTED]";
    }
}
