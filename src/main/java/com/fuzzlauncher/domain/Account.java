package com.fuzzlauncher.domain;

/** Contains identity only. Credentials use a separate encrypted store. */
public record Account(
    String homeAccountId, String uuid, String name, AccountType type, String skinUrl) {
  public Account {
    if (type == null) type = AccountType.MICROSOFT;
    skinUrl = skinUrl == null ? "" : skinUrl;
    if (homeAccountId == null
        || homeAccountId.isBlank()
        || uuid == null
        || uuid.isBlank()
        || name == null
        || name.isBlank()) throw new IllegalArgumentException("账号身份资料不完整，请重新登录");
  }

  public Account(String homeAccountId, String uuid, String name, AccountType type) {
    this(homeAccountId, uuid, name, type, "");
  }

  public Account(String homeAccountId, String uuid, String name) {
    this(homeAccountId, uuid, name, AccountType.MICROSOFT);
  }

  public boolean isOffline() {
    return type == AccountType.OFFLINE;
  }

  public static Account offline(String name) {
    String username = name == null ? "" : name.strip();
    if (!username.matches("[A-Za-z0-9_]{3,16}"))
      throw new IllegalArgumentException("离线用户名需为 3–16 位英文字母、数字或下划线");
    String uuid =
        java.util
            .UUID
            .nameUUIDFromBytes(
                ("OfflinePlayer:" + username).getBytes(java.nio.charset.StandardCharsets.UTF_8))
            .toString()
            .replace("-", "");
    return new Account("offline:" + uuid, uuid, username, AccountType.OFFLINE);
  }

  @Override
  public String toString() {
    return name;
  }
}
