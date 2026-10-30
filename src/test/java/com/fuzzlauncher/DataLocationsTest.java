package com.fuzzlauncher;

import com.fuzzlauncher.domain.*;
import com.fuzzlauncher.infrastructure.*;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.*;

import static org.junit.jupiter.api.Assertions.*;

class DataLocationsTest {
    @TempDir
    Path temp;

    @Test
    void unconfiguredDataDefaultsBesideExecutableAndExplicitOverrideIsPreserved() throws Exception {
        Path launcher = Files.createDirectories(temp.resolve("launcher"));
        Path bootstrap = temp.resolve("bootstrap");
        assertEquals(launcher.resolve("data"), DataLocations.resolve(bootstrap, launcher));
        assertFalse(Files.exists(bootstrap));
        String previousExecutable = System.getProperty("jpackage.app-path");
        String previousOverride = System.getProperty("fuzz.dataDir");
        try {
            System.setProperty("jpackage.app-path", launcher.resolve("FuzzLauncher.exe").toString());
            Path override = temp.resolve("override");
            System.setProperty("fuzz.dataDir", override.toString());
            assertEquals(override, DataLocations.resolve());
        } finally {
            if (previousExecutable == null) {
                System.clearProperty("jpackage.app-path");
            } else {
                System.setProperty("jpackage.app-path", previousExecutable);
            }
            if (previousOverride == null) {
                System.clearProperty("fuzz.dataDir");
            } else {
                System.setProperty("fuzz.dataDir", previousOverride);
            }
        }
    }

    @Test
    void localSettingsAndExistingDirectorySelectionsRemainReadable() throws Exception {
        Path launcher = Files.createDirectories(temp.resolve("launcher"));
        Path bootstrap = Files.createDirectories(temp.resolve("bootstrap"));
        var legacySettings = new LauncherSettings(temp.resolve("legacy-game").toString(), IsolationMode.ALL);
        new SettingsStore(bootstrap).save(legacySettings);
        assertEquals(bootstrap, DataLocations.resolve(bootstrap, launcher));
        Path local = Files.createDirectories(launcher.resolve("data"));
        var localSettings = new LauncherSettings(launcher.resolve("game").toString(), IsolationMode.ALL);
        new SettingsStore(local).save(localSettings);
        assertEquals(local, DataLocations.resolve(bootstrap, launcher));
        assertEquals(localSettings, new SettingsStore(local).load());
        Path selected = temp.resolve("selected-data");
        DataLocations.configure(bootstrap, local, selected);
        assertEquals(selected, DataLocations.resolve(bootstrap, launcher));
        assertEquals(localSettings, new SettingsStore(selected).load());
        assertEquals(selected, DataLocations.resolve(bootstrap, launcher));
        assertEquals(legacySettings, new SettingsStore(bootstrap).load());
    }

    @Test
    void restartMigrationCopiesStateAndLogsWithoutMovingGamesOrRemovingOriginalData() throws Exception {
        Path source = Files.createDirectories(temp.resolve("old")), target = temp.resolve("new"), bootstrap = temp.resolve("bootstrap");
        Path game = Files.createDirectories(source.resolve(".minecraft/saves"));
        Files.writeString(game.resolve("world.dat"), "world");
        new SettingsStore(source)
            .save(new LauncherSettings(source.resolve(".minecraft").toString(), IsolationMode.ALL, DownloadSource.AUTO, target.toString()));
        Files.writeString(source.resolve("instances.json"), "{\"instances\":[]}");
        Files.writeString(source.resolve("account.json"), "{}");
        Files.write(source.resolve("credentials.dpapi"), new byte[] { 1, 2, 3 });
        Files.createDirectories(source.resolve("logs"));
        Files.writeString(source.resolve("logs/launcher.log"), "history");
        Files.createDirectories(source.resolve("skins"));
        Files.writeString(source.resolve("skins/fixture.png"), "cached avatar fixture");
        DataLocations.configure(bootstrap, source, target);
        assertFalse(Files.exists(target));
        assertEquals(target, DataLocations.resolve(bootstrap));
        assertEquals(source.resolve(".minecraft").toString(), new SettingsStore(target).load().gameRoot());
        assertArrayEquals(new byte[] { 1, 2, 3 }, Files.readAllBytes(target.resolve("credentials.dpapi")));
        assertEquals("history", Files.readString(target.resolve("logs/launcher.log")));
        assertEquals("cached avatar fixture", Files.readString(target.resolve("skins/fixture.png")));
        assertTrue(Files.exists(source.resolve("settings.json")));
        assertTrue(Files.exists(game.resolve("world.dat")));
        assertFalse(Files.exists(target.resolve(".minecraft")));
        Files.writeString(source.resolve("logs/launcher.log"), "later old data");
        DataLocations.resolve(bootstrap);
        assertEquals("history", Files.readString(target.resolve("logs/launcher.log")),
            "Resolved pointer must not overwrite active data again");
    }

    @Test
    void occupiedTargetAndRunningSourceAreRejectedAndPendingChangeCanBeCancelled() throws Exception {
        Path source = Files.createDirectories(temp.resolve("old")), target = temp.resolve("new"), bootstrap = temp.resolve("bootstrap");
        Path occupied = Files.createDirectories(temp.resolve("occupied"));
        Files.writeString(occupied.resolve("keep"), "keep");
        assertThrows(IOException.class, () -> DataLocations.configure(bootstrap, source, occupied));
        assertThrows(IOException.class, () -> DataLocations.configure(bootstrap, source, source.resolve("skins/nested")));
        DataLocations.configure(bootstrap, source, target);
        try (var channel = FileChannel.open(source.resolve("launcher.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
            var lock = channel.lock()) {
            assertThrows(IOException.class, () -> DataLocations.resolve(bootstrap));
            assertFalse(Files.exists(target));
        }
        DataLocations.configure(bootstrap, source, source);
        assertEquals(source, DataLocations.resolve(bootstrap));
        assertFalse(Files.exists(target));
        assertEquals("keep", Files.readString(occupied.resolve("keep")));
    }
}
