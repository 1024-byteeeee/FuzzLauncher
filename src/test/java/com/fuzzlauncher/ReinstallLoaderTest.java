package com.fuzzlauncher;

import static org.junit.jupiter.api.Assertions.*;

import com.fuzzlauncher.application.*;
import com.fuzzlauncher.domain.*;
import com.fuzzlauncher.infrastructure.*;
import com.fuzzlauncher.infrastructure.game.*;
import com.google.gson.*;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class ReinstallLoaderTest {
  @TempDir Path temp;
  Path root;
  JsonInstanceRepository repository;
  GameInstance original;
  AtomicInteger installs = new AtomicInteger();
  TaskContext task =
      new TaskContext() {
        public boolean cancelled() {
          return false;
        }

        public void report(String message, double progress) {}
      };

  @BeforeEach
  void setup() throws Exception {
    root = Files.createDirectory(temp.resolve("game"));
    repository = new JsonInstanceRepository(temp.resolve("state"));
    original =
        new GameInstance(
                "fixture",
                "My Instance",
                "base",
                LoaderType.FABRIC,
                "1",
                IsolationOverride.ISOLATED,
                8192,
                "C:\\custom\\java.exe")
            .withSettings(
                "My Instance", IsolationOverride.ISOLATED, 1024, 8192, "C:\\custom\\java.exe");
    JsonObject customized = JsonFiles.GSON.toJsonTree(original).getAsJsonObject();
    customized.add("jvmArguments", JsonParser.parseString("[\"-Dcustom.option=preserved\"]"));
    customized.add("gameArguments", JsonParser.parseString("[\"--width\",\"1024\"]"));
    original = JsonFiles.GSON.fromJson(customized, GameInstance.class);
    repository.save(original);
    JsonObject vanilla =
        JsonParser.parseString(
                """
                {"id":"base","mainClass":"Vanilla","libraries":[{"name":"vanilla:base:1",
                  "downloads":{"artifact":{"path":"base/base.jar","url":""}}}],
                  "arguments":{"jvm":["vanilla-argument"]}}
                """)
            .getAsJsonObject();
    JsonFiles.write(root.resolve("versions/base/base.json"), vanilla);
    Files.writeString(root.resolve("versions/base/base.jar"), "vanilla client");
    write(root.resolve("libraries/base/base.jar"), "base dependency");
    write(root.resolve("libraries/loader/1/main.jar"), "old loader");
    JsonObject old = profile("1");
    old.add("fuzzInstance", JsonFiles.GSON.toJsonTree(original));
    JsonFiles.write(InstanceDirectories.profile(root, original), old);
    for (String file :
        List.of("saves/world/level.dat", "mods/my-mod.jar", "options.txt", "notes.json"))
      write(InstanceDirectories.directory(root, original).resolve(file), "user data " + file);
  }

  private static void write(Path path, String text) throws IOException {
    Files.createDirectories(path.getParent());
    Files.writeString(path, text);
  }

  private JsonObject profile(String version) {
    JsonObject profile = new JsonObject();
    profile.addProperty("id", "fixture");
    profile.addProperty("inheritsFrom", "base");
    profile.addProperty("mainClass", "Loader" + version);
    JsonArray libraries = new JsonArray();
    JsonObject library = new JsonObject();
    library.addProperty("name", "test:loader:" + version);
    JsonObject artifact = new JsonObject();
    artifact.addProperty("path", "loader/" + version + "/main.jar");
    artifact.addProperty("url", "");
    JsonObject downloads = new JsonObject();
    downloads.add("artifact", artifact);
    library.add("downloads", downloads);
    libraries.add(library);
    profile.add("libraries", libraries);
    profile.add("arguments", JsonParser.parseString("{\"jvm\":[\"loader-" + version + "\"]}"));
    return profile;
  }

  private ReinstallLoader operation(InstanceRepository state, LoaderInstaller installer) {
    return new ReinstallLoader(
        state,
        new GameFiles(new HttpTransport()),
        new VersionResolver(),
        Map.of(LoaderType.FABRIC, installer));
  }

  private LoaderInstaller installer() {
    return (fresh, workspace, game, version, context) -> {
      installs.incrementAndGet();
      assertFalse(fresh.equals(root));
      assertFalse(
          Files.exists(fresh.resolve("libraries/loader/1/main.jar")),
          "Never seed old loader outputs");
      assertFalse(
          Files.exists(fresh.resolve("libraries/loader/2/main.jar")),
          "Never reuse even a target-version cache");
      assertEquals("base dependency", Files.readString(fresh.resolve("libraries/base/base.jar")));
      write(fresh.resolve("libraries/helper/generated.jar"), "fresh helper");
      write(fresh.resolve("libraries/loader/" + version + "/main.jar"), "fresh loader " + version);
      return profile(version);
    };
  }

  @Test
  void upgradeAndDowngradeAlwaysReinstallAndPreserveUserDataAndSettings() throws Exception {
    write(root.resolve("libraries/loader/2/main.jar"), "stale target cache");
    GameInstance upgraded = operation(repository, installer()).execute(root, original, "2", task);
    assertEquals("2", upgraded.loaderVersion());
    assertEquals("fresh loader 2", Files.readString(root.resolve("libraries/loader/2/main.jar")));
    assertFalse(Files.exists(root.resolve("libraries/loader/1/main.jar")));
    JsonObject expected = JsonFiles.GSON.toJsonTree(original).getAsJsonObject();
    expected.addProperty("loaderVersion", "2");
    assertEquals(expected, JsonFiles.GSON.toJsonTree(upgraded));
    assertUserData();
    JsonObject resolved = new VersionResolver().resolveInstance(root, upgraded);
    assertFalse(resolved.toString().contains("loader-1"));
    assertTrue(resolved.toString().contains("loader-2"));
    GameInstance downgraded = operation(repository, installer()).execute(root, upgraded, "1", task);
    assertEquals("1", downgraded.loaderVersion());
    assertEquals("fresh loader 1", Files.readString(root.resolve("libraries/loader/1/main.jar")));
    assertFalse(Files.exists(root.resolve("libraries/loader/2/main.jar")));
    assertEquals(2, installs.get());
    assertUserData();
    assertFalse(Files.exists(root.resolve(".fuzz-staging")));
  }

  @Test
  void cancellationAndInstallerFailurePreserveOldLoader() throws Exception {
    byte[] before = Files.readAllBytes(InstanceDirectories.profile(root, original));
    LoaderInstaller failed =
        (fresh, workspace, game, version, context) -> {
          write(fresh.resolve("libraries/partial.jar"), "partial");
          throw new IOException("fixture installation failed");
        };
    assertThrows(
        IOException.class, () -> operation(repository, failed).execute(root, original, "2", task));
    AtomicBoolean cancelled = new AtomicBoolean();
    TaskContext cancellation =
        new TaskContext() {
          public boolean cancelled() {
            return cancelled.get();
          }

          public void report(String message, double progress) {}
        };
    LoaderInstaller cancel =
        (fresh, workspace, game, version, context) -> {
          var result = installer().install(fresh, workspace, game, version, context);
          cancelled.set(true);
          return result;
        };
    assertThrows(
        CancellationException.class,
        () -> operation(repository, cancel).execute(root, original, "2", cancellation));
    assertArrayEquals(before, Files.readAllBytes(InstanceDirectories.profile(root, original)));
    assertEquals(List.of(original), repository.findAll());
    assertEquals("old loader", Files.readString(root.resolve("libraries/loader/1/main.jar")));
    assertFalse(Files.exists(root.resolve("libraries/partial.jar")));
    assertFalse(Files.exists(root.resolve(".fuzz-staging")));
    assertUserData();
  }

  @Test
  void repositoryFailureRollsBackPublishedArtifactsAndDescriptor() throws Exception {
    write(root.resolve("libraries/loader/2/main.jar"), "cached before failure");
    byte[] before = Files.readAllBytes(InstanceDirectories.profile(root, original));
    InstanceRepository failing =
        new InstanceRepository() {
          public List<GameInstance> findAll() throws IOException {
            return repository.findAll();
          }

          public void remove(String id) throws IOException {
            repository.remove(id);
          }

          public void save(GameInstance value) throws IOException {
            if (value.loaderVersion().equals("2")) throw new IOException("fixture disk failure");
            repository.save(value);
          }
        };
    assertThrows(
        IOException.class,
        () -> operation(failing, installer()).execute(root, original, "2", task));
    assertArrayEquals(before, Files.readAllBytes(InstanceDirectories.profile(root, original)));
    assertEquals(
        "cached before failure", Files.readString(root.resolve("libraries/loader/2/main.jar")));
    assertFalse(Files.exists(root.resolve("libraries/helper/generated.jar")));
    assertEquals(List.of(original), repository.findAll());
    assertUserData();
  }

  @Test
  void sharedArtifactsAreRetainedAndConflictingBytesAreNeverOverwritten() throws Exception {
    Path other = root.resolve("versions/Other/.fuzz-instance.json");
    JsonFiles.write(other, profile("1"));
    var upgraded = operation(repository, installer()).execute(root, original, "2", task);
    assertTrue(
        Files.exists(root.resolve("libraries/loader/1/main.jar")),
        "Another profile still references old loader");
    JsonFiles.write(other, profile("3"));
    write(root.resolve("libraries/loader/3/main.jar"), "another instance dependency");
    byte[] before = Files.readAllBytes(InstanceDirectories.profile(root, upgraded));
    assertThrows(
        IOException.class,
        () -> operation(repository, installer()).execute(root, upgraded, "3", task));
    assertEquals(
        "another instance dependency",
        Files.readString(root.resolve("libraries/loader/3/main.jar")));
    assertArrayEquals(before, Files.readAllBytes(InstanceDirectories.profile(root, upgraded)));
    assertEquals(List.of(upgraded), repository.findAll());
  }

  private void assertUserData() throws IOException {
    for (String file :
        List.of("saves/world/level.dat", "mods/my-mod.jar", "options.txt", "notes.json"))
      assertEquals(
          "user data " + file,
          Files.readString(InstanceDirectories.directory(root, original).resolve(file)));
    assertEquals("vanilla client", Files.readString(root.resolve("versions/base/base.jar")));
  }

  @Test
  void vanillaAndBlankVersionAreRejectedWithoutStartingInstaller() {
    GameInstance vanilla =
        new GameInstance(
            "plain", "Plain", "base", LoaderType.VANILLA, null, IsolationOverride.ISOLATED, 4096);
    assertThrows(
        IOException.class,
        () -> operation(repository, installer()).execute(root, vanilla, "2", task));
    assertThrows(
        IOException.class,
        () -> operation(repository, installer()).execute(root, original, "", task));
    assertEquals(0, installs.get());
  }

  @Test
  void rollbackFailureKeepsRecoveryFilesInsteadOfDeletingTheOnlyBackups() throws Exception {
    InstanceRepository failing =
        new InstanceRepository() {
          public List<GameInstance> findAll() throws IOException {
            return repository.findAll();
          }

          public void remove(String id) throws IOException {
            repository.remove(id);
          }

          public void save(GameInstance value) throws IOException {
            throw new IOException("fixture locked registry");
          }
        };
    IOException error =
        assertThrows(
            IOException.class,
            () -> operation(failing, installer()).execute(root, original, "2", task));
    assertTrue(error.getMessage().contains("备份目录"));
    try (var paths = Files.walk(root.resolve(".fuzz-staging"))) {
      assertTrue(
          paths.anyMatch(path -> path.getFileName().toString().equals("original-instances.json")));
    }
    assertEquals(List.of(original), repository.findAll());
    assertEquals(
        "1",
        JsonFiles.read(InstanceDirectories.profile(root, original))
            .getAsJsonObject("fuzzInstance")
            .get("loaderVersion")
            .getAsString());
    assertUserData();
  }
}
