package com.fuzzlauncher.domain;

/** Contains identity only. Credentials use a separate encrypted store. */
public record Account(String homeAccountId, String uuid, String name) {
  public Account {
    if (homeAccountId == null
        || homeAccountId.isBlank()
        || uuid == null
        || uuid.isBlank()
        || name == null
        || name.isBlank()) throw new IllegalArgumentException("账号身份资料不完整，请重新登录");
  }

  @Override
  public String toString() {
    return name;
  }
}
