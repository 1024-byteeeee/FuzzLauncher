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
