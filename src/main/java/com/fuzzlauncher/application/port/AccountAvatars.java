package com.fuzzlauncher.application.port;

import com.fuzzlauncher.application.task.TaskContext;
import com.fuzzlauncher.domain.account.Account;

@FunctionalInterface
public interface AccountAvatars {
    byte[] load(Account account, TaskContext task) throws Exception;
}
