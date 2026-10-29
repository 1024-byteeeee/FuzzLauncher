package com.fuzzlauncher;

import static org.junit.jupiter.api.Assertions.*;

import com.fuzzlauncher.application.*;
import com.fuzzlauncher.domain.*;
import com.fuzzlauncher.infrastructure.*;
import com.fuzzlauncher.infrastructure.game.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/** Opt-in network integration test. Never signs in or launches using a synthetic account. */
@EnabledIfSystemProperty(named = "fuzz.integration", matches = "true")
class OfficialInstallTest {
  @Test
  void officialVanillaAndLoaderInstallations() throws Exception {
    Path directory = Path.of("build/integration").toAbsolutePath();
    Files.createDirectories(directory);
    HttpTransport http = new HttpTransport();
    VersionCatalog catalog = new VersionCatalog(http);
    VersionResolver resolver = new VersionResolver();
    GameFiles files = new GameFiles(http);
    JavaRuntimeManager runtimes = new JavaRuntimeManager(http, directory.resolve("runtimes"));
    var repo = new JsonInstanceRepository(directory);
    InstallGame install =
        new InstallGame(
            repo,
            files,
            resolver,
            Map.of(
                LoaderType.FABRIC,
                new FabricInstaller(http),
                LoaderType.FORGE,
                new OfficialLoaderInstaller(LoaderType.FORGE, http, runtimes, resolver),
                LoaderType.NEOFORGE,
                new OfficialLoaderInstaller(LoaderType.NEOFORGE, http, runtimes, resolver)));
    TaskContext context =
        new TaskContext() {
          private String previous = "";

          public synchronized void report(String message, double progress) {
            if (progress < 0 || progress == 1) {
              if (!message.equals(previous)) System.out.println(message);
              previous = message;
            }
          }

          public boolean cancelled() {
            return false;
          }
        };
    GameVersion game =
        catalog.games().stream().filter(v -> v.id().equals("1.20.4")).findFirst().orElseThrow();
    String requested = System.getProperty("fuzz.loaders", "VANILLA,FABRIC,FORGE,NEOFORGE");
    for (String name : requested.split(",")) {
      LoaderType type = LoaderType.valueOf(name);
      if (!Boolean.getBoolean("fuzz.forceInstall")
          && repo.findAll().stream().anyMatch(i -> i.loader() == type)) {
        System.out.println("Reuse previous verified installation: " + type);
        continue;
      }
      String loaderVersion =
          type == LoaderType.VANILLA ? null : catalog.loaders(type, game.id()).getFirst();
      GameInstance instance =
          install.execute(
              directory.resolve(".minecraft"), game, type, loaderVersion, "测试 " + type, context);
      var profile = resolver.resolveInstance(directory.resolve(".minecraft"), instance);
      assertNotNull(profile.get("mainClass"));
      assertEquals("1.20.4", profile.get("_clientVersion").getAsString());
      assertTrue(Files.isRegularFile(directory.resolve(".minecraft/baseVerisons/1.20.4/1.20.4.jar")));
      for (GameFiles.Artifact artifact : files.libraries(directory.resolve(".minecraft"), profile))
        assertTrue(Files.isRegularFile(artifact.path()), artifact.path().toString());
    }
  }
}
