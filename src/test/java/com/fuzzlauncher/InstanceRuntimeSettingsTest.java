package com.fuzzlauncher;

import static org.junit.jupiter.api.Assertions.*;

import com.fuzzlauncher.application.TaskContext;
import com.fuzzlauncher.domain.*;
import com.fuzzlauncher.infrastructure.*;
import com.fuzzlauncher.infrastructure.game.*;
import java.io.IOException;
import java.nio.file.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class InstanceRuntimeSettingsTest {
  @TempDir Path temp;
  private final TaskContext task =
      new TaskContext() {
        public void report(String message, double progress) {}

        public boolean cancelled() {
          return false;
        }
      };

  @Test
  void customJavaAndTypedMemoryPersistPerInstanceAndOldFilesRemainCompatible() throws Exception {
    GameInstance old =
        JsonFiles.GSON.fromJson(
            """
{"id":"old","name":"Old","gameVersion":"1.20.4","loader":"VANILLA","isolation":"INHERIT","memoryMb":4096}
""",
            GameInstance.class);
    assertEquals("", old.javaPath());
    assertEquals(256, old.minMemoryMb());
    assertEquals(4096, old.memoryMb());
    var repo = new JsonInstanceRepository(temp);
    GameInstance customized =
        old.withSettings("Custom", old.isolation(), 5000, "C:/My Java/bin/java.exe");
    repo.save(customized);
    repo.save(
        new GameInstance(
            "other", "Other", "1.20.4", LoaderType.VANILLA, null, IsolationOverride.INHERIT, 2048));
    assertEquals(customized, repo.findAll().getFirst());
    assertEquals(2048, repo.findAll().get(1).memoryMb());
    assertEquals("", repo.findAll().get(1).javaPath());
    assertEquals(
        customized.javaPath(),
        customized.withSettings("Renamed", old.isolation(), 6000).javaPath());
    assertThrows(
        IllegalArgumentException.class, () -> old.withSettings("Bad", old.isolation(), 100));
  }

  @Test
  void minimumAndMaximumMemoryPersistAndBecomeJvmArguments() throws Exception {
    GameInstance instance =
        new GameInstance(
                "heap", "Heap", "1.20.4", LoaderType.VANILLA, null, IsolationOverride.INHERIT, 4096)
            .withSettings("Heap", IsolationOverride.INHERIT, 1024, 6000, "");
    var repo = new JsonInstanceRepository(temp);
    repo.save(instance);
    GameInstance loaded = repo.findAll().getFirst();
    assertEquals(1024, loaded.minMemoryMb());
    assertEquals(6000, loaded.memoryMb());
    assertEquals(1024, loaded.withSettings("Renamed", loaded.isolation(), 7000).minMemoryMb());
    var version =
        com.google.gson.JsonParser.parseString(
                "{\"_clientVersion\":\"1.20.4\",\"mainClass\":\"Main\",\"minecraftArguments\":\"--username"
                    + " ${auth_player_name}\"}")
            .getAsJsonObject();
    var args =
        new LaunchArguments()
            .build(
                temp,
                temp,
                temp.resolve("natives"),
                loaded,
                GameSession.offline(Account.offline("Steve")),
                version,
                java.util.List.of());
    assertTrue(args.contains("-Xms1024M"));
    assertTrue(args.contains("-Xmx6000M"));
    assertFalse(args.contains("-Xms256M"));
    assertThrows(
        IllegalArgumentException.class,
        () -> loaded.withSettings("Bad", loaded.isolation(), 7000, 6000, ""));
    assertThrows(
        IllegalArgumentException.class,
        () -> loaded.withSettings("Bad", loaded.isolation(), 0, 6000, ""));
    assertThrows(
        IllegalArgumentException.class,
        () -> loaded.withSettings("Bad", loaded.isolation(), 256, 70000, ""));
  }

  @Test
  void explicitJavaUsesSelectedExecutableAndRejectsMissingOrOlderRuntime() throws Exception {
    Path home = temp.resolve("Java with spaces");
    Files.createDirectories(home.resolve("bin"));
    Path executable = Files.createFile(home.resolve("bin/java.exe"));
    Files.writeString(home.resolve("release"), "JAVA_VERSION=\"21.0.4\"\n");
    var manager = new JavaRuntimeManager(new HttpTransport(), temp.resolve("auto"));
    assertEquals(executable, manager.resolve(21, home.toString(), task));
    assertEquals(executable, manager.resolve(17, "\"" + executable + "\"", task));
    assertEquals(
        executable, JavaRuntimeManager.configuredExecutable(home.resolve("bin").toString()));
    assertEquals(
        executable,
        JavaRuntimeManager.configuredExecutable(home.resolve("bin/javaw.exe").toString()));
    assertThrows(IOException.class, () -> manager.resolve(25, home.toString(), task));
    assertThrows(
        IOException.class,
        () -> manager.resolve(21, temp.resolve("missing/java.exe").toString(), task));
    assertFalse(
        Files.exists(temp.resolve("auto")),
        "Invalid explicit path must not silently download another Java");
  }
}
