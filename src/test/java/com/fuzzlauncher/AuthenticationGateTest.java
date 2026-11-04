package com.fuzzlauncher;

import com.fuzzlauncher.application.port.AccountAuthenticator;
import com.fuzzlauncher.application.task.TaskContext;
import com.fuzzlauncher.domain.account.Account;
import com.fuzzlauncher.domain.account.GameSession;
import com.fuzzlauncher.domain.game.LoaderType;
import com.fuzzlauncher.domain.instance.GameInstance;
import com.fuzzlauncher.domain.instance.IsolationMode;
import com.fuzzlauncher.domain.instance.IsolationOverride;
import com.fuzzlauncher.domain.instance.IsolationPolicy;
import com.fuzzlauncher.domain.settings.LauncherSettings;
import com.fuzzlauncher.infrastructure.config.SettingsDefaults;
import com.fuzzlauncher.infrastructure.game.catalog.VersionResolver;
import com.fuzzlauncher.infrastructure.game.install.GameFiles;
import com.fuzzlauncher.infrastructure.game.launch.LaunchArguments;
import com.fuzzlauncher.infrastructure.game.launch.LocalGameLauncher;
import com.fuzzlauncher.infrastructure.game.runtime.JavaRuntimeManager;
import com.fuzzlauncher.infrastructure.network.HttpTransport;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.*;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class AuthenticationGateTest {
    @TempDir
    Path temp;

    @Test
    void rejectedOrMissingAccountCannotFallBackToOfflineLaunch() {
        AtomicInteger refreshes = new AtomicInteger();
        AccountAuthenticator rejecting = new AccountAuthenticator() {
            public Optional<Account> savedAccount() {
                return Optional.empty();
            }

            public GameSession login(TaskContext task) throws IOException {
                throw new IOException("Rejected");
            }

            public GameSession loginOffline(String name, TaskContext task) {
                return GameSession.offline(Account.offline(name));
            }

            public GameSession refresh(Account account, TaskContext task) throws IOException {
                refreshes.incrementAndGet();
                throw new IOException("No valid entitlement");
            }

            public void logout(TaskContext task) {
            }
        };
        HttpTransport http = new HttpTransport();
        LocalGameLauncher launch = new LocalGameLauncher(rejecting, new IsolationPolicy(), new VersionResolver(), new GameFiles(http),
            new JavaRuntimeManager(temp.resolve("runtimes")), new LaunchArguments(), temp.resolve("logs"));
        TaskContext task = new TaskContext() {
            public void report(String message, double value) {
            }

            public boolean cancelled() {
                return false;
            }
        };
        GameInstance instance = new GameInstance("test", "test", "1.20.4", LoaderType.VANILLA, null, IsolationOverride.ISOLATED, 4096);
        LauncherSettings settings = SettingsDefaults.create(temp.resolve("game").toString(), IsolationMode.ALL);
        assertThrows(IOException.class, () -> launch.execute(settings, instance, null, task));
        assertEquals(0, refreshes.get());
        assertThrows(IOException.class, () -> launch.execute(settings, instance, new Account("test", "test", "test"), task));
        assertEquals(1, refreshes.get());
        // An explicit offline identity skips Microsoft authentication and reaches version
        // resolution.
        assertThrows(IOException.class, () -> launch.execute(settings, instance, Account.offline("PlayerOne"), task));
        assertEquals(1, refreshes.get());
        assertFalse(Files.exists(temp.resolve("game")));
        assertFalse(Files.exists(temp.resolve("logs")));
    }
}
