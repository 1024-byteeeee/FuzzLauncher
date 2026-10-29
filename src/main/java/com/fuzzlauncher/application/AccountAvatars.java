package com.fuzzlauncher.application;

import com.fuzzlauncher.domain.Account;

@FunctionalInterface
public interface AccountAvatars {
  byte[] load(Account account, TaskContext task) throws Exception;
}
