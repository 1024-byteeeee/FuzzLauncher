package com.fuzzlauncher.application;

import com.fuzzlauncher.domain.*;

import java.io.IOException;
import java.util.Optional;

/** Microsoft authentication and explicitly selected local identities. */
public interface AccountAuthenticator {
    Optional<Account> savedAccount() throws IOException;

    GameSession login(TaskContext task) throws Exception;

    GameSession loginOffline(String name, TaskContext task) throws Exception;

    GameSession refresh(Account account, TaskContext task) throws Exception;

    void logout(TaskContext task) throws Exception;
}
