package com.fuzzlauncher;

import static org.junit.jupiter.api.Assertions.*;

import com.fuzzlauncher.application.InstanceRepository;
import com.fuzzlauncher.domain.*;
import com.fuzzlauncher.infrastructure.*;
import com.fuzzlauncher.infrastructure.game.VersionResolver;
import com.google.gson.*;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class InstanceDirectoriesTest {
  @TempDir Path root;

  private GameInstance instance(String id, String name) {
    return new GameInstance(
        id, name, "base", LoaderType.VANILLA, null, IsolationOverride.ISOLATED, 4096);
  }

  private void base(String id) throws IOException {
    JsonFiles.write(
        root.resolve("versions/" + id + "/" + id + ".json"),
        JsonParser.parseString("{\"id\":\"" + id + "\",\"mainClass\":\"Main\",\"libraries\":[]}"));
    Files.writeString(root.resolve("versions/" + id + "/" + id + ".jar"), "shared-base");
  }

  private Path legacy(GameInstance instance) throws IOException {
    Path folder = root.resolve("versions").resolve(instance.id());
    JsonObject profile = new JsonObject();
    profile.addProperty("id", instance.id());
    profile.addProperty("inheritsFrom", "base");
    JsonFiles.write(folder.resolve(instance.id() + ".json"), profile);
    Files.createDirectories(folder.resolve("saves/world"));
    Files.writeString(folder.resolve("saves/world/level.dat"), "saved-world");
    Files.createDirectories(folder.resolve("mods"));
    Files.writeString(folder.resolve("mods/example.jar"), "mod-data");
    Files.writeString(folder.resolve("options.txt"), "settings");
    return folder;
  }

  @Test
  void legacyUuidFolderMigratesToDisplayNameWithAllDataAndStableIdentity() throws Exception {
    base("base");
    GameInstance item = instance("fuzz-123", "中文 整合包");
    Path old = legacy(item);
    InstanceDirectories.migrate(root, item);
    Path folder = root.resolve("versions/中文 整合包");
    assertFalse(Files.exists(old));
    assertEquals("saved-world", Files.readString(folder.resolve("saves/world/level.dat")));
    assertEquals("mod-data", Files.readString(folder.resolve("mods/example.jar")));
    assertEquals("settings", Files.readString(folder.resolve("options.txt")));
    assertEquals(
        "fuzz-123",
        JsonFiles.read(folder.resolve(InstanceDirectories.PROFILE)).get("id").getAsString());
    assertEquals(
        "base",
        new VersionResolver().resolveInstance(root, item).get("_clientVersion").getAsString());
    InstanceDirectories.migrate(root, item);
    assertTrue(Files.exists(folder.resolve("saves/world/level.dat")));
  }

  @Test
  void instanceCanUseBaseVersionNameAndRenameWithoutMovingSharedBaseFiles() throws Exception {
    base("base");
    GameInstance item = instance("fuzz-123", "base");
    legacy(item);
    InstanceDirectories.migrate(root, item);
    var repository = new JsonInstanceRepository(root.resolve("state"));
    repository.save(item);
    GameInstance renamed = item.withSettings("生存 世界", item.isolation(), 5000);
    InstanceDirectories.save(root, item, renamed, repository);
    assertTrue(Files.exists(root.resolve("versions/base/base.json")));
    assertEquals("shared-base", Files.readString(root.resolve("versions/base/base.jar")));
    assertFalse(Files.exists(root.resolve("versions/base/saves")));
    assertEquals(
        "saved-world", Files.readString(root.resolve("versions/生存 世界/saves/world/level.dat")));
    assertEquals(renamed, repository.findAll().getFirst());
    assertEquals(
        "base",
        new VersionResolver().resolveInstance(root, renamed).get("_clientVersion").getAsString());
  }

  @Test
  void renameRollsBackFolderAndDataIfSavingRegistryFails() throws Exception {
    base("base");
    GameInstance item = instance("fuzz-1", "Old");
    legacy(item);
    InstanceDirectories.migrate(root, item);
    InstanceRepository failing =
        new InstanceRepository() {
          public List<GameInstance> findAll() {
            return List.of(item);
          }

          public void save(GameInstance ignored) throws IOException {
            throw new IOException("fixture write failure");
          }

          public void remove(String id) {}
        };
    assertThrows(
        IOException.class,
        () ->
            InstanceDirectories.save(
                root, item, item.withSettings("New", item.isolation(), 4096), failing));
    assertTrue(Files.exists(root.resolve("versions/Old/saves/world/level.dat")));
    assertFalse(Files.exists(root.resolve("versions/New")));
  }

  @Test
  void existingDataAndDuplicateNamesAreNeverOverwritten() throws Exception {
    GameInstance item = instance("fuzz-1", "Taken");
    Path old = legacy(item);
    Path existing = Files.createDirectories(root.resolve("versions/Taken/saves"));
    Files.writeString(existing.resolve("keep.dat"), "keep");
    assertThrows(IOException.class, () -> InstanceDirectories.migrate(root, item));
    assertTrue(Files.exists(old.resolve("saves/world/level.dat")));
    assertEquals("keep", Files.readString(existing.resolve("keep.dat")));
    var repo = new JsonInstanceRepository(root.resolve("state"));
    repo.save(item);
    assertThrows(IOException.class, () -> repo.save(instance("fuzz-2", "taken")));
    assertThrows(
        IOException.class,
        () ->
            InstanceDirectories.checkAvailable(root, instance("fuzz-2", "TAKEN"), repo.findAll()));
  }

  @Test
  void illegalWindowsNamesCannotEscapeTheVersionsDirectory() {
    for (String name :
        new String[] {
          "../escape", "a/b", "a\\b", "CON", "nul.txt", "COM1", "bad:", "ends.", "a\n\nb", ".."
        }) assertThrows(IllegalArgumentException.class, () -> instance("id", name), name);
    assertEquals(
        root.resolve("versions/整合包 1"),
        new IsolationPolicy().gameDirectory(root, instance("id", "整合包 1"), IsolationMode.ALL));
    assertNotEquals(
        new IsolationPolicy().gameDirectory(root, instance("a", "One"), IsolationMode.ALL),
        new IsolationPolicy().gameDirectory(root, instance("b", "Two"), IsolationMode.ALL));
  }

  @Test
  void changingOnlyNameCaseUpdatesTheFolderName() throws Exception {
    base("base");
    GameInstance item = instance("fuzz-1", "Pack");
    legacy(item);
    InstanceDirectories.migrate(root, item);
    var repo = new JsonInstanceRepository(root.resolve("state"));
    repo.save(item);
    GameInstance updated = item.withSettings("pack", item.isolation(), 4096);
    InstanceDirectories.save(root, item, updated, repo);
    try (var folders = Files.list(root.resolve("versions"))) {
      assertTrue(folders.anyMatch(path -> path.getFileName().toString().equals("pack")));
    }
    assertEquals(
        "saved-world", Files.readString(root.resolve("versions/pack/saves/world/level.dat")));
  }

  @Test
  void conflictFromOldDuplicateNamesCanBeResolvedByRenamingTheLegacyInstance() throws Exception {
    base("base");
    GameInstance first = instance("fuzz-1", "Pack"), second = instance("fuzz-2", "Pack");
    legacy(first);
    legacy(second);
    InstanceDirectories.migrate(root, first);
    InstanceRepository oldRegistry =
        new InstanceRepository() {
          private List<GameInstance> entries = List.of(first, second);

          public List<GameInstance> findAll() {
            return entries;
          }

          public void save(GameInstance value) {
            entries = List.of(first, value);
          }

          public void remove(String id) {}
        };
    assertThrows(IOException.class, () -> InstanceDirectories.migrate(root, second));
    GameInstance updated = second.withSettings("Second pack", second.isolation(), 4096);
    InstanceDirectories.save(root, second, updated, oldRegistry);
    assertTrue(Files.exists(root.resolve("versions/Pack/saves/world/level.dat")));
    assertTrue(Files.exists(root.resolve("versions/Second pack/saves/world/level.dat")));
    assertFalse(Files.exists(root.resolve("versions/fuzz-2")));
  }
}
