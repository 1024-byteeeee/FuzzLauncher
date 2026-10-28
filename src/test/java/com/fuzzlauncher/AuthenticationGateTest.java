package com.fuzzlauncher;

import static org.junit.jupiter.api.Assertions.*;

import com.fuzzlauncher.application.*;
import com.fuzzlauncher.domain.*;
import com.fuzzlauncher.infrastructure.*;
import com.fuzzlauncher.infrastructure.game.*;
import java.io.IOException;
import java.nio.file.*;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class AuthenticationGateTest {
  @TempDir Path temp;

  @Test
  void rejectedOrMissingAccountCannotFallBackToOfflineLaunch() {
    AtomicInteger refreshes = new AtomicInteger();
    AccountAuthenticator rejecting =
        new AccountAuthenticator() {
          public Optional<Account> savedAccount() {
            return Optional.empty();
          }

          public GameSession login(TaskContext task) throws IOException {
            throw new IOException("Rejected");
          }

          public GameSession refresh(Account account, TaskContext task) throws IOException {
            refreshes.incrementAndGet();
            throw new IOException("No valid entitlement");
          }

          public void logout(TaskContext task) {}
        };
    HttpTransport http = new HttpTransport();
    LaunchGame launch =
        new LaunchGame(
            rejecting,
            new IsolationPolicy(),
            new VersionResolver(),
            new GameFiles(http),
            new JavaRuntimeManager(http, temp.resolve("runtimes")),
            new LaunchArguments(),
            temp.resolve("logs"));
    TaskContext task =
        new TaskContext() {
          public void report(String message, double value) {}

          public boolean cancelled() {
            return false;
          }
        };
    GameInstance instance =
        new GameInstance(
            "test", "test", "1.20.4", LoaderType.VANILLA, null, IsolationOverride.ISOLATED, 4096);
    LauncherSettings settings =
        new LauncherSettings(temp.resolve("game").toString(), IsolationMode.ALL);
    assertThrows(IOException.class, () -> launch.execute(settings, instance, null, task));
    assertEquals(0, refreshes.get());
    assertThrows(
        IOException.class,
        () -> launch.execute(settings, instance, new Account("test", "test", "test"), task));
    assertEquals(1, refreshes.get());
    assertFalse(Files.exists(temp.resolve("game")));
    assertFalse(Files.exists(temp.resolve("logs")));
  }
}
