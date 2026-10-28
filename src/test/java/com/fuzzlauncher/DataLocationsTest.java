package com.fuzzlauncher;

import static org.junit.jupiter.api.Assertions.*;

import com.fuzzlauncher.domain.*;
import com.fuzzlauncher.infrastructure.*;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DataLocationsTest {
  @TempDir Path temp;

  @Test
  void restartMigrationCopiesStateAndLogsWithoutMovingGamesOrRemovingOriginalData()
      throws Exception {
    Path source = Files.createDirectories(temp.resolve("old")),
        target = temp.resolve("new"),
        bootstrap = temp.resolve("bootstrap");
    Path game = Files.createDirectories(source.resolve(".minecraft/saves"));
    Files.writeString(game.resolve("world.dat"), "world");
    new SettingsStore(source)
        .save(
            new LauncherSettings(
                source.resolve(".minecraft").toString(),
                IsolationMode.ALL,
                DownloadSource.AUTO,
                target.toString()));
    Files.writeString(source.resolve("instances.json"), "{\"instances\":[]}");
    Files.writeString(source.resolve("account.json"), "{}");
    Files.write(source.resolve("credentials.dpapi"), new byte[] {1, 2, 3});
    Files.createDirectories(source.resolve("logs"));
    Files.writeString(source.resolve("logs/launcher.log"), "history");
    DataLocations.configure(bootstrap, source, target);
    assertFalse(Files.exists(target));
    assertEquals(target, DataLocations.resolve(bootstrap));
    assertEquals(
        source.resolve(".minecraft").toString(), new SettingsStore(target).load().gameRoot());
    assertArrayEquals(
        new byte[] {1, 2, 3}, Files.readAllBytes(target.resolve("credentials.dpapi")));
    assertEquals("history", Files.readString(target.resolve("logs/launcher.log")));
    assertTrue(Files.exists(source.resolve("settings.json")));
    assertTrue(Files.exists(game.resolve("world.dat")));
    assertFalse(Files.exists(target.resolve(".minecraft")));
    Files.writeString(source.resolve("logs/launcher.log"), "later old data");
    DataLocations.resolve(bootstrap);
    assertEquals(
        "history",
        Files.readString(target.resolve("logs/launcher.log")),
        "Resolved pointer must not overwrite active data again");
  }

  @Test
  void occupiedTargetAndRunningSourceAreRejectedAndPendingChangeCanBeCancelled() throws Exception {
    Path source = Files.createDirectories(temp.resolve("old")),
        target = temp.resolve("new"),
        bootstrap = temp.resolve("bootstrap");
    Path occupied = Files.createDirectories(temp.resolve("occupied"));
    Files.writeString(occupied.resolve("keep"), "keep");
    assertThrows(IOException.class, () -> DataLocations.configure(bootstrap, source, occupied));
    DataLocations.configure(bootstrap, source, target);
    try (var channel =
            FileChannel.open(
                source.resolve("launcher.lock"),
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE);
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
