package com.fuzzlauncher;

import com.fuzzlauncher.domain.*;
import com.fuzzlauncher.infrastructure.*;
import com.fuzzlauncher.infrastructure.game.DetachedGame;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class LauncherVisibilityTest {
    @TempDir
    Path temp;

    @Test
    void policiesPersistAndMissingPolicyKeepsExistingBehavior() throws Exception {
        SettingsStore store = new SettingsStore(temp);
        assertEquals(LauncherVisibility.KEEP, store.load().launcherVisibility());
        for (LauncherVisibility policy : LauncherVisibility.values()) {
            var settings = new LauncherSettings(temp.toString(), IsolationMode.ALL, DownloadSource.AUTO, "", policy);
            store.save(settings);
            assertEquals(settings, store.load());
        }
        assertEquals(LauncherVisibility.KEEP,
            JsonFiles.GSON.fromJson("{\"gameRoot\":\"game\",\"isolation\":\"ALL\"}", LauncherSettings.class).launcherVisibility());
    }

    @Test
    void detachedLoggingSurvivesParentExitAndRedactsSecrets() throws Exception {
        Path log = temp.resolve("game.log");
        String classes = Path.of(LauncherVisibilityTest.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
        String main = Path.of(DetachedGame.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
        String executable = Path.of(System.getProperty("java.home"), "bin", "java.exe").toString();
        Process parent = new ProcessBuilder(executable, "-cp", main + java.io.File.pathSeparator + classes, Parent.class.getName(),
            executable, classes, temp.toString(), log.toString()).start();
        try {
            assertTrue(parent.waitFor(10, TimeUnit.SECONDS));
            assertEquals(0, parent.exitValue(), new String(parent.getErrorStream().readAllBytes()));
            long relayPid = Long.parseLong(parent.inputReader().readLine());
            ProcessHandle relay = ProcessHandle.of(relayPid).orElse(null);
            try {
                if (relay != null) {
                    relay.onExit().get(15, TimeUnit.SECONDS);
                }
                String output = Files.readString(log);
                assertTrue(output.contains("after-parent-exit [REDACTED]"));
                assertTrue(output.contains("stderr [REDACTED]"));
                assertFalse(output.contains("private-token"));
            } finally {
                if (relay != null && relay.isAlive()) {
                    relay.destroyForcibly();
                }
            }
        } finally {
            parent.destroyForcibly();
        }
        assertThrows(java.io.IOException.class,
            () -> DetachedGame.start(List.of(temp.resolve("missing.exe").toString()), temp, temp.resolve("failed.log"), ""));
        Process failedGame = DetachedGame.start(List.of(executable, "-cp", classes, Fixture.class.getName(), "0", "7"), temp,
            temp.resolve("nonzero.log"), "private-token");
        try {
            assertTrue(failedGame.waitFor(10, TimeUnit.SECONDS));
            assertEquals(7, failedGame.exitValue(), "Relay must preserve the game's exit status");
        } finally {
            failedGame.destroyForcibly();
        }
    }

    public static class Parent {
        public static void main(String[] args) throws Exception {
            Process relay = DetachedGame.start(
                List.of(args[0], "-cp", args[1], Fixture.class.getName(), Long.toString(ProcessHandle.current().pid())), Path.of(args[2]),
                Path.of(args[3]), "private-token");
            System.out.println(relay.pid());
            System.out.flush();
            // Exit with the relay and game still alive, just like the launcher EXIT policy.
            System.exit(0);
        }
    }

    public static class Fixture {
        public static void main(String[] args) throws Exception {
            long parent = Long.parseLong(args[0]);
            while (parent != 0 && ProcessHandle.of(parent).map(ProcessHandle::isAlive).orElse(false)) {
                Thread.sleep(10);
            }
            System.out.println("after-parent-exit private-token");
            System.err.println("stderr private-token");
            if (args.length > 1) {
                System.exit(Integer.parseInt(args[1]));
            }
        }
    }
}
