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
import com.fuzzlauncher.domain.settings.DownloadSource;
import com.fuzzlauncher.domain.settings.LauncherVisibility;
import com.fuzzlauncher.infrastructure.auth.MicrosoftAuthentication;
import com.fuzzlauncher.infrastructure.config.SettingsDefaults;
import com.fuzzlauncher.infrastructure.game.catalog.VersionResolver;
import com.fuzzlauncher.infrastructure.game.install.GameFiles;
import com.fuzzlauncher.infrastructure.game.launch.LaunchArguments;
import com.fuzzlauncher.infrastructure.game.launch.LocalGameLauncher;
import com.fuzzlauncher.infrastructure.game.runtime.JavaRuntimeManager;
import com.fuzzlauncher.infrastructure.network.HttpTransport;
import com.fuzzlauncher.infrastructure.persistence.JsonFiles;

import com.google.gson.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.jar.*;
import javax.tools.ToolProvider;

import static org.junit.jupiter.api.Assertions.*;

class OfflineAccountTest {
    @TempDir
    Path temp;
    private final TaskContext task = new TaskContext() {
        public void report(String message, double progress) {
        }

        public boolean cancelled() {
            return false;
        }
    };

    @Test
    void offlineIdentityIsStableValidatedAndPersistsWithoutMicrosoftLogin() throws Exception {
        Account steve = Account.offline("Steve");
        assertEquals("5627dd98e6be3c21b8a8e92344183641", steve.uuid());
        assertEquals(steve, Account.offline(" Steve "));
        assertNotEquals(steve.uuid(), Account.offline("Alex").uuid());
        for (String invalid : new String[] { "", "ab", "中文名", "a b", "../Steve", "12345678901234567" }) {
            assertThrows(IllegalArgumentException.class, () -> Account.offline(invalid));
        }
        var auth = new MicrosoftAuthentication(new HttpTransport(), temp);
        GameSession session = auth.loginOffline("Steve", task);
        assertEquals(steve, session.account());
        assertTrue(session.account().isOffline());
        assertEquals(steve, new MicrosoftAuthentication(new HttpTransport(), temp).savedAccount().orElseThrow());
        assertThrows(IllegalArgumentException.class, () -> auth.loginOffline("bad name", task));
        assertEquals(steve, auth.savedAccount().orElseThrow());
        auth.logout(task);
        assertTrue(auth.savedAccount().isEmpty());
    }

    @Test
    void legacyAccountFilesRemainMicrosoftAccounts() {
        Account account = JsonFiles.GSON.fromJson("{\"homeAccountId\":\"old-id\",\"uuid\":\"uuid\",\"name\":\"Player\"}", Account.class);
        assertFalse(account.isOffline());
        assertThrows(IllegalArgumentException.class, () -> GameSession.offline(account));
    }

    @ParameterizedTest
    @EnumSource(LauncherVisibility.class)
    void offlineLaunchRunsLocalFixtureAndKeepsZeroesInLogsWithoutRefreshingMicrosoft(LauncherVisibility visibility) throws Exception {
        Path root = temp.resolve("game");
        Path versionDir = root.resolve("versions/local-test");
        Files.createDirectories(versionDir);
        Path source = temp.resolve("OfflineLaunchFixture.java");
        Files.writeString(source, """
            public class OfflineLaunchFixture {
              public static void main(String[] args) throws Exception {
                java.nio.file.Files.writeString(java.nio.file.Path.of("received.txt"), String.join("\\n", args));
                System.out.println("offline-probe-0");
                System.out.println("<log4j:Event thread='main' level='WARN'><log4j:Message>log-format-probe</log4j:Message></log4j:Event>");
              }
            }
            """);
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null, "-d", temp.toString(), source.toString()));
        try (var jar = new JarOutputStream(Files.newOutputStream(versionDir.resolve("local-test.jar")))) {
            jar.putNextEntry(new JarEntry("OfflineLaunchFixture.class"));
            Files.copy(temp.resolve("OfflineLaunchFixture.class"), jar);
            jar.closeEntry();
        }
        JsonObject version = JsonParser
            .parseString(
                """
                    {"id":"local-test","mainClass":"OfflineLaunchFixture","libraries":[],"javaVersion":{"majorVersion":21},
                     "arguments":{"game":["--username","${auth_player_name}","--uuid","${auth_uuid}","--accessToken","${auth_access_token}","--userType","${user_type}"]}}
                    """)
            .getAsJsonObject();
        JsonFiles.write(versionDir.resolve("local-test.json"), version);
        AccountAuthenticator noMicrosoft = new AccountAuthenticator() {
            public Optional<Account> savedAccount() {
                return Optional.empty();
            }

            public GameSession login(TaskContext task) {
                throw new AssertionError("Unexpected login");
            }

            public GameSession loginOffline(String name, TaskContext task) {
                return GameSession.offline(Account.offline(name));
            }

            public GameSession refresh(Account account, TaskContext task) {
                throw new AssertionError("Offline launch called Microsoft");
            }

            public void logout(TaskContext task) {
            }
        };
        HttpTransport http = new HttpTransport();
        var launch = new LocalGameLauncher(noMicrosoft, new IsolationPolicy(), new VersionResolver(), new GameFiles(http),
            new JavaRuntimeManager(temp.resolve("runtimes")), new LaunchArguments(), temp.resolve("logs"));
        var instance = new GameInstance("local-test", "Local fixture", "local-test", LoaderType.VANILLA, null, IsolationOverride.ISOLATED,
            5000, Path.of(System.getProperty("java.home"), "bin/java.exe").toString());
        Account account = Account.offline("Steve");
        var running = launch.execute(SettingsDefaults.create(root.toString(), IsolationMode.ALL, DownloadSource.AUTO, "", visibility), instance, account, task);
        try {
            assertTrue(running.process().waitFor(15, TimeUnit.SECONDS));
            assertEquals(0, running.process().exitValue());
            String received = Files.readString(root.resolve("versions/Local fixture/received.txt"));
            assertFalse(Files.exists(root.resolve("versions/local-test")));
            assertTrue(received.contains("--username\nSteve"));
            assertTrue(received.contains("--uuid\n" + account.uuid()));
            assertTrue(received.contains("--accessToken\n0"));
            assertTrue(received.contains("--userType\nlegacy"));
            String output = "";
            for (int attempt = 0; attempt < 30 && !output.contains("log-format-probe"); attempt++) {
                if (Files.exists(running.log())) {
                    output = Files.readString(running.log());
                }
                if (output.contains("log-format-probe")) {
                    break;
                }
                Thread.sleep(50);
            }
            assertTrue(output.contains("offline-probe-0"));
            assertTrue(output.contains("[main/WARN]: log-format-probe"));
            assertFalse(output.contains("log4j:"));
        } finally {
            running.process().destroyForcibly();
        }
    }
}
