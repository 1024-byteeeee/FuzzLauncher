package com.fuzzlauncher.application;

import com.fuzzlauncher.domain.*;
import java.io.IOException;
import java.util.Optional;

/** Only authenticated, entitled Minecraft Java identities may produce a game session. */
public interface AccountAuthenticator {
  Optional<Account> savedAccount() throws IOException;

  GameSession login(String clientId, TaskContext task) throws Exception;

  GameSession refresh(String clientId, Account account, TaskContext task) throws Exception;

  void logout(TaskContext task) throws Exception;
}
